package io.siggi.togethertunes.http;

import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Our view of a SiggiHTTP response. Every call writes through to the underlying response, so
 * mixing these helpers with {@link #raw()} (e.g. {@code returnFile}) is safe. Headers can only be
 * changed until the first byte is written ({@link #committed()}).
 */
public final class Response {
    public static final String JSON = "application/json; charset=utf-8";
    public static final String TEXT = "text/plain; charset=utf-8";

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(200, "OK"), Map.entry(201, "Created"), Map.entry(202, "Accepted"),
            Map.entry(204, "No Content"), Map.entry(206, "Partial Content"),
            Map.entry(301, "Moved Permanently"), Map.entry(302, "Found"), Map.entry(303, "See Other"),
            Map.entry(304, "Not Modified"), Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"),
            Map.entry(406, "Not Acceptable"), Map.entry(409, "Conflict"), Map.entry(410, "Gone"),
            Map.entry(411, "Length Required"), Map.entry(412, "Precondition Failed"),
            Map.entry(413, "Content Too Large"), Map.entry(415, "Unsupported Media Type"),
            Map.entry(416, "Range Not Satisfiable"), Map.entry(422, "Unprocessable Content"),
            Map.entry(426, "Upgrade Required"),
            Map.entry(429, "Too Many Requests"),
            Map.entry(500, "Internal Server Error"), Map.entry(501, "Not Implemented"),
            Map.entry(502, "Bad Gateway"), Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"), Map.entry(507, "Insufficient Storage"));

    private final HTTPRequest rawRequest;
    private final HTTPResponse raw;
    private boolean detached;
    /** The cookies set so far, by name. */
    private final Map<String, String> cookies = new LinkedHashMap<>();

    Response(HTTPRequest rawRequest) {
        this.rawRequest = rawRequest;
        this.raw = rawRequest.response;
    }

    /** The status line for a code, e.g. {@code "404 Not Found"}. */
    static String statusLine(int status) {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("Bad status " + status);
        }
        return status + " " + REASONS.getOrDefault(status, "Status " + status);
    }

    /** Sets the status (default 200). */
    public Response status(int status) {
        raw.setHeader(statusLine(status));
        return this;
    }

    /** Sets a header, replacing earlier values ({@code Set-Cookie}: see {@link #setCookie}). */
    public Response header(String name, String value) {
        if (name.equalsIgnoreCase("Set-Cookie")) {
            return setCookie(value);
        }
        raw.setHeader(name, value);
        return this;
    }

    /**
     * Adds a header value. A {@code Set-Cookie} goes through {@link #setCookie}, so a later cookie
     * of the same name replaces an earlier one.
     */
    public Response addHeader(String name, String value) {
        if (name.equalsIgnoreCase("Set-Cookie")) {
            return setCookie(value);
        }
        raw.addHeader(name, value);
        return this;
    }

    /**
     * Sets a cookie: {@code setCookieValue} is a whole {@code Set-Cookie} header value
     * ({@code name=value; attributes}). An earlier cookie of the same name in this response is
     * replaced, so e.g. a refreshed session cookie and a logout's clearing cookie never both go out.
     */
    public Response setCookie(String setCookieValue) {
        int eq = setCookieValue.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("Not a Set-Cookie value");
        }
        cookies.put(setCookieValue.substring(0, eq).strip(), setCookieValue);
        raw.deleteHeader("Set-Cookie");
        for (String cookie : cookies.values()) {
            raw.addHeader("Set-Cookie", cookie);
        }
        return this;
    }

    /** Sends {@code body} as JSON with status 200. */
    public void json(Object body) throws IOException {
        json(200, body);
    }

    /** Sends {@code body} as JSON. */
    public void json(int status, Object body) throws IOException {
        send(status, JSON, Json.GSON.toJson(body));
    }

    /** Sends plain text. */
    public void text(int status, String body) throws IOException {
        send(status, TEXT, body);
    }

    /** Sends a complete body with the given content type. */
    public void send(int status, String contentType, String body) throws IOException {
        status(status);
        raw.setContentType(contentType);
        raw.send(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends an empty response, e.g. {@code 204}. */
    public void empty(int status) throws IOException {
        status(status);
        if (status != 204 && status != 304) {
            raw.contentLength(0);
        }
        raw.sendHeaders();
    }

    /** Sends the {@code docs/api.md} error body (and {@code Retry-After} if the error has one). */
    public void error(HttpError error) throws IOException {
        if (error.retryAfterSeconds() > 0) {
            header("Retry-After", Long.toString(error.retryAfterSeconds()));
        }
        error(error.status(), error.code(), error.getMessage());
    }

    /** Sends {@code { "error": { "code", "message" } }}. */
    public void error(int status, String code, String message) throws IOException {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        json(status, Map.of("error", error));
    }

    /**
     * Starts an endless (or open-ended) streaming body with status 200. Call before anything else
     * is written; set extra headers first. For {@code HEAD} the headers are sent now and the
     * returned body is {@linkplain StreamBody#headOnly() head-only}.
     */
    public StreamBody stream(String contentType, StreamBody.Framing framing) throws IOException {
        return stream(200, contentType, framing);
    }

    /** Like {@link #stream(String, StreamBody.Framing)} with a status. */
    public StreamBody stream(int status, String contentType, StreamBody.Framing framing) throws IOException {
        if (committed()) {
            throw new IllegalStateException("Response already committed");
        }
        status(status);
        raw.setContentType(contentType);
        raw.setHeader("Cache-Control", "no-store");
        if (framing == StreamBody.Framing.CLOSE_DELIMITED) {
            raw.closeDelimited();
        } else {
            raw.contentLength(-1);
        }
        raw.setBuffered(false);
        boolean head = "HEAD".equals(rawRequest.method());
        if (head) {
            raw.sendHeaders();
        }
        return new StreamBody(raw, head);
    }

    /** Whether the status line and headers have been sent. */
    public boolean committed() {
        return raw.alreadyWrote();
    }

    /**
     * Takes the response over for writing after the handler returns, e.g. from another thread
     * (SiggiHTTP's {@code openResponse()}). The caller must eventually {@code close()} it.
     */
    public HTTPResponse detach() throws IOException {
        HTTPResponse response = rawRequest.openResponse();
        detached = true;
        return response;
    }

    boolean detached() {
        return detached;
    }

    /** The underlying SiggiHTTP response, e.g. for {@code returnFile} or streaming. */
    public HTTPResponse raw() {
        return raw;
    }
}
