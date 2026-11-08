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

    /**
     * @param status  HTTP status, 400–599
     * @param code    machine-readable snake_case code
     * @param message human-readable message
     */
    public HttpError(int status, String code, String message) {
        super(message, null, false, false);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Not an error status: " + status);
        }
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
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
}
