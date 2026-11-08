package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Streaming request bodies to disk with {@link Request#bodyToFile} and {@link Request#copyBody}:
 * {@code Content-Length} and chunked bodies, a body over 4 GiB, writing at an offset (resumable
 * chunks), limits (413 before reading, and mid-stream for chunked), truncated bodies (400), and
 * that SiggiHTTP neither spools multipart bodies nor refuses big {@code Expect: 100-continue}
 * bodies itself. Uses raw sockets so exact framing can be sent.
 */
class RequestBodyTest {
    private static final long FILE_LIMIT = 256L * 1024 * 1024;

    @TempDir
    Path dir;

    private Path uploads;
    private Path siggiTmp;
    private WebServer server;
    private PrintStream originalErr;
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        uploads = Files.createDirectory(dir.resolve("uploads"));
        siggiTmp = Files.createDirectory(dir.resolve("siggi-tmp"));
        originalErr = System.err;
        System.setErr(new PrintStream(new TeeStream(originalErr, capturedErr), true));
        Router router = new Router();
        router.put("/file/{name}", (rq, rs) -> {
            String pos = rq.query("pos");
            long written = rq.bodyToFile(uploads.resolve(rq.pathParam("name")),
                    pos == null ? 0 : Long.parseLong(pos), FILE_LIMIT);
            rs.json(Map.of("written", written));
        });
        // Counts and checksums the body without touching the disk (for the > 4 GiB test)
        router.put("/count", (rq, rs) -> count(rq, rs));
        router.post("/count", (rq, rs) -> count(rq, rs));
        server = WebServer.start("127.0.0.1", 0, router, siggiTmp.toFile());
    }

    private static void count(Request rq, Response rs) throws IOException {
        CrcStream sink = new CrcStream();
        long n = rq.copyBody(sink, -1);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("bytes", n);
        result.put("crc", sink.crc.getValue());
        rs.json(result);
    }

    @AfterEach
    void tearDown() {
        try {
            if (server != null) {
                server.close();
            }
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void contentLengthBodyIsWrittenToFileAndConnectionIsReused() throws IOException {
        byte[] data = random(48 * 1024 * 1024 + 123, 1);
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/file/a.bin", "Content-Length: " + data.length));
            out.write(data);
            out.flush();
            Resp r = Resp.read(s.getInputStream());
            assertEquals(200, r.status, r.body);
            assertEquals("{\"written\":" + data.length + "}", r.body);
            assertArrayEquals(data, Files.readAllBytes(uploads.resolve("a.bin")));

            // same connection: the body was consumed exactly, so the next request parses
            byte[] small = "hello".getBytes(StandardCharsets.US_ASCII);
            out.write(head("PUT", "/file/b.bin", "Content-Length: 5"));
            out.write(small);
            out.flush();
            Resp r2 = Resp.read(s.getInputStream());
            assertEquals(200, r2.status, r2.body);
            assertArrayEquals(small, Files.readAllBytes(uploads.resolve("b.bin")));
        }
        assertNoErrorsLogged();
    }

    @Test
    void chunkedBodyIsWrittenToFile() throws IOException {
        byte[] data = random(20 * 1024 * 1024 + 7, 2);
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/file/c.bin", "Transfer-Encoding: chunked"));
            writeChunked(out, data, 0, data.length, new Random(3));
            out.flush();
            Resp r = Resp.read(s.getInputStream());
            assertEquals(200, r.status, r.body);
            assertEquals("{\"written\":" + data.length + "}", r.body);
        }
        assertArrayEquals(data, Files.readAllBytes(uploads.resolve("c.bin")));
        assertNoErrorsLogged();
    }

    @Test
    void writesAtOffsetWithoutTruncating() throws IOException {
        byte[] data = random(3 * 1024 * 1024, 4);
        int split = 1024 * 1024 + 17;
        Path file = uploads.resolve("r.bin");
        // second half first is not how uploads work, but shows nothing is truncated or moved
        assertEquals(200, put("/file/r.bin", data, 0, split).status);
        assertEquals(200, put("/file/r.bin?pos=" + split, data, split, data.length - split).status);
        assertArrayEquals(data, Files.readAllBytes(file));
        // rewriting the first chunk leaves the rest in place
        assertEquals(200, put("/file/r.bin", data, 0, split).status);
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    /** Over 4 GiB through Content-Length (no int overflow anywhere), streamed with a 64 KiB buffer. */
    @Test
    void bodyOverFourGibStreams() throws IOException {
        long length = 4L * 1024 * 1024 * 1024 + 12345;
        byte[] block = random(1024 * 1024, 5);
        CRC32 crc = new CRC32();
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/count", "Content-Length: " + length));
            long left = length;
            while (left > 0) {
                int n = (int) Math.min(block.length, left);
                out.write(block, 0, n);
                crc.update(block, 0, n);
                left -= n;
            }
            out.flush();
            Resp r = Resp.read(s.getInputStream());
            assertEquals(200, r.status, r.body);
            assertEquals("{\"bytes\":" + length + ",\"crc\":" + crc.getValue() + "}", r.body);
        }
    }

    @Test
    void declaredLengthOverLimitIsRefusedBeforeReading() throws IOException {
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            // only the headers: the 413 must come without the server waiting for the body
            out.write(head("PUT", "/file/big.bin", "Content-Length: " + (5L << 30)));
            out.flush();
            Resp r = Resp.read(s.getInputStream());
            assertEquals(413, r.status);
            assertTrue(r.body.contains("payload_too_large"), r.body);
        }
        assertFalse(Files.exists(uploads.resolve("big.bin")));
    }

    @Test
    void expectContinueOverTwoGibIsLeftToTheHandler() throws IOException {
        // SiggiHTTP's default upload limit (2 GB) would answer this with its own 413
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/count", "Content-Length: " + (3L << 30), "Expect: 100-continue"));
            out.flush();
            String line = readLine(s.getInputStream());
            assertEquals("HTTP/1.1 100 Continue", line);
        }
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/file/big.bin", "Content-Length: " + (3L << 30), "Expect: 100-continue"));
            out.flush();
            InputStream in = s.getInputStream();
            assertEquals("HTTP/1.1 100 Continue", readLine(in));
            assertEquals("", readLine(in));
            Resp r = Resp.read(in);
            assertEquals(413, r.status);
            assertTrue(r.body.contains("payload_too_large"), r.body);
        }
    }

    @Test
    void chunkedBodyOverLimitIsCutOff() throws IOException {
        byte[] block = random(1024 * 1024, 6);
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/file/over.bin", "Transfer-Encoding: chunked"));
            Resp r = null;
            try {
                for (long sent = 0; sent <= FILE_LIMIT + block.length; sent += block.length) {
                    writeChunked(out, block, 0, block.length, null, false);
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // the server may close before we finish sending; the response is still there
            }
            r = Resp.read(s.getInputStream());
            assertEquals(413, r.status);
        }
        assertEquals(FILE_LIMIT, Files.size(uploads.resolve("over.bin")));
        assertNoErrorsLogged();
    }

    @Test
    void truncatedContentLengthBodyIsAnError() throws IOException {
        byte[] data = random(100_000, 7);
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/file/t.bin", "Content-Length: " + (data.length + 1000)));
            out.write(data);
            out.flush();
            s.shutdownOutput();
            Resp r = Resp.read(s.getInputStream());
            assertEquals(400, r.status);
            assertTrue(r.body.contains("incomplete_body"), r.body);
        }
        // what arrived is kept, for resuming
        assertArrayEquals(data, Files.readAllBytes(uploads.resolve("t.bin")));
        assertNoErrorsLogged();
    }

    @Test
    void truncatedChunkedBodyIsAnError() throws IOException {
        byte[] data = random(100_000, 8);
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", "/file/tc.bin", "Transfer-Encoding: chunked"));
            out.write(("30000\r\n").getBytes(StandardCharsets.US_ASCII)); // promises 196608 bytes
            out.write(data);
            out.flush();
            s.shutdownOutput();
            Resp r = Resp.read(s.getInputStream());
            assertEquals(400, r.status);
            assertTrue(r.body.contains("incomplete_body"), r.body);
        }
        assertNoErrorsLogged();
    }

    @Test
    void multipartBodyReachesHandlerUnparsed() throws IOException {
        String boundary = "XyZ123";
        byte[] payload = random(2 * 1024 * 1024, 9);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(payload);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = body.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("POST", "/count", "Content-Type: multipart/form-data; boundary=" + boundary,
                    "Content-Length: " + bytes.length));
            out.write(bytes);
            out.flush();
            Resp r = Resp.read(s.getInputStream());
            assertEquals(200, r.status, r.body);
            assertEquals("{\"bytes\":" + bytes.length + ",\"crc\":" + crc.getValue() + "}", r.body);
        }
        try (var files = Files.list(siggiTmp)) {
            assertEquals(0, files.count(), "SiggiHTTP spooled the multipart body");
        }
    }

    // --- helpers ---

    private Socket connect() throws IOException {
        Socket s = new Socket("127.0.0.1", server.port());
        s.setSoTimeout(30_000);
        return s;
    }

    private Resp put(String path, byte[] data, int off, int len) throws IOException {
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            out.write(head("PUT", path, "Content-Length: " + len));
            out.write(data, off, len);
            out.flush();
            return Resp.read(s.getInputStream());
        }
    }

    private static byte[] head(String method, String path, String... headers) {
        StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: localhost\r\n");
        boolean hasType = false;
        for (String h : headers) {
            sb.append(h).append("\r\n");
            hasType |= h.toLowerCase(Locale.ROOT).startsWith("content-type:");
        }
        if (!hasType) {
            sb.append("Content-Type: application/octet-stream\r\n");
        }
        return sb.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeChunked(OutputStream out, byte[] data, int off, int len, Random sizes) throws IOException {
        writeChunked(out, data, off, len, sizes, true);
    }

    /** Writes {@code data} as chunks of random sizes (1 B to 200 KiB), or as one chunk. */
    private static void writeChunked(OutputStream out, byte[] data, int off, int len, Random sizes, boolean last) throws IOException {
        int pos = off;
        int end = off + len;
        while (pos < end) {
            int n = sizes == null ? end - pos : Math.min(end - pos, 1 + sizes.nextInt(200 * 1024));
            out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(data, pos, n);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            pos += n;
        }
        if (last) {
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private void assertNoErrorsLogged() {
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertFalse(err.contains("Exception"), "Unexpected log output:\n" + err);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    /** A status line, headers and a Content-Length body. */
    private record Resp(int status, Map<String, String> headers, String body) {
        static Resp read(InputStream in) throws IOException {
            String status = readLine(in);
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            String length = headers.get("content-length");
            byte[] body = length == null ? new byte[0] : in.readNBytes(Integer.parseInt(length));
            return new Resp(Integer.parseInt(status.split(" ")[1]), headers, new String(body, StandardCharsets.UTF_8));
        }
    }

    private static final class CrcStream extends OutputStream {
        final CRC32 crc = new CRC32();

        @Override
        public void write(int b) {
            crc.update(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            crc.update(b, off, len);
        }
    }

    private static final class TeeStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) throws IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }
    }
}
