package io.siggi.togethertunes.output;

import java.io.IOException;

/**
 * The pause between two items ({@code docs/program-output.md#transitions}): a card source (the next
 * item's card for a transition card, else the blank {@link FillerSource.Filler#GAP} card) cut to a
 * fixed number of AAC frames, after which it ends like an item does. The card's last GOP is
 * truncated at the end by the unit builder; the next item starts with its own IDR there.
 */
public final class GapSource implements ProgramSource {
    private final ProgramSource card;
    private final long audioFrames;
    private final boolean transitionCard;
    private long emitted;

    /**
     * @param card           a source that never ends by itself (a card with silence); owned (closed with this)
     * @param audioFrames    the gap's length in AAC frames ({@link ProgramCue.Gap#audioFrames()}), at least 1
     * @param transitionCard whether {@code card} is the next item's card (for status and logs)
     */
    public GapSource(ProgramSource card, long audioFrames, boolean transitionCard) {
        if (card == null || audioFrames < 1) {
            throw new IllegalArgumentException("a card and at least one audio frame are required");
        }
        this.card = card;
        this.audioFrames = audioFrames;
        this.transitionCard = transitionCard;
    }

    /** How many AAC frames the gap lasts. */
    public long audioFrames() {
        return audioFrames;
    }

    /** Whether it shows the next item's card (else the blank card). */
    public boolean transitionCard() {
        return transitionCard;
    }

    /** The card source it cuts. */
    public ProgramSource card() {
        return card;
    }

    @Override
    public VideoSample nextVideo() throws IOException {
        return card.nextVideo();
    }

    @Override
    public byte[] nextAudio() throws IOException {
        if (emitted >= audioFrames) {
            return null;
        }
        byte[] f = card.nextAudio();
        if (f != null) {
            emitted++;
        }
        return f;
    }

    @Override
    public String describe() {
        return (transitionCard ? "transition card " : "gap ") + (audioFrames * AUDIO_FRAME_TICKS / 90) + "ms";
    }

    @Override
    public void close() {
        card.close();
    }
}
