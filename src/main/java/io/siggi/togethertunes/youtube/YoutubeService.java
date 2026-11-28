package io.siggi.togethertunes.youtube;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The client of the YouTube service (YTSucks, {@code docs/library-and-ingest.md#youtube-caching}):
 * JDK {@link HttpClient}, no redirects, a {@link #CONNECT_TIMEOUT} connect timeout, answers over a
 * size cap refused. The endpoint ({@code youtube.serviceUrl}, {@code youtube.serviceToken}) is read
 * for every call, so an admin's change applies at once. The token only ever travels in the
 * {@code Authorization} header (never in a URL, never logged); without one no header is sent (for a
 * TogetherTunes whose address is on the service's allow list). Videos are always named by their ID
 * ({@code vid=}), never by a pasted URL.
 *
 * <ul>
 *   <li>{@link #info}: {@code GET /api/info?vid=&stream=true}, its {@code stage} lines to a listener,
 *       {@code keepalive} ignored, the {@code result} or {@code error} line; {@link #INFO_TIMEOUT}
 *       overall.</li>
 *   <li>{@link #search}: {@code GET /api/search?q=&n=&exclude_live=true}, {@link #SEARCH_TIMEOUT}.</li>
 *   <li>{@link #download}: {@code GET /api/video?vid=&quality=&download=true&stream=true} until the job
 *       is {@code done} (its {@code file_url}) or {@code error}; then {@link #fetchFile} streams the MP4.
 *       Both with an idle timeout (no progress, no bytes) instead of a total one.</li>
 *   <li>{@link #cover}: the info's {@code thumbnail} (https on {@code *.ytimg.com} /
 *       {@code *.googleusercontent.com} only), else YouTube's {@code hqdefault.jpg}.</li>
 *   <li>{@link #status}, {@link #startUpdate}, {@link #updateLog}, {@link #checkToken}: the admin box.</li>
 * </ul>
 */
public final class YoutubeService {
    /** Where the service is: its base URL (null or blank = not set up) and the bearer token (null or blank = none). */
    public record Endpoint(String url, String token) {
        public static final Endpoint NONE = new Endpoint(null, null);

        public boolean configured() {
            return url != null && !url.isBlank();
        }

        boolean hasToken() {
            return token != null && !token.isEmpty();
        }

        @Override
        public String toString() {
            return "Endpoint[" + url + (hasToken() ? ", token set" : "") + "]"; // never the token
        }
    }

    /** Fetches a thumbnail image (tests replace it; the default goes to the internet). */
    @FunctionalInterface
    public interface ImageFetcher {
        /** The body of a {@code 200} answer of at most {@code maxBytes}; throws otherwise. */
        byte[] fetch(URI uri, int maxBytes) throws IOException, InterruptedException;
    }

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** The service gives up on a lookup after 90 s; this leaves it room to say so. */
    public static final Duration INFO_TIMEOUT = Duration.ofSeconds(120);
    public static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(15);
    /** A download fails after this long without progress or bytes. */
    public static final Duration DOWNLOAD_IDLE_TIMEOUT = Duration.ofMinutes(5);
    static final int INFO_MAX_BYTES = 8 * 1024 * 1024;
    static final int SEARCH_MAX_BYTES = 4 * 1024 * 1024;
    static final int ADMIN_MAX_BYTES = 8 * 1024 * 1024;
    static final int LINE_MAX_BYTES = 4 * 1024 * 1024;
    public static final int THUMBNAIL_MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern TOKEN_PARAM = Pattern.compile("(?i)([?&]token=)[^&#]*");
    private static final System.Logger LOG = System.getLogger(YoutubeService.class.getName());

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();

    private final Supplier<Endpoint> endpoint;
    private volatile ImageFetcher images = YoutubeService::fetchImage;
    private volatile Duration infoTimeout = INFO_TIMEOUT;
    private volatile Duration searchTimeout = SEARCH_TIMEOUT;
    private volatile Duration downloadIdleTimeout = DOWNLOAD_IDLE_TIMEOUT;

    /** @param endpoint read for every call */
    public YoutubeService(Supplier<Endpoint> endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint);
    }

    /** A service that is never set up (every call: {@link YoutubeServiceException.Kind#NOT_CONFIGURED}). */
    public static YoutubeService none() {
        return new YoutubeService(() -> Endpoint.NONE);
    }

    /** Replaces how thumbnails are fetched (tests). */
    public void imageFetcher(ImageFetcher fetcher) {
        this.images = Objects.requireNonNull(fetcher);
    }

    /** Replaces the lookup and search timeouts and the download's idle timeout (tests). */
    public void timeouts(Duration info, Duration search, Duration downloadIdle) {
        this.infoTimeout = Objects.requireNonNull(info);
        this.searchTimeout = Objects.requireNonNull(search);
        this.downloadIdleTimeout = Objects.requireNonNull(downloadIdle);
    }

    /** How long a download may go without progress or bytes ({@link #DOWNLOAD_IDLE_TIMEOUT}). */
    public Duration downloadIdleTimeout() {
        return downloadIdleTimeout;
    }

    /** Whether {@code youtube.serviceUrl} is set right now. */
    public boolean configured() {
        return current().configured();
    }

    private Endpoint current() {
        Endpoint e;
        try {
            e = endpoint.get();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the YouTube service settings: " + ex.getMessage());
            e = null;
        }
        return e == null ? Endpoint.NONE : e;
    }

    // ------------------------------------------------------------------ lookup and search

    /**
     * The video's metadata ({@code /api/info}; yt-dlp's field names).
     *
     * @param stage told each {@code stage} line's message, on this thread; may be null
     */
    public JsonObject info(String videoId, Consumer<String> stage) throws YoutubeServiceException, InterruptedException {
        Endpoint e = require();
        URI uri = uri(e, "/api/info?vid=" + checkId(videoId) + "&stream=true");
        Duration timeout = infoTimeout;
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpResponse<InputStream> r = open(e, uri, timeout);
        try (Watchdog dog = new Watchdog(r.body(), deadline, 0)) {
            requireOk(r, dog);
            long total = 0;
            while (true) {
                byte[] raw = readLine(r.body(), LINE_MAX_BYTES, dog);
                if (raw == null) {
                    throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                            "The YouTube service's lookup answer ended without a result");
                }
                total += raw.length;
                if (total > INFO_MAX_BYTES) {
                    throw tooLarge();
                }
                JsonObject line = jsonLine(raw);
                if (line == null) {
                    continue;
                }
                String type = string(line, "type");
                if ("stage".equals(type)) {
                    String message = string(line, "message");
                    if (stage != null && message != null) {
                        try {
                            stage.accept(message);
                        } catch (RuntimeException ignored) {
                            // feedback only
                        }
                    }
                } else if ("result".equals(type)) {
                    line.remove("type");
                    return line;
                } else if ("error".equals(type)) {
                    throw youtubeError(line, -1);
                }
                // keepalive and anything unknown: ignored
            }
        } catch (IOException ex) {
            throw transport(ex, uri);
        }
    }

    /** A YouTube search ({@code /api/search}, live and upcoming streams left out): its {@code results}. */
    public JsonObject search(String query, int count) throws YoutubeServiceException, InterruptedException {
        Endpoint e = require();
        int n = Math.max(1, Math.min(50, count));
        URI uri = uri(e, "/api/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&n=" + n
                + "&exclude_live=true");
        JsonElement body = getJson(e, uri, searchTimeout, SEARCH_MAX_BYTES, true);
        if (!body.isJsonObject()) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                    "The YouTube service's search answer isn't a JSON object");
        }
        return body.getAsJsonObject();
    }

    // ------------------------------------------------------------------ download

    /**
     * Has the service download the video and waits until it is done.
     *
     * @param quality  the height ({@code 1080})
     * @param progress the job's progress, 0..1
     * @param update   each new state of the service's job ({@code status}, {@code progress},
     *                 {@code title}, ...), for the job log; may be null
     * @return the {@code file_url} to {@link #fetchFile}
     */
    public String download(String videoId, int quality, DoubleConsumer progress, Consumer<JsonObject> update,
                           Duration idleTimeout) throws YoutubeServiceException, InterruptedException {
        Endpoint e = require();
        URI uri = uri(e, "/api/video?vid=" + checkId(videoId) + "&quality=" + quality + "&download=true&stream=true");
        HttpResponse<InputStream> r = open(e, uri, idleTimeout);
        try (Watchdog dog = new Watchdog(r.body(), Long.MAX_VALUE, idleTimeout.toNanos())) {
            requireOk(r, dog);
            String lastState = null;
            while (true) {
                byte[] raw = readLine(r.body(), LINE_MAX_BYTES, dog);
                if (raw == null) {
                    throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                            "The YouTube service's download answer ended before the download finished");
                }
                JsonObject line = jsonLine(raw);
                if (line == null) {
                    continue;
                }
                String status = string(line, "status");
                double pct = number(line, "progress");
                String state = status + " " + pct;
                // the service repeats an unchanged state every 15 s; only a change (or waiting in its queue) counts
                if (!state.equals(lastState) || "queued".equals(status)) {
                    dog.touch();
                }
                if (!state.equals(lastState)) {
                    lastState = state;
                    if (pct >= 0) {
                        progress.accept(Math.max(0, Math.min(1, pct / 100)));
                    }
                    if (update != null) {
                        update.accept(line);
                    }
                }
                if ("done".equals(status)) {
                    String file = string(line, "file_url");
                    if (file == null) {
                        throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                                "The YouTube service finished the download but named no file");
                    }
                    return file;
                }
                if ("error".equals(status)) {
                    throw youtubeError(line, -1);
                }
                if ("not_downloaded".equals(status)) {
                    throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                            "The YouTube service didn't start the download");
                }
            }
        } catch (IOException ex) {
            throw transport(ex, uri);
        }
    }

    /**
     * Streams {@code fileUrl} (from {@link #download}; relative to the service URL, or an absolute URL
     * on the service itself) into {@code target} (replaced when complete).
     *
     * @param bytes told the bytes so far; may be null
     * @return the file's size
     */
    public long fetchFile(String fileUrl, Path target, LongConsumer bytes, Duration idleTimeout)
            throws YoutubeServiceException, InterruptedException, IOException {
        Endpoint e = require();
        URI uri = fileUri(e, fileUrl);
        HttpResponse<InputStream> r = open(e, uri, idleTimeout);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (Watchdog dog = new Watchdog(r.body(), Long.MAX_VALUE, idleTimeout.toNanos())) {
            requireOk(r, dog);
            long total = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[256 * 1024];
                while (true) {
                    int n;
                    try {
                        n = r.body().read(buf);
                    } catch (IOException ex) {
                        throw dog.check(ex);
                    }
                    if (n < 0) {
                        break;
                    }
                    dog.touch();
                    out.write(buf, 0, n);
                    total += n;
                    if (bytes != null) {
                        bytes.accept(total);
                    }
                }
            }
            if (total == 0) {
                throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                        "The YouTube service sent an empty file");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return total;
        } catch (YoutubeServiceException ex) {
            Files.deleteIfExists(part);
            throw ex;
        } catch (IOException ex) {
            Files.deleteIfExists(part);
            if (ex instanceof TransportFailure) {
                throw transport(ex, uri);
            }
            throw ex; // writing the file
        }
    }

    /** The {@code file_url} as a URI on the service: relative to its base URL, or an absolute URL on the same origin. */
    static URI fileUri(Endpoint e, String fileUrl) throws YoutubeServiceException {
        URI base = URI.create(e.url());
        try {
            if (fileUrl.startsWith("/") && !fileUrl.startsWith("//")) {
                return URI.create(stripSlash(e.url()) + fileUrl);
            }
            URI u = new URI(fileUrl);
            if (u.isAbsolute() && u.getRawUserInfo() == null && base.getScheme().equalsIgnoreCase(u.getScheme())
                    && base.getHost() != null && base.getHost().equalsIgnoreCase(u.getHost())
                    && port(base) == port(u)) {
                return u;
            }
        } catch (java.net.URISyntaxException | IllegalArgumentException ex) {
            // refused below
        }
        throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                "The YouTube service named a file somewhere else: " + redact(fileUrl));
    }

    private static int port(URI u) {
        return u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    // ------------------------------------------------------------------ cover

    /**
     * Writes the video's cover to {@code dir/<baseName>.<jpg|webp|png>}: the info's {@code thumbnail}
     * when it is an allowed URL ({@link #allowedThumbnail}), else (or if that fails)
     * {@code https://i.ytimg.com/vi/<id>/hqdefault.jpg}. Never throws for a missing cover.
     *
     * @param log one line per step, for the job log
     * @return the file, or null if there is no cover
     */
    public Path cover(String videoId, Path dir, String baseName, Consumer<String> log) throws InterruptedException {
        String id = checkId(videoId);
        String thumbnail = null;
        try {
            thumbnail = string(info(id, null), "thumbnail");
        } catch (YoutubeServiceException e) {
            log.accept("no thumbnail URL from the YouTube service (" + e.getMessage() + ")");
        }
        if (thumbnail != null) {
            URI allowed = allowedThumbnail(thumbnail);
            if (allowed == null) {
                log.accept("thumbnail refused (not an allowed image host): " + redact(thumbnail));
            } else {
                Path p = saveImage(allowed, dir, baseName, log);
                if (p != null) {
                    return p;
                }
            }
        }
        return saveImage(URI.create(fallbackThumbnail(id)), dir, baseName, log);
    }

    private Path saveImage(URI uri, Path dir, String baseName, Consumer<String> log) throws InterruptedException {
        byte[] data;
        try {
            data = images.fetch(uri, THUMBNAIL_MAX_BYTES);
        } catch (IOException e) {
            log.accept("thumbnail " + uri + " failed: " + e.getMessage());
            return null;
        }
        String ext = imageExtension(data);
        if (ext == null) {
            log.accept("thumbnail " + uri + " isn't a JPEG, WebP or PNG image");
            return null;
        }
        try {
            Path p = dir.resolve(baseName + "." + ext);
            Files.write(p, data);
            log.accept("thumbnail " + uri + " (" + data.length + " bytes)");
            return p;
        } catch (IOException e) {
            log.accept("could not save the thumbnail: " + e.getMessage());
            return null;
        }
    }

    /** {@code jpg}, {@code webp} or {@code png} from the image's first bytes, else null. */
    static String imageExtension(byte[] d) {
        if (d.length > 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E'
                && d[10] == 'B' && d[11] == 'P') {
            return "webp";
        }
        if (d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        return null;
    }

    /** YouTube's own thumbnail of {@code videoId}. */
    public static String fallbackThumbnail(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    /**
     * {@code url} when it is an {@code https} URL on {@code ytimg.com}, {@code *.ytimg.com} or
     * {@code *.googleusercontent.com}, without user info or another port; else null.
     */
    public static URI allowedThumbnail(String url) {
        if (url == null || url.length() > 2048) {
            return null;
        }
        try {
            URI u = new URI(url);
            String host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
            if ("https".equalsIgnoreCase(u.getScheme()) && host != null && u.getRawUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && (host.equals("ytimg.com") || host.endsWith(".ytimg.com")
                    || host.endsWith(".googleusercontent.com"))) {
                return u;
            }
        } catch (java.net.URISyntaxException e) {
            // refused
        }
        return null;
    }

    private static byte[] fetchImage(URI uri, int maxBytes) throws IOException, InterruptedException {
        if (allowedThumbnail(uri.toString()) == null) {
            throw new IOException("not an allowed image URL");
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode());
            }
            return readAll(in, maxBytes);
        }
    }

    // ------------------------------------------------------------------ admin

    /** {@code GET /api/status} (needs no token): the active toolchain, providers, update state. */
    public JsonObject status() throws YoutubeServiceException, InterruptedException {
        Endpoint e = require();
        JsonElement body = getJson(e, uri(e, "/api/status"), ADMIN_TIMEOUT, ADMIN_MAX_BYTES, false);
        if (!body.isJsonObject()) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                    "The YouTube service's status isn't a JSON object");
        }
        return body.getAsJsonObject();
    }

    /** {@code POST /api/update}: {@code {"started", "reason"?}}. */
    public JsonObject startUpdate() throws YoutubeServiceException, InterruptedException {
        Endpoint e = require();
        URI uri = uri(e, "/api/update");
        HttpRequest req = request(e, uri, ADMIN_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<InputStream> r = send(req, uri);
        try (Watchdog dog = new Watchdog(r.body(), System.nanoTime() + ADMIN_TIMEOUT.toNanos(), 0)) {
            requireOk(r, dog);
            JsonElement body = parse(readAll(r.body(), ADMIN_MAX_BYTES, dog));
            if (!body.isJsonObject()) {
                throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                        "The YouTube service's update answer isn't a JSON object");
            }
            return body.getAsJsonObject();
        } catch (IOException ex) {
            throw transport(ex, uri);
        }
    }

    /** {@code GET /api/status/update-log}: the last update's summary and log, or JSON null if there was none. */
    public JsonElement updateLog() throws YoutubeServiceException, InterruptedException {
        Endpoint e = require();
        return getJson(e, uri(e, "/api/status/update-log"), ADMIN_TIMEOUT, ADMIN_MAX_BYTES, false);
    }

    /**
     * Whether {@code e} (not necessarily the stored endpoint) answers and accepts its token: an
     * authenticated call for a job that doesn't exist, which answers 404 when the token is accepted.
     *
     * @return the HTTP status of that call
     * @throws YoutubeServiceException {@code UNREACHABLE} or {@code TIMEOUT}
     */
    public static int checkToken(Endpoint e) throws YoutubeServiceException, InterruptedException {
        if (!e.configured()) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.NOT_CONFIGURED, "No service URL is set");
        }
        URI uri = uri(e, "/api/jobs/" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        HttpResponse<InputStream> r = send(request(e, uri, ADMIN_TIMEOUT).GET().build(), uri);
        try (InputStream in = r.body()) {
            in.readNBytes(64 * 1024);
        } catch (IOException ignored) {
            // only the status matters
        }
        return r.statusCode();
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private Endpoint require() throws YoutubeServiceException {
        Endpoint e = current();
        if (!e.configured()) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.NOT_CONFIGURED,
                    "The YouTube service isn't set up (youtube.serviceUrl)");
        }
        return e;
    }

    private static String checkId(String videoId) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("Not a YouTube video ID: " + videoId);
        }
        return videoId;
    }

    private static String stripSlash(String url) {
        String s = url.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static URI uri(Endpoint e, String pathAndQuery) throws YoutubeServiceException {
        try {
            return URI.create(stripSlash(e.url()) + pathAndQuery);
        } catch (IllegalArgumentException ex) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.NOT_CONFIGURED,
                    "The YouTube service URL isn't valid");
        }
    }

    private static HttpRequest.Builder request(Endpoint e, URI uri, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json, application/x-ndjson");
        if (e.hasToken()) {
            b.header("Authorization", "Bearer " + e.token());
        }
        return b;
    }

    private static HttpResponse<InputStream> open(Endpoint e, URI uri, Duration timeout)
            throws YoutubeServiceException, InterruptedException {
        return send(request(e, uri, timeout).GET().build(), uri);
    }

    private static HttpResponse<InputStream> send(HttpRequest req, URI uri)
            throws YoutubeServiceException, InterruptedException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException ex) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.UNREACHABLE,
                    "The YouTube service at " + redact(uri.toString()) + " didn't accept the connection in time");
        } catch (HttpTimeoutException ex) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.TIMEOUT,
                    "The YouTube service at " + redact(uri.toString()) + " didn't answer in time");
        } catch (IOException ex) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.UNREACHABLE,
                    "The YouTube service at " + redact(uri.toString()) + " can't be reached: " + describe(ex));
        }
    }

    private static JsonElement getJson(Endpoint e, URI uri, Duration timeout, int maxBytes, boolean youtubeErrors)
            throws YoutubeServiceException, InterruptedException {
        HttpResponse<InputStream> r = open(e, uri, timeout);
        try (Watchdog dog = new Watchdog(r.body(), System.nanoTime() + timeout.toNanos(), 0)) {
            if (r.statusCode() != 200 && youtubeErrors && r.statusCode() != 401) {
                // a YouTube error comes back as {"error_type", "error"} with 403/404/502
                byte[] body = readAll(r.body(), 64 * 1024, dog);
                JsonObject o = jsonLine(body);
                if (o != null && string(o, "error_type") != null) {
                    throw youtubeError(o, r.statusCode());
                }
                throw status(r.statusCode(), uri);
            }
            requireOk(r, dog);
            return parse(readAll(r.body(), maxBytes, dog));
        } catch (IOException ex) {
            throw transport(ex, uri);
        }
    }

    private static void requireOk(HttpResponse<InputStream> r, Watchdog dog) throws YoutubeServiceException {
        if (r.statusCode() != 200) {
            throw status(r.statusCode(), r.uri());
        }
    }

    private static YoutubeServiceException status(int code, URI uri) {
        if (code == 401) {
            return new YoutubeServiceException(YoutubeServiceException.Kind.UNAUTHORIZED,
                    "The YouTube service refused the token (HTTP 401)", null, code);
        }
        return new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                "The YouTube service answered HTTP " + code + " for " + redact(uri.toString()), null, code);
    }

    private static YoutubeServiceException youtubeError(JsonObject o, int httpStatus) {
        String type = string(o, "error_type");
        String error = string(o, "error");
        return new YoutubeServiceException(YoutubeServiceException.Kind.YOUTUBE,
                error == null || error.isBlank() ? "YouTube refused" : error.strip(),
                type == null ? "other" : type, httpStatus);
    }

    private static YoutubeServiceException tooLarge() {
        return new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                "The YouTube service's answer is too large");
    }

    private static YoutubeServiceException transport(IOException ex, URI uri) throws InterruptedException {
        boolean timedOut = ex instanceof TransportFailure t && t.timedOut;
        if (!timedOut && (causedByInterrupt(ex) || Thread.currentThread().isInterrupted())) {
            throw new InterruptedException("cancelled");
        }
        if (ex instanceof TransportFailure t && t.timedOut) {
            return new YoutubeServiceException(YoutubeServiceException.Kind.TIMEOUT,
                    "The YouTube service at " + redact(uri.toString()) + " didn't answer in time");
        }
        if (ex instanceof TransportFailure t && t.tooLarge) {
            return tooLarge();
        }
        return new YoutubeServiceException(YoutubeServiceException.Kind.UNREACHABLE,
                "The connection to the YouTube service broke: " + describe(ex.getCause() instanceof IOException c ? c : ex));
    }

    private static boolean causedByInterrupt(Throwable t) {
        for (int i = 0; t != null && i < 5; i++, t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(IOException ex) {
        if (ex instanceof ConnectException) {
            return "connection refused";
        }
        String m = ex.getMessage();
        return m == null || m.isBlank() ? ex.getClass().getSimpleName() : m;
    }

    /** {@code url} with any {@code token=} parameter's value removed, for logs. */
    public static String redact(String url) {
        return url == null ? null : TOKEN_PARAM.matcher(url).replaceAll("$1…");
    }

    private static JsonElement parse(byte[] body) throws YoutubeServiceException {
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new YoutubeServiceException(YoutubeServiceException.Kind.BAD_ANSWER,
                    "The YouTube service's answer isn't JSON");
        }
    }

    /** One JSON Lines line as an object, or null (blank, not an object, broken). */
    private static JsonObject jsonLine(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8).strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : -1;
    }

    /** The next line without its {@code \n}, or null at the end; a line over {@code max} is an error. */
    private static byte[] readLine(InputStream in, int max, Watchdog dog) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b;
            try {
                b = in.read();
            } catch (IOException ex) {
                throw dog.check(ex);
            }
            if (b < 0) {
                return line.size() == 0 ? null : line.toByteArray();
            }
            if (b == '\n') {
                return line.toByteArray();
            }
            if (line.size() >= max) {
                TransportFailure f = new TransportFailure("line too long", null);
                f.tooLarge = true;
                throw f;
            }
            line.write(b);
        }
    }

    private static byte[] readAll(InputStream in, int max, Watchdog dog) throws IOException {
        try {
            return readAll(in, max);
        } catch (TransportFailure f) {
            throw f;
        } catch (IOException ex) {
            throw dog.check(ex);
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        byte[] data = in.readNBytes(max + 1);
        if (data.length > max) {
            TransportFailure f = new TransportFailure("answer too large", null);
            f.tooLarge = true;
            throw f;
        }
        return data;
    }

    /** A read that failed: timed out (the watchdog closed it), too large, or the connection broke. */
    static final class TransportFailure extends IOException {
        boolean timedOut;
        boolean tooLarge;

        TransportFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Ends a response's reading when its deadline passes or nothing happened for {@code idleNanos}:
     * closes the stream and interrupts the reading thread (a blocked read on the JDK's stream may not
     * notice a close alone). Closing the watchdog closes the stream too (also on cancel), which
     * drops the connection.
     */
    static final class Watchdog implements AutoCloseable {
        private final InputStream stream;
        private final long deadline;
        private final long idleNanos;
        private final Thread reader = Thread.currentThread();
        private final Thread thread;
        private volatile long lastActivity = System.nanoTime();
        private boolean done;
        private volatile boolean fired;

        Watchdog(InputStream stream, long deadline, long idleNanos) {
            this.stream = stream;
            this.deadline = deadline;
            this.idleNanos = idleNanos;
            this.thread = Thread.ofVirtual().name("youtube-service-watchdog").start(this::watch);
        }

        void touch() {
            lastActivity = System.nanoTime();
        }

        private void watch() {
            try {
                while (true) {
                    long now = System.nanoTime();
                    long until = deadline;
                    if (idleNanos > 0) {
                        until = Math.min(until, lastActivity + idleNanos);
                    }
                    if (now - until >= 0) {
                        break;
                    }
                    long wait = Math.min(until - now, 1_000_000_000L);
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.max(1_000_000L, wait));
                }
            } catch (InterruptedException e) {
                return; // closed
            }
            synchronized (this) {
                if (done) {
                    return;
                }
                fired = true;
                reader.interrupt();
            }
            try {
                stream.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }

        /** A read's exception, marked as a timeout when this watchdog ended it. */
        TransportFailure check(IOException ex) {
            TransportFailure f = ex instanceof TransportFailure t ? t : new TransportFailure(ex.getMessage(), ex);
            if (fired) {
                f.timedOut = true;
            }
            return f;
        }

        @Override
        public void close() {
            synchronized (this) {
                done = true;
            }
            thread.interrupt();
            if (fired) {
                Thread.interrupted(); // the watchdog's own interrupt, not a cancel
            }
            try {
                stream.close();
            } catch (IOException ignored) {
                // done with it
            }
        }
    }

}
