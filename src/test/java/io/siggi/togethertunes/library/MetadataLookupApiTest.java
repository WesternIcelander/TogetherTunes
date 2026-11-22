package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Looking song details up online over HTTP ({@link MetadataLookupApi}, a fake TrackSplitter):
 * who may, the review's data and cover, applying through PATCH and the cover route, and the
 * automatic lookup of new uploads ({@code docs/library-and-ingest.md#looking-details-up-online}).
 */
class MetadataLookupApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Settings settings;
    private Config config;
    private DataDir data;
    private IngestJobs jobs;
    private FakeTrackSplitter fake;
    private MetadataLookupApi lookupApi;
    private String aliceId;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example");
        config = new ConfigLoader(env, 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        data = DataDir.create(dir.resolve("data"));
        jobs = new IngestJobs(db, clock);
        Items items = new Items(db, data, clock);
        items.trimJobs(jobs);
        Permissions permissions = new Permissions(db, settings);
        ItemsApi itemsApi = new ItemsApi(items, permissions);
        fake = new FakeTrackSplitter(dir.resolve("ts"));
        lookupApi = new MetadataLookupApi(items, permissions, settings,
                new MetadataLookup(fake.script.toString(), fake.cacheDir, "TogetherTunes/test", null, clock),
                Tools.from(config), data, jobs, clock);
        itemsApi.lookup(lookupApi);
        itemsApi.coverUpload(new CoverUpload(items, Tools.from(config), data, jobs));
        itemsApi.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        accounts.createLocal("root", null, "root-password", false, null, "account.register");
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        String bobId = accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        lookupApi.close();
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return json(r).getAsJsonObject("error").get("code").getAsString();
    }

    /**
     * An item alice added (in her library), with {@code album} (or none), an original file
     * {@code original} (or none) and a cover (or none) in its version 0.
     */
    private String item(String album, String original, boolean cover) throws Exception {
        String id = UuidV7.newId();
        Path itemDir = Files.createDirectories(IngestPipeline.itemDir(data, id));
        if (original != null) {
            Files.writeString(itemDir.resolve(original), "audio");
        }
        if (cover) {
            FakeTrackSplitter.writeJpeg(itemDir.resolve(IngestPipeline.COVER), 16, 16);
        }
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, album, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, original_file, genre)"
                    + " VALUES (?, 'audio', 'Good is YEAH!', 'Wonderful★opportunity!', ?, 'upload', ?, 3, 'ready',"
                    + " '720p30', 1, ?, 'Vocaloid')")) {
                ps.setString(1, id);
                ps.setString(2, album);
                ps.setString(3, aliceId);
                ps.setString(4, original);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 3, 'upload')")) {
                ps.setString(1, aliceId);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /** An upload row for the item: by alice, or by a guest (who may since have merged into alice). */
    private void uploadRow(String itemId, boolean guest) throws Exception {
        String guestId = guest ? UuidV7.newId() : null;
        db.write(c -> {
            if (guest) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO guests (id, kind, key_hash, display_name,"
                        + " created_at, last_seen_at) VALUES (?, 'cookie', ?, 'Guest', 1, 1)")) {
                    ps.setString(1, guestId);
                    ps.setString(2, guestId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO uploads (id, user_id, guest_id, filename, size,"
                    + " created_at, updated_at, item_id) VALUES (?, ?, ?, 'song.m4a', 5, 1, 1, ?)")) {
                ps.setString(1, UuidV7.newId());
                ps.setString(2, guest ? null : aliceId);
                ps.setString(3, guestId);
                ps.setString(4, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String column(String itemId, String column) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void onlySignedInEditorsWhileTheFeatureIsOn() throws Exception {
        String id = item("Good is YEAH!", null, false);
        fake.respond(FakeTrackSplitter.singleTrack(null, "name"), 0);
        assertEquals(401, call("POST", "/api/items/" + id + "/lookup", null, "{}").statusCode());
        assertEquals("forbidden", code(call("POST", "/api/items/" + id + "/lookup", bob, "{}")));
        assertEquals(0, fake.calls(), "refused callers never cause a lookup");
        assertEquals(200, call("POST", "/api/items/" + id + "/lookup", alice, "{}").statusCode());
        assertEquals(1, fake.calls());
        // the item page says who may
        JsonObject you = json(call("GET", "/api/items/" + id, alice, null)).getAsJsonObject("you");
        assertTrue(you.get("canLookUp").getAsBoolean());
        assertFalse(json(call("GET", "/api/items/" + id, bob, null)).getAsJsonObject("you").has("canLookUp"));
        assertFalse(json(call("GET", "/api/items/" + id, null, null)).getAsJsonObject("you").has("canLookUp"));
        // off: 403 feature_disabled for everyone, and the page hides the button
        settings.set(Settings.METADATA_LOOKUP_ENABLED, false);
        assertEquals("feature_disabled", code(call("POST", "/api/items/" + id + "/lookup", alice, "{}")));
        assertEquals("feature_disabled", code(call("POST", "/api/items/" + id + "/lookup", null, "{}")));
        assertFalse(json(call("GET", "/api/items/" + id, alice, null)).getAsJsonObject("you").has("canLookUp"));
        assertEquals(1, fake.calls());
        settings.set(Settings.METADATA_LOOKUP_ENABLED, true);
        assertEquals("item_not_found", code(call("POST", "/api/items/" + UuidV7.newId() + "/lookup", alice, "{}")));
    }

    @Test
    void foundReviewAndApply() throws Exception {
        String id = item("Good is YEAH!", "original.m4a", true);
        Path cover = fake.artwork("catalogue-0D8.jpg", 40, 30);
        fake.respond(FakeTrackSplitter.singleTrack(cover, "artwork"), 0);
        HttpResponse<String> r = call("POST", "/api/items/" + id + "/lookup", alice, "{}");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        // the original is the file argument (an .m4a), not the cover
        List<String> args = fake.lastArgs();
        assertEquals(IngestPipeline.itemDir(data, id).resolve("original.m4a").toString(), args.get(args.size() - 1));
        assertEquals(List.of("metadata", "--lookup"), args.subList(0, 2), "an online lookup");
        assertFalse(args.contains("--artwork"));
        JsonObject body = json(r);
        assertEquals("found", body.get("result").getAsString());
        JsonObject d = body.getAsJsonObject("details");
        assertEquals("Wonderful★opportunity!, Kagamine Rin & Kagamine Len", d.get("artist").getAsString());
        assertEquals(2, d.getAsJsonArray("artists").size());
        assertEquals("JPVI02201530", d.get("isrc").getAsString());
        assertFalse(d.has("disc"));
        assertFalse(r.body().contains("Catalogue") || r.body().contains("catalogue.example"),
                "where it came from is never shown: " + r.body());
        JsonObject coverInfo = body.getAsJsonObject("cover");
        assertEquals(40, coverInfo.get("width").getAsInt());
        assertEquals(30, coverInfo.get("height").getAsInt());
        String token = body.get("lookupToken").getAsString();
        // the cover comes from the server, only for the user who looked it up
        HttpResponse<String> img = call("GET", coverInfo.get("url").getAsString(), alice, null);
        assertEquals(200, img.statusCode());
        assertEquals("image/jpeg", img.headers().firstValue("Content-Type").orElse(""));
        assertEquals(404, call("GET", coverInfo.get("url").getAsString(), bob, null).statusCode());
        assertEquals(401, call("GET", coverInfo.get("url").getAsString(), null, null).statusCode());

        // Apply: PATCH with the checked fields and the token keeps the album object
        HttpResponse<String> p = call("PATCH", "/api/items/" + id, alice,
                "{\"isrc\":\"JPVI02201530\",\"label\":\"FlyingDog\",\"lookupToken\":\"" + token + "\"}");
        assertEquals(200, p.statusCode(), p.body());
        assertEquals("FlyingDog", json(p).get("label").getAsString());
        JsonObject meta = JsonParser.parseString(column(id, "metadata_json")).getAsJsonObject();
        assertEquals("0D8", meta.getAsJsonObject("lookup").get("id").getAsString());
        assertEquals("invalid_lookup_token", code(call("PATCH", "/api/items/" + id, alice,
                "{\"label\":\"X\",\"lookupToken\":\"nope\"}")));
        // another user can't use alice's lookup
        assertEquals("invalid_lookup_token", code(call("PATCH", "/api/items/" + id, bob,
                "{\"lookupToken\":\"" + token + "\"}")));

        // the cover: queued as a card-only re-encode, the scaled cover waiting in the item's directory
        assertEquals("invalid_lookup_token", code(call("PUT", "/api/items/" + id + "/cover", alice,
                "{\"lookupToken\":\"nope\"}")));
        assertEquals("forbidden", code(call("PUT", "/api/items/" + id + "/cover", bob,
                "{\"lookupToken\":\"" + token + "\"}")));
        HttpResponse<String> c = call("PUT", "/api/items/" + id + "/cover", alice, "{\"lookupToken\":\"" + token + "\"}");
        assertEquals(202, c.statusCode(), c.body());
        assertTrue(Files.isRegularFile(IngestPipeline.pendingCover(data, id)));
        IngestJob job = jobs.latestForItem(id).orElseThrow();
        assertEquals(IngestJob.Kind.REENCODE, job.kind());
        assertEquals(IngestJob.State.QUEUED, job.state());
        assertEquals(Integer.valueOf(1), db.<Integer>read(cn -> {
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'item.cover_changed' AND target = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }));
        // again while that job waits: it takes the newer cover
        assertEquals(202, call("PUT", "/api/items/" + id + "/cover", alice, "{\"lookupToken\":\"" + token + "\"}")
                .statusCode());
        assertEquals(job.id(), jobs.latestForItem(id).orElseThrow().id());
    }

    private HttpResponse<String> upload(String path, String token, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).header("Content-Type", "application/octet-stream");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] png(int width, int height) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /**
     * Replace artwork (docs/library-and-ingest.md#cards): a signed-in editor's image body on
     * {@code PUT /api/items/{id}/cover} becomes the waiting cover and queues the card-only re-encode,
     * also while looking details up is off; guests 401, others 403, non-images 400 invalid_image,
     * over 20 MB 413, a busy item 409; audited with {@code via: upload}.
     */
    @Test
    void replaceArtworkTakesAnUploadedImage() throws Exception {
        settings.set(Settings.METADATA_LOOKUP_ENABLED, false);
        String id = item("Album", null, true);
        String path = "/api/items/" + id + "/cover";
        assertEquals(401, upload(path, null, png(40, 40)).statusCode());
        assertEquals("forbidden", code(upload(path, bob, png(40, 40))));
        assertEquals("invalid_image", code(upload(path, alice, "not an image".getBytes())));
        assertEquals("invalid_image", code(upload(path, alice, new byte[0])));
        // a PNG header with nothing decodable behind it
        assertEquals("invalid_image", code(upload(path, alice,
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0})));
        assertEquals(413, upload(path, alice, new byte[(int) CoverUpload.MAX_BYTES + 1]).statusCode());
        assertTrue(jobs.latestForItem(id).isEmpty(), "nothing queued for refused uploads");
        assertFalse(Files.exists(IngestPipeline.pendingCover(data, id)));

        HttpResponse<String> r = upload(path, alice, png(1600, 900));
        assertEquals(202, r.statusCode(), r.body());
        assertEquals(id, json(r).get("id").getAsString());
        java.awt.image.BufferedImage staged = javax.imageio.ImageIO.read(IngestPipeline.pendingCover(data, id).toFile());
        assertEquals(1200, staged.getWidth(), "scaled like every cover.jpg");
        IngestJob job = jobs.latestForItem(id).orElseThrow();
        assertEquals(IngestJob.Kind.REENCODE, job.kind());
        assertEquals(IngestJob.State.QUEUED, job.state());
        assertEquals("{\"via\":\"upload\"}", db.<String>read(cn -> {
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT details_json FROM audit_log WHERE action = 'item.cover_changed' AND target = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }));
        // a WebP while that job waits: the same job takes the newer cover
        Path webp = dir.resolve("art.webp");
        Tools.from(config).ffmpeg(List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                "color=c=blue:s=300x200", "-frames:v", "1", webp.toString())).run();
        assertEquals(202, upload(path, alice, Files.readAllBytes(webp)).statusCode());
        assertEquals(job.id(), jobs.latestForItem(id).orElseThrow().id());
        assertEquals(300, javax.imageio.ImageIO.read(IngestPipeline.pendingCover(data, id).toFile()).getWidth());

        // busy: a running job holds the item
        String busy = item("Album", null, true);
        jobs.cancel(job.id(), null);
        IngestJob running = jobs.enqueue(busy, IngestJob.Kind.QUALITY);
        assertEquals(running.id(), jobs.claimNext().id());
        assertEquals("item_busy", code(upload("/api/items/" + busy + "/cover", alice, png(40, 40))));
        assertFalse(Files.exists(IngestPipeline.pendingCover(data, busy)));
    }

    @Test
    void coverIsRefusedWhileTheItemIsBusy() throws Exception {
        String id = item("Good is YEAH!", null, true);
        fake.respond(FakeTrackSplitter.singleTrack(fake.artwork("c.jpg", 20, 20), "name"), 0);
        String token = json(call("POST", "/api/items/" + id + "/lookup", alice, "{}")).get("lookupToken").getAsString();
        // the item's cover was the --artwork
        assertTrue(fake.lastArgs().contains("--artwork"));
        jobs.enqueueTrim(id, "{\"audioStartMs\":0}");
        HttpResponse<String> r = call("PUT", "/api/items/" + id + "/cover", alice, "{\"lookupToken\":\"" + token + "\"}");
        assertEquals(409, r.statusCode(), r.body());
        assertEquals("item_busy", code(r));
        assertFalse(Files.exists(IngestPipeline.pendingCover(data, id)), "nothing left waiting");
    }

    @Test
    void albumListThenPick() throws Exception {
        String id = item(null, "original.m4a", true);
        fake.respond(FakeTrackSplitter.albumList(), 0);
        HttpResponse<String> r = call("POST", "/api/items/" + id + "/lookup", alice, "{}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(fake.lastArgs().contains("--limit"));
        assertFalse(fake.lastArgs().contains("--artwork"));
        assertFalse(fake.lastArgs().contains("--"), "no file without an album");
        JsonObject body = json(r);
        assertEquals("albums", body.get("result").getAsString());
        assertEquals(2, body.getAsJsonArray("albums").size());
        JsonObject first = body.getAsJsonArray("albums").get(0).getAsJsonObject();
        assertEquals("Troubadour", first.get("album").getAsString());
        assertEquals("K'naan", first.get("albumArtist").getAsString());
        assertEquals(7, first.get("track").getAsInt());
        assertFalse(r.body().contains("images.example") || r.body().contains("Catalogue"), r.body());
        // pick one: looked up again with it
        fake.respond(FakeTrackSplitter.singleTrack(null, "name"), 0);
        r = call("POST", "/api/items/" + id + "/lookup", alice, "{\"album\":\"Troubadour\",\"albumArtist\":\"K'naan\"}");
        assertEquals("found", json(r).get("result").getAsString());
        List<String> args = fake.lastArgs();
        assertEquals("Troubadour", args.get(args.indexOf("--album") + 1));
        assertEquals("K'naan", args.get(args.indexOf("--album-artist") + 1));
        assertEquals(List.of("metadata", "--lookup"), args.subList(0, 2), "an online lookup");
        assertFalse(json(r).has("cover"));
        assertEquals("unknown_field", code(call("POST", "/api/items/" + id + "/lookup", alice, "{\"x\":1}")));
    }

    @Test
    void nothingFoundAndNotices() throws Exception {
        String id = item(null, null, false);
        fake.respond("{\"tracks\":[],\"notices\":[\"Catalogue is rate limiting\"]}", 1);
        JsonObject body = json(call("POST", "/api/items/" + id + "/lookup", alice, "{}"));
        assertEquals("none", body.get("result").getAsString());
        assertEquals(MetadataLookup.NOTICE_TEXT, body.get("notice").getAsString());
        fake.respond("garbage", 0);
        HttpResponse<String> r = call("POST", "/api/items/" + id + "/lookup", alice, "{}");
        assertEquals(502, r.statusCode());
        assertEquals("lookup_failed", code(r));
    }

    @Test
    void automaticLookupOfAUsersUpload() throws Exception {
        String id = item("Good is YEAH!", null, false);
        uploadRow(id, false);
        fake.respond(FakeTrackSplitter.singleTrack(fake.artwork("c.jpg", 50, 50), "name"), 0);
        Map<String, Object> filled = lookupApi.autoLookup(id);
        assertEquals(1, fake.calls());
        // only empty fields: genre was set, title/artist/album never change
        assertEquals("Vocaloid", column(id, "genre"));
        assertEquals("Good is YEAH!", column(id, "title"));
        assertEquals("Wonderful★opportunity!", column(id, "artist"));
        assertEquals("Good is YEAH!", column(id, "album"));
        assertEquals("JPVI02201530", column(id, "isrc"));
        assertEquals("FlyingDog", column(id, "label"));
        assertEquals("Wonderful★opportunity!", column(id, "album_artist"));
        assertNull(column(id, "artists_json"), "the found names aren't the item's artist: the list stays empty");
        assertFalse(filled.containsKey("genre"));
        // no cover yet: the found one is taken
        assertEquals(Boolean.TRUE, filled.get("cover"));
        assertTrue(Files.isRegularFile(IngestPipeline.pendingCover(data, id)));
        assertEquals("0D8", JsonParser.parseString(column(id, "metadata_json")).getAsJsonObject()
                .getAsJsonObject("lookup").get("id").getAsString());
    }

    @Test
    void automaticCoverRules() throws Exception {
        // the item has a cover: a larger one is only taken when matched by cover or store ID
        String byName = item("Good is YEAH!", null, true);
        uploadRow(byName, false);
        fake.respond(FakeTrackSplitter.singleTrack(fake.artwork("big.jpg", 64, 64), "name"), 0);
        assertFalse(lookupApi.autoLookup(byName).containsKey("cover"));
        String byCover = item("Good is YEAH!", null, true);
        uploadRow(byCover, false);
        fake.respond(FakeTrackSplitter.singleTrack(fake.artwork("big.jpg", 64, 64), "artwork"), 0);
        assertEquals(Boolean.TRUE, lookupApi.autoLookup(byCover).get("cover"));
        String smaller = item("Good is YEAH!", null, true);
        uploadRow(smaller, false);
        fake.respond(FakeTrackSplitter.singleTrack(fake.artwork("small.jpg", 8, 8), "appleId"), 0);
        assertFalse(lookupApi.autoLookup(smaller).containsKey("cover"));
    }

    @Test
    void neverForGuestsOrWithoutAnAlbumOrWhenOff() throws Exception {
        fake.respond(FakeTrackSplitter.singleTrack(null, "name"), 0);
        // a guest's upload, merged into alice's account since: never looked up automatically
        String guest = item("Good is YEAH!", null, false);
        uploadRow(guest, true);
        assertEquals(Map.of(), lookupApi.autoLookup(guest));
        // no album: nothing could be filled in
        String noAlbum = item(null, null, false);
        uploadRow(noAlbum, false);
        assertEquals(Map.of(), lookupApi.autoLookup(noAlbum));
        // the setting off
        String user = item("Good is YEAH!", null, false);
        uploadRow(user, false);
        settings.set(Settings.METADATA_LOOKUP_AUTO_ON_UPLOAD, false);
        assertEquals(Map.of(), lookupApi.autoLookup(user));
        settings.set(Settings.METADATA_LOOKUP_AUTO_ON_UPLOAD, true);
        settings.set(Settings.METADATA_LOOKUP_ENABLED, false);
        assertEquals(Map.of(), lookupApi.autoLookup(user));
        assertEquals(0, fake.calls());
        settings.set(Settings.METADATA_LOOKUP_ENABLED, true);
        assertFalse(lookupApi.autoLookup(user).isEmpty());
        assertEquals(1, fake.calls());
    }

    @Test
    void automaticLookupRunsWhenAnUploadJobFinishes() throws Exception {
        String id = item("Good is YEAH!", null, false);
        uploadRow(id, false);
        fake.respond(FakeTrackSplitter.singleTrack(null, "name"), 0);
        jobs.uploadReadyListener(lookupApi::uploadReady);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'pending' WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        IngestJob job = jobs.enqueue(id, IngestJob.Kind.UPLOAD);
        assertEquals(job.id(), jobs.claimNext().id());
        assertTrue(jobs.complete(job.id(), "done"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (column(id, "isrc") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("JPVI02201530", column(id, "isrc"));
    }
}
