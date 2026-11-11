package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * YouTube imports ({@link YoutubeImports}) against a fake yt-dlp that answers {@code -J} from a
 * file and records every call: normalisation, the cache lookup for every item status, the
 * metadata rules and rejections, library entries, {@code presentAsAudio} and the cookies file.
 */
abstract class YoutubeImportsContract {
    static final String ID = "dQw4w9WgXcQ";
    static final Classify.Classification MV = new Classify.Classification(MediaType.MUSIC_VIDEO, null);
    static final int MAX_SECONDS = 600;

    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private IngestJobs jobs;
    private YoutubeImports imports;
    private final AtomicInteger wakes = new AtomicInteger();
    private volatile String cookies;
    private Path metaFile;
    private Path errorFile;
    private Path callsFile;
    /** A script whose output the fake prints to stderr before answering (yt-dlp's progress lines). */
    private Path stagesFile;
    private Actor.User alice;
    private Actor.User bob;
    private Actor.Guest guest;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        jobs = new IngestJobs(db, clock);
        metaFile = dir.resolve("meta.json");
        errorFile = dir.resolve("error.txt");
        callsFile = dir.resolve("calls.txt");
        stagesFile = dir.resolve("stages.sh");
        Path script = dir.resolve("fake-yt-dlp");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" >> '" + callsFile + "'\n"
                + "echo '----' >> '" + callsFile + "'\n"
                + "if [ -f '" + stagesFile + "' ]; then sh '" + stagesFile + "' >&2; fi\n"
                + "if [ -f '" + errorFile + "' ]; then cat '" + errorFile + "' >&2; exit 1; fi\n"
                + "cat '" + metaFile + "'\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        imports = imports(new Tools("ffmpeg", "ffprobe", script.toString()));
        meta("{\"id\":\"" + ID + "\",\"title\":\"Rick Astley - Never Gonna Give You Up (Official Video)\","
                + "\"uploader\":\"Rick Astley\",\"track\":\"Never Gonna Give You Up\",\"artist\":\"Rick Astley\","
                + "\"duration\":212,\"live_status\":\"not_live\",\"is_live\":false}");

        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        alice = new Actor.User(accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id(),
                "s1", Actor.Via.BEARER);
        bob = new Actor.User(accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id(),
                "s2", Actor.Via.BEARER);
        String guestId = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO guests (id, kind, key_hash, display_name,"
                    + " created_at, last_seen_at) VALUES (?, 'cookie', ?, 'Quiet Otter', 1, 1)")) {
                ps.setString(1, guestId);
                ps.setString(2, "hash-" + guestId);
                ps.executeUpdate();
            }
            return null;
        });
        guest = new Actor.Guest(guestId, io.siggi.togethertunes.settings.GuestIdentity.COOKIE, false);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private YoutubeImports imports(Tools tools) {
        return new YoutubeImports(db, tools, jobs, wakes::incrementAndGet, OutputEncoding.defaultFor(OutputProfile.P720_30), MAX_SECONDS,
                () -> cookies, clock);
    }

    private void meta(String json) throws Exception {
        Files.writeString(metaFile, json);
    }

    /** The argument lists of every yt-dlp call so far. */
    private List<List<String>> calls() throws Exception {
        List<List<String>> out = new ArrayList<>();
        if (!Files.exists(callsFile)) {
            return out;
        }
        List<String> current = new ArrayList<>();
        for (String line : Files.readAllLines(callsFile)) {
            if (line.equals("----")) {
                out.add(current);
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        return out;
    }

    private Map<String, Object> item(String id) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kind, title, artist, album, duration_ms, source,"
                    + " youtube_id, added_by_user_id, added_by_guest_id, status, output_profile, metadata_json,"
                    + " CASE WHEN media_type = 'audio' THEN 1 ELSE 0 END AS present_as_audio, last_referenced_at,"
                    + " content_sha256, media_type FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    java.util.HashMap<String, Object> m = new java.util.HashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        m.put(rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
                    }
                    return m;
                }
            }
        });
    }

    private void setStatus(String itemId, String status) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private int count(String sql, String param) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    private String libraryVia(Actor.User user, String itemId) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT via FROM library_entries WHERE user_id = ? AND item_id = ?")) {
                ps.setString(1, user.userId());
                ps.setString(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Runs the item's queued job to the end ({@code done} = ready, else failed). */
    private void finishJob(boolean ok) throws Exception {
        IngestJob job = jobs.claimNext();
        if (ok) {
            jobs.complete(job.id(), null);
        } else {
            jobs.fail(job.id(), "yt-dlp: Video unavailable", null);
        }
    }

    private static HttpError error(Callable<?> call) {
        return assertThrows(HttpError.class, call::call);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aNewVideoBecomesAPendingItemWithAJob() throws Exception {
        YoutubeImports.Imported r = imports.importUrl(alice, "https://youtu.be/" + ID + "?t=42", null, null, MV);
        assertFalse(r.cached());
        assertEquals("pending", r.status());
        assertTrue(UuidV7.isValid(r.itemId()));
        assertEquals(1, wakes.get());

        List<List<String>> calls = calls();
        assertEquals(1, calls.size());
        assertEquals(List.of("-J", "--no-playlist", "--no-warnings", "--verbose", "--", "https://www.youtube.com/watch?v=" + ID),
                calls.get(0), "metadata from the canonical URL, never the pasted one");

        Map<String, Object> item = item(r.itemId());
        assertEquals("video", item.get("kind"));
        assertEquals("Never Gonna Give You Up", item.get("title"));
        assertEquals("Rick Astley", item.get("artist"));
        assertNull(item.get("album"));
        assertEquals(212_000L, ((Number) item.get("duration_ms")).longValue());
        assertEquals("youtube", item.get("source"));
        assertEquals(ID, item.get("youtube_id"));
        assertEquals(alice.userId(), item.get("added_by_user_id"));
        assertNull(item.get("added_by_guest_id"));
        assertEquals("pending", item.get("status"));
        assertEquals("720p30-3000k-v3", item.get("output_profile"));
        assertNull(item.get("content_sha256"));
        assertEquals(0, ((Number) item.get("present_as_audio")).intValue());
        assertEquals(clock.millis(), ((Number) item.get("last_referenced_at")).longValue());
        assertEquals(ID, JsonParser.parseString((String) item.get("metadata_json")).getAsJsonObject()
                .getAsJsonObject("youtube").get("id").getAsString());

        IngestJob job = jobs.latestForItem(r.itemId()).orElseThrow();
        assertEquals(IngestJob.Kind.YOUTUBE, job.kind());
        assertEquals(IngestJob.State.QUEUED, job.state());
        assertEquals("youtube", libraryVia(alice, r.itemId()));
    }

    /** The milestone check: other forms of the same link reuse the item without starting yt-dlp. */
    @Test
    void otherFormsOfTheSameLinkReuseTheItemWithoutYtdlp() throws Exception {
        String itemId = imports.importUrl(alice, "https://www.youtube.com/watch?v=" + ID, null, null, MV).itemId();
        assertEquals(1, calls().size());
        // still pending: attaches to the queued job
        for (String link : List.of("https://youtu.be/" + ID, "https://www.youtube.com/shorts/" + ID,
                "https://www.youtube.com/watch?v=" + ID + "&t=1m2s&list=PL123")) {
            YoutubeImports.Imported r = imports.importUrl(bob, link, null, null, MV);
            assertEquals(itemId, r.itemId(), link);
            assertFalse(r.cached());
            assertEquals("pending", r.status());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ?", itemId), "one job");
        jobs.claimNext();
        YoutubeImports.Imported processing = imports.importUrl(bob, "m.youtube.com/watch?v=" + ID, null, null, MV);
        assertEquals("processing", processing.status());
        assertFalse(processing.cached());
        jobs.complete(jobs.latestForItem(itemId).orElseThrow().id(), null);

        clock.advance(60_000);
        YoutubeImports.Imported ready = imports.importUrl(bob, "https://music.youtube.com/watch?v=" + ID + "&si=x",
                Boolean.TRUE, null);
        assertEquals(new YoutubeImports.Imported(itemId, true, "ready"), ready);
        // ready: the forms the milestone names, each with a timestamp too
        for (String link : List.of("https://youtu.be/" + ID, "https://youtu.be/" + ID + "?t=42",
                "https://www.youtube.com/shorts/" + ID, "https://youtube.com/shorts/" + ID + "?feature=share",
                "https://www.youtube.com/watch?v=" + ID + "&t=90s", "https://www.youtube.com/watch?t=5&v=" + ID)) {
            assertEquals(new YoutubeImports.Imported(itemId, true, "ready"),
                    imports.importUrl(alice, link, null, null, MV), link);
        }
        assertEquals(1, calls().size(), "yt-dlp ran only for the first import");
        assertEquals(1, count("SELECT COUNT(*) FROM items WHERE youtube_id = ?", ID));
        assertEquals(1, count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ?", itemId));
        assertEquals("youtube", libraryVia(bob, itemId));
        assertEquals(clock.millis(), ((Number) item(itemId).get("last_referenced_at")).longValue(), "referenced again");
        assertEquals(0, ((Number) item(itemId).get("present_as_audio")).intValue(),
                "presentAsAudio doesn't change an existing item");
    }

    @Test
    void presentAsAudioAndLibraryChoicesAtImport() throws Exception {
        YoutubeImports.Imported r = imports.importUrl(alice, "https://youtu.be/" + ID, true, false);
        assertEquals(1, ((Number) item(r.itemId()).get("present_as_audio")).intValue());
        assertNull(libraryVia(alice, r.itemId()), "addToLibrary: false");
        // a guest's import: no library, the guest is the adder
        meta("{\"id\":\"abcdefghijk\",\"title\":\"Guest video\",\"uploader\":\"Somebody - Topic\"}");
        YoutubeImports.Imported g = imports.importUrl(guest, "https://youtu.be/abcdefghijk", null, null, MV);
        Map<String, Object> item = item(g.itemId());
        assertEquals(guest.guestId(), item.get("added_by_guest_id"));
        assertNull(item.get("added_by_user_id"));
        assertEquals("Somebody", item.get("artist"));
        assertNull(item.get("duration_ms"));
        assertEquals(0, count("SELECT COUNT(*) FROM library_entries WHERE item_id = ?", g.itemId()));
        assertThrows(IllegalArgumentException.class,
                () -> imports.importUrl(new Actor.Anonymous(), "https://youtu.be/" + ID, null, null, MV));
    }

    /**
     * The preset tier asked for (docs/library-and-ingest.md#presets): an import into a library
     * asks for the quality preset, one without (a room request, a guest) for the quick preset; and
     * every library add tells the listener (which queues the quality re-encode).
     */
    @Test
    void importsAskForATierAndTellTheLibraryListener() throws Exception {
        List<String> added = new java.util.ArrayList<>();
        imports.libraryAddListener(added::add);
        String library = imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV).itemId();
        assertEquals(IngestJob.Tier.QUALITY, jobs.latestForItem(library).orElseThrow().tier());
        assertEquals(List.of(library), added);
        meta("{\"id\":\"abcdefghijk\",\"title\":\"Room request\",\"uploader\":\"Somebody\"}");
        String room = imports.importUrl(alice, "https://youtu.be/abcdefghijk", null, false).itemId();
        assertEquals(IngestJob.Tier.QUICK, jobs.latestForItem(room).orElseThrow().tier());
        assertEquals(List.of(library), added, "no library entry, no call");
        // adding the ready room item to a library later: the listener hears it
        finishJob(true);
        finishJob(true);
        imports.addExisting(bob, "abcdefghijk", "library", MV);
        assertEquals(List.of(library, room), added);
        // a purged item fetched again for a room only: quick
        setStatus(room, "purged");
        imports.useExisting("abcdefghijk");
        IngestJob refetch = jobs.latestForItem(room).orElseThrow();
        assertEquals(IngestJob.Kind.REFETCH, refetch.kind());
        assertEquals(IngestJob.Tier.QUICK, refetch.tier());
    }

    /**
     * Classify on library add (docs/media-types.md#media-types): an uncategorized video needs a
     * type to go into a library. Without one the import still happens (no entry, quick tier) and
     * NeedsClassification carries the item and the suggestion from yt-dlp's metadata (kept in
     * metadata_json); requests that don't go into a library never ask.
     */
    @Test
    void anUncategorizedVideoNeedsATypeForTheLibrary() throws Exception {
        List<String> added = new java.util.ArrayList<>();
        imports.libraryAddListener(added::add);
        // track/artist → music_video
        Classify.NeedsClassification e = assertThrows(Classify.NeedsClassification.class,
                () -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null));
        String itemId = e.itemId();
        assertEquals(409, e.status());
        assertEquals("needs_classification", e.code());
        assertEquals(new Classify.Suggestion("music_video", null), e.suggested());
        assertEquals("uncategorized", item(itemId).get("media_type"));
        assertNull(libraryVia(alice, itemId));
        assertTrue(added.isEmpty());
        assertEquals(IngestJob.Tier.QUICK, jobs.latestForItem(itemId).orElseThrow().tier());
        com.google.gson.JsonObject yt = com.google.gson.JsonParser.parseString((String) item(itemId).get("metadata_json"))
                .getAsJsonObject().getAsJsonObject("youtube");
        assertEquals("Never Gonna Give You Up", yt.get("track").getAsString());
        assertEquals("Rick Astley", yt.get("artist").getAsString());
        // asked again without a second lookup; with a type it goes in
        assertThrows(Classify.NeedsClassification.class, () -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null));
        assertEquals(1, calls().size());
        imports.importUrl(alice, "https://youtu.be/" + ID, null, null,
                new Classify.Classification(MediaType.SHOW, new MediaType.Show("Rick", 1, 2)));
        assertEquals("show", item(itemId).get("media_type"));
        assertEquals("youtube", libraryVia(alice, itemId));
        assertEquals(List.of(itemId), added);
        // classified now: bob's import doesn't ask, and his type is ignored
        imports.importUrl(bob, "https://youtu.be/" + ID, null, null, new Classify.Classification(MediaType.MOVIE, null));
        assertEquals("show", item(itemId).get("media_type"));
        assertEquals("youtube", libraryVia(bob, itemId));

        // series/season/episode → show, prefilled
        meta("{\"id\":\"abcdefghijk\",\"title\":\"Episode 3\",\"uploader\":\"Channel\",\"series\":\"The Show\","
                + "\"season_number\":2,\"episode_number\":3,\"duration\":100}");
        e = assertThrows(Classify.NeedsClassification.class,
                () -> imports.importUrl(alice, "https://youtu.be/abcdefghijk", null, null));
        assertEquals(new Classify.Suggestion("show", new Classify.SuggestedShow("The Show", 2, 3)), e.suggested());
        // the same item through addExisting (PUT /api/library) asks too, and nothing changes
        String show = e.itemId();
        assertThrows(Classify.NeedsClassification.class, () -> imports.addExisting(bob, "abcdefghijk", "manual"));
        assertNull(libraryVia(bob, show));
        imports.addExisting(bob, "abcdefghijk", "manual",
                new Classify.Classification(MediaType.SHOW, new MediaType.Show("The Show", 2, 3)));
        assertEquals("manual", libraryVia(bob, show));
        assertEquals("The Show", db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT show_name FROM items WHERE id = ?")) {
                ps.setString(1, show);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'item.updated' AND target = ?", show),
                "the classification is audited like an edit");

        // nothing known → no suggestion; a room request (no library) and a guest never ask
        meta("{\"id\":\"zzzzzzzzzzz\",\"title\":\"A clip\",\"uploader\":\"Somebody\",\"duration\":10}");
        e = assertThrows(Classify.NeedsClassification.class,
                () -> imports.importUrl(alice, "https://youtu.be/zzzzzzzzzzz", null, null));
        assertNull(e.suggested());
        imports.importUrl(bob, "https://youtu.be/zzzzzzzzzzz", null, false);
        imports.importUrl(guest, "https://youtu.be/zzzzzzzzzzz", null, null);
        assertEquals("uncategorized", item(e.itemId()).get("media_type"));
        // presentAsAudio makes it audio: nothing to ask
        meta("{\"id\":\"yyyyyyyyyyy\",\"title\":\"A song\",\"uploader\":\"Somebody\",\"duration\":10}");
        String song = imports.importUrl(alice, "https://youtu.be/yyyyyyyyyyy", true, null).itemId();
        assertEquals("youtube", libraryVia(alice, song));
    }

    @Test
    void aPurgedItemIsFetchedAgainIntoTheSameRow() throws Exception {
        String itemId = imports.importUrl(alice, "https://youtu.be/" + ID, null, false).itemId();
        finishJob(true);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged', purged_at = 5,"
                    + " title = 'Kept title' WHERE id = ?")) {
                ps.setString(1, itemId);
                ps.executeUpdate();
            }
            return null;
        });
        clock.advance(86_400_000);
        YoutubeImports.Imported r = imports.importUrl(bob, "https://www.youtube.com/watch?v=" + ID, true, null, MV);
        assertEquals(new YoutubeImports.Imported(itemId, false, "pending"), r);
        assertEquals(1, calls().size(), "no new metadata request");
        IngestJob job = jobs.latestForItem(itemId).orElseThrow();
        assertEquals(IngestJob.Kind.REFETCH, job.kind());
        Map<String, Object> item = item(itemId);
        assertEquals("pending", item.get("status"));
        assertEquals("Kept title", item.get("title"));
        assertEquals(1, ((Number) item.get("present_as_audio")).intValue(), "the requester brings it back");
        assertEquals(clock.millis(), ((Number) item.get("last_referenced_at")).longValue());
        assertEquals("youtube", libraryVia(bob, itemId));
        assertEquals(2, wakes.get());
        // a second request while the refetch is queued attaches to it
        assertEquals(itemId, imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV).itemId());
        assertEquals(2, count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ?", itemId));
    }

    private String column(String itemId, String column) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /**
     * A purged show (auto-deleted, say) comes back into the same row with its type and show
     * details: adding it to another library doesn't ask for a type again, and the refetch is the
     * quality tier for the library.
     */
    @Test
    void aPurgedShowIsFetchedAgainWithItsTypeAndDetails() throws Exception {
        String itemId = imports.importUrl(alice, "https://youtu.be/" + ID, null, null,
                new Classify.Classification(MediaType.SHOW, new MediaType.Show("Rick", 1, 2))).itemId();
        finishJob(true);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged', purged_at = 5,"
                    + " last_viewed_at = 4 WHERE id = ?")) {
                ps.setString(1, itemId);
                ps.executeUpdate();
            }
            return null;
        });
        clock.advance(86_400_000);
        YoutubeImports.Imported r = imports.importUrl(bob, "https://www.youtube.com/watch?v=" + ID, null, null);
        assertEquals(new YoutubeImports.Imported(itemId, false, "pending"), r, "no needs_classification");
        assertEquals(1, calls().size(), "no new metadata request");
        IngestJob job = jobs.latestForItem(itemId).orElseThrow();
        assertEquals(IngestJob.Kind.REFETCH, job.kind());
        assertEquals(IngestJob.Tier.QUALITY, job.tier());
        assertEquals("show", column(itemId, "media_type"));
        assertEquals("Rick", column(itemId, "show_name"));
        assertEquals("1", column(itemId, "season_number"));
        assertEquals("2", column(itemId, "episode_number"));
        assertEquals("youtube", libraryVia(bob, itemId));
        assertEquals("youtube", libraryVia(alice, itemId), "alice's entry stayed");
        // a type sent along is ignored: changing it is an edit
        imports.importUrl(alice, "https://youtu.be/" + ID, null, null, new Classify.Classification(MediaType.MOVIE, null));
        assertEquals("show", column(itemId, "media_type"));
        assertEquals(1, count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ? AND kind = 'refetch'", itemId));
        finishJob(true);
        assertEquals("ready", column(itemId, "status"));
        assertEquals("show", column(itemId, "media_type"));
    }

    /**
     * After an admin's delete record the video ID is free: the same link makes a new row (a new
     * metadata lookup and a first fetch, not a refetch), uncategorized again, so a library add
     * asks for the type again.
     */
    @Test
    void deleteRecordThenTheSameLinkMakesANewItem() throws Exception {
        String old = imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV).itemId();
        finishJob(true);
        Items items = new Items(db, io.siggi.togethertunes.DataDir.create(dir.resolve("data")), clock);
        items.deleteRecord(alice, old, null);
        assertNull(item(old));

        Classify.NeedsClassification e = assertThrows(Classify.NeedsClassification.class,
                () -> imports.importUrl(bob, "https://www.youtube.com/watch?v=" + ID, null, null));
        String again = e.itemId();
        assertNotEquals(old, again);
        assertEquals(new Classify.Suggestion("music_video", null), e.suggested());
        assertEquals(2, calls().size(), "looked up again");
        Map<String, Object> row = item(again);
        assertEquals(ID, row.get("youtube_id"));
        assertEquals("uncategorized", row.get("media_type"));
        assertEquals("pending", row.get("status"));
        assertEquals(IngestJob.Kind.YOUTUBE, jobs.latestForItem(again).orElseThrow().kind());
        assertNull(libraryVia(alice, again), "the old library entries went with the record");
        imports.importUrl(bob, "https://youtu.be/" + ID, null, null, MV);
        assertEquals("youtube", libraryVia(bob, again));
        assertEquals("music_video", item(again).get("media_type"));
        assertEquals(again, imports.importUrl(alice, "https://youtu.be/" + ID, null, false).itemId(),
                "the new row is the one row for the ID now");
        assertEquals(1, count("SELECT COUNT(*) FROM items WHERE youtube_id = ?", ID));
    }

    @Test
    void lowDiskSpaceRefusesNewFetchesButNotReadyItems() throws Exception {
        java.util.concurrent.atomic.AtomicLong free = new java.util.concurrent.atomic.AtomicLong(1_000);
        java.util.concurrent.atomic.AtomicLong min = new java.util.concurrent.atomic.AtomicLong(5_000);
        imports.diskSpace(new io.siggi.togethertunes.storage.DiskSpace(
                () -> new io.siggi.togethertunes.storage.DiskSpace.Usage(free.get(), 100_000), min::get,
                java.time.Duration.ZERO, System::nanoTime));

        // A new video: refused before yt-dlp runs, nothing written.
        HttpError e = assertThrows(HttpError.class, () -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV));
        assertEquals(507, e.status());
        assertEquals("insufficient_storage", e.code());
        assertEquals(0, calls().size());
        assertEquals(0, count("SELECT COUNT(*) FROM items WHERE youtube_id = ?", ID));

        // With space it goes through; a ready item is still handed out while low (nothing to download).
        free.set(10_000);
        String itemId = imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV).itemId();
        finishJob(true);
        free.set(1_000);
        assertEquals(new YoutubeImports.Imported(itemId, true, "ready"),
                imports.importUrl(bob, "https://www.youtube.com/watch?v=" + ID, null, null, MV));
        assertEquals(itemId, imports.addExisting(bob, ID, "youtube", MV).itemId());

        // A purged item would be fetched again: refused while low, no job queued.
        setStatus(itemId, "purged");
        int jobsBefore = count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ?", itemId);
        assertEquals(507, assertThrows(HttpError.class,
                () -> imports.importUrl(bob, "https://youtu.be/" + ID, null, null, MV)).status());
        assertEquals(507, assertThrows(HttpError.class, () -> imports.useExisting(ID)).status());
        assertEquals(jobsBefore, count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ?", itemId));
        assertEquals("purged", item(itemId).get("status"));

        // A minimum of 0 turns the check off.
        min.set(0);
        assertEquals("pending", imports.importUrl(bob, "https://youtu.be/" + ID, null, null, MV).status());
    }

    @Test
    void aFailedItemIsRetriedAfterTenMinutes() throws Exception {
        String itemId = imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV).itemId();
        jobs.claimNext();
        clock.advance(30_000);
        jobs.fail(jobs.latestForItem(itemId).orElseThrow().id(), "Video unavailable", null);
        assertEquals("failed", item(itemId).get("status"));

        clock.advance(4 * 60_000);
        HttpError e = error(() -> imports.importUrl(bob, "https://youtu.be/" + ID, null, null, MV));
        assertEquals(422, e.status());
        assertEquals("youtube_failed", e.code());
        assertEquals(6 * 60, e.retryAfterSeconds());
        assertNull(libraryVia(bob, itemId), "nothing is added for a refused import");
        assertEquals("failed", item(itemId).get("status"));

        clock.advance(6 * 60_000 - 1);
        assertEquals("youtube_failed", error(() -> imports.importUrl(bob, "https://youtu.be/" + ID, null, null, MV)).code());
        clock.advance(1);
        YoutubeImports.Imported r = imports.importUrl(bob, "https://youtu.be/" + ID, null, null, MV);
        assertEquals(new YoutubeImports.Imported(itemId, false, "pending"), r);
        IngestJob retry = jobs.latestForItem(itemId).orElseThrow();
        assertEquals(IngestJob.Kind.YOUTUBE, retry.kind());
        assertEquals(IngestJob.State.QUEUED, retry.state());
        assertEquals(1, calls().size(), "the retry doesn't fetch the metadata again");
        assertEquals("youtube", libraryVia(bob, itemId));
    }

    @Test
    void rejections() throws Exception {
        for (String bad : List.of("", "hello", "https://vimeo.com/123", "https://www.youtube.com/playlist?list=PL1",
                "dQw4w9WgXcQ")) {
            HttpError e = error(() -> imports.importUrl(alice, bad, null, null, MV));
            assertEquals(400, e.status(), bad);
            assertEquals("invalid_youtube_url", e.code(), bad);
        }
        assertEquals(0, calls().size(), "a bad link never reaches yt-dlp");

        meta("{\"id\":\"" + ID + "\",\"title\":\"Live now\",\"is_live\":true,\"live_status\":\"is_live\"}");
        HttpError live = error(() -> imports.importUrl(alice, "https://www.youtube.com/live/" + ID, null, null, MV));
        assertEquals(422, live.status());
        assertEquals("youtube_live", live.code());
        meta("{\"id\":\"" + ID + "\",\"title\":\"Soon\",\"live_status\":\"is_upcoming\"}");
        assertEquals("youtube_live", error(() -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV)).code());

        meta("{\"id\":\"" + ID + "\",\"title\":\"Long\",\"duration\":" + (MAX_SECONDS + 1) + "}");
        HttpError tooLong = error(() -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV));
        assertEquals(422, tooLong.status());
        assertEquals("too_long", tooLong.code());
        assertTrue(tooLong.getMessage().contains("601 s"), tooLong.getMessage());
        meta("{\"id\":\"" + ID + "\",\"title\":\"Exactly the limit\",\"duration\":" + MAX_SECONDS + "}");
        String ok = imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV).itemId();
        assertEquals("Exactly the limit", item(ok).get("title"));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM library_entries WHERE item_id = ?")) {
                ps.setString(1, ok);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ingest_jobs WHERE item_id = ?")) {
                ps.setString(1, ok);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM items WHERE id = ?")) {
                ps.setString(1, ok);
                ps.executeUpdate();
            }
            return null;
        });

        Files.writeString(errorFile, "WARNING: something\nERROR: [youtube] " + ID + ": Video unavailable. This video is private\n");
        HttpError unavailable = error(() -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV));
        assertEquals(422, unavailable.status());
        assertEquals("youtube_unavailable", unavailable.code());
        assertEquals("YouTube didn't return this video: Video unavailable. This video is private", unavailable.getMessage());
        Files.delete(errorFile);

        meta("this is not json");
        assertEquals("youtube_bad_metadata", error(() -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV)).code());

        assertEquals(0, count("SELECT COUNT(*) FROM items WHERE youtube_id = ?", ID), "no item for a rejected import");
        assertEquals(0, wakes.get() - 1, "only the accepted import queued a job");

        YoutubeImports noTool = imports(new Tools("ffmpeg", "ffprobe", dir.resolve("no-such-yt-dlp").toString()));
        HttpError missing = error(() -> noTool.importUrl(alice, "https://youtu.be/" + ID, null, null, MV));
        assertEquals(503, missing.status());
        assertEquals("ytdlp_unavailable", missing.code());
    }

    @Test
    void errorMessages() {
        assertEquals("YouTube didn't return this video: Sign in to confirm you're not a bot",
                YoutubeImports.errorMessage("ERROR: [youtube] " + ID + ": Sign in to confirm you're not a bot"));
        assertEquals("YouTube didn't return this video: Unsupported URL: x",
                YoutubeImports.errorMessage("ERROR: Unsupported URL: x"));
        assertEquals("YouTube didn't return this video", YoutubeImports.errorMessage("Traceback\n  oops"));
        assertEquals("YouTube didn't return this video", YoutubeImports.errorMessage(null));
    }

    @Test
    void theCookiesFileIsPassedWhenSet() throws Exception {
        Path file = Files.writeString(dir.resolve("cookies.txt"), "# Netscape HTTP Cookie File\n");
        cookies = file.toString();
        imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV);
        List<String> args = calls().get(0);
        assertEquals(file.toString(), args.get(args.indexOf("--cookies") + 1));
        assertEquals("https://www.youtube.com/watch?v=" + ID, args.get(args.size() - 1));

        cookies = dir.resolve("missing.txt").toString();
        meta("{\"id\":\"abcdefghijk\",\"title\":\"t\"}");
        imports.importUrl(alice, "https://youtu.be/abcdefghijk", null, null, MV);
        assertFalse(calls().get(1).contains("--cookies"), "an unreadable cookies file is skipped");
    }

    /** Concurrent imports of one new video run yt-dlp once and make one item. */
    @Test
    void concurrentImportsMakeOneItem() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<YoutubeImports.Imported>> futures = new ArrayList<>();
            List<String> links = List.of("https://youtu.be/" + ID, "https://www.youtube.com/watch?v=" + ID,
                    "youtube.com/shorts/" + ID, "https://m.youtube.com/watch?v=" + ID);
            for (int i = 0; i < links.size(); i++) {
                Actor actor = i % 2 == 0 ? alice : bob;
                String link = links.get(i);
                futures.add(pool.submit(() -> imports.importUrl(actor, link, null, null, MV)));
            }
            String itemId = null;
            for (Future<YoutubeImports.Imported> f : futures) {
                String id = f.get().itemId();
                if (itemId == null) {
                    itemId = id;
                }
                assertEquals(itemId, id);
            }
            assertEquals(1, calls().size());
            assertEquals(1, count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ?", itemId));
            assertNotEquals(null, libraryVia(alice, itemId));
            assertNotEquals(null, libraryVia(bob, itemId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theLookupReportsItsStagesInOrder() throws Exception {
        Files.writeString(stagesFile, "echo '[debug] Encodings: locale UTF-8'\n"
                + "echo '[youtube] Extracting URL: https://www.youtube.com/watch?v=" + ID + "'\n"
                + "sleep 0.1\n"
                + "echo '[youtube] " + ID + ": Downloading webpage'\n"
                + "echo 'a line nobody knows'\n"
                + "echo '[youtube] " + ID + ": Downloading tv client config'\n"
                + "sleep 0.1\n"
                + "echo '[youtube] " + ID + ": Downloading webpage'\n"
                + "echo '[youtube] " + ID + ": Downloading m3u8 information'\n"
                + "echo '[info] " + ID + ": Downloading 1 format(s): 18'\n");
        List<String> stages = new java.util.concurrent.CopyOnWriteArrayList<>();
        YoutubeImports.Imported r = imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV,
                s -> stages.add(s.wire()));
        assertEquals("pending", r.status());
        assertEquals(List.of("contacting", "page", "details", "formats"), stages);

        // known now: no lookup, no stages
        stages.clear();
        imports.importUrl(bob, "https://youtu.be/" + ID, null, null, MV, s -> stages.add(s.wire()));
        assertEquals(List.of(), stages);
    }

    @Test
    void unknownOutputAndAThrowingListenerNeverFailTheLookup() throws Exception {
        Files.writeString(stagesFile, "echo 'Totally new wording: fetching things'\n"
                + "echo '[youtube] " + ID + ": Downloading webpage'\n");
        YoutubeImports.Imported r = imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV, s -> {
            throw new IllegalStateException("the socket is gone");
        });
        assertEquals("pending", r.status());
    }

    @Test
    void anErrorFollowedByATracebackStillGivesTheErrorMessage() throws Exception {
        StringBuilder traceback = new StringBuilder("ERROR: [youtube] " + ID + ": Video unavailable\n"
                + "Traceback (most recent call last):\n");
        for (int i = 0; i < 40; i++) {
            traceback.append("  File \"/x/yt_dlp/y.py\", line ").append(i).append(", in f\n");
        }
        Files.writeString(errorFile, traceback.toString());
        HttpError e = error(() -> imports.importUrl(alice, "https://youtu.be/" + ID, null, null, MV, s -> { }));
        assertEquals("youtube_unavailable", e.code());
        assertEquals("YouTube didn't return this video: Video unavailable", e.getMessage());
    }
}
