package io.siggi.togethertunes.youtube;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A scripted YouTube service (YTSucks) for tests, on a JDK {@link HttpServer} at 127.0.0.1:
 * {@code /api/info} (stream), {@code /api/search}, {@code /api/video} (stream) and the file it names,
 * {@code /api/status}, {@code /api/update}, {@code /api/status/update-log} and {@code /api/jobs/<id>}.
 * Records every request with its {@code Authorization} header. With {@link #requiredToken} set,
 * everything but {@code /api/status} answers 401 without {@code Bearer <token>}. Thumbnails are served
 * through {@link #images} (an {@link YoutubeService.ImageFetcher} the {@link #service} uses).
 */
public final class FakeYtSucks implements AutoCloseable {
    /** One request. */
    public record Req(String method, String path, Map<String, String> query, String authorization) {
    }

    private final HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> infos = new ConcurrentHashMap<>();
    private final Map<String, String[]> infoErrors = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String[]> videoErrors = new ConcurrentHashMap<>();
    private final Map<String, byte[]> images = new ConcurrentHashMap<>();
    private final List<URI> imageRequests = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    /** Required bearer token, or null (open). */
    public volatile String requiredToken;
    /** {@code stage} messages sent before every info result. */
    public volatile List<String> stages = List.of();
    /** Delay before the info answer's result line. */
    public volatile long infoDelayMs;
    /** The search answer (JSON) and its status. */
    public volatile String searchBody = "{\"query\":\"q\",\"results\":[]}";
    public volatile int searchStatus = 200;
    public volatile long searchDelayMs;
    /** After the first progress line, the video stream sends nothing more (until closed). */
    public volatile boolean videoStalls;
    /** The progress values the video stream reports before {@code done}. */
    public volatile List<Integer> videoProgress = List.of(0, 40, 80, 100);
    /** The info result of every video without its own {@link #info} or error (raw: may be broken JSON), or null. */
    public volatile String defaultInfo;
    /** Every video without its own {@link #info} gets {@code {"title": "Video <id>", "uploader": "Chan", "duration": 200}}. */
    public volatile boolean anyVideo;
    /** Status code for {@code /api/info} instead of a stream, or 0. */
    public volatile int infoStatus;
    public volatile String statusBody = "{\"active\":{\"id\":\"tc1\",\"channel\":\"nightly\","
            + "\"pip\":{\"yt-dlp\":\"2026.11.20\",\"yt-dlp-ejs\":\"0.3.1\",\"bgutil-ytdlp-pot-provider\":\"1.2.2\"},"
            + "\"provider\":\"1.2.2\",\"deno\":\"2.5.6\",\"ffmpeg\":\"N-12345\"},"
            + "\"providers\":[{\"toolchain\":\"tc1\",\"port\":4416,\"ready\":true,\"in_use\":0}],"
            + "\"updating\":false,\"staging\":null,\"last_update\":{\"time\":\"2026-11-28T04:00:00+00:00\","
            + "\"result\":\"up_to_date\"},\"active_jobs\":0}";
    public volatile String updateBody = "{\"started\":true}";
    public volatile String updateLogBody = "{\"time\":\"2026-11-28T04:00:00+00:00\",\"result\":\"up_to_date\","
            + "\"log\":[\"stage: building\",\"result: up_to_date\"]}";

    public FakeYtSucks() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code http://127.0.0.1:<port>} */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A client of this fake without a token, its thumbnails served by {@link #image}. */
    public YoutubeService service() {
        return service(null);
    }

    public YoutubeService service(String token) {
        YoutubeService s = new YoutubeService(() -> new YoutubeService.Endpoint(url(), token));
        s.imageFetcher(this::fetchImage);
        return s;
    }

    /** The video's info result (a JSON object, without {@code type}). */
    public void info(String videoId, String json) {
        infoErrors.remove(videoId);
        infos.put(videoId, json);
    }

    public void infoError(String videoId, String errorType, String error) {
        infos.remove(videoId);
        infoErrors.put(videoId, new String[] {errorType, error});
    }

    /** The MP4 the download of {@code videoId} delivers. */
    public void video(String videoId, byte[] file) {
        videoErrors.remove(videoId);
        files.put(videoId, file);
    }

    public void videoError(String videoId, String errorType, String error) {
        files.remove(videoId);
        videoErrors.put(videoId, new String[] {errorType, error});
    }

    /** An image the {@linkplain #service service's} thumbnail fetches get for {@code url}. */
    public void image(String url, byte[] data) {
        images.put(url, data);
    }

    /** The thumbnail URLs fetched so far. */
    public List<URI> imageRequests() {
        return List.copyOf(imageRequests);
    }

    private byte[] fetchImage(URI uri, int maxBytes) throws IOException {
        imageRequests.add(uri);
        byte[] d = images.get(uri.toString());
        if (d == null) {
            throw new IOException("HTTP 404");
        }
        return d;
    }

    public List<Req> requests() {
        return List.copyOf(requests);
    }

    /** The requests to {@code path}. */
    public List<Req> requests(String path) {
        List<Req> out = new ArrayList<>();
        for (Req r : requests) {
            if (r.path().equals(path)) {
                out.add(r);
            }
        }
        return out;
    }

    public void clearRequests() {
        requests.clear();
    }

    /**
     * A search answer with these {@code [id, title, channel, durationSec, live_status?, thumbnails?]}
     * results ({@code thumbnails} as raw JSON; an empty live_status is left out).
     */
    public static String results(String[]... entries) {
        StringBuilder b = new StringBuilder("{\"query\":\"q\",\"cached\":false,\"results\":[");
        for (int i = 0; i < entries.length; i++) {
            String[] e = entries[i];
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"id\":\"").append(e[0]).append("\",\"title\":\"").append(e[1]).append("\",\"channel\":\"")
                    .append(e[2]).append("\",\"uploader\":\"").append(e[2]).append("\",\"duration\":").append(e[3])
                    .append(e.length > 4 && !e[4].isEmpty() ? ",\"live_status\":\"" + e[4] + "\"" : "")
                    .append(e.length > 5 ? ",\"thumbnails\":" + e[5] : "")
                    .append(",\"url\":\"https://www.youtube.com/watch?v=").append(e[0]).append("\"}");
        }
        return b.append("]}").toString();
    }

    /** The {@code q} of every search so far. */
    public List<String> searches() {
        List<String> out = new ArrayList<>();
        for (Req r : requests("/api/search")) {
            out.add(r.query().get("q"));
        }
        return out;
    }

    /** A tiny JPEG-looking image. */
    public static byte[] jpeg() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0};
    }

    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
    }

    // ------------------------------------------------------------------ server

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            String path = x.getRequestURI().getPath();
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            String auth = x.getRequestHeaders().getFirst("Authorization");
            requests.add(new Req(x.getRequestMethod(), path, q, auth));
            String token = requiredToken;
            if (token != null && !path.equals("/api/status") && !("Bearer " + token).equals(auth)) {
                send(x, 401, "{\"detail\":\"Missing or wrong token\"}");
                return;
            }
            if (path.equals("/api/info")) {
                info(x, q);
            } else if (path.equals("/api/search")) {
                sleep(searchDelayMs);
                send(x, searchStatus, searchBody);
            } else if (path.equals("/api/video")) {
                video(x, q);
            } else if (path.startsWith("/api/jobs/") && path.endsWith("/file")) {
                String vid = path.substring("/api/jobs/".length(), path.length() - "/file".length());
                byte[] f = files.get(vid);
                if (f == null) {
                    send(x, 404, "{\"detail\":\"No such job\"}");
                } else {
                    x.getResponseHeaders().set("Content-Type", "video/mp4");
                    x.sendResponseHeaders(200, f.length);
                    x.getResponseBody().write(f);
                }
            } else if (path.startsWith("/api/jobs/")) {
                send(x, 404, "{\"detail\":\"No such job\"}");
            } else if (path.equals("/api/status")) {
                send(x, 200, statusBody);
            } else if (path.equals("/api/update") && x.getRequestMethod().equals("POST")) {
                send(x, 200, updateBody);
            } else if (path.equals("/api/status/update-log")) {
                send(x, 200, updateLogBody);
            } else {
                send(x, 404, "{\"detail\":\"Not Found\"}");
            }
        } catch (IOException | RuntimeException e) {
            // the client went away
        }
    }

    private void info(HttpExchange x, Map<String, String> q) throws IOException {
        if (infoStatus != 0) {
            send(x, infoStatus, "{\"detail\":\"scripted\"}");
            return;
        }
        String vid = q.get("vid");
        x.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        x.sendResponseHeaders(200, 0);
        OutputStream out = x.getResponseBody();
        for (String s : stages) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "stage");
            o.addProperty("message", s);
            line(out, o.toString());
        }
        line(out, "{\"type\":\"keepalive\"}");
        sleep(infoDelayMs);
        String[] error = vid == null ? null : infoErrors.get(vid);
        String result = vid == null || error != null ? null : infos.get(vid);
        if (result == null && error == null && defaultInfo != null) {
            result = defaultInfo;
        }
        if (result == null && anyVideo && vid != null && error == null) {
            result = "{\"id\":\"" + vid + "\",\"title\":\"Video " + vid + "\",\"uploader\":\"Chan\","
                    + "\"duration\":200,\"live_status\":\"not_live\"}";
        }
        if (result != null) {
            JsonObject o;
            try {
                o = JsonParser.parseString(result).getAsJsonObject();
            } catch (RuntimeException e) {
                line(out, result); // broken on purpose
                return;
            }
            JsonObject r = new JsonObject();
            r.addProperty("type", "result");
            r.addProperty("cached", false);
            o.entrySet().forEach(e -> r.add(e.getKey(), e.getValue()));
            line(out, r.toString());
        } else {
            JsonObject o = new JsonObject();
            o.addProperty("type", "error");
            o.addProperty("error_type", error == null ? "unavailable" : error[0]);
            o.addProperty("error", error == null ? "[youtube] " + vid + ": Video unavailable" : error[1]);
            line(out, o.toString());
        }
    }

    private void video(HttpExchange x, Map<String, String> q) throws IOException {
        String vid = q.get("vid");
        x.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        x.sendResponseHeaders(200, 0);
        OutputStream out = x.getResponseBody();
        line(out, snapshot(vid, "queued", 0, null, null, null));
        if (videoStalls) {
            line(out, snapshot(vid, "downloading", 10, null, null, null));
            // the service repeats the unchanged state every 15 s; here every 200 ms
            for (int i = 0; i < 600 && closed.getCount() > 0; i++) {
                sleep(200);
                line(out, snapshot(vid, "downloading", 10, null, null, null));
            }
            return;
        }
        String[] error = videoErrors.get(vid);
        if (error != null || !files.containsKey(vid)) {
            line(out, snapshot(vid, "error", 0, error == null ? "other" : error[0],
                    error == null ? "ERROR: no such video" : error[1], null));
            return;
        }
        for (int p : videoProgress) {
            line(out, snapshot(vid, p >= 100 ? "processing" : "downloading", p, null, null, null));
        }
        line(out, snapshot(vid, "done", 100, null, null, "/api/jobs/" + vid + "/file"));
    }

    private static String snapshot(String vid, String status, double progress, String errorType, String error,
                                   String fileUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-" + vid);
        m.put("video_id", vid);
        m.put("status", status);
        m.put("progress", progress);
        m.put("title", "A title");
        m.put("error", error);
        m.put("error_type", errorType);
        m.put("file_url", fileUrl);
        return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(m);
    }

    private static void line(OutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sleep(long ms) {
        if (ms > 0) {
            try {
                closed.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            x.getResponseBody().write(b);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) {
            return m;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    /** Short timeouts for tests of them. */
    public static void shortTimeouts(YoutubeService s, Duration d) {
        s.timeouts(d, d, d);
    }
}
