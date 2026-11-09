package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.AddMediaPolicy;
import io.siggi.togethertunes.library.Library;
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
 * A room's request history ({@code room_requests}, {@code docs/libraries.md#room-owners-request-history}).
 *
 * <p><b>Writing.</b> {@link QueueOps#add} writes a row with the queue entry, in the same
 * transaction, for every request: an add by anyone but the room's owner, and in {@code owner}
 * mode also not by a moderator (who adds directly there; {@code docs/rooms.md}: a request is an
 * add "by someone other than the owner or a moderator acting in owner mode"). Playlists added by
 * the owner or moderators are not requests. The row keeps the requester's name at that time
 * ({@code by_name}), how ({@code via}: {@code library}, {@code upload}, {@code youtube}), when,
 * and the entry.
 *
 * <p><b>Reading.</b> The owner and the moderators (and {@code rooms.moderateAny}) list the history,
 * newest first, filtered by source and requester. Requests of purged items are still listed (the
 * item's {@code status} says {@code purged}). Rows older than {@code rooms.requestHistoryDays} are
 * never listed, and {@link #trim} deletes them.
 *
 * <p><b>Add to my library.</b> Only the owner (read/write; moderators read only) adds the items of
 * selected requests to <em>their</em> library ({@code via='request'}), which protects them from
 * retention. A purged or failed YouTube item is fetched again (needs {@code library.youtube});
 * a purged or failed upload can't be restored.
 *
 * <p>No IP address is read or returned: a requester is an {@link ActorRef} (with the stored name).
 */
public final class RoomRequests {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Most request ids in one add-to-library call. */
    public static final int ADD_MAX = 100;
    /** The {@code via} values, which are the {@code source} filter's values. */
    public static final Set<String> SOURCES = Set.of("library", "upload", "youtube");

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** The {@code rooms.requestHistoryDays} setting. */
    @FunctionalInterface
    public interface Days {
        int get() throws SQLException;
    }

    /**
     * One request as the history shows it.
     *
     * @param requester           the user or guest who made it, with the name it had then;
     *                            {@code null} once that account or guest is gone
     * @param requesterName       the requester's name at request time (also when gone)
     * @param via                 {@code library}, {@code upload} or {@code youtube}
     * @param entryId             the queue entry it made, or {@code null} once that is gone
     * @param addedToOwnerLibrary the owner added it from the history
     * @param inOwnerLibrary      the item is in the owner's library now (however it got there)
     */
    public record Request(String id, Library.ItemView item, ActorRef requester, String requesterName, String via,
                          long at, String entryId, boolean addedToOwnerLibrary, boolean inOwnerLibrary) {
    }

    /** One page; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Request> items, String nextCursor) {
    }

    /** An error of one request in an add-to-library call. */
    public record Failure(String code, String message) {
    }

    /**
     * What add-to-library did for one request id: {@code created} (the entry is new) or not (it
     * was already there), or {@code error}.
     */
    public record Added(String requestId, String itemId, Boolean created, Failure error) {
    }

    /** The results, in the order of the (de-duplicated) request ids. */
    public record AddResults(List<Added> results) {
    }

    private final Database db;
    private final Rooms rooms;
    private final Library library;
    private final AddMediaPolicy addMedia;
    private final Days days;
    private final Clock clock;

    public RoomRequests(Database db, Rooms rooms, Library library, AddMediaPolicy addMedia, Days days, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.rooms = Objects.requireNonNull(rooms);
        this.library = Objects.requireNonNull(library);
        this.addMedia = Objects.requireNonNull(addMedia);
        this.days = Objects.requireNonNull(days);
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ writing

    /** Whether an add by {@code a} is a request: not by the owner, nor by a moderator in {@code owner} mode. */
    static boolean isRequest(RoomState s, RoomActor a) {
        if (!a.identified() || (a.userId() != null && a.userId().equals(s.ownerId()))) {
            return false;
        }
        return !(a.moderates() && s.controlMode() == Rooms.ControlMode.OWNER);
    }

    /** Writes a {@code room_requests} row; the requester's current name becomes {@code by_name}. */
    static void insert(Connection c, String id, String roomId, RoomActor a, String itemId, String via, long at,
                       String entryId) throws SQLException {
        if (!SOURCES.contains(via)) {
            throw new IllegalArgumentException("via: " + via);
        }
        String name = null;
        String sql = a.userId() != null ? "SELECT display_name FROM users WHERE id = ?"
                : "SELECT display_name FROM guests WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a.userId() != null ? a.userId() : a.guestId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    name = rs.getString(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO room_requests (id, room_id, item_id, by_user_id,"
                + " by_guest_id, by_name, via, at, entry_id, added_to_owner_library)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, id);
            ps.setString(2, roomId);
            ps.setString(3, itemId);
            ps.setString(4, a.userId());
            ps.setString(5, a.guestId());
            ps.setString(6, name == null || name.isEmpty() ? "?" : name);
            ps.setString(7, via);
            ps.setLong(8, at);
            ps.setString(9, entryId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ reading

    private static final String SELECT = "SELECT r.id, r.by_user_id, r.by_guest_id, r.by_name, r.via, r.at,"
            + " r.entry_id, r.added_to_owner_library,"
            + " i.id, i.kind, i.title, i.artist, i.album, i.duration_ms, i.source, i.youtube_id, i.status,"
            + " i.present_as_audio,"
            + " CASE WHEN EXISTS (SELECT 1 FROM library_entries le WHERE le.user_id = ? AND le.item_id = r.item_id)"
            + " THEN 1 ELSE 0 END"
            + " FROM room_requests r JOIN items i ON i.id = r.item_id";

    /** The oldest {@code at} still kept, or {@link Long#MIN_VALUE} when the history is never trimmed. */
    private long cutoff() throws SQLException {
        int d = days.get();
        return d <= 0 ? Long.MIN_VALUE : EpochMs.now(clock) - d * DAY_MS;
    }

    /**
     * The room's requests, newest first (then by id, descending). Owner and moderators only.
     *
     * @param source      {@code library}, {@code upload}, {@code youtube} or {@code null} for all;
     *                    {@code 400 invalid_source} otherwise
     * @param requesterId a user's or guest's id, or {@code null} for everyone; {@code 400
     *                    invalid_requester} if it isn't an id
     * @param cursor      the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *                    invalid_cursor} if malformed
     * @throws HttpError {@code 404 room_not_found}, {@code 403 forbidden} (not owner or moderator),
     *                   as {@link Rooms#access}
     */
    public Page list(Actor actor, String idOrSlug, String source, String requesterId, String cursor, int limit)
            throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        if (source != null && !SOURCES.contains(source)) {
            throw new HttpError(400, "invalid_source", "source must be library, upload or youtube");
        }
        if (requesterId != null && !UuidV7.isValid(requesterId)) {
            throw new HttpError(400, "invalid_requester", "requester must be a user or guest id");
        }
        long cursorAt = 0;
        String cursorId = null;
        if (cursor != null) {
            int sep = cursor.indexOf('_');
            try {
                cursorAt = Long.parseLong(cursor.substring(0, Math.max(sep, 0)));
            } catch (NumberFormatException e) {
                sep = -1;
            }
            if (sep <= 0 || sep == cursor.length() - 1) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorId = cursor.substring(sep + 1);
        }
        Rooms.Access access = requireModerator(actor, idOrSlug);
        String roomId = access.room().id();
        String ownerId = access.room().ownerId();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE r.room_id = ? AND r.at >= ?");
        List<Object> params = new ArrayList<>();
        params.add(ownerId);
        params.add(roomId);
        params.add(cutoff());
        if (source != null) {
            sql.append(" AND r.via = ?");
            params.add(source);
        }
        if (requesterId != null) {
            sql.append(" AND (r.by_user_id = ? OR r.by_guest_id = ?)");
            params.add(requesterId);
            params.add(requesterId);
        }
        if (cursorId != null) {
            sql.append(" AND (r.at < ? OR (r.at = ? AND r.id < ?))");
            params.add(cursorAt);
            params.add(cursorAt);
            params.add(cursorId);
        }
        sql.append(" ORDER BY r.at DESC, r.id DESC LIMIT ").append(limit + 1);
        return db.read(c -> {
            List<Request> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(request(rs));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                Request last = items.get(limit - 1);
                next = last.at() + "_" + last.id();
            }
            return new Page(List.copyOf(items), next);
        });
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i) instanceof Long l) {
                ps.setLong(i + 1, l);
            } else {
                ps.setString(i + 1, (String) params.get(i));
            }
        }
    }

    private static Request request(ResultSet rs) throws SQLException {
        String userId = rs.getString(2);
        String guestId = rs.getString(3);
        String name = rs.getString(4);
        ActorRef requester = userId != null ? ActorRef.user(userId, name)
                : guestId != null ? ActorRef.guest(guestId, name) : null;
        long duration = rs.getLong(14);
        Long durationMs = rs.wasNull() ? null : duration;
        Library.ItemView item = new Library.ItemView(rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getString(13), durationMs, rs.getString(15), rs.getString(16), rs.getString(17),
                rs.getInt(18) != 0);
        return new Request(rs.getString(1), item, requester, name, rs.getString(5), rs.getLong(6), rs.getString(7),
                rs.getInt(8) != 0, rs.getInt(19) != 0);
    }

    private Rooms.Access requireModerator(Actor actor, String idOrSlug) throws SQLException {
        if (!(actor instanceof Actor.User)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        Rooms.Access a = rooms.access(actor, idOrSlug);
        if (a.role() == null || !a.role().moderates()) {
            throw HttpError.forbidden("Only the owner and moderators can see the request history");
        }
        return a;
    }

    // ------------------------------------------------------------------ add to library

    /**
     * Adds the items of these requests to the owner's library ({@code via='request'}) and marks
     * the requests {@code addedToOwnerLibrary}. The owner only (moderators read the history but
     * don't change it). Each request is handled on its own: one that fails doesn't stop the
     * others, and its result carries the error ({@code request_not_found} for an id that isn't a
     * request of this room or is past {@code rooms.requestHistoryDays}; {@code media_expired} /
     * {@code item_failed} for an upload that can't be restored; a YouTube item that has to be
     * fetched again needs {@code library.youtube}, else {@code forbidden} / {@code feature_disabled}).
     *
     * @throws HttpError {@code 400 invalid_request_ids} (none, more than {@value #ADD_MAX}, not
     *                   ids), {@code 403 forbidden} (not the owner), as {@link Rooms#access}
     */
    public AddResults addToLibrary(Actor actor, String idOrSlug, List<String> requestIds)
            throws SQLException, InterruptedException {
        if (requestIds == null || requestIds.isEmpty() || requestIds.size() > ADD_MAX) {
            throw new HttpError(400, "invalid_request_ids", "Give 1.." + ADD_MAX + " request ids");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : requestIds) {
            if (id == null || !UuidV7.isValid(id)) {
                throw new HttpError(400, "invalid_request_ids", "Not a request id: " + id);
            }
            ids.add(id);
        }
        if (!(actor instanceof Actor.User owner)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        Rooms.Access access = rooms.access(actor, idOrSlug);
        if (!owner.userId().equals(access.room().ownerId()) || access.role() != Rooms.Role.OWNER) {
            throw HttpError.forbidden("Only the room's owner can add requests to their library");
        }
        String roomId = access.room().id();
        long cutoff = cutoff();
        Map<String, String> itemOf = db.read(c -> {
            Map<String, String> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id FROM room_requests WHERE id = ? AND room_id = ? AND at >= ?")) {
                for (String id : ids) {
                    ps.setString(1, id);
                    ps.setString(2, roomId);
                    ps.setLong(3, cutoff);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.put(id, rs.getString(1));
                        }
                    }
                }
            }
            return out;
        });
        List<Added> results = new ArrayList<>();
        for (String id : ids) {
            String itemId = itemOf.get(id);
            if (itemId == null) {
                results.add(new Added(id, null, null,
                        new Failure("request_not_found", "No such request in this room's history")));
                continue;
            }
            try {
                Library.Added added = library.add(owner, itemId, Library.VIA_REQUEST,
                        () -> addMedia.requireYoutube(owner, null));
                db.write(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE room_requests SET added_to_owner_library = 1 WHERE id = ? AND room_id = ?")) {
                        ps.setString(1, id);
                        ps.setString(2, roomId);
                        ps.executeUpdate();
                    }
                    return null;
                });
                results.add(new Added(id, itemId, added.created(), null));
            } catch (HttpError e) {
                results.add(new Added(id, itemId, null, new Failure(e.code(), e.getMessage())));
            }
        }
        return new AddResults(List.copyOf(results));
    }

    // ------------------------------------------------------------------ trimming

    /**
     * Deletes every request older than {@code rooms.requestHistoryDays} (none when it is 0).
     *
     * @return how many were deleted
     */
    public int trim() throws SQLException {
        long cutoff = cutoff();
        if (cutoff == Long.MIN_VALUE) {
            return 0;
        }
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_requests WHERE at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }
}
