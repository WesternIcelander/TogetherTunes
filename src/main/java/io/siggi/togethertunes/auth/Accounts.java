package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Creates local (username + password) accounts.
 *
 * <p><b>First account:</b> the account that turns an empty {@code users} table into a non-empty one
 * gets the {@value BuiltinRoles#ADMIN} role ({@code docs/auth.md}), whichever way it is created.
 * Every account also gets the default roles ({@code roles.is_default}, initially
 * {@value BuiltinRoles#USER}). All account creations are serialised by locking the
 * {@code admin} role row first (a row lock on PostgreSQL; SQLite has a single writer anyway), so
 * two concurrent first sign-ups can't both become admin, and two sign-ups with the same username
 * can't both pass the clash check.
 *
 * <p><b>Usernames:</b> {@value #USERNAME_MIN}–{@value #USERNAME_MAX} characters from
 * {@code A–Z a–z 0–9 . _ -}, starting with a letter or digit; surrounding whitespace is ignored.
 * They are unique case-insensitively but stored as typed. <b>Display names:</b> trimmed, inner
 * whitespace collapsed to one space, 1–{@value #DISPLAY_NAME_MAX} characters, no control
 * characters; the username when left out. <b>Passwords:</b> {@link Passwords#problem}.
 */
public final class Accounts {
    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 32;
    public static final int DISPLAY_NAME_MAX = 64;

    private final Database db;
    private final Passwords passwords;
    private final Clock clock;
    /** Test hook: runs inside the transaction right after the first-account check. */
    volatile Runnable afterFirstCheck = () -> { };

    /** An account as created. */
    public record Account(String id, String username, String displayName, List<String> roles) {
    }

    public Accounts(Database db, Passwords passwords) {
        this(db, passwords, Clock.systemUTC());
    }

    public Accounts(Database db, Passwords passwords, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.passwords = Objects.requireNonNull(passwords);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Whether at least one account exists (if not, the server shows the setup page). */
    public boolean anyExists() throws SQLException {
        return db.read(Accounts::anyExists);
    }

    private static boolean anyExists(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    /**
     * Creates a local account.
     *
     * @param onlyFirst   fail with 409 {@code setup_complete} unless this is the first account
     *                    (the setup flow)
     * @param ip          the client IP, for the audit log only
     * @param auditAction the {@code audit_log} action, e.g. {@code account.setup}
     * @throws HttpError 400 {@code invalid_username}, {@code invalid_display_name} or
     *                   {@code invalid_password}; 409 {@code username_taken} or
     *                   {@code setup_complete}
     */
    public Account createLocal(String username, String displayName, String password, boolean onlyFirst,
                               String ip, String auditAction) throws SQLException {
        String name = validUsername(username);
        String display = validDisplayName(displayName, name);
        String passwordProblem = Passwords.problem(password);
        if (passwordProblem != null) {
            throw new HttpError(400, "invalid_password", passwordProblem);
        }
        if (onlyFirst && anyExists()) {
            throw setupComplete(); // cheap early answer; checked again under the lock
        }
        String hash = passwords.hash(password); // slow: keep it out of the transaction
        long now = EpochMs.now(clock);
        String id = UuidV7.newId();
        return db.write(c -> {
            lockAccountCreation(c);
            boolean first = !anyExists(c);
            afterFirstCheck.run();
            if (onlyFirst && !first) {
                throw setupComplete();
            }
            if (usernameTaken(c, name)) {
                throw new HttpError(409, "username_taken", "That username is taken");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, ?, ?, ?, 0)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, display);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO password_credentials (user_id, hash, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, hash);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            List<String> roles = grantRoles(c, id, first);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("username", name);
            details.put("roles", roles);
            AuditLog.write(c, now, id, null, ip, auditAction, id, details);
            return new Account(id, name, display, roles);
        });
    }

    /**
     * Checks a local username and password ({@code docs/auth.md#local-passwords}). The username is
     * matched case-insensitively after stripping surrounding whitespace. On success the hash is
     * replaced by a fresh one if its Argon2 parameters differ from the current ones
     * ({@link Passwords#needsRehash}), and the login is audited as {@value #AUDIT_LOGIN}; a failure
     * is audited as {@value #AUDIT_LOGIN_FAILED} (no actor; {@code details.username} as typed,
     * shortened).
     *
     * <p>An unknown username, an account without a local password and a wrong password all give
     * the same answer, and all three cost one Argon2 derivation, so the answer and its timing
     * don't reveal which usernames exist. The right password for a disabled account gives
     * {@code 403 account_disabled}.
     *
     * @param ip the client IP, for the audit log only
     * @throws HttpError 401 {@code invalid_credentials}; 403 {@code account_disabled}
     */
    public Account login(String username, String password, String ip) throws SQLException {
        String name = username == null ? "" : username.strip();
        boolean plausible = !name.isEmpty() && name.length() <= USERNAME_MAX && password != null
                && password.length() <= 2 * Passwords.MAX_LENGTH;
        Credential credential = plausible ? db.read(c -> credential(c, name)) : null;
        boolean ok;
        if (credential == null || credential.hash() == null) {
            passwords.verify(plausible ? password : "", dummyHash()); // same cost as a real check
            ok = false;
        } else {
            ok = passwords.verify(password, credential.hash());
        }
        long now = EpochMs.now(clock);
        if (!ok) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("username", name.length() > USERNAME_MAX ? name.substring(0, USERNAME_MAX) + "…" : name);
            details.put("reason", credential == null ? "unknown_user"
                    : credential.hash() == null ? "no_password" : "wrong_password");
            db.write(c -> {
                AuditLog.write(c, now, null, null, ip, AUDIT_LOGIN_FAILED, credential == null ? null : credential.userId(),
                        details);
                return null;
            });
            throw new HttpError(401, "invalid_credentials", "Wrong username or password");
        }
        if (credential.disabled()) {
            db.write(c -> {
                AuditLog.write(c, now, null, null, ip, AUDIT_LOGIN_FAILED, credential.userId(),
                        Map.of("username", credential.username(), "reason", "disabled"));
                return null;
            });
            throw new HttpError(403, "account_disabled", "This account is disabled");
        }
        String newHash = passwords.needsRehash(credential.hash()) ? passwords.hash(password) : null;
        return db.write(c -> {
            if (newHash != null) {
                // Only replace the hash we verified, so a concurrent password change wins.
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE password_credentials SET hash = ?, updated_at = ? WHERE user_id = ? AND hash = ?")) {
                    ps.setString(1, newHash);
                    ps.setLong(2, now);
                    ps.setString(3, credential.userId());
                    ps.setString(4, credential.hash());
                    ps.executeUpdate();
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("method", "password");
            if (newHash != null) {
                details.put("rehashed", true);
            }
            AuditLog.write(c, now, credential.userId(), null, ip, AUDIT_LOGIN, credential.userId(), details);
            return new Account(credential.userId(), credential.username(), credential.displayName(),
                    roleNames(c, credential.userId()));
        });
    }

    /** The enabled account with this id, or {@code null} (unknown or disabled). */
    public Account findEnabled(String userId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, username, display_name FROM users WHERE id = ? AND disabled = 0")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new Account(rs.getString(1), rs.getString(2), rs.getString(3), roleNames(c, rs.getString(1)));
                }
            }
        });
    }

    /** Writes one {@code audit_log} row with a user actor (or none), in its own transaction. */
    public void audit(String actorUserId, String ip, String action, String target, Map<String, ?> details)
            throws SQLException {
        long now = EpochMs.now(clock);
        db.write(c -> {
            AuditLog.write(c, now, actorUserId, null, ip, action, target, details);
            return null;
        });
    }

    /** {@code audit_log} action of a successful login. */
    public static final String AUDIT_LOGIN = "auth.login";
    /** {@code audit_log} action of a failed login. */
    public static final String AUDIT_LOGIN_FAILED = "auth.login_failed";

    /** A user row with its local password hash ({@code null} if it has none). */
    private record Credential(String userId, String username, String displayName, boolean disabled, String hash) {
    }

    private static Credential credential(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT u.id, u.username, u.display_name, u.disabled, p.hash FROM users u"
                        + " LEFT JOIN password_credentials p ON p.user_id = u.id WHERE lower(u.username) = ?")) {
            ps.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Credential(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4) != 0, rs.getString(5)) : null;
            }
        }
    }

    private volatile String dummyHash;

    /** A hash of a random password at the current parameters, so failed lookups cost a real verify. */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwords.hash(Tokens.newToken());
            dummyHash = hash;
        }
        return hash;
    }

    private static HttpError setupComplete() {
        return new HttpError(409, "setup_complete", "Setup is already complete");
    }

    /** Takes the lock that serialises account creation (see the class comment). */
    private static void lockAccountCreation(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE roles SET builtin = 1 WHERE name = ? AND builtin = 1")) {
            ps.setString(1, BuiltinRoles.ADMIN);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("The built-in admin role is missing (BuiltinRoles.ensure not run)");
            }
        }
    }

    private static boolean usernameTaken(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE lower(username) = ?")) {
            ps.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Grants the default roles, plus admin for the first account; returns the role names, sorted. */
    private static List<String> grantRoles(Connection c, String userId, boolean first) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE is_default = 1 OR (? = 1 AND name = ?)")) {
            ps.setString(1, userId);
            ps.setInt(2, first ? 1 : 0);
            ps.setString(3, BuiltinRoles.ADMIN);
            ps.executeUpdate();
        }
        return roleNames(c, userId);
    }

    /** The names of the user's roles, sorted. */
    private static List<String> roleNames(Connection c, String userId) throws SQLException {
        List<String> roles = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY r.name")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
        }
        return roles;
    }

    /**
     * The username as stored, or 400 {@code invalid_username}.
     */
    public static String validUsername(String username) {
        String name = username == null ? "" : username.strip();
        boolean ok = name.length() >= USERNAME_MIN && name.length() <= USERNAME_MAX;
        for (int i = 0; ok && i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean alnum = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            ok = alnum || (i > 0 && (ch == '.' || ch == '_' || ch == '-'));
        }
        if (!ok) {
            throw new HttpError(400, "invalid_username", "Usernames are " + USERNAME_MIN + " to " + USERNAME_MAX
                    + " characters: letters, digits, '.', '_' and '-', starting with a letter or digit");
        }
        return name;
    }

    /**
     * The display name as stored ({@code fallback} when {@code displayName} is null or blank), or
     * 400 {@code invalid_display_name}.
     */
    public static String validDisplayName(String displayName, String fallback) {
        String name = displayName == null ? "" : displayName.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return fallback;
        }
        int length = name.codePointCount(0, name.length());
        boolean ok = length <= DISPLAY_NAME_MAX && name.codePoints().noneMatch(cp -> {
            if (cp == 0x200D) {
                return false; // zero-width joiner, used in emoji sequences
            }
            int type = Character.getType(cp);
            return type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
        });
        if (!ok) {
            throw new HttpError(400, "invalid_display_name", "Display names are 1 to " + DISPLAY_NAME_MAX
                    + " characters, without control characters");
        }
        return name;
    }
}
