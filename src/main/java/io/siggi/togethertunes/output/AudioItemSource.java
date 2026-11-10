package io.siggi.togethertunes.output;

import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.IOException;

/**
 * An item played as audio: the item's card GOP repeated as video (the room's overlaid card when the
 * overlay is on; that is just the card passed in), and the item's audio from the AAC frame that
 * contains the requested position. Used for audio items and for video items with
 * {@code presentAsAudio} ({@code docs/program-output.md#units}). The source ends with the audio;
 * the card video never runs out.
 */
public final class AudioItemSource implements ItemSource {
    private final String itemId;
    private final long requestedStartMs;
    private final boolean presentAsAudio;
    private final SampleData data;
    private final Track audio;
    private final CardGop card;
    private final CardGop.Repeat cardVideo;
    private final long mediaStartMs;
    private int nextAudio;

    /**
     * @param index          the item's index; only its audio track is used
     * @param card           the card to show (the item's card, or the room's overlaid version)
     * @param startMs        media position to start from; negative is treated as 0
     * @param presentAsAudio true for a video item shown as audio (informational)
     * @throws IOException if the index has no audio track
     */
    public AudioItemSource(String itemId, SampleIndex index, SampleData data, CardGop card, long startMs,
            boolean presentAsAudio) throws IOException {
        if (card == null) {
            throw new IllegalArgumentException("card is null");
        }
        this.itemId = itemId;
        this.requestedStartMs = startMs;
        this.presentAsAudio = presentAsAudio;
        this.data = data;
        this.audio = index.track(Kind.AUDIO);
        if (audio == null) {
            throw new IOException("item " + itemId + ": index has no audio track");
        }
        this.card = card;
        this.cardVideo = card.repeat();
        long target = MediaTime.msToTicks(Math.max(0, startMs), audio.timescale());
        this.nextAudio = Math.max(0, audio.sampleAtOrBefore(target));
        this.mediaStartMs = nextAudio < audio.sampleCount()
                ? MediaTime.ticksToMs(audio.pts(nextAudio), audio.timescale())
                : Math.max(0, startMs);
    }

    /** Whether this is a video item shown as audio. */
    public boolean presentAsAudio() {
        return presentAsAudio;
    }

    public CardGop card() {
        return card;
    }

    @Override
    public VideoSample nextVideo() {
        return cardVideo.next();
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
        return (presentAsAudio ? "video item as audio " : "audio item ") + itemId + "@" + mediaStartMs + "ms";
    }
}
