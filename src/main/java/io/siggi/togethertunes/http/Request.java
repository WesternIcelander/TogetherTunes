package io.siggi.togethertunes.http;

import com.google.gson.JsonParseException;
import io.siggi.http.HTTPRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    private static final int COPY_BUFFER = 64 * 1024;

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

    /**
     * The declared {@code Content-Length}, or -1 if there is none (no body, or a chunked one).
     * SiggiHTTP has already rejected malformed or conflicting values with a 400.
     */
    public long contentLength() {
        if (header("Transfer-Encoding") != null) {
            return -1;
        }
        String value = header("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.split(",", 2)[0].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Streams the request body (of any size, {@code Content-Length} or chunked) into {@code out}
     * with a fixed-size buffer, so a multi-GB upload never sits in memory. {@code out} is flushed
     * but not closed. Wrap it (e.g. in a {@code DigestOutputStream}) to hash the body on the way.
     *
     * <p>Errors: a declared {@code Content-Length} above {@code maxBytes} fails at once with 413,
     * before any byte is read; a chunked body fails with 413 as soon as it passes
     * {@code maxBytes} (the bytes up to the limit have been written by then). A body that ends
     * early (the client left, timed out after SiggiHTTP's 60 s read timeout, or sent fewer bytes
     * than its {@code Content-Length}) fails with 400 {@code incomplete_body}. Those are
     * {@link HttpError}s; an {@link IOException} from {@code out} (e.g. a full disk) propagates
     * as is and so becomes a 500.
     *
     * @param maxBytes the largest body accepted, or a negative number for no limit
     * @return the number of bytes copied
     */
    public long copyBody(OutputStream out, long maxBytes) throws IOException {
        long declared = contentLength();
        if (maxBytes >= 0 && declared > maxBytes) {
            throw tooLarge(maxBytes);
        }
        InputStream in = body();
        byte[] buffer = new byte[COPY_BUFFER];
        long total = 0;
        while (true) {
            int n;
            try {
                n = in.read(buffer);
            } catch (IOException e) {
                throw incompleteBody();
            }
            if (n < 0) {
                break;
            }
            if (maxBytes >= 0 && total + n > maxBytes) {
                out.write(buffer, 0, (int) (maxBytes - total));
                out.flush();
                throw tooLarge(maxBytes);
            }
            out.write(buffer, 0, n);
            total += n;
        }
        out.flush();
        // SiggiHTTP's Content-Length stream reports a closed connection as a normal end of body
        if (declared >= 0 && total != declared) {
            throw incompleteBody();
        }
        return total;
    }

    /**
     * Streams the request body into {@code file}, starting at byte {@code position}, creating the
     * file if needed. Bytes outside the written range are left as they are (the file is never
     * truncated), so a resumable upload can write one chunk at its offset; the caller checks the
     * offset against the file's current size. On an error the bytes received so far stay in the
     * file. Same limits and errors as {@link #copyBody}.
     *
     * @return the number of bytes written
     */
    public long bodyToFile(Path file, long position, long maxBytes) throws IOException {
        if (position < 0) {
            throw new IllegalArgumentException("Negative position " + position);
        }
        long declared = contentLength();
        if (maxBytes >= 0 && declared > maxBytes) {
            throw tooLarge(maxBytes); // before creating the file
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(position);
            return copyBody(Channels.newOutputStream(channel), maxBytes);
        }
    }

    private static HttpError tooLarge(long maxBytes) {
        return HttpError.payloadTooLarge("Request body is larger than " + maxBytes + " bytes");
    }

    private static HttpError incompleteBody() {
        return new HttpError(400, "incomplete_body", "The request body ended early");
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
