package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link IdTokenValidator} against a local fake IdP ({@code com.sun.net.httpserver}: discovery and
 * JWKS): the allowed algorithms (RS256/ES256; {@code none}, HS256 and others refused), the signature
 * against the JWKS (also after a rotation: unknown {@code kid}), {@code iss}, {@code aud}/{@code azp},
 * {@code exp}, {@code iat} ±60 s, {@code nbf}, {@code nonce} and {@code sub}.
 */
class IdTokenValidatorTest {
    private static final String CLIENT = "client-1";
    private static final String NONCE = "the-nonce";

    private HttpServer server;
    private String issuer;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final AtomicReference<String> jwks = new AtomicReference<>();
    private final AtomicInteger jwksHits = new AtomicInteger();
    private final AtomicInteger failWith = new AtomicInteger();
    private IdTokenValidator validator;
    private RSAKey rsa;
    private ECKey ec;

    @BeforeEach
    void setUp() throws IOException, JOSEException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (failWith.get() != 0) {
                    exchange.sendResponseHeaders(failWith.get(), -1);
                    return;
                }
                String body = switch (exchange.getRequestURI().getPath()) {
                    case "/idp/.well-known/openid-configuration" -> "{\"issuer\":\"" + issuer + "\","
                            + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                            + "\"token_endpoint\":\"" + issuer + "/token\","
                            + "\"jwks_uri\":\"" + issuer + "/jwks\"}";
                    case "/idp/jwks" -> {
                        jwksHits.incrementAndGet();
                        yield jwks.get();
                    }
                    default -> null;
                };
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/idp";
        rsa = new RSAKeyGenerator(2048).keyID("rsa").keyUse(KeyUse.SIGNATURE).generate();
        ec = new ECKeyGenerator(Curve.P_256).keyID("ec").keyUse(KeyUse.SIGNATURE).generate();
        jwks.set(new JWKSet(List.of(rsa, ec)).toString(false));
        OidcDiscovery discovery = new OidcDiscovery(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(5), now::get);
        validator = new IdTokenValidator(discovery, now::get);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Valid claims for the provider, optionally changed. */
    private JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> change) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-1")
                .audience(CLIENT)
                .issueTime(new Date(now.get()))
                .expirationTime(new Date(now.get() + 300_000))
                .claim("nonce", NONCE)
                .claim("preferred_username", "alice");
        change.accept(b);
        return b.build();
    }

    private static String sign(JWSAlgorithm alg, String kid, JWSSigner signer, JWTClaimsSet claims)
            throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(kid).build(), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private String rs256(JWTClaimsSet claims) throws JOSEException {
        return sign(JWSAlgorithm.RS256, rsa.getKeyID(), new RSASSASigner(rsa), claims);
    }

    private String rs256(Consumer<JWTClaimsSet.Builder> change) throws JOSEException {
        return rs256(claims(change));
    }

    private IdTokenValidator.IdToken validate(String token) throws OidcLogin.LoginException {
        return validator.validate(token, issuer, CLIENT, NONCE);
    }

    private void assertInvalid(String token, String messagePart) {
        OidcLogin.LoginException e = assertThrows(OidcLogin.LoginException.class, () -> validate(token));
        assertEquals("id_token_invalid", e.code(), e.getMessage());
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        if (token != null && !token.isEmpty()) {
            assertFalse(e.getMessage().contains(token), "the token is never in the message");
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void validRs256AndEs256Tokens() throws Exception {
        IdTokenValidator.IdToken t = validate(rs256(b -> { }));
        assertEquals("user-1", t.subject());
        assertEquals("alice", t.claims().getStringClaim("preferred_username"));
        assertFalse(t.toString().contains("user-1"));

        String es = sign(JWSAlgorithm.ES256, ec.getKeyID(), new ECDSASigner(ec), claims(b -> b.subject("user-2")));
        assertEquals("user-2", validate(es).subject());
        assertEquals(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), validator.algorithms());
    }

    @Test
    void onlyAllowedAlgorithms() throws Exception {
        // alg none (unsecured)
        assertInvalid(new PlainJWT(claims(b -> { })).serialize(), "isn't signed");
        // HS256 with the client ID as the "secret" (or any key): never accepted
        byte[] secret = "a-shared-secret-that-is-long-enough!".getBytes(StandardCharsets.UTF_8);
        assertInvalid(sign(JWSAlgorithm.HS256, null, new MACSigner(secret), claims(b -> { })), "HS256");
        // RS384 / RS512 / PS256 with a published key: not in the default set
        assertInvalid(sign(JWSAlgorithm.RS512, rsa.getKeyID(), new RSASSASigner(rsa), claims(b -> { })), "RS512");
        assertInvalid(sign(JWSAlgorithm.PS256, rsa.getKeyID(), new RSASSASigner(rsa), claims(b -> { })), "PS256");
        // a header claiming RS256 over a body signed otherwise doesn't verify
        String real = rs256(b -> { });
        String[] parts = real.split("\\.");
        String swapped = parts[0] + "." + Base64URL.encode(claims(b -> b.subject("admin")).toString()) + "." + parts[2];
        assertInvalid(swapped, "signature");
        // garbage
        assertInvalid("not.a.jwt", "isn't a JWT");
        assertInvalid("", "no ID token");
        assertInvalid(null, "no ID token");

        assertThrows(IllegalArgumentException.class, () -> new IdTokenValidator(
                new OidcDiscovery(), now::get, Set.of(JWSAlgorithm.HS256)));
        assertThrows(IllegalArgumentException.class, () -> new IdTokenValidator(
                new OidcDiscovery(), now::get, Set.of()));
        // widened explicitly: RS512 works then
        IdTokenValidator wide = new IdTokenValidator(new OidcDiscovery(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(5), now::get), now::get, Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS512));
        assertEquals("user-1", wide.validate(sign(JWSAlgorithm.RS512, rsa.getKeyID(), new RSASSASigner(rsa),
                claims(b -> { })), issuer, CLIENT, NONCE).subject());
    }

    @Test
    void signatureMustVerifyWithAPublishedKey() throws Exception {
        RSAKey rogue = new RSAKeyGenerator(2048).keyID("rogue").generate();
        assertInvalid(sign(JWSAlgorithm.RS256, "rogue", new RSASSASigner(rogue), claims(b -> { })), "signature");
        // a rogue key claiming the published kid
        RSAKey impostor = new RSAKeyGenerator(2048).keyID(rsa.getKeyID()).generate();
        assertInvalid(sign(JWSAlgorithm.RS256, rsa.getKeyID(), new RSASSASigner(impostor), claims(b -> { })),
                "signature");
    }

    @Test
    void unknownKidRefetchesTheJwks() throws Exception {
        assertEquals("user-1", validate(rs256(b -> { })).subject());
        assertEquals(1, jwksHits.get());
        // the provider rotates in a new key; a token with its kid makes the set be fetched again
        RSAKey next = new RSAKeyGenerator(2048).keyID("next").keyUse(KeyUse.SIGNATURE).generate();
        jwks.set(new JWKSet(List.of(rsa, ec, next)).toString(false));
        now.addAndGet(OidcDiscovery.REFETCH_MIN_MS);
        assertEquals("user-9", validate(sign(JWSAlgorithm.RS256, "next", new RSASSASigner(next),
                claims(b -> b.subject("user-9")))).subject());
        assertEquals(2, jwksHits.get());
        // a kid the provider never published, even after the refetch: refused
        now.addAndGet(OidcDiscovery.REFETCH_MIN_MS);
        RSAKey unknown = new RSAKeyGenerator(2048).keyID("unknown").generate();
        assertInvalid(sign(JWSAlgorithm.RS256, "unknown", new RSASSASigner(unknown), claims(b -> { })),
                "signature");
        assertEquals(3, jwksHits.get());
    }

    @Test
    void unreachableKeysAreReportedAsSuch() throws Exception {
        failWith.set(500);
        OidcLogin.LoginException e = assertThrows(OidcLogin.LoginException.class,
                () -> validate(rs256(b -> { })));
        assertEquals("provider_unreachable", e.code(), e.getMessage());
    }

    @Test
    void issuerMustMatchExactly() throws Exception {
        assertInvalid(rs256(b -> b.issuer(issuer + "/")), "iss");
        assertInvalid(rs256(b -> b.issuer("https://evil.example")), "iss");
        assertInvalid(rs256(b -> b.issuer(null)), "iss");
    }

    @Test
    void audienceMustBeTheClient() throws Exception {
        assertInvalid(rs256(b -> b.audience("someone-else")), "aud");
        assertInvalid(rs256(b -> b.audience((String) null)), "aud");
        // several audiences: azp needed, and it must be us
        assertInvalid(rs256(b -> b.audience(List.of(CLIENT, "other"))), "azp");
        assertInvalid(rs256(b -> b.audience(List.of(CLIENT, "other")).claim("azp", "other")), "azp");
        assertEquals("user-1", validate(rs256(b -> b.audience(List.of(CLIENT, "other")).claim("azp", CLIENT)))
                .subject());
        // one audience with a different azp
        assertInvalid(rs256(b -> b.claim("azp", "other")), "azp");
        assertEquals("user-1", validate(rs256(b -> b.claim("azp", CLIENT))).subject());
    }

    @Test
    void expiryWithSixtySecondsOfSkew() throws Exception {
        long t = now.get();
        assertInvalid(rs256(b -> b.expirationTime(null)), "no exp");
        // expired 2 min ago
        assertInvalid(rs256(b -> b.issueTime(new Date(t)).expirationTime(new Date(t - 120_000))), "expired");
        // expired exactly 60 s ago: out
        assertInvalid(rs256(b -> b.expirationTime(new Date(t - IdTokenValidator.MAX_SKEW_MS))), "expired");
        // expired 59 s ago: within the skew
        assertEquals("user-1", validate(rs256(b -> b.expirationTime(new Date(t - 59_000)))).subject());
    }

    @Test
    void issuedAtWithinSixtySeconds() throws Exception {
        long t = now.get();
        assertInvalid(rs256(b -> b.issueTime(null)), "no iat");
        assertInvalid(rs256(b -> b.issueTime(new Date(t - 61_000))), "iat");
        assertInvalid(rs256(b -> b.issueTime(new Date(t + 61_000))), "iat");
        assertEquals("user-1", validate(rs256(b -> b.issueTime(new Date(t - 60_000)))).subject());
        assertEquals("user-1", validate(rs256(b -> b.issueTime(new Date(t + 60_000)))).subject());
    }

    @Test
    void notBeforeWithSkew() throws Exception {
        long t = now.get();
        assertInvalid(rs256(b -> b.notBeforeTime(new Date(t + 61_000))), "nbf");
        assertEquals("user-1", validate(rs256(b -> b.notBeforeTime(new Date(t + 60_000)))).subject());
    }

    @Test
    void nonceMustMatch() throws Exception {
        assertInvalid(rs256(b -> b.claim("nonce", "another-nonce")), "nonce");
        assertInvalid(rs256(b -> b.claim("nonce", null)), "nonce");
        assertInvalid(rs256(b -> b.claim("nonce", 42)), "nonce");
        assertInvalid(rs256(b -> b.claim("nonce", NONCE + "x")), "nonce");
    }

    @Test
    void subjectIsRequired() throws Exception {
        assertInvalid(rs256(b -> b.subject(null)), "sub");
        assertInvalid(rs256(b -> b.subject("")), "sub");
    }
}
