package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.Database;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What one {@link RoomMutation} did: the database writes to commit and the events to broadcast.
 * Created by the executor for each mutation and valid only during it.
 */
public final class RoomTx {
    private final RoomState state;
    private final long now;
    private final List<Database.SqlWork<?>> writes = new ArrayList<>();
    private final List<RoomEvent> events = new ArrayList<>();
    private boolean touched;
    private boolean closeRoom;

    RoomTx(RoomState state, long now) {
        this.state = state;
        this.now = now;
    }

    /** The room's state; change it only after every check has passed. */
    public RoomState state() {
        return state;
    }

    public String roomId() {
        return state.id();
    }

    /** The time the mutation started, epoch ms (from the executor's clock). */
    public long now() {
        return now;
    }

    /**
     * Adds a database write. All writes of a mutation run in one transaction, in order, together
     * with the executor's update of the room row (version and timeline).
     */
    public RoomTx persist(Database.SqlWork<?> write) {
        touched = true;
        writes.add(Objects.requireNonNull(write));
        return this;
    }

    /** Adds an event to broadcast once the change is committed. */
    public RoomTx event(RoomEvent event) {
        touched = true;
        events.add(Objects.requireNonNull(event));
        return this;
    }

    /** Sets the timeline (re-anchoring it) and adds {@link RoomEvent.TimelineChanged}. */
    public RoomTx timeline(Timeline timeline) {
        state().timeline(timeline);
        return event(new RoomEvent.TimelineChanged());
    }

    /**
     * Closes the room's executor after this mutation (the room was deleted). Nothing is persisted
     * or broadcast as a change; the listeners get {@link RoomListener#onClosed} with
     * {@code deleted = true}.
     */
    void closeRoom() {
        touched = true;
        closeRoom = true;
    }

    boolean touched() {
        return touched;
    }

    boolean changed() {
        return !writes.isEmpty() || !events.isEmpty();
    }

    boolean closesRoom() {
        return closeRoom;
    }

    List<Database.SqlWork<?>> writes() {
        return writes;
    }

    List<RoomEvent> events() {
        return events;
    }
}
