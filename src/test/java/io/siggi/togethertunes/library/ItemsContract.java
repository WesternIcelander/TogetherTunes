package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.siggi.togethertunes.ingest.IngestPipeline;
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
        return new Items.Edit(title, null, false, null, false, null);
    }

    private static String code(Executable e) {
        return assertThrows(HttpError.class, e::run).code();
    }

    @FunctionalInterface
    private interface Executable {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ get

    @Test
    void pageShowsTheItemAndTheCallersRights() throws Exception {
        String id = aliceItem("audio");
        Items.ItemPage page = items.get(alice, id, NONE);
        assertEquals(new Items.ItemPage(id, "audio", "Song", "Artist", null, 5000L, "upload", null, "ready", false, 7,
                null, 0, new Items.You(true, true, false)), page);

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
                false, null), NONE, "192.0.2.5");
        assertEquals("New Title", edited.title());
        assertEquals("New Artist", edited.artist());
        assertEquals("Album", edited.album());
        assertEquals(edited, items.get(alice, id, NONE));
        assertEquals(List.of(alice.userId() + " " + id
                        + " {\"title\":\"New Title\",\"artist\":\"New Artist\",\"album\":\"Album\"} 192.0.2.5"),
                audit(Items.AUDIT_UPDATED));

        // clearing artist and album; an unchanged edit writes nothing
        Items.ItemPage cleared = items.update(alice, id, new Items.Edit(null, null, true, null, true, null), NONE, null);
        assertNull(cleared.artist());
        assertNull(cleared.album());
        assertEquals("New Title", cleared.title());
        assertNull(column(id, "artist"));
        items.update(alice, id, title("New Title"), NONE, null);
        items.update(alice, id, new Items.Edit(null, null, false, null, false, null), NONE, null);
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

    @Test
    void presentAsAudioOnlyForVideoItems() throws Exception {
        String audio = aliceItem("audio");
        String video = aliceItem("video");
        Items.Edit on = new Items.Edit(null, null, false, null, false, true);
        assertEquals("not_a_video", code(() -> items.update(alice, audio, on, NONE, null)));
        // false on an audio item is no change
        assertFalse(items.update(alice, audio, new Items.Edit(null, null, false, null, false, false), NONE, null)
                .presentAsAudio());
        assertTrue(items.update(alice, video, on, NONE, null).presentAsAudio());
        assertEquals("1", column(video, "present_as_audio"));
        assertFalse(items.update(alice, video, new Items.Edit(null, null, false, null, false, false), NONE, null)
                .presentAsAudio());
        assertEquals(List.of(alice.userId() + " " + video + " {\"presentAsAudio\":true} null",
                alice.userId() + " " + video + " {\"presentAsAudio\":false} null"), audit(Items.AUDIT_UPDATED));
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
        Items.Purged purged = items.purge(id, items.auditedBy(bob.userId(), "2001:db8::1"));
        assertEquals(new Items.Purged("ready", 0), purged);
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
}
