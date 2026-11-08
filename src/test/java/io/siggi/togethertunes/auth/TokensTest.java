package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokensTest {
    @Test
    void newTokensAre256BitBase64url() {
        String a = Tokens.newToken();
        assertEquals(43, a.length());
        assertEquals(32, Base64.getUrlDecoder().decode(a).length);
        assertTrue(Tokens.isWellFormed(a));
        assertNotEquals(a, Tokens.newToken());
    }

    @Test
    void wellFormed() {
        assertFalse(Tokens.isWellFormed(null));
        assertFalse(Tokens.isWellFormed(""));
        assertFalse(Tokens.isWellFormed("a".repeat(42)));
        assertFalse(Tokens.isWellFormed("a".repeat(44)));
        assertFalse(Tokens.isWellFormed("a".repeat(42) + "="));
        assertFalse(Tokens.isWellFormed("a".repeat(42) + "+"));
        assertTrue(Tokens.isWellFormed("aZ09-_".repeat(7) + "x"));
    }

    @Test
    void hashIsLowercaseHexSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Tokens.hash(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Tokens.hash("abc"));
    }

    @Test
    void redactNeverShowsTheWholeToken() {
        String t = Tokens.newToken();
        String r = Tokens.redact(t);
        assertEquals(t.substring(0, 4) + "…", r);
        assertEquals("…", Tokens.redact("abc"));
        assertEquals("null", Tokens.redact(null));
    }
}
