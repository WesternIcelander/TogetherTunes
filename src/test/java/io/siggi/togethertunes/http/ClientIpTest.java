package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigException;
import io.siggi.togethertunes.config.ConfigLoader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Client IP resolution: {@code http.trustedProxies} fed into SiggiHTTP's
 * {@link HTTPServer#isIPTrusted} via {@link WebServer#setTrustedProxies} (CIDR matching for IPv4
 * and IPv6, SiggiHTTP's default private ranges dropped), and end to end through
 * {@code X-Forwarded-For} to {@link Request#clientIp()}.
 */
class ClientIpTest {
    @TempDir
    Path dir;

    private HTTPServer serverTrusting(String... cidrs) {
        HTTPServer server = new HTTPServerBuilder().setTmpDir(dir.toFile()).build();
        WebServer.setTrustedProxies(server, List.of(cidrs));
        return server;
    }

    @Nested
    class CidrMatching {
        @Test
        void emptyListTrustsNothingNotEvenSiggiHttpsPrivateRangeDefaults() {
            HTTPServer server = serverTrusting();
            assertEquals(List.of(), server.getTrustedIPs());
            for (String ip : List.of("10.1.2.3", "172.16.0.1", "192.168.1.1", "127.0.0.1", "8.8.8.8", "::1",
                    "2001:db8::1")) {
                assertFalse(server.isIPTrusted(ip), ip);
            }
        }

        @Test
        void ipv4Slash8() {
            HTTPServer server = serverTrusting("10.0.0.0/8");
            assertTrue(server.isIPTrusted("10.0.0.0"));
            assertTrue(server.isIPTrusted("10.0.0.1"));
            assertTrue(server.isIPTrusted("10.123.45.67"));
            assertTrue(server.isIPTrusted("10.255.255.255"));
            assertFalse(server.isIPTrusted("9.255.255.255"));
            assertFalse(server.isIPTrusted("11.0.0.0"));
            assertFalse(server.isIPTrusted("192.168.1.1"));
        }

        @Test
        void ipv4PrefixNotOnAByteBoundary() {
            HTTPServer server = serverTrusting("172.16.0.0/12", "198.51.100.64/26");
            assertTrue(server.isIPTrusted("172.16.0.0"));
            assertTrue(server.isIPTrusted("172.31.255.255"));
            assertFalse(server.isIPTrusted("172.15.255.255"));
            assertFalse(server.isIPTrusted("172.32.0.0"));
            assertTrue(server.isIPTrusted("198.51.100.64"));
            assertTrue(server.isIPTrusted("198.51.100.127"));
            assertFalse(server.isIPTrusted("198.51.100.63"));
            assertFalse(server.isIPTrusted("198.51.100.128"));
        }

        @Test
        void ipv4SingleAddress() {
            HTTPServer server = serverTrusting("192.168.1.1/32");
            assertTrue(server.isIPTrusted("192.168.1.1"));
            assertFalse(server.isIPTrusted("192.168.1.0"));
            assertFalse(server.isIPTrusted("192.168.1.2"));
        }

        @Test
        void ipv4Slash31AndSlash1() {
            // the /31 lies below 128.0.0.0, so the /1 doesn't cover its neighbours
            HTTPServer server = serverTrusting("100.64.0.6/31", "128.0.0.0/1");
            assertTrue(server.isIPTrusted("100.64.0.6"));
            assertTrue(server.isIPTrusted("100.64.0.7"));
            assertFalse(server.isIPTrusted("100.64.0.5"));
            assertFalse(server.isIPTrusted("100.64.0.8"));
            assertTrue(server.isIPTrusted("128.0.0.0"));
            assertTrue(server.isIPTrusted("255.255.255.255"));
            assertFalse(server.isIPTrusted("127.255.255.255"));
        }

        @Test
        void ipv6Slash32() {
            HTTPServer server = serverTrusting("2001:db8:0:0:0:0:0:0/32");
            assertTrue(server.isIPTrusted("2001:db8::"));
            assertTrue(server.isIPTrusted("2001:db8::1"));
            assertTrue(server.isIPTrusted("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"));
            assertTrue(server.isIPTrusted("2001:db8:0:0:0:0:0:7")); // the form getHostAddress() gives
            assertFalse(server.isIPTrusted("2001:db9::"));
            assertFalse(server.isIPTrusted("2001:db7:ffff:ffff:ffff:ffff:ffff:ffff"));
            assertFalse(server.isIPTrusted("::1"));
        }

        @Test
        void ipv6Slash64AndOddPrefix() {
            HTTPServer server = serverTrusting("fd00:1:2:3::/64", "2001:db8:abc0::/44");
            assertTrue(server.isIPTrusted("fd00:1:2:3::1"));
            assertTrue(server.isIPTrusted("fd00:1:2:3:ffff:ffff:ffff:ffff"));
            assertFalse(server.isIPTrusted("fd00:1:2:4::"));
            assertFalse(server.isIPTrusted("fd00:1:2:2:ffff:ffff:ffff:ffff"));
            assertTrue(server.isIPTrusted("2001:db8:abc0::"));
            assertTrue(server.isIPTrusted("2001:db8:abcf:ffff::1"));
            assertFalse(server.isIPTrusted("2001:db8:abbf:ffff::1"));
            assertFalse(server.isIPTrusted("2001:db8:abd0::"));
        }

        @Test
        void ipv6SingleAddress() {
            HTTPServer server = serverTrusting("0:0:0:0:0:0:0:1/128");
            assertTrue(server.isIPTrusted("::1"));
            assertTrue(server.isIPTrusted("0:0:0:0:0:0:0:1"));
            assertFalse(server.isIPTrusted("::2"));
            assertFalse(server.isIPTrusted("::"));
        }

        @Test
        void slash0TrustsEverythingOfItsFamily() {
            HTTPServer v4 = serverTrusting("0.0.0.0/0");
            assertTrue(v4.isIPTrusted("1.2.3.4"));
            assertTrue(v4.isIPTrusted("255.255.255.255"));
            assertFalse(v4.isIPTrusted("2001:db8::1"));
            HTTPServer v6 = serverTrusting("0:0:0:0:0:0:0:0/0");
            assertTrue(v6.isIPTrusted("2001:db8::1"));
            assertTrue(v6.isIPTrusted("ffff::1"));
            assertFalse(v6.isIPTrusted("1.2.3.4"));
        }

        @Test
        void familiesDontMix() {
            HTTPServer v4 = serverTrusting("10.0.0.0/8");
            assertFalse(v4.isIPTrusted("::a00:1")); // the same low 32 bits as 10.0.0.1
            assertFalse(v4.isIPTrusted("::ffff:a00:1"));
            HTTPServer v6 = serverTrusting("::/96");
            assertFalse(v6.isIPTrusted("10.0.0.1"));
        }

        @Test
        void replacingTheListDropsTheOldEntries() {
            HTTPServer server = serverTrusting("10.0.0.0/8");
            WebServer.setTrustedProxies(server, List.of("2001:db8:0:0:0:0:0:0/32"));
            assertFalse(server.isIPTrusted("10.0.0.1"));
            assertTrue(server.isIPTrusted("2001:db8::1"));
            assertEquals(1, server.getTrustedIPs().size());
        }

        @Test
        void invalidEntryIsRejectedAndLeavesTheListUnchanged() {
            HTTPServer server = serverTrusting("10.0.0.0/8");
            assertThrows(IllegalArgumentException.class,
                    () -> WebServer.setTrustedProxies(server, List.of("192.168.0.0/16", "2001:db8::/129")));
            assertTrue(server.isIPTrusted("10.0.0.1"));
            assertFalse(server.isIPTrusted("192.168.0.1"));
        }

        @Test
        void entriesAsTheConfigLoaderNormalisesThem() throws ConfigException {
            Config config = new ConfigLoader(new HashMap<>(), 1, dir).loadFromJson(null, """
                    {"http.publicBaseUrl": "https://music.example.com",
                     "http.trustedProxies": ["10.0.0.0/8", "192.168.1.1", "2001:DB8::/32", "::1"]}
                    """).config();
            HTTPServer server = serverTrusting(config.trustedProxies().toArray(String[]::new));
            assertTrue(server.isIPTrusted("10.9.8.7"));
            assertTrue(server.isIPTrusted("192.168.1.1"));
            assertFalse(server.isIPTrusted("192.168.1.2"));
            assertTrue(server.isIPTrusted("2001:db8:1::1"));
            assertTrue(server.isIPTrusted("::1"));
            assertFalse(server.isIPTrusted("2001:db9::1"));
        }
    }

    /**
     * End to end: the test connects from loopback, which SiggiHTTP always trusts, so the rightmost
     * {@code X-Forwarded-For} entry is taken; further entries to its left are only followed past
     * hops in the trusted list.
     */
    @Nested
    class ForwardedFor {
        private WebServer web;
        private final HttpClient client = HttpClient.newHttpClient();

        @AfterEach
        void tearDown() {
            if (web != null) {
                web.close();
            }
        }

        private void start(String... trusted) throws IOException {
            Router router = new Router();
            router.get("/ip", (rq, rs) -> rs.text(200, rq.clientIp()));
            File tmp = Files.createDirectories(dir.resolve("tmp")).toFile();
            web = WebServer.start("127.0.0.1", 0, router, tmp, List.of(trusted));
        }

        private String ip(String forwardedFor) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + "/ip"));
            if (forwardedFor != null) {
                b.header("X-Forwarded-For", forwardedFor);
            }
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, r.statusCode());
            return r.body().trim();
        }

        @Test
        void withoutHeaderItIsThePeer() throws Exception {
            start("10.0.0.0/8");
            assertEquals("127.0.0.1", ip(null));
        }

        @Test
        void trustedHopsAreSkippedIpv4() throws Exception {
            start("10.0.0.0/8");
            assertEquals("203.0.113.7", ip("203.0.113.7, 10.0.0.5"));
            assertEquals("203.0.113.7", ip("198.51.100.1, 203.0.113.7, 10.1.1.1, 10.0.0.5"));
        }

        @Test
        void untrustedHopStopsTheWalk() throws Exception {
            start();
            // 10.0.0.5 would be trusted by SiggiHTTP's defaults; with an empty list it isn't
            assertEquals("10.0.0.5", ip("203.0.113.7, 10.0.0.5"));
            assertEquals("192.168.1.1", ip("203.0.113.7, 192.168.1.1"));
        }

        @Test
        void trustedHopsAreSkippedIpv6() throws Exception {
            start("2001:db8:1:0:0:0:0:0/48");
            assertEquals("2001:db8:0:0:0:0:0:7", ip("2001:db8::7, 2001:db8:1::5"));
            assertEquals("2001:db8:2:0:0:0:0:5", ip("2001:db8::7, 2001:db8:2::5"));
        }

        @Test
        void mixedFamilies() throws Exception {
            start("10.0.0.0/8", "fd00:0:0:0:0:0:0:0/8");
            assertEquals("203.0.113.7", ip("203.0.113.7, fd12::1, 10.0.0.5"));
            assertEquals("2001:db8:0:0:0:0:0:7", ip("2001:db8::7, 10.0.0.5, fd00::5"));
        }

        @Test
        void garbageEntryStopsTheWalk() throws Exception {
            start("10.0.0.0/8");
            assertEquals("10.0.0.5", ip("203.0.113.7, not-an-ip, 10.0.0.5"));
        }
    }
}
