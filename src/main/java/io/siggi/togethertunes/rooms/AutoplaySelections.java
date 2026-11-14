package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.Playlists;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which playlists autoplay draws from in a room ({@code room_autoplay_selections},
 * {@code docs/ui.md#autoplay-from-playlists}, {@code docs/rooms.md#autoplay}).
 *
 * <h2>Who selects</h2>
 * Each person selects among <b>their own</b> playlists (Liked Songs too). The owner always may;
 * others by the room setting {@code autoplay.selectors}: {@code moderators} adds users whose stored
 * room role is {@code moderator}, {@code members} adds {@code member} too. The stored role counts,
 * not {@code rooms.moderateAny} (an admin power over rooms, not a seat in them). Guests and
 * anonymous visitors never select (they have no playlists).
 *
 * <h2>Losing the right</h2>
 * {@link #prune} deletes the selections of everyone who may no longer select. {@link Rooms} calls it
 * in the same transaction as a change to {@code autoplay.selectors}, a role change (incl. a ban and
 * handing the room over) and removing a member row (leaving or being removed, i.e. leaving for
 * good). Deleting the room, the playlist or the account deletes the rows by cascade. Merely
 * disconnecting from the room keeps them.
 *
 * <h2>Visibility</h2>
 * Everyone who may open the room ({@link Rooms#access}) may list the room's selections and read a
 * <b>selected</b> playlist (name and entries) through the room; nothing else of its owner's becomes
 * readable, and a playlist stops being readable there as soon as it is deselected.
 */
public final class AutoplaySelections {
    /** The setting whose change prunes selections. */
    static final String SELECTORS = "autoplay.selectors";
    /** At most this many playlists in one {@link #setMine} call. */
    public static final int MAX_SET = 1000;

    /** A selected playlist without its entries. */
    public record SelectedPlaylist(String id, String name, int entryCount, String builtin, long selectedAt) {
    }

    /** One person's selected playlists, in that person's playlist order. */
    public record PersonSelection(ActorRef user, List<SelectedPlaylist> playlists) {
    }

    /**
     * The room's selections, people in the order they first selected, the caller first among
     * them when they have any. {@code canSelect}: whether the caller may select here now.
     */
    public record Listing(String roomId, boolean canSelect, List<PersonSelection> selections) {
    }

    /** A playlist read through the room: its owner and the playlist with its entries. */
    public record RoomPlaylist(ActorRef owner, Playlists.PlaylistPage playlist) {
    }

    private final Rooms rooms;
    private final Database db;
    private final Playlists playlists;
    private final Clock clock;

    public AutoplaySelections(Rooms rooms, Playlists playlists) {
        this.rooms = Objects.requireNonNull(rooms);
        this.db = rooms.db();
        this.playlists = Objects.requireNonNull(playlists);
        this.clock = rooms.clock();
    }

    // ------------------------------------------------------------------ rights

    /** Whether the user may select in the room, given their stored {@code room_members} role. */
    static boolean maySelect(Rooms.Room room, String userId, Rooms.Role stored) {
        if (userId == null) {
            return false;
        }
        if (room.ownerId().equals(userId)) {
            return true;
        }
        return switch (room.settings().autoplaySelectors()) {
            case OWNER -> false;
            case MODERATORS -> stored == Rooms.Role.MODERATOR;
            case MEMBERS -> stored == Rooms.Role.MODERATOR || stored == Rooms.Role.MEMBER;
        };
    }

    /**
     * Deletes the selections in the room of everyone who may no longer select ({@link #maySelect}
     * with the room's current owner, settings and roles).
     *
     * @return how many rows went
     */
    static int prune(Connection c, String roomId) throws SQLException {
        String ownerId;
        RoomSettings settings;
        try (PreparedStatement ps = c.prepareStatement("SELECT owner_id, settings_json FROM rooms WHERE id = ?")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                ownerId = rs.getString(1);
                settings = RoomSettings.parse(rs.getString(2));
            }
        }
        String roles = switch (settings.autoplaySelectors()) {
            case OWNER -> null;
            case MODERATORS -> "'moderator'";
            case MEMBERS -> "'moderator', 'member'";
        };
        String sql = "DELETE FROM room_autoplay_selections WHERE room_id = ? AND user_id <> ?"
                + (roles == null ? "" : " AND user_id NOT IN (SELECT m.user_id FROM room_members m"
                + " WHERE m.room_id = ? AND m.role IN (" + roles + "))");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, ownerId);
            if (roles != null) {
                ps.setString(3, roomId);
            }
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * The room's selections as the caller sees them.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room}
     */
    public Listing list(Actor actor, String idOrSlug) throws SQLException {
        boolean moderateAny = rooms.moderatesAny(actor);
        return db.read(c -> listing(c, rooms.access(c, actor, idOrSlug, false, moderateAny), actor));
    }

    private static String userId(Actor actor) {
        return actor instanceof Actor.User u ? u.userId() : null;
    }

    private static Listing listing(Connection c, Rooms.Access a, Actor actor) throws SQLException {
        String uid = userId(actor);
        Rooms.Room room = a.room();
        boolean canSelect = uid != null && maySelect(room, uid, Rooms.memberRole(c, room.id(), uid));
        Map<String, ActorRef> people = new LinkedHashMap<>();
        Map<String, Long> firstSelected = new LinkedHashMap<>();
        Map<String, List<SelectedPlaylist>> byUser = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT s.user_id, u.display_name, p.id, p.name,"
                + " (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlist_id = p.id), p.builtin, s.selected_at"
                + " FROM room_autoplay_selections s JOIN playlists p ON p.id = s.playlist_id"
                + " JOIN users u ON u.id = s.user_id WHERE s.room_id = ?"
                + " ORDER BY s.user_id, (CASE WHEN p.builtin IS NULL THEN 1 ELSE 0 END), p.position, p.id")) {
            ps.setString(1, room.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String user = rs.getString(1);
                    people.putIfAbsent(user, ActorRef.user(user, rs.getString(2)));
                    long at = rs.getLong(7);
                    firstSelected.merge(user, at, Math::min);
                    byUser.computeIfAbsent(user, k -> new ArrayList<>()).add(new SelectedPlaylist(rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getString(6), at));
                }
            }
        }
        List<String> order = new ArrayList<>(people.keySet());
        order.sort((x, y) -> {
            if (x.equals(uid) != y.equals(uid)) {
                return x.equals(uid) ? -1 : 1;
            }
            int byTime = Long.compare(firstSelected.get(x), firstSelected.get(y));
            return byTime != 0 ? byTime : x.compareTo(y);
        });
        List<PersonSelection> out = new ArrayList<>();
        for (String user : order) {
            out.add(new PersonSelection(people.get(user), List.copyOf(byUser.get(user))));
        }
        return new Listing(room.id(), canSelect, List.copyOf(out));
    }

    /**
     * A playlist selected in the room, with its entries, for everyone who may open the room.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room}, {@code 404
     *                   playlist_not_found} (not selected there)
     */
    public RoomPlaylist playlist(Actor actor, String idOrSlug, String playlistId) throws SQLException {
        boolean moderateAny = rooms.moderatesAny(actor);
        ActorRef owner = db.read(c -> {
            Rooms.Access a = rooms.access(c, actor, idOrSlug, false, moderateAny);
            try (PreparedStatement ps = c.prepareStatement("SELECT s.user_id, u.display_name"
                    + " FROM room_autoplay_selections s JOIN users u ON u.id = s.user_id"
                    + " WHERE s.room_id = ? AND s.playlist_id = ?")) {
                ps.setString(1, a.room().id());
                ps.setString(2, playlistId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? ActorRef.user(rs.getString(1), rs.getString(2)) : null;
                }
            }
        });
        if (owner == null) {
            throw playlistNotFound();
        }
        // Read after the check: a playlist deselected in between is shown once more at most.
        return new RoomPlaylist(owner, playlists.get(owner.id(), playlistId));
    }

    private static HttpError playlistNotFound() {
        return new HttpError(404, "playlist_not_found", "No such playlist");
    }

    // ------------------------------------------------------------------ changing

    /** What a change does with the caller's selections. */
    private interface Change {
        void apply(Connection c, String roomId, String userId, long now) throws SQLException;
    }

    /**
     * Runs a change to the caller's own selections with the room row locked (so it can't race a
     * {@link #prune}), then returns the listing.
     *
     * @param requireRight whether the caller must be allowed to select ({@code 403 forbidden});
     *                     deselecting never needs it
     */
    private Listing change(Actor actor, String idOrSlug, boolean requireRight, Change change) throws SQLException {
        if (!(actor instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        boolean moderateAny = rooms.moderatesAny(actor);
        long now = EpochMs.now(clock);
        return db.write(c -> {
            Rooms.Access a = rooms.access(c, actor, idOrSlug, true, moderateAny);
            Rooms.Room room = a.room();
            if (requireRight && !maySelect(room, user.userId(), Rooms.memberRole(c, room.id(), user.userId()))) {
                throw HttpError.forbidden("You can't select playlists for autoplay in this room");
            }
            change.apply(c, room.id(), user.userId(), now);
            return listing(c, a, actor);
        });
    }

    /** Throws {@code 404 playlist_not_found} unless the user owns the playlist; locks it on PostgreSQL. */
    private void requireOwn(Connection c, String userId, String playlistId) throws SQLException {
        boolean forUpdate = db.dialect() instanceof PostgresDialect;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM playlists WHERE id = ? AND owner_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setString(1, playlistId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw playlistNotFound();
                }
            }
        }
    }

    private void insert(Connection c, String roomId, String userId, String playlistId, long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("room_autoplay_selections",
                List.of("room_id", "playlist_id", "user_id", "selected_at"), List.of("room_id", "playlist_id")))) {
            ps.setString(1, roomId);
            ps.setString(2, playlistId);
            ps.setString(3, userId);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /**
     * Selects one of the caller's playlists in the room (already selected: unchanged).
     *
     * @throws HttpError {@code 401 not_authenticated}, {@code 404 room_not_found} / {@code 403
     *                   banned_from_room}, {@code 403 forbidden} (may not select here), {@code 404
     *                   playlist_not_found} (not the caller's)
     */
    public Listing select(Actor actor, String idOrSlug, String playlistId) throws SQLException {
        return change(actor, idOrSlug, true, (c, roomId, userId, now) -> {
            requireOwn(c, userId, playlistId);
            insert(c, roomId, userId, playlistId, now);
        });
    }

    /**
     * Deselects one of the caller's playlists in the room (not selected: unchanged). Needs no right
     * to select.
     */
    public Listing deselect(Actor actor, String idOrSlug, String playlistId) throws SQLException {
        return change(actor, idOrSlug, false, (c, roomId, userId, now) -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_autoplay_selections"
                    + " WHERE room_id = ? AND user_id = ? AND playlist_id = ?")) {
                ps.setString(1, roomId);
                ps.setString(2, userId);
                ps.setString(3, playlistId);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Makes these playlists the caller's whole selection in the room (one: "only this one"; none:
     * clears it). Other people's selections are untouched. Playlists that stay selected keep their
     * {@code selectedAt}. Clearing needs no right to select.
     *
     * @throws HttpError as {@link #select}; {@code 400 invalid_playlist_ids} (over
     *                   {@value #MAX_SET})
     */
    public Listing setMine(Actor actor, String idOrSlug, List<String> playlistIds) throws SQLException {
        Set<String> wanted = new LinkedHashSet<>(playlistIds);
        if (wanted.size() > MAX_SET) {
            throw new HttpError(400, "invalid_playlist_ids", "At most " + MAX_SET + " playlists");
        }
        return change(actor, idOrSlug, !wanted.isEmpty(), (c, roomId, userId, now) -> {
            for (String id : wanted) {
                requireOwn(c, userId, id);
            }
            List<String> drop = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT playlist_id FROM room_autoplay_selections"
                    + " WHERE room_id = ? AND user_id = ?")) {
                ps.setString(1, roomId);
                ps.setString(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!wanted.contains(rs.getString(1))) {
                            drop.add(rs.getString(1));
                        }
                    }
                }
            }
            for (String id : drop) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_autoplay_selections"
                        + " WHERE room_id = ? AND playlist_id = ?")) {
                    ps.setString(1, roomId);
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
            }
            for (String id : wanted) {
                insert(c, roomId, userId, id, now);
            }
        });
    }
}
