package io.siggi.togethertunes.rooms;

/**
 * The room settings the control modes and queue operations read
 * ({@code docs/rooms.md#room-settings-settings_json}): {@code allowDownvotes} (default
 * {@code false}), {@code voteSkipThreshold} (default {@code 0.5}, or {@code "off"}),
 * {@code requestsPerActor} (default 3) and {@code noRepeatWindow} (default 10). Part of
 * {@link RoomSettings}, which reads and validates them.
 *
 * @param voteSkipThreshold the fraction of present identified members whose skip votes skip the
 *                          current entry, in {@code (0, 1]}, or {@code null} when vote-skip is off
 * @param requestsPerActor  how many waiting (queued or suggested) entries one user or guest may
 *                          have at a time, {@code 1..}{@value #MAX_REQUESTS_PER_ACTOR}; owner and
 *                          moderators are exempt
 * @param noRepeatWindow    refuse an item that was played in the last this many entries,
 *                          {@code 0} (off) {@code ..}{@value #MAX_NO_REPEAT_WINDOW}; owner and
 *                          moderators are exempt
 */
public record ControlSettings(boolean allowDownvotes, Double voteSkipThreshold, int requestsPerActor,
                              int noRepeatWindow) {
    public static final int MAX_REQUESTS_PER_ACTOR = 1000;
    public static final int MAX_NO_REPEAT_WINDOW = 1000;
    public static final int DEFAULT_REQUESTS_PER_ACTOR = 3;
    public static final int DEFAULT_NO_REPEAT_WINDOW = 10;
    public static final ControlSettings DEFAULTS = new ControlSettings(false, 0.5);

    /** Downvotes and threshold as given, the other settings at their defaults. */
    public ControlSettings(boolean allowDownvotes, Double voteSkipThreshold) {
        this(allowDownvotes, voteSkipThreshold, DEFAULT_REQUESTS_PER_ACTOR, DEFAULT_NO_REPEAT_WINDOW);
    }

    /** The control settings of stored {@code settings_json}; see {@link RoomSettings#parse}. */
    public static ControlSettings parse(String settingsJson) {
        return RoomSettings.parse(settingsJson).control();
    }

    /**
     * How many skip votes skip the current entry with {@code presentIdentified} identified members
     * present (listen + remote): the threshold fraction of them, rounded up, at least 1. Presence
     * below 1 counts as 1, so a lone voter can always reach it.
     *
     * @throws IllegalStateException when vote-skip is off
     */
    public int skipVotesNeeded(int presentIdentified) {
        if (voteSkipThreshold == null) {
            throw new IllegalStateException("vote-skip is off");
        }
        int present = Math.max(1, presentIdentified);
        // The epsilon keeps 0.3 * 10 = 3.0000000000000004 at 3.
        return Math.max(1, (int) Math.ceil(voteSkipThreshold * present - 1e-9));
    }
}
