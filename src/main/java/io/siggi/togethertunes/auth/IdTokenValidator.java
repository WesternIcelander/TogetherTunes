package io.siggi.togethertunes.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Validates an OIDC ID token ({@code docs/auth.md#oidc}, step 3; OpenID Connect Core §3.1.3.7) with
 * Nimbus JOSE+JWT:
 *
 * <ul>
 *   <li>a <b>signed</b> JWT (unsecured {@code alg=none} and encrypted tokens are refused) whose
 *       {@code alg} is one of the allowed ones, by default only <b>RS256 and ES256</b>; HMAC
 *       ({@code HS*}) is never accepted (it would need the client secret as a key and an explicit
 *       per-provider setting, which doesn't exist);</li>
 *   <li>the signature verifies with a key from the issuer's JWKS ({@link OidcDiscovery#keySource},
 *       which refetches the set once for an unknown {@code kid});</li>
 *   <li>{@code iss} equals the provider's issuer exactly;</li>
 *   <li>{@code aud} contains the client ID; with several audiences {@code azp} must be present, and
 *       a present {@code azp} must be the client ID;</li>
 *   <li>{@code exp} is required and not passed, {@code iat} is required and within
 *       ±{@value #MAX_SKEW_MS} ms of now, a present {@code nbf} has been reached (both with the same
 *       skew);</li>
 *   <li>{@code nonce} equals the one sent with the authorization request (compared in constant
 *       time);</li>
 *   <li>{@code sub} is present and non-empty.</li>
 * </ul>
 *
 * Failures are {@link OidcLogin.LoginException}s with code {@code id_token_invalid}, or
 * {@code provider_unreachable} when the keys couldn't be fetched. Messages never contain the token.
 */
public final class IdTokenValidator {
    /** Allowed clock difference for {@code exp}, {@code iat} and {@code nbf}: 60 s. */
    public static final long MAX_SKEW_MS = 60_000L;
    /** The signing algorithms accepted by default. */
    public static final Set<JWSAlgorithm> DEFAULT_ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

    /** A validated ID token. */
    public record IdToken(String subject, JWTClaimsSet claims) {
        public IdToken {
            Objects.requireNonNull(subject);
            Objects.requireNonNull(claims);
        }

        @Override
        public String toString() {
            return "IdToken[" + claims.getIssuer() + "]";
        }
    }

    private final OidcDiscovery discovery;
    private final LongSupplier clock;
    private final Set<JWSAlgorithm> algorithms;

    /** With the {@linkplain #DEFAULT_ALGORITHMS default algorithms}. */
    public IdTokenValidator(OidcDiscovery discovery, LongSupplier clock) {
        this(discovery, clock, DEFAULT_ALGORITHMS);
    }

    /**
     * @param algorithms the accepted signing algorithms; asymmetric ones only ({@code none} and
     *                   {@code HS*} are refused)
     */
    public IdTokenValidator(OidcDiscovery discovery, LongSupplier clock, Set<JWSAlgorithm> algorithms) {
        this.discovery = Objects.requireNonNull(discovery);
        this.clock = Objects.requireNonNull(clock);
        if (algorithms.isEmpty()) {
            throw new IllegalArgumentException("no ID-token algorithms allowed");
        }
        for (JWSAlgorithm alg : algorithms) {
            if (!JWSAlgorithm.Family.RSA.contains(alg) && !JWSAlgorithm.Family.EC.contains(alg)
                    && !JWSAlgorithm.Family.ED.contains(alg)) {
                throw new IllegalArgumentException("not an asymmetric signing algorithm: " + alg);
            }
        }
        this.algorithms = Set.copyOf(algorithms);
    }

    /** The accepted signing algorithms. */
    public Set<JWSAlgorithm> algorithms() {
        return algorithms;
    }

    /**
     * Validates {@code idToken} for the provider with {@code issuer} and {@code clientId}, sent
     * with {@code nonce}.
     */
    public IdToken validate(String idToken, String issuer, String clientId, String nonce)
            throws OidcLogin.LoginException {
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(nonce);
        if (idToken == null || idToken.isEmpty()) {
            throw invalid("no ID token");
        }
        JWT jwt;
        try {
            jwt = JWTParser.parse(idToken);
        } catch (ParseException e) {
            throw invalid("the ID token isn't a JWT");
        }
        if (!(jwt instanceof SignedJWT signed)) {
            throw invalid("the ID token isn't signed");
        }
        JWSAlgorithm alg = signed.getHeader().getAlgorithm();
        if (!algorithms.contains(alg)) {
            throw invalid("the ID token's algorithm " + alg + " isn't allowed");
        }

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, discovery.keySource(issuer)));
        // The claims are checked below, against the injected clock.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        JWTClaimsSet claims;
        try {
            claims = processor.process(signed, null);
        } catch (BadJOSEException e) {
            throw invalid("the ID token's signature doesn't verify: " + e.getMessage());
        } catch (KeySourceException e) {
            throw new OidcLogin.LoginException("provider_unreachable",
                    "the provider's keys couldn't be fetched: " + e.getMessage());
        } catch (JOSEException e) {
            throw invalid("the ID token couldn't be verified: " + e.getMessage());
        }
        return new IdToken(checkClaims(claims, issuer, clientId, nonce), claims);
    }

    private String checkClaims(JWTClaimsSet claims, String issuer, String clientId, String nonce)
            throws OidcLogin.LoginException {
        if (!issuer.equals(claims.getIssuer())) {
            throw invalid("the ID token's iss isn't the provider's issuer");
        }
        List<String> audience = claims.getAudience();
        if (!audience.contains(clientId)) {
            throw invalid("the ID token's aud doesn't contain the client ID");
        }
        String azp;
        try {
            azp = claims.getStringClaim("azp");
        } catch (ParseException e) {
            throw invalid("the ID token's azp isn't a string");
        }
        if (audience.size() > 1 && azp == null) {
            throw invalid("the ID token has several audiences but no azp");
        }
        if (azp != null && !azp.equals(clientId)) {
            throw invalid("the ID token's azp isn't the client ID");
        }

        long now = clock.getAsLong();
        Date exp = claims.getExpirationTime();
        if (exp == null) {
            throw invalid("the ID token has no exp");
        }
        if (exp.getTime() + MAX_SKEW_MS <= now) {
            throw invalid("the ID token has expired");
        }
        Date iat = claims.getIssueTime();
        if (iat == null) {
            throw invalid("the ID token has no iat");
        }
        if (Math.abs(now - iat.getTime()) > MAX_SKEW_MS) {
            throw invalid("the ID token's iat is more than 60 s away from now");
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && nbf.getTime() - MAX_SKEW_MS > now) {
            throw invalid("the ID token isn't valid yet (nbf)");
        }

        String tokenNonce;
        try {
            tokenNonce = claims.getStringClaim("nonce");
        } catch (ParseException e) {
            throw invalid("the ID token's nonce isn't a string");
        }
        if (tokenNonce == null || !MessageDigest.isEqual(tokenNonce.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8))) {
            throw invalid("the ID token's nonce doesn't match");
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isEmpty()) {
            throw invalid("the ID token has no sub");
        }
        return subject;
    }

    private static OidcLogin.LoginException invalid(String message) {
        return new OidcLogin.LoginException("id_token_invalid", message);
    }
}
