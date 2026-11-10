package io.siggi.togethertunes.rooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestProgress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends one room's state and changes to the sockets in it ({@code docs/api.md#websocket},
 * {@code docs/rooms.md#broadcasting}). One per {@link RoomExecutor}, registered as its
 * {@link RoomListener}; created by {@link RoomSocket} when the first socket joins.
 *
 * <p>The listener calls run on the executor thread and only take a {@link RoomSnapshot} and queue
 * it. A virtual thread of its own works through the queue in order: it looks up item metadata
 * and names ({@link RoomViews}) and builds each socket's message, so the executor never waits on
 * the database or on a socket. Joins, re-sends and leaves go through the same queue (joins and
 * re-sends are queued from inside a mutation), so every socket sees its full state and then
 * every later change, in version order.
 *
 * <p>Per committed change, every socket in the room gets exactly one message carrying the new
 * {@code version}:
 * <ul>
 *   <li>{@code presence} when only the presence counts changed;</li>
 *   <li>{@code timeline} (timeline and current entry) when only the timeline was re-anchored on
 *       the same entry (play, pause, seek);</li>
 *   <li>otherwise the full {@code state} while the room has at most {@value #FULL_STATE_MAX}
 *       waiting entries, and a {@code queue} delta (changed entries, removed ids, the new order)
 *       above that. Room, settings and member changes always send the full state.</li>
 * </ul>
 * A {@link RoomEvent.RequestAdded} also sends {@code request.new} to the owner and moderators.
 * The room's active program outputs ({@link RoomOutputs}) go into every {@code state}; a change of
 * one of them ({@link #outputStatus}) is sent as {@code output.status}, without a version, in order
 * with the other messages. Each output carries {@code estimatedDelayMs} (the room setting
 * {@code estimatedDelayMs.<kind>}, whose defaults are 5000 / 2000 / 8000 / 3000 ms for RTMP, .ts, HLS, MP3); the RTMP
 * {@code detail} goes to the owner and moderators only.
 * A banned guest's sockets ({@link RoomEvent.GuestBanned}), and sockets whose actor may no longer
 * see the room after a room, settings or member change, get {@code kicked} and leave the room.
 */
final class RoomBroadcaster implements RoomListener {
    private static final System.Logger LOG = System.getLogger(RoomBroadcaster.class.getName());

    /** Up to this many waiting entries (queue + suggestions), every change sends the full state. */
    static final int FULL_STATE_MAX = 200;
    /** The most often {@code ingest} goes out, and how often it is looked up while needed. */
    static final long INGEST_INTERVAL_MS = 1000;

    /** A socket as the broadcaster sees it. */
    interface Conn {
        /** The connection id (as in the room's presence). */
        String id();

        /** Who is on the socket now. */
        Actor actor();

        /** Queues a message; never blocks. */
        void send(JsonObject message);

        /** The broadcaster took the socket out of the room ({@code kicked} was sent). */
        void removedFromRoom(RoomBroadcaster from);
    }

    /** Re-checks an actor's access to the room ({@link RoomGuests#roomActor(Actor, String)}). */
    interface Access {
        /** @throws HttpError {@code 404 room_not_found}, {@code 403 banned_from_room} */
        RoomActor roomActor(Actor actor, String roomId) throws Exception;
    }

    private sealed interface Task permits Join, Resend, Leave, Changed, Closed, OutputChanged {
    }

    private record OutputChanged(RoomOutputs.Status status) implements Task {
    }

    private record Join(Conn conn, RoomActor actor, RoomSnapshot snapshot) implements Task {
    }

    private record Resend(Conn conn, RoomActor actor, RoomSnapshot snapshot) implements Task {
    }

    private record Leave(Conn conn) implements Task {
    }

    private record Changed(RoomSnapshot snapshot, RoomChange change) implements Task {
    }

    private record Closed(boolean deleted) implements Task {
    }

    /** A socket in the room. Only touched on the broadcaster thread. */
    private static final class Member {
        final Conn conn;
        RoomActor actor;
        /** The snapshot its last {@code state} or {@code queue} message was built from. */
        RoomSnapshot base;

        Member(Conn conn, RoomActor actor) {
            this.conn = conn;
            this.actor = actor;
        }
    }

    private final String roomId;
    private final RoomExecutor executor;
    private final RoomViews views;
    private final Access access;
    private final Runnable onStopped;
    private final RoomOutputs outputs;
    private final LinkedBlockingQueue<Task> tasks = new LinkedBlockingQueue<>();
    /** Joins queued or done minus members removed: no snapshot is taken while nobody listens. */
    private final AtomicInteger interest = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();
    /** By connection id. Only touched on the broadcaster thread. */
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final IngestProgress ingest;
    private final long ingestIntervalMs;
    /** The newest snapshot sent (any message). Only touched on the broadcaster thread. */
    private RoomSnapshot latest;
    /** The {@code items} of the last {@code ingest} sent; empty when none is needed. Broadcaster thread only. */
    private JsonObject lastIngest = new JsonObject();
    /** When the next ingest look-up is due ({@code System.nanoTime()}). Broadcaster thread only. */
    private long nextIngestNanos;

    RoomBroadcaster(RoomExecutor executor, RoomViews views, Access access, Runnable onStopped) {
        this(executor, views, access, onStopped, RoomOutputs.NONE);
    }

    RoomBroadcaster(RoomExecutor executor, RoomViews views, Access access, Runnable onStopped, RoomOutputs outputs) {
        this(executor, views, access, onStopped, outputs, IngestProgress.NONE, INGEST_INTERVAL_MS);
    }

    /**
     * @param ingest           what the ingest of items that aren't ready is doing ({@code ingest} messages)
     * @param ingestIntervalMs the least time between two {@code ingest} messages (and look-ups)
     */
    RoomBroadcaster(RoomExecutor executor, RoomViews views, Access access, Runnable onStopped, RoomOutputs outputs,
                    IngestProgress ingest, long ingestIntervalMs) {
        this.ingest = Objects.requireNonNull(ingest);
        if (ingestIntervalMs < 1) {
            throw new IllegalArgumentException("ingestIntervalMs");
        }
        this.ingestIntervalMs = ingestIntervalMs;
        this.nextIngestNanos = System.nanoTime();
        this.outputs = Objects.requireNonNull(outputs);
        this.executor = Objects.requireNonNull(executor);
        this.roomId = executor.roomId();
        this.views = Objects.requireNonNull(views);
        this.access = Objects.requireNonNull(access);
        this.onStopped = Objects.requireNonNull(onStopped);
        Thread.ofVirtual().name("room-ws-" + roomId).start(this::loop);
    }

    String roomId() {
        return roomId;
    }

    RoomExecutor executor() {
        return executor;
    }

    // ------------------------------------------------------------------ queueing (any thread)

    /** A socket joins: it gets the full state of {@code snapshot}, then every later change. Call inside a mutation. */
    void join(Conn conn, RoomActor actor, RoomSnapshot snapshot) {
        interest.incrementAndGet();
        tasks.add(new Join(conn, actor, snapshot));
    }

    /**
     * Sends the full state of {@code snapshot} to a socket in the room again ({@code sync}, or a
     * new identity), with its new room actor ({@code null}: unchanged). Call inside a mutation.
     */
    void resend(Conn conn, RoomActor actor, RoomSnapshot snapshot) {
        tasks.add(new Resend(conn, actor, snapshot));
    }

    /** A socket leaves the room (it asked to, joined another room, or closed). */
    void leave(Conn conn) {
        tasks.add(new Leave(conn));
    }

    /** One of the room's outputs changed: {@code output.status} to every socket in the room. Any thread. */
    void outputStatus(RoomOutputs.Status status) {
        if (interest.get() > 0) {
            tasks.add(new OutputChanged(status));
        }
    }

    @Override
    public void onChange(RoomState state, RoomChange change) {
        if (interest.get() > 0) {
            tasks.add(new Changed(RoomSnapshot.of(state), change));
        }
    }

    @Override
    public void onClosed(String roomId, boolean deleted) {
        if (closing.compareAndSet(false, true)) {
            tasks.add(new Closed(deleted));
        }
    }

    // ------------------------------------------------------------------ the broadcaster thread

    private void loop() {
        try {
            while (true) {
                Task task;
                try {
                    if (ingestNeeded()) {
                        long waitNanos = nextIngestNanos - System.nanoTime();
                        task = waitNanos > 0 ? tasks.poll(waitNanos, TimeUnit.NANOSECONDS) : tasks.poll();
                    } else {
                        task = tasks.take();
                    }
                } catch (InterruptedException e) {
                    return;
                }
                if (task == null) {
                    try {
                        ingestTick();
                    } catch (Throwable t) {
                        LOG.log(System.Logger.Level.WARNING, "Ingest progress in room " + roomId + " failed", t);
                    }
                    continue;
                }
                try {
                    switch (task) {
                        case Join j -> joined(j);
                        case Resend r -> resent(r);
                        case Leave l -> {
                            if (members.remove(l.conn().id()) != null) {
                                interest.decrementAndGet();
                            }
                        }
                        case Changed c -> changed(c.snapshot(), c.change());
                        case OutputChanged o -> outputChanged(o.status());
                        case Closed c -> {
                            closed(c.deleted());
                            return;
                        }
                    }
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.WARNING, "Broadcast in room " + roomId + " failed", t);
                }
            }
        } finally {
            onStopped.run();
        }
    }

    private void joined(Join j) throws Exception {
        Member m = new Member(j.conn(), j.actor());
        if (members.put(j.conn().id(), m) != null) {
            interest.decrementAndGet(); // joined again: one member
        }
        sendState(List.of(m), j.snapshot());
        sendLastIngest(m);
    }

    // ------------------------------------------------------------------ ingest progress

    /** Whether some entry (current, queued or suggested) has an item that isn't ready, or the last {@code ingest} still says so. */
    private boolean ingestNeeded() {
        if (members.isEmpty() || latest == null) {
            return false;
        }
        return !lastIngest.isEmpty() || !notReady(latest).isEmpty();
    }

    /** The items of {@code s}'s entries that aren't ready and may still become ready (or failed), in order. */
    static List<String> notReady(RoomSnapshot s) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (s.current() != null && waitsForIngest(s.current())) {
            ids.add(s.current().itemId());
        }
        for (QueueEntry e : s.waiting()) {
            if (waitsForIngest(e)) {
                ids.add(e.itemId());
            }
        }
        return new ArrayList<>(ids);
    }

    private static boolean waitsForIngest(QueueEntry e) {
        return !"ready".equals(e.itemStatus()) && !"purged".equals(e.itemStatus());
    }

    /**
     * Looks up the ingest of the room's items that aren't ready and sends {@code ingest} to every
     * socket if it changed; once every item is ready, one last {@code ingest} with no items.
     */
    private void ingestTick() throws Exception {
        nextIngestNanos = System.nanoTime() + ingestIntervalMs * 1_000_000L;
        if (members.isEmpty() || latest == null) {
            return;
        }
        List<String> ids = notReady(latest);
        JsonObject items = new JsonObject();
        if (!ids.isEmpty()) {
            for (Map.Entry<String, IngestProgress.Status> e : ingest.of(ids).entrySet()) {
                items.add(e.getKey(), e.getValue().toJson());
            }
        }
        if (items.equals(lastIngest)) {
            return;
        }
        lastIngest = items;
        for (Member m : members.values()) {
            m.conn.send(ingestMessage(items));
        }
    }

    /** The last {@code ingest} to a socket that just got the full state, if there is one to send. */
    private void sendLastIngest(Member m) {
        if (!lastIngest.isEmpty()) {
            m.conn.send(ingestMessage(lastIngest));
        }
    }

    private static JsonObject ingestMessage(JsonObject items) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "ingest");
        msg.add("items", items.deepCopy());
        return msg;
    }

    private void resent(Resend r) throws Exception {
        Member m = members.get(r.conn().id());
        if (m == null) {
            return;
        }
        if (r.actor() != null) {
            m.actor = r.actor();
        }
        sendState(List.of(m), r.snapshot());
        sendLastIngest(m);
    }

    private void closed(boolean deleted) {
        String reason = deleted ? "room_deleted" : "room_closed";
        for (Member m : members.values()) {
            kickMessage(m, reason);
        }
        members.clear();
        interest.set(0);
    }

    private void changed(RoomSnapshot s, RoomChange change) throws Exception {
        noteSnapshot(s);
        List<RoomEvent> events = change.events();
        // Banned guests first: their sockets are already out of the presence.
        for (RoomEvent e : events) {
            if (e instanceof RoomEvent.GuestBanned b) {
                for (String connectionId : b.connectionIds()) {
                    Member m = members.remove(connectionId);
                    if (m != null) {
                        interest.decrementAndGet();
                        kickMessage(m, "banned_from_room");
                    }
                }
            }
        }
        boolean recheckAll = false;
        Set<String> recheckUsers = new HashSet<>();
        boolean presenceOnly = !events.isEmpty();
        boolean timelineOnly = !events.isEmpty();
        for (RoomEvent e : events) {
            switch (e) {
                case RoomEvent.RoomUpdated ignored -> recheckAll = true;
                case RoomEvent.SettingsChanged ignored -> recheckAll = true;
                case RoomEvent.MemberChanged mc -> recheckUsers.add(mc.userId());
                default -> {
                }
            }
            presenceOnly &= e instanceof RoomEvent.PresenceChanged;
            timelineOnly &= e instanceof RoomEvent.TimelineChanged;
        }
        // Who may still see the room, and with which rights.
        Set<Member> forceFull = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Member m : new ArrayList<>(members.values())) {
            boolean recheck = recheckAll
                    || (m.conn.actor() instanceof Actor.User u && recheckUsers.contains(u.userId()));
            if (!recheck) {
                continue;
            }
            try {
                m.actor = access.roomActor(m.conn.actor(), roomId);
                forceFull.add(m);
            } catch (HttpError refused) {
                members.remove(m.conn.id());
                interest.decrementAndGet();
                kickMessage(m, refused.code());
                leavePresence(m.conn.id());
            }
        }
        boolean big = s.waiting().size() > FULL_STATE_MAX;
        List<Member> full = new ArrayList<>();
        Map<RoomSnapshot, List<Member>> deltas = new IdentityHashMap<>();
        JsonObject currentJson = null;
        boolean currentLooked = false;
        for (Member m : members.values()) {
            if (forceFull.contains(m) || m.base == null) {
                full.add(m);
            } else if (presenceOnly) {
                JsonObject msg = message("presence", s.version());
                msg.add("presence", RoomViews.presence(s.presence()));
                m.conn.send(msg);
            } else if (timelineOnly && sameCurrent(m.base, s)) {
                if (!currentLooked) {
                    currentLooked = true;
                    if (s.current() != null) {
                        RoomViews.Lookup l = views.lookup(List.of(s.current()), List.of());
                        currentJson = RoomViews.current(l, s.current());
                    }
                }
                JsonObject msg = message("timeline", s.version());
                msg.add("timeline", RoomViews.timeline(s.timeline()));
                if (currentJson != null) {
                    msg.add("current", currentJson.deepCopy());
                }
                JsonObject skip = RoomViews.skipVotes(s);
                if (skip != null) {
                    msg.add("skipVotes", skip);
                }
                m.conn.send(msg);
            } else if (!big) {
                full.add(m);
            } else {
                deltas.computeIfAbsent(m.base, b -> new ArrayList<>()).add(m);
            }
        }
        if (!full.isEmpty()) {
            sendState(full, s);
        }
        if (!deltas.isEmpty()) {
            List<RoomViews.HistoryRow> history = views.history(roomId);
            for (Map.Entry<RoomSnapshot, List<Member>> d : deltas.entrySet()) {
                sendDelta(d.getValue(), d.getKey(), s, history);
            }
        }
        for (RoomEvent e : events) {
            if (e instanceof RoomEvent.RequestAdded r) {
                requestNew(s, r);
            }
        }
    }

    private static boolean sameCurrent(RoomSnapshot a, RoomSnapshot b) {
        String x = a.current() == null ? null : a.current().id();
        String y = b.current() == null ? null : b.current().id();
        return Objects.equals(x, y);
    }

    /** Keeps the newest snapshot for the ingest look-ups. */
    private void noteSnapshot(RoomSnapshot s) {
        if (latest == null || s.version() >= latest.version()) {
            latest = s;
        }
    }

    /** The full {@code state} of {@code s} to each of {@code to}. */
    private void sendState(List<Member> to, RoomSnapshot s) throws Exception {
        noteSnapshot(s);
        List<RoomViews.HistoryRow> history = views.history(roomId);
        List<QueueEntry> entries = new ArrayList<>(s.waiting());
        if (s.current() != null) {
            entries.add(s.current());
        }
        RoomViews.Lookup l = views.lookup(entries, history);
        JsonObject base = message("state", s.version());
        JsonObject room = new JsonObject();
        room.addProperty("id", s.roomId());
        room.addProperty("slug", s.slug());
        room.addProperty("name", s.name());
        room.addProperty("visibility", s.visibility().wire());
        room.addProperty("controlMode", s.controlMode().wire());
        room.addProperty("ownerId", s.ownerId());
        room.add("settings", s.settings().deepCopy());
        base.add("room", room);
        base.add("timeline", RoomViews.timeline(s.timeline()));
        if (s.current() != null) {
            base.add("current", RoomViews.current(l, s.current()));
        }
        base.add("queue", entries(l, s, s.queue()));
        base.add("suggestions", entries(l, s, s.suggestions()));
        base.add("history", RoomViews.history(l, history));
        JsonObject skip = RoomViews.skipVotes(s);
        if (skip != null) {
            base.add("skipVotes", skip);
        }
        base.add("presence", RoomViews.presence(s.presence()));
        // The overlay (M5) adds its part here.
        List<RoomOutputs.Status> active = activeOutputs();
        for (Member m : to) {
            JsonObject msg = base.deepCopy();
            msg.add("outputs", outputsJson(active, s.settings(), m.actor));
            msg.getAsJsonObject("room").add("you", you(s, m.actor));
            yourVotes(msg.getAsJsonArray("queue"), s, m.actor);
            yourVotes(msg.getAsJsonArray("suggestions"), s, m.actor);
            m.conn.send(msg);
            m.base = s;
        }
    }

    /**
     * A {@code queue} delta from {@code base} to {@code s} for sockets that last got {@code base}:
     * the entries that are new or changed ({@code upsert}), the ids that left ({@code removed}),
     * the full order ({@code order}, {@code suggestionOrder}) and the small parts in full.
     */
    private void sendDelta(List<Member> to, RoomSnapshot base, RoomSnapshot s, List<RoomViews.HistoryRow> history)
            throws Exception {
        List<QueueEntry> upsert = new ArrayList<>();
        Set<String> now = new HashSet<>();
        for (QueueEntry e : s.waiting()) {
            now.add(e.id());
            QueueEntry before = base.entry(e.id());
            if (before == null || !before.equals(e) || !base.votesOn(e.id()).equals(s.votesOn(e.id()))) {
                upsert.add(e);
            }
        }
        JsonArray removed = new JsonArray();
        for (QueueEntry e : base.waiting()) {
            if (!now.contains(e.id())) {
                removed.add(e.id());
            }
        }
        List<QueueEntry> look = new ArrayList<>(upsert);
        if (s.current() != null) {
            look.add(s.current());
        }
        RoomViews.Lookup l = views.lookup(look, history);
        JsonObject msg = message("queue", s.version());
        msg.add("upsert", entries(l, s, upsert));
        msg.add("removed", removed);
        msg.add("order", ids(s.queue()));
        msg.add("suggestionOrder", ids(s.suggestions()));
        msg.add("timeline", RoomViews.timeline(s.timeline()));
        if (s.current() != null) {
            msg.add("current", RoomViews.current(l, s.current()));
        }
        msg.add("history", RoomViews.history(l, history));
        JsonObject skip = RoomViews.skipVotes(s);
        if (skip != null) {
            msg.add("skipVotes", skip);
        }
        msg.add("presence", RoomViews.presence(s.presence()));
        for (Member m : to) {
            JsonObject copy = msg.deepCopy();
            yourVotes(copy.getAsJsonArray("upsert"), s, m.actor);
            m.conn.send(copy);
            m.base = s;
        }
    }

    /** The room's active outputs; none if they can't be read (logged). */
    private List<RoomOutputs.Status> activeOutputs() {
        try {
            return outputs.active(roomId);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the outputs of room " + roomId, e);
            return List.of();
        }
    }

    private static JsonArray outputsJson(List<RoomOutputs.Status> active, JsonObject settings, RoomActor actor) {
        JsonArray a = new JsonArray();
        for (RoomOutputs.Status st : active) {
            a.add(outputJson(new JsonObject(), st, settings, actor));
        }
        return a;
    }

    /**
     * {@code kind}, {@code status}, {@code estimatedDelayMs} (unless off) and, for the owner and
     * moderators, {@code detail} (moderators: without URLs or key hints, {@link RoomOutputs#moderatorDetail}).
     */
    private static JsonObject outputJson(JsonObject o, RoomOutputs.Status st, JsonObject settings,
                                         RoomActor actor) {
        o.addProperty("kind", st.kind());
        o.addProperty("status", st.status());
        Integer delay = "off".equals(st.status()) ? null : estimatedDelayMs(settings, st.kind());
        if (delay != null) {
            o.addProperty("estimatedDelayMs", delay);
        }
        if (actor.moderates() && st.detail() != null) {
            o.addProperty("detail", actor.role() == Rooms.Role.OWNER ? st.detail()
                    : RoomOutputs.moderatorDetail(st.detail()));
        }
        return o;
    }

    /** The room setting {@code estimatedDelayMs.<kind>}, or null if the settings don't have it. */
    static Integer estimatedDelayMs(JsonObject settings, String kind) {
        if (settings == null || !(settings.get("estimatedDelayMs") instanceof JsonObject delays)) {
            return null;
        }
        JsonElement v = delays.get(kind);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return v.getAsInt();
    }

    /** {@code output.status} to every socket in the room (no version: it isn't a room change). */
    private void outputChanged(RoomOutputs.Status st) {
        for (Member m : members.values()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "output.status");
            m.conn.send(outputJson(msg, st, m.base == null ? null : m.base.settings(), m.actor));
        }
    }

    /** {@code request.new} to the owner and moderators in the room. */
    private void requestNew(RoomSnapshot s, RoomEvent.RequestAdded r) throws Exception {
        List<Member> mods = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.actor.moderates()) {
                mods.add(m);
            }
        }
        QueueEntry e = s.entry(r.entryId());
        if (mods.isEmpty() || e == null) {
            return;
        }
        RoomViews.Lookup l = views.lookup(List.of(e), List.of());
        JsonObject request = new JsonObject();
        request.addProperty("entryId", r.entryId());
        request.add("item", RoomViews.item(l, e.itemId()));
        ActorRef by = RoomViews.addedBy(l, e.addedByUserId(), e.addedByGuestId());
        if (by != null) {
            request.add("by", RoomViews.actor(by));
        }
        request.addProperty("via", r.via());
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "request.new");
        msg.add("request", request);
        for (Member m : mods) {
            m.conn.send(msg.deepCopy());
        }
    }

    private static JsonArray entries(RoomViews.Lookup l, RoomSnapshot s, List<QueueEntry> entries) {
        JsonArray a = new JsonArray();
        for (QueueEntry e : entries) {
            a.add(RoomViews.entry(l, e, s));
        }
        return a;
    }

    private static JsonArray ids(List<QueueEntry> entries) {
        JsonArray a = new JsonArray();
        for (QueueEntry e : entries) {
            a.add(e.id());
        }
        return a;
    }

    /** Adds {@code yourVote} (1, -1 or 0) to each entry of {@code entries}. */
    private static void yourVotes(JsonArray entries, RoomSnapshot s, RoomActor a) {
        String key = a.identified() ? a.key() : null;
        for (JsonElement el : entries) {
            JsonObject o = el.getAsJsonObject();
            Integer v = key == null ? null : s.votesOn(o.get("entryId").getAsString()).get(key);
            o.addProperty("yourVote", v == null ? 0 : v);
        }
    }

    /** The {@code you} object: role and what the socket's actor may do ({@link ControlPolicy#capabilities}). */
    private static JsonObject you(RoomSnapshot s, RoomActor a) {
        ControlPolicy.Capabilities c = ControlPolicy.capabilities(s.controlMode(), a);
        JsonObject o = new JsonObject();
        if (c.role() != null) {
            o.addProperty("role", c.role());
        }
        o.addProperty("identified", a.identified());
        o.addProperty("canVote", c.canVote());
        o.addProperty("canRequest", c.canRequest());
        // requests by item id (library search, the history's Add to Queue); room setting libraryRequests
        JsonElement libraryRequests = s.settings().get("libraryRequests");
        boolean libraryOn = libraryRequests == null || !libraryRequests.isJsonPrimitive()
                || libraryRequests.getAsBoolean();
        o.addProperty("canRequestLibrary", a.identified() && (a.moderates() || a.canRequestLibrary() && libraryOn));
        // requests by upload; room setting uploadRequests (only on while libraryRequests is)
        JsonElement uploadRequests = s.settings().get("uploadRequests");
        boolean uploadOn = libraryOn && (uploadRequests == null || !uploadRequests.isJsonPrimitive()
                || uploadRequests.getAsBoolean());
        o.addProperty("canUpload", c.canUpload() && (a.moderates() || uploadOn));
        o.addProperty("canYoutube", c.canYoutube());
        o.addProperty("canSkip", c.canSkip());
        o.addProperty("canModerate", a.moderates());
        return o;
    }

    private static JsonObject message(String type, long version) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("version", version);
        return o;
    }

    private void kickMessage(Member m, String reason) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "kicked");
        msg.addProperty("reason", reason);
        m.conn.send(msg);
        m.conn.removedFromRoom(this);
    }

    private void leavePresence(String connectionId) {
        executor.submit(tx -> PresenceOps.leave(tx, connectionId));
    }
}
