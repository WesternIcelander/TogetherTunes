package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Feature;
import io.siggi.togethertunes.auth.IpAccess;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.Settings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Guests in rooms ({@code docs/rooms.md#guests-in-rooms}).
 *
 * <h2>Effective capabilities</h2>
 * Each guest capability is effective only when the <b>server</b> setting and the <b>room</b>
 * setting are both on ({@link #effective}): {@code guests.canVote}, {@code guests.canRequestLibrary},
 * {@code guests.canUpload}, {@code guests.canYoutube} (the last also needs {@code ytdlp.enabled}).
 * With {@code guests.enabled} off, or for a guest banned server-wide, nothing is effective. A
 * guest banned from the room can't open it at all ({@code 403 banned_from_room}, {@link Rooms}).
 * {@link #roomActor} turns a request's {@link Actor} into the {@link RoomActor} the queue
 * operations check; {@link #require} is the room half of {@link
 * io.siggi.togethertunes.library.AddMediaPolicy} (uploads and YouTube links by guests).
 *
 * <h2>Guest bans</h2>
 * The owner and moderators (incl. {@code rooms.moderateAny}) ban guests from a room
 * ({@code room_guest_bans}), list the bans and lift them. A ban removes the guest's connections
 * from the room's presence and is broadcast as {@link RoomEvent.GuestBanned} (the WebSocket layer
 * closes those sockets). A guest identified by IP ({@code identifiedBy: "ip"}) is everyone
 * sharing that IPv4 address or IPv6 /64, so its {@link GuestBan} carries {@link #IP_MODE_WARNING}
 * for the UI. The address itself is never part of a {@link GuestBan}: an {@code ip.view} holder
 * gets it separately from {@link #ipsOf} ({@link IpAccess}). Audited ({@code room.guest_banned},
 * {@code room.guest_unbanned}).
 */
public final class RoomGuests implements RoomGuestRules {
    public static final String AUDIT_BANNED = "room.guest_banned";
    public static final String AUDIT_UNBANNED = "room.guest_unbanned";
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** The warning shown before (and with) a ban of an IP-mode guest. Contains no address. */
    public static final String IP_MODE_WARNING = "This guest is identified by its IP address: banning it bans"
            + " everyone sharing that address (IPv4) or /64 network (IPv6), for example a whole household or"
            + " venue.";

    /**
     * What a guest may do in a room: each one is the server setting AND the room setting.
     * {@link #canRequest()}: any of the three request paths.
     */
    public record Capabilities(boolean canVote, boolean canRequestLibrary, boolean canUpload, boolean canYoutube) {
        public static final Capabilities NONE = new Capabilities(false, false, false, false);

        public boolean canRequest() {
            return canRequestLibrary || canUpload || canYoutube;
        }
    }

    /**
     * A guest's ban state in a room, as the owner and moderators see it.
     *
     * @param guest        the guest (an actor ref: id and name, never an address)
     * @param identifiedBy {@code cookie} or {@code ip}
     * @param banned       whether the room has banned it
     * @param bannedAt     when, or {@code null} if not banned
     * @param warning      {@link #IP_MODE_WARNING} for an IP-mode guest, else {@code null}
     */
    public record GuestBan(ActorRef guest, String identifiedBy, boolean banned, Long bannedAt, String warning) {
    }

    /** One page of bans; {@code nextCursor} is {@code null} on the last page. */
    public record GuestBanPage(List<GuestBan> items, String nextCursor) {
    }

    /** {@link #ban}'s result: the ban, and whether it is new. */
    public record BanSet(GuestBan ban, boolean created) {
    }

    private final Database db;
    private final Settings settings;
    private final Permissions permissions;
    private final Rooms rooms;
    private final RoomRegistry registry;
    private final IpAccess ipAccess;

    public RoomGuests(Database db, Settings settings, Permissions permissions, Rooms rooms, RoomRegistry registry) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.permissions = Objects.requireNonNull(permissions);
        this.rooms = Objects.requireNonNull(rooms);
        this.registry = Objects.requireNonNull(registry);
        this.ipAccess = new IpAccess(db, permissions);
    }

    // ------------------------------------------------------------------ capabilities

    /**
     * The effective capabilities: each one on only if {@code guestsEnabled}, the server's and the
     * room's setting are all on.
     */
    public static Capabilities effective(boolean guestsEnabled, Capabilities server, RoomSettings room) {
        if (!guestsEnabled) {
            return Capabilities.NONE;
        }
        return new Capabilities(server.canVote() && room.guestsCanVote(),
                server.canRequestLibrary() && room.guestsCanRequestLibrary(),
                server.canUpload() && room.guestsCanUpload(),
                server.canYoutube() && room.guestsCanYoutube());
    }

    /** The server half: the {@code guests.*} settings ({@code guests.canYoutube} also needs {@code ytdlp.enabled}). */
    public Capabilities serverCapabilities() throws SQLException {
        return new Capabilities(settings.guestsCanVote(), settings.guestsCanRequestLibrary(),
                settings.guestsCanUpload(), settings.guestsCanYoutube() && permissions.enabled(Feature.YOUTUBE));
    }

    /**
     * The server switches behind the room settings, for the room's management view, which greys
     * out room settings the server has turned off ({@code docs/rooms.md#guests-in-rooms}).
     * {@code guests}: the server half of each guest capability ({@link #serverCapabilities()});
     * {@code guestsEnabled} off turns all of them off.
     */
    public record ServerSwitches(boolean guestsEnabled, Capabilities guests, boolean overlayEnabled,
                                 boolean rtmpEnabled, boolean tsEnabled, boolean hlsEnabled,
                                 int rtmpMaxIdleStopMinutes) {
    }

    /** The server switches ({@link ServerSwitches}). */
    public ServerSwitches serverSwitches() throws SQLException {
        return new ServerSwitches(settings.guestsEnabled(), serverCapabilities(), settings.overlayEnabled(),
                settings.outputsRtmpEnabled(), settings.outputsTsEnabled(), settings.outputsHlsEnabled(),
                settings.outputsRtmpMaxIdleStopMinutes());
    }

    /**
     * What a guest (or an anonymous visitor, once it becomes one) may do in a room with these
     * settings. A guest banned server-wide gets {@link Capabilities#NONE}; a room ban is checked
     * when the room is opened ({@link Rooms#access}), not here.
     *
     * @throws IllegalArgumentException for a user (users have rights by role and permission)
     */
    public Capabilities capabilities(Actor actor, RoomSettings room) throws SQLException {
        return switch (actor) {
            case Actor.User ignored -> throw new IllegalArgumentException("users have no guest capabilities");
            case Actor.Guest g -> g.banned() ? Capabilities.NONE
                    : effective(settings.guestsEnabled(), serverCapabilities(), room);
            case Actor.Anonymous ignored -> effective(settings.guestsEnabled(), serverCapabilities(), room);
        };
    }

    /**
     * The {@link RoomActor} for a request's actor in a room it may see ({@link Rooms#access}
     * passed): a user with the room role and its upload/YouTube permissions, a guest with its
     * effective capabilities, or {@link RoomActor#ANONYMOUS}.
     */
    public RoomActor roomActor(Actor actor, Rooms.Access access) throws SQLException {
        return switch (actor) {
            case Actor.User u -> RoomActor.user(u.userId(), access.role(),
                    permissions.allowed(u, Permission.LIBRARY_UPLOAD),
                    permissions.enabled(Feature.YOUTUBE) && permissions.allowed(u, Permission.LIBRARY_YOUTUBE));
            case Actor.Guest g -> RoomActor.guest(g.guestId(), capabilities(g, access.room().settings()));
            case Actor.Anonymous ignored -> RoomActor.ANONYMOUS;
        };
    }

    /**
     * {@link #roomActor(Actor, Rooms.Access)} for the room with this id or slug.
     *
     * @throws HttpError {@code 404 room_not_found} (also a private room for a guest),
     *                   {@code 403 banned_from_room} (a banned user, or a guest banned from the room)
     */
    public RoomActor roomActor(Actor actor, String idOrSlug) throws SQLException {
        return roomActor(actor, rooms.access(actor, idOrSlug));
    }

    /**
     * The room half of a guest upload or YouTube link: the room must exist and be visible to the
     * guest (not private, not banning it: {@code 404 room_not_found} / {@code 403
     * banned_from_room}), and its setting must be on ({@code 403 guest_uploads_disabled} /
     * {@code guest_youtube_disabled}). The server half was checked by the caller.
     */
    @Override
    public void require(String roomId, Actor guest, Capability capability) throws SQLException {
        Rooms.Access access = rooms.access(guest, roomId);
        RoomSettings room = access.room().settings();
        switch (capability) {
            case UPLOAD -> {
                if (!room.guestsCanUpload()) {
                    throw new HttpError(403, "guest_uploads_disabled", "Guests can't upload files in this room");
                }
            }
            case YOUTUBE -> {
                if (!room.guestsCanYoutube()) {
                    throw new HttpError(403, "guest_youtube_disabled", "Guests can't add YouTube links in this room");
                }
            }
        }
    }

    // ------------------------------------------------------------------ bans

    /** The id of the room with this id or slug, else {@code 404 room_not_found}. */
    private String roomId(String idOrSlug) throws SQLException {
        String id = rooms.idOf(idOrSlug);
        if (id == null) {
            throw new HttpError(404, "room_not_found", "No such room");
        }
        return id;
    }

    /** Throws unless the caller moderates the room (owner, moderator, {@code rooms.moderateAny}). */
    private Rooms.Access requireModerator(Actor actor, String roomId) throws SQLException {
        Rooms.Access a = rooms.access(actor, roomId);
        if (a.role() == null || !a.role().moderates()) {
            throw HttpError.forbidden("Only the owner and moderators can manage guest bans");
        }
        return a;
    }

    private record GuestRow(String name, String kind) {
    }

    private static GuestRow guestRow(Connection c, String guestId) throws SQLException {
        if (guestId == null || !UuidV7.isValid(guestId)) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT display_name, kind FROM guests WHERE id = ?")) {
            ps.setString(1, guestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new GuestRow(rs.getString(1), rs.getString(2)) : null;
            }
        }
    }

    private static Long bannedAt(Connection c, String roomId, String guestId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT created_at FROM room_guest_bans WHERE room_id = ? AND guest_id = ?")) {
            ps.setString(1, roomId);
            ps.setString(2, guestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static GuestBan view(String guestId, GuestRow g, Long bannedAt) {
        boolean ipMode = "ip".equals(g.kind());
        return new GuestBan(ActorRef.guest(guestId, g.name()), g.kind(), bannedAt != null, bannedAt,
                ipMode ? IP_MODE_WARNING : null);
    }

    private static HttpError guestNotFound() {
        return new HttpError(404, "guest_not_found", "No such guest");
    }

    /**
     * A guest's ban state in the room, whether banned or not (so the UI can show the IP-mode
     * warning before banning). Owner and moderators only.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code guest_not_found}, {@code 403 forbidden}
     */
    public GuestBan get(Actor actor, String idOrSlug, String guestId) throws SQLException {
        String roomId = roomId(idOrSlug);
        requireModerator(actor, roomId);
        return db.read(c -> {
            GuestRow g = guestRow(c, guestId);
            if (g == null) {
                throw guestNotFound();
            }
            return view(guestId, g, bannedAt(c, roomId, guestId));
        });
    }

    /**
     * The room's guest bans, newest first (then by guest id, descending). Owner and moderators only.
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400 invalid_cursor} if malformed
     */
    public GuestBanPage list(Actor actor, String idOrSlug, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
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
        String roomId = roomId(idOrSlug);
        requireModerator(actor, roomId);
        String sql = "SELECT b.guest_id, b.created_at, g.display_name, g.kind FROM room_guest_bans b"
                + " JOIN guests g ON g.id = b.guest_id WHERE b.room_id = ?"
                + (cursorId != null ? " AND (b.created_at < ? OR (b.created_at = ? AND b.guest_id < ?))" : "")
                + " ORDER BY b.created_at DESC, b.guest_id DESC LIMIT " + (limit + 1);
        long at = cursorAt;
        String afterId = cursorId;
        return db.read(c -> {
            List<GuestBan> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, roomId);
                if (afterId != null) {
                    ps.setLong(2, at);
                    ps.setLong(3, at);
                    ps.setString(4, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(view(rs.getString(1), new GuestRow(rs.getString(3), rs.getString(4)),
                                rs.getLong(2)));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                GuestBan last = items.get(limit - 1);
                next = last.bannedAt() + "_" + last.guest().id();
            }
            return new GuestBanPage(List.copyOf(items), next);
        });
    }

    /**
     * Bans a guest from the room (owner and moderators). Banning again changes nothing. The
     * guest's connections leave the room's presence ({@link RoomEvent.GuestBanned}); its queue
     * entries and votes stay (moderators can remove entries), as for a banned user.
     *
     * @param ip the caller's IP, for the audit row only
     * @throws HttpError {@code 404 room_not_found} / {@code guest_not_found}, {@code 403 forbidden}
     */
    public BanSet ban(Actor actor, String idOrSlug, String guestId, String ip) throws Exception {
        String roomId = roomId(idOrSlug);
        String actorUserId = actor instanceof Actor.User u ? u.userId() : null;
        return registry.call(roomId, tx -> {
            requireModerator(actor, roomId);
            GuestRow g = db.read(c -> guestRow(c, guestId));
            if (g == null) {
                throw guestNotFound();
            }
            Long existing = db.read(c -> bannedAt(c, roomId, guestId));
            if (existing != null) {
                return new BanSet(view(guestId, g, existing), false);
            }
            long now = tx.now();
            tx.persist(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO room_guest_bans (room_id, guest_id, created_at) VALUES (?, ?, ?)")) {
                    ps.setString(1, roomId);
                    ps.setString(2, guestId);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }
                AuditLog.write(c, now, actorUserId, null, ip, AUDIT_BANNED, roomId,
                        Map.of("guestId", guestId, "identifiedBy", g.kind()));
                return null;
            });
            List<String> kicked = PresenceOps.removeActor(tx, "g:" + guestId);
            tx.event(new RoomEvent.GuestBanned(guestId, kicked));
            return new BanSet(view(guestId, g, now), true);
        });
    }

    /**
     * Lifts a guest's ban (owner and moderators).
     *
     * @return whether there was a ban
     * @throws HttpError {@code 404 room_not_found}, {@code 403 forbidden}
     */
    public boolean unban(Actor actor, String idOrSlug, String guestId, String ip) throws Exception {
        String roomId = roomId(idOrSlug);
        String actorUserId = actor instanceof Actor.User u ? u.userId() : null;
        return registry.call(roomId, tx -> {
            requireModerator(actor, roomId);
            if (guestId == null || !UuidV7.isValid(guestId) || db.read(c -> bannedAt(c, roomId, guestId)) == null) {
                return false;
            }
            long now = tx.now();
            tx.persist(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM room_guest_bans WHERE room_id = ? AND guest_id = ?")) {
                    ps.setString(1, roomId);
                    ps.setString(2, guestId);
                    ps.executeUpdate();
                }
                AuditLog.write(c, now, actorUserId, null, ip, AUDIT_UNBANNED, roomId, Map.of("guestId", guestId));
                return null;
            });
            tx.event(new RoomEvent.GuestUnbanned(guestId));
            return true;
        });
    }

    /**
     * The last known IP of these guests, for {@code ip.view} holders only (empty for everyone
     * else); see {@link IpAccess#ipsOfGuests}.
     */
    public Map<String, String> ipsOf(Actor viewer, Collection<String> guestIds) throws SQLException {
        return ipAccess.ipsOfGuests(viewer, guestIds);
    }
}
