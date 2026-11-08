package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Router, hooks and adapters, through a real SiggiHTTP server on a loopback port. */
class RouterTest {
    private final List<String> trace = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WebServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        client.close();
    }

    private Hook tracing(String name) {
        return (request, response, next) -> {
            trace.add(name + ">");
            next.handle(request, response);
            trace.add("<" + name);
        };
    }

    private void start(Router router) throws IOException {
        server = WebServer.start("127.0.0.1", 0, router, new File(System.getProperty("java.io.tmpdir")));
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(builder.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(req(path));
    }

    private static JsonObject json(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                response.headers().toString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).getAsJsonObject("error").get("code").getAsString(), response.body());
    }

    @Test
    void routesWithDecodedPathParamsQueryHeadersAndCookies() throws Exception {
        Router router = new Router();
        router.get("/api/items/{id}", (rq, rs) -> rs.json(Map.of(
                "id", rq.pathParam("id"),
                "q", String.valueOf(rq.query("q")),
                "header", String.valueOf(rq.header("x-test")),
                "cookie", String.valueOf(rq.cookie("sid")),
                "method", rq.method())));
        start(router);

        HttpResponse<String> r = send(req("/api/items/a%20b%C3%A9?q=x%26y").header("X-Test", "hi").header("Cookie", "sid=abc; other=1"));
        assertEquals(200, r.statusCode());
        JsonObject body = json(r);
        assertEquals("a bé", body.get("id").getAsString());
        assertEquals("x&y", body.get("q").getAsString());
        assertEquals("hi", body.get("header").getAsString());
        assertEquals("abc", body.get("cookie").getAsString());
        assertEquals("GET", body.get("method").getAsString());
    }

    @Test
    void handlersRunOnVirtualThreads() throws Exception {
        Router router = new Router((rq, rs, next) -> {
            trace.add("hook virtual=" + Thread.currentThread().isVirtual());
            next.handle(rq, rs);
        });
        router.get("/t", (rq, rs) -> rs.text(200, "virtual=" + Thread.currentThread().isVirtual()));
        start(router);
        assertEquals("virtual=true", get("/t").body());
        assertEquals(List.of("hook virtual=true"), trace);
    }

    @Test
    void manyConcurrentBlockingRequests() throws Exception {
        int n = 200;
        CountDownLatch arrived = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        Router router = new Router();
        router.get("/block", (rq, rs) -> {
            arrived.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            rs.text(200, "ok");
        });
        start(router);
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(client.sendAsync(req("/block").timeout(Duration.ofSeconds(60)).build(), BodyHandlers.ofString()));
        }
        // All n handlers block at once, which a small platform pool couldn't do.
        assertTrue(arrived.await(30, TimeUnit.SECONDS), "only " + (n - arrived.getCount()) + " handlers ran concurrently");
        release.countDown();
        for (CompletableFuture<HttpResponse<String>> f : futures) {
            assertEquals("ok", f.get(30, TimeUnit.SECONDS).body());
        }
    }

    @Test
    void hookChainRunsGlobalThenRouteHooksThenHandler() throws Exception {
        Router router = new Router(tracing("auth"), tracing("csrf"));
        router.post("/api/x", (rq, rs) -> {
            trace.add("handler");
            rs.empty(204);
        }, tracing("rate"), tracing("extra"));
        router.get("/api/y", (rq, rs) -> {
            trace.add("handler");
            rs.json(Map.of());
        });
        start(router);

        assertEquals(204, send(req("/api/x").POST(BodyPublishers.noBody())).statusCode());
        assertEquals(List.of("auth>", "csrf>", "rate>", "extra>", "handler", "<extra", "<rate", "<csrf", "<auth"), trace);

        trace.clear();
        assertEquals(200, get("/api/y").statusCode());
        assertEquals(List.of("auth>", "csrf>", "handler", "<csrf", "<auth"), trace);
    }

    @Test
    void hookCanRejectAndPassDataToTheHandler() throws Exception {
        record Actor(String name) {
        }
        Hook auth = (rq, rs, next) -> {
            String token = rq.header("Authorization");
            if (token == null) {
                throw HttpError.unauthorized("Log in first");
            }
            rq.setAttribute(Actor.class, new Actor(token));
            next.handle(rq, rs);
        };
        AtomicBoolean handled = new AtomicBoolean();
        Router router = new Router(auth);
        router.get("/api/me", (rq, rs) -> {
            handled.set(true);
            rs.json(Map.of("name", rq.attribute(Actor.class).name()));
        });
        start(router);

        HttpResponse<String> denied = get("/api/me");
        assertError(denied, 401, "unauthorized");
        assertEquals("Log in first", json(denied).getAsJsonObject("error").get("message").getAsString());
        assertFalse(handled.get());

        assertEquals("bob", json(send(req("/api/me").header("Authorization", "bob"))).get("name").getAsString());
    }

    @Test
    void rateLimitHookRefusesWith429AndRetryAfter() throws Exception {
        io.siggi.togethertunes.TestClock clock = new io.siggi.togethertunes.TestClock(1_000_000);
        RateLimiter limiter = new RateLimiter("login", 2, 5_000, clock);
        java.util.concurrent.atomic.AtomicInteger handled = new java.util.concurrent.atomic.AtomicInteger();
        Router router = new Router();
        router.post("/api/auth/login", (rq, rs) -> {
            handled.incrementAndGet();
            rs.json(Map.of("ok", true));
        }, Hooks.rateLimit(limiter));
        router.post("/api/auth/token", (rq, rs) -> rs.json(Map.of("ok", true)), Hooks.rateLimit(limiter));
        start(router);
        assertEquals(200, send(req("/api/auth/login").POST(BodyPublishers.noBody())).statusCode());
        assertEquals(200, send(req("/api/auth/token").POST(BodyPublishers.noBody())).statusCode()); // shared bucket
        HttpResponse<String> refused = send(req("/api/auth/login").POST(BodyPublishers.ofString("{}")));
        assertError(refused, 429, "rate_limited");
        assertEquals("5", refused.headers().firstValue("Retry-After").orElse(""));
        assertEquals(1, handled.get());
        clock.advance(4_001);
        assertEquals("1", send(req("/api/auth/login").POST(BodyPublishers.noBody())).headers()
                .firstValue("Retry-After").orElse(""), "rounded up to whole seconds");
        clock.advance(999);
        assertEquals(200, send(req("/api/auth/login").POST(BodyPublishers.noBody())).statusCode());
        assertEquals(2, handled.get());
        assertThrows(NullPointerException.class, () -> Hooks.rateLimit(null));
    }

    @Test
    void notFoundMethodNotAllowedOptionsAndHead() throws Exception {
        Router router = new Router();
        router.get("/api/rooms/{id}", (rq, rs) -> rs.json(Map.of("id", rq.pathParam("id"))));
        router.delete("/api/rooms/{id}", (rq, rs) -> rs.empty(204));
        start(router);

        assertError(get("/nope"), 404, "not_found");
        assertError(get("/api/rooms"), 404, "not_found");

        HttpResponse<String> put = send(req("/api/rooms/1").PUT(BodyPublishers.ofString("x")));
        assertError(put, 405, "method_not_allowed");
        assertEquals("DELETE, GET, HEAD, OPTIONS", put.headers().firstValue("Allow").orElseThrow());

        HttpResponse<String> options = send(req("/api/rooms/1").method("OPTIONS", BodyPublishers.noBody()));
        assertEquals(204, options.statusCode());
        assertEquals("DELETE, GET, HEAD, OPTIONS", options.headers().firstValue("Allow").orElseThrow());

        HttpResponse<String> head = send(req("/api/rooms/7").method("HEAD", BodyPublishers.noBody()));
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(String.valueOf("{\"id\":\"7\"}".length()), head.headers().firstValue("Content-Length").orElseThrow());
    }

    @Test
    void mostSpecificPatternWins() throws Exception {
        Router router = new Router();
        router.get("/api/rooms/{id}", (rq, rs) -> rs.text(200, "param " + rq.pathParam("id")));
        router.get("/api/rooms/mine", (rq, rs) -> rs.text(200, "literal"));
        router.get("/{path...}", (rq, rs) -> rs.text(200, "rest " + rq.pathParam("path")));
        router.post("/api/rooms/search", (rq, rs) -> rs.text(200, "post"));
        start(router);
        assertEquals("literal", get("/api/rooms/mine").body());
        assertEquals("param 42", get("/api/rooms/42").body());
        assertEquals("rest web/index.html", get("/web/index.html").body());
        // The literal POST route doesn't hide the GET parameter route.
        assertEquals("param search", get("/api/rooms/search").body());
    }

    @Test
    void duplicateRoutesAreRejected() {
        Router router = new Router();
        router.get("/a/{x}", (rq, rs) -> rs.empty(204));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{y}", (rq, rs) -> rs.empty(204)));
        assertThrows(IllegalArgumentException.class, () -> router.add("HEAD", "/b", (rq, rs) -> rs.empty(204)));
        assertThrows(IllegalArgumentException.class, () -> router.get("b", (rq, rs) -> rs.empty(204)));
        router.post("/a/{x}", (rq, rs) -> rs.empty(204));
    }

    @Test
    void exceptionsBecomeErrorResponses() throws Exception {
        Router router = new Router();
        router.get("/boom", (rq, rs) -> {
            throw new IllegalStateException("secret internals");
        });
        router.get("/teapot", (rq, rs) -> {
            throw new HttpError(409, "conflict", "Already exists");
        });
        router.get("/silent", (rq, rs) -> {
        });
        start(router);

        HttpResponse<String> boom = get("/boom");
        assertError(boom, 500, "internal_error");
        assertFalse(boom.body().contains("secret"), boom.body());
        assertError(get("/teapot"), 409, "conflict");
        assertError(get("/silent"), 404, "not_found");
        // The connection survives handled errors.
        assertEquals(404, get("/silent").statusCode());
    }

    record Body(String name, int count) {
    }

    @Test
    void readJson() throws Exception {
        Router router = new Router();
        router.post("/json", (rq, rs) -> {
            Body body = rq.readJson(Body.class);
            rs.json(201, Map.of("name", body.name(), "count", body.count()));
        });
        router.post("/small", (rq, rs) -> {
            rq.readJson(Body.class, 16);
            rs.empty(204);
        });
        start(router);

        HttpResponse<String> ok = send(req("/json").header("Content-Type", "application/json; charset=utf-8")
                .POST(BodyPublishers.ofString("{\"name\":\"ö\",\"count\":3}")));
        assertEquals(201, ok.statusCode());
        assertEquals("ö", json(ok).get("name").getAsString());
        assertEquals(3, json(ok).get("count").getAsInt());

        assertError(send(req("/json").header("Content-Type", "text/plain").POST(BodyPublishers.ofString("{}"))), 415, "unsupported_media_type");
        assertError(send(req("/json").header("Content-Type", "application/json").POST(BodyPublishers.ofString("{nope"))), 400, "invalid_json");
        assertError(send(req("/json").header("Content-Type", "application/json").POST(BodyPublishers.ofString("{\"count\":\"x\"}"))), 400, "invalid_json");
        assertError(send(req("/json").header("Content-Type", "application/json").POST(BodyPublishers.noBody())), 400, "invalid_json");
        assertError(send(req("/small").header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"name\":\"" + "x".repeat(100) + "\"}"))), 413, "payload_too_large");
    }

    @Test
    void closeStopsTheServer() throws Exception {
        Router router = new Router();
        router.get("/", (rq, rs) -> rs.text(200, "hi"));
        start(router);
        assertEquals("hi", get("/").body());
        server.close();
        server.join();
        assertThrows(IOException.class, () -> get("/"));
    }
}
