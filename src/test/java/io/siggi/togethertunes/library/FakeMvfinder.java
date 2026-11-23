package io.siggi.togethertunes.library;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny local stand-in for MVFinder: answers every request with {@link #status} and {@link #body}
 * ({@code /search} with {@link #searchStatus} and {@link #searchBody}) (after {@link #delayMs}) and records each request's path, query and headers.
 */
final class FakeMvfinder implements AutoCloseable {
    /** The track answer from MVFinder's README. */
    static final String README_TRACK = """
            {
              "source": {
                "service": "spotify", "type": "track", "id": "6khnYqsyOTFq1SgpCi3Ucx",
                "url": "https://open.spotify.com/track/6khnYqsyOTFq1SgpCi3Ucx",
                "title": "リモコン-NT ver.- (feat. 鏡音リン&鏡音レン)",
                "artists": ["Wonderful★opportunity!", "Kagamine Rin", "Kagamine Len"],
                "album": "リモコン-NT ver.-", "durationMs": 314142, "isrc": "JP92Q2503558",
                "metadataSource": "api"
              },
              "matches": [
                {
                  "type": "song",
                  "title": "リモコン-NT ver.- - remote controller-NT ver.- (feat. Kagamine Rin&Kagamine Len)",
                  "artists": ["Wonderfulopportunity", "Kagamine Rin", "Kagamine Len"],
                  "album": "Remote Controller-NT ver.-",
                  "durationMs": 315000, "videoId": "b31SNbUxVTU",
                  "url": "https://music.youtube.com/watch?v=b31SNbUxVTU",
                  "youtubeUrl": "https://www.youtube.com/watch?v=b31SNbUxVTU",
                  "youtubeMusicUrl": "https://music.youtube.com/watch?v=b31SNbUxVTU",
                  "confidence": 1, "matchedBy": "isrc"
                },
                {
                  "type": "music_video",
                  "title": "リモコン -NT ver.-/ 鏡音リンレンNT - ワンオポ",
                  "artists": ["Wonderfulopportunity"],
                  "durationMs": 314000, "videoId": "9OBmDkSlkrQ",
                  "url": "https://www.youtube.com/watch?v=9OBmDkSlkrQ",
                  "youtubeUrl": "https://www.youtube.com/watch?v=9OBmDkSlkrQ",
                  "youtubeMusicUrl": "https://music.youtube.com/watch?v=9OBmDkSlkrQ",
                  "confidence": 0.89, "matchedBy": "search"
                }
              ]
            }
            """;
    static final String SONG_ID = "b31SNbUxVTU";
    static final String VIDEO_ID = "9OBmDkSlkrQ";

    /** One request as received. */
    record Seen(String path, String query, Map<String, List<String>> headers) {
        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private final HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String body = README_TRACK;
    volatile long delayMs;
    /** The answer to {@code /search} (default: no matches). */
    volatile String searchBody = "{\"query\":\"q\",\"matches\":[]}";
    /** The status of {@code /search}; null = {@link #status}. */
    volatile Integer searchStatus;

    FakeMvfinder() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                seen.add(new Seen(exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
                        Map.copyOf(exchange.getRequestHeaders())));
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                boolean search = "/search".equals(exchange.getRequestURI().getRawPath());
                byte[] bytes = (search ? searchBody : body).getBytes(StandardCharsets.UTF_8);
                int code = search && searchStatus != null ? searchStatus : status;
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    /** The base URL ({@code http://127.0.0.1:<port>}). */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
