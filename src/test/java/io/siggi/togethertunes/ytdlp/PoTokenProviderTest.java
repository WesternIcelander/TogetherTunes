package io.siggi.togethertunes.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** {@link PoTokenProvider}: the extractor argument, the plugin status parsing and the ping. */
@Timeout(60)
class PoTokenProviderTest {

    @Test
    void argsOnlyWhenSet() {
        assertEquals(List.of(), PoTokenProvider.args(null));
        assertEquals(List.of(), PoTokenProvider.args(""));
        assertEquals(List.of(), PoTokenProvider.args("  "));
        assertEquals(List.of("--extractor-args", "youtubepot-bgutilhttp:base_url=http://127.0.0.1:4416"),
                PoTokenProvider.args("http://127.0.0.1:4416"));
        assertEquals(List.of("--extractor-args", "youtubepot-bgutilhttp:base_url=https://pot.example/base"),
                PoTokenProvider.args(" https://pot.example/base "));
        for (String bad : List.of("http://a,b", "http://a;b", "http://a b")) {
            assertThrows(IllegalArgumentException.class, () -> PoTokenProvider.args(bad), bad);
        }
    }

    @Test
    void pluginCheckDoesNotReachYoutube() {
        List<String> args = PoTokenProvider.pluginCheckArgs();
        assertTrue(args.contains("--verbose") && args.contains("--simulate"));
        int proxy = args.indexOf("--proxy");
        assertTrue(proxy >= 0 && args.get(proxy + 1).startsWith("http://127.0.0.1:"), args.toString());
        assertEquals("--", args.get(args.size() - 2));
    }

    /** Verbose output of yt-dlp 2026.10.26 without a provider plugin. */
    static final String WITHOUT_PLUGIN = """
            [debug] yt-dlp version stable@2026.10.26 from yt-dlp/yt-dlp [c91f8de0b] (darwin_exe)
            [debug] Plugin directories: none
            [debug] Loaded 1747 extractors
            [debug] [youtube] [pot] PO Token Providers: none
            [debug] [youtube] [pot] PO Token Cache Providers: memory
            [debug] [youtube] [pot] PO Token Cache Spec Providers: webpo
            [youtube] Extracting URL: https://www.youtube.com/watch?v=jNQXAC9IVRw
            ERROR: [youtube] jNQXAC9IVRw: Unable to download API page
            """;

    /** The same with the plugin (the line from the plugin's README). */
    static final String WITH_PLUGIN = """
            [debug] Plugin directories: /home/tt/.yt-dlp/plugins/bgutil-ytdlp-pot-provider.zip/yt_dlp_plugins
            [debug] Loaded 1747 extractors
            [debug] [youtube] [pot] PO Token Providers: bgutil:http-2.0.2 (external), bgutil:script-node-2.0.2 (external), bgutil:script-deno-2.0.2 (external, unavailable)
            [debug] [youtube] [pot] PO Token Cache Providers: memory
            """;

    @Test
    void parsesTheProviderList() {
        PoTokenProvider.PluginStatus with = PoTokenProvider.parse(WITH_PLUGIN);
        assertTrue(with.listed());
        assertTrue(with.loaded());
        assertEquals(List.of(
                new PoTokenProvider.Provider("bgutil:http", "2.0.2", true, true),
                new PoTokenProvider.Provider("bgutil:script-node", "2.0.2", true, true),
                new PoTokenProvider.Provider("bgutil:script-deno", "2.0.2", true, false)), with.providers());
        assertEquals("2.0.2", with.httpProvider().version());

        PoTokenProvider.PluginStatus without = PoTokenProvider.parse(WITHOUT_PLUGIN);
        assertTrue(without.listed());
        assertFalse(without.loaded());
        assertEquals(List.of(), without.providers());
        assertNull(without.httpProvider());

        // an old yt-dlp (no provider framework) or a check that stopped early: no list at all
        PoTokenProvider.PluginStatus old = PoTokenProvider.parse("[debug] Loaded 1700 extractors\nERROR: x\n");
        assertFalse(old.listed());
        assertFalse(old.loaded());
        assertFalse(PoTokenProvider.parse(null).loaded());

        // only the script provider: nothing uses the provider URL
        PoTokenProvider.PluginStatus script = PoTokenProvider.parse(
                "[debug] [youtube] [pot] PO Token Providers: bgutil:script-node-2.0.2 (external)\r\n");
        assertTrue(script.listed());
        assertFalse(script.loaded());
        // an entry without version or status
        assertEquals(List.of(new PoTokenProvider.Provider("custom", null, false, true),
                        new PoTokenProvider.Provider("bgutil:http", "1.1.0", true, false)),
                PoTokenProvider.parse("[debug] [youtube] [pot] PO Token Providers: custom, bgutil:http-1.1.0"
                        + " (external, unavailable)").providers());
    }

    private static HttpServer stub(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (!exchange.getRequestURI().getPath().equals("/ping")) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void pingReportsTheServersVersion() throws Exception {
        HttpServer server = stub(200, "{\"server_uptime\": 12.5, \"version\": \"2.0.2\"}");
        try {
            assertEquals(new PoTokenProvider.PingResult(true, 200, "2.0.2", null),
                    PoTokenProvider.ping(base(server), Duration.ofSeconds(5)));
            assertEquals(new PoTokenProvider.PingResult(true, 200, "2.0.2", null),
                    PoTokenProvider.ping(base(server) + "/", Duration.ofSeconds(5)), "trailing slash");
        } finally {
            server.stop(0);
        }
        server = stub(200, "{\"server_uptime\": 1}");
        try {
            assertEquals(new PoTokenProvider.PingResult(true, 200, null, null),
                    PoTokenProvider.ping(base(server), Duration.ofSeconds(5)), "no version");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pingFailures() throws Exception {
        HttpServer server = stub(200, "<html>hello</html>");
        try {
            PoTokenProvider.PingResult r = PoTokenProvider.ping(base(server), Duration.ofSeconds(5));
            assertFalse(r.reachable());
            assertEquals(200, r.status());
            assertTrue(r.error().contains("isn't a PO token provider"), r.error());
            r = PoTokenProvider.ping(base(server) + "/elsewhere/x", Duration.ofSeconds(5));
            assertEquals(new PoTokenProvider.PingResult(false, 404, null,
                    "the server answered HTTP 404 instead of a PO token provider's answer"), r);
        } finally {
            server.stop(0);
        }
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        PoTokenProvider.PingResult refused = PoTokenProvider.ping("http://127.0.0.1:" + port, Duration.ofSeconds(5));
        assertFalse(refused.reachable());
        assertNull(refused.status());
        assertTrue(refused.error().contains("refused"), refused.error());
    }
}
