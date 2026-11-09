package io.siggi.togethertunes.rooms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The room settings the control modes read ({@code docs/rooms.md#room-settings-settings_json}):
 * {@code allowDownvotes} (default {@code false}) and {@code voteSkipThreshold} (default
 * {@code 0.5}, or {@code "off"}). Read leniently from {@code settings_json}: a missing or invalid
 * value falls back to the default (the room-settings step validates what is written).
 *
 * @param voteSkipThreshold the fraction of present identified members whose skip votes skip the
 *                          current entry, in {@code (0, 1]}, or {@code null} when vote-skip is off
 */
public record ControlSettings(boolean allowDownvotes, Double voteSkipThreshold) {
    public static final ControlSettings DEFAULTS = new ControlSettings(false, 0.5);

    public static ControlSettings parse(String settingsJson) {
        JsonObject o;
        try {
            JsonElement e = settingsJson == null ? null : JsonParser.parseString(settingsJson);
            if (e == null || !e.isJsonObject()) {
                return DEFAULTS;
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return DEFAULTS;
        }
        boolean downvotes = DEFAULTS.allowDownvotes();
        if (o.get("allowDownvotes") instanceof JsonPrimitive p && p.isBoolean()) {
            downvotes = p.getAsBoolean();
        }
        Double threshold = DEFAULTS.voteSkipThreshold();
        if (o.get("voteSkipThreshold") instanceof JsonPrimitive p) {
            if (p.isString() && "off".equals(p.getAsString())) {
                threshold = null;
            } else if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d > 0 && d <= 1) {
                    threshold = d;
                }
            }
        }
        return new ControlSettings(downvotes, threshold);
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
