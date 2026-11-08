package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Guest identities ({@code docs/auth.md#guests}).
 *
 * <p>A guest is created <b>lazily</b>: {@link #requireIdentity} is what every action that needs an
 * identity (vote, request, upload, YouTube import, suggest, vote-skip) calls first. A user or an
 * existing guest is returned as is; an anonymous actor becomes a new guest, the request's
 * {@link ActorResolver#actor} is updated to it, and in cookie mode the {@code gid} cookie is set on
 * the response. {@code POST /api/me/guest} ({@link MeApi}) does the same explicitly.
 *
 * <ul>
 *   <li><b>Cookie mode:</b> a new 256-bit token goes into the {@code gid} cookie ({@link #cookie});
 *       its SHA-256 is the guest's {@code key_hash}.</li>
 *   <li><b>IP mode:</b> the key is the client IP ({@link GuestKeys}); two requests racing to
 *       create the same IP guest end up with the same row.</li>
 * </ul>
 * Both modes record the creating request's IP in {@code ip_last}, and {@link ActorResolver} keeps
 * it (and {@code last_seen_at}) current through {@link #touch} whenever the guest is used; in IP
 * mode that is the full address, while the key is only its /64 for IPv6. Only {@code IpAccess}
 * reads {@code ip_last}; {@link GuestView} has no IP field ({@code docs/auth.md#ip-address-policy}).
 * The {@code gid} cookie slides: it is re-sent with a fresh one-year {@code Max-Age} when its
 * guest's use is recorded.
 *
 * <p>Names: without a chosen name a guest gets a generated one ({@code name_chosen = 0}, see
 * {@link #generatedName}); with
 * {@code guests.allowCustomNames} on, a name can be given at creation and changed later
 * ({@link GuestNameRules}).
 */
public final class Guests {
    /** Lifetime of the {@code gid} cookie: one year, sliding (re-sent on use, see {@link ActorResolver}). */
    public static final long COOKIE_MAX_AGE_SECONDS = 365L * 24 * 60 * 60;
    /**
     * A guest's use is recorded ({@code last_seen_at}, and the {@code gid} cookie re-sent) at most
     * this often; a changed client IP is recorded in {@code ip_last} right away.
     */
    public static final long TOUCH_INTERVAL_MS = 60_000;
    /** Shortest custom guest name, in code points. */
    public static final int NAME_MIN = GuestNameRules.MIN_LENGTH;
    /** Longest custom guest name, in code points. */
    public static final int NAME_MAX = GuestNameRules.MAX_LENGTH;
    /** {@code audit_log} action of a guest identity created. */
    public static final String AUDIT_CREATED = "guest.created";
    /** {@code audit_log} action of a guest renaming itself. */
    public static final String AUDIT_RENAMED = "guest.renamed";

    /** Generated names drawn before a number is appended ({@code docs/auth.md#names}). */
    public static final int NAME_DRAWS = 5;
    /**
     * A guest seen within this long (by {@code last_seen_at}) is <i>active</i>: generated names
     * are unique among active guests. 30 days.
     */
    public static final long ACTIVE_MS = 30L * 24 * 60 * 60 * 1000;
    /** PostgreSQL advisory lock that serialises generated-name choice with the insert. */
    private static final long POSTGRES_NAME_LOCK_KEY = 0x5454677565737473L; // "TTguests"

    private final Database db;
    private final Settings settings;
    private final Clock clock;
    private final boolean secureCookies;
    private final Supplier<String> names;

    /** A guest as the guest itself, and other users, may see it. No IP. */
    public record GuestView(String id, GuestIdentity kind, String name, boolean nameChosen, boolean banned,
                            long createdAt) {
        public GuestView {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(name);
        }

        /** The {@link Actor} of this guest. */
        public Actor.Guest actor() {
            return new Actor.Guest(id, kind, banned);
        }
    }

    /**
     * A guest found or created by {@link #create}.
     *
     * @param cookieToken the new {@code gid} token (cookie mode), or {@code null}
     * @param created     whether a new row was inserted (false: an existing IP guest was found)
     */
    public record Created(GuestView guest, String cookieToken, boolean created) {
    }

    /**
     * @param secureCookies whether the {@code gid} cookie gets the {@code Secure} flag (as in
     *                      {@link AuthApi})
     */
    public Guests(Database db, Settings settings, boolean secureCookies) {
        this(db, settings, Clock.systemUTC(), secureCookies, GuestNames.bundled());
    }

    /**
     * @param names draws a name for a guest that hasn't chosen one (made unique by
     *              {@link #generatedName})
     */
    public Guests(Database db, Settings settings, Clock clock, boolean secureCookies, Supplier<String> names) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.secureCookies = secureCookies;
        this.names = Objects.requireNonNull(names);
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * The identity of this request, creating a guest if it is anonymous (see the class comment).
     *
     * @return an {@link Actor.User} or an {@link Actor.Guest}
     * @throws HttpError 403 {@code guests_disabled} for an anonymous request while
     *                   {@code guests.enabled} is off
     */
    public Actor requireIdentity(Request request, Response response) throws SQLException {
        Actor actor = ActorResolver.actor(request);
        if (!(actor instanceof Actor.Anonymous)) {
            return actor;
        }
        return create(request, response, null).guest().actor();
    }

    /**
     * Creates a guest for an anonymous request (sets the {@code gid} cookie in cookie mode, and
     * updates {@link ActorResolver#actor}).
     *
     * @param customName a requested name, or {@code null} for a generated one
     * @throws HttpError 403 {@code guests_disabled}; 403 {@code custom_names_disabled} for a
     *                   {@code customName} while custom names are off; 400 {@code invalid_name},
     *                   409 {@code name_taken} or 400 {@code name_blocked} ({@link GuestNameRules})
     */
    public Created create(Request request, Response response, String customName) throws SQLException {
        if (!settings.guestsEnabled()) {
            throw new HttpError(403, "guests_disabled", "Guests are disabled on this server; log in first");
        }
        String name = customName == null ? null : checkedCustomName(customName);
        Created created = create(settings.guestsIdentifyBy(), request.clientIp(), name);
        if (created.cookieToken() != null) {
            response.setCookie(cookie(created.cookieToken(), secureCookies));
        }
        ActorResolver.setActor(request, created.guest().actor());
        return created;
    }

    /**
     * A custom name to store, after checking that custom names are allowed: the rules of
     * {@link GuestNameRules#check} (form, no clash with an account, not on the blocklist).
     */
    private String checkedCustomName(String name) throws SQLException {
        if (!settings.guestsAllowCustomNames()) {
            throw new HttpError(403, "custom_names_disabled", "Guests can't choose their names on this server");
        }
        return GuestNameRules.check(db, settings.guestsNameBlocklist(), name);
    }

    /** The {@code gid} cookie header value ({@code HttpOnly; SameSite=Lax}, one year). */
    public static String cookie(String token, boolean secure) {
        return cookie(token, COOKIE_MAX_AGE_SECONDS, secure);
    }

    /** The {@code gid} cookie header value with an explicit {@code Max-Age} (0 clears it). */
    public static String cookie(String value, long maxAgeSeconds, boolean secure) {
        return ActorResolver.GUEST_COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds
                + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
    }

    // ------------------------------------------------------------------ storage

    /**
     * Creates a guest in {@code mode}, or (IP mode) returns the existing guest of {@code clientIp}.
     *
     * @param name a validated custom name ({@code name_chosen = 1}), or {@code null} to generate one
     * @throws HttpError 400 {@code no_client_ip} in IP mode without a usable client IP
     */
    public Created create(GuestIdentity mode, String clientIp, String name) throws SQLException {
        Objects.requireNonNull(mode);
        String token = null;
        String keyHash;
        if (mode == GuestIdentity.COOKIE) {
            token = Tokens.newToken();
            keyHash = Tokens.hash(token);
        } else {
            keyHash = clientIp == null ? null : GuestKeys.ipKeyHash(clientIp);
            if (keyHash == null) {
                throw new HttpError(400, "no_client_ip", "The client IP address is unknown");
            }
            GuestView existing = findByKey(mode, keyHash);
            if (existing != null) {
                return new Created(existing, null, false);
            }
        }
        String id = UuidV7.newId();
        boolean chosen = name != null;
        long now = EpochMs.now(clock);
        String displayName;
        try {
            displayName = db.write(c -> {
                String dn = chosen ? name : generatedName(c, now);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO guests (id, kind, key_hash, ip_last, display_name, name_chosen, created_at,"
                                + " last_seen_at, banned) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
                    ps.setString(1, id);
                    ps.setString(2, mode.wireName());
                    ps.setString(3, keyHash);
                    ps.setString(4, clientIp);
                    ps.setString(5, dn);
                    ps.setInt(6, chosen ? 1 : 0);
                    ps.setLong(7, now);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                }
                AuditLog.write(c, now, null, id, clientIp, AUDIT_CREATED, id, Map.of("kind", mode.wireName()));
                return dn;
            });
        } catch (SQLException e) {
            // IP mode: another request from the same address created the guest first.
            GuestView raced = mode == GuestIdentity.IP ? findByKey(mode, keyHash) : null;
            if (raced == null) {
                throw e;
            }
            return new Created(raced, null, false);
        }
        return new Created(new GuestView(id, mode, displayName, chosen, false, now), token, true);
    }

    /**
     * A generated name that no active guest ({@link #ACTIVE_MS}) uses, compared ignoring case:
     * up to {@value #NAME_DRAWS} draws, then the last draw with the smallest free number from 2
     * appended ("Quiet Walrus 2"). Call it in the write transaction that inserts the guest; on
     * PostgreSQL it takes an advisory lock so concurrent creations can't pick the same name
     * (SQLite writes are serialised anyway).
     */
    String generatedName(Connection c, long now) throws SQLException {
        Dialect dialect = db.dialect();
        if (dialect instanceof PostgresDialect) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                ps.setLong(1, POSTGRES_NAME_LOCK_KEY);
                ps.execute();
            }
        }
        long activeSince = now - ACTIVE_MS;
        String drawn = null;
        for (int i = 0; i < NAME_DRAWS; i++) {
            drawn = names.get();
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM guests WHERE "
                    + dialect.equalsIgnoreCase("display_name") + " AND last_seen_at >= ?")) {
                ps.setString(1, Dialect.foldCase(drawn));
                ps.setLong(2, activeSince);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return drawn;
                    }
                }
            }
        }
        String prefix = Dialect.foldCase(drawn) + " ";
        Set<Long> taken = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT display_name FROM guests WHERE "
                + dialect.containsIgnoreCase("display_name") + " AND last_seen_at >= ?")) {
            ps.setString(1, Dialect.escapeLike(prefix) + "%");
            ps.setLong(2, activeSince);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String folded = Dialect.foldCase(rs.getString(1));
                    if (folded.startsWith(prefix)) {
                        String suffix = folded.substring(prefix.length());
                        if (suffix.matches("[1-9][0-9]{0,17}")) {
                            taken.add(Long.parseLong(suffix));
                        }
                    }
                }
            }
        }
        long n = 2;
        while (taken.contains(n)) {
            n++;
        }
        return drawn + " " + n;
    }

    /** The guest with this id, or {@code null}. */
    public GuestView get(String guestId) throws SQLException {
        return db.read(c -> find(c, "id = ?", guestId, null));
    }

    /**
     * Records a use of a guest: {@code last_seen_at = now}, and {@code ip_last = clientIp} (unless
     * {@code clientIp} is {@code null}). Does nothing if the guest was seen less than
     * {@link #TOUCH_INTERVAL_MS} ago from the same IP. Conditional on the {@code last_seen_at} the
     * caller read, so concurrent requests write once.
     *
     * @param sameIp whether the stored {@code ip_last} equals {@code clientIp}, as the caller found
     *               with {@link IpAccess#GUEST_IP_UNCHANGED} (the stored IP itself is never read here)
     * @return whether the row was updated
     */
    static boolean touch(Database db, String guestId, long lastSeenAt, boolean sameIp, String clientIp, long now)
            throws SQLException {
        boolean ipChanged = clientIp != null && !sameIp;
        if (!ipChanged && now - lastSeenAt < TOUCH_INTERVAL_MS) {
            return false;
        }
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE guests SET last_seen_at = ?, ip_last = COALESCE(?, ip_last)"
                            + " WHERE id = ? AND last_seen_at = ?")) {
                ps.setLong(1, Math.max(now, lastSeenAt));
                ps.setString(2, clientIp);
                ps.setString(3, guestId);
                ps.setLong(4, lastSeenAt);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /**
     * A guest for the admin list: its view plus when it was last seen. No IP (an {@code ip.view}
     * holder gets it from {@link IpAccess#ipsOfGuests}).
     */
    public record GuestListItem(GuestView guest, long lastSeenAt) {
    }

    /** A page of {@link #list}; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<GuestListItem> items, String nextCursor) {
    }

    /** Default and largest page size of {@link #list}. */
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    /**
     * All guests (banned ones too), most recently seen first. {@code query} (optional) matches
     * part of the name, ignoring case. {@code cursor} is the previous page's {@code nextCursor}
     * ({@code 400 invalid_cursor} if malformed). As guests keep being seen, a guest can move to an
     * earlier page while paging; that is fine for an admin list.
     */
    public Page list(String query, String cursor, int limit) throws SQLException {
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
        Dialect d = db.dialect();
        StringBuilder sql = new StringBuilder(
                "SELECT id, kind, display_name, name_chosen, banned, created_at, last_seen_at FROM guests WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND ").append(d.containsIgnoreCase("display_name"));
            params.add(Dialect.containsPattern(query.strip()));
        }
        if (cursorId != null) {
            sql.append(" AND (last_seen_at < ? OR (last_seen_at = ? AND id < ?))");
            params.add(cursorAt);
            params.add(cursorAt);
            params.add(cursorId);
        }
        sql.append(" ORDER BY last_seen_at DESC, id DESC LIMIT ").append(limit + 1);
        return db.read(c -> {
            List<GuestListItem> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i) instanceof Long l) {
                        ps.setLong(i + 1, l);
                    } else {
                        ps.setString(i + 1, (String) params.get(i));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new GuestListItem(view(rs), rs.getLong(7)));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                GuestListItem last = items.get(limit - 1);
                next = last.lastSeenAt() + "_" + last.guest().id();
            }
            return new Page(List.copyOf(items), next);
        });
    }

    private GuestView findByKey(GuestIdentity kind, String keyHash) throws SQLException {
        return db.read(c -> find(c, "key_hash = ? AND kind = ?", keyHash, kind.wireName()));
    }

    private static GuestView find(Connection c, String where, String a, String b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, kind, display_name, name_chosen, banned, created_at FROM guests WHERE " + where)) {
            ps.setString(1, a);
            if (b != null) {
                ps.setString(2, b);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return view(rs);
            }
        }
    }

    /** A {@link GuestView} from columns 1..6: id, kind, display_name, name_chosen, banned, created_at. */
    private static GuestView view(ResultSet rs) throws SQLException {
        GuestIdentity kind = rs.getString(2).equals(GuestIdentity.IP.wireName())
                ? GuestIdentity.IP : GuestIdentity.COOKIE;
        return new GuestView(rs.getString(1), kind, rs.getString(3), rs.getInt(4) != 0, rs.getInt(5) != 0,
                rs.getLong(6));
    }

    /**
     * Renames a guest to a name it chose ({@code name_chosen = 1}).
     *
     * @throws HttpError 403 {@code custom_names_disabled}; 403 {@code guest_banned}; 400
     *                   {@code invalid_name}, 409 {@code name_taken} or 400 {@code name_blocked}
     *                   ({@link GuestNameRules}); 404 {@code not_found} for an unknown guest
     */
    public GuestView rename(String guestId, String name, String clientIp) throws SQLException {
        String valid = checkedCustomName(name);
        long now = EpochMs.now(clock);
        return db.write(c -> {
            GuestView guest = find(c, "id = ?", guestId, null);
            if (guest == null) {
                throw new HttpError(404, "not_found", "No such guest");
            }
            if (guest.banned()) {
                throw new HttpError(403, "guest_banned", "This guest is banned");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE guests SET display_name = ?, name_chosen = 1 WHERE id = ?")) {
                ps.setString(1, valid);
                ps.setString(2, guestId);
                ps.executeUpdate();
            }
            AuditLog.write(c, now, null, guestId, clientIp, AUDIT_RENAMED, guestId, Map.of("name", valid));
            return new GuestView(guest.id(), guest.kind(), valid, true, false, guest.createdAt());
        });
    }

    /**
     * A custom guest name in its stored form ({@link GuestNameRules#normalise}), without the
     * account and blocklist checks. Otherwise 400 {@code invalid_name}.
     */
    public static String validCustomName(String name) {
        return GuestNameRules.normalise(name);
    }
}
