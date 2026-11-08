package io.siggi.togethertunes.http;

import com.google.gson.JsonObject;
import io.siggi.simplewebsocket.WebSocketCloseInfo;
import io.siggi.simplewebsocket.WebSocketMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Test WebSocket endpoint at {@value #PATH}: sends every text or binary message straight back,
 * unchanged. Used by the test pages ({@code /test/ws-echo.html}, interactive, and
 * {@code /test/ws-auto.html}, unattended) to check WebSocket behaviour in real browsers (M0), and
 * as a smoke test of a deployment.
 *
 * <p>It goes through the same {@link WebSockets} handler as every other socket, so it has the
 * same origin check (only the origin of {@code http.publicBaseUrl}), the 64 KiB message limit
 * (a bigger message closes the socket with 1009) and the close-info log line. It needs no login.
 *
 * <p>For the unattended test, a socket opened with {@code ?tag=<tag>} has its server-side close
 * info remembered (the last {@value #MAX_REMEMBERED} tagged sockets, in memory), so the page can
 * check what the server saw when a tab was closed or reloaded: {@code GET} {@value #CLOSES_PATH}
 * {@code ?tag=<tag>}. The page posts its final report to {@value #REPORT_PATH}, which logs it.
 * Neither holds anything but the tags and close codes the test pages chose themselves.
 */
public final class EchoEndpoint {
    private static final System.Logger LOG = System.getLogger(EchoEndpoint.class.getName());

    /** Route of the echo socket. */
    public static final String PATH = "/ws/echo";

    /** Route that returns the remembered close info of a tagged socket. */
    public static final String CLOSES_PATH = "/test/ws-echo/closes";

    /** Route that logs a test page's final report. */
    public static final String REPORT_PATH = "/test/ws-echo/report";

    /** How many tagged closes are remembered. */
    static final int MAX_REMEMBERED = 500;

    /** Largest accepted report body. */
    static final int MAX_REPORT_BYTES = 256 * 1024;

    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private EchoEndpoint() {
    }

    /** The endpoint: one stateless session per socket that echoes each message. */
    public static WebSockets.Endpoint endpoint() {
        return endpoint(null);
    }

    /** As {@link #endpoint()}, remembering the close info of tagged sockets in {@code closes}. */
    static WebSockets.Endpoint endpoint(CloseLog closes) {
        return (request, ws) -> {
            String tag = request.query("tag");
            boolean remember = closes != null && tag != null && TAG.matcher(tag).matches();
            long start = System.nanoTime();
            return new WebSockets.Session() {
                @Override
                public void onMessage(WebSocketMessage message) throws Exception {
                    ws.send(message);
                }

                @Override
                public void onClose(WebSocketCloseInfo info) {
                    if (remember) {
                        closes.add(Closed.of(tag, (System.nanoTime() - start) / 1_000_000L, info));
                    }
                }
            };
        };
    }

    /** Registers the echo socket and the two test routes on {@code router}. */
    public static void register(Router router, WebSockets webSockets, Hook... hooks) {
        CloseLog closes = new CloseLog();
        router.get(PATH, webSockets.handler(endpoint(closes)), hooks);
        router.get(CLOSES_PATH, (request, response) -> {
            String tag = request.query("tag");
            if (tag == null || !TAG.matcher(tag).matches()) {
                throw HttpError.badRequest("tag must be 1-80 characters of A-Z, a-z, 0-9, _ and -");
            }
            response.header("Cache-Control", "no-store");
            response.json(Map.of("closes", closes.find(tag)));
        }, hooks);
        router.post(REPORT_PATH, (request, response) -> {
            JsonObject report = request.readJson(JsonObject.class, MAX_REPORT_BYTES);
            // Gson writes it on one line, with control characters escaped
            LOG.log(System.Logger.Level.INFO, "WebSocket test report: " + Json.GSON.toJson(report));
            response.empty(204);
        }, hooks);
    }

    /** What the server saw when a tagged socket ended (see {@link WebSocketCloseInfo}). */
    record Closed(String tag, long durationMs, int remoteCode, String remoteReason, int localCode,
                  String localReason, boolean clean, boolean initiatedByPeer, String error) {
        static Closed of(String tag, long durationMs, WebSocketCloseInfo info) {
            return new Closed(tag, durationMs, info.getRemoteStatusCode(), info.getRemoteReason(),
                    info.getLocalStatusCode(), info.getLocalReason(), info.isClean(), info.isInitiatedByPeer(),
                    info.getError() == null ? null : info.getError().toString());
        }
    }

    /** The last {@value #MAX_REMEMBERED} tagged closes. Thread-safe. */
    static final class CloseLog {
        private final Deque<Closed> entries = new ArrayDeque<>();

        synchronized void add(Closed closed) {
            if (entries.size() >= MAX_REMEMBERED) {
                entries.removeFirst();
            }
            entries.addLast(closed);
        }

        synchronized List<Closed> find(String tag) {
            List<Closed> found = new ArrayList<>();
            for (Closed closed : entries) {
                if (closed.tag().equals(tag)) {
                    found.add(closed);
                }
            }
            return found;
        }
    }
}
