package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.Tokens;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.library.LibraryAddListener;
import io.siggi.togethertunes.retention.References;
import io.siggi.togethertunes.settings.GuestIdentity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Guest → account merge ({@code docs/auth.md#accounts}, "Guest upgrade"): a user who logged in
 * or registered in the browser that holds a guest's {@code gid} cookie takes over that guest's
 * queue entries (every state: requests, the queue and the history are all entries, see
 * {@code docs/rooms.md#history}), votes and skip votes.
 *
 * <ul>
 *   <li>{@code GET /api/me/merge-guest} → {@code {"guest"?: actor}}: the guest this browser's
 *       {@code gid} cookie holds, if it can be merged into the caller (so the UI knows whether to
 *       offer it). A user only ({@code 401 not_authenticated}); {@code guest} is left out for a
 *       bearer request (no cookies), no or a stale {@code gid} cookie, or a banned guest.</li>
 *   <li>{@code POST /api/me/merge-guest} (no body) → {@code 200 {"guest": actor, "moved":
 *       {"entries", "votes", "items", "requestedItems"}}} ({@code votes} counts votes and skip votes,
 *       {@code items} the items the guest added, now the user's and in their library,
 *       {@code requestedItems} the items of the guest's link and file requests newly put into it) and clears
 *       the {@code gid} cookie. Errors: {@code 401 not_authenticated} (not a logged-in user),
 *       {@code 403 cookie_session_required} (a bearer request: only the browser holding the cookie
 *       can merge), {@code 404 guest_not_found} (no {@code gid} cookie, or none of a current
 *       guest), {@code 403 guest_banned} (a guest banned server-wide).</li>
 * </ul>
 *
 * <p><b>Cookie mode only:</b> only a {@code cookie} guest, found by the request's {@code gid}
 * cookie, can be merged, never an IP-mode guest (several people can share one). Every room the
 * guest touched is changed on its executor ({@link RoomRegistry#call}), so the live state and the
 * database stay one; the room's clients get the change ({@link RoomEvent.GuestMerged}). Where the
 * user already voted (or voted to skip) on an entry, the user's vote stays and the guest's is
 * dropped. Entries keep {@code added_by_name} (the name a request was made under; where it is
 * {@code NULL} the history shows the account's name from now on). Afterwards the guest's
 * key is replaced by a random one, so the old cookie (and any copy of it) no longer identifies
 * it; the guest row itself stays (its uploads keep pointing at it). The
 * merge is written to the audit log ({@value #AUDIT_MERGED}). No IP in any response.
 *
 * <p>The items the guest added ({@code items.added_by_guest_id}) become the user's and are added
 * to the user's library ({@link #moveItems}); the guest's uploads ({@code uploads.guest_id}) keep
 * pointing at the guest.
 */
public final class GuestMerge {
    /** Audit action of a merge (actor: the user; target: the guest). */
    public static final String AUDIT_MERGED = "guest.merged";
    /** Passes over the guest's rooms (a socket still open as the guest may add something meanwhile). */
    private static final int PASSES = 3;

    private final RoomRegistry registry;
    private final Database db;
    private final Clock clock;

    /**
     * What a merge moved. {@code votes} counts votes and skip votes, {@code items} the items the
     * guest had added ({@code items.added_by_guest_id}), which now are the user's and in their library,
     * {@code requestedItems} the items of the guest's requests by link or file ({@code queue_entries.via}
     * {@code youtube}/{@code upload}) that the merge newly put into the user's library (as a signed-in
     * user's such request does, {@link Library#addRequested}).
     */
    public record Moved(int entries, int votes, int items, int requestedItems) {
        public Moved(int entries, int votes, int items) {
            this(entries, votes, items, 0);
        }

        Moved plus(Moved o) {
            return new Moved(entries + o.entries, votes + o.votes, items + o.items,
                    requestedItems + o.requestedItems);
        }
    }

    private volatile LibraryAddListener libraryAddListener = LibraryAddListener.NONE;

    public GuestMerge(RoomRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        this.db = registry.db();
        this.clock = registry.clock();
    }

    /** Who is told about the items the merge adds to the user's library (the quality re-encode). */
    public GuestMerge libraryAddListener(LibraryAddListener listener) {
        this.libraryAddListener = listener == null ? LibraryAddListener.NONE : listener;
        return this;
    }

    public void register(Router router) {
        router.get("/api/me/merge-guest", this::offer);
        router.post("/api/me/merge-guest", this::merge);
    }

    // ------------------------------------------------------------------ HTTP

    /** A cookie guest: id, display name, banned. */
    private record CookieGuest(String id, String name, boolean banned) {
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    private void offer(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        response.header("Cache-Control", "no-store");
        CookieGuest guest = user.via() == Actor.Via.COOKIE ? cookieGuest(request) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (guest != null && !guest.banned()) {
            out.put("guest", ActorRef.guest(guest.id(), guest.name()));
        }
        response.json(out);
    }

    private void merge(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        response.header("Cache-Control", "no-store");
        if (user.via() != Actor.Via.COOKIE) {
            throw new HttpError(403, "cookie_session_required",
                    "A guest can only be merged in the browser that holds it, while logged in there");
        }
        CookieGuest guest = cookieGuest(request);
        if (guest == null) {
            throw new HttpError(404, "guest_not_found", "This browser holds no guest identity to merge");
        }
        if (guest.banned()) {
            throw new HttpError(403, "guest_banned", "This guest is banned and can't be merged");
        }
        Moved moved = merge(guest.id(), user.userId(), request.clientIp());
        response.setCookie(Guests.cookie("", 0, request.isSecure()));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("entries", moved.entries());
        counts.put("votes", moved.votes());
        counts.put("items", moved.items());
        counts.put("requestedItems", moved.requestedItems());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guest", ActorRef.guest(guest.id(), guest.name()));
        out.put("moved", counts);
        response.json(out);
    }

    /** The cookie-mode guest of the request's {@code gid} cookie, or {@code null}. */
    private CookieGuest cookieGuest(Request request) throws SQLException {
        String token = request.cookie(ActorResolver.GUEST_COOKIE);
        if (!Tokens.isWellFormed(token)) {
            return null;
        }
        String hash = Tokens.hash(token);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, display_name, banned FROM guests WHERE key_hash = ? AND kind = ?")) {
                ps.setString(1, hash);
                ps.setString(2, GuestIdentity.COOKIE.wireName());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new CookieGuest(rs.getString(1), rs.getString(2), rs.getInt(3) != 0) : null;
                }
            }
        });
    }

    // ------------------------------------------------------------------ service

    /**
     * Moves everything of guest {@code guestId} in the rooms to user {@code userId}, then retires
     * the guest's key and writes the audit row. The caller checked who may do this.
     *
     * @param clientIp for the audit row only, or {@code null}
     */
    public Moved merge(String guestId, String userId, String clientIp) throws Exception {
        Objects.requireNonNull(guestId);
        Objects.requireNonNull(userId);
        Moved total = new Moved(0, 0, 0);
        Map<String, String> requested = new LinkedHashMap<>();
        for (int pass = 0; pass < PASSES; pass++) {
            List<String> roomIds = roomsOf(guestId);
            if (roomIds.isEmpty()) {
                break;
            }
            for (String roomId : roomIds) {
                try {
                    Counter counter = registry.call(roomId, tx -> mergeRoom(tx, guestId, userId));
                    total = total.plus(counter.get());
                    counter.requested.forEach(requested::putIfAbsent);
                } catch (HttpError e) {
                    if (e.status() != 404) {
                        throw e;
                    }
                    // the room was deleted meanwhile: nothing left to move there
                }
            }
        }
        Moved done = total;
        long now = EpochMs.now(clock);
        int[] requestedAdded = new int[1];
        int items = db.write(c -> {
            int n = moveItems(c, guestId, userId, now);
            requestedAdded[0] = addRequested(c, requested, userId, now);
            try (PreparedStatement ps = c.prepareStatement("UPDATE guests SET key_hash = ? WHERE id = ?")) {
                ps.setString(1, Tokens.hash(Tokens.newToken()));
                ps.setString(2, guestId);
                ps.executeUpdate();
            }
            AuditLog.write(c, now, userId, null, clientIp, AUDIT_MERGED, guestId,
                    Map.of("entries", done.entries(), "votes", done.votes(), "items", n,
                            "requestedItems", requestedAdded[0]));
            return n;
        });
        return new Moved(done.entries(), done.votes(), items, requestedAdded[0]);
    }

    /**
     * The items of the guest's requests by link or file ({@code item id → via}) go into the user's
     * library like a signed-in user's such request ({@code via} {@code youtube}/{@code upload}, no
     * classify question, an existing entry stays): items that still exist and haven't failed.
     *
     * @return how many were newly added
     */
    private int addRequested(Connection c, Map<String, String> requested, String userId, long now)
            throws SQLException {
        int added = 0;
        try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM items WHERE id = ? AND status <> 'failed'");
             PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                     List.of("user_id", "item_id", "added_at", "via"), List.of("user_id", "item_id")))) {
            for (Map.Entry<String, String> e : requested.entrySet()) {
                check.setString(1, e.getKey());
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        continue;
                    }
                }
                ps.setString(1, userId);
                ps.setString(2, e.getKey());
                ps.setLong(3, now);
                ps.setString(4, e.getValue());
                if (ps.executeUpdate() > 0) {
                    added++;
                    References.touch(c, e.getKey(), now);
                    libraryAddListener.addedToLibrary(e.getKey());
                }
            }
        }
        return added;
    }

    /**
     * The items the guest added become the user's ({@code items.added_by_user_id}) and go into the
     * user's library ({@link Library#VIA_MANUAL}, as an add of their own; an uncategorized video stays
     * uncategorized, so it shows in the Uncategorized view). Items in the library already keep
     * their entry.
     *
     * @return how many items changed hands
     */
    private int moveItems(Connection c, String guestId, String userId, long now) throws SQLException {
        List<String> itemIds = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM items WHERE added_by_guest_id = ? ORDER BY id")) {
            ps.setString(1, guestId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itemIds.add(rs.getString(1));
                }
            }
        }
        if (itemIds.isEmpty()) {
            return 0;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE items SET added_by_user_id = ?, added_by_guest_id = NULL WHERE added_by_guest_id = ?")) {
            ps.setString(1, userId);
            ps.setString(2, guestId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                List.of("user_id", "item_id", "added_at", "via"), List.of("user_id", "item_id")))) {
            for (String itemId : itemIds) {
                ps.setString(1, userId);
                ps.setString(2, itemId);
                ps.setLong(3, now);
                ps.setString(4, Library.VIA_MANUAL);
                if (ps.executeUpdate() > 0) {
                    References.touch(c, itemId, now);
                    libraryAddListener.addedToLibrary(itemId);
                }
            }
        }
        return itemIds.size();
    }

    /** The rooms where the guest has entries, votes or skip votes. */
    private List<String> roomsOf(String guestId) throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT room_id FROM queue_entries WHERE added_by_guest_id = ?"
                            + " UNION SELECT q.room_id FROM votes v JOIN queue_entries q ON q.id = v.entry_id"
                            + " WHERE v.voter_guest_id = ?"
                            + " UNION SELECT q.room_id FROM skip_votes v JOIN queue_entries q ON q.id = v.entry_id"
                            + " WHERE v.voter_guest_id = ?")) {
                for (int i = 1; i <= 3; i++) {
                    ps.setString(i, guestId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            out.sort(null);
            return out;
        });
    }

    /** Collects the counts the writes report once they ran (in the executor's commit). */
    private static final class Counter {
        private int entries;
        private int votes;
        /** The items of the guest's link and file requests in this room → their {@code via}. */
        private final Map<String, String> requested = new LinkedHashMap<>();

        Moved get() {
            return new Moved(entries, votes, 0);
        }
    }

    /** The merge in one room, on its executor: the live state, and the writes for every row. */
    private static Counter mergeRoom(RoomTx tx, String guestId, String userId) {
        RoomState s = tx.state();
        String roomId = s.id();
        QueueEntry current = s.current();
        if (current != null && guestId.equals(current.addedByGuestId())) {
            s.current(current.withAddedByUser(userId));
        }
        List<QueueEntry> waiting = new ArrayList<>(s.queue());
        waiting.addAll(s.suggestions());
        for (QueueEntry e : waiting) {
            if (guestId.equals(e.addedByGuestId())) {
                s.addToQueue(e.withAddedByUser(userId));
            }
        }
        s.mergeVoter(RoomStore.voterKey(null, guestId), RoomStore.voterKey(userId, null));
        Counter counter = new Counter();
        tx.persist(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT item_id, via FROM queue_entries"
                    + " WHERE added_by_guest_id = ? AND room_id = ? AND via IN ('youtube', 'upload') ORDER BY id")) {
                ps.setString(1, guestId);
                ps.setString(2, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        counter.requested.putIfAbsent(rs.getString(1), rs.getString(2));
                    }
                }
            }
            counter.entries += update(c, "UPDATE queue_entries SET added_by_user_id = ?, added_by_guest_id = NULL"
                    + " WHERE added_by_guest_id = ? AND room_id = ?", userId, guestId, roomId);
            for (String table : new String[] {"votes", "skip_votes"}) {
                // the user's own vote on an entry wins over the guest's
                counter.votes += update(c, "DELETE FROM " + table + " WHERE voter_guest_id = ? AND entry_id IN"
                        + " (SELECT entry_id FROM " + table + " WHERE voter_user_id = ?)"
                        + " AND entry_id IN (SELECT id FROM queue_entries WHERE room_id = ?)", guestId, userId, roomId);
                counter.votes += update(c, "UPDATE " + table + " SET voter_user_id = ?, voter_guest_id = NULL"
                        + " WHERE voter_guest_id = ? AND entry_id IN (SELECT id FROM queue_entries WHERE room_id = ?)",
                        userId, guestId, roomId);
            }
            return null;
        });
        tx.event(new RoomEvent.GuestMerged(guestId, userId));
        return counter;
    }

    private static int update(Connection c, String sql, String a, String b, String roomId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.setString(3, roomId);
            return ps.executeUpdate();
        }
    }
}
