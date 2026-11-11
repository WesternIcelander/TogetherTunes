package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OidcDiscovery} against a local fake IdP ({@code com.sun.net.httpserver}): the discovery
 * document's checks, caching of both documents, the refetch on an unknown {@code kid} (rate-limited),
 * stale copies on failures, and the key source used by Nimbus's JWT processor.
 */
class OidcDiscoveryTest {
    private HttpServer server;
    private String issuer;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** The discovery document to serve; {@code null}: build the default one. */
    private final AtomicReference<String> discovery = new AtomicReference<>();
    private final AtomicReference<String> jwks = new AtomicReference<>();
    /** Status to answer every request with instead (0: normal). */
    private final AtomicInteger failWith = new AtomicInteger();
    private OidcDiscovery oidc;
    private RSAKey keyA;
    private RSAKey keyB;

    @BeforeEach
    void setUp() throws IOException, JOSEException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            try (exchange) {
                if (failWith.get() != 0) {
                    exchange.sendResponseHeaders(failWith.get(), -1);
                    return;
                }
                String body = switch (path) {
                    case "/idp/.well-known/openid-configuration" ->
                            discovery.get() != null ? discovery.get() : defaultDiscovery();
                    case "/idp/jwks" -> jwks.get();
                    case "/idp/redirect/.well-known/openid-configuration" -> {
                        exchange.getResponseHeaders().add("Location", issuer + "/.well-known/openid-configuration");
                        exchange.sendResponseHeaders(302, -1);
                        yield null;
                    }
                    case "/idp/big/.well-known/openid-configuration" ->
                            "{\"pad\":\"" + "x".repeat(OidcDiscovery.MAX_BODY_BYTES) + "\"}";
                    default -> {
                        exchange.sendResponseHeaders(404, -1);
                        yield null;
                    }
                };
                if (body != null) {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        });
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/idp";
        keyA = new RSAKeyGenerator(2048).keyID("a").keyUse(KeyUse.SIGNATURE).generate();
        keyB = new RSAKeyGenerator(2048).keyID("b").keyUse(KeyUse.SIGNATURE).generate();
        jwks.set(new JWKSet(keyA).toString(false));
        oidc = new OidcDiscovery(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(5), now::get);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String defaultDiscovery() {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"userinfo_endpoint\":\"" + issuer + "/userinfo\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\","
                + "\"response_types_supported\":[\"code\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\",\"ES256\"],"
                + "\"code_challenge_methods_supported\":[\"plain\",\"S256\"]}";
    }

    private int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    private int discoveryHits() {
        return hits("/idp/.well-known/openid-configuration");
    }

    private int jwksHits() {
        return hits("/idp/jwks");
    }

    private static JWKSelector kid(String kid) {
        return new JWKSelector(new JWKMatcher.Builder().keyID(kid).build());
    }

    private void advance(long ms) {
        now.addAndGet(ms);
    }

    @Test
    void discoveryDocumentIsParsedAndCached() throws Exception {
        OidcDiscovery.Metadata m = oidc.metadata(issuer);
        assertEquals(issuer, m.issuer());
        assertEquals(issuer + "/authorize", m.authorizationEndpoint());
        assertEquals(issuer + "/token", m.tokenEndpoint());
        assertEquals(issuer + "/userinfo", m.userinfoEndpoint());
        assertEquals(issuer + "/jwks", m.jwksUri());
        assertEquals(List.of("RS256", "ES256"), m.idTokenSigningAlgs());
        assertEquals(List.of("plain", "S256"), m.codeChallengeMethods());
        assertTrue(m.supportsS256());
        assertEquals(1, discoveryHits());

        advance(OidcDiscovery.METADATA_TTL_MS - 1);
        assertSame(m, oidc.metadata(issuer));
        assertEquals(1, discoveryHits());

        advance(1);
        OidcDiscovery.Metadata again = oidc.metadata(issuer);
        assertEquals(m, again);
        assertEquals(2, discoveryHits());
    }

    @Test
    void optionalFieldsMayBeMissing() throws Exception {
        discovery.set("{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/a\","
                + "\"token_endpoint\":\"" + issuer + "/t\",\"jwks_uri\":\"" + issuer + "/jwks\"}");
        OidcDiscovery.Metadata m = oidc.metadata(issuer);
        assertNull(m.userinfoEndpoint());
        assertEquals(List.of(), m.idTokenSigningAlgs());
        assertTrue(m.supportsS256(), "no methods listed: assume S256");
        discovery.set(defaultDiscovery().replace("[\"plain\",\"S256\"]", "[\"plain\"]"));
        assertFalse(newDiscovery().metadata(issuer).supportsS256());
    }

    @Test
    void issuerWithTrailingSlashUsesOneSlash() {
        assertEquals("https://idp.example/.well-known/openid-configuration",
                OidcDiscovery.discoveryUrl("https://idp.example/").toString());
        assertEquals("https://idp.example/realms/x/.well-known/openid-configuration",
                OidcDiscovery.discoveryUrl("https://idp.example/realms/x").toString());
    }

    @Test
    void invalidDiscoveryDocumentsAreRejected() {
        String good = defaultDiscovery();
        Map<String, String> cases = Map.of(
                good.replace("\"issuer\":\"" + issuer + "\"", "\"issuer\":\"" + issuer + "/\""), "doesn't match",
                good.replace("\"issuer\":\"" + issuer + "\",", ""), "issuer is missing",
                good.replace("\"jwks_uri\":\"" + issuer + "/jwks\",", ""), "no jwks_uri",
                good.replace("\"token_endpoint\":\"" + issuer + "/token\",", ""), "no token_endpoint",
                good.replace(issuer + "/authorize", "http://idp.example/authorize"), "authorization_endpoint must use https",
                good.replace(issuer + "/jwks", "https://u:p@idp.example/jwks"), "jwks_uri must be an absolute URL",
                good.replace("\"issuer\":\"" + issuer + "\"", "\"issuer\":5"), "issuer is not a string",
                "[1,2]", "not a JSON object",
                "{nope", "not valid JSON");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            discovery.set(c.getKey());
            OidcDiscovery fresh = newDiscovery();
            OidcDiscovery.DiscoveryException ex =
                    assertThrows(OidcDiscovery.DiscoveryException.class, () -> fresh.metadata(issuer));
            assertTrue(ex.getMessage().contains(c.getValue()), ex.getMessage() + " should contain " + c.getValue());
        }
    }

    @Test
    void httpFailuresRedirectsAndHugeBodiesAreErrors() {
        failWith.set(500);
        OidcDiscovery.DiscoveryException ex =
                assertThrows(OidcDiscovery.DiscoveryException.class, () -> oidc.metadata(issuer));
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
        failWith.set(0);
        hits.clear();

        ex = assertThrows(OidcDiscovery.DiscoveryException.class, () -> oidc.metadata(issuer + "/redirect"));
        assertTrue(ex.getMessage().contains("HTTP 302"), ex.getMessage());
        assertEquals(0, discoveryHits(), "the redirect isn't followed");

        ex = assertThrows(OidcDiscovery.DiscoveryException.class, () -> oidc.metadata(issuer + "/big"));
        assertTrue(ex.getMessage().contains("larger than"), ex.getMessage());

        ex = assertThrows(OidcDiscovery.DiscoveryException.class, () -> oidc.metadata(issuer + "/missing"));
        assertTrue(ex.getMessage().contains("HTTP 404"), ex.getMessage());

        server.stop(0);
        ex = assertThrows(OidcDiscovery.DiscoveryException.class, () -> newDiscovery().metadata(issuer));
        assertTrue(ex.getMessage().contains("can't fetch the discovery document"), ex.getMessage());
    }

    @Test
    void clientThatFollowsRedirectsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new OidcDiscovery(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                Duration.ofSeconds(5), now::get));
    }

    @Test
    void jwksKeepsOnlyPublicSigningKeys() throws Exception {
        ECKey ec = new ECKeyGenerator(Curve.P_256).keyID("ec").generate();
        RSAKey enc = new RSAKeyGenerator(2048).keyID("enc").keyUse(KeyUse.ENCRYPTION).generate();
        OctetSequenceKey hmac = new OctetSequenceKeyGenerator(256).keyID("hmac").generate();
        // Private parts published by mistake must not be kept.
        jwks.set(new JWKSet(List.of(keyA, ec, enc, hmac)).toString(false));
        JWKSet set = oidc.jwks(issuer);
        List<String> kids = new ArrayList<>();
        for (JWK k : set.getKeys()) {
            kids.add(k.getKeyID());
            assertFalse(k.isPrivate(), k.getKeyID() + " is public only");
        }
        assertEquals(List.of("a", "ec"), kids);
        assertEquals(keyA.toPublicJWK(), set.getKeyByKeyId("a"));
    }

    @Test
    void invalidJwksIsAnError() {
        jwks.set("{\"keys\":5}");
        OidcDiscovery.DiscoveryException ex =
                assertThrows(OidcDiscovery.DiscoveryException.class, () -> oidc.jwks(issuer));
        assertTrue(ex.getMessage().contains("JWK set is not valid"), ex.getMessage());
    }

    @Test
    void jwksIsCachedAndRefreshedAfterItsTtl() throws Exception {
        JWKSet first = oidc.jwks(issuer);
        assertEquals(1, jwksHits());
        assertEquals(1, oidc.select(issuer, kid("a")).size());
        advance(OidcDiscovery.JWKS_TTL_MS - 1);
        assertSame(first, oidc.jwks(issuer));
        assertEquals(1, jwksHits());

        jwks.set(new JWKSet(keyB).toString(false));
        advance(1);
        JWKSet second = oidc.jwks(issuer);
        assertEquals(2, jwksHits());
        assertNull(second.getKeyByKeyId("a"), "keys the provider dropped go away at the refresh");
        assertNotNull(second.getKeyByKeyId("b"));
        assertEquals(1, discoveryHits(), "the discovery document has its own, longer TTL");
    }

    @Test
    void unknownKidRefetchesTheJwks() throws Exception {
        assertEquals(1, oidc.select(issuer, kid("a")).size());
        assertEquals(1, jwksHits());

        // The provider rotates in key b; a token signed with it arrives a little later.
        jwks.set(new JWKSet(List.of(keyA, keyB)).toString(false));
        advance(OidcDiscovery.REFETCH_MIN_MS);
        List<JWK> found = oidc.select(issuer, kid("b"));
        assertEquals(List.of(keyB.toPublicJWK()), found);
        assertEquals(2, jwksHits());

        // Now cached: no further fetch for b, nor for a.
        assertEquals(1, oidc.select(issuer, kid("b")).size());
        assertEquals(1, oidc.select(issuer, kid("a")).size());
        assertEquals(2, jwksHits());
    }

    @Test
    void unknownKidRefetchIsRateLimited() throws Exception {
        oidc.jwks(issuer);
        assertEquals(1, jwksHits());
        // A kid nobody has: one refetch, then none until the interval has passed.
        advance(OidcDiscovery.REFETCH_MIN_MS);
        assertTrue(oidc.select(issuer, kid("nope")).isEmpty());
        assertEquals(2, jwksHits());
        for (int i = 0; i < 5; i++) {
            advance(OidcDiscovery.REFETCH_MIN_MS / 10);
            assertTrue(oidc.select(issuer, kid("nope-" + i)).isEmpty());
        }
        assertEquals(2, jwksHits());
        advance(OidcDiscovery.REFETCH_MIN_MS);
        assertTrue(oidc.select(issuer, kid("nope")).isEmpty());
        assertEquals(3, jwksHits());
    }

    @Test
    void knownKidWithoutMatchDoesNotRefetch() throws Exception {
        oidc.jwks(issuer);
        advance(OidcDiscovery.REFETCH_MIN_MS * 2);
        // kid a exists, but it's an RSA key and the selector wants EC: refetching wouldn't help.
        JWKSelector ecWithKidA = new JWKSelector(new JWKMatcher.Builder()
                .keyID("a").keyType(com.nimbusds.jose.jwk.KeyType.EC).build());
        assertTrue(oidc.select(issuer, ecWithKidA).isEmpty());
        assertEquals(1, jwksHits());
    }

    @Test
    void tokenWithoutKidRefetchesWhenNothingMatches() throws Exception {
        oidc.jwks(issuer);
        ECKey ec = new ECKeyGenerator(Curve.P_256).keyID("ec").generate();
        jwks.set(new JWKSet(List.of(keyA, ec)).toString(false));
        advance(OidcDiscovery.REFETCH_MIN_MS);
        JWKSelector anyEc = new JWKSelector(new JWKMatcher.Builder()
                .keyType(com.nimbusds.jose.jwk.KeyType.EC).build());
        assertEquals(1, oidc.select(issuer, anyEc).size());
        assertEquals(2, jwksHits());
    }

    @Test
    void staleCopiesAreUsedWhileTheProviderIsDown() throws Exception {
        OidcDiscovery.Metadata m = oidc.metadata(issuer);
        JWKSet set = oidc.jwks(issuer);
        failWith.set(503);
        advance(OidcDiscovery.METADATA_TTL_MS);
        assertSame(m, oidc.metadata(issuer));
        assertSame(set, oidc.jwks(issuer));
        assertEquals(2, discoveryHits());
        assertEquals(2, jwksHits());
        // No new attempt until the retry interval has passed.
        advance(OidcDiscovery.REFETCH_MIN_MS - 1);
        assertSame(m, oidc.metadata(issuer));
        assertSame(set, oidc.jwks(issuer));
        assertEquals(2, discoveryHits());
        assertEquals(2, jwksHits());
        // The provider is back.
        failWith.set(0);
        jwks.set(new JWKSet(keyB).toString(false));
        advance(1);
        assertNotNull(oidc.jwks(issuer).getKeyByKeyId("b"));
        assertEquals(3, discoveryHits());
        assertEquals(3, jwksHits());
    }

    @Test
    void changedJwksUriIsFetchedAtOnce() throws Exception {
        oidc.jwks(issuer);
        assertEquals(1, jwksHits());
        discovery.set(defaultDiscovery().replace(issuer + "/jwks", issuer + "/jwks?v=2"));
        advance(OidcDiscovery.METADATA_TTL_MS);
        oidc.jwks(issuer);
        assertEquals(2, jwksHits());
    }

    @Test
    void concurrentCallersShareOneFetch() throws Exception {
        int n = 16;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JWKSet>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return oidc.jwks(issuer);
                }));
            }
            start.countDown();
            for (Future<JWKSet> f : results) {
                assertNotNull(f.get().getKeyByKeyId("a"));
            }
        }
        assertEquals(1, discoveryHits());
        assertEquals(1, jwksHits());
    }

    @Test
    void nimbusProcessorVerifiesTokensSignedWithARotatedKey() throws Exception {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), oidc.keySource(issuer)));
        processor.setJWTClaimsSetVerifier((claims, ctx) -> { });

        assertEquals("u1", processor.process(sign(keyA, "u1"), null).getSubject());
        assertEquals(1, jwksHits());

        jwks.set(new JWKSet(List.of(keyA, keyB)).toString(false));
        advance(OidcDiscovery.REFETCH_MIN_MS);
        assertEquals("u2", processor.process(sign(keyB, "u2"), null).getSubject());
        assertEquals(2, jwksHits());

        ECKey ec = new ECKeyGenerator(Curve.P_256).keyID("ec").generate();
        jwks.set(new JWKSet(List.of(keyA, keyB, ec)).toString(false));
        advance(OidcDiscovery.REFETCH_MIN_MS);
        SignedJWT ecJwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec").build(),
                new JWTClaimsSet.Builder().subject("u3").build());
        ecJwt.sign(new ECDSASigner(ec));
        assertEquals("u3", processor.process(ecJwt, null).getSubject());

        // A key the provider never published: refused.
        RSAKey rogue = new RSAKeyGenerator(2048).keyID("rogue").generate();
        advance(OidcDiscovery.REFETCH_MIN_MS);
        assertThrows(BadJOSEException.class, () -> processor.process(sign(rogue, "u4"), null));
        // Signed with a's private key but claiming kid b: the signature doesn't verify.
        RSAKey forged = new RSAKey.Builder(keyA).keyID("b").build();
        assertThrows(BadJOSEException.class, () -> processor.process(sign(forged, "u5"), null));
    }

    @Test
    void keySourceReportsAnUnreachableProvider() {
        failWith.set(500);
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, oidc.keySource(issuer)));
        Exception ex = assertThrows(Exception.class, () -> processor.process(sign(keyA, "u"), null));
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }

    private OidcDiscovery newDiscovery() {
        return new OidcDiscovery(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(5), now::get);
    }

    private static SignedJWT sign(RSAKey key, String subject) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject(subject).build());
        jwt.sign(new RSASSASigner(key));
        return jwt;
    }
}
