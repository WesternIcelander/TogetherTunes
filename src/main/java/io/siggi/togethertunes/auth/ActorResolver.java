package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.Hook;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves every request to exactly one {@link Actor} ({@code docs/auth.md}).
 *
 * <p>Order:
 * <ol>
 *   <li>An {@code Authorization: Bearer} header decides alone: a live {@code bearer} session of an
 *       enabled user gives that {@link Actor.User}; anything else is a {@code 401
 *       invalid_token} (a client that sends a token wants to be that user, so it must not
 *       silently become anonymous). Cookies are then ignored, so a bearer request is never
 *       cookie-authenticated. Other {@code Authorization} schemes are ignored.</li>
 *   <li>The {@code sid} cookie: a live {@code cookie} session of an enabled user. An unknown,
 *       expired or revoked {@code sid}, or one of a disabled user, is ignored (stale cookies are
 *       normal), and resolution goes on.</li>
 *   <li>If {@code guests.enabled} is off: {@link Actor#ANONYMOUS}.</li>
 *   <li>The {@code gid} cookie: a {@code cookie} guest with that key. Honoured in both
 *       {@code guests.identifyBy} modes, since changing the mode doesn't migrate existing
 *       guests.</li>
 *   <li>In IP mode, the guest whose key is the client IP ({@link GuestKeys}).</li>
 *   <li>Otherwise {@link Actor#ANONYMOUS}.</li>
 * </ol>
 *
 * <p>Using a session slides its expiry ({@link Sessions#touch}, at most once a minute); over
 * HTTP, a slid {@code cookie} session also gets its {@code sid} cookie re-sent with a fresh
 * {@code Max-Age}, so the browser keeps it as long as the server does. Likewise using a guest
 * records it ({@link Guests#touch}: {@code last_seen_at} at most once a minute, {@code ip_last}
 * whenever the client IP changed), and over HTTP a recorded {@code cookie} guest gets its
 * {@code gid} cookie re-sent with a fresh one-year {@code Max-Age}. Resolution never creates a
 * guest: that happens lazily on the first action that needs an identity
 * ({@link Guests#requireIdentity}).
 *
 * <p>Over HTTP, {@link #hook()} resolves lazily: the actor is looked up the first time
 * {@link #actor(Request)} is called for a request and then reused, so static files never touch
 * the database.
 */
public final class ActorResolver {
    /** The session cookie. */
    public static final String SESSION_COOKIE = "sid";
    /** The guest cookie. */
    public static final String GUEST_COOKIE = "gid";

    private final Database db;
    private final Settings settings;
    private final Clock clock;
    private final Sessions sessions;
    private final boolean secureCookies;

    /** What a request presents to identify itself. Any component may be {@code null}. */
    public record Credentials(String bearer, String sessionCookie, String guestCookie, String clientIp) {
        /** A bearer header that was present but empty or malformed. */
        static final String MALFORMED_BEARER = "";

        /** The credentials of {@code request}. */
        public static Credentials of(Request request) {
            return new Credentials(bearer(request.header("Authorization")), request.cookie(SESSION_COOKIE),
                    request.cookie(GUEST_COOKIE), request.clientIp());
        }

        /**
         * The token of an {@code Authorization: Bearer <token>} header; {@code ""} for a Bearer
         * header without a token; {@code null} for no header or another scheme.
         */
        static String bearer(String authorization) {
            if (authorization == null) {
                return null;
            }
            String value = authorization.strip();
            int space = value.indexOf(' ');
            String scheme = space < 0 ? value : value.substring(0, space);
            if (!scheme.toLowerCase(Locale.ROOT).equals("bearer")) {
                return null;
            }
            return space < 0 ? MALFORMED_BEARER : value.substring(space + 1).strip();
        }
    }

    /** With {@code Secure} session cookies. */
    public ActorResolver(Database db, Settings settings) {
        this(db, settings, Clock.systemUTC(), true);
    }

    /**
     * @param secureCookies whether a re-sent {@code sid} cookie gets the {@code Secure} flag (as
     *                      in {@link AuthApi})
     */
    public ActorResolver(Database db, Settings settings, boolean secureCookies) {
        this(db, settings, Clock.systemUTC(), secureCookies);
    }

    public ActorResolver(Database db, Settings settings, Clock clock) {
        this(db, settings, clock, true);
    }

    public ActorResolver(Database db, Settings settings, Clock clock, boolean secureCookies) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.sessions = new Sessions(db, clock);
        this.secureCookies = secureCookies;
    }

    /**
     * An actor, and the {@code Set-Cookie} value to re-send because its {@code sid} session or
     * {@code gid} guest just slid ({@code null}: none).
     */
    private record Resolved(Actor actor, String setCookie) {
    }

    /**
     * The actor for {@code credentials}.
     *
     * @throws HttpError 401 {@code invalid_token} for a bearer token that doesn't identify a user
     */
    public Actor resolve(Credentials credentials) throws SQLException {
        return resolveFull(credentials).actor();
    }

    private Resolved resolveFull(Credentials credentials) throws SQLException {
        long now = EpochMs.now(clock);
        if (credentials.bearer() != null) {
            SessionUser found = Tokens.isWellFormed(credentials.bearer())
                    ? findUser(credentials.bearer(), Actor.Via.BEARER, now) : null;
            Actor.User user = found == null ? null : found.user();
            if (user == null) {
                throw new HttpError(401, "invalid_token", "The bearer token is invalid or expired");
            }
            return new Resolved(user, null);
        }
        if (Tokens.isWellFormed(credentials.sessionCookie())) {
            SessionUser found = findUser(credentials.sessionCookie(), Actor.Via.COOKIE, now);
            if (found != null) {
                return new Resolved(found.user(),
                        found.slid() ? Sessions.cookie(credentials.sessionCookie(), secureCookies) : null);
            }
        }
        boolean wantGuestCookie = Tokens.isWellFormed(credentials.guestCookie());
        if (!wantGuestCookie && credentials.clientIp() == null) {
            return new Resolved(Actor.ANONYMOUS, null);
        }
        if (!settings.guestsEnabled()) {
            return new Resolved(Actor.ANONYMOUS, null);
        }
        if (wantGuestCookie) {
            GuestRow row = findGuest(GuestIdentity.COOKIE, Tokens.hash(credentials.guestCookie()), credentials.clientIp());
            if (row != null) {
                boolean touched = touch(row, credentials.clientIp(), now);
                return new Resolved(row.guest(),
                        touched ? Guests.cookie(credentials.guestCookie(), secureCookies) : null);
            }
        }
        if (credentials.clientIp() != null && settings.guestsIdentifyBy() == GuestIdentity.IP) {
            String keyHash = GuestKeys.ipKeyHash(credentials.clientIp());
            if (keyHash != null) {
                GuestRow row = findGuest(GuestIdentity.IP, keyHash, credentials.clientIp());
                if (row != null) {
                    touch(row, credentials.clientIp(), now);
                    return new Resolved(row.guest(), null);
                }
            }
        }
        return new Resolved(Actor.ANONYMOUS, null);
    }

    private boolean touch(GuestRow row, String clientIp, long now) throws SQLException {
        return Guests.touch(db, row.guest().guestId(), row.lastSeenAt(), row.sameIp(), clientIp, now);
    }

    /** A user found by session token; {@code slid} if this use moved the session's expiry. */
    private record SessionUser(Actor.User user, boolean slid) {
    }

    private SessionUser findUser(String token, Actor.Via via, long now) throws SQLException {
        String hash = Tokens.hash(token);
        record Row(Actor.User user, long lastSeenAt) {
        }
        Row row = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.id, s.user_id, s.last_seen_at FROM sessions s JOIN users u ON u.id = s.user_id"
                            + " WHERE s.token_hash = ? AND s.kind = ? AND s.expires_at > ? AND u.disabled = 0")) {
                ps.setString(1, hash);
                ps.setString(2, via.kind());
                ps.setLong(3, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Row(new Actor.User(rs.getString(2), rs.getString(1), via), rs.getLong(3)) : null;
                }
            }
        });
        if (row == null) {
            return null;
        }
        boolean slid = now - row.lastSeenAt() >= Sessions.TOUCH_INTERVAL_MS
                && sessions.touch(row.user().sessionId(), row.lastSeenAt()) != 0;
        return new SessionUser(row.user(), slid);
    }

    /**
     * A guest found by key, with what {@link Guests#touch} needs: whether its stored
     * {@code ip_last} equals this request's IP (the stored IP itself is not read; see {@link IpAccess}).
     */
    private record GuestRow(Actor.Guest guest, long lastSeenAt, boolean sameIp) {
    }

    private GuestRow findGuest(GuestIdentity kind, String keyHash, String clientIp) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, banned, last_seen_at, " + IpAccess.GUEST_IP_UNCHANGED
                            + " FROM guests WHERE key_hash = ? AND kind = ?")) {
                ps.setString(1, clientIp);
                ps.setString(2, keyHash);
                ps.setString(3, kind.wireName());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new GuestRow(new Actor.Guest(rs.getString(1), kind, rs.getInt(2) != 0),
                            rs.getLong(3), rs.getInt(4) != 0) : null;
                }
            }
        });
    }

    // ------------------------------------------------------------------ HTTP

    /** The router-wide hook: makes {@link #actor(Request)} work for every request. */
    public Hook hook() {
        return (request, response, next) -> {
            request.setAttribute(Lazy.class, new Lazy(this, Credentials.of(request), response));
            next.handle(request, response);
        };
    }

    /**
     * The actor of {@code request}, resolved on first use and then cached for the request.
     *
     * @throws HttpError 401 {@code invalid_token} for an invalid bearer token (on every call)
     * @throws IllegalStateException if the request didn't pass through {@link #hook()}
     */
    public static Actor actor(Request request) throws SQLException {
        Lazy lazy = request.attribute(Lazy.class);
        if (lazy == null) {
            throw new IllegalStateException("ActorResolver.hook() is not installed on this router");
        }
        return lazy.get();
    }

    /**
     * Replaces the actor of {@code request} for the rest of the request, e.g. after an anonymous
     * request became a guest ({@link Guests#requireIdentity}).
     *
     * @throws IllegalStateException if the request didn't pass through {@link #hook()}
     */
    static void setActor(Request request, Actor actor) {
        Lazy lazy = request.attribute(Lazy.class);
        if (lazy == null) {
            throw new IllegalStateException("ActorResolver.hook() is not installed on this router");
        }
        lazy.actor = Objects.requireNonNull(actor);
        lazy.error = null;
    }

    /** Per-request memo. Requests are handled on one thread, so no locking. */
    private static final class Lazy {
        private final ActorResolver resolver;
        private final Credentials credentials;
        private final Response response;
        private Actor actor;
        private HttpError error;

        Lazy(ActorResolver resolver, Credentials credentials, Response response) {
            this.resolver = resolver;
            this.credentials = credentials;
            this.response = response;
        }

        Actor get() throws SQLException {
            if (error != null) {
                throw error;
            }
            if (actor == null) {
                try {
                    Resolved resolved = resolver.resolveFull(credentials);
                    actor = resolved.actor();
                    if (resolved.setCookie() != null && !response.committed()) {
                        response.setCookie(resolved.setCookie());
                    }
                } catch (HttpError e) {
                    error = e;
                    throw e;
                }
            }
            return actor;
        }
    }
}
