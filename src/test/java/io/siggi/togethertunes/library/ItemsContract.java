package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestItems;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.MediaVersions;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.settings.GuestIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Items by id ({@link Items}): the item page and the caller's rights, the edit rule (adder while in
 * their library, or {@code library.editAny}), the latest job with its log only for editors, and
 * purging (files moved aside and deleted, queued jobs cancelled, refused while a job runs).
 */
abstract class ItemsContract {
    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private DataDir data;
    private Items items;
    private IngestJobs jobs;
    private Actor.User alice;
    private Actor.User bob;
    private Actor.Guest guest;

    private static final Items.Rights NONE = new Items.Rights(false, false);
    private static final Items.Rights EDIT_ANY = new Items.Rights(true, false);
    private static final Items.Rights PURGE = new Items.Rights(false, true);

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        data = DataDir.create(dir.resolve("data"));
        items = new Items(db, data, clock);
        jobs = new IngestJobs(db, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        alice = new Actor.User(accounts.createLocal("alice", "Alice", "alice-password", false, null,
                "account.register").id(), "s1", Actor.Via.BEARER);
        bob = new Actor.User(accounts.createLocal("bob", "Bob", "bob-password", false, null,
                "account.register").id(), "s2", Actor.Via.BEARER);
        String guestId = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO guests (id, kind, key_hash, display_name,"
                    + " name_chosen, created_at, last_seen_at, banned) VALUES (?, 'cookie', ?, 'Quiet Walrus', 0, 1, 1, 0)")) {
                ps.setString(1, guestId);
                ps.setString(2, "hash-" + guestId);
                ps.executeUpdate();
            }
            return null;
        });
        guest = new Actor.Guest(guestId, GuestIdentity.COOKIE, false);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ fixture

    private String item(String kind, String status, String addedByUserId, String addedByGuestId) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, album,"
                    + " duration_ms, source, added_by_user_id, added_by_guest_id, added_at, status, error,"
                    + " output_profile, last_referenced_at) VALUES (?, ?, 'Song', 'Artist', NULL, 5000, 'upload', ?, ?,"
                    + " 7, ?, ?, '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, addedByUserId);
                ps.setString(4, addedByGuestId);
                ps.setString(5, status);
                ps.setString(6, status.equals("failed") ? "ffmpeg broke" : null);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String aliceItem(String kind) throws SQLException {
        String id = item(kind, "ready", alice.userId(), null);
        entry(alice, id);
        return id;
    }

    private void entry(Actor.User user, String itemId) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 1, 'upload')")) {
                ps.setString(1, user.userId());
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void removeEntry(Actor.User user, String itemId) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM library_entries WHERE user_id = ? AND item_id = ?")) {
                ps.setString(1, user.userId());
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String column(String itemId, String column) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return rs.getString(1);
                }
            }
        });
    }

    private List<String> audit(String action) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT actor_user_id, target, details_json, ip"
                    + " FROM audit_log WHERE action = ? ORDER BY at, id")) {
                ps.setString(1, action);
                List<String> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getString(4));
                    }
                }
                return out;
            }
        });
    }

    private static Items.Edit title(String title) {
        return new Items.Edit(title, null, false, null, false);
    }

    private static String code(Executable e) {
        return assertThrows(HttpError.class, e::run).code();
    }

    @FunctionalInterface
    private interface Executable {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ get

    /**
     * Not every item has a cover (an upload with no embedded picture has none), so the page says
     * whether the current version has a {@code cover.jpg} and the item page asks for it only then.
     */
    @Test
    void hasCoverFollowsTheCurrentVersionsCoverFile() throws Exception {
        String id = aliceItem("audio");
        assertFalse(items.get(alice, id, NONE).hasCover());
        Path itemDir = IngestPipeline.itemDir(data, id);
        Files.createDirectories(MediaVersions.versionDir(itemDir, 2));
        Files.writeString(MediaVersions.versionDir(itemDir, 2).resolve(IngestPipeline.COVER), "jpeg");
        assertFalse(items.get(alice, id, NONE).hasCover(), "version 0 still has none");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET media_version = 2 WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        assertTrue(items.get(alice, id, NONE).hasCover());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'processing' WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        assertFalse(items.get(alice, id, NONE).hasCover(), "only a ready item offers its cover");
    }

    @Test
    void pageShowsTheItemAndTheCallersRights() throws Exception {
        String id = aliceItem("audio");
        Items.ItemPage page = items.get(alice, id, NONE);
        assertEquals(new Items.ItemPage(id, "audio", "Song", "Artist", null, 5000L, "upload", null, "ready", "audio",
                null, 7, null, 0, new Items.You(true, true, false)), page);
        assertFalse(page.presentAsAudio());

        assertEquals(new Items.You(false, false, false), items.get(bob, id, NONE).you());
        assertEquals(new Items.You(false, true, false), items.get(bob, id, EDIT_ANY).you());
        assertEquals(new Items.You(false, false, true), items.get(bob, id, PURGE).you());
        assertEquals(new Items.You(false, false, false), items.get(guest, id, NONE).you());
        assertEquals(new Items.You(false, false, false), items.get(new Actor.Anonymous(), id, NONE).you());
        // rights given to a non-user don't count (they can't hold permissions anyway)
        assertEquals(new Items.You(false, false, false), items.get(guest, id, new Items.Rights(true, true)).you());

        // the adder without the item in their library can't edit
        removeEntry(alice, id);
        assertEquals(new Items.You(false, false, false), items.get(alice, id, NONE).you());
        // someone else having it in their library doesn't make them an editor
        entry(bob, id);
        assertEquals(new Items.You(true, false, false), items.get(bob, id, NONE).you());

        String failed = item("video", "failed", bob.userId(), null);
        assertEquals("ffmpeg broke", items.get(new Actor.Anonymous(), failed, NONE).error());
        assertEquals("item_not_found", code(() -> items.get(alice, UuidV7.newId(), NONE)));
    }

    // ------------------------------------------------------------------ update

    @Test
    void theAdderEditsWhileTheItemIsInTheirLibrary() throws Exception {
        String id = aliceItem("audio");
        Items.ItemPage edited = items.update(alice, id, new Items.Edit("New Title", "New Artist", false, "Album",
                false), NONE, "192.0.2.5");
        assertEquals("New Title", edited.title());
        assertEquals("New Artist", edited.artist());
        assertEquals("Album", edited.album());
        assertEquals(edited, items.get(alice, id, NONE));
        assertEquals(List.of(alice.userId() + " " + id
                        + " {\"title\":\"New Title\",\"artist\":\"New Artist\",\"album\":\"Album\"} 192.0.2.5"),
                audit(Items.AUDIT_UPDATED));

        // clearing artist and album; an unchanged edit writes nothing
        Items.ItemPage cleared = items.update(alice, id, new Items.Edit(null, null, true, null, true), NONE, null);
        assertNull(cleared.artist());
        assertNull(cleared.album());
        assertEquals("New Title", cleared.title());
        assertNull(column(id, "artist"));
        items.update(alice, id, title("New Title"), NONE, null);
        items.update(alice, id, new Items.Edit(null, null, false, null, false), NONE, null);
        assertEquals(2, audit(Items.AUDIT_UPDATED).size());

        // others can't; library.editAny can; the adder can't once it left their library
        assertEquals("forbidden", code(() -> items.update(bob, id, title("Bob's"), NONE, null)));
        entry(bob, id);
        assertEquals("forbidden", code(() -> items.update(bob, id, title("Bob's"), NONE, null)));
        assertEquals("Bob's", items.update(bob, id, title("Bob's"), EDIT_ANY, null).title());
        assertEquals("forbidden", code(() -> items.update(bob, id, title("Purger"), PURGE, null)));
        removeEntry(alice, id);
        assertEquals("forbidden", code(() -> items.update(alice, id, title("Again"), NONE, null)));
        assertEquals("Bob's", column(id, "title"));
        assertEquals("item_not_found", code(() -> items.update(alice, UuidV7.newId(), title("x"), EDIT_ANY, null)));
    }

    private static Items.Edit type(MediaType type, MediaType.Show show) {
        return new Items.Edit(null, null, false, null, false, type, show);
    }

    @Test
    void mediaTypeRules() throws Exception {
        String audio = aliceItem("audio");
        String video = aliceItem("video");
        assertEquals("audio", items.get(alice, audio, NONE).mediaType());
        assertEquals("uncategorized", items.get(alice, video, NONE).mediaType());
        // an audio item is always audio
        assertEquals("not_a_video", code(() -> items.update(alice, audio, type(MediaType.MUSIC_VIDEO, null), NONE, null)));
        assertEquals("audio", items.update(alice, audio, type(MediaType.AUDIO, null), NONE, null).mediaType());

        // audio on a video item: presented as audio (the derived field)
        Items.ItemPage asAudio = items.update(alice, video, type(MediaType.AUDIO, null), NONE, null);
        assertEquals("audio", asAudio.mediaType());
        assertTrue(asAudio.presentAsAudio());
        assertEquals("audio", column(video, "media_type"));
        Items.ItemPage mv = items.update(alice, video, type(MediaType.MUSIC_VIDEO, null), NONE, null);
        assertFalse(mv.presentAsAudio());
        assertNull(mv.show());

        // show: details required, and only for show
        assertEquals("show_required", code(() -> items.update(alice, video, type(MediaType.SHOW, null), NONE, null)));
        assertEquals("not_a_show", code(() -> items.update(alice, video,
                type(MediaType.MOVIE, new MediaType.Show("X", null, 1)), NONE, null)));
        assertEquals("not_a_show", code(() -> items.update(alice, video,
                type(null, new MediaType.Show("X", null, 1)), NONE, null)), "still a music video");
        Items.ItemPage show = items.update(alice, video, type(MediaType.SHOW, new MediaType.Show("Series", 2, 5)),
                NONE, null);
        assertEquals(new MediaType.Show("Series", 2, 5), show.show());
        assertEquals("Series", column(video, "show_name"));
        // details alone change a show; the type stays
        assertEquals(new MediaType.Show("Series", null, 0), items.update(alice, video,
                type(null, new MediaType.Show("Series", null, 0)), NONE, null).show());
        assertNull(column(video, "season_number"));
        assertEquals("0", column(video, "episode_number"));
        // a title edit keeps the show
        assertEquals(new MediaType.Show("Series", null, 0), items.update(alice, video, title("T"), NONE, null).show());
        // changing the type away clears the details
        Items.ItemPage movie = items.update(alice, video, type(MediaType.MOVIE, null), NONE, null);
        assertEquals("movie", movie.mediaType());
        assertNull(movie.show());
        assertNull(column(video, "show_name"));
        assertNull(column(video, "episode_number"));
        assertEquals("movie", items.get(bob, video, NONE).mediaType());

        // same edit rights as the other metadata
        assertEquals("forbidden", code(() -> items.update(bob, video, type(MediaType.SHOW,
                new MediaType.Show("B", 1, 1)), NONE, null)));
        assertEquals("show", items.update(bob, video, type(MediaType.SHOW, new MediaType.Show("B", 1, 1)), EDIT_ANY,
                null).mediaType());

        List<String> audits = audit(Items.AUDIT_UPDATED);
        assertTrue(audits.contains(alice.userId() + " " + video + " {\"mediaType\":\"audio\"} null"), audits.toString());
        assertTrue(audits.contains(alice.userId() + " " + video
                + " {\"mediaType\":\"show\",\"show\":{\"name\":\"Series\",\"season\":2,\"episode\":5}} null"),
                audits.toString());
    }

    /**
     * A type change into or out of movie/show queues the system audio re-encode that makes the
     * loudness normalisation follow the type (docs/library-and-ingest.md#loudness-and-media-types).
     */
    @Test
    void aTypeChangeIntoOrOutOfMovieOrShowQueuesAnAudioReencode() throws Exception {
        items.trimJobs(jobs);
        String video = aliceItem("video");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET original_file = 'original.mp4' WHERE id = ?")) {
                ps.setString(1, video);
                ps.executeUpdate();
            }
            return null;
        });
        items.update(alice, video, type(MediaType.MUSIC_VIDEO, null), NONE, null);
        assertTrue(jobs.latestForItem(video).isEmpty(), "music videos are normalised, like the files");
        items.update(alice, video, type(MediaType.MOVIE, null), NONE, null);
        IngestJob job = jobs.latestForItem(video).orElseThrow();
        assertEquals(IngestJob.Kind.REENCODE, job.kind());
        // another change while it is queued: the same job (it reads the type when it runs)
        items.update(alice, video, type(MediaType.SHOW, new MediaType.Show("S", null, 1)), NONE, null);
        assertEquals(job.id(), jobs.latestForItem(video).orElseThrow().id());
        assertEquals(job.id(), jobs.claimNext().id());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET loudness_normalized = 0 WHERE id = ?")) {
                ps.setString(1, video);
                ps.executeUpdate();
            }
            return null;
        });
        assertTrue(jobs.complete(job.id(), "log"));
        assertEquals(job.id(), jobs.latestForItem(video).orElseThrow().id(), "the show's files follow it now");
        items.update(alice, video, type(MediaType.MOVIE, null), NONE, null);
        assertEquals(job.id(), jobs.latestForItem(video).orElseThrow().id(), "movie to show needs nothing");
        items.update(alice, video, type(MediaType.AUDIO, null), NONE, null);
        IngestJob back = jobs.latestForItem(video).orElseThrow();
        assertNotEquals(job.id(), back.id());
        assertEquals(IngestJob.Kind.REENCODE, back.kind());
    }

    @Test
    void guestAddersCantEdit() throws Exception {
        String id = item("audio", "ready", null, guest.guestId());
        assertFalse(items.get(guest, id, NONE).you().canEdit());
    }

    // ------------------------------------------------------------------ job

    @Test
    void jobShowsTheLatestJobAndItsLogOnlyToEditors() throws Exception {
        String id = aliceItem("audio");
        assertEquals("job_not_found", code(() -> items.job(alice, id, NONE)));
        IngestJob first = jobs.enqueue(id, IngestJob.Kind.UPLOAD);
        IngestJob claimed = jobs.claimNext();
        assertEquals(first.id(), claimed.id());
        jobs.saveProgress(first.id(), 0.25, "line 1");
        Items.JobView view = items.job(alice, id, NONE);
        assertEquals(new Items.JobView(first.id(), "upload", "running", 0.25, 1, clock.millis(), clock.millis(), null,
                "line 1", null, null), view);
        // a running job's live step comes from the ingest queue
        items.jobSteps(jobId -> jobId.equals(first.id())
                ? java.util.Optional.of(new io.siggi.togethertunes.ingest.IngestQueue.LiveStep("encode", 0.5))
                : java.util.Optional.empty());
        Items.JobView stepped = items.job(alice, id, NONE);
        assertEquals("encode", stepped.step());
        assertEquals(0.5, stepped.stepProgress());
        items.jobSteps(io.siggi.togethertunes.ingest.JobList.Steps.NONE);
        assertNull(items.job(bob, id, NONE).log());
        assertNull(items.job(guest, id, NONE).log());
        assertEquals("line 1", items.job(bob, id, EDIT_ANY).log());
        jobs.complete(first.id(), "done");
        clock.advance(1000);
        IngestJob second = jobs.enqueue(id, IngestJob.Kind.REENCODE);
        Items.JobView latest = items.job(new Actor.Anonymous(), id, NONE);
        assertEquals(second.id(), latest.id());
        assertEquals("reencode", latest.kind());
        assertEquals("queued", latest.state());
        assertNull(latest.startedAt());
        assertEquals("item_not_found", code(() -> items.job(alice, UuidV7.newId(), NONE)));
    }

    // ------------------------------------------------------------------ purge

    private Path files(String itemId) throws Exception {
        Path itemDir = IngestPipeline.itemDir(data, itemId);
        Files.createDirectories(itemDir);
        Files.writeString(itemDir.resolve("audio.m4a"), "audio");
        Files.writeString(itemDir.resolve("card.mp4"), "card");
        // two media versions (the current one and one waiting for its clean-up) and the original
        Files.createDirectories(itemDir.resolve("v1"));
        Files.writeString(itemDir.resolve("v1").resolve("mezz.mp4"), "v1");
        Files.createDirectories(itemDir.resolve("v2"));
        Files.writeString(itemDir.resolve("v2").resolve("mezz.mp4"), "v2");
        Files.writeString(itemDir.resolve("original.mp4"), "original");
        return itemDir;
    }

    private Path staged(String itemId) throws Exception {
        Path staging = IngestPipeline.stagingDir(data, itemId);
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("source.mp3"), "upload");
        return staging;
    }

    private void assertNoLeftovers() throws Exception {
        try (var s = Files.list(data.media())) {
            assertEquals(List.of(), s.filter(p -> p.getFileName().toString().startsWith(".")).toList());
        }
        Path ingest = data.tmp().resolve("ingest");
        if (Files.isDirectory(ingest)) {
            try (var s = Files.list(ingest)) {
                assertEquals(List.of(), s.filter(p -> p.getFileName().toString().startsWith(".")).toList());
            }
        }
    }

    @Test
    void purgeDeletesFilesAndKeepsTheRow() throws Exception {
        String id = aliceItem("audio");
        Path itemDir = files(id);
        Path other = files(aliceItem("audio"));
        clock.advance(5000);
        List<String> told = new java.util.ArrayList<>();
        items.purgeListener(told::add);
        Items.Purged purged = items.purge(id, items.auditedBy(bob.userId(), "2001:db8::1"));
        assertEquals(new Items.Purged("ready", 0), purged);
        assertEquals(List.of(id), told, "the purge listener (the overlay cache) is told");
        assertFalse(Files.exists(itemDir), "every version and the original are gone");
        assertTrue(Files.exists(other.resolve("audio.m4a")), "other items are untouched");
        assertNoLeftovers();
        assertEquals("purged", column(id, "status"));
        assertEquals(String.valueOf(clock.millis()), column(id, "purged_at"));
        assertEquals("Song", column(id, "title"));
        assertTrue(items.get(alice, id, NONE).you().inLibrary(), "library entries stay (media expired)");
        assertEquals(List.of(bob.userId() + " " + id + " {\"previousStatus\":\"ready\"} 2001:db8::1"),
                audit(Items.AUDIT_PURGED));

        // purging again: still purged, the first purge time is kept, leftovers go
        long first = clock.millis();
        clock.advance(5000);
        files(id);
        assertEquals(new Items.Purged("purged", 0), items.purge(id, (c, i, s) -> { }));
        assertEquals(String.valueOf(first), column(id, "purged_at"));
        assertFalse(Files.exists(itemDir));

        assertEquals("item_not_found", code(() -> items.purge(UuidV7.newId(), (c, i, s) -> { })));
    }

    @Test
    void purgeCancelsQueuedJobsAndDeletesTheStagedUpload() throws Exception {
        String id = item("audio", "pending", alice.userId(), null);
        Path staging = staged(id);
        IngestJob job = jobs.enqueue(id, IngestJob.Kind.UPLOAD);
        assertEquals(new Items.Purged("pending", 1), items.purge(id, (c, i, s) -> { }));
        assertEquals(IngestJob.State.FAILED, jobs.get(job.id()).orElseThrow().state());
        assertNotNull(jobs.get(job.id()).orElseThrow().finishedAt());
        assertNull(jobs.claimNext(), "the cancelled job doesn't run");
        assertEquals("purged", column(id, "status"));
        assertFalse(Files.exists(staging));
        assertNoLeftovers();
    }

    @Test
    void purgeIsRefusedWhileAJobRuns() throws Exception {
        String id = aliceItem("video");
        Path itemDir = files(id);
        jobs.enqueue(id, IngestJob.Kind.REENCODE);
        IngestJob running = jobs.claimNext();
        assertEquals("item_busy", code(() -> items.purge(id, items.auditedBy(bob.userId(), null))));
        assertEquals("ready", column(id, "status"));
        assertNull(column(id, "purged_at"));
        assertTrue(Files.exists(itemDir.resolve("audio.m4a")));
        assertEquals(IngestJob.State.RUNNING, jobs.get(running.id()).orElseThrow().state());
        assertEquals(List.of(), audit(Items.AUDIT_PURGED), "the audit row is rolled back");
        jobs.complete(running.id(), "done");
        items.purge(id, (c, i, s) -> { });
        assertEquals("purged", column(id, "status"));
    }

    @Test
    void aRefusingCheckChangesNothing() throws Exception {
        String id = aliceItem("audio");
        Path itemDir = files(id);
        Path staging = staged(id);
        IngestJob job = jobs.enqueue(id, IngestJob.Kind.REENCODE);
        HttpError refused = new HttpError(409, "still_referenced", "no");
        assertEquals("still_referenced", code(() -> items.purge(id, (c, i, s) -> {
            throw refused;
        })));
        assertEquals("ready", column(id, "status"));
        assertEquals(IngestJob.State.QUEUED, jobs.get(job.id()).orElseThrow().state());
        assertTrue(Files.exists(itemDir.resolve("audio.m4a")));
        assertTrue(Files.exists(staging.resolve("source.mp3")));

        // moving the staged upload aside fails after the item directory was moved: the
        // transaction rolls back and the item directory is put back
        Path ingestDir = staging.getParent();
        assertTrue(ingestDir.toFile().setWritable(false), "can make tmp/ingest read-only");
        try {
            assertThrows(SQLException.class, () -> items.purge(id, (c, i, s) -> { }));
        } finally {
            ingestDir.toFile().setWritable(true);
        }
        assertEquals("ready", column(id, "status"));
        assertEquals(IngestJob.State.QUEUED, jobs.get(job.id()).orElseThrow().state());
        assertTrue(Files.exists(itemDir.resolve("audio.m4a")));
        assertTrue(Files.exists(staging.resolve("source.mp3")));
        assertNoLeftovers();
    }

    // ------------------------------------------------------------------ undo

    /** Publishes version {@code v} of an audio item (its audio.m4a names the version), {@code kept} kept for undo. */
    private void publishVersion(String item, int v, Integer kept, String edit, long durationMs) throws Exception {
        Path f = MediaVersions.versionDir(data, item, v).resolve(IngestPipeline.AUDIO);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "v" + v);
        new IngestItems(db).published(item, false, durationMs, OutputEncoding.DEFAULT, X264Preset.VERYFAST, true, null,
                null, v, new IngestItems.VersionChange(kept, kept != null, edit, clock.millis()));
    }

    private String currentAudio(String item) throws Exception {
        return Files.readString(MediaVersions.currentDir(db, data, item).resolve(IngestPipeline.AUDIO));
    }

    /**
     * Undo of a media change: only for those who may edit the item (the page shows it to them
     * alone), audited, restores the old files and settings; redo by undo; nothing after a purge.
     */
    @Test
    void undoIsForThoseWhoMayEdit() throws Exception {
        MediaVersions versions = new MediaVersions(data, db, clock::millis, java.time.Duration.ofMinutes(5));
        versions.undoWindow(() -> 48 * 3_600_000L);
        items.mediaVersions(versions);
        String item = aliceItem("audio");
        publishVersion(item, 1, null, "{\"audioStartMs\":0}", 5000);
        assertNull(items.get(alice, item, NONE).undo(), "a first ingest has nothing to undo");
        assertEquals("nothing_to_undo", code(() -> items.undo(alice, item, NONE, "203.0.113.5")));
        clock.advance(60_000);
        long changedAt = clock.millis();
        publishVersion(item, 2, 1, "{\"audioStartMs\":1500}", 3500);
        versions.retired(item, 1, 3500);

        assertEquals(new Items.UndoView(changedAt), items.get(alice, item, NONE).undo());
        assertNull(items.get(bob, item, NONE).undo(), "not for someone who can't edit");
        assertNull(items.get(guest, item, NONE).undo());
        assertEquals("forbidden", code(() -> items.undo(bob, item, NONE, null)));
        assertEquals("item_not_found", code(() -> items.undo(alice, UuidV7.newId(), NONE, null)));
        assertEquals("v2", currentAudio(item));

        clock.advance(60_000);
        Items.ItemPage page = items.undo(alice, item, NONE, "203.0.113.5");
        assertEquals(3, page.mediaVersion());
        assertEquals(5000L, page.durationMs());
        assertEquals("v1", currentAudio(item));
        assertEquals("{\"audioStartMs\":0}", MediaVersions.currentEditJson(db, item));
        assertEquals(new Items.UndoView(clock.millis()), page.undo(), "the undo can be undone");
        assertEquals(List.of(alice.userId() + " " + item
                + " {\"undoneVersion\":2,\"restoredVersion\":1,\"newVersion\":3} 203.0.113.5"),
                audit(Items.AUDIT_MEDIA_UNDONE));

        // library.editAny may undo too: the undo of the undo
        page = items.undo(bob, item, EDIT_ANY, null);
        assertEquals(4, page.mediaVersion());
        assertEquals(3500L, page.durationMs());
        assertEquals("v2", currentAudio(item));
        assertEquals("{\"audioStartMs\":1500}", MediaVersions.currentEditJson(db, item));

        items.purge(item, (c, id, status) -> { });
        assertNull(items.get(alice, item, NONE).undo());
        assertEquals("nothing_to_undo", code(() -> items.undo(alice, item, NONE, null)));
        assertEquals(0, (int) db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT (SELECT COUNT(*) FROM media_undo WHERE item_id = ?)"
                    + " + (SELECT COUNT(*) FROM media_versions WHERE item_id = ?)")) {
                ps.setString(1, item);
                ps.setString(2, item);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }));
    }
}
