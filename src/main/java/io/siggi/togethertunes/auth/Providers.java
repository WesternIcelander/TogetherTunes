package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.secrets.SecretDecryptionException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Login providers ({@code oidc_providers}; {@code docs/auth.md#oidc}): the service behind
 * {@link ProvidersApi}. Any number of providers can be enabled at once, each with its own login
 * button; nothing here assumes there is only one.
 *
 * <ul>
 *   <li><b>kind</b>: {@code oidc} (OpenID Connect; discovery from {@code issuer}) or
 *       {@code oauth2} (plain OAuth 2.0; endpoints and claim paths in {@link ClaimMapping}). Fixed
 *       at creation.</li>
 *   <li><b>slug</b>: 1–{@value #SLUG_MAX} of {@code a-z 0-9 -}, starting with a letter or digit
 *       (lower-cased first), unique ({@code 409 provider_slug_taken}). It is in the login URLs and
 *       the redirect URI {@code {publicBaseUrl}/auth/oidc/{slug}/callback}.</li>
 *   <li><b>displayName</b>: the button label, 1–{@value #DISPLAY_NAME_MAX} characters.</li>
 *   <li><b>issuer</b>: an URL ({@link #requireUrl}, no query or fragment). Required for OpenID
 *       Connect (compared exactly with the ID token's {@code iss}); for OAuth it only names the
 *       provider and defaults to the origin of the authorization endpoint.</li>
 *   <li><b>clientId</b>: 1–{@value #CLIENT_MAX} characters. <b>clientSecret</b>: optional (a public
 *       client has none), stored encrypted ({@link SecretCipher}, context
 *       {@code oidc_providers.client_secret_enc:<id>}), never returned by the API
 *       ({@link Provider#hasClientSecret}); {@link #clientSecret} decrypts it for the login flow.</li>
 *   <li><b>scopes</b>: space-separated scope tokens (RFC 6749 characters), duplicates dropped, at
 *       most {@value #SCOPES_MAX} characters. OpenID Connect needs {@code openid}; its default is
 *       {@value #OIDC_DEFAULT_SCOPES}. OAuth defaults to none.</li>
 *   <li><b>enabled</b> (default on) and <b>allowSignup</b> (default off: only existing or linked
 *       accounts can log in through it).</li>
 * </ul>
 *
 * <p><b>Identities stay valid:</b> an identity is {@code (provider, subject)}, so whatever decides
 * what a subject means can't change while a provider has identities ({@code 409
 * provider_has_identities}): the issuer of an OpenID Connect provider, the userinfo URL and subject
 * path of an OAuth provider. Such a provider can't be deleted either (that would delete the
 * identities and could lock accounts without a password out); disable it instead. Create another
 * provider for another issuer.
 *
 * <p>Invalid input is {@code 400 invalid_provider}, an unknown id {@code 404 provider_not_found}.
 * Auditing is left to the caller.
 */
public final class Providers {
    public static final int SLUG_MAX = 32;
    public static final int DISPLAY_NAME_MAX = 64;
    public static final int CLIENT_MAX = 1024;
    public static final int URL_MAX = 2048;
    public static final int SCOPES_MAX = 1024;
    public static final String OIDC_DEFAULT_SCOPES = "openid profile email";

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]*");
    /** RFC 6749 scope-token: %x21 / %x23-5B / %x5D-7E. */
    private static final Pattern SCOPE_TOKEN = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+");

    /** The protocol a provider speaks. */
    public enum Kind {
        OIDC("oidc"), OAUTH2("oauth2");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** The kind for a wire name; {@code 400 invalid_provider} for another. */
        public static Kind of(String wire) {
            for (Kind k : values()) {
                if (k.wire.equals(wire)) {
                    return k;
                }
            }
            throw invalid("kind must be oidc or oauth2");
        }
    }

    /** A stored provider, without its secret. */
    public record Provider(String id, String slug, String displayName, Kind kind, String issuer, String clientId,
                           boolean hasClientSecret, String scopes, boolean enabled, boolean allowSignup,
                           ClaimMapping claimMapping) {
        /** Its settings, e.g. to start an update from. */
        public Spec spec() {
            return new Spec(slug, displayName, kind, issuer, clientId, scopes, enabled, allowSignup, claimMapping);
        }
    }

    /**
     * A provider's settings as given by an admin ({@link #normalize} cleans and checks them).
     *
     * @param issuer  {@code null} for an OAuth provider: the authorization endpoint's origin
     * @param scopes  {@code null}: the kind's default
     */
    public record Spec(String slug, String displayName, Kind kind, String issuer, String clientId, String scopes,
                       boolean enabled, boolean allowSignup, ClaimMapping claimMapping) {
    }

    /** What an update does to the client secret. */
    public sealed interface SecretChange {
        /** Leaves it as it is. */
        SecretChange KEEP = new Keep();

        record Keep() implements SecretChange {
        }

        /** Sets it ({@code value} non-empty) or removes it ({@code null}). */
        record Replace(String value) implements SecretChange {
        }
    }

    private final Database db;
    private final SecretCipher cipher;

    public Providers(Database db, SecretCipher cipher) {
        this.db = Objects.requireNonNull(db);
        this.cipher = Objects.requireNonNull(cipher);
    }

    // ------------------------------------------------------------- reading

    /** Every provider, oldest first (the order of the login buttons). */
    public List<Provider> list() throws SQLException {
        return db.read(c -> query(c, "", null));
    }

    /** The enabled providers, oldest first. */
    public List<Provider> listEnabled() throws SQLException {
        return db.read(c -> query(c, " WHERE enabled = 1", null));
    }

    /** One provider; {@code 404 provider_not_found}. */
    public Provider get(String id) throws SQLException {
        return db.read(c -> find(c, id));
    }

    /** The provider with this slug, enabled or not, or {@code null}. */
    public Provider bySlug(String slug) throws SQLException {
        return db.read(c -> {
            List<Provider> found = query(c, " WHERE slug = ?", slug);
            return found.isEmpty() ? null : found.get(0);
        });
    }

    /** How many identities (linked accounts) a provider has. */
    public long identityCount(String id) throws SQLException {
        return db.read(c -> identities(c, id));
    }

    /** The decrypted client secret, or {@code null} for a public client; {@code 404 provider_not_found}. */
    public String clientSecret(String id) throws SQLException {
        String stored = db.read(c -> {
            find(c, id);
            try (PreparedStatement ps = c.prepareStatement("SELECT client_secret_enc FROM oidc_providers WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        try {
            return cipher.decrypt(stored, context(id));
        } catch (SecretDecryptionException e) {
            throw new IllegalStateException("The client secret of login provider " + id
                    + " can't be decrypted with this server's secret key", e);
        }
    }

    // ------------------------------------------------------------- changing

    /** Creates a provider; {@code clientSecret} {@code null} for a public client. */
    public Provider create(Spec spec, String clientSecret) throws SQLException {
        Spec clean = normalize(spec);
        String secret = secret(clientSecret);
        String id = UuidV7.newId();
        return db.write(c -> {
            requireSlugFree(c, clean.slug(), null);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO oidc_providers (id, slug, display_name, issuer,"
                    + " client_id, client_secret_enc, scopes, enabled, allow_signup, claim_mapping_json, kind)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, clean.slug());
                ps.setString(3, clean.displayName());
                ps.setString(4, clean.issuer());
                ps.setString(5, clean.clientId());
                ps.setString(6, secret == null ? null : cipher.encrypt(secret, context(id)));
                ps.setString(7, clean.scopes());
                ps.setInt(8, clean.enabled() ? 1 : 0);
                ps.setInt(9, clean.allowSignup() ? 1 : 0);
                ps.setString(10, ClaimMapping.toStored(clean.claimMapping()));
                ps.setString(11, clean.kind().wire());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw slugRace(e);
            }
            return find(c, id);
        });
    }

    /**
     * Replaces a provider's settings ({@code kind} can't change: {@code 400 invalid_provider}).
     * {@code 409 provider_has_identities} if the change would give its subjects another meaning.
     */
    public Provider update(String id, Spec spec, SecretChange secretChange) throws SQLException {
        Spec clean = normalize(spec);
        String secret = secretChange instanceof SecretChange.Replace set ? secret(set.value()) : null;
        return db.write(c -> {
            Provider before = find(c, id);
            if (before.kind() != clean.kind()) {
                throw invalid("A provider's kind can't be changed; create a new provider instead");
            }
            if (!sameSubjects(before, clean) && identities(c, id) > 0) {
                throw new HttpError(409, "provider_has_identities", before.kind() == Kind.OIDC
                        ? "Accounts are linked to this provider, so its issuer can't change; add a new provider instead"
                        : "Accounts are linked to this provider, so its userinfo URL and subject can't change;"
                        + " add a new provider instead");
            }
            requireSlugFree(c, clean.slug(), id);
            boolean writeSecret = secretChange instanceof SecretChange.Replace;
            try (PreparedStatement ps = c.prepareStatement("UPDATE oidc_providers SET slug = ?, display_name = ?,"
                    + " issuer = ?, client_id = ?, scopes = ?, enabled = ?, allow_signup = ?, claim_mapping_json = ?"
                    + (writeSecret ? ", client_secret_enc = ?" : "") + " WHERE id = ?")) {
                int i = 1;
                ps.setString(i++, clean.slug());
                ps.setString(i++, clean.displayName());
                ps.setString(i++, clean.issuer());
                ps.setString(i++, clean.clientId());
                ps.setString(i++, clean.scopes());
                ps.setInt(i++, clean.enabled() ? 1 : 0);
                ps.setInt(i++, clean.allowSignup() ? 1 : 0);
                ps.setString(i++, ClaimMapping.toStored(clean.claimMapping()));
                if (writeSecret) {
                    ps.setString(i++, secret == null ? null : cipher.encrypt(secret, context(id)));
                }
                ps.setString(i, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw slugRace(e);
            }
            return find(c, id);
        });
    }

    /** Deletes a provider without identities; {@code 409 provider_has_identities} otherwise. */
    public Provider delete(String id) throws SQLException {
        return db.write(c -> {
            Provider before = find(c, id);
            if (identities(c, id) > 0) {
                throw new HttpError(409, "provider_has_identities",
                        "Accounts are linked to this provider; disable it instead of deleting it");
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM oidc_providers WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return before;
        });
    }

    // ------------------------------------------------------------- validation

    /** Cleans and checks a spec: {@code 400 invalid_provider} with a message for the admin. */
    public static Spec normalize(Spec spec) {
        Kind kind = spec.kind() == null ? Kind.OIDC : spec.kind();
        String slug = spec.slug() == null ? null : spec.slug().trim().toLowerCase(Locale.ROOT);
        if (slug == null || slug.isEmpty()) {
            throw invalid("slug is required");
        }
        if (slug.length() > SLUG_MAX || !SLUG.matcher(slug).matches()) {
            throw invalid("slug must be 1–" + SLUG_MAX + " lowercase letters, digits or -, starting with a letter or digit");
        }
        String displayName = text("displayName", spec.displayName(), DISPLAY_NAME_MAX);
        String clientId = text("clientId", spec.clientId(), CLIENT_MAX);
        ClaimMapping mapping = spec.claimMapping();
        if (kind == Kind.OAUTH2 && (mapping == null || mapping.subject() == null)) {
            throw invalid("An OAuth provider needs claimMapping with authorizationEndpoint, tokenEndpoint,"
                    + " userinfoUrl and subject");
        }
        if (kind == Kind.OIDC && mapping != null && (mapping.authorizationEndpoint() != null
                || mapping.tokenEndpoint() != null || mapping.userinfoUrl() != null || mapping.subject() != null)) {
            throw invalid("Endpoints and subject in claimMapping are only for OAuth providers");
        }
        String issuer = spec.issuer() == null || spec.issuer().isBlank() ? null : spec.issuer().trim();
        if (issuer == null) {
            if (kind == Kind.OIDC) {
                throw invalid("issuer is required");
            }
            issuer = origin(mapping.authorizationEndpoint());
        }
        issuer = requireUrl("issuer", issuer, false);
        String scopes = scopes(spec.scopes(), kind);
        return new Spec(slug, displayName, kind, issuer, clientId, scopes, spec.enabled(), spec.allowSignup(), mapping);
    }

    /**
     * Checks an URL a provider is reached at: absolute {@code https}, or {@code http} only for a
     * loopback host (a provider on the same machine, e.g. for tests); no user info or fragment,
     * and no query unless {@code allowQuery}; at most {@value #URL_MAX} characters.
     *
     * @return the URL, trimmed
     */
    public static String requireUrl(String field, String value, boolean allowQuery) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) {
            throw invalid(field + " is required");
        }
        if (url.length() > URL_MAX) {
            throw invalid(field + " is longer than " + URL_MAX + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid(field + " is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!uri.isAbsolute() || host == null || host.isEmpty() || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || (!allowQuery && uri.getRawQuery() != null)) {
            throw invalid(field + " must be an absolute URL" + (allowQuery ? "" : " without a query")
                    + " and without a fragment");
        }
        if (scheme.equals("http") ? !loopback(host) : !scheme.equals("https")) {
            throw invalid(field + " must use https (http only for a provider on this machine)");
        }
        return url;
    }

    private static boolean loopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) {
            return true;
        }
        // Literal addresses only: no name lookups.
        if (h.matches("[0-9.]+") || h.contains(":")) {
            try {
                return InetAddress.getByName(h).isLoopbackAddress();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static String origin(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static String scopes(String raw, Kind kind) {
        if (raw == null) {
            return kind == Kind.OIDC ? OIDC_DEFAULT_SCOPES : "";
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String t : raw.trim().split("\\s+")) {
            if (t.isEmpty()) {
                continue;
            }
            if (!SCOPE_TOKEN.matcher(t).matches()) {
                throw invalid("scopes must be scope names separated by spaces");
            }
            tokens.add(t);
        }
        if (kind == Kind.OIDC && !tokens.contains("openid")) {
            throw invalid("The scopes of an OpenID Connect provider must include openid");
        }
        String out = String.join(" ", tokens);
        if (out.length() > SCOPES_MAX) {
            throw invalid("scopes is longer than " + SCOPES_MAX + " characters");
        }
        return out;
    }

    private static String text(String field, String value, int max) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) {
            throw invalid(field + " is required");
        }
        if (s.codePointCount(0, s.length()) > max) {
            throw invalid(field + " is longer than " + max + " characters");
        }
        if (s.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field + " must not contain control characters");
        }
        return s;
    }

    /** A client secret to store: {@code null} stays {@code null}; empty or too long is refused. */
    private static String secret(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            throw invalid("clientSecret must not be empty (null removes it)");
        }
        if (value.length() > CLIENT_MAX) {
            throw invalid("clientSecret is longer than " + CLIENT_MAX + " characters");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("clientSecret must not contain control characters");
        }
        return value;
    }

    /** Whether {@code after} keeps the meaning of the subjects {@code before} produced. */
    private static boolean sameSubjects(Provider before, Spec after) {
        if (before.kind() == Kind.OIDC) {
            return before.issuer().equals(after.issuer());
        }
        ClaimMapping a = before.claimMapping();
        ClaimMapping b = after.claimMapping();
        return a != null && b != null && Objects.equals(a.userinfoUrl(), b.userinfoUrl())
                && Objects.equals(a.subject(), b.subject());
    }

    static HttpError invalid(String message) {
        return new HttpError(400, "invalid_provider", message);
    }

    /** The {@link SecretCipher} context of a provider's client secret. */
    static String context(String id) {
        return "oidc_providers.client_secret_enc:" + id;
    }

    // ------------------------------------------------------------- SQL

    private static final String COLUMNS = "id, slug, display_name, kind, issuer, client_id, client_secret_enc,"
            + " scopes, enabled, allow_signup, claim_mapping_json";

    private static List<Provider> query(Connection c, String where, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM oidc_providers" + where
                + " ORDER BY id")) {
            if (param != null) {
                ps.setString(1, param);
            }
            List<Provider> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Provider(rs.getString(1), rs.getString(2), rs.getString(3), Kind.of(rs.getString(4)),
                            rs.getString(5), rs.getString(6), rs.getString(7) != null, rs.getString(8),
                            rs.getInt(9) != 0, rs.getInt(10) != 0, ClaimMapping.fromStored(rs.getString(11))));
                }
            }
            return out;
        }
    }

    private static Provider find(Connection c, String id) throws SQLException {
        List<Provider> found = query(c, " WHERE id = ?", id);
        if (found.isEmpty()) {
            throw new HttpError(404, "provider_not_found", "No such login provider");
        }
        return found.get(0);
    }

    private static long identities(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM user_identities WHERE provider_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void requireSlugFree(Connection c, String slug, String exceptId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM oidc_providers WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && !rs.getString(1).equals(exceptId)) {
                    throw slugTaken();
                }
            }
        }
    }

    private static SQLException slugRace(SQLException e) {
        if (isUniqueViolation(e)) {
            throw slugTaken();
        }
        return e;
    }

    private static HttpError slugTaken() {
        return new HttpError(409, "provider_slug_taken", "Another login provider has this slug");
    }

    private static boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                if ("23505".equals(sql.getSQLState())) {
                    return true;
                }
                String m = sql.getMessage();
                if (m != null && m.contains("UNIQUE constraint failed")) {
                    return true;
                }
            }
        }
        return false;
    }
}
