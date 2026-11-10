package io.siggi.togethertunes.output;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Splits an MPEG audio byte stream (ffmpeg's {@code -f mp3} output) into whole Layer III frames, so
 * the MP3 output can hand listeners data that starts on a frame boundary
 * ({@code docs/program-output.md#mp3-output}). Bytes before the first frame sync, an ID3v2 tag and
 * anything that doesn't parse as a frame header are skipped. Not thread-safe.
 *
 * <p>Frame length (Layer III): MPEG-1: {@code 144 · bitrate / sampleRate + padding}; MPEG-2/2.5:
 * {@code 72 · bitrate / sampleRate + padding}. A frame is only emitted once the header of the frame
 * after it is seen too (or it is the last one at {@link #flush}), so a false sync in the middle of
 * audio data can't start a frame.
 */
public final class Mp3Frames {
    private static final int[][] BITRATES_KBPS = {
            // MPEG-1 Layer III
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1},
            // MPEG-2/2.5 Layer III
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1},
    };
    private static final int[] SAMPLE_RATES_MPEG1 = {44100, 48000, 32000, -1};

    private byte[] buf = new byte[8192];
    private int len;
    private long frames;
    private long samples;

    /** A Layer III frame header's facts. */
    public record Header(int length, int sampleRate, int samplesPerFrame, int bitrateKbps) {
    }

    /**
     * The header at {@code b[off..off+4)}, or null if it isn't a valid Layer III header (free
     * format and reserved values count as invalid).
     */
    public static Header header(byte[] b, int off) {
        int b0 = b[off] & 0xff;
        int b1 = b[off + 1] & 0xff;
        int b2 = b[off + 2] & 0xff;
        if (b0 != 0xff || (b1 & 0xe0) != 0xe0) {
            return null;
        }
        int version = (b1 >> 3) & 3; // 0: 2.5, 1: reserved, 2: MPEG-2, 3: MPEG-1
        int layer = (b1 >> 1) & 3; // 1: Layer III
        if (version == 1 || layer != 1) {
            return null;
        }
        int bitrateIndex = b2 >> 4;
        int rateIndex = (b2 >> 2) & 3;
        int padding = (b2 >> 1) & 1;
        boolean mpeg1 = version == 3;
        int kbps = BITRATES_KBPS[mpeg1 ? 0 : 1][bitrateIndex];
        int rate = SAMPLE_RATES_MPEG1[rateIndex];
        if (kbps <= 0 || rate < 0) {
            return null;
        }
        if (version == 2) {
            rate /= 2;
        } else if (version == 0) {
            rate /= 4;
        }
        int length = (mpeg1 ? 144 : 72) * kbps * 1000 / rate + padding;
        return new Header(length, rate, mpeg1 ? 1152 : 576, kbps);
    }

    /**
     * Adds bytes; returns the whole frames that are now complete (concatenated, in order), or an
     * empty array.
     */
    public byte[] feed(byte[] b, int off, int n) {
        if (len + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
        System.arraycopy(b, off, buf, len, n);
        len += n;
        return drain(false);
    }

    /** At the end of the stream: the last frame if it is complete. */
    public byte[] flush() {
        byte[] out = drain(true);
        len = 0;
        return out;
    }

    /** Frames emitted so far. */
    public long frames() {
        return frames;
    }

    /** Audio samples (per channel) in the frames emitted so far. */
    public long samples() {
        return samples;
    }

    private byte[] drain(boolean last) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            if (!last && len > pos && len - pos < 10 && buf[pos] == 'I' && (len - pos < 2 || buf[pos + 1] == 'D')
                    && (len - pos < 3 || buf[pos + 2] == '3')) {
                break; // maybe an ID3v2 header: wait for all of it
            }
            if (len - pos >= 10 && buf[pos] == 'I' && buf[pos + 1] == 'D' && buf[pos + 2] == '3') {
                // ID3v2: 10-byte header, syncsafe size (+10 for a footer)
                int size = ((buf[pos + 6] & 0x7f) << 21) | ((buf[pos + 7] & 0x7f) << 14)
                        | ((buf[pos + 8] & 0x7f) << 7) | (buf[pos + 9] & 0x7f);
                int total = 10 + size + ((buf[pos + 5] & 0x10) != 0 ? 10 : 0);
                if (len - pos < total) {
                    break;
                }
                pos += total;
                continue;
            }
            if (len - pos < 4) {
                break;
            }
            Header h = header(buf, pos);
            if (h == null) {
                pos++;
                continue;
            }
            int end = pos + h.length();
            if (end + 4 <= len) {
                if (header(buf, end) == null) {
                    pos++; // not followed by another frame: a false sync
                    continue;
                }
            } else if (!(last && end <= len)) {
                break; // wait for the rest (and the next header)
            }
            out.write(buf, pos, h.length());
            frames++;
            samples += h.samplesPerFrame();
            pos = end;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
        return out.toByteArray();
    }
}
