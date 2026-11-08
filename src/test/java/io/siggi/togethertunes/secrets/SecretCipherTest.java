package io.siggi.togethertunes.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
    private static final String CTX = "oidc_providers.client_secret_enc:0190f0b2-0000-7000-8000-000000000001";

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    @Test
    void roundTrip() throws Exception {
        SecretCipher c = new SecretCipher(key(1));
        for (String s : new String[] {"", "hunter2", "ünïcödé ✓ 🎵", "x".repeat(10_000)}) {
            String enc = c.encrypt(s, CTX);
            assertFalse(enc.contains(s.isEmpty() ? "\0" : s));
            assertEquals(s, c.decrypt(enc, CTX));
        }
    }

    @Test
    void nullPassesThrough() throws Exception {
        SecretCipher c = new SecretCipher(key(1));
        assertNull(c.encrypt(null, CTX));
        assertNull(c.decrypt(null, CTX));
    }

    @Test
    void freshNonceEachTime() throws Exception {
        SecretCipher c = new SecretCipher(key(1));
        String a = c.encrypt("same", CTX);
        String b = c.encrypt("same", CTX);
        assertNotEquals(a, b);
        assertEquals("same", c.decrypt(a, CTX));
        assertEquals("same", c.decrypt(b, CTX));
    }

    @Test
    void storedFormat() {
        String enc = new SecretCipher(key(1)).encrypt("abc", CTX);
        assertTrue(enc.matches("[A-Za-z0-9_-]+"), enc);
        byte[] raw = Base64.getUrlDecoder().decode(enc);
        assertEquals(SecretCipher.VERSION, raw[0]);
        assertEquals(1 + SecretCipher.NONCE_BYTES + 3 + 16, raw.length);
    }

    @Test
    void wrongKeyFails() {
        String enc = new SecretCipher(key(1)).encrypt("secret", CTX);
        assertThrows(SecretDecryptionException.class, () -> new SecretCipher(key(2)).decrypt(enc, CTX));
    }

    @Test
    void wrongContextFails() {
        SecretCipher c = new SecretCipher(key(1));
        String enc = c.encrypt("secret", CTX);
        assertThrows(SecretDecryptionException.class, () -> c.decrypt(enc, CTX + "x"));
        assertThrows(SecretDecryptionException.class, () -> c.decrypt(enc, "stream_outputs.rtmp_key_enc:x"));
    }

    @Test
    void tamperingFails() {
        SecretCipher c = new SecretCipher(key(1));
        byte[] raw = Base64.getUrlDecoder().decode(c.encrypt("secret", CTX));
        for (int i = 1; i < raw.length; i++) {
            byte[] t = raw.clone();
            t[i] ^= 1;
            String bad = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
            assertThrows(SecretDecryptionException.class, () -> c.decrypt(bad, CTX), "byte " + i);
        }
        byte[] truncated = Arrays.copyOf(raw, raw.length - 1);
        assertThrows(SecretDecryptionException.class,
                () -> c.decrypt(Base64.getUrlEncoder().encodeToString(truncated), CTX));
    }

    @Test
    void malformedValues() {
        SecretCipher c = new SecretCipher(key(1));
        assertThrows(SecretDecryptionException.class, () -> c.decrypt("not base64!", CTX));
        assertThrows(SecretDecryptionException.class, () -> c.decrypt("", CTX));
        assertThrows(SecretDecryptionException.class, () -> c.decrypt("AQID", CTX));
        byte[] raw = Base64.getUrlDecoder().decode(c.encrypt("secret", CTX));
        raw[0] = 2;
        SecretDecryptionException e = assertThrows(SecretDecryptionException.class,
                () -> c.decrypt(Base64.getUrlEncoder().encodeToString(raw), CTX));
        assertTrue(e.getMessage().contains("version 2"), e.getMessage());
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(new byte[33]));
    }

    @Test
    void keyNotCapturedByCallerArray() throws Exception {
        byte[] k = key(7);
        SecretCipher c = new SecretCipher(k);
        String enc = c.encrypt("v", CTX);
        Arrays.fill(k, (byte) 0);
        assertEquals("v", c.decrypt(enc, CTX));
        assertFalse(c.toString().contains("7"));
    }
}
