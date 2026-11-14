package io.siggi.togethertunes.rooms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who is in a room right now ({@code docs/rooms.md#guests-in-rooms}): the room's open connections
 * (WebSocket sockets that joined it), each with the actor it belongs to and its mode. Part of the
 * {@link RoomState}, so only touched on the room's executor thread; not persisted (connections
 * don't survive a restart), but kept when the executor reloads the state.
 *
 * <p>Counting ({@link #counts}):
 * <ul>
 *   <li>An identified actor (a user, or a guest once it has an identity) counts <b>once</b>, however
 *       many connections it has: as {@code listening} if any of them is in listen mode, else as
 *       {@code remote}. These are the members present for the vote-skip threshold
 *       ({@link #identified}).</li>
 *   <li>Anonymous connections are counted separately ({@code anonymous}, "+4 anonymous"), one
 *       per connection, since nothing tells two of them apart.</li>
 * </ul>
 * Holds connection ids and actor keys ({@link RoomActor#key}) only, never an IP address.
 */
public final class RoomPresence {
    /** How a connection follows the room ({@code docs/rooms.md#listen-mode}, {@code #remote-mode}). */
    public enum Mode {
        /** Plays the room's media in the browser. */
        LISTEN,
        /** Hears the room elsewhere (VRChat); loads no media. */
        REMOTE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The mode with this wire name, or {@code null}. */
        public static Mode parse(String s) {
            for (Mode m : values()) {
                if (m.wire().equals(s)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** The presence counts sent to clients ({@code presence} in the room state). */
    public record Counts(int listening, int remote, int anonymous) {
        public static final Counts NONE = new Counts(0, 0, 0);

        /** Identified members present (listening + remote). */
        public int identified() {
            return listening + remote;
        }
    }

    /** One connection: its actor key ({@code null}: anonymous) and mode. */
    private record Connection(String actorKey, Mode mode) {
    }

    private final Map<String, Connection> connections = new LinkedHashMap<>();

    /**
     * Adds a connection, or replaces it if the id is already present (a socket that joins again).
     *
     * @param actorKey {@link RoomActor#key} of a user or guest, or {@code null} for an anonymous visitor
     */
    public void join(String connectionId, String actorKey, Mode mode) {
        connections.put(Objects.requireNonNull(connectionId, "connectionId"),
                new Connection(actorKey, Objects.requireNonNull(mode, "mode")));
    }

    /** Removes a connection; whether it was present. */
    public boolean leave(String connectionId) {
        return connections.remove(connectionId) != null;
    }

    /** Changes a connection's mode; whether it was present. */
    public boolean mode(String connectionId, Mode mode) {
        Connection c = connections.get(connectionId);
        if (c == null) {
            return false;
        }
        connections.put(connectionId, new Connection(c.actorKey(), Objects.requireNonNull(mode, "mode")));
        return true;
    }

    /**
     * Gives a connection an identity (an anonymous socket that just became a guest, or logged in);
     * whether it was present.
     */
    public boolean identify(String connectionId, String actorKey) {
        Connection c = connections.get(connectionId);
        if (c == null) {
            return false;
        }
        connections.put(connectionId, new Connection(Objects.requireNonNull(actorKey, "actorKey"), c.mode()));
        return true;
    }

    /** Removes every connection of this actor (it was banned); the ids of the removed connections. */
    public List<String> removeActor(String actorKey) {
        List<String> removed = new ArrayList<>();
        connections.entrySet().removeIf(e -> {
            if (Objects.equals(e.getValue().actorKey(), actorKey) && actorKey != null) {
                removed.add(e.getKey());
                return true;
            }
            return false;
        });
        return removed;
    }

    /** Whether this connection is present. */
    public boolean contains(String connectionId) {
        return connections.containsKey(connectionId);
    }

    /** The actor key of a connection ({@code null}: anonymous or not present). */
    public String actorKey(String connectionId) {
        Connection c = connections.get(connectionId);
        return c == null ? null : c.actorKey();
    }

    /** The number of open connections. */
    public int connections() {
        return connections.size();
    }

    /** The counts as described in the class comment. */
    public Counts counts() {
        Map<String, Boolean> listensByActor = new HashMap<>();
        int anonymous = 0;
        for (Connection c : connections.values()) {
            if (c.actorKey() == null) {
                anonymous++;
            } else {
                listensByActor.merge(c.actorKey(), c.mode() == Mode.LISTEN, Boolean::logicalOr);
            }
        }
        int listening = 0;
        for (boolean listens : listensByActor.values()) {
            if (listens) {
                listening++;
            }
        }
        return new Counts(listening, listensByActor.size() - listening, anonymous);
    }

    /** The ids of the users present (listen or remote), each once, in the order they came. */
    public List<String> userIds() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Connection c : connections.values()) {
            String key = c.actorKey();
            if (key != null && key.startsWith("u:")) {
                out.add(key.substring(2));
            }
        }
        return List.copyOf(out);
    }

    /** Identified members present (distinct users and guests, listen or remote). */
    public int identified() {
        Set<String> actors = new HashSet<>();
        for (Connection c : connections.values()) {
            if (c.actorKey() != null) {
                actors.add(c.actorKey());
            }
        }
        return actors.size();
    }
}
