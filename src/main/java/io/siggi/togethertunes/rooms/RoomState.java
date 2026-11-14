package io.siggi.togethertunes.rooms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A room's authoritative in-memory state ({@code docs/rooms.md#room-state}). It is owned by the
 * room's {@link RoomExecutor}: it is read and changed <b>only on that executor's thread</b>, inside
 * a {@link RoomMutation} (or a {@link RoomListener} callback), so it needs no locking. Never keep a
 * reference to it beyond that; copy what you need (for example the immutable {@link Timeline}).
 *
 * <p>Changes are made through a mutation, which also persists them ({@link RoomTx#persist}); the
 * executor then bumps {@link #version()} and broadcasts. Presence ({@link #presence()}) lives in
 * memory only. Later M3 steps add history here.
 */
public final class RoomState {
    private final String id;
    private String slug;
    private String name;
    private String ownerId;
    private Rooms.Visibility visibility;
    private Rooms.ControlMode controlMode;
    private RoomSettings settings;
    private Timeline timeline;
    /** The entry the timeline points at, or {@code null} when idle. */
    private QueueEntry current;
    /** The {@code queued} entries (not the current one), in no particular order; see {@link Playback#playOrder}. */
    private final List<QueueEntry> queue = new ArrayList<>();
    /** The {@code suggested} entries ({@code owner} mode), in no particular order. */
    private final List<QueueEntry> suggestions = new ArrayList<>();
    /** Votes on queued and suggested entries: entryId -> voter key ({@link RoomActor#key}) -> value (±1). */
    private final Map<String, Map<String, Integer>> votes = new HashMap<>();
    /** Voter keys of the skip votes on the current entry. */
    private final Set<String> skipVotes = new LinkedHashSet<>();
    /** Who is in the room (connections); maintained through {@link PresenceOps}, kept across reloads. */
    private RoomPresence presence = new RoomPresence();
    private long version;
    /** The user whose alone room this is ({@link AloneSessions}), or {@code null} for an ordinary room. */
    private String aloneUserId;
    /** For an alone room: the room its user is in (the alone state is saved meanwhile), or {@code null}. */
    private String awayInRoomId;

    RoomState(String id, String slug, String name, String ownerId, Rooms.Visibility visibility,
              Rooms.ControlMode controlMode, String settingsJson, Timeline timeline, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.name = Objects.requireNonNull(name, "name");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.controlMode = Objects.requireNonNull(controlMode, "controlMode");
        this.settings = RoomSettings.parse(Objects.requireNonNull(settingsJson, "settingsJson"));
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.version = version;
    }

    RoomState(String id, String slug, String name, String ownerId, Rooms.Visibility visibility,
              Rooms.ControlMode controlMode, String settingsJson, Timeline timeline, long version,
              QueueEntry current, List<QueueEntry> queue) {
        this(id, slug, name, ownerId, visibility, controlMode, settingsJson, timeline, version);
        this.current = current;
        boolean audioOnly = settings.audioOnly();
        for (QueueEntry e : queue) {
            this.queue.add(e.withAudioOnly(audioOnly));
        }
    }

    RoomState(String id, String slug, String name, String ownerId, Rooms.Visibility visibility,
              Rooms.ControlMode controlMode, String settingsJson, Timeline timeline, long version,
              QueueEntry current, List<QueueEntry> queue, List<QueueEntry> suggestions,
              Map<String, Map<String, Integer>> votes, Set<String> skipVotes) {
        this(id, slug, name, ownerId, visibility, controlMode, settingsJson, timeline, version, current, queue);
        for (QueueEntry e : suggestions) {
            this.suggestions.add(e.withAudioOnly(settings.audioOnly()));
        }
        votes.forEach((entry, byVoter) -> this.votes.put(entry, new HashMap<>(byVoter)));
        this.skipVotes.addAll(skipVotes);
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

    /** The room's settings ({@code settings_json}). */
    public RoomSettings settings() {
        return settings;
    }

    /** The settings the control modes read ({@link RoomSettings#control()}). */
    public ControlSettings controlSettings() {
        return settings.control();
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

    /** The {@code suggested} entries (unmodifiable view, in no particular order; see {@link Playback#suggestionOrder}). */
    public List<QueueEntry> suggestions() {
        return Collections.unmodifiableList(suggestions);
    }

    /** The queued or suggested entry with this id, or {@code null}. */
    public QueueEntry waitingEntry(String entryId) {
        for (QueueEntry e : queue) {
            if (e.id().equals(entryId)) {
                return e;
            }
        }
        for (QueueEntry e : suggestions) {
            if (e.id().equals(entryId)) {
                return e;
            }
        }
        return null;
    }

    /** The sum of the votes on an entry. */
    public int voteTotal(String entryId) {
        Map<String, Integer> v = votes.get(entryId);
        if (v == null) {
            return 0;
        }
        int sum = 0;
        for (int value : v.values()) {
            sum += value;
        }
        return sum;
    }

    /** The vote of the voter with this key ({@link RoomActor#key}) on an entry: 1, -1, or 0 for none. */
    public int voteOf(String entryId, String voterKey) {
        Map<String, Integer> v = votes.get(entryId);
        Integer value = v == null ? null : v.get(voterKey);
        return value == null ? 0 : value;
    }

    /** The votes on an entry, voter key -> ±1 (a new map; empty if none). */
    Map<String, Integer> votesOn(String entryId) {
        Map<String, Integer> v = votes.get(entryId);
        return v == null ? Map.of() : Map.copyOf(v);
    }

    /** The voter keys of the skip votes on the current entry (unmodifiable view). */
    public Set<String> skipVotes() {
        return Collections.unmodifiableSet(skipVotes);
    }

    /** Identified members present (listen + remote), for the vote-skip threshold. */
    public int presentIdentified() {
        return presence.identified();
    }

    /**
     * Who is in the room. Change it through {@link PresenceOps} (which broadcasts new counts and
     * re-checks the vote-skip threshold), not directly.
     */
    public RoomPresence presence() {
        return presence;
    }

    /** Takes over the presence of the state this one replaces (a reload: connections stay open). */
    void adoptPresence(RoomState previous) {
        this.presence = previous.presence;
    }

    /** The user whose alone room this is, or {@code null} for an ordinary room. */
    public String aloneUserId() {
        return aloneUserId;
    }

    /** For an alone room: the room its user is in now ({@code null}: alone, or an ordinary room). */
    public String awayInRoomId() {
        return awayInRoomId;
    }

    void alone(String aloneUserId, String awayInRoomId) {
        this.aloneUserId = aloneUserId;
        this.awayInRoomId = aloneUserId == null ? null : awayInRoomId;
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

    /**
     * Takes over the settings after {@link Rooms} changed them. A change of {@code audioOnly}
     * applies to the waiting entries at once and to the current entry not at all: it keeps the
     * mode it started with until it ends ({@code docs/rooms.md#audio-only-rooms}).
     */
    public void settings(RoomSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        boolean audioOnly = settings.audioOnly();
        queue.replaceAll(e -> e.withAudioOnly(audioOnly));
        suggestions.replaceAll(e -> e.withAudioOnly(audioOnly));
    }

    /**
     * Sets the timeline and keeps {@link #current()} on its entry. The executor persists it with every committed change; the caller adds a
     * {@link RoomEvent.TimelineChanged} with {@link RoomTx#timeline}, which does both.
     */
    void timeline(Timeline timeline) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        String entryId = timeline.entryId();
        if (entryId == null) {
            current(null);
        } else if (current == null || !current.id().equals(entryId)) {
            // Keep current() pointing at the timeline's entry (Playback sets it first; this is
            // the fallback for a timeline set directly).
            QueueEntry found = null;
            for (QueueEntry e : queue) {
                if (e.id().equals(entryId)) {
                    found = e;
                    break;
                }
            }
            removeFromQueue(entryId);
            current(found);
        }
    }

    /**
     * Sets the current entry. When it changes, the skip votes (which belong to the old one) are
     * dropped, and so are the new entry's votes (they only order the queue).
     */
    void current(QueueEntry current) {
        String oldId = this.current == null ? null : this.current.id();
        String newId = current == null ? null : current.id();
        if (!Objects.equals(oldId, newId)) {
            skipVotes.clear();
            if (newId != null) {
                votes.remove(newId);
                // an entry that starts playing takes the room's mode now, and keeps it until it ends
                current = current.withAudioOnly(settings.audioOnly());
            }
        } else if (current != null) {
            current = current.withAudioOnly(this.current.audioOnly());
        }
        this.current = current;
    }

    /** Removes the entry from the queue and the suggestions. */
    void removeFromQueue(String entryId) {
        queue.removeIf(e -> e.id().equals(entryId));
        suggestions.removeIf(e -> e.id().equals(entryId));
    }

    /** Adds (or replaces) an entry: to the queue if {@code queued}, to the suggestions if {@code suggested}. */
    void addToQueue(QueueEntry entry) {
        removeFromQueue(entry.id());
        entry = entry.withAudioOnly(settings.audioOnly());
        switch (entry.state()) {
            case QUEUED -> queue.add(entry);
            case SUGGESTED -> suggestions.add(entry);
            case PLAYING, PLAYED, SKIPPED, REMOVED -> throw new IllegalArgumentException(
                    "only queued and suggested entries wait: " + entry.state());
        }
    }

    /** Drops an entry that left the queue for good, with its votes. */
    void forget(String entryId) {
        removeFromQueue(entryId);
        votes.remove(entryId);
    }

    /** Sets (±1) or clears (0) a vote. */
    void vote(String entryId, String voterKey, int value) {
        if (value == 0) {
            Map<String, Integer> v = votes.get(entryId);
            if (v != null) {
                v.remove(voterKey);
                if (v.isEmpty()) {
                    votes.remove(entryId);
                }
            }
        } else {
            votes.computeIfAbsent(entryId, k -> new HashMap<>()).put(voterKey, value);
        }
    }

    /**
     * Moves the votes and skip votes of voter {@code fromKey} to {@code toKey} (a guest merged into
     * an account). Where {@code toKey} already voted on an entry, its own vote stays and
     * {@code fromKey}'s is dropped.
     */
    void mergeVoter(String fromKey, String toKey) {
        for (Map<String, Integer> byVoter : votes.values()) {
            Integer v = byVoter.remove(fromKey);
            if (v != null) {
                byVoter.putIfAbsent(toKey, v);
            }
        }
        if (skipVotes.remove(fromKey)) {
            skipVotes.add(toKey);
        }
    }

    void addSkipVote(String voterKey) {
        skipVotes.add(voterKey);
    }

    /**
     * Takes over freshly read item status and duration ({@code entryId -> item}) for the current
     * and the queued entries. Entries missing from the map keep what they had.
     */
    void refreshItems(Map<String, ItemInfo> items) {
        if (current != null && items.containsKey(current.id())) {
            ItemInfo i = items.get(current.id());
            current = current.withItem(i.status(), i.durationMs(), i.filesUsable(), i.audioOnlyMs());
        }
        queue.replaceAll(e -> {
            ItemInfo i = items.get(e.id());
            return i == null ? e : e.withItem(i.status(), i.durationMs(), i.filesUsable(), i.audioOnlyMs());
        });
        suggestions.replaceAll(e -> {
            ItemInfo i = items.get(e.id());
            return i == null ? e : e.withItem(i.status(), i.durationMs(), i.filesUsable(), i.audioOnlyMs());
        });
    }

    /**
     * An item's status, duration, whether its files are usable and its audio-only file's length
     * ({@link QueueEntry#audioOnlyMs}), as read for {@link #refreshItems}.
     */
    record ItemInfo(String status, Long durationMs, boolean filesUsable, Long audioOnlyMs) {
        ItemInfo(String status, Long durationMs, boolean filesUsable) {
            this(status, durationMs, filesUsable, null);
        }
    }

    void version(long version) {
        this.version = version;
    }
}
