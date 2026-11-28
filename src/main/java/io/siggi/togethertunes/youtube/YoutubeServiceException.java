package io.siggi.togethertunes.youtube;

/**
 * A YouTube service call that didn't give an answer ({@link YoutubeService}). The message is for
 * logs and admins; callers turn the {@link Kind} into the error people see.
 */
public final class YoutubeServiceException extends Exception {
    /** What went wrong. */
    public enum Kind {
        /** {@code youtube.serviceUrl} is empty. */
        NOT_CONFIGURED,
        /** The service couldn't be reached (connection refused, DNS, reset). */
        UNREACHABLE,
        /** The service answered 401: the token is missing or wrong. */
        UNAUTHORIZED,
        /** The service answered something unusable: another status, a redirect, too much, broken JSON. */
        BAD_ANSWER,
        /** No answer (or no progress) in time. */
        TIMEOUT,
        /** YouTube itself refused: the service reported an {@code error_type} ({@link #errorType}). */
        YOUTUBE
    }

    private final Kind kind;
    private final String errorType;
    private final int httpStatus;

    public YoutubeServiceException(Kind kind, String message) {
        this(kind, message, null, -1);
    }

    /**
     * @param errorType  the service's {@code error_type} for {@link Kind#YOUTUBE}
     *                   ({@code unavailable}, {@code private}, {@code age_restricted},
     *                   {@code sign_in_required}, {@code network}, {@code other}), else null
     * @param httpStatus the HTTP status, or -1
     */
    public YoutubeServiceException(Kind kind, String message, String errorType, int httpStatus) {
        super(message);
        this.kind = kind;
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    /** The service's {@code error_type} ({@link Kind#YOUTUBE}), else null. */
    public String errorType() {
        return errorType;
    }

    /** The HTTP status, or -1. */
    public int httpStatus() {
        return httpStatus;
    }
}
