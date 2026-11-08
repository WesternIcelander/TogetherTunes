package io.siggi.togethertunes.http;

/**
 * Test WebSocket endpoint at {@value #PATH}: sends every text or binary message straight back,
 * unchanged. Used by the test page ({@code /test/ws-echo.html}) to check WebSocket behaviour in
 * real browsers (M0), and as a smoke test of a deployment.
 *
 * <p>It goes through the same {@link WebSockets} handler as every other socket, so it has the
 * same origin check (only the origin of {@code http.publicBaseUrl}), the 64 KiB message limit
 * (a bigger message closes the socket with 1009) and the close-info log line. It keeps no state
 * and needs no login.
 */
public final class EchoEndpoint {
    /** Route of the echo socket. */
    public static final String PATH = "/ws/echo";

    private EchoEndpoint() {
    }

    /** The endpoint: one stateless session per socket that echoes each message. */
    public static WebSockets.Endpoint endpoint() {
        return (request, ws) -> message -> ws.send(message);
    }

    /** Registers {@code GET} {@value #PATH} on {@code router}. */
    public static void register(Router router, WebSockets webSockets, Hook... hooks) {
        router.get(PATH, webSockets.handler(endpoint()), hooks);
    }
}
