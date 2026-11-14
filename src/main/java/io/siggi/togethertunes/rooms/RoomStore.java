package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.ingest.PlayableFiles;
import io.siggi.togethertunes.retention.References;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads a {@link RoomState} from the {@code rooms} row and its queue entries, and writes its
 * version and timeline back.
 */
final class RoomStore {
    private static final System.Logger LOG = System.getLogger(RoomStore.class.getName());

    private final Database db;
    /** Whether the program output can play a ready item's files (one notion for rooms and packager). */
    private volatile PlayableFiles.Check playable = PlayableFiles.Check.ANY;
    /** Autoplay's randomness ({@link Autoplay}); seeded in tests. */
    private volatile java.util.Random random = new java.security.SecureRandom();

    RoomStore(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    Database db() {
        return db;
    }

    void playable(PlayableFiles.Check check) {
        this.playable = check == null ? PlayableFiles.Check.ANY : check;
    }

    PlayableFiles.Check playable() {
        return playable;
    }

    void random(java.util.Random random) {
        this.random = Objects.requireNonNull(random);
    }

    java.util.Random random() {
        return random;
    }

    /** Whether the program output can play this item's files (true while it isn't {@code ready}: not asked). */
    boolean filesUsable(ItemRow item) {
        return filesUsable(playable, item.id(), item.status(), item.kind(), item.outputProfile(), item.mediaVersion());
    }

    private static boolean filesUsable(PlayableFiles.Check check, String itemId, String status, String kind,
                                       String outputProfile, int mediaVersion) {
        return !"ready".equals(status) || check.usable(new PlayableFiles.Item(itemId, kind, outputProfile, mediaVersion));
    }

    /** The room's state as persisted, or {@code null} if there is no such room. */
    RoomState load(String roomId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT slug, name, owner_id, visibility, control_mode,"
                    + " settings_json, playback_state, current_entry_id, anchor_server_ms, anchor_position_ms, version,"
                    + " current_audio_only, alone_user_id,"
                    + " (SELECT s.room_id FROM alone_sessions s WHERE s.user_id = rooms.alone_user_id)"
                    + " FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    String stateName = rs.getString(7);
                    PlaybackState state = PlaybackState.parse(stateName);
                    String entryId = rs.getString(8);
                    long anchorServer = rs.getLong(9);
                    long anchorPosition = rs.getLong(10);
                    if (state == null) {
                        // Written by a newer version. Freeze it, as clients do with an unknown state.
                        LOG.log(System.Logger.Level.WARNING, "Room " + roomId + " has unknown playback_state '"
                                + stateName + "'; treating it as paused");
                        state = entryId == null ? PlaybackState.IDLE : PlaybackState.PAUSED;
                    }
                    String slug = rs.getString(1);
                    String name = rs.getString(2);
                    String ownerId = rs.getString(3);
                    Rooms.Visibility visibility = Objects.requireNonNull(Rooms.Visibility.parse(rs.getString(4)),
                            "visibility");
                    Rooms.ControlMode mode = Objects.requireNonNull(Rooms.ControlMode.parse(rs.getString(5)),
                            "control_mode");
                    String settings = rs.getString(6);
                    long version = rs.getLong(11);
                    boolean currentAudioOnly = rs.getInt(12) != 0;
                    List<QueueEntry> entries = entries(c, roomId, entryId, playable);
                    QueueEntry current = null;
                    List<QueueEntry> queue = new ArrayList<>();
                    List<QueueEntry> suggestions = new ArrayList<>();
                    for (QueueEntry e : entries) {
                        if (e.id().equals(entryId)) {
                            current = e.withAudioOnly(currentAudioOnly); // the mode it started with
                        } else if (e.state() == QueueEntry.State.SUGGESTED) {
                            suggestions.add(e);
                        } else {
                            queue.add(e);
                        }
                    }
                    Timeline timeline = switch (state) {
                        case IDLE -> Timeline.IDLE;
                        case PLAYING, PAUSED -> current == null ? Timeline.IDLE
                                : new Timeline(state, entryId, anchorServer, anchorPosition);
                    };
                    if (timeline.entryId() == null) {
                        current = null;
                    }
                    Map<String, Map<String, Integer>> votes = votes(c, roomId);
                    if (current != null) {
                        votes.remove(current.id());
                    }
                    Set<String> skipVotes = current == null ? Set.of() : skipVotes(c, current.id());
                    String aloneUserId = rs.getString(13);
                    String awayIn = rs.getString(14);
                    RoomState loaded = new RoomState(roomId, slug, name, ownerId, visibility, mode, settings, timeline,
                            version, current, queue, suggestions, votes, skipVotes);
                    loaded.alone(aloneUserId, awayIn);
                    return loaded;
                }
            }
        });
    }

    private static final String ENTRY_COLUMNS = "q.id, q.item_id, q.added_by_user_id, q.added_by_guest_id,"
            + " q.added_at, q.state, q.position, q.pinned, q.played_at, i.status, i.duration_ms,"
            + " i.kind, i.output_profile, i.media_version,"
            + " CASE WHEN i.kind = 'video' THEN i.audio_only_ms ELSE NULL END, q.section";

    /** Votes on the room's queued and suggested entries: entryId -> voter key -> value. */
    private static Map<String, Map<String, Integer>> votes(Connection c, String roomId) throws SQLException {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT v.entry_id, v.voter_user_id, v.voter_guest_id, v.value"
                + " FROM votes v JOIN queue_entries q ON q.id = v.entry_id"
                + " WHERE q.room_id = ? AND q.state IN ('queued', 'suggested') AND v.value <> 0")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                            .put(voterKey(rs.getString(2), rs.getString(3)), Integer.signum(rs.getInt(4)));
                }
            }
        }
        return out;
    }

    /** Voter keys of the skip votes on an entry. */
    private static Set<String> skipVotes(Connection c, String entryId) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT voter_user_id, voter_guest_id FROM skip_votes"
                + " WHERE entry_id = ? ORDER BY at")) {
            ps.setString(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(voterKey(rs.getString(1), rs.getString(2)));
                }
            }
        }
        return out;
    }

    /** The key {@link RoomActor#key} gives the voter with these columns. */
    static String voterKey(String userId, String guestId) {
        return userId != null ? "u:" + userId : "g:" + guestId;
    }

    /**
     * The room's {@code queued} and {@code suggested} entries and the entry {@code currentId}
     * (whatever its state; may be {@code null}), with their items' status and duration. Entries with a state this version
     * doesn't know are left out (logged).
     */
    static List<QueueEntry> entries(Connection c, String roomId, String currentId, PlayableFiles.Check check)
            throws SQLException {
        List<QueueEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ENTRY_COLUMNS
                + " FROM queue_entries q JOIN items i ON i.id = q.item_id"
                + " WHERE q.room_id = ? AND (q.state IN ('queued', 'suggested') OR q.id = ?)"
                + " ORDER BY q.position, q.added_at, q.id")) {
            ps.setString(1, roomId);
            ps.setString(2, currentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    QueueEntry e = entry(rs, roomId, check);
                    if (e != null) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    /** The entry of an {@link #ENTRY_COLUMNS} row, or {@code null} (logged) if its state is unknown. */
    private static QueueEntry entry(ResultSet rs, String roomId, PlayableFiles.Check check) throws SQLException {
        QueueEntry.State state = QueueEntry.State.parse(rs.getString(6));
        if (state == null) {
            LOG.log(System.Logger.Level.WARNING, "Queue entry " + rs.getString(1) + " in room " + roomId
                    + " has unknown state '" + rs.getString(6) + "'; ignoring it");
            return null;
        }
        long playedAt = rs.getLong(9);
        Long played = rs.wasNull() ? null : playedAt;
        long duration = rs.getLong(11);
        Long durationMs = rs.wasNull() ? null : duration;
        String status = rs.getString(10);
        boolean usable = filesUsable(check, rs.getString(2), status, rs.getString(12), rs.getString(13), rs.getInt(14));
        long audioOnly = rs.getLong(15);
        Long audioOnlyMs = rs.wasNull() ? null : audioOnly;
        QueueEntry.Section section = QueueEntry.Section.parse(rs.getString(16));
        if (section == null) {
            // written by a newer version: treat it as manually added (it stays where it is)
            section = QueueEntry.Section.MANUAL;
        }
        // audio-only mode (false here) is the room's business: RoomState sets it
        return new QueueEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), state, rs.getDouble(7), rs.getInt(8) != 0, played, status, durationMs, usable,
                audioOnlyMs, false, section);
    }

    /**
     * The entry that played (or was skipped) most recently, other than {@code exceptId}, with its
     * item; {@code null} if there is none. For {@code previous}.
     */
    static QueueEntry lastPlayed(Connection c, String roomId, String exceptId, PlayableFiles.Check check)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ENTRY_COLUMNS
                + " FROM queue_entries q JOIN items i ON i.id = q.item_id"
                + " WHERE q.room_id = ? AND q.state IN ('played', 'skipped') AND q.played_at IS NOT NULL"
                + " AND q.id <> ? ORDER BY q.played_at DESC, q.id DESC LIMIT 1")) {
            ps.setString(1, roomId);
            ps.setString(2, exceptId == null ? "" : exceptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? entry(rs, roomId, check) : null;
            }
        }
    }

    /**
     * The items of the last {@code n} entries that started playing (the current one included),
     * most recent first. For {@code noRepeatWindow}.
     */
    static List<String> recentlyPlayedItems(Connection c, String roomId, int n) throws SQLException {
        List<String> out = new ArrayList<>();
        if (n <= 0) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT item_id FROM queue_entries"
                + " WHERE room_id = ? AND played_at IS NOT NULL ORDER BY played_at DESC, id DESC LIMIT ?")) {
            ps.setString(1, roomId);
            ps.setInt(2, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** An item as a queue entry needs it ({@code audioOnlyMs}: a video's audio-only file's length, or null). */
    record ItemRow(String id, String status, Long durationMs, String source, String kind, String outputProfile,
                   int mediaVersion, Long audioOnlyMs) {
        ItemRow(String id, String status, Long durationMs, String source, String kind, String outputProfile,
                int mediaVersion) {
            this(id, status, durationMs, source, kind, outputProfile, mediaVersion, null);
        }
    }

    /** The item, or {@code null} if there is no such item. */
    static ItemRow item(Connection c, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, status, duration_ms, source, kind,"
                + " output_profile, media_version, audio_only_ms FROM items"
                + " WHERE id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long d = rs.getLong(3);
                Long duration = rs.wasNull() ? null : d;
                long a = rs.getLong(8);
                Long audioOnlyMs = rs.wasNull() || !"video".equals(rs.getString(5)) ? null : a;
                return new ItemRow(rs.getString(1), rs.getString(2), duration, rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7), audioOnlyMs);
            }
        }
    }

    /** Inserts a new {@code queue_entries} row for {@code e} in the room, with no {@code via}. */
    static void insertEntry(Connection c, String roomId, QueueEntry e) throws SQLException {
        insertEntry(c, roomId, e, null);
    }

    /**
     * Inserts a new {@code queue_entries} row for {@code e} in the room. The adder's display name
     * now is kept with it ({@code added_by_name}, for the history).
     *
     * @param via how it was added ({@code library}, {@code upload}, {@code youtube},
     *            {@code playlist}, {@code history}), or {@code null}
     */
    static void insertEntry(Connection c, String roomId, QueueEntry e, String via) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                + " added_by_user_id, added_by_guest_id, added_at, state, position, pinned, played_at, via,"
                + " added_by_name, section) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?,"
                + " COALESCE((SELECT display_name FROM users WHERE id = ?),"
                + " (SELECT display_name FROM guests WHERE id = ?)), ?)")) {
            ps.setString(13, e.section().wire());
            ps.setString(10, via);
            ps.setString(11, e.addedByUserId());
            ps.setString(12, e.addedByGuestId());
            ps.setString(1, e.id());
            ps.setString(2, roomId);
            ps.setString(3, e.itemId());
            ps.setString(4, e.addedByUserId());
            ps.setString(5, e.addedByGuestId());
            ps.setLong(6, e.addedAt());
            ps.setString(7, e.state().wire());
            ps.setDouble(8, e.position());
            ps.setInt(9, e.pinned() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /**
     * Moves a waiting entry to another section, now added by {@code e}'s adder (an autoplay entry
     * turned into someone's request), with its state, position and pin from {@code e}. The adder's
     * display name now is kept with it ({@code added_by_name}).
     */
    static void setSection(Connection c, String roomId, QueueEntry e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE queue_entries SET section = ?, added_by_user_id = ?,"
                + " added_by_guest_id = ?, added_by_name = COALESCE((SELECT display_name FROM users WHERE id = ?),"
                + " (SELECT display_name FROM guests WHERE id = ?)), state = ?, position = ?, pinned = ?"
                + " WHERE id = ? AND room_id = ?")) {
            ps.setString(1, e.section().wire());
            ps.setString(2, e.addedByUserId());
            ps.setString(3, e.addedByGuestId());
            ps.setString(4, e.addedByUserId());
            ps.setString(5, e.addedByGuestId());
            ps.setString(6, e.state().wire());
            ps.setDouble(7, e.position());
            ps.setInt(8, e.pinned() ? 1 : 0);
            ps.setString(9, e.id());
            ps.setString(10, roomId);
            ps.executeUpdate();
        }
    }

    /** Deletes the votes and skip votes on an entry (it is becoming current, or going back to the queue). */
    static void clearVotes(Connection c, String entryId) throws SQLException {
        for (String table : new String[] {"votes", "skip_votes"}) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE entry_id = ?")) {
                ps.setString(1, entryId);
                ps.executeUpdate();
            }
        }
    }

    /**
     * The current status and duration of the items of {@code state}'s current and queued
     * entries ({@code entryId -> item}): ingest finishes on its own threads, so the executor reads
     * them again before choosing what to play.
     */
    Map<String, RoomState.ItemInfo> itemInfo(RoomState state) throws SQLException {
        String currentId = state.current() == null ? null : state.current().id();
        return db.read(c -> {
            Map<String, RoomState.ItemInfo> out = new HashMap<>();
            for (QueueEntry e : entries(c, state.id(), currentId, playable)) {
                out.put(e.id(), new RoomState.ItemInfo(e.itemStatus(), e.durationMs(), e.filesUsable(),
                        e.audioOnlyMs()));
            }
            return out;
        });
    }

    /**
     * Sets an entry's state (and {@code played_at}, if not {@code null}), and touches its item's
     * {@code last_referenced_at} ({@link References#touchQueueEntry}): every way an entry stops
     * (played, skipped, removed, rejected) or starts again (previous) being a reference goes
     * through here, so retention can't miss one. An entry becoming {@code playing} (current) is a
     * view of its item ({@link io.siggi.togethertunes.library.Views#queueEntry}).
     */
    static void setEntryState(Connection c, String roomId, String entryId, QueueEntry.State state, Long playedAt,
                              long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE queue_entries SET state = ?,"
                + " played_at = COALESCE(?, played_at) WHERE id = ? AND room_id = ?")) {
            ps.setString(1, state.wire());
            if (playedAt == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, playedAt);
            }
            ps.setString(3, entryId);
            ps.setString(4, roomId);
            if (ps.executeUpdate() == 0) {
                return;
            }
        }
        References.touchQueueEntry(c, entryId, now);
        if (state == QueueEntry.State.PLAYING) {
            // the entry became current: a view of its item (docs/media-types.md#views-auto-delete-and-pins)
            io.siggi.togethertunes.library.Views.queueEntry(c, entryId, now);
        }
    }

    /**
     * Writes the version and the timeline of {@code state} (with {@code version}) to its row, and
     * whether its current entry plays audio-only ({@code current_audio_only}: the mode it started
     * with, kept across restarts).
     *
     * @return {@code false} if the row is gone (the room was deleted)
     */
    static boolean save(Connection c, RoomState state, long version) throws SQLException {
        Timeline t = state.timeline();
        try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET version = ?, playback_state = ?,"
                + " current_entry_id = ?, anchor_server_ms = ?, anchor_position_ms = ?, current_audio_only = ?"
                + " WHERE id = ?")) {
            ps.setLong(1, version);
            ps.setString(2, t.state().wire());
            ps.setString(3, t.entryId());
            ps.setLong(4, t.anchorServerMs());
            ps.setLong(5, t.anchorPositionMs());
            ps.setInt(6, state.current() != null && state.current().audioOnly() ? 1 : 0);
            ps.setString(7, state.id());
            return ps.executeUpdate() == 1;
        }
    }
}
