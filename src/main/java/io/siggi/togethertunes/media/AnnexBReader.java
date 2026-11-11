package io.siggi.togethertunes.media;

import io.siggi.togethertunes.media.AnnexB.H264FormatException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Splits an H.264 Annex B byte stream (as ffmpeg writes it with {@code -f h264}) into NAL units,
 * reading it incrementally ({@code docs/overlay.md#reading-the-output-java}).
 *
 * <p>A NAL unit starts after a start code ({@code 00 00 01}, or {@code 00 00 00 01}) and ends at the
 * next one or at the end of the stream. Zero bytes before a start code ({@code zero_byte},
 * {@code trailing_zero_8bits}) are not part of the NAL unit, so trailing zeros are stripped (a NAL
 * unit itself never ends in a zero byte: its RBSP ends with the stop bit, and
 * {@code cabac_zero_word}s are {@code 00 00 03}). Emulation prevention guarantees that
 * {@code 00 00 01} never occurs inside a NAL unit. Empty NAL units are skipped.
 *
 * <p>Not thread-safe; one reader per stream.
 */
public final class AnnexBReader {
    /** Default largest NAL unit accepted: far more than any frame at the profile's bitrate cap. */
    public static final int DEFAULT_MAX_NAL_BYTES = 32 * 1024 * 1024;

    private final InputStream in;
    private final int maxNalBytes;
    private byte[] buf = new byte[64 * 1024];
    /** Bytes {@code [0, end)} of {@code buf} are valid. */
    private int end;
    /** Start of the current NAL unit's payload in {@code buf}, or -1 before the first start code. */
    private int nalStart = -1;
    /** Where to continue looking for the next start code. */
    private int scan;
    private boolean eof;
    private long nalCount;

    public AnnexBReader(InputStream in) {
        this(in, DEFAULT_MAX_NAL_BYTES);
    }

    /**
     * @param maxNalBytes the largest NAL unit accepted; a longer one fails {@link #next()}
     */
    public AnnexBReader(InputStream in, int maxNalBytes) {
        this.in = Objects.requireNonNull(in);
        if (maxNalBytes < 1) {
            throw new IllegalArgumentException("maxNalBytes");
        }
        this.maxNalBytes = maxNalBytes;
    }

    /** NAL units returned so far. */
    public long nalCount() {
        return nalCount;
    }

    /**
     * The next NAL unit (header byte first, no start code), or null at the end of the stream.
     *
     * @throws H264FormatException if non-zero bytes come before the first start code, or a NAL
     *                             unit is longer than the limit
     * @throws IOException         if reading fails
     */
    public byte[] next() throws IOException {
        while (true) {
            // look for the next start code from scan
            int found = findStartCode();
            if (found >= 0) {
                int codeEnd = found + 3;
                if (nalStart < 0) {
                    for (int i = 0; i < found; i++) {
                        if (buf[i] != 0) {
                            throw new H264FormatException("not an Annex B stream: data before the first start code");
                        }
                    }
                    nalStart = codeEnd;
                    scan = codeEnd;
                    continue;
                }
                byte[] nal = cut(nalStart, found);
                nalStart = codeEnd;
                scan = codeEnd;
                if (nal != null) {
                    nalCount++;
                    return nal;
                }
                continue;
            }
            if (eof) {
                if (nalStart < 0) {
                    for (int i = 0; i < end; i++) {
                        if (buf[i] != 0) {
                            throw new H264FormatException("not an Annex B stream: no start code");
                        }
                    }
                    end = 0;
                    scan = 0;
                    return null;
                }
                byte[] nal = cut(nalStart, end);
                nalStart = end;
                scan = end;
                if (nal != null) {
                    nalCount++;
                    return nal;
                }
                return null;
            }
            fill();
        }
    }

    /**
     * Index of the first {@code 00} of the next {@code 00 00 01} at or after {@code scan} (fully in
     * the buffer), or -1; on -1, {@code scan} moves to where the search can resume.
     */
    private int findStartCode() throws H264FormatException {
        int i = scan;
        while (i + 2 < end) {
            byte b2 = buf[i + 2];
            if (b2 == 1) {
                if (buf[i + 1] == 0 && buf[i] == 0) {
                    return i;
                }
                i += 3;
            } else if (b2 == 0) {
                i += 1;
            } else {
                i += 3;
            }
        }
        scan = Math.max(scan, Math.min(i, Math.max(0, end - 2)));
        int pending = end - (nalStart < 0 ? 0 : nalStart);
        if (pending > maxNalBytes + 3) {
            throw new H264FormatException("NAL unit longer than " + maxNalBytes + " bytes");
        }
        return -1;
    }

    /** The NAL unit in {@code buf[from, to)} without trailing zero bytes, or null if empty. */
    private byte[] cut(int from, int to) throws H264FormatException {
        while (to > from && buf[to - 1] == 0) {
            to--;
        }
        if (to <= from) {
            return null;
        }
        if (to - from > maxNalBytes) {
            throw new H264FormatException("NAL unit longer than " + maxNalBytes + " bytes");
        }
        return Arrays.copyOfRange(buf, from, to);
    }

    /** Reads more data, first dropping what has been returned already and growing the buffer if full. */
    private void fill() throws IOException {
        int keep = nalStart < 0 ? Math.max(0, end - 3) : nalStart;
        if (nalStart < 0 && keep > 0) {
            // only zeros may come before the first start code; check what is dropped
            for (int i = 0; i < keep; i++) {
                if (buf[i] != 0) {
                    throw new H264FormatException("not an Annex B stream: data before the first start code");
                }
            }
        }
        if (keep > 0) {
            System.arraycopy(buf, keep, buf, 0, end - keep);
            end -= keep;
            scan = Math.max(0, scan - keep);
            if (nalStart >= 0) {
                nalStart -= keep;
            }
        }
        if (end == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        int n = in.read(buf, end, buf.length - end);
        if (n < 0) {
            eof = true;
        } else {
            end += n;
        }
    }
}
