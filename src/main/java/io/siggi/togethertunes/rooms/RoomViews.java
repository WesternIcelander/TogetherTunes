package io.siggi.togethertunes.rooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the WebSocket room messages ({@code docs/api.md#websocket}) need from the database, and
 * their JSON shapes: item metadata and media URLs, the names of the users and guests who added
 * entries, and the room's recent history.
 *
 * <p>Reads only columns that hold no IP address ({@code items}, {@code users.display_name},
 * {@code guests.display_name}, {@code queue_entries}); actors are {@link ActorRef}s
 * ({@code {kind, id, name, isGuest}}).
 */
final class RoomViews {
    /** How many played or skipped entries the room state lists as {@code history}. */
    static final int HISTORY_SIZE = 20;
    private static final int IN_CHUNK = 500;

    private final Database db;

    RoomViews(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    /** An item's columns as the room messages show it. */
    record ItemRow(String id, String kind, String title, String artist, Long durationMs, String status, String source,
                   boolean presentAsAudio, String originalFile, boolean originalWeb, Double originalGainDb) {
    }

    /** A played or skipped entry of the room's history. */
    record HistoryRow(String entryId, String itemId, String addedByUserId, String addedByGuestId, long addedAt,
                      String state, long playedAt) {
    }

    /** Items and actors looked up for one message. */
    record Lookup(Map<String, ItemRow> items, Map<String, ActorRef> actors) {
    }

    /** The room's last {@link #HISTORY_SIZE} played or skipped entries, latest first. */
    List<HistoryRow> history(String roomId) throws SQLException {
        return db.read(c -> {
            List<HistoryRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, item_id, added_by_user_id, added_by_guest_id,"
                    + " added_at, state, played_at FROM queue_entries WHERE room_id = ? AND state IN ('played', 'skipped')"
                    + " AND played_at IS NOT NULL ORDER BY played_at DESC, id DESC LIMIT " + HISTORY_SIZE)) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new HistoryRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getLong(5), rs.getString(6), rs.getLong(7)));
                    }
                }
            }
            return out;
        });
    }

    /** Looks up the items and adders of these entries and history rows. */
    Lookup lookup(Collection<QueueEntry> entries, Collection<HistoryRow> history) throws SQLException {
        Set<String> itemIds = new LinkedHashSet<>();
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> guestIds = new LinkedHashSet<>();
        for (QueueEntry e : entries) {
            itemIds.add(e.itemId());
            add(userIds, e.addedByUserId());
            add(guestIds, e.addedByGuestId());
        }
        for (HistoryRow h : history) {
            itemIds.add(h.itemId());
            add(userIds, h.addedByUserId());
            add(guestIds, h.addedByGuestId());
        }
        return db.read(c -> {
            Map<String, ItemRow> items = new HashMap<>();
            for (List<String> chunk : chunks(itemIds)) {
                try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, title, artist, duration_ms, status,"
                        + " source, present_as_audio, original_file, original_web, original_gain_db FROM items"
                        + " WHERE id IN (" + marks(chunk.size()) + ")")) {
                    bind(ps, chunk);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long duration = rs.getLong(5);
                            Long durationMs = rs.wasNull() ? null : duration;
                            double gain = rs.getDouble(11);
                            Double gainDb = rs.wasNull() ? null : gain;
                            items.put(rs.getString(1), new ItemRow(rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), durationMs, rs.getString(6), rs.getString(7), rs.getInt(8) != 0,
                                    rs.getString(9), rs.getInt(10) != 0, gainDb));
                        }
                    }
                }
            }
            Map<String, ActorRef> actors = new HashMap<>();
            names(c, "users", userIds, (id, name) -> actors.put("u:" + id, ActorRef.user(id, name)));
            names(c, "guests", guestIds, (id, name) -> actors.put("g:" + id, ActorRef.guest(id, name)));
            return new Lookup(items, actors);
        });
    }

    private interface NameSink {
        void put(String id, String name);
    }

    private static void names(Connection c, String table, Set<String> ids, NameSink sink) throws SQLException {
        for (List<String> chunk : chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, display_name FROM " + table
                    + " WHERE id IN (" + marks(chunk.size()) + ")")) {
                bind(ps, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sink.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
        }
    }

    private static void add(Set<String> set, String id) {
        if (id != null) {
            set.add(id);
        }
    }

    private static List<List<String>> chunks(Collection<String> ids) {
        List<List<String>> out = new ArrayList<>();
        List<String> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + IN_CHUNK)));
        }
        return out;
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private static void bind(PreparedStatement ps, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setString(i + 1, values.get(i));
        }
    }

    // ------------------------------------------------------------------ JSON

    /**
     * An item: {@code id, kind, presentAsAudio, title, artist?, durationMs?, status}, and once it
     * is {@code ready} its media URLs: {@code coverUrl}, {@code audioUrl}, {@code videoUrl} (video
     * items), and for an audio upload whose original browsers can play, {@code originalUrl} with
     * {@code originalGainDb} (the gain that brings it to the loudness of {@code audio.m4a}).
     */
    static JsonObject item(Lookup l, String itemId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", itemId);
        ItemRow r = l.items().get(itemId);
        if (r == null) {
            return o;
        }
        o.addProperty("kind", r.kind());
        o.addProperty("presentAsAudio", r.presentAsAudio());
        o.addProperty("title", r.title());
        if (r.artist() != null) {
            o.addProperty("artist", r.artist());
        }
        if (r.durationMs() != null) {
            o.addProperty("durationMs", r.durationMs());
        }
        o.addProperty("status", r.status());
        if ("ready".equals(r.status())) {
            String base = "/media/" + r.id() + "/";
            o.addProperty("coverUrl", base + "cover.jpg");
            o.addProperty("audioUrl", base + "audio.m4a");
            if ("video".equals(r.kind())) {
                o.addProperty("videoUrl", base + "mezz.mp4");
            }
            if ("audio".equals(r.kind()) && "upload".equals(r.source()) && r.originalWeb()
                    && r.originalFile() != null) {
                o.addProperty("originalUrl", base + "original");
                o.addProperty("originalGainDb", r.originalGainDb() == null ? 0.0 : r.originalGainDb());
            }
        }
        return o;
    }

    /** {@code ready} (playable), {@code failed} / {@code purged} (never will be) or {@code processing}. */
    static String entryStatus(QueueEntry e) {
        if (e.ready()) {
            return "ready";
        }
        return switch (e.itemStatus()) {
            case "failed", "purged" -> e.itemStatus();
            default -> "processing";
        };
    }

    /** The actor who added an entry, or {@code null} if it is gone. */
    static ActorRef addedBy(Lookup l, String userId, String guestId) {
        if (userId != null) {
            return l.actors().get("u:" + userId);
        }
        return guestId == null ? null : l.actors().get("g:" + guestId);
    }

    static JsonObject actor(ActorRef a) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", a.kind());
        o.addProperty("id", a.id());
        o.addProperty("name", a.name());
        o.addProperty("isGuest", a.isGuest());
        return o;
    }

    /**
     * A queued or suggested entry, without {@code yourVote} (added per socket):
     * {@code entryId, status, item, addedBy?, addedAt, votes, pinned}.
     */
    static JsonObject entry(Lookup l, QueueEntry e, RoomSnapshot s) {
        JsonObject o = new JsonObject();
        o.addProperty("entryId", e.id());
        o.addProperty("status", entryStatus(e));
        o.add("item", item(l, e.itemId()));
        ActorRef by = addedBy(l, e.addedByUserId(), e.addedByGuestId());
        if (by != null) {
            o.add("addedBy", actor(by));
        }
        o.addProperty("addedAt", e.addedAt());
        o.addProperty("votes", s.voteTotal(e.id()));
        o.addProperty("pinned", e.pinned());
        return o;
    }

    /** The current entry: {@code entryId, status, item, addedBy?, addedAt}. */
    static JsonObject current(Lookup l, QueueEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("entryId", e.id());
        o.addProperty("status", entryStatus(e));
        o.add("item", item(l, e.itemId()));
        ActorRef by = addedBy(l, e.addedByUserId(), e.addedByGuestId());
        if (by != null) {
            o.add("addedBy", actor(by));
        }
        o.addProperty("addedAt", e.addedAt());
        return o;
    }

    /** The timeline: {@code state, entryId?, anchorServerMs, anchorPositionMs}. */
    static JsonObject timeline(Timeline t) {
        JsonObject o = new JsonObject();
        o.addProperty("state", t.state().wire());
        if (t.entryId() != null) {
            o.addProperty("entryId", t.entryId());
        }
        o.addProperty("anchorServerMs", t.anchorServerMs());
        o.addProperty("anchorPositionMs", t.anchorPositionMs());
        return o;
    }

    static JsonObject presence(RoomPresence.Counts c) {
        JsonObject o = new JsonObject();
        o.addProperty("listening", c.listening());
        o.addProperty("remote", c.remote());
        o.addProperty("anonymous", c.anonymous());
        return o;
    }

    /** {@code {votes, needed}} for the current entry in {@code vote} mode with vote-skip on, else {@code null}. */
    static JsonObject skipVotes(RoomSnapshot s) {
        if (s.controlMode() != Rooms.ControlMode.VOTE || s.skipVotesNeeded() == null || s.current() == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("entryId", s.current().id());
        o.addProperty("votes", s.skipVotes());
        o.addProperty("needed", s.skipVotesNeeded());
        return o;
    }

    /** The history: {@code [{entryId, state, item, addedBy?, playedAt}]}, latest first. */
    static JsonArray history(Lookup l, List<HistoryRow> rows) {
        JsonArray a = new JsonArray();
        for (HistoryRow h : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("entryId", h.entryId());
            o.addProperty("state", h.state());
            o.add("item", item(l, h.itemId()));
            ActorRef by = addedBy(l, h.addedByUserId(), h.addedByGuestId());
            if (by != null) {
                o.add("addedBy", actor(by));
            }
            o.addProperty("playedAt", h.playedAt());
            a.add(o);
        }
        return a;
    }
}
