package io.siggi.togethertunes.secrets;

/**
 * An encrypted column value can't be decrypted: it is malformed, was written with another key or
 * context, or has been tampered with. The message never contains the value itself.
 */
public final class SecretDecryptionException extends Exception {
    public SecretDecryptionException(String message) {
        super(message);
    }
}
