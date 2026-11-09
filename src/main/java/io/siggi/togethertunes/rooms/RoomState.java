package io.siggi.togethertunes.rooms;

import java.util.Objects;

/**
 * A room's authoritative in-memory state ({@code docs/rooms.md#room-state}). It is owned by the
 * room's {@link RoomExecutor}: it is read and changed <b>only on that executor's thread</b>, inside
 * a {@link RoomMutation} (or a {@link RoomListener} callback), so it needs no locking. Never keep a
 * reference to it beyond that; copy what you need (for example the immutable {@link Timeline}).
 *
 * <p>Changes are made through a mutation, which also persists them ({@link RoomTx#persist}); the
 * executor then bumps {@link #version()} and broadcasts. Later M3 steps add the queue, history,
 * votes, typed settings and presence here.
 */
public final class RoomState {
    private final String id;
    private String slug;
    private String name;
    private String ownerId;
    private Rooms.Visibility visibility;
    private Rooms.ControlMode controlMode;
    private String settingsJson;
    private Timeline timeline;
    private long version;

    RoomState(String id, String slug, String name, String ownerId, Rooms.Visibility visibility,
              Rooms.ControlMode controlMode, String settingsJson, Timeline timeline, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.name = Objects.requireNonNull(name, "name");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.controlMode = Objects.requireNonNull(controlMode, "controlMode");
        this.settingsJson = Objects.requireNonNull(settingsJson, "settingsJson");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.version = version;
    }

    public String id() {
        return id;
    }

    public String slug() {
        return slug;
    }

    public String name() {
        return name;
    }

    public String ownerId() {
        return ownerId;
    }

    public Rooms.Visibility visibility() {
        return visibility;
    }

    public Rooms.ControlMode controlMode() {
        return controlMode;
    }

    /** The raw {@code settings_json} (typed settings come with the room-settings step). */
    public String settingsJson() {
        return settingsJson;
    }

    public Timeline timeline() {
        return timeline;
    }

    /** The version of the last committed change; bumped by the executor, never by mutations. */
    public long version() {
        return version;
    }

    /** Takes over the room record after {@link Rooms} changed it (name, slug, visibility, mode, owner). */
    public void record(String slug, String name, String ownerId, Rooms.Visibility visibility,
                       Rooms.ControlMode controlMode) {
        this.slug = Objects.requireNonNull(slug, "slug");
        this.name = Objects.requireNonNull(name, "name");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.controlMode = Objects.requireNonNull(controlMode, "controlMode");
    }

    public void settingsJson(String settingsJson) {
        this.settingsJson = Objects.requireNonNull(settingsJson, "settingsJson");
    }

    /**
     * Sets the timeline. The executor persists it with every committed change; the caller adds a
     * {@link RoomEvent.TimelineChanged} with {@link RoomTx#timeline}, which does both.
     */
    void timeline(Timeline timeline) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    void version(long version) {
        this.version = version;
    }
}
