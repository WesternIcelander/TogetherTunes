package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.media.EncodeProfile;
import java.util.ArrayList;
import java.util.List;

/**
 * How a new video trim is made from the current encode ({@code docs/library-and-ingest.md#trim}):
 * which frames of the current version are kept as they are and which are encoded again from the
 * original. No I/O; {@code web/src/trim.ts} mirrors it for the page's hint.
 *
 * <p>Frame {@code k} of the current version shows original time {@code curStart + k / 30 s}. Its
 * first GOP has {@code f} frames (1..60; 60 unless a smart trim made it shorter), and its later
 * GOPs start at frames {@code f + 60·j} (invariant 2). Those later IDRs (and frame 0 too when
 * {@code f = 60}) are the places a new encode can continue from with the current GOPs; the current
 * short first GOP is never kept in the middle of a stream.
 *
 * <p><b>One rule for any start change:</b> let {@code S} be the new start and {@code B} the first
 * of those IDRs at or after {@code S} (within half a frame) that the current encode has. Then
 * {@code [S, B)} is encoded from the original as a <em>head</em> of {@code h = round((B − S)·30)}
 * frames whose first GOP is {@code h mod 60} frames (60 if that is 0), followed by whole 2 s GOPs,
 * and the current GOPs are kept from {@code B} on ({@code h = 0}: nothing is encoded at the front).
 * A start that doesn't change keeps everything from frame 0. At the end the kept frames are cut (no
 * B-frames, so dropping the last frames of a GOP is valid) or, when the new range ends later, the
 * current last GOP (which may have been cut short) is encoded again from its start together with
 * what is new. A full re-encode is only the fallback: the current encode has no video, or nothing of
 * it can be kept.
 *
 * <p>The head's frames show {@code S + k / 30 s}, as a full encode from {@code S} would; the kept
 * frames continue at {@code B}, which is within half a frame of {@code S + h / 30 s}.
 */
public final class TrimPlan {
    /** Frames per second of every encode. */
    static final int FPS = 30;
    static final int GOP = EncodeProfile.GOP_FRAMES;
    static final long GOP_MS = EncodeProfile.GOP_MS;

    public enum Mode {
        /** Reuse GOPs of the current version; encode only what is new. */
        SMART,
        /** Encode the whole new range from the original. */
        FULL
    }

    public enum Source {
        /** Frames copied from the current version. */
        REUSE,
        /** Frames encoded from the original. */
        ENCODE
    }

    /**
     * A run of the new encode's frames.
     *
     * @param source   reused from the current version, or encoded from the original
     * @param from     for {@link Source#REUSE}: the current version's frame it starts at (an IDR);
     *                 for {@link Source#ENCODE}: -1
     * @param frames   how many frames
     * @param startUs  where its first frame lies on the original's timeline, in µs
     * @param firstGop its first GOP's length in frames (1..60): an IDR at its frame 0, then at
     *                 {@code firstGop + 60·k}
     */
    public record Piece(Source source, int from, int frames, long startUs, int firstGop) {
        /** For {@link Source#REUSE}: the current version's frame after its last. */
        public int to() {
            return from + frames;
        }
    }

    /**
     * @param mode   smart or full
     * @param pieces in output order (empty for {@link Mode#FULL})
     * @param frames the new encode's frame count (if the original has video all the way)
     * @param reason why a full re-encode is needed, else null
     */
    public record Plan(Mode mode, List<Piece> pieces, int frames, String reason) {
        /** Frames encoded from the original. */
        public int encodedFrames() {
            if (mode == Mode.FULL) {
                return frames;
            }
            int n = 0;
            for (Piece p : pieces) {
                if (p.source() == Source.ENCODE) {
                    n += p.frames();
                }
            }
            return n;
        }

        /** Whether no video is encoded at all (only cut). */
        public boolean cutOnly() {
            return mode == Mode.SMART && encodedFrames() == 0;
        }

        /** The new encode's first GOP in frames (60 for a full re-encode). */
        public int firstGop() {
            return pieces.isEmpty() ? GOP : pieces.get(0).firstGop();
        }
    }

    private TrimPlan() {
    }

    /** Frames of an encode {@code durationMs} long: those with {@code k / 30 s < duration}. */
    public static int frames(long durationMs) {
        return (int) Math.max(0, (durationMs * FPS + 999) / 1000);
    }

    /** Where frame {@code k} of an encode starting at {@code startMs} lies on the original, in µs (rounded). */
    static long frameUs(long startMs, int k) {
        return startMs * 1000 + Math.round(k * 1_000_000.0 / FPS);
    }

    /** {@link #plan(long, int, int, long, long)} for a current encode with a normal 60-frame first GOP. */
    public static Plan plan(long curStartMs, int curFrames, long newStartMs, long newEndMs) {
        return plan(curStartMs, curFrames, GOP, newStartMs, newEndMs);
    }

    /**
     * Plans the new trim.
     *
     * @param curStartMs  the current encode's start on the original ({@code videoStartMs})
     * @param curFrames   the current encode's video frame count
     * @param curFirstGop the current encode's first GOP in frames (1..60)
     * @param newStartMs  the new start on the original
     * @param newEndMs    the new end on the original (resolved: not past the original's end)
     */
    public static Plan plan(long curStartMs, int curFrames, int curFirstGop, long newStartMs, long newEndMs) {
        if (newStartMs < 0 || newEndMs <= newStartMs) {
            throw new IllegalArgumentException("bad range " + newStartMs + ".." + newEndMs);
        }
        if (curFirstGop < 1 || curFirstGop > GOP) {
            throw new IllegalArgumentException("first GOP of " + curFirstGop + " frames");
        }
        int n = frames(newEndMs - newStartMs);
        if (curFrames <= 0) {
            return full(n, "the current encode has no video");
        }
        long delta = newStartMs - curStartMs;
        // B: the first IDR the new encode can continue from, h: the frames encoded before it
        int b = -1;
        int h = 0;
        if (delta == 0) {
            b = 0;
        } else {
            for (int k = curFirstGop == GOP ? 0 : curFirstGop; k < curFrames; k += GOP) {
                // h = round(k − delta·30/1000) = floor((100k − 3·delta + 50) / 100)
                long x = 100L * k - 3 * delta + 50;
                if (x >= 0) {
                    b = k;
                    h = (int) (x / 100);
                    break;
                }
            }
        }
        if (b < 0) {
            return full(n, "the new start is past the current encode's last keyframe");
        }
        int end = b + n - h; // on the current encode's frames
        int lastGopStart = (curFrames - 1) < curFirstGop ? 0
                : curFirstGop + (curFrames - 1 - curFirstGop) / GOP * GOP;
        int reuseEnd = end <= curFrames ? end : lastGopStart;
        int midTo = Math.min(end, reuseEnd);
        if (midTo <= b) {
            return full(n, "nothing of the current encode can be kept");
        }
        List<Piece> pieces = new ArrayList<>();
        if (h > 0) {
            int first = h % GOP == 0 ? GOP : h % GOP;
            pieces.add(new Piece(Source.ENCODE, -1, h, newStartMs * 1000, first));
        }
        pieces.add(new Piece(Source.REUSE, b, midTo - b, frameUs(curStartMs, b), b == 0 ? curFirstGop : GOP));
        if (end > midTo) {
            pieces.add(new Piece(Source.ENCODE, -1, end - midTo, frameUs(curStartMs, midTo), GOP));
        }
        return new Plan(Mode.SMART, List.copyOf(pieces), n, null);
    }

    private static Plan full(int frames, String reason) {
        return new Plan(Mode.FULL, List.of(), frames, reason);
    }
}
