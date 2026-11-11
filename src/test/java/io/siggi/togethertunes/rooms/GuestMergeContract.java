package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guest → account merge on a real database ({@link GuestMerge#merge}): entries of every state
 * (the current one, queued, suggested, history; requests are entries too), votes and skip votes
 * (the user's own vote wins a clash), in a loaded room and in one that wasn't loaded; the live state matches what a
 * reload reads; the guest's key is retired and the merge audited.
 */
abstract class GuestMergeContract {
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private Database db;
    private RoomRegistry registry;
    private final LinkedBlockingQueue<RoomChange> changes = new LinkedBlockingQueue<>();
    private Rooms rooms;
    private String aliceId;
    private String bobId;
    private String carolId;
    private String guestId;
    private String otherGuestId;
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
        rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        guestId = guest("Quiet Walrus");
        otherGuestId = guest("Loud Heron");
        registry = new RoomRegistry(db, clock, changes::add);
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

    private String guest(String name) throws SQLException {
        String id = UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'cookie', ?, ?, 1, 1)", id, "hash-" + id, name);
        return id;
    }

    private String room(String name) throws Exception {
        return rooms.create(new Actor.User(aliceId, "s-alice", Actor.Via.BEARER), name, null, null, null,
                "203.0.113.9").id();
    }

    /** A ready 60 s entry in this state, added by the user or the guest; returns its id. */
    private String entry(String roomId, String state, String userId, String byGuestId) throws SQLException {
        String itemId = UuidV7.newId();
        String entryId = UuidV7.newId();
        long addedAt = clock.millis();
        clock.advance(1);
        sql("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, duration_ms) VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'ready', '720p30', 1,"
                + " 60000)", itemId, aliceId);
        sql("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_by_guest_id, added_at, state,"
                + " position, pinned) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)", entryId, roomId, itemId, userId, byGuestId,
                addedAt, state, nextPosition++);
        return entryId;
    }

    private void vote(String entryId, String userId, String byGuestId, int value) throws SQLException {
        sql("INSERT INTO votes (entry_id, voter_user_id, voter_guest_id, value, at) VALUES (?, ?, ?, ?, 1)",
                entryId, userId, byGuestId, value);
    }

    private void skipVote(String entryId, String userId, String byGuestId) throws SQLException {
        sql("INSERT INTO skip_votes (entry_id, voter_user_id, voter_guest_id, at) VALUES (?, ?, ?, 1)",
                entryId, userId, byGuestId);
    }

    /** Marks an entry as a request made under {@code name} (requests are entries with {@code via}). */
    private void request(String entryId, String name) throws SQLException {
        sql("UPDATE queue_entries SET via = 'library', added_by_name = ? WHERE id = ?", name, entryId);
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

    private void reload() {
        registry.close();
        registry = new RoomRegistry(db, clock, changes::add);
    }

    /** What the room's live state says about entries, votes and skip votes. */
    private record Live(String currentAddedByUser, String currentAddedByGuest, Map<String, String> waitingAdders,
                        Map<String, Map<String, Integer>> votes, Set<String> skipVotes) {
    }

    private Live live(String roomId) throws Exception {
        return registry.call(roomId, tx -> {
            RoomState s = tx.state();
            Map<String, String> adders = new java.util.TreeMap<>();
            Map<String, Map<String, Integer>> votes = new java.util.TreeMap<>();
            List<QueueEntry> waiting = new ArrayList<>(s.queue());
            waiting.addAll(s.suggestions());
            for (QueueEntry e : waiting) {
                adders.put(e.id(), e.addedByUserId() != null ? "u:" + e.addedByUserId() : "g:" + e.addedByGuestId());
                votes.put(e.id(), new java.util.TreeMap<>(s.votesOn(e.id())));
            }
            QueueEntry cur = s.current();
            return new Live(cur == null ? null : cur.addedByUserId(), cur == null ? null : cur.addedByGuestId(),
                    adders, votes, Set.copyOf(s.skipVotes()));
        });
    }

    @Test
    void movesEntriesVotesAndRequestsToTheAccount() throws Exception {
        String g = "g:" + guestId;
        String u = "u:" + bobId;
        // Room one, loaded and playing: the guest's entry plays, more wait, one is history.
        String party = room("Party");
        String playing = entry(party, "queued", null, guestId);
        String guestQueued = entry(party, "queued", null, guestId);
        String bobQueued = entry(party, "queued", bobId, null);
        String carolQueued = entry(party, "queued", carolId, null);
        String guestSuggested = entry(party, "suggested", null, guestId);
        String otherGuests = entry(party, "queued", null, otherGuestId);
        String guestPlayed = entry(party, "played", null, guestId);
        assertTrue(registry.call(party, Playback::startIfIdle));
        assertEquals(playing, registry.call(party, tx -> tx.state().current().id()));
        vote(guestQueued, null, guestId, 1);
        vote(bobQueued, null, guestId, 1);     // Bob voted on it himself too: his vote stays
        vote(bobQueued, bobId, null, -1);
        vote(carolQueued, null, guestId, -1);
        vote(carolQueued, carolId, null, 1);
        vote(otherGuests, null, otherGuestId, 1);
        skipVote(playing, null, guestId);
        skipVote(playing, bobId, null);        // a clash: one skip vote remains
        skipVote(playing, carolId, null);
        request(guestQueued, "Quiet Walrus");
        request(guestPlayed, "Quiet Walrus");
        request(otherGuests, "Loud Heron");
        reload();
        Live before = live(party);
        assertEquals(guestId, before.currentAddedByGuest());
        assertEquals(2, before.votes().get(bobQueued).size(), "both votes on Bob's entry are live");
        assertEquals(Set.of(g, u, "u:" + carolId), before.skipVotes());

        // Room two, not loaded: only a vote of the guest.
        String lounge = room("Lounge");
        String aliceQueued = entry(lounge, "queued", aliceId, null);
        vote(aliceQueued, null, guestId, 1);
        String loungeOther = entry(lounge, "queued", null, otherGuestId);
        // Room three: nothing of the guest.
        String quiet = room("Quiet");
        entry(quiet, "queued", null, otherGuestId);
        String keyBefore = (String) row("SELECT key_hash FROM guests WHERE id = ?", guestId).get(0);
        changes.clear();

        GuestMerge.Moved moved = new GuestMerge(registry).merge(guestId, bobId, "203.0.113.20");
        // entries: playing, queued, suggested, played; votes: 3 moved + 1 dropped, skip votes 1
        // dropped, plus the lounge vote
        assertEquals(new GuestMerge.Moved(4, 5), moved);

        // The live state of the loaded room.
        Live after = live(party);
        assertEquals(bobId, after.currentAddedByUser());
        assertEquals(null, after.currentAddedByGuest());
        assertEquals(u, after.waitingAdders().get(guestQueued));
        assertEquals(u, after.waitingAdders().get(guestSuggested));
        assertEquals("g:" + otherGuestId, after.waitingAdders().get(otherGuests), "another guest's entry stays");
        assertEquals(Map.of(u, 1), after.votes().get(guestQueued));
        assertEquals(Map.of(u, -1), after.votes().get(bobQueued), "Bob's own vote wins");
        assertEquals(Map.of(u, -1, "u:" + carolId, 1), after.votes().get(carolQueued));
        assertEquals(Map.of("g:" + otherGuestId, 1), after.votes().get(otherGuests));
        assertEquals(Set.of(u, "u:" + carolId), after.skipVotes());
        assertEquals(playing, registry.call(party, tx -> tx.state().current().id()), "still playing the same entry");

        // The database.
        assertEquals(0L, count("SELECT COUNT(*) FROM queue_entries WHERE added_by_guest_id = ?", guestId));
        assertEquals(0L, count("SELECT COUNT(*) FROM votes WHERE voter_guest_id = ?", guestId));
        assertEquals(0L, count("SELECT COUNT(*) FROM skip_votes WHERE voter_guest_id = ?", guestId));
        assertEquals(5L, count("SELECT COUNT(*) FROM queue_entries WHERE added_by_user_id = ?", bobId),
                "the guest's four and Bob's own");
        assertEquals(List.of("Quiet Walrus", "Quiet Walrus"), row("SELECT added_by_name FROM queue_entries"
                + " WHERE added_by_user_id = ? AND via IS NOT NULL", bobId), "requests keep the name they were made under");
        assertEquals(List.of("Loud Heron"), row("SELECT added_by_name FROM queue_entries WHERE id = ?", otherGuests));
        assertEquals(List.of(otherGuestId), row("SELECT added_by_guest_id FROM queue_entries WHERE id = ?", loungeOther));
        assertEquals(1L, count("SELECT COUNT(*) FROM votes WHERE entry_id = ? AND voter_user_id = ?", aliceQueued,
                bobId), "the room that wasn't loaded");
        assertEquals(List.of(-1), row("SELECT value FROM votes WHERE entry_id = ? AND voter_user_id = ?", bobQueued,
                bobId).stream().map(o -> ((Number) o).intValue()).toList());
        assertEquals(2L, count("SELECT COUNT(*) FROM skip_votes WHERE entry_id = ?", playing));
        assertEquals(3L, count("SELECT COUNT(*) FROM queue_entries WHERE added_by_guest_id = ?", otherGuestId),
                "the other guest keeps its entries (one in each room)");

        // Broadcast: one change per touched room, none for the untouched one.
        Set<String> changedRooms = new java.util.HashSet<>();
        for (RoomChange c : changes) {
            assertTrue(c.events().contains(new RoomEvent.GuestMerged(guestId, bobId)), c.toString());
            changedRooms.add(c.roomId());
        }
        assertEquals(Set.of(party, lounge), changedRooms);
        assertNotNull(registry.loaded(lounge), "the lounge was loaded to merge it");
        assertEquals(null, registry.loaded(quiet));

        // The guest's key is retired, the guest row stays; the merge is audited.
        assertNotEquals(keyBefore, row("SELECT key_hash FROM guests WHERE id = ?", guestId).get(0));
        assertEquals(List.of(bobId, guestId), row("SELECT actor_user_id, target FROM audit_log WHERE action = ?",
                GuestMerge.AUDIT_MERGED));
        String details = (String) row("SELECT details_json FROM audit_log WHERE action = ?", GuestMerge.AUDIT_MERGED)
                .get(0);
        assertEquals(com.google.gson.JsonParser.parseString("{\"entries\":4,\"votes\":5}"),
                com.google.gson.JsonParser.parseString(details));

        // A reload reads what the live state had.
        reload();
        assertEquals(after, live(party));

        // Nothing left: merging again moves nothing.
        assertEquals(new GuestMerge.Moved(0, 0), new GuestMerge(registry).merge(guestId, bobId, null));
    }
}
