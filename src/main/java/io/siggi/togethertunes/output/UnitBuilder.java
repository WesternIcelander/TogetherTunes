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
 * {@code S_n + p}. Frames less than one frame interval (3000 ticks) before {@code E_n} are
 * dropped (apart from the unit's IDR), which truncates a repeated card GOP (or a video longer than
 * its audio) at the end; the next source starts with a fresh IDR at {@code S_{n+1}}, so no picture
 * lasts less than a frame interval at a splice (players and ffmpeg retime by frame rate and choke on
 * two frames within one interval). For the same reason a source whose audio would end less than a
 * frame interval after a unit start doesn't get that unit: its last frames go into the unit before
 * (which then lasts up to 3000 + 1919 ticks longer than 2 s), and a source with less audio than
 * that in all gives no unit at all.</li>
 * <li><b>Units</b> run from one of the source's IDRs to its next (2 s for the profile's closed
 * 60-frame GOPs) and carry the audio frames whose PTS falls in that span, so a filler, which never
 * ends by itself, is always emitted in whole 2 s units. A unit ends early where its source
 * ends, and a video whose first GOP is short (a smart trim's, invariant 2) gives a short first
 * unit when played from its start: its next IDR comes sooner, nothing else changes (the audio
 * frames still follow each other 1920 ticks apart across the boundary). Since audio frames don't divide 2 s (93.75 frames), a unit's last audio frame usually
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
    /** Audio frames read ahead of {@link #audioFrames} (at most a few). */
    private final List<byte[]> aheadAudio = new ArrayList<>(4);
    /** Whether the source's audio has ended (the read-ahead holds what is left). */
    private boolean audioDone;
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
        aheadAudio.clear();
        audioDone = false;
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
            started = true;
        }
        long start = clock;
        if (audioFrames == 0 && audioEndsBefore(start + ProgramSource.VIDEO_FRAME_TICKS)) {
            // less audio than one video frame (none at all, or e.g. a seek to the very end): playing
            // it would put the next source's IDR less than a frame after this one, so it gives nothing
            ended = true;
            return null;
        }

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
        // a source whose audio ends less than one video frame past the boundary ends in this unit
        // instead (its next unit would be shorter than a frame)
        long limit = audioEndsBefore(boundary + ProgramSource.VIDEO_FRAME_TICKS) ? Long.MAX_VALUE : boundary;
        while (peekAudio(0) != null) {
            long pts = sourceStart + audioFrames * AUDIO;
            if (pts >= limit) {
                break;
            }
            audio.add(new Unit.Audio(aheadAudio.remove(0), pts));
            audioFrames++;
        }
        long sourceAudioEnd = sourceStart + audioFrames * AUDIO;
        audioEnd = Math.max(audioEnd, sourceAudioEnd);

        if (peekAudio(0) == null) {
            // the source ended in this unit, at E_n: drop video less than a frame before it (truncates
            // a card GOP or a video longer than its audio), so the next source's IDR at E_n comes at
            // least one frame interval after this unit's last picture; the unit's IDR always stays
            ended = true;
            long end = sourceAudioEnd;
            long lastAllowed = end - ProgramSource.VIDEO_FRAME_TICKS;
            Unit.Video idr = video.get(0);
            video.removeIf(v -> v != idr && v.pts() > lastAllowed);
            clock = Math.max(start, end);
            return new Unit(start, end, end, video, audio, source, true);
        }
        clock = boundary;
        return new Unit(start, boundary, audioEnd, video, audio, source, false);
    }

    /** Audio frame {@code audioFrames + i} of the source, reading ahead; null past its end. */
    private byte[] peekAudio(int i) throws IOException {
        while (aheadAudio.size() <= i && !audioDone) {
            byte[] f = source.nextAudio();
            if (f == null) {
                audioDone = true;
            } else {
                aheadAudio.add(f);
            }
        }
        return i < aheadAudio.size() ? aheadAudio.get(i) : null;
    }

    /** Whether the source's audio (from frame {@link #audioFrames} on) ends before {@code limit}. */
    private boolean audioEndsBefore(long limit) throws IOException {
        for (int i = 0; ; i++) {
            if (sourceStart + (audioFrames + i) * AUDIO >= limit) {
                return false;
            }
            if (peekAudio(i) == null) {
                return true;
            }
        }
    }

    @Override
    public void close() {
        if (source != null) {
            source.close();
            source = null;
        }
    }
}
