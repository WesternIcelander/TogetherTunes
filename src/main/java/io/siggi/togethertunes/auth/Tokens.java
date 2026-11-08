package io.siggi.togethertunes.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque credential tokens ({@code docs/auth.md#sessions}): session tokens ({@code sid} cookie and
 * bearer) and guest cookie tokens ({@code gid}). 256 random bits, base64url without padding
 * (43 characters). The database stores only {@link #hash}.
 */
public final class Tokens {
    /** Bytes of randomness in a token. */
    public static final int TOKEN_BYTES = 32;
    /** Length of a token's text form. */
    public static final int TOKEN_LENGTH = 43;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    /** A new random token. */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * Whether {@code value} has the shape of a token (43 base64url characters). Values that don't
     * are rejected without a database lookup.
     */
    public static boolean isWellFormed(String value) {
        if (value == null || value.length() != TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** SHA-256 of the UTF-8 text, as 64 lowercase hex digits: the form stored in {@code *_hash} columns. */
    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is always available", e);
        }
    }

    /** A token shortened for logs (invariant 6: never log credentials in full). */
    public static String redact(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 6 ? "…" : token.substring(0, 4) + "…";
    }
}
