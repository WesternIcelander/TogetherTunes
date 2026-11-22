package io.siggi.togethertunes.settings;

import java.util.Locale;

/**
 * Which YouTube match a resolved music-service link imports ({@code mvfinder.prefer},
 * {@code docs/library-and-ingest.md#links-from-other-music-services}): the music video (or another
 * official video upload standing in for it) with the YouTube Music audio track as the fallback, or
 * the other way round.
 */
public enum LinkPreference {
    MUSIC_VIDEO, SONG;

    /** The stored form: {@code music_video} or {@code song}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
