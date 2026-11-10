package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.media.EncodeProfile;
import java.util.ArrayList;
import java.util.List;

/**
 * How a new video trim is made from the current encode ({@code docs/library-and-ingest.md#trim}):
 * which frames of the current version are kept as they are and which are encoded again from the
 * original. No I/O; {@code web/src/trim.ts} mirrors it for the page's hint.
 *
 * <p>Frames are counted on the <b>current encode's grid</b>: frame {@code k} of the current version
 * shows original time {@code curStart + k / 30 s}, and its GOPs start at every 60th frame
 * ({@code curStart + 2 s × g}). A new start on that grid ({@code newStart − curStart} a multiple
 * of 2 s, either way) keeps the grid, so the new encode is the current one with whole GOPs dropped
 * from or encoded and added at the front, and at the end either cut (no B-frames, so dropping the
 * last frames of a GOP is valid) or, when it ends later, encoded again from the start of the
 * current last GOP (which may have been cut short). A new start anywhere else restarts the grid:
 * a full re-encode (invariant 2).
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
     * Frames {@code [from, to)} on the current encode's grid (negative: before its start).
     * An {@link Source#ENCODE} piece starts on a GOP boundary.
     */
    public record Piece(Source source, int from, int to) {
        public int frames() {
            return to - from;
        }

        /** Where the piece starts on the original's timeline, in ms (for an encode, a whole GOP). */
        public long originalStartMs(long curStartMs) {
            return curStartMs + Math.floorDiv(from, GOP) * GOP_MS + (long) Math.floorMod(from, GOP) * 1000 / FPS;
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
    }

    private TrimPlan() {
    }

    /** Frames of an encode {@code durationMs} long: those with {@code k / 30 s < duration}. */
    public static int frames(long durationMs) {
        return (int) Math.max(0, (durationMs * FPS + 999) / 1000);
    }

    /**
     * Plans the new trim.
     *
     * @param curStartMs the current encode's start on the original ({@code videoStartMs})
     * @param curFrames  the current encode's video frame count
     * @param newStartMs the new start on the original
     * @param newEndMs   the new end on the original (resolved: not past the original's end)
     */
    public static Plan plan(long curStartMs, int curFrames, long newStartMs, long newEndMs) {
        if (newStartMs < 0 || newEndMs <= newStartMs) {
            throw new IllegalArgumentException("bad range " + newStartMs + ".." + newEndMs);
        }
        int n = frames(newEndMs - newStartMs);
        long delta = newStartMs - curStartMs;
        if (delta % GOP_MS != 0) {
            return full(n, "the new start isn't on the current encode's 2 s keyframe grid");
        }
        if (curFrames <= 0) {
            return full(n, "the current encode has no video");
        }
        int d = Math.toIntExact(delta / GOP_MS * GOP);
        int end = d + n;
        int lastGopStart = (curFrames - 1) / GOP * GOP;
        int reuseEnd = end <= curFrames ? end : lastGopStart;
        int midFrom = Math.max(d, 0);
        int midTo = Math.min(end, reuseEnd);
        if (midTo <= midFrom) {
            return full(n, "nothing of the current encode can be kept");
        }
        List<Piece> pieces = new ArrayList<>();
        if (d < 0) {
            pieces.add(new Piece(Source.ENCODE, d, 0));
        }
        pieces.add(new Piece(Source.REUSE, midFrom, midTo));
        if (end > midTo) {
            pieces.add(new Piece(Source.ENCODE, midTo, end));
        }
        return new Plan(Mode.SMART, List.copyOf(pieces), n, null);
    }

    private static Plan full(int frames, String reason) {
        return new Plan(Mode.FULL, List.of(), frames, reason);
    }
}
