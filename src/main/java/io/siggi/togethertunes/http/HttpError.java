package io.siggi.togethertunes.http;

/**
 * An error response, thrown from a {@link Handler} or {@link Hook} and turned by the
 * {@link Router} into {@code { "error": { "code": "…", "message": "…" } }} with the given status
 * (the error format from {@code docs/api.md}).
 *
 * <p>Messages are shown to the client, so they must not contain internals (stack traces, SQL,
 * paths) or anything covered by the IP policy.
 */
public class HttpError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final long retryAfterSeconds;

    /**
     * @param status  HTTP status, 400–599
     * @param code    machine-readable snake_case code
     * @param message human-readable message
     */
    public HttpError(int status, String code, String message) {
        this(status, code, message, 0);
    }

    /**
     * @param retryAfterSeconds sent as {@code Retry-After} when positive (e.g. for {@code 429})
     */
    public HttpError(int status, String code, String message, long retryAfterSeconds) {
        super(message, null, false, false);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Not an error status: " + status);
        }
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** The {@code Retry-After} value in seconds, or 0 for none. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * More fields of the error object, after {@code code} and {@code message} (e.g.
     * {@code 409 needs_classification}'s {@code itemId} and {@code suggested}); none by default.
     * Serialised with Gson, so the same IP rules as for any response apply.
     */
    public java.util.Map<String, Object> extra() {
        return java.util.Map.of();
    }

    public static HttpError badRequest(String message) {
        return new HttpError(400, "bad_request", message);
    }

    public static HttpError unauthorized(String message) {
        return new HttpError(401, "unauthorized", message);
    }

    public static HttpError forbidden(String message) {
        return new HttpError(403, "forbidden", message);
    }

    public static HttpError notFound() {
        return new HttpError(404, "not_found", "Not found");
    }

    public static HttpError payloadTooLarge(String message) {
        return new HttpError(413, "payload_too_large", message);
    }

    public static HttpError unsupportedMediaType(String message) {
        return new HttpError(415, "unsupported_media_type", message);
    }

    public static HttpError tooManyRequests(String message) {
        return new HttpError(429, "rate_limited", message);
    }

    /**
     * {@code 429 rate_limited} with {@code Retry-After}: {@code retryAfterMs} rounded up to whole
     * seconds (at least 1).
     */
    public static HttpError tooManyRequests(String message, long retryAfterMs) {
        return new HttpError(429, "rate_limited", message, Math.max(1, (retryAfterMs + 999) / 1000));
    }
}
