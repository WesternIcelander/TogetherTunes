package io.siggi.togethertunes.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Feature;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Looking song details up online ({@code docs/library-and-ingest.md#looking-details-up-online},
 * {@link MetadataLookup}): the item page's <b>Look up details</b>, the review's cover, setting a
 * found cover, and the automatic lookup of a signed-in user's new upload.
 *
 * <p>Every route: the feature first ({@code metadataLookup.enabled} and TrackSplitter installed,
 * else {@code 403 feature_disabled}), then signed-in users only ({@code 401 not_authenticated} for
 * guests and anonymous visitors: they never cause a lookup), then the edit right ({@code 403
 * forbidden}: the adder while the item is in their library, or {@code library.editAny});
 * {@code 404 item_not_found}. {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code POST /api/items/{id}/lookup} {@code {"album"?, "albumArtist"?}} (a picked album; else the
 *       item's own) → {@code {"result": "found", "details": {title?, artist?, artists?, album?, albumArtist?,
 *       track?, …, barcode?}, "lookupToken", "cover"?: {"url", "width", "height"}, "namesOnly", "notice"?}},
 *       {@code {"result": "albums", "albums": [{album, albumArtist?, title?, artist?, date?, track?,
 *       trackTotal?}], "notice"?}} (without an album) or {@code {"result": "none", "notice"?}}.
 *       {@code notice} is {@value MetadataLookup#NOTICE_TEXT}. {@code 400 artist_required} (no
 *       artist, album artist or album to look up by), {@code 400 unknown_field}, and
 *       {@link MetadataLookup#lookup}'s errors ({@code 429 too_many_lookups}, {@code 503 lookup_busy},
 *       {@code 504 lookup_timeout}, {@code 502 lookup_failed}).</li>
 *   <li>{@code GET /api/items/{id}/lookup/{token}/cover} → the found cover (JPEG, served from the
 *       server, never the catalogue's address), for the user who made the lookup ({@code 404
 *       not_found} otherwise or once it expired).</li>
 *   <li>{@code PUT /api/items/{id}/cover} {@code {"lookupToken"}} → {@code 202}, the item page: the
 *       found cover becomes the item's {@code cover.jpg} and the card is made again (card-only
 *       re-encode, {@link IngestJobs#enqueueCover}); audited {@code item.cover_changed}. {@code 400
 *       invalid_lookup_token} (unknown, expired, another user's or item's, or without a cover),
 *       {@code 409 item_not_ready}, {@code 409 item_busy}.</li>
 * </ul>
 *
 * <p>Applying the details goes through {@code PATCH /api/items/{id}}, which also takes
 * {@code lookupToken} to keep the lookup's album object in {@code items.metadata_json.lookup}
 * ({@link #albumFor}).
 */
public final class MetadataLookupApi {
    private static final System.Logger LOG = System.getLogger(MetadataLookupApi.class.getName());

    /** How long a lookup's token (its cover and album object) stays usable. */
    static final long TOKEN_TTL_MS = 30 * 60_000L;
    /** Most tokens kept per user (the oldest go first). */
    static final int TOKENS_PER_USER = 20;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final Items items;
    private final Permissions permissions;
    private final Settings settings;
    private final MetadataLookup lookup;
    private final Tools tools;
    private final DataDir dataDir;
    private final IngestJobs jobs;
    private final Clock clock;
    private final Path tokenDir;
    private final Map<String, Token> tokens = new LinkedHashMap<>();
    private final ExecutorService background = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("metadata-lookup").factory());
    private volatile java.util.function.Function<Actor.User, Items.Rights> rights;

    /** A lookup's result kept for the review: its album object and its cover (a copy), if any. */
    private record Token(String id, String userId, String itemId, JsonObject album, Path cover, long expiresAt) {
    }

    /**
     * @param jobs where the card-only re-encode of a new cover is queued
     */
    public MetadataLookupApi(Items items, Permissions permissions, Settings settings, MetadataLookup lookup,
                             Tools tools, DataDir dataDir, IngestJobs jobs, Clock clock) {
        this.items = Objects.requireNonNull(items);
        this.permissions = Objects.requireNonNull(permissions);
        this.settings = Objects.requireNonNull(settings);
        this.lookup = Objects.requireNonNull(lookup);
        this.tools = Objects.requireNonNull(tools);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.jobs = Objects.requireNonNull(jobs);
        this.clock = Objects.requireNonNull(clock);
        this.tokenDir = dataDir.tmp().resolve("lookups");
        try {
            deleteTree(tokenDir);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "could not clear " + tokenDir + ": " + e.getMessage());
        }
    }

    /** Where the caller's rights come from (set by {@link ItemsApi}). */
    void rights(java.util.function.Function<Actor.User, Items.Rights> rights) {
        this.rights = rights;
    }

    void register(Router router) {
        router.post("/api/items/{id}/lookup", this::lookup);
        router.get("/api/items/{id}/lookup/{token}/cover", this::cover);
        router.put("/api/items/{id}/cover", this::setCover);
    }

    /** Whether lookups are possible now: TrackSplitter installed and {@code metadataLookup.enabled} on. */
    public boolean available() throws SQLException {
        return lookup.installed() && permissions.enabled(Feature.METADATA_LOOKUP);
    }

    /** The checks every route starts with; returns the signed-in caller. */
    private Actor.User caller(Request request) throws SQLException {
        if (!lookup.installed()) {
            throw new HttpError(403, "feature_disabled", "Looking up song details online isn't available on this server");
        }
        permissions.requireFeature(Feature.METADATA_LOOKUP);
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        return user;
    }

    private Items.Rights rightsOf(Actor.User user) {
        java.util.function.Function<Actor.User, Items.Rights> r = rights;
        return r == null ? new Items.Rights(false, false) : r.apply(user);
    }

    // ------------------------------------------------------------------ routes

    private void lookup(Request request, Response response) throws Exception {
        Actor.User user = caller(request);
        String itemId = request.pathParam("id");
        Items.LookupSource source = items.lookupSource(user, itemId, rightsOf(user));
        JsonObject body = request.readJson(JsonObject.class);
        String album = null;
        String albumArtist = null;
        if (body != null) {
            for (String key : body.keySet()) {
                if (!key.equals("album") && !key.equals("albumArtist")) {
                    throw new HttpError(400, "unknown_field", "Unknown field: " + key);
                }
            }
            album = text(body.get("album"));
            albumArtist = text(body.get("albumArtist"));
        }
        MetadataLookup.Query query = query(source, album, albumArtist);
        if (query.album() == null && query.artist() == null && query.albumArtist() == null) {
            throw new HttpError(400, "artist_required", "Add the artist or the album first, then look the details up");
        }
        MetadataLookup.Result result = lookup.lookup(user.userId(), query, MetadataLookup.Priority.INTERACTIVE);
        Map<String, Object> out = new LinkedHashMap<>();
        switch (result) {
            case MetadataLookup.Found found -> {
                MetadataLookup.Track t = found.track();
                Token token = issue(user.userId(), itemId, t);
                out.put("result", "found");
                out.put("details", details(t));
                out.put("lookupToken", token.id());
                if (token.cover() != null) {
                    int[] size = imageSize(token.cover());
                    Map<String, Object> cover = new LinkedHashMap<>();
                    cover.put("url", "/api/items/" + itemId + "/lookup/" + token.id() + "/cover");
                    if (size != null) {
                        cover.put("width", size[0]);
                        cover.put("height", size[1]);
                    }
                    out.put("cover", cover);
                }
                out.put("namesOnly", t.namesOnly());
            }
            case MetadataLookup.Albums albums -> {
                out.put("result", "albums");
                List<Map<String, Object>> list = new ArrayList<>();
                for (MetadataLookup.Track t : albums.albums()) {
                    if (t.album() == null) {
                        continue;
                    }
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("album", t.album());
                    put(a, "albumArtist", t.details().albumArtist());
                    put(a, "title", t.title());
                    put(a, "artist", t.artist());
                    put(a, "date", t.details().date());
                    put(a, "track", t.details().track());
                    put(a, "trackTotal", t.details().trackTotal());
                    list.add(a);
                }
                out.put("albums", list);
            }
            case MetadataLookup.NotFound none -> out.put("result", "none");
        }
        if (result.notices()) {
            out.put("notice", MetadataLookup.NOTICE_TEXT);
        }
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private void cover(Request request, Response response) throws Exception {
        String method = request.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            response.header("Allow", "GET, HEAD, OPTIONS");
            throw new HttpError(405, "method_not_allowed", "Method " + method + " is not allowed here");
        }
        Actor.User user = caller(request);
        Token token = token(request.pathParam("token"), user.userId(), request.pathParam("id"));
        if (token == null || token.cover() == null || !Files.isRegularFile(token.cover())) {
            throw new HttpError(404, "not_found", "No such cover (it may have expired; look the details up again)");
        }
        response.header("Cache-Control", "no-store");
        response.raw().returnFile(token.cover().toFile(), "image/jpeg");
    }

    private void setCover(Request request, Response response) throws Exception {
        Actor.User user = caller(request);
        String itemId = request.pathParam("id");
        Items.Rights r = rightsOf(user);
        Items.LookupSource source = items.lookupSource(user, itemId, r);
        JsonObject body = request.readJson(JsonObject.class);
        if (body == null || !(body.get("lookupToken") instanceof JsonPrimitive p) || !p.isString()) {
            throw new HttpError(400, "invalid_lookup_token", "Expected {\"lookupToken\"} from a lookup with a cover");
        }
        for (String key : body.keySet()) {
            if (!key.equals("lookupToken")) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
        Token token = token(p.getAsString(), user.userId(), itemId);
        if (token == null || token.cover() == null || !Files.isRegularFile(token.cover())) {
            throw new HttpError(400, "invalid_lookup_token",
                    "This lookup has no cover or has expired; look the details up again");
        }
        if (!"ready".equals(source.status())) {
            throw new HttpError(409, "item_not_ready", "The item isn't ready yet");
        }
        applyCover(itemId, token.cover());
        items.auditCover(user.userId(), itemId, request.clientIp());
        Items.ItemPage page = items.get(user, itemId, r);
        response.header("Cache-Control", "no-store");
        response.json(202, page);
    }

    /**
     * Makes {@code image} the item's new cover: scaled into {@link IngestPipeline#PENDING_COVER}, then
     * the card-only re-encode is queued.
     *
     * @throws HttpError {@code 409 item_busy}, {@code 502 lookup_failed} (the image can't be converted)
     */
    private void applyCover(String itemId, Path image) throws SQLException, InterruptedException {
        try {
            IngestPipeline.stagePendingCover(tools, dataDir, itemId, image);
        } catch (IOException | ToolException e) {
            LOG.log(System.Logger.Level.WARNING, "could not use the found cover for " + itemId + ": " + e.getMessage());
            throw new HttpError(502, "lookup_failed", "The cover couldn't be used");
        }
        try {
            jobs.enqueueCover(itemId);
        } catch (IngestJobs.ItemBusy e) {
            try {
                Files.deleteIfExists(IngestPipeline.pendingCover(dataDir, itemId));
            } catch (IOException ignored) {
                // a leftover is used by the item's next re-encode
            }
            throw new HttpError(409, "item_busy", e.getMessage());
        }
    }

    /**
     * The album object of the caller's lookup {@code token} for {@code itemId}, for
     * {@code PATCH /api/items/{id}} with {@code lookupToken}.
     *
     * @throws HttpError {@code 400 invalid_lookup_token}
     */
    JsonObject albumFor(String userId, String itemId, String tokenId) {
        Token token = token(tokenId, userId, itemId);
        if (token == null) {
            throw new HttpError(400, "invalid_lookup_token", "This lookup has expired; look the details up again");
        }
        return token.album();
    }

    // ------------------------------------------------------------------ automatic lookup of new uploads

    /**
     * An upload became ready ({@link IngestJobs#uploadReadyListener}): looks its details up in the
     * background (low priority, after people's lookups) when both settings are on and a signed-in
     * user uploaded it. Never for guests. A failure only logs.
     */
    public void uploadReady(String itemId) {
        background.execute(() -> {
            try {
                autoLookup(itemId);
            } catch (HttpError e) {
                LOG.log(System.Logger.Level.INFO, "automatic lookup of " + itemId + ": " + e.code() + " " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "automatic lookup of " + itemId + " failed", e);
            }
        });
    }

    /** {@link #uploadReady}'s work, on the calling thread. Returns what was filled in (for tests). */
    Map<String, Object> autoLookup(String itemId) throws Exception {
        if (!available() || !settings.get(Settings.METADATA_LOOKUP_AUTO_ON_UPLOAD)) {
            return Map.of();
        }
        Items.LookupSource source = items.lookupSource(itemId);
        if (source == null || source.uploader() == null || !"ready".equals(source.status())) {
            return Map.of(); // guests' uploads (also merged ones) and other items: never
        }
        if (source.album() == null) {
            // without an album TrackSplitter lists albums; an album is only taken from a single
            // unambiguous match, so there is nothing to fill in
            return Map.of();
        }
        MetadataLookup.Query query = query(source, null, null);
        MetadataLookup.Result result = lookup.lookup(source.uploader(), query, MetadataLookup.Priority.BACKGROUND);
        if (!(result instanceof MetadataLookup.Found found)) {
            return Map.of();
        }
        MetadataLookup.Track t = found.track();
        Map<String, Object> filled = new LinkedHashMap<>(items.fillEmpty(itemId, t.details(), t.albumInfo()));
        if (t.artwork() != null && "ready".equals(source.status())) {
            boolean take = source.cover() == null;
            if (!take && t.matchedByCoverOrId()) {
                int[] found2 = imageSize(t.artwork());
                int[] current = imageSize(source.cover());
                take = found2 != null && current != null
                        && (long) found2[0] * found2[1] > (long) current[0] * current[1];
            }
            if (take) {
                try {
                    applyCover(itemId, t.artwork());
                    items.auditCover(null, itemId, null);
                    filled.put("cover", true);
                } catch (HttpError e) {
                    LOG.log(System.Logger.Level.INFO, "automatic lookup of " + itemId + ": cover not set: " + e.getMessage());
                }
            }
        }
        return filled;
    }

    /** Stops the background lookups. */
    public void close() {
        background.shutdownNow();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The lookup of an item: its title and artist, the album (a picked one, else its own) and album
     * artist; with an album, its original as the file when it is {@code .m4a}, {@code .mp3} or
     * {@code .flac}, else its cover as {@code --artwork}.
     */
    static MetadataLookup.Query query(Items.LookupSource s, String album, String albumArtist) {
        String a = album != null ? album : s.album();
        String aa = albumArtist != null ? albumArtist : s.details().albumArtist();
        Path file = null;
        Path artwork = null;
        if (a != null) {
            if (s.original() != null && taggedAudio(s.original())) {
                file = s.original();
            } else if (s.cover() != null) {
                artwork = s.cover();
            }
        }
        return new MetadataLookup.Query(s.title(), s.artist(), a, aa, file, artwork);
    }

    private static boolean taggedAudio(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".m4a") || name.endsWith(".mp3") || name.endsWith(".flac");
    }

    /** The found details as the item page names them, only the known ones. */
    static Map<String, Object> details(MetadataLookup.Track t) {
        Map<String, Object> d = new LinkedHashMap<>();
        put(d, "title", t.title());
        put(d, "artist", t.artist());
        put(d, "album", t.album());
        for (String f : SongDetails.FIELDS) {
            put(d, f, t.details().get(f));
        }
        return d;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static String text(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new HttpError(400, "invalid_album", "Must be a string");
        }
        return UploadMetadata.clean(p.getAsString());
    }

    private Token issue(String userId, String itemId, MetadataLookup.Track t) throws IOException {
        long now = clock.millis();
        byte[] random = new byte[18];
        RANDOM.nextBytes(random);
        String id = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Path cover = null;
        if (t.artwork() != null) {
            Files.createDirectories(tokenDir);
            cover = tokenDir.resolve(id + ".jpg");
            Files.copy(t.artwork(), cover, StandardCopyOption.REPLACE_EXISTING);
        }
        Token token = new Token(id, userId, itemId, t.albumInfo(), cover, now + TOKEN_TTL_MS);
        List<Token> dropped = new ArrayList<>();
        synchronized (tokens) {
            int mine = 0;
            for (Token k : tokens.values()) {
                if (k.userId().equals(userId)) {
                    mine++;
                }
            }
            for (Iterator<Token> it = tokens.values().iterator(); it.hasNext(); ) {
                Token k = it.next();
                if (k.expiresAt() <= now || k.userId().equals(userId) && mine >= TOKENS_PER_USER) {
                    if (k.userId().equals(userId) && k.expiresAt() > now) {
                        mine--;
                    }
                    it.remove();
                    dropped.add(k);
                }
            }
            tokens.put(id, token);
        }
        for (Token k : dropped) {
            if (k.cover() != null) {
                Files.deleteIfExists(k.cover());
            }
        }
        return token;
    }

    private Token token(String id, String userId, String itemId) {
        if (id == null) {
            return null;
        }
        synchronized (tokens) {
            Token t = tokens.get(id);
            if (t == null || t.expiresAt() <= clock.millis() || !t.userId().equals(userId) || !t.itemId().equals(itemId)) {
                return null;
            }
            return t;
        }
    }

    /** An image's width and height, read from its header; null if it can't be read. */
    static int[] imageSize(Path image) {
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
