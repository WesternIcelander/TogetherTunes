package io.siggi.togethertunes.output;

import io.siggi.togethertunes.media.EncodeProfile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns {@link ProgramSource}s into {@link Unit}s on one continuous output clock, following the
 * timestamp rules in {@code docs/program-output.md#timestamp-rules}:
 *
 * <ul>
 * <li>The clock starts at {@link OutputClock#START} (10 s). Timestamps are kept unwrapped here and
 * masked to 33 bits on the wire ({@link OutputClock#wrap}). DTS = PTS (no B-frames).</li>
 * <li><b>Audio drives source boundaries.</b> A source started at {@code S_n} has its audio frame
 * <i>k</i> at {@code S_n + k·1920}. It ends at {@code E_n = S_n + K·1920} (<i>K</i> frames
 * emitted), and the next source starts at {@code S_{n+1} = E_n}: no audio gaps or overlaps at any
 * splice. When the packager switches away from a source that hasn't ended (at a unit boundary), the
 * same holds with <i>K</i> = the frames emitted so far: the new source starts where the emitted
 * audio ends, which is up to one frame past the unit boundary.</li>
 * <li><b>Video follows audio.</b> A video sample at source-relative PTS <i>p</i> goes out at
 * {@code S_n + p}. Frames at or after {@code E_n} are dropped, which truncates a repeated card GOP
 * (or a video longer than its audio) at the end; the next source starts with a fresh IDR at
 * {@code S_{n+1}}.</li>
 * <li><b>Units</b> run from one of the source's IDRs to its next (2 s for the profile's closed
 * 60-frame GOPs) and carry the audio frames whose PTS falls in that span, so a filler, which never
 * ends by itself, is always emitted in whole 2 s units. A unit ends early where its source
 * ends. Since audio frames don't divide 2 s (93.75 frames), a unit's last audio frame usually
 * straddles its boundary; when the source ends there or the next unit is a new source, the unit
 * runs on to that frame's end, so it can last up to 1919 ticks (21.3 ms) longer than 2 s.</li>
 * <li>If a source's video runs out before its audio, the last frame stays on screen to the end of
 * that 2 s unit; any further unit (rare: only when the audio outlasts the video by more than the
 * rest of a GOP) repeats the source's last IDR frame once, at its start, so every unit still starts
 * with an IDR.</li>
 * </ul>
 *
 * <p>Applying timeline changes (which source to play, and when) is up to the caller: it calls
 * {@link #play} between units; {@link #switchPoint()} says where a new source would start. The builder owns the source it plays and closes it when switching
 * away and on {@link #close}. Not thread-safe: one packager thread uses it.
 */
public final class UnitBuilder implements AutoCloseable {
    /** A unit's normal length: one GOP, 2 s. */
    public static final long UNIT_TICKS = (long) EncodeProfile.GOP_MS * OutputClock.HZ / 1000;

    private static final long AUDIO = ProgramSource.AUDIO_FRAME_TICKS;

    /** Where the next unit starts. */
    private long clock;
    /** Where the audio emitted so far ends. */
    private long audioEnd;

    private ProgramSource source;
    /** {@code S_n}: the current source's start on the output clock. */
    private long sourceStart;
    /** Audio frames taken from the current source so far. */
    private long audioFrames;
    private boolean started;
    private boolean ended;
    /** The current source's next IDR (source-relative), read ahead; null once its video ran out. */
    private VideoSample pendingVideo;
    /** The next audio frame, read ahead; null once the source ended. */
    private byte[] pendingAudio;
    /** The last IDR the current source gave, repeated if its video runs out before its audio. */
    private VideoSample lastIdr;

    /** A builder whose clock starts at {@link OutputClock#START}. */
    public UnitBuilder() {
        this(OutputClock.START);
    }

    /** A builder whose clock starts at {@code clockStart} (tests use this to cross the 33-bit wrap). */
    UnitBuilder(long clockStart) {
        if (clockStart < 0) {
            throw new IllegalArgumentException("clockStart < 0");
        }
        this.clock = clockStart;
        this.audioEnd = clockStart;
    }

    /**
     * Plays {@code next} from the next unit on, starting it at {@link #switchPoint()}. Closes the
     * previous source. Call it between units only (this is the unit boundary).
     */
    public void play(ProgramSource next) {
        if (next == null) {
            throw new IllegalArgumentException("source is null");
        }
        if (source != null && source != next) {
            source.close();
        }
        clock = switchPoint();
        source = next;
        sourceStart = clock;
        audioFrames = 0;
        started = false;
        ended = false;
        pendingVideo = null;
        pendingAudio = null;
        lastIdr = null;
    }

    /**
     * Where the next unit starts on the (unwrapped) output clock if nothing changes: the current
     * source's next IDR (the last unit's {@link Unit#boundaryPts()}), or {@link #switchPoint()} when
     * there is no source yet, it hasn't started, or it has ended.
     */
    public long nextUnitStart() {
        return source == null || ended || !started ? switchPoint() : clock;
    }

    /**
     * Where a source passed to {@link #play} now would start ({@code S_{n+1}}): where the audio
     * emitted so far ends. Up to one audio frame (1919 ticks) past {@link #nextUnitStart()}.
     */
    public long switchPoint() {
        return Math.max(clock, audioEnd);
    }

    /** The source being played, or null before the first {@link #play}. */
    public ProgramSource source() {
        return source;
    }

    /** Whether the current source has ended: the next unit needs a new source ({@link #play}). */
    public boolean sourceEnded() {
        return ended;
    }

    /**
     * Builds the next unit from the current source.
     *
     * @return the unit, or null if the source ended without giving anything (it had no audio at
     *         all); {@link #sourceEnded()} is then true
     * @throws IllegalStateException if there is no source or it has ended
     * @throws IOException           if reading fails or the source breaks the {@link ProgramSource}
     *                               contract (first video sample not an IDR at 0, video PTS not
     *                               strictly increasing)
     */
    public Unit next() throws IOException {
        if (source == null) {
            throw new IllegalStateException("no source");
        }
        if (ended) {
            throw new IllegalStateException("source ended: " + source.describe());
        }
        if (!started) {
            VideoSample first = source.nextVideo();
            if (first == null || !first.idr() || first.pts() != 0) {
                throw new IOException(source.describe() + ": video must start with an IDR at 0");
            }
            pendingVideo = first;
            pendingAudio = source.nextAudio();
            started = true;
        }
        long start = clock;

        List<Unit.Video> video = new ArrayList<>(EncodeProfile.GOP_FRAMES);
        long boundary;
        if (pendingVideo != null) {
            VideoSample s = pendingVideo;
            lastIdr = s;
            long last = sourceStart + s.pts();
            video.add(new Unit.Video(s.data(), last, true));
            pendingVideo = null;
            while ((s = source.nextVideo()) != null) {
                long pts = sourceStart + s.pts();
                if (pts <= last) {
                    throw new IOException(source.describe() + ": video PTS not increasing (" + s.pts() + ")");
                }
                if (s.idr()) {
                    pendingVideo = s;
                    break;
                }
                video.add(new Unit.Video(s.data(), pts, false));
                last = pts;
            }
            boundary = pendingVideo != null
                    ? sourceStart + pendingVideo.pts()
                    : Math.max(start + UNIT_TICKS, last + ProgramSource.VIDEO_FRAME_TICKS);
        } else {
            // the video ran out earlier: hold its last picture as well as we can with an IDR
            video.add(new Unit.Video(lastIdr.data(), start, true));
            boundary = start + UNIT_TICKS;
        }

        List<Unit.Audio> audio = new ArrayList<>((int) (UNIT_TICKS / AUDIO) + 2);
        while (pendingAudio != null) {
            long pts = sourceStart + audioFrames * AUDIO;
            if (pts >= boundary) {
                break;
            }
            audio.add(new Unit.Audio(pendingAudio, pts));
            audioFrames++;
            pendingAudio = source.nextAudio();
        }
        long sourceAudioEnd = sourceStart + audioFrames * AUDIO;
        audioEnd = Math.max(audioEnd, sourceAudioEnd);

        if (pendingAudio == null) {
            // the source ended in this unit, at E_n: drop video at or after it (truncates a card GOP)
            ended = true;
            long end = sourceAudioEnd;
            video.removeIf(v -> v.pts() >= end);
            clock = Math.max(start, end);
            if (video.isEmpty()) {
                return null;
            }
            return new Unit(start, end, end, video, audio, source, true);
        }
        clock = boundary;
        return new Unit(start, boundary, audioEnd, video, audio, source, false);
    }

    @Override
    public void close() {
        if (source != null) {
            source.close();
            source = null;
        }
    }
}
