package io.siggi.togethertunes.rooms;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable copy of what the WebSocket layer shows of a room ({@link RoomSocket}): taken on
 * the room's executor thread ({@link #of}), then read on other threads (the room's
 * {@link RoomBroadcaster}), so it holds no reference to the live {@link RoomState}.
 *
 * <p>Holds ids, entries, vote values and counts only, never an IP address. Item metadata and
 * actor names are looked up from the database when a message is built ({@link RoomViews}).
 *
 * @param version         the state's version when taken (for a snapshot taken inside a mutation:
 *                        the version before that mutation's bump)
 * @param queue           the {@code queued} entries in play order ({@link Playback#playOrder})
 * @param suggestions     the {@code suggested} entries, oldest first
 * @param votes           entryId -> voter key -> ±1, for the queued and suggested entries
 * @param skipVotes       skip votes on the current entry
 * @param skipVotesNeeded skip votes that skip it, or {@code null} when vote-skip is off
 */
record RoomSnapshot(String roomId, long version, String slug, String name, String ownerId,
                    Rooms.Visibility visibility, Rooms.ControlMode controlMode, JsonObject settings,
                    Timeline timeline, QueueEntry current, List<QueueEntry> queue, List<QueueEntry> suggestions,
                    Map<String, Map<String, Integer>> votes, int skipVotes, Integer skipVotesNeeded,
                    RoomPresence.Counts presence) {

    /** A copy of {@code s}. Call on the room's executor thread only. */
    static RoomSnapshot of(RoomState s) {
        List<QueueEntry> queue = Playback.playOrder(s);
        List<QueueEntry> suggestions = Playback.suggestionOrder(s);
        Map<String, Map<String, Integer>> votes = new HashMap<>();
        for (QueueEntry e : queue) {
            Map<String, Integer> v = s.votesOn(e.id());
            if (!v.isEmpty()) {
                votes.put(e.id(), v);
            }
        }
        for (QueueEntry e : suggestions) {
            Map<String, Integer> v = s.votesOn(e.id());
            if (!v.isEmpty()) {
                votes.put(e.id(), v);
            }
        }
        ControlSettings control = s.controlSettings();
        Integer needed = control.voteSkipThreshold() == null ? null
                : control.skipVotesNeeded(s.presentIdentified());
        return new RoomSnapshot(s.id(), s.version(), s.slug(), s.name(), s.ownerId(), s.visibility(),
                s.controlMode(), s.settings().toJson(), s.timeline(), s.current(), List.copyOf(queue),
                List.copyOf(suggestions), Map.copyOf(votes), s.skipVotes().size(), needed, s.presence().counts());
    }

    /** The waiting entries: the queue in play order, then the suggestions. */
    List<QueueEntry> waiting() {
        List<QueueEntry> out = new ArrayList<>(queue.size() + suggestions.size());
        out.addAll(queue);
        out.addAll(suggestions);
        return out;
    }

    /** The current, queued or suggested entry with this id, or {@code null}. */
    QueueEntry entry(String entryId) {
        if (current != null && current.id().equals(entryId)) {
            return current;
        }
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

    /** The votes on an entry (empty map if none). */
    Map<String, Integer> votesOn(String entryId) {
        return votes.getOrDefault(entryId, Map.of());
    }

    /** The sum of the votes on an entry. */
    int voteTotal(String entryId) {
        int sum = 0;
        for (int v : votesOn(entryId).values()) {
            sum += v;
        }
        return sum;
    }
}
