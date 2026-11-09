package io.siggi.togethertunes.rooms;

import java.util.List;
import java.util.Objects;

/**
 * One committed change to a room: the {@code version} it produced, what changed
 * ({@code events}, in the order the mutation added them) and the timeline after the change.
 * Immutable.
 */
public record RoomChange(String roomId, long version, List<RoomEvent> events, Timeline timeline) {
    public RoomChange {
        Objects.requireNonNull(roomId, "roomId");
        events = List.copyOf(events);
        Objects.requireNonNull(timeline, "timeline");
    }

    /** Whether the timeline was re-anchored ({@link RoomEvent.TimelineChanged} is among the events). */
    public boolean timelineChanged() {
        for (RoomEvent e : events) {
            if (e instanceof RoomEvent.TimelineChanged) {
                return true;
            }
        }
        return false;
    }
}
