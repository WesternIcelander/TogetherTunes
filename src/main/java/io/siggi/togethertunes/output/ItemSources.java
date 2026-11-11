package io.siggi.togethertunes.output;

import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import java.io.IOException;
import java.util.function.Supplier;

/** Picks the source for an item: {@link VideoItemSource}, or {@link AudioItemSource} for audio. */
public final class ItemSources {
    private ItemSources() {
    }

    /**
     * The source for an item.
     *
     * <ul>
     *   <li>A video item without {@code presentAsAudio} → {@link VideoItemSource}.</li>
     *   <li>A video item with {@code presentAsAudio} → {@link AudioItemSource} with {@code card}
     *       (required: a video item's {@code card.mp4} isn't in its index; load it with
     *       {@link CardGop#load}).</li>
     *   <li>An audio item → {@link AudioItemSource} with {@code card}, or, if that is null, the
     *       card in the index's video track ({@code card.mp4}).</li>
     * </ul>
     *
     * @param videoItem      whether the item is a video item ({@code items.kind = 'video'})
     * @param presentAsAudio a video item of media type {@code audio}; ignored for audio items
     * @param card           the card to show when played as audio (the room's overlaid version when
     *                       the overlay is on), or null to use the audio item's indexed card
     */
    public static ItemSource forItem(String itemId, boolean videoItem, boolean presentAsAudio,
            SampleIndex index, SampleData data, CardGop card, long startMs) throws IOException {
        return forItem(itemId, videoItem, presentAsAudio, index, data, card, null, startMs);
    }

    /**
     * {@link #forItem(String, boolean, boolean, SampleIndex, SampleData, CardGop, long)} with the
     * room's overlaid card for an item played as audio, given by {@code overlay} once it is ready
     * ({@link AudioItemSource}); ignored for a video item played as video.
     */
    public static ItemSource forItem(String itemId, boolean videoItem, boolean presentAsAudio,
            SampleIndex index, SampleData data, CardGop card, Supplier<CardGop> overlay, long startMs)
            throws IOException {
        return forItem(itemId, videoItem, presentAsAudio, index, data, card, overlay, null, startMs);
    }

    /**
     * {@link #forItem(String, boolean, boolean, SampleIndex, SampleData, CardGop, Supplier, long)}
     * with the room's overlay re-encode for a video item played as video, asked at every GOP start
     * ({@link VideoItemSource}); ignored for an item played as audio.
     */
    public static ItemSource forItem(String itemId, boolean videoItem, boolean presentAsAudio,
            SampleIndex index, SampleData data, CardGop card, Supplier<CardGop> overlay,
            Supplier<VideoOverlay> videoOverlay, long startMs) throws IOException {
        if (videoItem && !presentAsAudio) {
            return new VideoItemSource(itemId, index, data, videoOverlay, startMs);
        }
        if (card == null) {
            if (videoItem) {
                throw new IllegalArgumentException("a video item shown as audio needs its card");
            }
            card = CardGop.fromTrack(index, index.track(Kind.VIDEO), data);
        }
        return new AudioItemSource(itemId, index, data, card, overlay, startMs, videoItem);
    }
}
