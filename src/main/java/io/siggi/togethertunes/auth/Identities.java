package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.security.SecureRandom;
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
 * A user's login-provider identities ({@code user_identities}, {@code docs/auth.md#oidc} step 4):
 * resolving a provider login to an account, linking, and the user's own list.
 *
 * <p><b>Resolution</b> is always by {@code (provider_id, subject)}, never by email (the email
 * claim is only stored on the identity, for the user's own list):
 * <ul>
 *   <li>{@link #login}: a known identity logs its user in (a disabled account gets
 *       {@code account_disabled}); an unknown one signs up a new account when the provider has
 *       {@code allow_signup} on ({@code signup_disabled} otherwise). The provider's flag is the
 *       only switch for that: {@code registration.mode} is for local registration. Before the
 *       first account exists (setup) a provider can't sign up anyone ({@code setup_required}).</li>
 *   <li>{@link #link}: adds the identity to the logged-in user ({@code intent=link}); one that
 *       belongs to another account is {@code identity_in_use}; a second, different identity at
 *       the same provider is {@code provider_already_linked} (unlink the old one first). Linking
 *       the identity the user already has is fine.</li>
 * </ul>
 * Each runs in one transaction that re-reads the provider (still enabled, same issuer and, for
 * OAuth2, same userinfo URL and subject path, else
 * {@code provider_unavailable}); signing up and linking take the account-creation lock
 * ({@link Accounts#lockAccountCreation}) and look the identity up again under it, so two
 * concurrent callbacks for one subject can't create two rows.
 *
 * <p><b>Signed-up accounts</b> have no password. Their username comes from the provider's username
 * claim (else its display name), kept to the username alphabet, made unique with a number
 * ({@code jane}, {@code jane2}, …), and {@code user} when nothing usable is left; the display
 * name from the display-name claim (the username if missing or invalid). They get the default
 * roles.
 *
 * <p><b>Unlinking</b> ({@link #unlink}) is refused with {@code 409 last_login_method} when it would
 * leave the account without a way to log in: no password and no other identity at an enabled
 * provider.
 *
 * <p>Audited: {@value Accounts#AUDIT_LOGIN} ({@code method: provider}),
 * {@value Accounts#AUDIT_LOGIN_FAILED} (with the reason), {@value #AUDIT_SIGNUP},
 * {@value #AUDIT_LINKED}, {@value #AUDIT_UNLINKED}; details name the provider by slug, never the
 * subject or email.
 */
public final class Identities {
    /** {@code audit_log} action of an account signed up through a provider. */
    public static final String AUDIT_SIGNUP = "account.register";
    public static final String AUDIT_LINKED = "auth.identity_linked";
    public static final String AUDIT_UNLINKED = "auth.identity_unlinked";
    /** Longest subject accepted (OIDC Core §2: at most 255 ASCII characters). */
    public static final int SUBJECT_MAX = 255;
    public static final int EMAIL_MAX = 320;
    static final String USERNAME_FALLBACK = "user";

    /**
     * Who the provider says logged in: what turns its answer into an identity.
     *
     * @param subject     the provider's stable ID for the user (required)
     * @param username    a username hint, or {@code null}
     * @param displayName a display name hint, or {@code null}
     * @param email       stored on the identity only, never used to find an account; or {@code null}
     */
    public record ProviderIdentity(String subject, String username, String displayName, String email) {
        public ProviderIdentity {
            Objects.requireNonNull(subject);
        }

        @Override
        public String toString() {
            return "ProviderIdentity[subject hidden]";
        }
    }

    /** What a login or link did. */
    public enum Outcome {
        LOGGED_IN, SIGNED_UP, LINKED, ALREADY_LINKED
    }

    public record Resolved(Accounts.Account account, Outcome outcome) {
    }

    /** An identity in the user's own list ({@code /api/me/identities}); no subject, no IP. */
    public record IdentityView(String id, String providerSlug, String providerName, boolean providerEnabled,
                               String email, long createdAt, Long lastLoginAt) {
    }

    /** A refusal inside a transaction; turned into a {@link OidcLogin.LoginException} outside it. */
    private static final class Refused extends RuntimeException {
        final String code;
        final String userId;

        Refused(String code, String message, String userId) {
            super(message, null, false, false);
            this.code = code;
            this.userId = userId;
        }
    }

    private record Row(String id, String userId) {
    }

    private final Database db;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public Identities(Database db) {
        this(db, Clock.systemUTC());
    }

    public Identities(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------- login / signup

    /**
     * Logs in (or signs up) the owner of {@code identity} at {@code provider}.
     *
     * @param ip the client IP, for the audit log only
     * @throws OidcLogin.LoginException {@code account_disabled}, {@code signup_disabled},
     *                                  {@code setup_required}, {@code provider_unavailable},
     *                                  {@code provider_error} (unusable subject)
     */
    public Resolved login(Providers.Provider provider, ProviderIdentity identity, String ip)
            throws OidcLogin.LoginException, SQLException {
        String subject = checkSubject(identity);
        String email = email(identity);
        long now = EpochMs.now(clock);
        try {
            return db.write(c -> {
                checkProvider(c, provider);
                Row row = find(c, provider.id(), subject);
                if (row == null) {
                    if (!provider.allowSignup()) {
                        throw new Refused("signup_disabled", "no account has this identity and sign-up is off", null);
                    }
                    if (!Accounts.anyExists(c)) {
                        throw new Refused("setup_required", "no account exists yet", null);
                    }
                    Accounts.lockAccountCreation(c);
                    row = find(c, provider.id(), subject); // a concurrent callback may have signed up
                    if (row == null) {
                        return new Resolved(signUp(c, provider, identity, subject, email, now, ip), Outcome.SIGNED_UP);
                    }
                }
                Accounts.Account account = account(c, row.userId());
                if (account == null) {
                    throw new Refused("account_disabled", "the account is disabled", row.userId());
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE user_identities SET last_login_at = ?, email = COALESCE(?, email) WHERE id = ?")) {
                    ps.setLong(1, now);
                    ps.setString(2, email);
                    ps.setString(3, row.id());
                    ps.executeUpdate();
                }
                AuditLog.write(c, now, account.id(), null, ip, Accounts.AUDIT_LOGIN, account.id(),
                        Map.of("method", "provider", "provider", provider.slug()));
                return new Resolved(account, Outcome.LOGGED_IN);
            });
        } catch (Refused r) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("method", "provider");
            details.put("provider", provider.slug());
            details.put("reason", r.code);
            db.write(c -> {
                AuditLog.write(c, now, null, null, ip, Accounts.AUDIT_LOGIN_FAILED, r.userId, details);
                return null;
            });
            throw new OidcLogin.LoginException(r.code, r.getMessage());
        }
    }

    private Accounts.Account signUp(Connection c, Providers.Provider provider, ProviderIdentity identity,
                                    String subject, String email, long now, String ip) throws SQLException {
        String username = freeUsername(c, usernameBase(identity.username() != null ? identity.username()
                : identity.displayName()));
        String display;
        try {
            display = Accounts.validDisplayName(identity.displayName(), username);
        } catch (HttpError e) {
            display = username;
        }
        String userId = UuidV7.newId();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, ?, ?, ?, 0)")) {
            ps.setString(1, userId);
            ps.setString(2, username);
            ps.setString(3, display);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
        List<String> roles = Accounts.grantRoles(c, userId, false);
        insert(c, userId, provider.id(), subject, email, now, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("username", username);
        details.put("roles", roles);
        details.put("method", "provider");
        details.put("provider", provider.slug());
        AuditLog.write(c, now, userId, null, ip, AUDIT_SIGNUP, userId, details);
        AuditLog.write(c, now, userId, null, ip, Accounts.AUDIT_LOGIN, userId,
                Map.of("method", "provider", "provider", provider.slug()));
        return new Accounts.Account(userId, username, display, roles, false);
    }

    // ------------------------------------------------------------- link

    /**
     * Links {@code identity} to the user {@code userId} ({@code intent=link}).
     *
     * @throws OidcLogin.LoginException {@code identity_in_use}, {@code provider_already_linked},
     *                                  {@code not_authenticated} (the user is gone or disabled),
     *                                  {@code provider_unavailable}, {@code provider_error}
     */
    public Resolved link(Providers.Provider provider, ProviderIdentity identity, String userId, String ip)
            throws OidcLogin.LoginException, SQLException {
        String subject = checkSubject(identity);
        String email = email(identity);
        long now = EpochMs.now(clock);
        try {
            return db.write(c -> {
                checkProvider(c, provider);
                Accounts.lockAccountCreation(c);
                Accounts.Account account = account(c, userId);
                if (account == null) {
                    throw new Refused("not_authenticated", "the linking user is disabled or gone", null);
                }
                Row row = find(c, provider.id(), subject);
                if (row != null) {
                    if (!row.userId().equals(userId)) {
                        throw new Refused("identity_in_use", "the identity belongs to another account", null);
                    }
                    return new Resolved(account, Outcome.ALREADY_LINKED);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM user_identities WHERE user_id = ? AND provider_id = ?")) {
                    ps.setString(1, userId);
                    ps.setString(2, provider.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new Refused("provider_already_linked",
                                    "the user has another identity at this provider", null);
                        }
                    }
                }
                String id = insert(c, userId, provider.id(), subject, email, now, null);
                AuditLog.write(c, now, userId, null, ip, AUDIT_LINKED, userId,
                        Map.of("provider", provider.slug(), "identity", id));
                return new Resolved(account, Outcome.LINKED);
            });
        } catch (Refused r) {
            throw new OidcLogin.LoginException(r.code, r.getMessage());
        }
    }

    // ------------------------------------------------------------- the user's list

    /** The user's identities, oldest first. */
    public List<IdentityView> list(String userId) throws SQLException {
        return db.read(c -> {
            List<IdentityView> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT i.id, p.slug, p.display_name, p.enabled, i.email, i.created_at, i.last_login_at"
                            + " FROM user_identities i JOIN oidc_providers p ON p.id = i.provider_id"
                            + " WHERE i.user_id = ? ORDER BY i.id")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long last = rs.getLong(7);
                        Long lastLogin = rs.wasNull() ? null : last;
                        out.add(new IdentityView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) != 0,
                                rs.getString(5), rs.getLong(6), lastLogin));
                    }
                }
            }
            return out;
        });
    }

    /** Whether the user has a local password. */
    public boolean hasPassword(String userId) throws SQLException {
        return db.read(c -> hasPassword(c, userId));
    }

    private static boolean hasPassword(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM password_credentials WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Removes one of the user's own identities.
     *
     * @throws HttpError 404 {@code identity_not_found} (unknown, or another user's);
     *                   409 {@code last_login_method}
     */
    public void unlink(String userId, String identityId, String ip) throws SQLException {
        long now = EpochMs.now(clock);
        db.write(c -> {
            // Serialise with other changes to this user's login methods (PostgreSQL; SQLite has one writer).
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE id = ?"
                    + (db.dialect() instanceof PostgresDialect ? " FOR UPDATE" : ""))) {
                ps.setString(1, userId);
                ps.executeQuery().close();
            }
            String slug;
            try (PreparedStatement ps = c.prepareStatement("SELECT p.slug FROM user_identities i"
                    + " JOIN oidc_providers p ON p.id = i.provider_id WHERE i.id = ? AND i.user_id = ?")) {
                ps.setString(1, identityId);
                ps.setString(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new HttpError(404, "identity_not_found", "No such linked login");
                    }
                    slug = rs.getString(1);
                }
            }
            boolean other = hasPassword(c, userId);
            if (!other) {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM user_identities i"
                        + " JOIN oidc_providers p ON p.id = i.provider_id"
                        + " WHERE i.user_id = ? AND i.id <> ? AND p.enabled = 1")) {
                    ps.setString(1, userId);
                    ps.setString(2, identityId);
                    try (ResultSet rs = ps.executeQuery()) {
                        other = rs.next();
                    }
                }
            }
            if (!other) {
                throw new HttpError(409, "last_login_method",
                        "This is your only way to log in; set a password or link another login first");
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM user_identities WHERE id = ?")) {
                ps.setString(1, identityId);
                ps.executeUpdate();
            }
            AuditLog.write(c, now, userId, null, ip, AUDIT_UNLINKED, userId,
                    Map.of("provider", slug, "identity", identityId));
            return null;
        });
    }

    // ------------------------------------------------------------- helpers

    private static String checkSubject(ProviderIdentity identity) throws OidcLogin.LoginException {
        String s = identity.subject();
        if (s.isEmpty() || s.length() > SUBJECT_MAX) {
            throw new OidcLogin.LoginException("provider_error", "the provider's subject is empty or too long");
        }
        return s;
    }

    private static String email(ProviderIdentity identity) {
        String e = identity.email();
        if (e == null) {
            return null;
        }
        e = e.strip();
        return e.isEmpty() || e.length() > EMAIL_MAX || e.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f) ? null : e;
    }

    /**
     * The provider as stored must still be the one the login started with: enabled, and what gives
     * its subjects their meaning unchanged (the issuer; for OAuth2 also the userinfo URL and the
     * subject path).
     */
    private static void checkProvider(Connection c, Providers.Provider provider) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT enabled, issuer, claim_mapping_json FROM oidc_providers WHERE id = ?")) {
            ps.setString(1, provider.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0 || !Objects.equals(rs.getString(2), provider.issuer())
                        || provider.kind() == Providers.Kind.OAUTH2
                        && !sameSubjectSource(ClaimMapping.fromStored(rs.getString(3)), provider.claimMapping())) {
                    throw new Refused("provider_unavailable", "the provider was disabled or changed", null);
                }
            }
        }
    }

    private static boolean sameSubjectSource(ClaimMapping stored, ClaimMapping used) {
        return stored != null && used != null && Objects.equals(stored.userinfoUrl(), used.userinfoUrl())
                && Objects.equals(stored.subject(), used.subject());
    }

    private static Row find(Connection c, String providerId, String subject) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, user_id FROM user_identities WHERE provider_id = ? AND subject = ?")) {
            ps.setString(1, providerId);
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getString(1), rs.getString(2)) : null;
            }
        }
    }

    private static String insert(Connection c, String userId, String providerId, String subject, String email,
                                 long now, Long lastLoginAt) throws SQLException {
        String id = UuidV7.newId();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO user_identities (id, user_id, provider_id,"
                + " subject, email, created_at, last_login_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, providerId);
            ps.setString(4, subject);
            ps.setString(5, email);
            ps.setLong(6, now);
            if (lastLoginAt == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, lastLoginAt);
            }
            ps.executeUpdate();
        }
        return id;
    }

    /** The enabled account, or {@code null}. */
    private static Accounts.Account account(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT u.id, u.username, u.display_name, p.must_change FROM users u"
                        + " LEFT JOIN password_credentials p ON p.user_id = u.id WHERE u.id = ? AND u.disabled = 0")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Accounts.Account(rs.getString(1), rs.getString(2), rs.getString(3),
                        Accounts.roleNames(c, rs.getString(1)), rs.getInt(4) != 0);
            }
        }
    }

    /**
     * A username from a provider's hint: only {@code A–Z a–z 0–9 . _ -}, starting with a letter or
     * digit, at most {@link Accounts#USERNAME_MAX}; {@value #USERNAME_FALLBACK} when shorter than
     * {@link Accounts#USERNAME_MIN}.
     */
    static String usernameBase(String hint) {
        StringBuilder out = new StringBuilder();
        if (hint != null) {
            for (int i = 0; i < hint.length() && out.length() < Accounts.USERNAME_MAX; i++) {
                char ch = hint.charAt(i);
                boolean alnum = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
                if (alnum || (!out.isEmpty() && (ch == '.' || ch == '_' || ch == '-'))) {
                    out.append(ch);
                }
            }
        }
        return out.length() < Accounts.USERNAME_MIN ? USERNAME_FALLBACK : out.toString();
    }

    /** {@code base}, or {@code base2}, {@code base3}, … (shortened to fit), whichever is free. */
    private String freeUsername(Connection c, String base) throws SQLException {
        if (!Accounts.usernameTaken(c, base)) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            String candidate = withSuffix(base, Integer.toString(n));
            if (!Accounts.usernameTaken(c, candidate)) {
                return candidate;
            }
        }
        while (true) {
            String candidate = withSuffix(base, Integer.toString(100_000 + random.nextInt(900_000)));
            if (!Accounts.usernameTaken(c, candidate)) {
                return candidate;
            }
        }
    }

    private static String withSuffix(String base, String suffix) {
        int keep = Math.min(base.length(), Accounts.USERNAME_MAX - suffix.length());
        return base.substring(0, keep) + suffix;
    }
}
