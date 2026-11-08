package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Csrf}: the {@code Origin} check for state-changing requests, through a real server. */
class CsrfTest {
    /** Mixed case, non-default port and a path prefix: the origin is {@code http://tunes.example:8080}. */
    private static final String PUBLIC_BASE_URL = "http://Tunes.Example:8080/app";
    private static final String ORIGIN = "http://tunes.example:8080";

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger handled = new AtomicInteger();
    private WebServer server;

    @BeforeEach
    void setUp() throws IOException {
        Csrf csrf = new Csrf(PUBLIC_BASE_URL, List.of("sid", "gid"));
        Router router = new Router(csrf.hook());
        Handler ok = (rq, rs) -> {
            handled.incrementAndGet();
            rs.json(Map.of("ok", true));
        };
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            router.add(method, "/api/thing", ok);
        }
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
        client.close();
    }

    /** Sends {@code method /api/thing} with the given header pairs and returns the status. */
    private int send(String method, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/thing"))
                .timeout(Duration.ofSeconds(10))
                .method(method, BodyPublishers.noBody());
        if (headers.length > 0) {
            b.headers(headers);
        }
        HttpResponse<String> r = client.send(b.build(), BodyHandlers.ofString());
        if (r.statusCode() == 403) {
            assertTrue(r.body().contains("\"origin_not_allowed\""), r.body());
        }
        return r.statusCode();
    }

    @Test
    void originOfPublicBaseUrl() {
        assertEquals("http://tunes.example:8080", Csrf.originOf("http://Tunes.Example:8080/app"));
        assertEquals("https://tunes.example", Csrf.originOf("https://tunes.example:443/"));
        assertEquals("http://tunes.example", Csrf.originOf("HTTP://tunes.example:80"));
        assertEquals("http://[::1]:8080", Csrf.originOf("http://[::1]:8080"));
        assertThrows(IllegalArgumentException.class, () -> Csrf.originOf("ftp://tunes.example"));
        assertEquals(ORIGIN, new Csrf(PUBLIC_BASE_URL, List.of("sid")).origin());
        assertThrows(IllegalArgumentException.class, () -> new Csrf(PUBLIC_BASE_URL, List.of()));
    }

    @Test
    void stateChangingMethods() {
        for (String m : List.of("POST", "PUT", "PATCH", "DELETE", "post", "PROPFIND")) {
            assertTrue(Csrf.stateChanging(m), m);
        }
        for (String m : List.of("GET", "HEAD", "OPTIONS", "get")) {
            assertFalse(Csrf.stateChanging(m), m);
        }
    }

    @Test
    void cookieRequestsNeedTheMatchingOrigin() throws Exception {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            for (String cookie : List.of("sid=abc", "gid=abc", "theme=dark; gid=abc")) {
                assertEquals(200, send(method, "Cookie", cookie, "Origin", ORIGIN), method + " " + cookie);
                assertEquals(403, send(method, "Cookie", cookie), method + " " + cookie + " without Origin");
                assertEquals(403, send(method, "Cookie", cookie, "Origin", "http://evil.example"), method + " " + cookie);
            }
        }
        assertEquals(200, send("POST", "Cookie", "sid=abc", "Origin", "HTTP://TUNES.EXAMPLE:8080"));
    }

    @Test
    void lookalikeOriginsNeverMatch() throws Exception {
        for (String origin : List.of("null", "", "http://tunes.example", "https://tunes.example:8080",
                "http://tunes.example:8081", "http://tunes.example:8080.evil.example", "http://evil.tunes.example:8080",
                "http://tunes.example:8080/app", ORIGIN + ", http://evil.example")) {
            assertEquals(403, send("POST", "Cookie", "sid=abc", "Origin", origin), origin);
        }
        assertEquals(0, handled.get(), "no refused request reached the handler");
    }

    @Test
    void crossSiteWritesAreRefusedEvenWithoutCookies() throws Exception {
        // login CSRF, and IP-mode guests (the client IP is an ambient credential too)
        assertEquals(403, send("POST", "Origin", "https://evil.example"));
        assertEquals(403, send("DELETE", "Origin", "null"));
        assertEquals(0, handled.get());
    }

    @Test
    void requestsWithoutOriginOrCookiesPass() throws Exception {
        // not from a browser page riding on cookies (e.g. curl or a script)
        assertEquals(200, send("POST"));
        assertEquals(200, send("POST", "Cookie", "theme=dark"));
        assertEquals(200, send("POST", "Origin", ORIGIN));
    }

    @Test
    void safeMethodsAreNotChecked() throws Exception {
        assertEquals(200, send("GET", "Cookie", "sid=abc", "Origin", "http://evil.example"));
        assertEquals(200, send("GET", "Cookie", "sid=abc"));
        assertEquals(200, send("OPTIONS", "Cookie", "sid=abc", "Origin", "http://evil.example"));
    }

    @Test
    void bearerRequestsAreNotCookieAuthenticated() throws Exception {
        // the actor resolver ignores cookies when a bearer token is sent, and a cross-site page
        // can't add Authorization without a CORS preflight
        assertEquals(200, send("POST", "Authorization", "Bearer x", "Cookie", "sid=abc"));
        assertEquals(200, send("POST", "Authorization", "bearer x", "Cookie", "sid=abc", "Origin", "http://evil.example"));
        // other schemes don't count
        assertEquals(403, send("POST", "Authorization", "Basic eDp5", "Cookie", "sid=abc"));
    }
}
