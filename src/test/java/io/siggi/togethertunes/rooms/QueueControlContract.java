package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The control modes on a real database ({@link QueueControl} on a {@link RoomExecutor}): votes
 * and downvotes ordering the queue, pins, suggestions accepted and rejected, vote-skip against the
 * presence threshold, the play order kept across a mode change, and all of it surviving a reload
 * from the database.
 */
abstract class QueueControlContract {
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private Database db;
    private RoomRegistry registry;
    private final LinkedBlockingQueue<RoomChange> changes = new LinkedBlockingQueue<>();
    private String roomId;
    private String aliceId;
    private String bobId;
    private String carolId;
    private String guestId;
    private RoomActor owner;
    private RoomActor bob;
    private RoomActor carol;
    private RoomActor guest;
    private double nextPosition = 1;

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        Permissions permissions = new Permissions(db, new Settings(db, config));
        Rooms rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        guestId = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO guests (id, kind, key_hash, display_name,"
                    + " created_at, last_seen_at) VALUES (?, 'cookie', ?, 'Quiet Walrus', 1, 1)")) {
                ps.setString(1, guestId);
                ps.setString(2, "hash-" + guestId);
                ps.executeUpdate();
            }
            return null;
        });
        roomId = rooms.create(new Actor.User(aliceId, "s-alice", Actor.Via.BEARER), "Party", null, null, null,
                "203.0.113.9").id();
        owner = RoomActor.user(aliceId, Rooms.Role.OWNER);
        bob = RoomActor.user(bobId, Rooms.Role.MEMBER);
        carol = RoomActor.user(carolId, null);
        guest = RoomActor.guest(guestId, true, true);
        registry = newRegistry();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private RoomRegistry newRegistry() {
        return new RoomRegistry(db, clock, changes::add);
    }

    /** Reloads the room from the database (a new registry, as after a restart). */
    private void reload() {
        registry.close();
        registry = newRegistry();
    }

    /** A ready 60 s entry in this state, added by Bob (or the guest). Returns {entryId, itemId}. */
    private String[] entry(String state, boolean byGuest) throws SQLException {
        String itemId = UuidV7.newId();
        String entryId = UuidV7.newId();
        double position = nextPosition++;
        long addedAt = clock.millis();
        clock.advance(1);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, duration_ms)"
                    + " VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'ready', '720p30', 1, 60000)")) {
                ps.setString(1, itemId);
                ps.setString(2, aliceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                    + " added_by_user_id, added_by_guest_id, added_at, state, position, pinned)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
                ps.setString(1, entryId);
                ps.setString(2, roomId);
                ps.setString(3, itemId);
                ps.setString(4, byGuest ? null : bobId);
                ps.setString(5, byGuest ? guestId : null);
                ps.setLong(6, addedAt);
                ps.setString(7, state);
                ps.setDouble(8, position);
                ps.executeUpdate();
            }
            return null;
        });
        return new String[] {entryId, itemId};
    }

    private String[] queued() throws SQLException {
        return entry("queued", false);
    }

    private void sql(String statement, Object... args) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private List<Object> row(String query, Object... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Object> out = new ArrayList<>();
                    while (rs.next()) {
                        for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                            out.add(rs.getObject(col));
                        }
                    }
                    return out;
                }
            }
        });
    }

    private long count(String query, Object... args) throws SQLException {
        return ((Number) row(query, args).get(0)).longValue();
    }

    private List<String> order() throws Exception {
        return registry.call(roomId, tx -> Playback.playOrder(tx.state()).stream().map(QueueEntry::id).toList());
    }

    private <T> T call(RoomMutation<T> m) throws Exception {
        return registry.call(roomId, m);
    }

    private boolean bool(RoomMutation<Boolean> m) throws Exception {
        return registry.call(roomId, m);
    }

    private HttpError refused(RoomMutation<?> m) {
        return assertThrows(HttpError.class, () -> registry.call(roomId, m));
    }

    private long version() throws Exception {
        return call(tx -> tx.state().version());
    }

    @Test
    void votesOrderTheQueueInVoteMode() throws Exception {
        String a = queued()[0];
        String b = queued()[0];
        String c = queued()[0];
        assertEquals(List.of(a, b, c), order(), "no votes: by time added");

        assertEquals(1, (int) call(tx -> QueueControl.vote(tx, bob, c, 1)));
        assertEquals(2, (int) call(tx -> QueueControl.vote(tx, guest, c, 1)));
        assertEquals(1, (int) call(tx -> QueueControl.vote(tx, bob, b, 1)));
        assertEquals(List.of(c, b, a), order());
        long v = version();
        assertEquals(1, (int) call(tx -> QueueControl.vote(tx, bob, b, 1)), "the same vote again");
        assertEquals(v, version(), "changes nothing");
        assertEquals(2L, count("SELECT COUNT(*) FROM votes WHERE entry_id = ?", c));

        // Ties are broken by time added: A and B both at 1, A was first.
        call(tx -> QueueControl.vote(tx, carol, a, 1));
        assertEquals(List.of(c, a, b), order());

        reload();
        assertEquals(List.of(c, a, b), order(), "votes survive a reload");
        assertEquals(2, (int) call(tx -> tx.state().voteTotal(c)));
        assertEquals(1, (int) call(tx -> tx.state().voteOf(c, guest.key())));

        // Taking a vote back.
        assertEquals(1, (int) call(tx -> QueueControl.vote(tx, guest, c, 0)));
        assertEquals(1L, count("SELECT COUNT(*) FROM votes WHERE entry_id = ?", c));

        // Downvotes need allowDownvotes.
        assertEquals("downvotes_disabled", refused(tx -> QueueControl.vote(tx, bob, a, -1)).code());
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"allowDownvotes\":true}", roomId);
        reload();
        assertEquals(-1, (int) call(tx -> QueueControl.vote(tx, bob, c, -1)), "Bob's +1 on C becomes -1");
        // A 1 (Carol), B 1 (Bob), C -1: A and B tie, A was added first.
        assertEquals(List.of(a, b, c), order());
        assertEquals(List.of(-1), row("SELECT value FROM votes WHERE entry_id = ? AND voter_user_id = ?", c, bobId)
                .stream().map(o -> ((Number) o).intValue()).toList());

        // Not on suggestions, unknown entries or the current entry; not without rights.
        assertEquals("entry_not_found", refused(tx -> QueueControl.vote(tx, bob, "nope", 1)).code());
        assertEquals("cannot_vote", refused(tx -> QueueControl.vote(tx, RoomActor.guest(guestId, true, false),
                a, 1)).code());
        assertEquals("identity_required", refused(tx -> QueueControl.vote(tx, RoomActor.ANONYMOUS, a, 1)).code());
        call(Playback::startIfIdle);
        assertEquals("entry_not_found", refused(tx -> QueueControl.vote(tx, bob, a, 1)).code(),
                "A is playing now");
    }

    @Test
    void votesOnlyInVoteMode() throws Exception {
        String a = queued()[0];
        sql("UPDATE rooms SET control_mode = 'open' WHERE id = ?", roomId);
        reload();
        assertEquals("wrong_control_mode", refused(tx -> QueueControl.vote(tx, bob, a, 1)).code());
        assertEquals("wrong_control_mode", refused(tx -> QueueControl.pin(tx, owner, a, true)).code());
        assertEquals("wrong_control_mode", refused(tx -> QueueControl.voteSkip(tx, bob)).code());
    }

    @Test
    void pinnedEntriesGoToTheTopInPinOrder() throws Exception {
        String a = queued()[0];
        String b = queued()[0];
        String c = queued()[0];
        String d = queued()[0];
        call(tx -> QueueControl.vote(tx, bob, b, 1));
        assertEquals(List.of(b, a, c, d), order());

        assertEquals("forbidden", refused(tx -> QueueControl.pin(tx, bob, d, true)).code());
        assertTrue(bool(tx -> QueueControl.pin(tx, owner, d, true)));
        assertTrue(bool(tx -> QueueControl.pin(tx, RoomActor.user(carolId, Rooms.Role.MODERATOR), c, true)));
        assertFalse(bool(tx -> QueueControl.pin(tx, owner, c, true)), "already pinned");
        assertEquals(List.of(d, c, b, a), order(), "pins first, the first pin on top, then votes");
        assertEquals(List.of(1), row("SELECT pinned FROM queue_entries WHERE id = ?", d).stream()
                .map(o -> ((Number) o).intValue()).toList());

        reload();
        assertEquals(List.of(d, c, b, a), order(), "pins survive a reload");

        assertTrue(bool(tx -> QueueControl.pin(tx, owner, d, false)));
        assertEquals(List.of(c, b, a, d), order(), "unpinned: back among the votes (by time added)");

        // The next entry to play is the top pin.
        call(Playback::startIfIdle);
        assertEquals(c, call(tx -> tx.state().current().id()));
    }

    @Test
    void suggestionsAreAcceptedOrRejectedByTheOwnerAndModerators() throws Exception {
        sql("UPDATE rooms SET control_mode = 'owner' WHERE id = ?", roomId);
        String[] s1 = entry("suggested", false);
        String[] s2 = entry("suggested", true);
        String[] s3 = entry("suggested", true);
        reload();
        assertEquals(List.of(s1[0], s2[0], s3[0]), call(tx -> Playback.suggestionOrder(tx.state()).stream()
                .map(QueueEntry::id).toList()));
        assertEquals(List.of(), order(), "suggestions aren't in the queue");
        assertEquals(ControlPolicy.AddAs.SUGGESTION, call(tx -> ControlPolicy.add(tx.state(), bob)));
        assertEquals(ControlPolicy.AddAs.QUEUE, call(tx -> ControlPolicy.add(tx.state(), owner)));

        assertEquals("forbidden", refused(tx -> {
            QueueControl.accept(tx, bob, s1[0]);
            return null;
        }).code());
        assertEquals("forbidden", refused(tx -> {
            QueueControl.reject(tx, guest, s1[0]);
            return null;
        }).code());

        // Accepting into an idle room starts it.
        call(tx -> {
            QueueControl.accept(tx, owner, s2[0]);
            return null;
        });
        assertEquals(s2[0], call(tx -> tx.state().current().id()));
        assertEquals(List.of("playing"), row("SELECT state FROM queue_entries WHERE id = ?", s2[0]));

        call(tx -> {
            QueueControl.accept(tx, RoomActor.user(carolId, Rooms.Role.MODERATOR), s3[0]);
            return null;
        });
        assertEquals(List.of(s3[0]), order());
        assertEquals("queued", row("SELECT state FROM queue_entries WHERE id = ?", s3[0]).get(0));

        clock.advance(5_000);
        call(tx -> {
            QueueControl.reject(tx, owner, s1[0]);
            return null;
        });
        assertEquals(List.of("removed", clock.millis()), List.of(
                row("SELECT state FROM queue_entries WHERE id = ?", s1[0]).get(0),
                ((Number) row("SELECT last_referenced_at FROM items WHERE id = ?", s1[1]).get(0)).longValue()));
        assertEquals(List.of(), call(tx -> tx.state().suggestions()));
        assertEquals("suggestion_not_found", refused(tx -> {
            QueueControl.accept(tx, owner, s1[0]);
            return null;
        }).code());
        assertEquals("suggestion_not_found", refused(tx -> {
            QueueControl.reject(tx, owner, s3[0]);
            return null;
        }).code(), "a queued entry isn't a suggestion");

        reload();
        assertEquals(List.of(s3[0]), order());
        assertEquals(List.of(), call(tx -> tx.state().suggestions()));
    }

    @Test
    void acceptedSuggestionsJoinTheEndOfAManualQueue() throws Exception {
        sql("UPDATE rooms SET control_mode = 'owner' WHERE id = ?", roomId);
        String a = queued()[0];
        String s = entry("suggested", true)[0];
        String b = queued()[0];
        String c = queued()[0];
        reload();
        call(Playback::startIfIdle);
        assertEquals(a, call(tx -> tx.state().current().id()));
        call(tx -> {
            QueueControl.accept(tx, owner, s);
            return null;
        });
        assertEquals(List.of(b, c, s), order());
    }

    /** Sets the presence to {@code n} identified listeners (connections {@code c0..}). */
    private static void present(RoomTx tx, int n) {
        RoomPresence p = tx.state().presence();
        for (int i = 0; i < 16; i++) {
            p.leave("c" + i);
        }
        for (int i = 0; i < n; i++) {
            p.join("c" + i, "u:present-" + i, RoomPresence.Mode.LISTEN);
        }
    }

    @Test
    void voteSkipReachesTheThresholdOfPresentMembers() throws Exception {
        String a = queued()[0];
        String b = queued()[0];
        String c = queued()[0];
        call(Playback::startIfIdle);
        assertEquals("identity_required", refused(tx -> QueueControl.voteSkip(tx, RoomActor.ANONYMOUS)).code());
        call(tx -> {
            present(tx, 4);
            return null;
        });

        QueueControl.VoteSkip r = call(tx -> QueueControl.voteSkip(tx, bob));
        assertEquals(new QueueControl.VoteSkip(1, 2, false), r);
        assertEquals(new QueueControl.VoteSkip(1, 2, false), call(tx -> QueueControl.voteSkip(tx, bob)),
                "voting twice counts once");
        assertEquals(1L, count("SELECT COUNT(*) FROM skip_votes WHERE entry_id = ?", a));

        reload(); // skip votes survive a reload (presence doesn't here: a new executor, no connections)
        assertEquals(1, (int) call(tx -> tx.state().skipVotes().size()));
        call(tx -> {
            present(tx, 4);
            return null;
        });
        changes.clear();
        r = call(tx -> QueueControl.voteSkip(tx, guest));
        assertEquals(new QueueControl.VoteSkip(2, 2, true), r);
        assertEquals("skipped", row("SELECT state FROM queue_entries WHERE id = ?", a).get(0));
        assertEquals(b, call(tx -> tx.state().current().id()));
        assertEquals(0, (int) call(tx -> tx.state().skipVotes().size()), "a new entry starts with no skip votes");
        RoomChange change = changes.take();
        assertTrue(change.events().contains(new RoomEvent.SkipVotesChanged(a, 2, 2)));
        assertTrue(change.events().contains(new RoomEvent.EntryStateChanged(a, QueueEntry.State.SKIPPED)));

        // Presence drops: fewer votes are needed, and the check skips.
        assertEquals(new QueueControl.VoteSkip(1, 2, false), call(tx -> QueueControl.voteSkip(tx, carol)));
        assertFalse(bool(QueueControl::checkVoteSkip));
        assertTrue(bool(tx -> {
            present(tx, 2);
            return QueueControl.checkVoteSkip(tx);
        }));
        assertEquals(c, call(tx -> tx.state().current().id()));

        // Off: refused.
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"voteSkipThreshold\":\"off\"}", roomId);
        reload();
        assertEquals("vote_skip_off", refused(tx -> QueueControl.voteSkip(tx, bob)).code());

        // Nothing playing.
        sql("UPDATE rooms SET settings_json = '{}' WHERE id = ?", roomId);
        reload();
        call(tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals("nothing_playing", refused(tx -> QueueControl.voteSkip(tx, bob)).code());
    }

    @Test
    void aModeChangeKeepsThePlayOrder() throws Exception {
        String a = queued()[0];
        String b = queued()[0];
        String c = queued()[0];
        String d = queued()[0];
        call(tx -> QueueControl.vote(tx, bob, c, 1));
        call(tx -> QueueControl.pin(tx, owner, d, true));
        List<String> voteOrder = order();
        assertEquals(List.of(d, c, a, b), voteOrder);

        changes.clear();
        call(tx -> {
            QueueControl.controlModeChanging(tx, Rooms.ControlMode.OWNER);
            RoomState s = tx.state();
            s.record(s.slug(), s.name(), s.ownerId(), s.visibility(), Rooms.ControlMode.OWNER);
            tx.event(new RoomEvent.RoomUpdated());
            return null;
        });
        sql("UPDATE rooms SET control_mode = 'owner' WHERE id = ?", roomId);
        assertTrue(changes.take().events().contains(new RoomEvent.QueueReordered()));
        assertEquals(voteOrder, order(), "manual order = the old vote order");
        assertEquals(List.of(d, 1.0, 0, c, 2.0, 0, a, 3.0, 0, b, 4.0, 0), normalise(row(
                "SELECT id, position, pinned FROM queue_entries WHERE room_id = ? ORDER BY position", roomId)));
        reload();
        assertEquals(voteOrder, order());
    }

    /** Numbers as double (position) or int (pinned), ids as they are. */
    private static List<Object> normalise(List<Object> row) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            Object o = row.get(i);
            out.add(switch (i % 3) {
                case 1 -> ((Number) o).doubleValue();
                case 2 -> ((Number) o).intValue();
                default -> o;
            });
        }
        return out;
    }
}
