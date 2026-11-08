package io.siggi.togethertunes.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for {@code *_enc} columns.
 *
 * <p>Stored form (a {@code TEXT} column): URL-safe base64 without padding of
 * {@code version (1 byte, 0x01) || nonce (12 random bytes) || ciphertext || tag (16 bytes)}.
 *
 * <p>Every call takes a <em>context</em> string that is bound to the ciphertext as additional
 * authenticated data, together with the version byte. Use the table, column and row id, e.g.
 * {@code "oidc_providers.client_secret_enc:" + id}, so a ciphertext copied into another column or
 * row fails to decrypt instead of quietly yielding someone else's secret.
 *
 * <p>Instances are immutable and thread-safe. Never log the plaintext or the key.
 */
public final class SecretCipher {
    /** Format version written as the first byte. */
    static final byte VERSION = 1;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** Builds a cipher from a 32-byte key (the array is copied). */
    public SecretCipher(byte[] key) {
        if (key.length != ServerSecret.KEY_BYTES) {
            throw new IllegalArgumentException("key must be " + ServerSecret.KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /**
     * Encrypts {@code plaintext} (UTF-8) for the given context.
     *
     * @return the column value; {@code null} if {@code plaintext} is {@code null}
     */
    public String encrypt(String plaintext, String context) {
        Objects.requireNonNull(context, "context");
        if (plaintext == null) {
            return null;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(context));
            byte[] sealed = cipher.doFinal(data);
            ByteBuffer out = ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length);
            out.put(VERSION).put(nonce).put(sealed);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    /**
     * Decrypts a column value written by {@link #encrypt} with the same context.
     *
     * @return the plaintext; {@code null} if {@code stored} is {@code null}
     * @throws SecretDecryptionException if the value is malformed, was written with another key
     *                                   or another context, or has been tampered with
     */
    public String decrypt(String stored, String context) throws SecretDecryptionException {
        Objects.requireNonNull(context, "context");
        if (stored == null) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            throw new SecretDecryptionException("not valid base64");
        }
        if (raw.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
            throw new SecretDecryptionException("too short");
        }
        if (raw[0] != VERSION) {
            throw new SecretDecryptionException("unknown format version " + (raw[0] & 0xff));
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 1, NONCE_BYTES));
            cipher.updateAAD(aad(context));
            byte[] plain = cipher.doFinal(raw, 1 + NONCE_BYTES, raw.length - 1 - NONCE_BYTES);
            try {
                return new String(plain, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (AEADBadTagException e) {
            throw new SecretDecryptionException("authentication failed (wrong key or context, or tampered)");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    private static byte[] aad(String context) {
        byte[] ctx = context.getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[1 + ctx.length];
        aad[0] = VERSION;
        System.arraycopy(ctx, 0, aad, 1, ctx.length);
        return aad;
    }

    @Override
    public String toString() {
        return "SecretCipher[AES-256-GCM]";
    }
}
