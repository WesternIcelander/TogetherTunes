package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.retention.References;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * The autoplay engine ({@code docs/rooms.md#autoplay}): keeps a room's autoplay section ("From
 * Playlist") at {@code autoplay.upcoming} entries, drawn from the playlists selected in the room
 * ({@link AutoplaySelections}) by {@link AutoplayPicker}. Runs on the room's executor after every
 * mutation that changed something ({@link RoomExecutor}), and after the ones that ask for it
 * ({@link RoomTx#refreshAutoplay}: the room loaded, selections or playlists changed, someone lost
 * the right to select).
 *
 * <p>What a run does:
 * <ol>
 *   <li>With a refresh: waiting autoplay entries whose song is no longer in its person's selected
 *       playlists (deselected, removed from the playlist, the playlist deleted, the person lost the
 *       right, the item isn't {@code ready} any more) are removed.</li>
 *   <li>Entries beyond {@code autoplay.upcoming} are removed from the end (the setting was
 *       lowered).</li>
 *   <li>Missing entries are added at the end of the section, each added by the person whose
 *       playlist it came from. Only {@code ready} items are picked. Adding never starts an idle
 *       room (play does, or a request).</li>
 * </ol>
 * A room without selections, or whose pool couldn't fill the section, remembers the queue it
 * failed on and doesn't read the database again until the queue changes or a refresh comes.
 */
final class Autoplay {
    private static final System.Logger LOG = System.getLogger(Autoplay.class.getName());
    /** {@code queue_entries.via} of an entry autoplay added. */
    static final String VIA_AUTOPLAY = "autoplay";
    /** At most this much play history is read for the weights. */
    private static final int MAX_HISTORY = 20_000;

    private Autoplay() {
    }

    /** Keeps the room's autoplay section filled; see the class comment. Never throws. */
    static void maintain(RoomTx tx, Random random) {
        try {
            run(tx, random);
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Autoplay failed in room " + tx.roomId(), e);
        }
    }

    private record Loaded(AutoplayPicker.Pool pool, Map<String, Set<String>> byUser, Map<String, Integer> ages,
                          String lastTurnUserId) {
    }

    private static void run(RoomTx tx, Random random) throws SQLException {
        RoomState s = tx.state();
        RoomSettings settings = s.settings();
        int upcoming = settings.autoplayUpcoming();
        boolean refresh = tx.refreshesAutoplay();
        List<QueueEntry> section = Playback.sectionOrder(s, QueueEntry.Section.AUTOPLAY);
        if (!refresh) {
            if (section.size() > upcoming) {
                trim(tx, section, upcoming);
                return;
            }
            if (section.size() == upcoming || fingerprint(s).equals(s.autoplayStuck())) {
                return;
            }
        }
        if (upcoming == 0 && section.isEmpty()) {
            s.autoplayStuck(null);
            return;
        }
        boolean turns = settings.autoplayTurns();
        int recentPct = settings.autoplayRecentPct();
        String roomId = s.id();
        // Read everything first: nothing below throws once the state starts changing.
        Loaded loaded = tx.store().db().read(c -> load(c, roomId, turns, recentPct));
        List<QueueEntry> kept = new ArrayList<>();
        for (QueueEntry e : section) {
            Set<String> items = e.addedByUserId() == null ? null : loaded.byUser().get(e.addedByUserId());
            if (refresh && (items == null || !items.contains(e.itemId()))) {
                remove(tx, e);
            } else {
                kept.add(e);
            }
        }
        if (kept.size() > upcoming) {
            trim(tx, kept, upcoming);
            kept = kept.subList(0, upcoming);
        }
        int need = upcoming - kept.size();
        List<QueueEntry> added = new ArrayList<>();
        if (need > 0) {
            Set<String> waiting = new HashSet<>();
            for (QueueEntry e : s.queue()) {
                waiting.add(e.itemId());
            }
            for (QueueEntry e : s.suggestions()) {
                waiting.add(e.itemId());
            }
            List<AutoplayPicker.Pick> picks = AutoplayPicker.pick(loaded.pool(), loaded.ages(), waiting, need, turns,
                    recentPct, loaded.lastTurnUserId(), random);
            if (!picks.isEmpty()) {
                Map<String, RoomStore.ItemRow> rows = tx.store().db().read(c -> {
                    Map<String, RoomStore.ItemRow> out = new HashMap<>();
                    for (AutoplayPicker.Pick p : picks) {
                        RoomStore.ItemRow row = RoomStore.item(c, p.itemId());
                        if (row != null) {
                            out.put(p.itemId(), row);
                        }
                    }
                    return out;
                });
                double position = QueueControl.endPosition(s);
                for (AutoplayPicker.Pick p : picks) {
                    RoomStore.ItemRow item = rows.get(p.itemId());
                    if (item != null) {
                        added.add(insert(tx, item, p.userId(), position++));
                    }
                }
            }
        }
        s.autoplayStuck(added.size() < need ? fingerprint(s) : null);
    }

    /** The selected songs, the play history for the weights, and whose turn was last. */
    private static Loaded load(Connection c, String roomId, boolean turns, int recentPct) throws SQLException {
        Map<String, Long> firstSelected = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT user_id, MIN(selected_at) FROM room_autoplay_selections"
                + " WHERE room_id = ? GROUP BY user_id")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    firstSelected.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        Map<String, Set<String>> byUser = new HashMap<>();
        if (!firstSelected.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT s.user_id, e.item_id"
                    + " FROM room_autoplay_selections s JOIN playlist_entries e ON e.playlist_id = s.playlist_id"
                    + " JOIN items i ON i.id = e.item_id WHERE s.room_id = ? AND i.status = 'ready'")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byUser.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(rs.getString(2));
                    }
                }
            }
        }
        List<String> order = new ArrayList<>(firstSelected.keySet());
        order.sort((x, y) -> {
            int byTime = Long.compare(firstSelected.get(x), firstSelected.get(y));
            return byTime != 0 ? byTime : x.compareTo(y);
        });
        List<AutoplayPicker.Person> people = new ArrayList<>();
        for (String user : order) {
            people.add(new AutoplayPicker.Person(user, byUser.getOrDefault(user, Set.of())));
        }
        AutoplayPicker.Pool pool = new AutoplayPicker.Pool(people);
        Map<String, Integer> ages = new HashMap<>();
        String last = null;
        if (!pool.isEmpty()) {
            int history = (int) Math.min(MAX_HISTORY,
                    Math.ceil(AutoplayPicker.rampLength(recentPct, pool.largest(turns))) + 1);
            List<String> recent = RoomStore.recentlyPlayedItems(c, roomId, history);
            for (int i = 0; i < recent.size(); i++) {
                ages.putIfAbsent(recent.get(i), i);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT added_by_user_id FROM queue_entries"
                    + " WHERE room_id = ? AND section = ? ORDER BY added_at DESC, position DESC, id DESC LIMIT 1")) {
                ps.setString(1, roomId);
                ps.setString(2, QueueEntry.Section.AUTOPLAY.wire());
                try (ResultSet rs = ps.executeQuery()) {
                    last = rs.next() ? rs.getString(1) : null;
                }
            }
        }
        return new Loaded(pool, byUser, ages, last);
    }

    /** Removes the entries of {@code section} (in order) after the first {@code keep}. */
    private static void trim(RoomTx tx, List<QueueEntry> section, int keep) {
        for (int i = section.size() - 1; i >= keep; i--) {
            remove(tx, section.get(i));
        }
    }

    private static void remove(RoomTx tx, QueueEntry e) {
        RoomState s = tx.state();
        String roomId = s.id();
        long now = tx.now();
        tx.persist(c -> {
            RoomStore.setEntryState(c, roomId, e.id(), QueueEntry.State.REMOVED, null, now);
            return null;
        });
        s.forget(e.id());
        tx.event(new RoomEvent.EntryStateChanged(e.id(), QueueEntry.State.REMOVED));
    }

    private static QueueEntry insert(RoomTx tx, RoomStore.ItemRow item, String userId, double position) {
        RoomState s = tx.state();
        long now = tx.now();
        QueueEntry e = new QueueEntry(UuidV7.newId(), item.id(), userId, null, now, QueueEntry.State.QUEUED,
                position, false, null, item.status(), item.durationMs(), tx.store().filesUsable(item),
                item.audioOnlyMs(), s.settings().audioOnly(), QueueEntry.Section.AUTOPLAY);
        String roomId = s.id();
        tx.persist(c -> {
            RoomStore.insertEntry(c, roomId, e, VIA_AUTOPLAY);
            References.touch(c, e.itemId(), now);
            return null;
        });
        s.addToQueue(e);
        tx.event(new RoomEvent.EntryAdded(e.id(), QueueEntry.State.QUEUED));
        return e;
    }

    /** What the queue and the autoplay settings look like now (the "nothing to add" memo's key). */
    private static String fingerprint(RoomState s) {
        StringBuilder b = new StringBuilder();
        RoomSettings settings = s.settings();
        b.append(settings.autoplayUpcoming()).append('|').append(settings.autoplayTurns()).append('|')
                .append(settings.autoplayRecentPct()).append('|')
                .append(s.current() == null ? "" : s.current().id());
        List<String> ids = new ArrayList<>();
        for (QueueEntry e : s.queue()) {
            ids.add(e.id());
        }
        for (QueueEntry e : s.suggestions()) {
            ids.add(e.id());
        }
        ids.sort(null);
        for (String id : ids) {
            b.append('|').append(id);
        }
        return b.toString();
    }
}
