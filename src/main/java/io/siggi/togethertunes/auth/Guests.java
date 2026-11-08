package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
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
 * Both modes record the creating request's IP in {@code ip_last}, which only {@code IpAccess}
 * reads; {@link GuestView} has no IP field ({@code docs/auth.md#ip-address-policy}).
 *
 * <p>Names: without a chosen name a guest gets a generated one ({@code name_chosen = 0}); with
 * {@code guests.allowCustomNames} on, a name can be given at creation and changed later
 * ({@link #validCustomName}).
 */
public final class Guests {
    /** Lifetime of the {@code gid} cookie: one year. */
    public static final long COOKIE_MAX_AGE_SECONDS = 365L * 24 * 60 * 60;
    /** Shortest custom guest name, in code points. */
    public static final int NAME_MIN = 2;
    /** Longest custom guest name, in code points. */
    public static final int NAME_MAX = 32;
    /** {@code audit_log} action of a guest identity created. */
    public static final String AUDIT_CREATED = "guest.created";
    /** {@code audit_log} action of a guest renaming itself. */
    public static final String AUDIT_RENAMED = "guest.renamed";

    private static final SecureRandom RANDOM = new SecureRandom();

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
        this(db, settings, Clock.systemUTC(), secureCookies, Guests::placeholderName);
    }

    /** @param names generates a name for a guest that hasn't chosen one */
    public Guests(Database db, Settings settings, Clock clock, boolean secureCookies, Supplier<String> names) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.secureCookies = secureCookies;
        this.names = Objects.requireNonNull(names);
    }

    /** Placeholder until the bundled adjective and noun lists exist: {@code Guest 1234}. */
    static String placeholderName() {
        return "Guest " + (1000 + RANDOM.nextInt(9000));
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
     *                   {@code customName} while custom names are off; 400 {@code invalid_name}
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

    /** {@link #validCustomName}, after checking that custom names are allowed. */
    private String checkedCustomName(String name) throws SQLException {
        if (!settings.guestsAllowCustomNames()) {
            throw new HttpError(403, "custom_names_disabled", "Guests can't choose their names on this server");
        }
        return validCustomName(name);
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
        String displayName = chosen ? name : names.get();
        long now = EpochMs.now(clock);
        try {
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO guests (id, kind, key_hash, ip_last, display_name, name_chosen, created_at,"
                                + " last_seen_at, banned) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
                    ps.setString(1, id);
                    ps.setString(2, mode.wireName());
                    ps.setString(3, keyHash);
                    ps.setString(4, clientIp);
                    ps.setString(5, displayName);
                    ps.setInt(6, chosen ? 1 : 0);
                    ps.setLong(7, now);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                }
                AuditLog.write(c, now, null, id, clientIp, AUDIT_CREATED, id, Map.of("kind", mode.wireName()));
                return null;
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

    /** The guest with this id, or {@code null}. */
    public GuestView get(String guestId) throws SQLException {
        return db.read(c -> find(c, "id = ?", guestId, null));
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
                GuestIdentity kind = rs.getString(2).equals(GuestIdentity.IP.wireName())
                        ? GuestIdentity.IP : GuestIdentity.COOKIE;
                return new GuestView(rs.getString(1), kind, rs.getString(3), rs.getInt(4) != 0, rs.getInt(5) != 0,
                        rs.getLong(6));
            }
        }
    }

    /**
     * Renames a guest to a name it chose ({@code name_chosen = 1}).
     *
     * @throws HttpError 403 {@code custom_names_disabled}; 403 {@code guest_banned}; 400
     *                   {@code invalid_name}; 404 {@code not_found} for an unknown guest
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
     * A custom guest name as stored: whitespace trimmed and collapsed, {@value #NAME_MIN} to
     * {@value #NAME_MAX} code points, no control or format characters. Otherwise 400
     * {@code invalid_name}.
     */
    public static String validCustomName(String name) {
        String n = name == null ? "" : name.replaceAll("(?U)\\s+", " ").strip();
        int length = n.codePointCount(0, n.length());
        boolean ok = length >= NAME_MIN && length <= NAME_MAX && n.codePoints().noneMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
        });
        if (!ok) {
            throw new HttpError(400, "invalid_name", "Guest names are " + NAME_MIN + " to " + NAME_MAX
                    + " characters, without control characters");
        }
        return n;
    }
}
