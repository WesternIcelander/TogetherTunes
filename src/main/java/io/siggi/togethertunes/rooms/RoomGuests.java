package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Feature;
import io.siggi.togethertunes.auth.IpAccess;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
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
 * guest banned or timed out in the room ({@link RoomBans}) may open it and listen, nothing else.
 * {@link #roomActor} turns a request's {@link Actor} into the {@link RoomActor} the queue
 * operations check; {@link #require} is the room half of {@link
 * io.siggi.togethertunes.library.AddMediaPolicy} (uploads and YouTube links by guests).
 *
 * <h2>Guest bans and timeouts</h2>
 * The owner and moderators (incl. {@code rooms.moderateAny}) ban or time out guests in a room
 * ({@code room_bans}, {@link RoomBans}): the guest and its IP address (an IP-mode guest is its
 * address; a cookie guest's is the one it connects from now, else its last known one). They list
 * the bans and lift them (the guest and the address together). A ban or timeout is broadcast as
 * {@link RoomEvent.RestrictionsChanged}: the guest stays in the room, read-only. A ban by IP covers
 * everyone sharing that IPv4 address or IPv6 /64 who isn't a member of the room, so a
 * {@link GuestBan} carries {@link #IP_MODE_WARNING} for the UI. The address itself is never part
 * of a {@link GuestBan}: an {@code ip.view} holder gets the guest's last IP separately from
 * {@link #ipsOf} ({@link IpAccess}). Audited ({@code room.guest_banned}, {@code room.guest_unbanned}).
 */
public final class RoomGuests implements RoomGuestRules {
    public static final String AUDIT_BANNED = "room.guest_banned";
    public static final String AUDIT_UNBANNED = "room.guest_unbanned";
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** The warning shown before (and with) a guest's ban. Contains no address. */
    public static final String IP_MODE_WARNING = "Bans and timeouts follow the IP address: this one also covers"
            + " everyone sharing the guest's address (IPv4) or /64 network (IPv6) who isn't a member of the room,"
            + " for example a whole household or venue.";

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
     * @param banned       whether the room has banned it (a ban, not a timeout)
     * @param bannedAt     when the ban or timeout was put in place, or {@code null} if there is none
     * @param warning      {@link #IP_MODE_WARNING} (bans follow the IP address), always set
     * @param timedOutUntil the end of its timeout (epoch ms), or {@code null}
     */
    public record GuestBan(ActorRef guest, String identifiedBy, boolean banned, Long bannedAt, String warning,
                           Long timedOutUntil) {
        public GuestBan(ActorRef guest, String identifiedBy, boolean banned, Long bannedAt, String warning) {
            this(guest, identifiedBy, banned, bannedAt, warning, null);
        }
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
    private final RoomBans.CurrentIps currentIps;

    public RoomGuests(Database db, Settings settings, Permissions permissions, Rooms rooms, RoomRegistry registry) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.permissions = Objects.requireNonNull(permissions);
        this.rooms = Objects.requireNonNull(rooms);
        this.registry = Objects.requireNonNull(registry);
        this.ipAccess = new IpAccess(db, permissions, rooms.clock());
        this.currentIps = new RoomBans.CurrentIps(ipAccess, registry.mediaAccess());
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
                                 boolean forceAutoOff, int maxAutoOffMinutes, boolean mp3Enabled) {
    }

    /** The server switches ({@link ServerSwitches}). */
    public ServerSwitches serverSwitches() throws SQLException {
        return new ServerSwitches(settings.guestsEnabled(), serverCapabilities(), settings.overlayEnabled(),
                settings.outputsRtmpEnabled(), settings.outputsTsEnabled(), settings.outputsHlsEnabled(),
                settings.outputsForceAutoOff(), settings.outputsMaxAutoOffMinutes(), settings.outputsMp3Enabled());
    }

    /**
     * What a guest (or an anonymous visitor, once it becomes one) may do in a room with these
     * settings. A guest banned server-wide gets {@link Capabilities#NONE}; a room ban or timeout
     * is the {@link Rooms.Access}'s ({@link #roomActor}), not here.
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
        RoomActor a = switch (actor) {
            case Actor.User u -> RoomActor.user(u.userId(), access.role(),
                    permissions.allowed(u, Permission.LIBRARY_UPLOAD),
                    permissions.enabled(Feature.YOUTUBE) && permissions.allowed(u, Permission.LIBRARY_YOUTUBE));
            case Actor.Guest g -> RoomActor.guest(g.guestId(), capabilities(g, access.room().settings()));
            case Actor.Anonymous ignored -> RoomActor.ANONYMOUS;
        };
        return a.restricted(access.restriction());
    }

    /**
     * {@link #roomActor(Actor, Rooms.Access)} for the room with this id or slug, without the
     * caller's IP address (only the account's or guest's own ban or timeout counts).
     *
     * @throws HttpError {@code 404 room_not_found} (also a private room for a guest)
     */
    public RoomActor roomActor(Actor actor, String idOrSlug) throws SQLException {
        return roomActor(actor, rooms.access(actor, idOrSlug));
    }

    /**
     * {@link #roomActor(Actor, Rooms.Access)} for the room with this id or slug, seen from
     * {@code clientIp}: a ban or timeout of that address counts too.
     *
     * @throws HttpError {@code 404 room_not_found} (also a private room for a guest)
     */
    public RoomActor roomActor(Actor actor, String clientIp, String idOrSlug) throws SQLException {
        return roomActor(actor, rooms.access(actor, idOrSlug, clientIp));
    }

    /**
     * The room half of a guest upload or YouTube link: the room must exist and be visible to the
     * guest (not private, not banning it: {@code 404 room_not_found} / {@code 403
     * banned_from_room}), an upload needs the room's {@code uploadRequests} ({@code 403
     * upload_requests_off}), and its setting must be on ({@code 403 guest_uploads_disabled} /
     * {@code guest_youtube_disabled}). The server half was checked by the caller.
     */
    @Override
    public void require(String roomId, Actor guest, Capability capability) throws SQLException {
        Rooms.Access access = rooms.access(guest, roomId);
        access.requireUnrestricted(); // read-only there
        RoomSettings room = access.room().settings();
        switch (capability) {
            case UPLOAD -> {
                if (!room.uploadRequests()) {
                    throw RoomQueue.uploadRequestsOff();
                }
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

    private static GuestBan view(Connection c, String roomId, String guestId, GuestRow g, long now)
            throws SQLException {
        RoomBans.Entry e = RoomBans.entry(c, roomId, RoomBans.GUEST, guestId, now);
        if (e == null) {
            return new GuestBan(ActorRef.guest(guestId, g.name()), g.kind(), false, null, IP_MODE_WARNING, null);
        }
        return new GuestBan(ActorRef.guest(guestId, g.name()), g.kind(), e.until() == null, e.at(), IP_MODE_WARNING,
                e.until());
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
            return view(c, roomId, guestId, g, EpochMs.now(rooms.clock()));
        });
    }

    /**
     * The room's guest bans and timeouts, newest first (then by guest, descending). Owner and
     * moderators only.
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400 invalid_cursor} if malformed
     */
    public GuestBanPage list(Actor actor, String idOrSlug, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        String roomId = roomId(idOrSlug);
        requireModerator(actor, roomId);
        long now = EpochMs.now(rooms.clock());
        return db.read(c -> {
            // Guests only: walk the room's restrictions page by page until enough guests are found.
            List<GuestBan> items = new ArrayList<>();
            String pageCursor = cursor;
            String next = null;
            while (true) {
                RoomBans.Page page = RoomBans.list(c, roomId, pageCursor, RoomBans.LIST_MAX, now);
                for (int i = 0; i < page.items().size(); i++) {
                    RoomBans.Entry e = page.items().get(i);
                    if (!e.person().isGuest()) {
                        continue;
                    }
                    if (items.size() == limit) {
                        GuestBan last = items.get(limit - 1);
                        next = last.bannedAt() + "_" + RoomBans.guestSubject(last.guest().id());
                        return new GuestBanPage(List.copyOf(items), next);
                    }
                    items.add(new GuestBan(e.person(), e.identifiedBy() == null ? "cookie" : e.identifiedBy(),
                            e.until() == null, e.at(), IP_MODE_WARNING, e.until()));
                }
                if (page.nextCursor() == null) {
                    return new GuestBanPage(List.copyOf(items), null);
                }
                pageCursor = page.nextCursor();
            }
        });
    }

    /**
     * Bans a guest from the room (owner and moderators): read-only there, see {@link #restrict}.
     *
     * @param ip the caller's IP, for the audit row only
     * @throws HttpError {@code 404 room_not_found} / {@code guest_not_found}, {@code 403 forbidden}
     */
    public BanSet ban(Actor actor, String idOrSlug, String guestId, String ip) throws Exception {
        return restrict(actor, idOrSlug, guestId, null, ip);
    }

    /**
     * Bans ({@code durationMs} {@code null}) or times out a guest in the room (owner and
     * moderators): the guest and its IP address, which also covers everyone at that address who
     * isn't a member. The guest stays in the room, read-only ({@link RoomEvent.RestrictionsChanged});
     * its queue entries and votes stay (moderators can remove entries). Banning a banned guest again
     * changes nothing; anything else replaces the previous ban or timeout.
     *
     * @param ip the caller's IP, for the audit row only
     * @throws HttpError {@code 404 room_not_found} / {@code guest_not_found}, {@code 403 forbidden},
     *                   {@code 400 invalid_duration}
     */
    public BanSet restrict(Actor actor, String idOrSlug, String guestId, Long durationMs, String ip) throws Exception {
        String roomId = roomId(idOrSlug);
        String actorUserId = actor instanceof Actor.User u ? u.userId() : null;
        BanSet set = registry.call(roomId, tx -> {
            requireModerator(actor, roomId);
            GuestRow g = db.read(c -> guestRow(c, guestId));
            if (g == null) {
                throw guestNotFound();
            }
            long now = tx.now();
            String subject = RoomBans.guestSubject(guestId);
            RoomBans.Restriction existing = db.read(c -> RoomBans.ofSubject(c, roomId, subject, now));
            if (existing != null && existing.ban() && durationMs == null) {
                return new BanSet(db.read(c -> view(c, roomId, guestId, g, now)), false);
            }
            Long until = durationMs == null ? null : RoomBans.timeoutEnd(now, durationMs);
            // An IP-mode guest is its address (key_hash is the hash of its IP key); a cookie guest's
            // is where it connects from now, else its last known one.
            String ipKeyHash = "ip".equals(g.kind()) ? db.read(c -> keyHash(c, guestId)) : currentIps.ofGuest(guestId);
            tx.persist(c -> {
                RoomBans.put(c, roomId, subject, RoomBans.GUEST, guestId, ipKeyHash, until, actorUserId, now);
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("guestId", guestId);
                details.put("identifiedBy", g.kind());
                details.put("kind", until == null ? "ban" : "timeout");
                if (until != null) {
                    details.put("until", until);
                }
                details.put("addressCovered", ipKeyHash != null);
                AuditLog.write(c, now, actorUserId, null, ip, AUDIT_BANNED, roomId, details);
                return null;
            });
            tx.event(new RoomEvent.RestrictionsChanged());
            GuestBan view = new GuestBan(ActorRef.guest(guestId, g.name()), g.kind(), until == null, now,
                    IP_MODE_WARNING, until);
            return new BanSet(view, existing == null);
        });
        registry.timeoutSet(roomId, set.ban().timedOutUntil());
        return set;
    }

    private static String keyHash(Connection c, String guestId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT key_hash FROM guests WHERE id = ?")) {
            ps.setString(1, guestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Lifts a guest's ban or timeout (owner and moderators): the guest and the address together.
     *
     * @return whether there was one
     * @throws HttpError {@code 404 room_not_found}, {@code 403 forbidden}
     */
    public boolean unban(Actor actor, String idOrSlug, String guestId, String ip) throws Exception {
        String roomId = roomId(idOrSlug);
        String actorUserId = actor instanceof Actor.User u ? u.userId() : null;
        return registry.call(roomId, tx -> {
            requireModerator(actor, roomId);
            if (guestId == null || !UuidV7.isValid(guestId)) {
                return false;
            }
            long now = tx.now();
            String subject = RoomBans.guestSubject(guestId);
            if (db.read(c -> RoomBans.ofSubject(c, roomId, subject, now)) == null) {
                return false;
            }
            tx.persist(c -> {
                RoomBans.lift(c, roomId, subject, false, now);
                AuditLog.write(c, now, actorUserId, null, ip, AUDIT_UNBANNED, roomId, Map.of("guestId", guestId));
                return null;
            });
            tx.event(new RoomEvent.RestrictionsChanged());
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
