package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.PlainHttpRedirect;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code http.insecureBaseUrl} end to end ({@link PlainHttpRedirect} with {@link PlainHttpStreams}):
 * plain-HTTP requests (direct, or {@code X-Forwarded-Proto: http} from a trusted proxy) get a
 * {@code 301} to the same path and query on the HTTPS URL, except stream URLs (public and unlisted
 * rooms' slugs, private rooms' tokens); an untrusted client's {@code X-Forwarded-Proto} is ignored. The routes are stand-ins that answer with the start of each output's normal body.
 */
class PlainHttpRedirectTest {
    static final String TOKEN = "Ab3_dEf-GhIjKlMnOpQrSt"; // a private room's token (not slug-shaped)

    @TempDir
    Path dir;

    WebServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    static Router router() {
        Router router = new Router();
        router.get("/", page("<!doctype html>"));
        router.get("/rooms/{room}", page("<!doctype html>"));
        router.get("/api/me", (rq, rs) -> rs.json(Map.of("ok", true)));
        router.post("/api/rooms", (rq, rs) -> rs.json(201, Map.of("ok", true)));
        router.get("/media/{id}/{file}", page("media"));
        router.get("/ws", page("ws"));
        router.get("/live/{file}", (rq, rs) -> {
            String f = rq.pathParam("file");
            rs.send(200, f.endsWith(".mp3") ? "audio/mpeg" : "video/mp2t", f.endsWith(".mp3") ? "ID3" : "G@");
        });
        router.get("/live/{token}/index.m3u8", page("#EXTM3U"));
        router.get("/live/{token}/seg/{file}", page("G@"));
        return router;
    }

    static io.siggi.togethertunes.http.Handler page(String body) {
        return (rq, rs) -> rs.send(200, Response.TEXT, body);
    }

    /** "club" and "hidden" are public/unlisted; "secret" is a private room's slug, {@link #TOKEN} its token. */
    static PlainHttpStreams.StreamKeys slugs() {
        return key -> key.equals("club") || key.equals("hidden") || key.equals(TOKEN);
    }

    void start(boolean redirect, String bind, List<String> trustedProxies) throws IOException {
        Router router = router();
        if (redirect) {
            router.filter(new PlainHttpRedirect("https://tunes.example", PlainHttpStreams.exemption(slugs())));
        }
        server = WebServer.start(bind, 0, router, dir.toFile(), trustedProxies);
    }

    record Reply(int status, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** One request on its own connection, raw (so the test controls every header). */
    Reply send(String host, String method, String target, String... headers) throws IOException {
        try (Socket s = new Socket(host, server.port())) {
            s.setSoTimeout(10_000);
            StringBuilder req = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: tunes.example\r\n"
                    + "Connection: close\r\n");
            for (String h : headers) {
                req.append(h).append("\r\n");
            }
            if (method.equals("POST")) {
                req.append("Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
            } else {
                req.append("\r\n");
            }
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            String status = line(in);
            Map<String, String> hs = new LinkedHashMap<>();
            for (String l = line(in); !l.isEmpty(); l = line(in)) {
                int colon = l.indexOf(':');
                hs.put(l.substring(0, colon).trim().toLowerCase(Locale.ROOT), l.substring(colon + 1).trim());
            }
            String body = "";
            String length = hs.get("content-length");
            if (!method.equals("HEAD") && length != null) {
                body = new String(in.readNBytes(Integer.parseInt(length)), StandardCharsets.UTF_8);
            }
            return new Reply(Integer.parseInt(status.split(" ")[1]), hs, body);
        }
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') {
                b.write(c);
            }
        }
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    static void assertRedirect(Reply r, String target) {
        assertEquals(301, r.status(), r.toString());
        assertEquals("https://tunes.example" + target, r.header("Location"));
        assertEquals("", r.body());
        assertEquals("0", r.header("Content-Length"));
    }

    static final List<String> REDIRECTED = List.of("/", "/rooms/club?mode=remote", "/api/me?x=1&y=%20z",
            "/media/0190/cover.jpg", "/nope/at/all", "/live/club.txt", "/live/club/other",
            "/live/" + TOKEN + "/other", "/live/Zz9_wrongTokenAbcdefgh.ts", "/live/secret.ts",
            "/live/secret/index.m3u8");

    @Test
    void plainHttpRedirectsEverythingButStreamUrls() throws Exception {
        start(true, "127.0.0.1", List.of());
        for (String forwarded : new String[] {null, "X-Forwarded-Proto: http"}) {
            String[] h = forwarded == null ? new String[0] : new String[] {forwarded};
            for (String target : REDIRECTED) {
                assertRedirect(send("127.0.0.1", "GET", target, h), target);
            }
            assertRedirect(send("127.0.0.1", "HEAD", "/api/me", h), "/api/me");
            assertRedirect(send("127.0.0.1", "POST", "/api/rooms", h), "/api/rooms");
            // A WebSocket upgrade
            String[] upgrade = concat(h, "Upgrade: websocket", "Sec-WebSocket-Version: 13",
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Origin: https://tunes.example");
            assertRedirect(send("127.0.0.1", "GET", "/ws?room=club", upgrade), "/ws?room=club");
            // Stream URLs of public and unlisted rooms: served as normal, GET and HEAD
            assertServed(send("127.0.0.1", "GET", "/live/club.mp3", h), "ID3");
            assertServed(send("127.0.0.1", "GET", "/live/club.ts", h), "G@");
            assertServed(send("127.0.0.1", "GET", "/live/hidden.ts?x=1", h), "G@");
            assertServed(send("127.0.0.1", "GET", "/live/club/index.m3u8", h), "#EXTM3U");
            assertServed(send("127.0.0.1", "GET", "/live/club/seg/123.ts", h), "G@");
            assertEquals(200, send("127.0.0.1", "HEAD", "/live/club.mp3", h).status());
            // Private rooms' token URLs: served too (the stream endpoint records the cleartext use)
            assertServed(send("127.0.0.1", "GET", "/live/" + TOKEN + ".ts", h), "G@");
            assertServed(send("127.0.0.1", "GET", "/live/" + TOKEN + ".mp3", h), "ID3");
            assertServed(send("127.0.0.1", "GET", "/live/" + TOKEN + "/index.m3u8", h), "#EXTM3U");
            assertServed(send("127.0.0.1", "GET", "/live/" + TOKEN + "/seg/12.ts", h), "G@");
            assertEquals(200, send("127.0.0.1", "HEAD", "/live/" + TOKEN + ".mp3", h).status());
            // Other methods on a stream URL redirect
            assertRedirect(send("127.0.0.1", "POST", "/live/club.mp3", h), "/live/club.mp3");
        }
    }

    static void assertServed(Reply r, String bodyStart) {
        assertEquals(200, r.status(), r.toString());
        assertNull(r.header("Location"));
        assertTrue(r.body().startsWith(bodyStart), r.body());
    }

    static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void httpsFromATrustedProxyIsServed() throws Exception {
        start(true, "127.0.0.1", List.of());
        for (String target : REDIRECTED) {
            if (target.startsWith("/live/") || target.equals("/nope/at/all")) {
                continue; // stand-in routes: unknown keys aren't answered differently here
            }
            Reply r = send("127.0.0.1", "GET", target, "X-Forwarded-Proto: https");
            assertEquals(200, r.status(), target + " " + r);
        }
        assertEquals(404, send("127.0.0.1", "GET", "/nope/at/all", "X-Forwarded-Proto: https").status());
        assertServed(send("127.0.0.1", "GET", "/live/" + TOKEN + ".ts", "X-Forwarded-Proto: https"), "G@");
    }

    @Test
    void anUntrustedClientsForwardedProtoIsIgnored() throws Exception {
        String lan = nonLoopbackAddress();
        Assumptions.assumeTrue(lan != null, "no non-loopback IPv4 address to connect from");
        start(true, "0.0.0.0", List.of()); // trusts no proxies; only loopback is trusted by SiggiHTTP itself
        assertRedirect(send(lan, "GET", "/api/me", "X-Forwarded-Proto: https"), "/api/me");
        assertRedirect(send(lan, "GET", "/live/secret.ts", "X-Forwarded-Proto: https"), "/live/secret.ts");
        assertServed(send(lan, "GET", "/live/club.ts", "X-Forwarded-Proto: https"), "G@");
    }

    @Test
    void featureOffNeverRedirects() throws Exception {
        start(false, "127.0.0.1", List.of());
        assertEquals(200, send("127.0.0.1", "GET", "/api/me").status());
        assertEquals(200, send("127.0.0.1", "GET", "/rooms/club", "X-Forwarded-Proto: http").status());
        assertServed(send("127.0.0.1", "GET", "/live/" + TOKEN + ".ts"), "G@");
    }

    @Test
    void anHttpPublicUrlNeverRedirects() throws Exception {
        // Main installs the redirect only when Config.redirectPlainHttp() (see ConfigLoaderTest#insecureBaseUrl);
        // the redirect itself refuses a plain-HTTP target.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PlainHttpRedirect("http://localhost:8080", PlainHttpStreams.exemption(slugs())));
    }

    @Test
    void streamKeys() {
        assertEquals("club", PlainHttpStreams.streamKey("/live/club.ts"));
        assertEquals("club", PlainHttpStreams.streamKey("/live/club.mp3"));
        assertEquals("club", PlainHttpStreams.streamKey("/live/club/index.m3u8"));
        assertEquals("club", PlainHttpStreams.streamKey("/live/club/seg/5.ts"));
        for (String p : List.of("/live/club", "/live/club.m3u8", "/live/club/seg/", "/live/club/seg/a/b",
                "/live/club/index.m3u8/x", "/live/.ts", "/live/a b.ts", "/api/live/club.ts", "/live/club/x.ts")) {
            assertNull(PlainHttpStreams.streamKey(p), p);
        }
    }

    static String nonLoopbackAddress() throws IOException {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                    return a.getHostAddress();
                }
            }
        }
        return null;
    }
}
