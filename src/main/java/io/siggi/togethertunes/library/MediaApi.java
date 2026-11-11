package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.IpKeys;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.ingest.MediaVersions;
import io.siggi.togethertunes.rooms.PlayingItems;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An item's media files ({@code docs/api.md}, {@code docs/libraries.md#media-access-rules}):
 * {@code GET /media/{itemId}/{file}} with {@code file} one of {@code audio.m4a},
 * {@code audio-only.m4a} (a video's audio-only file, for audio-only rooms), {@code mezz.mp4}, {@code original} (the kept original of an audio upload,
 * {@code items.original_file}; the originals of video and YouTube items only feed re-encodes and
 * are never served) and {@code cover.jpg}.
 *
 * <p><b>Versions</b> ({@code ingest.MediaVersions}). {@code GET /media/{itemId}/v/{N}/{file}}
 * serves version {@code N} of the item's files, as long as it still exists (an old version is kept
 * until nobody can still be playing it), so a player that started on version {@code N} stays on it
 * after a re-encode switched the item to a newer one. These are the URLs the room state and the
 * item page hand out. The unversioned URL serves the current version. {@code original} isn't
 * versioned (one per item), but is served under both.
 *
 * <ul>
 *   <li><b>Methods:</b> a {@code GET} route ({@code HEAD} runs it too; the router answers
 *       {@code OPTIONS} and refuses other methods). The handler checks the method again before
 *       touching the file. Files go out through SiggiHTTP's {@code returnFile(file, contentType)}
 *       (Range, {@code ETag}, {@code Last-Modified}, {@code 304}); never
 *       {@code handleRequestWithFile} (see CLAUDE.md).</li>
 *   <li><b>Access</b>, decided once when the request starts (a response that is already streaming
 *       isn't cut off): {@code cover.jpg} for anyone (item metadata is visible by direct link). The
 *       other files for logged-in users; for guests and anonymous visitors only if
 *       {@code guests.download} is on or the item is the current or next entry of some room
 *       ({@link PlayingItems}), else {@code 403 forbidden}. Access is decided before the item is
 *       looked up, so a refused caller learns nothing about which items exist.</li>
 *   <li><b>Not found:</b> {@code 404 item_not_found} (unknown or malformed id),
 *       {@code 404 not_found} (an unknown file name), {@code 404 file_not_found} (the item has no
 *       such file: not ingested yet, failed, purged, an audio item's {@code mezz.mp4}, no cover, or
 *       no kept original).</li>
 *   <li><b>Headers:</b> the content type by file ({@link #contentType}; the original's by its
 *       extension), {@code X-Content-Type-Options: nosniff}, and
 *       {@code Cache-Control: private, no-cache} on unversioned URLs: a re-encode changes what
 *       they serve and guest access can end, so browsers revalidate (an unchanged file costs a
 *       {@code 304}). A versioned URL never names other bytes (versions only grow), so it is
 *       {@code private, max-age=31536000, immutable}; the original under a versioned URL is
 *       revalidated like an unversioned one.</li>
 * </ul>
 *
 * <p><b>Views</b> ({@code docs/media-types.md#views-auto-delete-and-pins}, {@link Views}): a
 * {@code GET} of a file is never a view. A client that starts playing an item outside a room
 * reports it: {@code POST /api/items/{id}/view} (no body) → {@code 204}, setting
 * {@code items.last_viewed_at}. Allowed for whoever may play the item (the access rule above, for
 * its media files: {@code 403 forbidden} otherwise, decided before the item is looked up);
 * {@code 404 item_not_found}, {@code 409 item_not_ready} (not ready or purged: nothing to play).
 * Rate-limited per actor (user, guest, or an anonymous caller's IP key) and item: one view per
 * {@value #VIEW_INTERVAL_MS} ms is recorded; a report within that answers {@code 204} too but
 * writes nothing (the view a moment ago already counts, so a client pausing and resuming needn't
 * care).
 */
public final class MediaApi {
    /** A video's audio-only file (its audio trim), played only in audio-only rooms. */
    public static final String AUDIO_ONLY = "audio-only.m4a";

    /** The served file names that map 1:1 to a file in the item directory. */
    private static final Map<String, String> FIXED = Map.of(
            "audio.m4a", "audio/mp4",
            AUDIO_ONLY, "audio/mp4",
            "mezz.mp4", "video/mp4",
            "cover.jpg", "image/jpeg");

    /** The original's content type by its extension ({@code ingest.Originals#extension}). */
    private static final Map<String, String> ORIGINAL_TYPES = Map.ofEntries(
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("flac", "audio/flac"),
            Map.entry("wav", "audio/wav"),
            Map.entry("aac", "audio/aac"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("opus", "audio/ogg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("webm", "audio/webm"),
            Map.entry("mka", "audio/x-matroska"),
            Map.entry("mkv", "audio/x-matroska"),
            Map.entry("wma", "audio/x-ms-wma"),
            Map.entry("ape", "audio/x-ape"));

    /** {@code original.<ext>} as ingest names it; anything else in the column is not served. */
    private static final Pattern ORIGINAL_FILE = Pattern.compile("original\\.[a-z0-9]{1,10}");

    private static final String ORIGINAL = "original";

    /** At most one recorded view per actor and item in this many ms. */
    public static final long VIEW_INTERVAL_MS = 60_000;

    /** A version number in the URL: 0 or a positive number without leading zeros, at most 9 digits. */
    private static final Pattern VERSION = Pattern.compile("0|[1-9][0-9]{0,8}");

    /** {@code Cache-Control} of a versioned file. */
    static final String IMMUTABLE = "private, max-age=31536000, immutable";

    private final Database db;
    private final Path mediaDir;
    private final Settings settings;
    private final PlayingItems playing;
    private final Clock clock;
    private final RateLimiter views;

    public MediaApi(Database db, DataDir dataDir, Settings settings, PlayingItems playing) {
        this(db, dataDir, settings, playing, Clock.systemUTC());
    }

    public MediaApi(Database db, DataDir dataDir, Settings settings, PlayingItems playing, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.mediaDir = dataDir.media().toAbsolutePath().normalize();
        this.settings = Objects.requireNonNull(settings);
        this.playing = Objects.requireNonNull(playing);
        this.clock = Objects.requireNonNull(clock);
        this.views = new RateLimiter("views", 1, VIEW_INTERVAL_MS, clock);
    }

    public void register(Router router) {
        router.get("/media/{id}/{file}", this::handle);
        router.get("/media/{id}/v/{version}/{file}", this::handle);
        router.post("/api/items/{id}/view", this::view);
    }

    /** {@code POST /api/items/{id}/view}: see the class comment. */
    void view(Request request, Response response) throws IOException, SQLException {
        Actor actor = ActorResolver.actor(request);
        String itemId = request.pathParam("id");
        if (!allowed(actor, itemId, "audio.m4a")) {
            throw new HttpError(403, "forbidden", "Guests can only play what a room is playing");
        }
        if (!UuidV7.isValid(itemId)) {
            throw itemNotFound();
        }
        String status = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        if (status == null) {
            throw itemNotFound();
        }
        if (!"ready".equals(status)) {
            throw new HttpError(409, "item_not_ready", "The item can't be played now");
        }
        response.header("Cache-Control", "no-store");
        if (views.tryAcquire(viewKey(actor, request.clientIp()) + "|" + itemId) == 0) {
            long now = EpochMs.now(clock);
            db.write(c -> {
                Views.record(c, itemId, now);
                return null;
            });
        }
        response.empty(204);
    }

    /** Who reports a view, for the rate limit: the user, the guest, or an anonymous caller's IP key. */
    private static String viewKey(Actor actor, String clientIp) {
        return switch (actor) {
            case Actor.User u -> "u:" + u.userId();
            case Actor.Guest g -> "g:" + g.guestId();
            case Actor.Anonymous a -> {
                String ip = clientIp == null ? "" : clientIp;
                String key = IpKeys.ipKey(ip);
                yield "ip:" + (key == null ? ip : key);
            }
        };
    }

    /** The served URL of an item's file in one version (what clients are given). */
    public static String url(String itemId, int version, String file) {
        return "/media/" + itemId + "/v/" + version + "/" + file;
    }

    void handle(Request request, Response response) throws IOException, SQLException {
        String method = request.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            // Not reachable through register(), but never let another method near returnFile.
            response.header("Allow", "GET, HEAD, OPTIONS");
            throw new HttpError(405, "method_not_allowed", "Method " + method + " is not allowed here");
        }
        String itemId = request.pathParam("id");
        String name = request.pathParam("file");
        String versionParam = request.pathParams().get("version");
        if (!FIXED.containsKey(name) && !name.equals(ORIGINAL)) {
            throw HttpError.notFound();
        }
        if (versionParam != null && !VERSION.matcher(versionParam).matches()) {
            throw HttpError.notFound();
        }
        if (!allowed(ActorResolver.actor(request), itemId, name)) {
            throw new HttpError(403, "forbidden", "Guests can only play what a room is playing");
        }
        if (!UuidV7.isValid(itemId)) {
            throw itemNotFound();
        }
        Located located = locate(itemId, name);
        Path itemDir = mediaDir.resolve(itemId);
        boolean versioned = versionParam != null && !name.equals(ORIGINAL);
        Path dir = name.equals(ORIGINAL) ? itemDir
                : MediaVersions.versionDir(itemDir, versioned ? Integer.parseInt(versionParam) : located.version());
        Path file = dir.resolve(located.fileName());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new HttpError(404, "file_not_found", versioned
                    ? "The item has no " + name + " file in version " + versionParam
                    : "The item has no " + name + " file");
        }
        response.header("Cache-Control", versioned ? IMMUTABLE : "private, no-cache");
        response.header("X-Content-Type-Options", "nosniff");
        response.raw().returnFile(file.toFile(), contentType(located.fileName()));
    }

    /** The access rule for {@code actor} (see the class comment). */
    boolean allowed(Actor actor, String itemId, String name) throws SQLException {
        if (name.equals("cover.jpg") || actor instanceof Actor.User) {
            return true;
        }
        return playing.isCurrentOrNext(itemId) || settings.guestsDownload();
    }

    /** A served name's file name, and the item's current media version. */
    private record Located(String fileName, int version) {
    }

    /**
     * The file name for a served name (in the version directory, or the original in the item
     * directory) and the item's current version; {@code 404} if the item doesn't exist or was purged.
     */
    private Located locate(String itemId, String name) throws SQLException {
        String[] row = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, original_file, kind, source, media_version"
                    + " FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), Integer.toString(rs.getInt(5))} : null;
                }
            }
        });
        if (row == null) {
            throw itemNotFound();
        }
        if ("purged".equals(row[0])) {
            throw new HttpError(404, "file_not_found", "The item's media has been deleted");
        }
        int version = Integer.parseInt(row[4]);
        if (!name.equals(ORIGINAL)) {
            return new Located(name, version);
        }
        String original = row[1];
        // only an audio upload's original is played; a video or YouTube item's is for re-encodes
        if (!"audio".equals(row[2]) || !"upload".equals(row[3])) {
            original = null;
        }
        if (original == null || !ORIGINAL_FILE.matcher(original).matches()) {
            throw new HttpError(404, "file_not_found", "The item has no original file");
        }
        return new Located(original, version);
    }

    /** The {@code Content-Type} of a file in an item directory. */
    static String contentType(String fileName) {
        String fixed = FIXED.get(fileName);
        if (fixed != null) {
            return fixed;
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ORIGINAL_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    private static HttpError itemNotFound() {
        return new HttpError(404, "item_not_found", "No such item");
    }
}
