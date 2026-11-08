package io.siggi.togethertunes.http;

import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketAcceptor;
import io.siggi.simplewebsocket.WebSocketCloseInfo;
import io.siggi.simplewebsocket.WebSocketMessage;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket endpoints, on SimpleWebsocket. One instance (and so one {@link WebSocketAcceptor})
 * per server, shared by every WebSocket route.
 *
 * <p>The acceptor limits messages to {@value #MAX_MESSAGE_SIZE} bytes (after permessage-deflate
 * decompression; a bigger one closes the socket with 1009), and single frames to
 * {@value #MAX_FRAME_PAYLOAD} bytes as sent, and only accepts upgrades whose
 * {@code Origin} is the origin of {@code http.publicBaseUrl}. That is the WebSocket CSRF check
 * from {@code docs/auth.md} ({@link Csrf#checkUpgrade}, run before the acceptor's own check):
 * browsers always send {@code Origin} on a WebSocket handshake, so a page on another site can't
 * open a socket that carries the user's {@code sid}/{@code gid} cookies. Requests without
 * {@code Origin} are accepted only without those cookies, since they can't come from a browser
 * page (bearer clients authenticate with their first message instead).
 *
 * <p>Each socket runs on its own virtual thread: the upgrade request's handler thread (already a
 * virtual thread, see {@link WebServer}) stays with the socket, renamed {@code ws-<n>}, and loops
 * on the blocking {@link SimpleWebsocket#read()}, handing data messages to a {@link Session}.
 * Pings are answered by SimpleWebsocket; pongs and close frames are not passed on. When the
 * socket ends, its {@link SimpleWebsocket#getCloseInfo() close info} is logged.
 */
public final class WebSockets {
    private static final System.Logger LOG = System.getLogger(WebSockets.class.getName());

    /** Largest accepted message, in bytes. */
    public static final int MAX_MESSAGE_SIZE = 65536;

    /**
     * Largest accepted frame payload, in bytes as sent (compressed, with permessage-deflate).
     * Chrome and Firefox compress every message, and deflate makes incompressible data slightly
     * bigger (64 KiB of random bytes: 65557 bytes), so a frame limit equal to
     * {@link #MAX_MESSAGE_SIZE} would refuse full-size messages. The message limit, which counts
     * decompressed bytes, still caps what we accept.
     */
    static final int MAX_FRAME_PAYLOAD = MAX_MESSAGE_SIZE + 1024;

    /** Close code for an unexpected server error (RFC 6455 "Internal Error"). */
    static final int CLOSE_INTERNAL_ERROR = 1011;

    private static final AtomicLong IDS = new AtomicLong();

    private final WebSocketAcceptor acceptor;
    private final Csrf csrf;
    private final String origin;

    /**
     * @param csrf the CSRF check; its {@link Csrf#origin() origin} (from {@code http.publicBaseUrl})
     *             is the only allowed {@code Origin}
     */
    public WebSockets(Csrf csrf) {
        this.csrf = csrf;
        this.origin = csrf.origin();
        this.acceptor = new WebSocketAcceptor()
                .setMaxMessageSize(MAX_MESSAGE_SIZE)
                // The per-frame limit defaults to 16 KiB, and browsers send a message as one
                // frame, so without this a 16-64 KiB message would close the socket with 1009.
                // It counts compressed bytes, hence the headroom (see MAX_FRAME_PAYLOAD).
                .setMaxPayloadLength(MAX_FRAME_PAYLOAD)
                .setAllowedOrigins(origin)
                .setAllowMissingOrigin(true);
    }

    /** The only {@code Origin} accepted on upgrades. */
    public String origin() {
        return origin;
    }

    /** The shared acceptor (its settings apply to every socket). */
    public WebSocketAcceptor acceptor() {
        return acceptor;
    }

    /**
     * A GET route handler that upgrades the request and runs the socket on the current virtual
     * thread until it ends. A request that isn't a WebSocket upgrade gets {@code 426} (code
     * {@code upgrade_required}); a disallowed {@code Origin}, or none with a {@code sid}/{@code gid}
     * cookie, gets {@code 403} (code {@code origin_not_allowed}). Route hooks run before the upgrade, so they can still reject
     * the request with a normal HTTP error.
     */
    public Handler handler(Endpoint endpoint) {
        return (request, response) -> {
            if (!SimpleWebsocket.isWebsocketRequest(request.raw())) {
                response.header("Upgrade", "websocket");
                throw new HttpError(426, "upgrade_required", "This endpoint only accepts WebSocket connections");
            }
            csrf.checkUpgrade(request);
            if (!acceptor.isOriginAllowed(request.raw())) {
                LOG.log(System.Logger.Level.INFO, "WebSocket upgrade on " + request.path()
                        + " refused: Origin " + quote(request.header("Origin")) + " is not " + origin);
                throw new HttpError(403, "origin_not_allowed", "Origin not allowed");
            }
            SimpleWebsocket ws;
            try {
                ws = acceptor.accept(request.raw());
            } catch (IOException e) {
                // e.g. an unsupported Sec-WebSocket-Version (the acceptor has answered 426) or the
                // client left during the handshake
                LOG.log(System.Logger.Level.DEBUG, "WebSocket upgrade on " + request.path() + " failed: " + e);
                if (response.committed()) {
                    return;
                }
                throw HttpError.badRequest("WebSocket handshake failed");
            }
            run(request, ws, endpoint);
        };
    }

    private static void run(Request request, SimpleWebsocket ws, Endpoint endpoint) {
        Thread thread = Thread.currentThread();
        String oldName = thread.getName();
        String name = "ws-" + IDS.incrementAndGet();
        thread.setName(name);
        long start = System.nanoTime();
        Session session = null;
        try {
            session = endpoint.open(request, ws);
            WebSocketMessage message;
            while ((message = ws.read()) != null) {
                if (message.isControlMessage()) {
                    continue; // pongs and the peer's close frame; read() handles both
                }
                session.onMessage(message);
            }
        } catch (IOException e) {
            // read timeout, peer gone, message too big, protocol error, or a send on a closed
            // socket: recorded in the close info, which is logged below
            LOG.log(System.Logger.Level.DEBUG, name + " ended by " + e);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, name + " on " + request.path() + " failed", e);
            closeQuietly(ws, CLOSE_INTERNAL_ERROR, "Internal error");
        } finally {
            closeQuietly(ws, thread.isInterrupted() ? SimpleWebsocket.CLOSE_GOING_AWAY : SimpleWebsocket.CLOSE_NORMAL, null);
            WebSocketCloseInfo info = ws.getCloseInfo();
            long seconds = (System.nanoTime() - start) / 1_000_000_000L;
            LOG.log(System.Logger.Level.INFO, name + " on " + request.path() + " closed after " + seconds + " s: " + info);
            if (session != null) {
                try {
                    session.onClose(info);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, name + ": onClose failed", e);
                }
            }
            thread.setName(oldName);
        }
    }

    private static void closeQuietly(SimpleWebsocket ws, int code, String reason) {
        try {
            ws.close(code, reason);
        } catch (IOException | RuntimeException ignored) {
            // already closed, or the connection is gone
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "(none)";
        }
        String v = value.length() > 200 ? value.substring(0, 200) + "..." : value;
        return '"' + v.replaceAll("\\p{Cntrl}", "?") + '"';
    }

    /** Opens a {@link Session} for each accepted socket. */
    @FunctionalInterface
    public interface Endpoint {
        /**
         * Called on the socket's thread right after the upgrade. May send messages. Throwing
         * closes the socket with 1011 (an {@link IOException}: without one).
         */
        Session open(Request request, SimpleWebsocket ws) throws Exception;
    }

    /** One socket's message handler. All calls happen on the socket's own thread, in order. */
    public interface Session {
        /**
         * A text or binary message (at most {@value #MAX_MESSAGE_SIZE} bytes). Throwing closes
         * the socket with 1011 (an {@link IOException}: without one).
         */
        void onMessage(WebSocketMessage message) throws Exception;

        /** The socket has ended. {@code info} says why. */
        default void onClose(WebSocketCloseInfo info) {
        }
    }
}
