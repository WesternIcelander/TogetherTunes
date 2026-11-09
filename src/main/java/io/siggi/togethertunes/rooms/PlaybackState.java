package io.siggi.togethertunes.rooms;

import java.util.Locale;

/**
 * The state of a room's timeline ({@code rooms.playback_state}, {@code docs/rooms.md#room-state}).
 * {@link #IDLE} means there is no current entry.
 *
 * <p>The enum will grow (for example {@code LIVE}, see {@code docs/future-ideas.md}). Code that
 * switches on it handles every value explicitly and never has an {@code else} that assumes
 * "playing".
 */
public enum PlaybackState {
    IDLE, PLAYING, PAUSED;

    /** The wire and database name ({@code idle}, {@code playing}, {@code paused}). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The state with this wire name, or {@code null} if there is none. */
    public static PlaybackState parse(String s) {
        for (PlaybackState p : values()) {
            if (p.wire().equals(s)) {
                return p;
            }
        }
        return null;
    }
}
