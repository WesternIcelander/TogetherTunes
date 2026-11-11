package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.media.SampleIndex.Track;
import io.siggi.togethertunes.output.VideoOverlay;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * The overlay re-encode of one music video for one room ({@code docs/overlay.md#music-videos-re-encode-path}):
 * which of its GOPs are ready, and where each frame is. Written by {@link OverlayVideoEncoder},
 * read by the packager ({@link VideoOverlay}: {@link #gop}), which plays a GOP from here only when
 * it is {@link #isComplete complete} and the original GOP otherwise.
 *
 * <p>GOP <i>g</i> is the mezzanine's <i>g</i>-th GOP: the frames from its <i>g</i>-th keyframe up
 * to the next one (or the end). Frame numbers are the mezzanine's video sample numbers, so frame
 * <i>i</i> here replaces sample <i>i</i> of {@code mezz.mp4} one for one (same timing, same IDR
 * positions, same avcC).
 *
 * <p>Files, in the job's directory:
 * <ul>
 *   <li>{@value #H264_FILE}: the frames, one after the other, each as an AVCC sample (NAL units
 *       with a length prefix of the reference avcC's size, as in {@code mezz.mp4}'s {@code mdat}),
 *       without SPS, PPS, AUD and SEI. So a frame can be handed to the packager exactly like a
 *       mezzanine sample (the muxer adds AUD, SPS and PPS).</li>
 *   <li>{@value #INDEX_FILE}: a 12-byte header (magic {@code TTOV}, version, the mezzanine's frame
 *       count), then one {@value #ENTRY_BYTES}-byte entry per frame written: frame number (int),
 *       offset in {@value #H264_FILE} (long), size (int), flags (byte, 1 = IDR). Big-endian.
 *       A frame's entry is written after its bytes.</li>
 * </ul>
 *
 * <p>Thread-safe: the completion set is guarded by this object, and a frame's position is stored
 * before its GOP is marked complete, so a reader that saw {@link #isComplete} true sees it.
 */
public final class OverlayGops implements VideoOverlay {
    /** The frames ({@code rooms/<roomId>/ov/<itemId>/gops.h264} in the design). */
    public static final String H264_FILE = "gops.h264";
    /** The frame index. */
    public static final String INDEX_FILE = "gops.idx";
    static final int MAGIC = 0x54544F56; // "TTOV"
    static final int VERSION = 1;
    static final int HEADER_BYTES = 12;
    static final int ENTRY_BYTES = 4 + 8 + 4 + 1;
    static final int FLAG_IDR = 1;

    private final Path dir;
    private final int frameCount;
    private final int[] gopStarts;
    private final long[] offsets;
    private final int[] sizes;
    // guarded by this
    private final BitSet complete = new BitSet();

    /**
     * Empty state (no GOP complete) for the mezzanine's video track.
     *
     * @param dir   the directory holding (or about to hold) {@value #H264_FILE} and {@value #INDEX_FILE}
     * @param video the mezzanine's video track (from its {@code index.bin})
     * @throws IllegalArgumentException if the track is empty or doesn't start with a keyframe
     */
    public OverlayGops(Path dir, Track video) {
        this.dir = Objects.requireNonNull(dir);
        this.frameCount = video.sampleCount();
        this.gopStarts = gopStarts(video);
        this.offsets = new long[frameCount];
        this.sizes = new int[frameCount];
    }

    /**
     * The first frame of every GOP of {@code video}: its keyframes.
     *
     * @throws IllegalArgumentException if the track is empty or frame 0 isn't a keyframe
     */
    public static int[] gopStarts(Track video) {
        int n = video.sampleCount();
        if (n == 0 || !video.isKeyframe(0)) {
            throw new IllegalArgumentException("the video must start with a keyframe");
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (video.isKeyframe(i)) {
                count++;
            }
        }
        int[] starts = new int[count];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (video.isKeyframe(i)) {
                starts[k++] = i;
            }
        }
        return starts;
    }

    /** The job's directory. */
    public Path dir() {
        return dir;
    }

    /** {@value #H264_FILE} in the job's directory. */
    public Path h264File() {
        return dir.resolve(H264_FILE);
    }

    /** {@value #INDEX_FILE} in the job's directory. */
    public Path indexFile() {
        return dir.resolve(INDEX_FILE);
    }

    /** The mezzanine's frame count. */
    @Override
    public int frameCount() {
        return frameCount;
    }

    /** The mezzanine's GOP count. */
    public int gopCount() {
        return gopStarts.length;
    }

    /** The first frame of GOP {@code g}. */
    public int gopStart(int g) {
        return gopStarts[g];
    }

    /** One past the last frame of GOP {@code g}. */
    public int gopEnd(int g) {
        return g + 1 < gopStarts.length ? gopStarts[g + 1] : frameCount;
    }

    /** The GOP holding frame {@code frame}. */
    public int gopOf(int frame) {
        if (frame < 0 || frame >= frameCount) {
            throw new IndexOutOfBoundsException("frame " + frame + " of " + frameCount);
        }
        int i = Arrays.binarySearch(gopStarts, frame);
        return i >= 0 ? i : -i - 2;
    }

    /** Whether GOP {@code g} is complete: all its frames are written and may be played. */
    public synchronized boolean isComplete(int g) {
        return complete.get(g);
    }

    /** The complete GOPs (a copy). */
    public synchronized BitSet completed() {
        return (BitSet) complete.clone();
    }

    /** Whether every GOP from {@code g} to the end is complete. */
    public synchronized boolean completeFrom(int g) {
        int firstMissing = complete.nextClearBit(g);
        return firstMissing >= gopStarts.length;
    }

    /** Where frame {@code frame} starts in {@value #H264_FILE}; valid once its GOP {@link #isComplete is complete}. */
    public long offset(int frame) {
        return offsets[frame];
    }

    /** Frame {@code frame}'s size in bytes; valid once its GOP {@link #isComplete is complete}. */
    public int size(int frame) {
        return sizes[frame];
    }

    /**
     * The frames of the GOP starting at {@code keyframe} if it is complete, read from
     * {@value #H264_FILE} (one positional read per frame); null if it isn't complete, or
     * {@code keyframe} isn't the first frame of a GOP.
     */
    @Override
    public byte[][] gop(int keyframe) throws IOException {
        if (keyframe < 0 || keyframe >= frameCount) {
            return null;
        }
        int g = Arrays.binarySearch(gopStarts, keyframe);
        if (g < 0 || !isComplete(g)) {
            return null;
        }
        int end = gopEnd(g);
        byte[][] frames = new byte[end - keyframe][];
        try (FileChannel ch = FileChannel.open(h264File(), StandardOpenOption.READ)) {
            for (int f = keyframe; f < end; f++) {
                ByteBuffer buf = ByteBuffer.allocate(sizes[f]);
                while (buf.hasRemaining()) {
                    if (ch.read(buf, offsets[f] + buf.position()) < 0) {
                        throw new EOFException(h264File() + " ends before frame " + f);
                    }
                }
                frames[f - keyframe] = buf.array();
            }
        }
        return frames;
    }

    /** Records where a frame was written (before its GOP is marked complete). */
    void frameWritten(int frame, long offset, int size) {
        offsets[frame] = offset;
        sizes[frame] = size;
    }

    /** Publishes GOP {@code g} as complete. */
    synchronized void markComplete(int g) {
        complete.set(g);
    }

    /**
     * Reads a re-encode back from its files (an encode from an earlier run, kept in the cache). A
     * GOP counts as complete when the index has all its frames, in order, with IDR flags matching
     * the mezzanine's keyframes, and their bytes lie within {@value #H264_FILE}; a torn last entry
     * (the job was killed) is ignored.
     *
     * @throws IOException if the files are missing, or the header doesn't match (another format, or
     *                     another mezzanine's frame count)
     */
    public static OverlayGops load(Path dir, Track video) throws IOException {
        OverlayGops gops = new OverlayGops(dir, video);
        long dataLength = Files.size(gops.h264File());
        List<long[]> entries = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(gops.indexFile());
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            int magic;
            int version;
            int frames;
            try {
                magic = in.readInt();
                version = in.readInt();
                frames = in.readInt();
            } catch (EOFException e) {
                throw new IOException(gops.indexFile() + ": truncated header");
            }
            if (magic != MAGIC || version != VERSION) {
                throw new IOException(gops.indexFile() + ": not a version " + VERSION + " overlay index");
            }
            if (frames != gops.frameCount) {
                throw new IOException(gops.indexFile() + ": made for " + frames + " frames, the video has "
                        + gops.frameCount);
            }
            while (true) {
                int frame;
                long offset;
                int size;
                int flags;
                try {
                    frame = in.readInt();
                    offset = in.readLong();
                    size = in.readInt();
                    flags = in.readUnsignedByte();
                } catch (EOFException e) {
                    break; // the end, or a torn last entry
                }
                entries.add(new long[] {frame, offset, size, flags});
            }
        }
        int expected = -1;
        int gop = -1;
        for (long[] e : entries) {
            int frame = (int) e[0];
            long offset = e[1];
            int size = (int) e[2];
            boolean idr = (e[3] & FLAG_IDR) != 0;
            if (frame < 0 || frame >= gops.frameCount || size <= 0 || offset < 0 || offset + size > dataLength
                    || idr != video.isKeyframe(frame)) {
                break;
            }
            if (frame != expected) {
                // only the start of a GOP may follow a gap (an encode starts at a GOP)
                if (expected >= 0 || !idr) {
                    break;
                }
                gop = gops.gopOf(frame);
            } else if (idr) {
                gop++;
            }
            gops.frameWritten(frame, offset, size);
            expected = frame + 1;
            if (expected == gops.gopEnd(gop)) {
                gops.markComplete(gop);
            }
        }
        return gops;
    }
}
