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
    private boolean rescheduleAdvance;
    private boolean refreshAutoplay;
    private final RoomStore store;
    private final PackagerHook packager;

    /** The longest an abrupt start is put off ({@link #abruptStartAt()}). */
    public static final long MAX_START_DELAY_MS = 4000;

    RoomTx(RoomState state, long now, RoomStore store, PackagerHook packager) {
        this.state = state;
        this.now = now;
        this.store = store;
        this.packager = packager;
    }

    RoomStore store() {
        return store;
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
     * When an entry started abruptly in this mutation (skip, previous, a start from idle; not a
     * natural end) begins ({@code docs/rooms.md#room-state}): {@link #now()} unless the room's
     * program output runs; then the output's next unit it can start the entry at from 0:00
     * ({@link PackagerHook#itemStartAt}), at most {@link #MAX_START_DELAY_MS} from now. The
     * timeline then starts the entry at that (future) time, so web listeners and the stream both
     * begin it at 0:00.
     */
    public long abruptStartAt() {
        long at;
        try {
            at = packager.itemStartAt(state.id(), now);
        } catch (RuntimeException e) {
            return now;
        }
        return at <= now ? now : Math.min(at, now + MAX_START_DELAY_MS);
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

    /**
     * Sets the timeline (re-anchoring it) and adds {@link RoomEvent.TimelineChanged}. Once the
     * change is committed, the executor reschedules the advance task for the new anchor.
     */
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

    /**
     * Asks the executor to reschedule the advance task after this mutation even if the timeline
     * didn't change (resume after a restart; an advance task that ran a little early).
     */
    void rescheduleAdvance() {
        rescheduleAdvance = true;
    }

    /**
     * Asks {@link Autoplay} to look at the room's selections again after this mutation, even if
     * nothing else changed, and to drop autoplay entries whose song is no longer selected (the room
     * loaded, selections or playlists changed, someone lost the right to select).
     */
    void refreshAutoplay() {
        refreshAutoplay = true;
    }

    boolean refreshesAutoplay() {
        return refreshAutoplay;
    }

    boolean reschedulesAdvance() {
        return rescheduleAdvance;
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
