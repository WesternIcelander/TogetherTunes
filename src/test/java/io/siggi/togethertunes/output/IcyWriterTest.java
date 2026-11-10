package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link IcyWriter}: metadata blocks after exactly every {@code metaint} audio bytes. */
class IcyWriterTest {
    /** Splits an ICY stream back into audio and the metadata blocks' texts ("" for a 0 block). */
    record Parsed(byte[] audio, List<String> blocks) {
    }

    static Parsed parse(byte[] stream, int metaint) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        List<String> blocks = new ArrayList<>();
        int pos = 0;
        while (pos < stream.length) {
            int n = Math.min(metaint, stream.length - pos);
            audio.write(stream, pos, n);
            pos += n;
            if (n < metaint || pos >= stream.length) {
                break;
            }
            int len = (stream[pos] & 0xff) * 16;
            byte[] text = Arrays.copyOfRange(stream, pos + 1, pos + 1 + len);
            int end = text.length;
            while (end > 0 && text[end - 1] == 0) {
                end--;
            }
            blocks.add(new String(text, 0, end, StandardCharsets.UTF_8));
            pos += 1 + len;
        }
        return new Parsed(audio.toByteArray(), blocks);
    }

    static byte[] audio(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (seed + i * 7);
        }
        return b;
    }

    @Test
    void blocksComeAfterExactlyMetaintBytesAndRepeatOnlyChangedTitles() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IcyWriter w = new IcyWriter(out, 100);
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        byte[] a = audio(250, 1);
        byte[] b = audio(37, 2);
        byte[] c = audio(313, 3);
        w.write(a, "Artist - One");
        w.write(b, "Artist - One");
        w.write(c, "Artist - Two");
        sent.write(a);
        sent.write(b);
        sent.write(c);
        byte[] stream = out.toByteArray();
        Parsed p = parse(stream, 100);
        assertArrayEquals(sent.toByteArray(), p.audio());
        // blocks after 100, 200 (chunk a), 300 (chunk c), 400, 500, 600 (chunk c)
        assertEquals(List.of("StreamTitle='Artist - One';", "", "StreamTitle='Artist - Two';", "", "", ""),
                p.blocks());
        // the first block's position: exactly 100 audio bytes in, its length byte counts 16-byte units
        int len = stream[100] & 0xff;
        assertEquals(2, len); // 27 bytes of text → 2 units
        assertEquals(0, stream[100 + 1 + 27]);
    }

    @Test
    void withoutMetadataTheAudioIsUntouched() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IcyWriter w = new IcyWriter(out, 0);
        byte[] a = audio(40_000, 5);
        w.write(a, "Title");
        assertArrayEquals(a, out.toByteArray());
    }

    @Test
    void titlesAreMadeSafe() {
        assertEquals("Don’t Stop", IcyWriter.safeTitle("Don't Stop"));
        assertEquals("a b", IcyWriter.safeTitle("a\nb"));
        assertEquals("x';y", IcyWriter.safeTitle("x';y").replace('’', '\''));
        String longTitle = "ü".repeat(1000);
        String safe = IcyWriter.safeTitle(longTitle);
        assertEquals(IcyWriter.MAX_TITLE_CHARS + 1, safe.length());
        assertTrue(safe.endsWith("…"));
        byte[] block = IcyWriter.block(longTitle);
        assertEquals(block.length - 1, (block[0] & 0xff) * 16);
        assertTrue((block[0] & 0xff) <= 255);
        assertEquals("StreamTitle='';", parse(concat(audio(10, 0), IcyWriter.block("")), 10).blocks().get(0));
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
