package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.rooms.PlayingItems;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An item's media files ({@code docs/api.md}, {@code docs/libraries.md#media-access-rules}):
 * {@code GET /media/{itemId}/{file}} with {@code file} one of {@code audio.m4a},
 * {@code mezz.mp4}, {@code original} (the kept original of an audio upload,
 * {@code items.original_file}) and {@code cover.jpg}.
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
 *       {@code Cache-Control: private, no-cache}: a re-encode replaces the files under the same URL
 *       and guest access can end, so browsers revalidate (an unchanged file costs a {@code 304}).</li>
 * </ul>
 */
public final class MediaApi {
    /** The served file names that map 1:1 to a file in the item directory. */
    private static final Map<String, String> FIXED = Map.of(
            "audio.m4a", "audio/mp4",
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

    private final Database db;
    private final Path mediaDir;
    private final Settings settings;
    private final PlayingItems playing;

    public MediaApi(Database db, DataDir dataDir, Settings settings, PlayingItems playing) {
        this.db = Objects.requireNonNull(db);
        this.mediaDir = dataDir.media().toAbsolutePath().normalize();
        this.settings = Objects.requireNonNull(settings);
        this.playing = Objects.requireNonNull(playing);
    }

    public void register(Router router) {
        router.get("/media/{id}/{file}", this::handle);
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
        if (!FIXED.containsKey(name) && !name.equals(ORIGINAL)) {
            throw HttpError.notFound();
        }
        if (!allowed(ActorResolver.actor(request), itemId, name)) {
            throw new HttpError(403, "forbidden", "Guests can only play what a room is playing");
        }
        if (!UuidV7.isValid(itemId)) {
            throw itemNotFound();
        }
        String fileName = fileName(itemId, name);
        Path file = mediaDir.resolve(itemId).resolve(fileName);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new HttpError(404, "file_not_found", "The item has no " + name + " file");
        }
        response.header("Cache-Control", "private, no-cache");
        response.header("X-Content-Type-Options", "nosniff");
        response.raw().returnFile(file.toFile(), contentType(fileName));
    }

    /** The access rule for {@code actor} (see the class comment). */
    boolean allowed(Actor actor, String itemId, String name) throws SQLException {
        if (name.equals("cover.jpg") || actor instanceof Actor.User) {
            return true;
        }
        return playing.isCurrentOrNext(itemId) || settings.guestsDownload();
    }

    /** The file in the item directory for a served name; {@code 404} if the item doesn't exist. */
    private String fileName(String itemId, String name) throws SQLException {
        String[] row = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, original_file FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null;
                }
            }
        });
        if (row == null) {
            throw itemNotFound();
        }
        if ("purged".equals(row[0])) {
            throw new HttpError(404, "file_not_found", "The item's media has been deleted");
        }
        if (!name.equals(ORIGINAL)) {
            return name;
        }
        String original = row[1];
        if (original == null || !ORIGINAL_FILE.matcher(original).matches()) {
            throw new HttpError(404, "file_not_found", "The item has no original file");
        }
        return original;
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
