package io.siggi.togethertunes.output;

import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.ingest.SilenceFrame;
import java.util.function.Supplier;

/**
 * A filler: a card GOP repeated with the silence frame repeated, for as long as the packager wants
 * ({@code docs/program-output.md#units}). It never ends by itself; the unit builder emits it in
 * whole 2 s units and switches away at a unit boundary.
 */
public final class FillerSource implements ProgramSource {
    /** Why a filler is playing. */
    public enum Filler {
        /** The timeline is paused (also any state the packager doesn't recognise). */
        PAUSED(CardRenderer.Filler.PAUSED),
        /** The room is idle: the queue is empty. Shows the room's idle card. */
        IDLE(CardRenderer.Filler.QUEUE_EMPTY),
        /** The next item's index isn't loaded yet. */
        LOADING(CardRenderer.Filler.LOADING),
        /**
         * The pause between items without a transition card ({@code docs/program-output.md#transitions}):
         * the blank card, played for the gap's length only ({@link GapSource}).
         */
        GAP(CardRenderer.Filler.BLANK);

        private final CardRenderer.Filler serverCard;

        Filler(CardRenderer.Filler serverCard) {
            this.serverCard = serverCard;
        }

        /** The server-wide card for this filler (a room may use its own card instead). */
        public CardRenderer.Filler serverCard() {
            return serverCard;
        }
    }

    private final Filler filler;
    private final CardGop card;
    private final CardGop.Repeat video;
    private final byte[] silence;

    /**
     * @param card    the card to show (server filler card, room idle card, or an overlaid version)
     * @param silence the silence frame
     */
    public FillerSource(Filler filler, CardGop card, SilenceFrame silence) {
        this(filler, card, silence.frame());
    }

    /** @param silence a raw AAC silence frame (copied) */
    public FillerSource(Filler filler, CardGop card, byte[] silence) {
        this(filler, card, null, silence);
    }

    /**
     * @param overlay the room's overlaid version of {@code card} while the room's overlay is on
     *                (null otherwise), asked at every GOP start ({@link CardGop#repeat(Supplier)});
     *                null: always {@code card}
     * @param silence a raw AAC silence frame (copied)
     */
    public FillerSource(Filler filler, CardGop card, Supplier<CardGop> overlay, byte[] silence) {
        if (filler == null || card == null || silence == null || silence.length == 0) {
            throw new IllegalArgumentException("filler, card and silence are required");
        }
        this.filler = filler;
        this.card = card;
        this.video = overlay == null ? card.repeat() : card.repeat(overlay);
        this.silence = silence.clone();
    }

    public Filler filler() {
        return filler;
    }

    /** The card shown while no overlaid card is given. */
    public CardGop card() {
        return card;
    }

    /** The card the current GOP shows. */
    public CardGop showing() {
        return video.showing();
    }

    @Override
    public VideoSample nextVideo() {
        return video.next();
    }

    @Override
    public byte[] nextAudio() {
        return silence;
    }

    @Override
    public String describe() {
        return "filler " + filler.name().toLowerCase(java.util.Locale.ROOT);
    }
}
