package io.siggi.togethertunes.secrets;

/** The server's secret key is missing, malformed or can't be read or written. */
public final class SecretKeyException extends Exception {
    public SecretKeyException(String message) {
        super(message);
    }

    public SecretKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
