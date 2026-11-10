package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ContentId}: the chunked SHA-256 of the upload pre-check. The expected values were
 * computed independently (Python's hashlib: SHA-256 per 8 MiB piece, then SHA-256 of the
 * concatenated digests); {@code contentid.test.mjs} checks the browser code (Node's
 * {@code crypto.subtle}) against the same values.
 */
class ContentIdTest {
    /** Two full pieces and a short last one. */
    static final int FIXTURE_SIZE = 2 * ContentId.PIECE_BYTES + 12345;
    static final String FIXTURE_ID =
            "sha256c8m:16789561:b9d119c9f7078bd2a9ea80d853d0914f1946eabaa46a77c6657f01015932528e";

    @TempDir
    Path dir;

    /** Byte i is {@code (i * 31 + 7) & 0xff}, as in the JS test. */
    static byte[] fixture(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        return b;
    }

    @Test
    void knownValues() throws Exception {
        assertEquals(FIXTURE_ID, ContentId.of(new ByteArrayInputStream(fixture(FIXTURE_SIZE))));
        assertEquals("sha256c8m:3:4f8b42c22dd3729b519ba6f68d2da7cc5b2d606d05daed5ad5128cc03e6c6358",
                ContentId.of(new ByteArrayInputStream("abc".getBytes())));
        // exactly one piece: no empty piece after it
        assertEquals("sha256c8m:8388608:b179ca4324dda9576602c8bb5c78905e25575c145a67013b2468ccecf91f7832",
                ContentId.of(new ByteArrayInputStream(fixture(ContentId.PIECE_BYTES))));
        assertNull(ContentId.of(new ByteArrayInputStream(new byte[0])), "an empty file has no ID");
    }

    @Test
    void anySlicingGivesTheSameIdAndValueDoesNotDisturbTheState() throws Exception {
        byte[] data = fixture(FIXTURE_SIZE);
        Random random = new Random(1);
        ContentId id = new ContentId();
        int pos = 0;
        boolean buffers = false;
        while (pos < data.length) {
            int n = Math.min(data.length - pos, 1 + random.nextInt(3 * 1024 * 1024));
            if (buffers) {
                ByteBuffer b = ByteBuffer.wrap(data, pos, n);
                id.update(b);
                assertFalse(b.hasRemaining());
            } else {
                id.update(data, pos, n);
            }
            buffers = !buffers;
            pos += n;
            id.value(); // a look in between changes nothing
        }
        assertEquals(FIXTURE_ID, id.value());
        assertEquals(FIXTURE_ID, id.value());
        assertEquals(FIXTURE_SIZE, id.size());
        id.reset();
        assertNull(id.value());

        Path file = dir.resolve("f.bin");
        Files.write(file, data);
        assertEquals(FIXTURE_ID, ContentId.of(file));
    }

    @Test
    void format() {
        assertTrue(ContentId.isValid(FIXTURE_ID));
        assertEquals(FIXTURE_SIZE, ContentId.sizeOf(FIXTURE_ID));
        assertFalse(ContentId.isValid(null));
        assertFalse(ContentId.isValid(""));
        assertFalse(ContentId.isValid(FIXTURE_ID.toUpperCase()));
        assertFalse(ContentId.isValid("sha256c8m:0:" + "0".repeat(64)));
        assertFalse(ContentId.isValid("sha256c8m:12:" + "0".repeat(63)));
        assertFalse(ContentId.isValid("sha256:12:" + "0".repeat(64)));
        assertFalse(ContentId.isValid("sha256c8m:1" + "0".repeat(18) + ":" + "0".repeat(64)), "too large");
        assertTrue(ContentId.isValid("sha256c8m:9" + "0".repeat(17) + ":" + "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> ContentId.sizeOf("nope"));
    }
}
