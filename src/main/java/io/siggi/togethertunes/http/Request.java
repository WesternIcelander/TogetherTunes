package io.siggi.togethertunes.http;

import com.google.gson.JsonParseException;
import io.siggi.http.HTTPRequest;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Our view of a SiggiHTTP request: method, decoded path and its route parameters, query, headers,
 * cookies, body, and typed attributes that hooks use to hand data to handlers (e.g. the actor).
 *
 * <p>One instance per request, used from the request's own (virtual) thread; not thread-safe.
 */
public final class Request {
    /** Default limit for {@link #readJson} bodies. */
    public static final int DEFAULT_JSON_LIMIT = 1024 * 1024;

    private final HTTPRequest raw;
    private final Map<String, String> pathParams;
    private final Map<Class<?>, Object> attributes = new HashMap<>();

    Request(HTTPRequest raw, Map<String, String> pathParams) {
        this.raw = raw;
        this.pathParams = pathParams;
    }

    /** The method as sent, upper case ({@code HEAD} stays {@code HEAD}, though it runs GET routes). */
    public String method() {
        return raw.method();
    }

    /** The percent-decoded path, without the query string. */
    public String path() {
        return raw.path();
    }

    /**
     * A parameter of the matched route pattern.
     *
     * @throws IllegalArgumentException if the route has no such parameter (a programming error)
     */
    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Route has no parameter '" + name + "'");
        }
        return value;
    }

    /** All route parameters by name. */
    public Map<String, String> pathParams() {
        return pathParams;
    }

    /** A query parameter, or {@code null}. */
    public String query(String name) {
        return raw.get().get(name);
    }

    /** All query parameters (the last value wins for repeated names). */
    public Map<String, String> query() {
        return raw.get();
    }

    /** The first value of a header (name is case-insensitive), or {@code null}. */
    public String header(String name) {
        return raw.getHeader(name);
    }

    /** All values of a header (name is case-insensitive); empty if absent. */
    public List<String> headers(String name) {
        List<String> values = raw.getHeaders(name);
        return values == null ? List.of() : values;
    }

    /** A cookie value, or {@code null}. */
    public String cookie(String name) {
        return raw.cookies().get(name);
    }

    /**
     * The client's IP address as SiggiHTTP resolved it (behind trusted proxies, from
     * {@code X-Forwarded-For}). For rate limiting, guest IP mode and audit logs only: per
     * {@code docs/auth.md}, never put it in a response.
     */
    public String clientIp() {
        return raw.getIPAddress();
    }

    /** Whether the client connection is HTTPS (directly or per {@code X-Forwarded-Proto}). */
    public boolean isSecure() {
        return raw.isSecure();
    }

    /**
     * The raw request body; an empty stream when there is none. SiggiHTTP manages (and drains) it,
     * so callers needn't close it.
     */
    public InputStream body() {
        InputStream in = raw.getInputStream();
        return in == null ? InputStream.nullInputStream() : in;
    }

    /** Parses a JSON body of at most {@link #DEFAULT_JSON_LIMIT} bytes. */
    public <T> T readJson(Class<T> type) throws IOException {
        return readJson(type, DEFAULT_JSON_LIMIT);
    }

    /**
     * Parses a JSON body.
     *
     * @throws HttpError 415 unless {@code Content-Type} is {@code application/json}; 413 if the body
     *                   is longer than {@code maxBytes}; 400 if it is empty, {@code null} or not
     *                   valid JSON for {@code type}
     */
    public <T> T readJson(Type type, int maxBytes) throws IOException {
        String contentType = header("Content-Type");
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mediaType.equals("application/json")) {
            throw HttpError.unsupportedMediaType("Expected Content-Type: application/json");
        }
        byte[] bytes = body().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw HttpError.payloadTooLarge("Request body is larger than " + maxBytes + " bytes");
        }
        T value;
        try {
            value = Json.GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (JsonParseException e) {
            throw new HttpError(400, "invalid_json", "Request body is not valid JSON for this request");
        }
        if (value == null) {
            throw new HttpError(400, "invalid_json", "Request body is empty");
        }
        return value;
    }

    /** An attribute set by a hook, or {@code null}. */
    public <T> T attribute(Class<T> key) {
        return key.cast(attributes.get(key));
    }

    /** Sets (or with {@code null}, removes) an attribute, keyed by its type. */
    public <T> void setAttribute(Class<T> key, T value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, key.cast(value));
        }
    }

    /** The underlying SiggiHTTP request, for what this adapter doesn't cover (e.g. WebSocket upgrades). */
    public HTTPRequest raw() {
        return raw;
    }
}
