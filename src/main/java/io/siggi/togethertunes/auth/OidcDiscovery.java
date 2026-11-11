package io.siggi.togethertunes.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.siggi.togethertunes.http.HttpError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The OIDC relying party's view of its providers' published configuration
 * ({@code docs/auth.md#oidc}, step 1): the discovery document
 * {@code {issuer}/.well-known/openid-configuration} and the JWK set it points to, both fetched with
 * the JDK HTTP client and cached per issuer.
 *
 * <ul>
 *   <li><b>Discovery</b> is checked as OpenID Connect Discovery 1.0 §4.3 asks: its {@code issuer}
 *       must equal the configured issuer exactly. The authorization, token and JWKS endpoints are
 *       required; every endpoint must be {@code https} ({@code http} only on a loopback host, as
 *       for the provider settings, {@link Providers#requireUrl}). Kept for
 *       {@value #METADATA_TTL_HOURS} h.</li>
 *   <li><b>JWKS</b>: only public signing keys are kept (private parts dropped; {@code use} absent
 *       or {@code sig}; no symmetric {@code oct} keys). Kept for {@value #JWKS_TTL_MINUTES} min, and
 *       <b>refetched when a token names a {@code kid} the cached set doesn't have</b> (or names none
 *       and nothing matches), at most once per {@value #REFETCH_MIN_SECONDS} s per issuer, so a key
 *       rotation is picked up at the next login without letting tokens trigger a fetch each.</li>
 *   <li><b>Failures:</b> when a refresh fails while an older copy is cached, the old copy keeps
 *       being used (logged) and the next attempt waits {@value #REFETCH_MIN_SECONDS} s; without a
 *       cached copy the failure is thrown ({@link DiscoveryException}) on every call.</li>
 *   <li>Redirects aren't followed (an {@code https} provider can't send us to plain HTTP), each
 *       request has a timeout, and a response may be at most {@value #MAX_BODY_BYTES} bytes.</li>
 * </ul>
 *
 * Fetches for one issuer are serialised (concurrent logins share one fetch); different issuers
 * don't wait for each other. The cache is keyed by issuer, so changing a provider's issuer simply
 * uses another entry. Plain OAuth2 providers have no discovery and don't use this.
 */
public final class OidcDiscovery {
    private static final System.Logger LOG = System.getLogger(OidcDiscovery.class.getName());

    public static final long METADATA_TTL_HOURS = 24;
    public static final long JWKS_TTL_MINUTES = 60;
    public static final long REFETCH_MIN_SECONDS = 10;
    public static final int MAX_BODY_BYTES = 512 * 1024;

    static final long METADATA_TTL_MS = METADATA_TTL_HOURS * 3_600_000L;
    static final long JWKS_TTL_MS = JWKS_TTL_MINUTES * 60_000L;
    static final long REFETCH_MIN_MS = REFETCH_MIN_SECONDS * 1000L;

    /** What a provider's discovery document says (the parts the relying party uses). */
    public record Metadata(String issuer, String authorizationEndpoint, String tokenEndpoint,
                           String userinfoEndpoint, String jwksUri, List<String> idTokenSigningAlgs,
                           List<String> codeChallengeMethods) {
        public Metadata {
            idTokenSigningAlgs = List.copyOf(idTokenSigningAlgs);
            codeChallengeMethods = List.copyOf(codeChallengeMethods);
        }

        /**
         * Whether PKCE S256 can be used: the provider lists it, or lists no methods at all (the
         * field is optional and many providers that support S256 leave it out).
         */
        public boolean supportsS256() {
            return codeChallengeMethods.isEmpty() || codeChallengeMethods.contains("S256");
        }
    }

    /** The configuration couldn't be fetched or isn't valid; the message says why (no secrets in it). */
    public static final class DiscoveryException extends IOException {
        public DiscoveryException(String message) {
            super(message);
        }

        public DiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient client;
    private final Duration timeout;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public OidcDiscovery() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(15), System::currentTimeMillis);
    }

    /**
     * With {@code client} (must not follow redirects), a whole-request {@code timeout} and a clock
     * (epoch ms) for the cache.
     */
    public OidcDiscovery(HttpClient client, Duration timeout, LongSupplier clock) {
        this.client = Objects.requireNonNull(client);
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("the OIDC client must not follow redirects");
        }
        this.timeout = Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
    }

    /** {@code {issuer}/.well-known/openid-configuration} (a trailing {@code /} of the issuer dropped). */
    public static URI discoveryUrl(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        return URI.create(base + "/.well-known/openid-configuration");
    }

    /** The issuer's discovery document, from the cache while it's fresh. */
    public Metadata metadata(String issuer) throws DiscoveryException, InterruptedException {
        Entry e = entry(issuer);
        e.lock.lock();
        try {
            return metadataLocked(issuer, e);
        } finally {
            e.lock.unlock();
        }
    }

    /** The issuer's public signing keys, from the cache while they're fresh. */
    public JWKSet jwks(String issuer) throws DiscoveryException, InterruptedException {
        Entry e = entry(issuer);
        e.lock.lock();
        try {
            return jwksLocked(issuer, e, false);
        } finally {
            e.lock.unlock();
        }
    }

    /**
     * The issuer's keys {@code selector} matches. When none does and the token's {@code kid} isn't
     * in the cached set (or it has none), the set is fetched again first (rate-limited), so keys
     * the provider has rotated in are found.
     */
    public List<JWK> select(String issuer, JWKSelector selector) throws DiscoveryException, InterruptedException {
        Entry e = entry(issuer);
        e.lock.lock();
        try {
            JWKSet set = jwksLocked(issuer, e, false);
            List<JWK> keys = selector.select(set);
            if (keys.isEmpty() && unknownKid(set, selector)) {
                keys = selector.select(jwksLocked(issuer, e, true));
            }
            return keys;
        } finally {
            e.lock.unlock();
        }
    }

    /**
     * {@link #select} as a Nimbus key source, for the ID-token processor
     * ({@code JWSVerificationKeySelector}).
     */
    public JWKSource<SecurityContext> keySource(String issuer) {
        Objects.requireNonNull(issuer);
        return (selector, context) -> {
            try {
                return select(issuer, selector);
            } catch (DiscoveryException ex) {
                throw new KeySourceException(ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new KeySourceException("interrupted while fetching the provider's keys", ex);
            }
        };
    }

    /** Drops what's cached for {@code issuer} (e.g. after a provider was deleted). */
    public void forget(String issuer) {
        entries.remove(issuer);
    }

    private Entry entry(String issuer) {
        Objects.requireNonNull(issuer);
        return entries.computeIfAbsent(issuer, i -> new Entry());
    }

    private static boolean unknownKid(JWKSet set, JWKSelector selector) {
        Set<String> kids = selector.getMatcher().getKeyIDs();
        if (kids == null || kids.isEmpty()) {
            return true;
        }
        for (String kid : kids) {
            if (kid != null && set.getKeyByKeyId(kid) == null) {
                return true;
            }
        }
        return false;
    }

    private Metadata metadataLocked(String issuer, Entry e) throws DiscoveryException, InterruptedException {
        long now = clock.getAsLong();
        if (e.metadata != null && now - e.metadataFetchedAt < METADATA_TTL_MS) {
            return e.metadata;
        }
        if (e.metadata != null && now < e.metadataRetryAt) {
            return e.metadata;
        }
        try {
            e.metadata = parseMetadata(issuer, fetch(discoveryUrl(issuer), "discovery document"));
            e.metadataFetchedAt = now;
            return e.metadata;
        } catch (DiscoveryException ex) {
            if (e.metadata == null) {
                throw ex;
            }
            e.metadataRetryAt = now + REFETCH_MIN_MS;
            LOG.log(System.Logger.Level.WARNING, "Can't refresh the OIDC discovery document of " + issuer
                    + ", using the cached one: " + ex.getMessage());
            return e.metadata;
        }
    }

    private JWKSet jwksLocked(String issuer, Entry e, boolean unknownKid) throws DiscoveryException, InterruptedException {
        String uri = metadataLocked(issuer, e).jwksUri();
        long now = clock.getAsLong();
        boolean cached = e.jwks != null && uri.equals(e.jwksUri);
        if (cached) {
            boolean fresh = now - e.jwksFetchedAt < JWKS_TTL_MS;
            boolean waiting = now < e.jwksRetryAt;
            if (unknownKid ? now - e.jwksAttemptAt < REFETCH_MIN_MS || waiting : fresh || waiting) {
                return e.jwks;
            }
        }
        e.jwksAttemptAt = now;
        try {
            JWKSet set = parseJwks(fetch(URI.create(uri), "JWK set"));
            e.jwks = set;
            e.jwksUri = uri;
            e.jwksFetchedAt = now;
            return set;
        } catch (DiscoveryException ex) {
            if (!cached) {
                throw ex;
            }
            e.jwksRetryAt = now + REFETCH_MIN_MS;
            LOG.log(System.Logger.Level.WARNING, "Can't refresh the OIDC keys of " + issuer
                    + ", using the cached ones: " + ex.getMessage());
            return e.jwks;
        }
    }

    private String fetch(URI uri, String what) throws DiscoveryException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException ex) {
            throw new DiscoveryException("can't fetch the " + what + " from " + uri.getHost() + ": " + ex, ex);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new DiscoveryException("HTTP " + response.statusCode() + " for the " + what
                        + " from " + uri.getHost());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                if (out.size() + n > MAX_BODY_BYTES) {
                    throw new DiscoveryException("the " + what + " from " + uri.getHost() + " is larger than "
                            + MAX_BODY_BYTES + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DiscoveryException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DiscoveryException("can't read the " + what + " from " + uri.getHost() + ": " + ex, ex);
        }
    }

    static Metadata parseMetadata(String issuer, String body) throws DiscoveryException {
        JsonObject doc;
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) {
                throw new DiscoveryException("the discovery document is not a JSON object");
            }
            doc = el.getAsJsonObject();
        } catch (JsonParseException ex) {
            throw new DiscoveryException("the discovery document is not valid JSON");
        }
        String docIssuer = string(doc, "issuer");
        if (!issuer.equals(docIssuer)) {
            throw new DiscoveryException("the discovery document's issuer " + (docIssuer == null ? "is missing"
                    : "\"" + docIssuer + "\" doesn't match the configured \"" + issuer + "\""));
        }
        String userinfo = string(doc, "userinfo_endpoint");
        return new Metadata(issuer,
                endpoint(doc, "authorization_endpoint"),
                endpoint(doc, "token_endpoint"),
                userinfo == null ? null : endpoint(doc, "userinfo_endpoint"),
                endpoint(doc, "jwks_uri"),
                strings(doc, "id_token_signing_alg_values_supported"),
                strings(doc, "code_challenge_methods_supported"));
    }

    static JWKSet parseJwks(String body) throws DiscoveryException {
        JWKSet set;
        try {
            set = JWKSet.parse(body);
        } catch (ParseException ex) {
            throw new DiscoveryException("the JWK set is not valid: " + ex.getMessage());
        }
        List<JWK> keys = new ArrayList<>();
        for (JWK key : set.getKeys()) {
            if (KeyType.OCT.equals(key.getKeyType())) {
                continue;
            }
            if (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) {
                continue;
            }
            JWK pub = key.toPublicJWK();
            if (pub != null) {
                keys.add(pub);
            }
        }
        return new JWKSet(keys);
    }

    private static String string(JsonObject doc, String name) throws DiscoveryException {
        JsonElement el = doc.get(name);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new DiscoveryException("the discovery document's " + name + " is not a string");
        }
        return el.getAsString();
    }

    private static String endpoint(JsonObject doc, String name) throws DiscoveryException {
        String value = string(doc, name);
        if (value == null) {
            throw new DiscoveryException("the discovery document has no " + name);
        }
        try {
            return Providers.requireUrl(name, value, true);
        } catch (HttpError ex) {
            throw new DiscoveryException("the discovery document's " + ex.getMessage());
        }
    }

    private static List<String> strings(JsonObject doc, String name) throws DiscoveryException {
        JsonElement el = doc.get(name);
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        if (!el.isJsonArray()) {
            throw new DiscoveryException("the discovery document's " + name + " is not an array");
        }
        List<String> out = new ArrayList<>();
        for (JsonElement v : (JsonArray) el) {
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                out.add(v.getAsString());
            }
        }
        return out;
    }

    /** What's cached for one issuer; every field is guarded by {@link #lock}. */
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        Metadata metadata;
        long metadataFetchedAt;
        long metadataRetryAt;
        JWKSet jwks;
        String jwksUri;
        long jwksFetchedAt;
        long jwksAttemptAt;
        long jwksRetryAt;
    }
}
