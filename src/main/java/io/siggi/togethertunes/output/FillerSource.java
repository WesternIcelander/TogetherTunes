package io.siggi.togethertunes.output;

import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.ingest.SilenceFrame;

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
        LOADING(CardRenderer.Filler.LOADING);

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
        if (filler == null || card == null || silence == null || silence.length == 0) {
            throw new IllegalArgumentException("filler, card and silence are required");
        }
        this.filler = filler;
        this.card = card;
        this.video = card.repeat();
        this.silence = silence.clone();
    }

    public Filler filler() {
        return filler;
    }

    public CardGop card() {
        return card;
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
