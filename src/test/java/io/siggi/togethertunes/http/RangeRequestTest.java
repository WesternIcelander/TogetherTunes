package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Range requests through SiggiHTTP's {@code returnFile(file, contentType)}, served from a route of
 * our {@link Router} on a real loopback server (RFC 9110 section 14).
 */
class RangeRequestTest {
    private static final int SIZE = 100_000;
    private static final String TYPE = "audio/mp4";

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WebServer server;
    private byte[] content;

    @BeforeEach
    void setUp() throws IOException {
        content = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            content[i] = (byte) (i * 31 + (i >> 8));
        }
        File file = dir.resolve("audio.m4a").toFile();
        Files.write(file.toPath(), content);
        Router router = new Router();
        router.get("/media/file", (rq, rs) -> rs.raw().returnFile(file, TYPE));
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        client.close();
    }

    private HttpResponse<byte[]> get(String range) throws IOException, InterruptedException {
        return send("GET", range);
    }

    private HttpResponse<byte[]> send(String method, String range) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/media/file"))
                .timeout(Duration.ofSeconds(10))
                .method(method, BodyPublishers.noBody());
        if (range != null) {
            b.header("Range", range);
        }
        return client.send(b.build(), BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    private void assertPartial(HttpResponse<byte[]> r, long first, long last) {
        assertEquals(206, r.statusCode(), r.headers().toString());
        assertEquals("bytes " + first + "-" + last + "/" + SIZE, header(r, "Content-Range"));
        assertEquals(String.valueOf(last - first + 1), header(r, "Content-Length"));
        assertEquals(TYPE, header(r, "Content-Type"));
        assertArrayEquals(Arrays.copyOfRange(content, (int) first, (int) last + 1), r.body());
    }

    @Test
    void fullFileWithoutRange() throws Exception {
        HttpResponse<byte[]> r = get(null);
        assertEquals(200, r.statusCode());
        assertEquals("bytes", header(r, "Accept-Ranges"));
        assertEquals(String.valueOf(SIZE), header(r, "Content-Length"));
        assertEquals(TYPE, header(r, "Content-Type"));
        assertEquals(null, header(r, "Content-Range"));
        assertArrayEquals(content, r.body());
    }

    @Test
    void closedRange() throws Exception {
        assertPartial(get("bytes=0-9"), 0, 9);
        assertPartial(get("bytes=1000-1999"), 1000, 1999);
        assertPartial(get("bytes=" + (SIZE - 1) + "-" + (SIZE - 1)), SIZE - 1, SIZE - 1);
    }

    @Test
    void lastPosBeyondTheEndIsClamped() throws Exception {
        assertPartial(get("bytes=500-" + (SIZE * 10)), 500, SIZE - 1);
    }

    @Test
    void openEndedRange() throws Exception {
        assertPartial(get("bytes=500-"), 500, SIZE - 1);
        assertPartial(get("bytes=0-"), 0, SIZE - 1);
    }

    @Test
    void suffixRange() throws Exception {
        assertPartial(get("bytes=-10"), SIZE - 10, SIZE - 1);
        // A suffix longer than the file means the whole file.
        assertPartial(get("bytes=-" + (SIZE * 2)), 0, SIZE - 1);
    }

    @Test
    void unsatisfiableRangeIs416() throws Exception {
        for (String range : new String[] {"bytes=" + SIZE + "-", "bytes=" + (SIZE + 10) + "-" + (SIZE + 20), "bytes=-0"}) {
            HttpResponse<byte[]> r = get(range);
            assertEquals(416, r.statusCode(), range + " " + r.headers());
            assertEquals("bytes */" + SIZE, header(r, "Content-Range"), range);
        }
    }

    @Test
    void headWithAndWithoutRange() throws Exception {
        HttpResponse<byte[]> full = send("HEAD", null);
        assertEquals(200, full.statusCode());
        assertEquals(String.valueOf(SIZE), header(full, "Content-Length"));
        assertEquals("bytes", header(full, "Accept-Ranges"));
        assertEquals(0, full.body().length);

        HttpResponse<byte[]> partial = send("HEAD", "bytes=10-19");
        assertEquals(206, partial.statusCode());
        assertEquals("bytes 10-19/" + SIZE, header(partial, "Content-Range"));
        assertEquals("10", header(partial, "Content-Length"));
        assertEquals(0, partial.body().length);

        // The connection is still usable after a HEAD (no body bytes were sent).
        assertPartial(get("bytes=0-9"), 0, 9);
        assertFalse(full.headers().firstValue("Transfer-Encoding").isPresent());
    }
}
