package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.ItemSearch;
import io.siggi.togethertunes.library.Library;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A room's request search ({@code docs/libraries.md#visibility}): which items room members find
 * when they request from libraries. Any item on the server may be requested by id
 * ({@link RoomQueue#add}); the search only decides what is easy to find.
 *
 * <ul>
 *   <li>By default: the room owner's library and the items in the owner's playlists, plus the
 *       searching user's own library (guests and anonymous visitors have none).</li>
 *   <li>{@code all}: every item on the server that isn't purged ({@link ItemSearch.Scope#server()}).</li>
 * </ul>
 *
 * <p>The room setting {@code libraryRequests} off refuses the search ({@code 403
 * library_requests_off}) for everyone but the owner and moderators.
 *
 * <p><b>No library membership leaks.</b> One search implementation ({@link ItemSearch}): a result
 * is only the item's metadata ({@link Library.ItemView}), nothing about whose libraries or
 * playlists hold it, ordered by item id (newest item first), each item once.
 *
 * <p>Who may search: anyone who may open the room ({@link Rooms#access}) and request from the
 * library: the owner and moderators, users with a room role other than banned or none, and guests
 * (and anonymous visitors, who would become guests) with the effective
 * {@code guests.canRequestLibrary} ({@code 403 guest_library_requests_disabled}).
 */
public final class RequestSearch {
    public static final int LIST_DEFAULT = ItemSearch.LIST_DEFAULT;
    public static final int LIST_MAX = ItemSearch.LIST_MAX;

    /** One page of results; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Library.ItemView> items, String nextCursor) {
    }

    private final Rooms rooms;
    private final RoomGuests guests;
    private final ItemSearch items;

    public RequestSearch(Database db, Rooms rooms, RoomGuests guests) {
        this.rooms = Objects.requireNonNull(rooms);
        this.guests = Objects.requireNonNull(guests);
        this.items = new ItemSearch(db);
    }

    /** {@link #search(Actor, String, String, String, String, int, boolean)} without {@code all}. */
    public Page search(Actor actor, String idOrSlug, String query, String kind, String cursor, int limit)
            throws SQLException {
        return search(actor, idOrSlug, query, kind, cursor, limit, false);
    }

    /**
     * Searches the owner's and the requester's libraries, or with {@code all} the whole server.
     *
     * @param query  words that must each appear in the title, artist or album (case-insensitive,
     *               substring); {@code null}/blank for all. {@code 400 invalid_query} if too long
     * @param kind   {@code audio}, {@code video} or {@code null}; {@code 400 invalid_kind}
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400 invalid_cursor}
     * @throws HttpError {@code 404 room_not_found}, {@code 403 banned_from_room},
     *                   {@code 403 guest_library_requests_disabled}, {@code 403 library_requests_off}
     */
    public Page search(Actor actor, String idOrSlug, String query, String kind, String cursor, int limit,
                       boolean all) throws SQLException {
        return search(actor, idOrSlug, query, kind, null, cursor, limit, all);
    }

    /**
     * As {@link #search(Actor, String, String, String, String, int, boolean)}, with the
     * {@code type=} filter ({@link io.siggi.togethertunes.library.MediaType#filter}; {@code 400 invalid_type}).
     */
    public Page search(Actor actor, String idOrSlug, String query, String kind, String type, String cursor,
                       int limit, boolean all) throws SQLException {
        Rooms.Room room = requireMayRequest(actor, idOrSlug);
        String userId = actor instanceof Actor.User u ? u.userId() : null;
        ItemSearch.Scope scope = all ? ItemSearch.Scope.server()
                : ItemSearch.Scope.of(Arrays.asList(room.ownerId(), userId), List.of(room.ownerId()));
        ItemSearch.Page page = items.search(scope, null, query, kind, type, cursor, limit);
        return new Page(page.items().stream().map(ItemSearch.Hit::item).toList(), page.nextCursor());
    }

    /**
     * Checks that {@code actor} may open the room and request from libraries there (the rules of
     * the request search, also used by {@code GET /api/search} for guests and anonymous visitors).
     *
     * @return the room
     * @throws HttpError {@code 404 room_not_found}, {@code 403 banned_from_room},
     *                   {@code 403 guest_library_requests_disabled}, {@code 403 library_requests_off}
     */
    public Rooms.Room requireMayRequest(Actor actor, String idOrSlug) throws SQLException {
        Rooms.Access access = rooms.access(actor, idOrSlug);
        Rooms.Room room = access.room();
        boolean mods = access.role() != null && access.role().moderates();
        if (!mods && !room.settings().libraryRequests()) {
            throw new HttpError(403, "library_requests_off", "This room doesn't take requests from libraries");
        }
        if (!mods && !(actor instanceof Actor.User)
                && !guests.capabilities(actor, room.settings()).canRequestLibrary()) {
            throw new HttpError(403, "guest_library_requests_disabled",
                    "Guests can't request from the library in this room");
        }
        return room;
    }
}
