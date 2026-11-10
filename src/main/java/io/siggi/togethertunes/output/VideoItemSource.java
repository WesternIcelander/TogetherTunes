package io.siggi.togethertunes.output;

import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.IOException;

/**
 * A video item played as video: its mezzanine GOPs from the keyframe at or before the requested
 * position, and its audio from the same media time ({@code docs/program-output.md#units}).
 *
 * <p>Video sample PTS (relative to the source start) = {@code (srcPts − srcStartPts)·90000/timescale},
 * where {@code srcStartPts} is the starting keyframe's PTS. Audio starts at the AAC frame nearest to
 * that keyframe's time (at most half a frame, about 11 ms, away), and the source ends when the
 * audio does. The video may end earlier (the builder holds the last picture) or later (the builder
 * drops what is past the audio's end).
 *
 * <p>Substituting the room's overlay GOPs (M5, {@code docs/overlay.md}) will happen per GOP here;
 * GOPs are read sample by sample, so nothing is read ahead that would have to be thrown away.
 */
public final class VideoItemSource implements ItemSource {
    private final String itemId;
    private final long requestedStartMs;
    private final SampleData data;
    private final Track video;
    private final Track audio;
    private final long videoStartPts;
    private final long mediaStartMs;
    private int nextVideo;
    private int nextAudio;
    private long lastVideoPts = -1;

    /**
     * @param index   the item's index ({@code mezz.mp4} video track, {@code audio.m4a} audio track)
     * @param data    reads the index's files
     * @param startMs media position to start from; negative is treated as 0, and a position past
     *                the end gives a source that ends almost at once
     * @throws IOException if the index lacks a video or audio track, or the video has no keyframe
     */
    public VideoItemSource(String itemId, SampleIndex index, SampleData data, long startMs) throws IOException {
        this.itemId = itemId;
        this.requestedStartMs = startMs;
        this.data = data;
        this.video = index.track(Kind.VIDEO);
        this.audio = index.track(Kind.AUDIO);
        if (video == null || audio == null) {
            throw new IOException("item " + itemId + ": index needs a video and an audio track");
        }
        long target = MediaTime.msToTicks(Math.max(0, startMs), video.timescale());
        int k = video.keyframeAtOrBefore(Math.max(0, video.sampleAtOrBefore(target)));
        if (k < 0) {
            k = video.keyframeAtOrAfter(0);
        }
        if (k < 0) {
            throw new IOException("item " + itemId + ": video has no keyframe");
        }
        this.nextVideo = k;
        this.videoStartPts = video.pts(k);
        long audioTarget = MediaTime.rescale(videoStartPts, video.timescale(), audio.timescale());
        this.nextAudio = nearest(audio, audioTarget);
        this.mediaStartMs = nextAudio < audio.sampleCount()
                ? MediaTime.ticksToMs(audio.pts(nextAudio), audio.timescale())
                : MediaTime.ticksToMs(videoStartPts, video.timescale());
    }

    /** The sample whose PTS is nearest to {@code t} (the earlier one on a tie); 0 for an empty track. */
    static int nearest(Track track, long t) {
        int i = track.sampleAtOrBefore(t);
        if (i < 0) {
            return 0;
        }
        if (i + 1 < track.sampleCount() && track.pts(i + 1) - t < t - track.pts(i)) {
            return i + 1;
        }
        return i;
    }

    @Override
    public VideoSample nextVideo() throws IOException {
        if (nextVideo >= video.sampleCount()) {
            return null;
        }
        int i = nextVideo++;
        byte[] bytes = data.read(video.fileRef(), video.offset(i), video.size(i));
        long pts = MediaTime.rescale(video.pts(i) - videoStartPts, video.timescale(), MediaTime.CLOCK);
        // the index guarantees strictly increasing PTS; keep that after rounding to 90 kHz too
        pts = Math.max(pts, lastVideoPts + 1);
        lastVideoPts = pts;
        return new VideoSample(bytes, pts, video.isKeyframe(i));
    }

    @Override
    public byte[] nextAudio() throws IOException {
        if (nextAudio >= audio.sampleCount()) {
            return null;
        }
        int i = nextAudio++;
        return data.read(audio.fileRef(), audio.offset(i), audio.size(i));
    }

    @Override
    public String itemId() {
        return itemId;
    }

    @Override
    public long requestedStartMs() {
        return requestedStartMs;
    }

    @Override
    public long mediaStartMs() {
        return mediaStartMs;
    }

    @Override
    public String describe() {
        return "video item " + itemId + "@" + mediaStartMs + "ms";
    }
}
