package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.Hook;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
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
 * <p>Resolution only reads. It never creates a guest (that happens lazily on the first action
 * that needs an identity) and never updates {@code last_seen_at}/{@code expires_at}/{@code ip_last};
 * the session and guest steps of M1 do that.
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

    public ActorResolver(Database db, Settings settings) {
        this(db, settings, Clock.systemUTC());
    }

    public ActorResolver(Database db, Settings settings, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * The actor for {@code credentials}.
     *
     * @throws HttpError 401 {@code invalid_token} for a bearer token that doesn't identify a user
     */
    public Actor resolve(Credentials credentials) throws SQLException {
        long now = EpochMs.now(clock);
        if (credentials.bearer() != null) {
            Actor.User user = Tokens.isWellFormed(credentials.bearer())
                    ? findUser(credentials.bearer(), Actor.Via.BEARER, now) : null;
            if (user == null) {
                throw new HttpError(401, "invalid_token", "The bearer token is invalid or expired");
            }
            return user;
        }
        if (Tokens.isWellFormed(credentials.sessionCookie())) {
            Actor.User user = findUser(credentials.sessionCookie(), Actor.Via.COOKIE, now);
            if (user != null) {
                return user;
            }
        }
        boolean wantGuestCookie = Tokens.isWellFormed(credentials.guestCookie());
        if (!wantGuestCookie && credentials.clientIp() == null) {
            return Actor.ANONYMOUS;
        }
        if (!settings.guestsEnabled()) {
            return Actor.ANONYMOUS;
        }
        if (wantGuestCookie) {
            Actor.Guest guest = findGuest(GuestIdentity.COOKIE, Tokens.hash(credentials.guestCookie()));
            if (guest != null) {
                return guest;
            }
        }
        if (credentials.clientIp() != null && settings.guestsIdentifyBy() == GuestIdentity.IP) {
            String keyHash = GuestKeys.ipKeyHash(credentials.clientIp());
            if (keyHash != null) {
                Actor.Guest guest = findGuest(GuestIdentity.IP, keyHash);
                if (guest != null) {
                    return guest;
                }
            }
        }
        return Actor.ANONYMOUS;
    }

    private Actor.User findUser(String token, Actor.Via via, long now) throws SQLException {
        String hash = Tokens.hash(token);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.id, s.user_id FROM sessions s JOIN users u ON u.id = s.user_id"
                            + " WHERE s.token_hash = ? AND s.kind = ? AND s.expires_at > ? AND u.disabled = 0")) {
                ps.setString(1, hash);
                ps.setString(2, via.kind());
                ps.setLong(3, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Actor.User(rs.getString(2), rs.getString(1), via) : null;
                }
            }
        });
    }

    private Actor.Guest findGuest(GuestIdentity kind, String keyHash) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, banned FROM guests WHERE key_hash = ? AND kind = ?")) {
                ps.setString(1, keyHash);
                ps.setString(2, kind.wireName());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Actor.Guest(rs.getString(1), kind, rs.getInt(2) != 0) : null;
                }
            }
        });
    }

    // ------------------------------------------------------------------ HTTP

    /** The router-wide hook: makes {@link #actor(Request)} work for every request. */
    public Hook hook() {
        return (request, response, next) -> {
            request.setAttribute(Lazy.class, new Lazy(this, Credentials.of(request)));
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

    /** Per-request memo. Requests are handled on one thread, so no locking. */
    private static final class Lazy {
        private final ActorResolver resolver;
        private final Credentials credentials;
        private Actor actor;
        private HttpError error;

        Lazy(ActorResolver resolver, Credentials credentials) {
            this.resolver = resolver;
            this.credentials = credentials;
        }

        Actor get() throws SQLException {
            if (error != null) {
                throw error;
            }
            if (actor == null) {
                try {
                    actor = resolver.resolve(credentials);
                } catch (HttpError e) {
                    error = e;
                    throw e;
                }
            }
            return actor;
        }
    }
}
