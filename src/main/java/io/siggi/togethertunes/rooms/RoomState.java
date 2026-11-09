package io.siggi.togethertunes.rooms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A room's authoritative in-memory state ({@code docs/rooms.md#room-state}). It is owned by the
 * room's {@link RoomExecutor}: it is read and changed <b>only on that executor's thread</b>, inside
 * a {@link RoomMutation} (or a {@link RoomListener} callback), so it needs no locking. Never keep a
 * reference to it beyond that; copy what you need (for example the immutable {@link Timeline}).
 *
 * <p>Changes are made through a mutation, which also persists them ({@link RoomTx#persist}); the
 * executor then bumps {@link #version()} and broadcasts. Later M3 steps add history, votes,
 * suggestions, typed settings and presence here.
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
    /** The entry the timeline points at, or {@code null} when idle. */
    private QueueEntry current;
    /** The {@code queued} entries (not the current one), in no particular order; see {@link Playback#playOrder}. */
    private final List<QueueEntry> queue = new ArrayList<>();
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

    RoomState(String id, String slug, String name, String ownerId, Rooms.Visibility visibility,
              Rooms.ControlMode controlMode, String settingsJson, Timeline timeline, long version,
              QueueEntry current, List<QueueEntry> queue) {
        this(id, slug, name, ownerId, visibility, controlMode, settingsJson, timeline, version);
        this.current = current;
        this.queue.addAll(queue);
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

    /**
     * The entry the timeline points at ({@link Timeline#entryId()}), or {@code null} when idle.
     * Its {@link QueueEntry#state()} is {@link QueueEntry.State#PLAYING}.
     */
    public QueueEntry current() {
        return current;
    }

    /** The {@code queued} entries, without the current one (unmodifiable view, in no particular order). */
    public List<QueueEntry> queue() {
        return Collections.unmodifiableList(queue);
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
     * Sets the timeline and keeps {@link #current()} on its entry. The executor persists it with every committed change; the caller adds a
     * {@link RoomEvent.TimelineChanged} with {@link RoomTx#timeline}, which does both.
     */
    void timeline(Timeline timeline) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        String entryId = timeline.entryId();
        if (entryId == null) {
            current = null;
        } else if (current == null || !current.id().equals(entryId)) {
            // Keep current() pointing at the timeline's entry (Playback sets it first; this is
            // the fallback for a timeline set directly).
            current = null;
            for (QueueEntry e : queue) {
                if (e.id().equals(entryId)) {
                    current = e;
                    break;
                }
            }
            removeFromQueue(entryId);
        }
    }

    void current(QueueEntry current) {
        this.current = current;
    }

    void removeFromQueue(String entryId) {
        queue.removeIf(e -> e.id().equals(entryId));
    }

    void addToQueue(QueueEntry entry) {
        removeFromQueue(entry.id());
        queue.add(Objects.requireNonNull(entry));
    }

    /**
     * Takes over freshly read item status and duration ({@code entryId -> item}) for the current
     * and the queued entries. Entries missing from the map keep what they had.
     */
    void refreshItems(Map<String, ItemInfo> items) {
        if (current != null && items.containsKey(current.id())) {
            ItemInfo i = items.get(current.id());
            current = current.withItem(i.status(), i.durationMs());
        }
        queue.replaceAll(e -> {
            ItemInfo i = items.get(e.id());
            return i == null ? e : e.withItem(i.status(), i.durationMs());
        });
    }

    /** An item's status and duration, as read for {@link #refreshItems}. */
    record ItemInfo(String status, Long durationMs) {
    }

    void version(long version) {
        this.version = version;
    }
}
