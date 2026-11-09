package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.Library;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A room's request search and request scope ({@code docs/libraries.md#visibility}, room setting
 * {@code requestScope}): which items room members can find, and request by id, from libraries.
 *
 * <ul>
 *   <li>{@code owner} (default): the room owner's library and the items in the owner's playlists.</li>
 *   <li>{@code owner+self}: also the requesting user's own library (guests have none, so for them
 *       it is {@code owner}).</li>
 *   <li>{@code none}: no library search and no requests by item id
 *       ({@code 403 library_requests_disabled}); requests come only from uploads and YouTube links.
 *       The owner still finds their own library (it is theirs anyway); moderators don't.</li>
 * </ul>
 *
 * <p><b>No library-origin leaks.</b> A result is only the item's metadata ({@link Library.ItemView}):
 * nothing says which library or playlist it came from, when or how it was added, or who shared it.
 * An item in several of them appears once. Results are ordered by item id, newest item first
 * (the item's creation, which is the same for every library), never by when a library got it.
 * Requesting by id ({@link #requireInScope}) answers only "in scope or not", which the search
 * already tells. Uploads that failed or whose media expired can't be requested, so they are left
 * out; a failed or purged YouTube item is listed (requesting it fetches it again).
 *
 * <p>Who may search: anyone who may open the room ({@link Rooms#access}) and request from the
 * library: the owner and moderators, users with a room role other than banned or none, and guests
 * (and anonymous visitors, who would become guests) with the effective
 * {@code guests.canRequestLibrary} ({@code 403 guest_library_requests_disabled}).
 */
public final class RequestSearch {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    /** One page of results; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Library.ItemView> items, String nextCursor) {
    }

    private final Database db;
    private final Rooms rooms;
    private final RoomGuests guests;

    public RequestSearch(Database db, Rooms rooms, RoomGuests guests) {
        this.db = Objects.requireNonNull(db);
        this.rooms = Objects.requireNonNull(rooms);
        this.guests = Objects.requireNonNull(guests);
    }

    /** Whose libraries a search or request may draw from; {@code selfUserId} is {@code null} for none. */
    private record Libraries(String ownerId, String selfUserId) {
    }

    /**
     * The libraries in scope for this requester, or {@code 403 library_requests_disabled} for
     * {@code none} (except for the owner, who keeps their own library).
     */
    private static Libraries libraries(RoomSettings.RequestScope scope, String ownerId, String requesterUserId) {
        boolean isOwner = ownerId.equals(requesterUserId);
        return switch (scope) {
            case NONE -> {
                if (!isOwner) {
                    throw new HttpError(403, "library_requests_disabled",
                            "This room doesn't take requests from libraries");
                }
                yield new Libraries(ownerId, null);
            }
            case OWNER -> new Libraries(ownerId, null);
            case OWNER_SELF -> new Libraries(ownerId, isOwner ? null : requesterUserId);
        };
    }

    /**
     * Searches the room's request scope.
     *
     * @param query  words that must each appear in the title, artist or album (case-insensitive,
     *               substring); {@code null}/blank for all. {@code 400 invalid_query} if too long
     * @param kind   {@code audio}, {@code video} or {@code null}; {@code 400 invalid_kind}
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400 invalid_cursor}
     * @throws HttpError {@code 404 room_not_found}, {@code 403 banned_from_room},
     *                   {@code 403 guest_library_requests_disabled}, {@code 403 library_requests_disabled}
     */
    public Page search(Actor actor, String idOrSlug, String query, String kind, String cursor, int limit)
            throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        if (kind != null && !kind.equals("audio") && !kind.equals("video")) {
            throw new HttpError(400, "invalid_kind", "kind must be audio or video");
        }
        if (cursor != null && !UuidV7.isValid(cursor)) {
            throw new HttpError(400, "invalid_cursor", "Malformed cursor");
        }
        List<String> terms = Library.terms(query);
        Rooms.Access access = rooms.access(actor, idOrSlug);
        Rooms.Room room = access.room();
        String userId = actor instanceof Actor.User u ? u.userId() : null;
        boolean mods = access.role() != null && access.role().moderates();
        if (!mods && !(actor instanceof Actor.User)
                && !guests.capabilities(actor, room.settings()).canRequestLibrary()) {
            throw new HttpError(403, "guest_library_requests_disabled",
                    "Guests can't request from the library in this room");
        }
        Libraries libs = libraries(room.settings().requestScope(), room.ownerId(), userId);

        Dialect d = db.dialect();
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT i.id, i.kind, i.title, i.artist, i.album, i.duration_ms,"
                + " i.source, i.youtube_id, i.status, i.present_as_audio FROM items i WHERE (");
        scopePredicate(sql, params, libs);
        sql.append(") AND NOT (i.youtube_id IS NULL AND i.status IN ('failed', 'purged'))");
        if (kind != null) {
            sql.append(" AND i.kind = ?");
            params.add(kind);
        }
        String like = " LIKE ? ESCAPE '" + Dialect.LIKE_ESCAPE + "'";
        for (String term : terms) {
            sql.append(" AND (").append(d.lower("i.title")).append(like)
                    .append(" OR ").append(d.lower("i.artist")).append(like)
                    .append(" OR ").append(d.lower("i.album")).append(like).append(")");
            String pattern = Dialect.containsPattern(term);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (cursor != null) {
            sql.append(" AND i.id < ?");
            params.add(cursor);
        }
        sql.append(" ORDER BY i.id DESC LIMIT ").append(limit + 1);
        return db.read(c -> {
            List<Library.ItemView> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long duration = rs.getLong(6);
                        Long durationMs = rs.wasNull() ? null : duration;
                        items.add(new Library.ItemView(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), durationMs, rs.getString(7), rs.getString(8),
                                rs.getString(9), rs.getInt(10) != 0));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                next = items.get(limit - 1).id();
            }
            return new Page(List.copyOf(items), next);
        });
    }

    /** {@code i.id IN (…)} for every library and playlist set in scope. */
    private static void scopePredicate(StringBuilder sql, List<String> params, Libraries libs) {
        sql.append("i.id IN (SELECT item_id FROM library_entries WHERE user_id = ?)")
                .append(" OR i.id IN (SELECT pe.item_id FROM playlist_entries pe")
                .append(" JOIN playlists p ON p.id = pe.playlist_id WHERE p.owner_id = ?)");
        params.add(libs.ownerId());
        params.add(libs.ownerId());
        if (libs.selfUserId() != null) {
            sql.append(" OR i.id IN (SELECT item_id FROM library_entries WHERE user_id = ?)");
            params.add(libs.selfUserId());
        }
    }

    /**
     * A request by item id must come from the room's request scope. The owner and moderators are
     * exempt (they add from anywhere, as before). Same answer for an unknown item and one out of
     * scope, so nothing beyond the scope is revealed.
     *
     * @throws HttpError {@code 403 library_requests_disabled} ({@code none}),
     *                   {@code 403 not_in_request_scope}
     */
    public static void requireInScope(Database db, RoomSettings.RequestScope scope, String ownerId, RoomActor a,
                                      String itemId) throws SQLException {
        if (a.moderates()) {
            return;
        }
        Libraries libs = libraries(scope, ownerId, a.userId());
        boolean in = UuidV7.isValid(itemId) && db.read(c -> inScope(c, libs, itemId));
        if (!in) {
            throw new HttpError(403, "not_in_request_scope", "That item can't be requested in this room");
        }
    }

    private static boolean inScope(Connection c, Libraries libs, String itemId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT 1 FROM items i WHERE i.id = ? AND (");
        List<String> params = new ArrayList<>();
        params.add(itemId);
        scopePredicate(sql, params, libs);
        sql.append(")");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
