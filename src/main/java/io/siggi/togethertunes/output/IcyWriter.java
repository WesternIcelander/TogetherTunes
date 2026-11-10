package io.siggi.togethertunes.output;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Writes an MP3 stream with Shoutcast/Icecast ("ICY") metadata
 * ({@code docs/program-output.md#mp3-output}): after every {@code metaint} bytes of audio comes
 * one metadata block, a length byte {@code N} followed by {@code 16·N} bytes holding
 * {@code StreamTitle='…';} padded with NUL bytes. A block repeats the title only when it changed
 * since the last block sent (the first block always has it); otherwise it is the single byte 0.
 *
 * <p>With {@code metaint} 0 the audio is written as it is (the client didn't ask for metadata).
 */
public final class IcyWriter {
    /** Audio bytes between metadata blocks ({@code icy-metaint}). */
    public static final int METAINT = 16_000;
    /** The longest title sent, in code points; longer titles are cut with "…". */
    static final int MAX_TITLE_CHARS = 250;

    private final OutputStream out;
    private final int metaint;
    private int untilMeta;
    private String sentTitle;

    public IcyWriter(OutputStream out, int metaint) {
        this.out = Objects.requireNonNull(out, "out");
        if (metaint < 0) {
            throw new IllegalArgumentException("metaint");
        }
        this.metaint = metaint;
        this.untilMeta = metaint;
    }

    /** Writes audio; a block due within it carries {@code title} (null: no title, sent as empty). */
    public void write(byte[] audio, String title) throws IOException {
        if (metaint == 0) {
            out.write(audio);
            return;
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream(audio.length + 64);
        int pos = 0;
        while (pos < audio.length) {
            int n = Math.min(untilMeta, audio.length - pos);
            b.write(audio, pos, n);
            pos += n;
            untilMeta -= n;
            if (untilMeta == 0) {
                String t = title == null ? "" : title;
                if (t.equals(sentTitle)) {
                    b.write(0);
                } else {
                    b.write(block(t));
                    sentTitle = t;
                }
                untilMeta = metaint;
            }
        }
        out.write(b.toByteArray());
    }

    /**
     * The metadata block for a title: {@code StreamTitle='<title>';} with the title made safe
     * ({@link #safeTitle}), padded to a multiple of 16 bytes, after its length byte.
     */
    static byte[] block(String title) {
        byte[] text = ("StreamTitle='" + safeTitle(title) + "';").getBytes(StandardCharsets.UTF_8);
        int n = (text.length + 15) / 16;
        byte[] out = new byte[1 + n * 16];
        out[0] = (byte) n;
        System.arraycopy(text, 0, out, 1, text.length);
        return out;
    }

    /**
     * The title as it may go between the quotes: control characters become spaces, an apostrophe
     * becomes {@code ’} (U+2019; clients look for {@code ';} and some for any {@code '}), and at
     * most {@value #MAX_TITLE_CHARS} code points are kept (then "…"). The whole block stays well
     * under the 255·16 bytes a length byte can describe.
     */
    static String safeTitle(String title) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            if (count == MAX_TITLE_CHARS) {
                sb.append('…');
                break;
            }
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) {
                sb.append(' ');
            } else if (cp == '\'') {
                sb.append('’');
            } else {
                sb.appendCodePoint(cp);
            }
            count++;
        }
        return sb.toString().strip();
    }
}
