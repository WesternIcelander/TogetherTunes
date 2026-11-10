package io.siggi.togethertunes.rooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketCloseInfo;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.auth.ActorRefs;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.IdentityTickets;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebSockets;
import io.siggi.togethertunes.ingest.IngestProgress;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.library.YoutubeLookupStage;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The room WebSocket, {@code GET /ws} ({@code docs/api.md#websocket}).
 *
 * <p><b>Identity.</b> A socket starts as the actor of its upgrade request (the {@code sid} or
 * {@code gid} cookie, an IP-mode guest, or anonymous; {@link ActorResolver}) and is told so with
 * an {@code identity} message. A bearer client sends {@code {type:"auth", token}} before joining.
 * An anonymous socket becomes a guest with {@code {type:"identity", ticket}}, the single-use ticket
 * from {@code POST /api/me/guest} ({@link IdentityTickets}). Before every room action the identity
 * is checked again (the session may have been revoked or the user disabled, the guest deleted);
 * if it no longer holds, the socket gets {@code kicked} ({@code session_ended}) and is closed.
 *
 * <p><b>Rooms.</b> One room per socket: {@code join} (with {@code mode} {@code listen} or
 * {@code remote}) checks access ({@link Rooms#access}, so private rooms and room bans hold),
 * enters the room's presence ({@link PresenceOps}) and subscribes the socket to the room's
 * {@link RoomBroadcaster}, which sends the full {@code state} and then every change with its
 * {@code version}. {@code sync} re-sends the full state (a client that saw a gap in
 * {@code version}), {@code mode} switches listen/remote, {@code leave} leaves.
 *
 * <p><b>Requests.</b> Queue and playback messages run through {@link RoomQueue} with the socket's
 * actor and a freshly built {@link RoomActor} ({@link RoomGuests#roomActor}: room role and effective
 * guest capabilities). A message with a {@code rid} is answered with {@code ack} (plus the result)
 * or {@code error} ({@code status}, {@code code}, {@code message}), echoing the {@code rid}; one
 * without gets only an {@code error} if it fails. Actions that need an identity fail with
 * {@code identity_required} on an anonymous socket. {@code queue.add},
 * {@code queue.addPlaylist} and {@code queue.retry} may import or resolve media first (or wait for
 * an import of the same video), so they run on a thread of their
 * own (at most {@value #MAX_SLOW_ACTIONS} at a time per socket) and the socket keeps answering
 * {@code time} meanwhile. {@code time} is answered at once, at any point. Unknown message types are
 * ignored.
 *
 * <p><b>Sending.</b> Every outgoing message goes through the socket's bounded outbox and one
 * sender thread, so the room executor and broadcaster never wait on a socket. A socket whose
 * outbox overflows ({@value #OUTBOX_LIMIT} messages) is too slow and is closed (1008).
 *
 * <p>No message carries an IP address: actors are {@code {kind, id, name, isGuest}}, and the
 * client IP is only used to resolve the actor.
 */
public final class RoomSocket {
    private static final System.Logger LOG = System.getLogger(RoomSocket.class.getName());

    /** Route of the room socket. */
    public static final String PATH = "/ws";
    /** Messages waiting to be sent before a socket counts as too slow and is closed. */
    static final int OUTBOX_LIMIT = 1024;
    /** {@code queue.add} / {@code queue.addPlaylist} running at once per socket. */
    static final int MAX_SLOW_ACTIONS = 4;
    /** Close code for a socket that can't keep up (policy violation). */
    static final int CLOSE_TOO_SLOW = 1008;

    private final ActorResolver actors;
    private final IdentityTickets tickets;
    private final Guests guests;
    private final Settings settings;
    private final Rooms rooms;
    private final RoomGuests roomGuests;
    private final RoomRegistry registry;
    private final RoomQueue queue;
    private final ActorRefs actorRefs;
    private final RoomViews views;
    private final Clock clock;
    private final Map<RoomExecutor, RoomBroadcaster> broadcasters = new ConcurrentHashMap<>();
    private volatile RoomOutputs outputs = RoomOutputs.NONE;
    private volatile IngestProgress ingestProgress = IngestProgress.NONE;
    private volatile long ingestIntervalMs = RoomBroadcaster.INGEST_INTERVAL_MS;

    public RoomSocket(ActorResolver actors, IdentityTickets tickets, Guests guests, Settings settings, Rooms rooms,
                      RoomGuests roomGuests, RoomRegistry registry, RoomQueue queue, Database db, Clock clock) {
        this.actors = Objects.requireNonNull(actors);
        this.tickets = Objects.requireNonNull(tickets);
        this.guests = Objects.requireNonNull(guests);
        this.settings = Objects.requireNonNull(settings);
        this.rooms = Objects.requireNonNull(rooms);
        this.roomGuests = Objects.requireNonNull(roomGuests);
        this.registry = Objects.requireNonNull(registry);
        this.queue = Objects.requireNonNull(queue);
        this.actorRefs = new ActorRefs(db);
        this.views = new RoomViews(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Registers {@value #PATH}. The actor is resolved before the upgrade, so an invalid bearer
     * token is a plain {@code 401 invalid_token} and never becomes a socket.
     */
    public void register(Router router, WebSockets webSockets) {
        router.get(PATH, webSockets.handler(this::open), (request, response, next) -> {
            ActorResolver.actor(request);
            next.handle(request, response);
        });
    }

    private WebSockets.Session open(Request request, SimpleWebsocket ws) throws Exception {
        Connection c = new Connection(ws, ActorResolver.actor(request), ActorResolver.Credentials.of(request));
        c.sendIdentity();
        return c;
    }

    /** The broadcaster of a room executor, made and registered on first use. */
    private RoomBroadcaster broadcaster(RoomExecutor ex) {
        RoomBroadcaster b = broadcasters.computeIfAbsent(ex, e -> {
            RoomBroadcaster nb = new RoomBroadcaster(e, views, roomGuests::roomActor, () -> broadcasters.remove(e),
                    roomId -> outputs.active(roomId), ids -> ingestProgress.of(ids), ingestIntervalMs);
            e.addListener(nb);
            return nb;
        });
        if (ex.isClosed()) {
            // Closed before (or while) the listener was added: it may never hear of it.
            b.onClosed(ex.roomId(), false);
            throw new RoomClosedException(ex.roomId());
        }
        return b;
    }

    /**
     * What the ingest of not-ready items is doing (the {@code ingest} messages), looked up at most
     * every {@code intervalMs} per room while a room has such an entry.
     */
    public void ingestProgress(IngestProgress progress, long intervalMs) {
        this.ingestProgress = Objects.requireNonNull(progress);
        this.ingestIntervalMs = intervalMs;
    }

    /** Where the rooms' program outputs come from (the {@code outputs} of {@code state}). */
    public void outputs(RoomOutputs outputs) {
        this.outputs = Objects.requireNonNull(outputs);
    }

    /**
     * One of a room's outputs changed (status, turned on or off): sends {@code output.status} to
     * the sockets in the room. Any thread; never blocks.
     */
    public void outputStatus(String roomId, RoomOutputs.Status status) {
        for (RoomBroadcaster b : broadcasters.values()) {
            if (b.roomId().equals(roomId)) {
                b.outputStatus(status);
            }
        }
    }

    /** How many rooms have a broadcaster (tests). */
    int broadcasterCount() {
        return broadcasters.size();
    }

    /** The room a socket is in. */
    private record Joined(String roomId, RoomExecutor executor, RoomBroadcaster broadcaster) {
    }

    /** A message handler's result: the fields added to the {@code ack}. */
    @FunctionalInterface
    private interface Action {
        JsonObject run() throws Exception;
    }

    private static final String POISON = "\u0000stop";
    private static final String CLOSE_SLOW = "\u0000slow";
    private static final String CLOSE_ENDED = "\u0000ended";

    /** One socket. Messages are handled on the socket's thread, in order (slow actions aside). */
    private final class Connection implements WebSockets.Session, RoomBroadcaster.Conn {
        private final String id = UuidV7.newId();
        private final SimpleWebsocket ws;
        private final String clientIp;
        private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(OUTBOX_LIMIT + 1);
        private final Semaphore slowActions = new Semaphore(MAX_SLOW_ACTIONS);
        private final Object lock = new Object();
        private volatile Actor actor;
        /** What the actor was resolved from; {@code null} for a guest that came by identity ticket. */
        private volatile ActorResolver.Credentials credentials;
        private volatile boolean closed;
        /** The identity no longer holds: the socket closes after the current message. */
        private volatile boolean ending;
        /** Guarded by {@link #lock}. */
        private Joined joined;

        Connection(SimpleWebsocket ws, Actor actor, ActorResolver.Credentials credentials) {
            this.ws = ws;
            this.actor = actor;
            this.credentials = credentials;
            this.clientIp = credentials.clientIp();
            Thread.ofVirtual().name("ws-send-" + id).start(this::sendLoop);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Actor actor() {
            return actor;
        }

        // ------------------------------------------------------------ sending

        @Override
        public void send(JsonObject message) {
            if (closed) {
                return;
            }
            if (!outbox.offer(Json.GSON.toJson(message)) || outbox.size() > OUTBOX_LIMIT) {
                LOG.log(System.Logger.Level.INFO, "WebSocket " + id + " can't keep up; closing it");
                closed = true;
                outbox.clear();
                outbox.offer(CLOSE_SLOW);
            }
        }

        private void sendLoop() {
            try {
                while (true) {
                    String text = outbox.take();
                    if (text == POISON) {
                        return;
                    }
                    if (text == CLOSE_SLOW) {
                        ws.close(CLOSE_TOO_SLOW, "Too slow");
                        return;
                    }
                    if (text == CLOSE_ENDED) {
                        ws.close(SimpleWebsocket.CLOSE_NORMAL, "Session ended");
                        return;
                    }
                    ws.send(WebSocketMessage.create(text));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                // The socket is gone; its read loop ends too.
                closed = true;
            }
        }

        private void reply(JsonElement rid, JsonObject result) {
            if (rid == null) {
                return;
            }
            JsonObject ack = new JsonObject();
            ack.addProperty("type", "ack");
            ack.add("rid", rid);
            if (result != null) {
                for (Map.Entry<String, JsonElement> e : result.entrySet()) {
                    ack.add(e.getKey(), e.getValue());
                }
            }
            send(ack);
        }

        private void error(JsonElement rid, int status, String code, String message) {
            JsonObject err = new JsonObject();
            err.addProperty("type", "error");
            if (rid != null) {
                err.add("rid", rid);
            }
            err.addProperty("status", status);
            err.addProperty("code", code);
            err.addProperty("message", message);
            send(err);
        }

        /** {@code {type:"identity", actor}}; {@code actor} is left out for an anonymous socket. */
        void sendIdentity() throws Exception {
            ActorRef ref = actorRefs.of(actor);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "identity");
            if (ref != null) {
                msg.add("actor", RoomViews.actor(ref));
            }
            send(msg);
        }

        // ------------------------------------------------------------ receiving

        @Override
        public void onMessage(WebSocketMessage message) {
            if (closed) {
                return;
            }
            if (message.isBinary()) {
                error(null, 400, "invalid_message", "Messages are JSON text");
                return;
            }
            JsonObject msg;
            try {
                JsonElement parsed = JsonParser.parseString(message.getText());
                if (!parsed.isJsonObject()) {
                    throw new JsonParseException("not an object");
                }
                msg = parsed.getAsJsonObject();
            } catch (JsonParseException e) {
                error(null, 400, "invalid_message", "Messages are JSON objects");
                return;
            }
            JsonElement rid = msg.get("rid");
            if (rid != null && !(rid.isJsonPrimitive() && (rid.getAsJsonPrimitive().isNumber()
                    || rid.getAsJsonPrimitive().isString()))) {
                rid = null;
            }
            JsonElement typeEl = msg.get("type");
            if (typeEl == null || !typeEl.isJsonPrimitive() || !typeEl.getAsJsonPrimitive().isString()) {
                error(rid, 400, "invalid_message", "A message needs a string type");
                return;
            }
            String type = typeEl.getAsString();
            JsonElement ridFinal = rid;
            switch (type) {
                case "time" -> time(rid, msg);
                case "auth" -> handle(rid, () -> auth(msg));
                case "identity" -> handle(rid, () -> identity(msg));
                case "join" -> handle(rid, () -> join(msg));
                case "leave" -> handle(rid, this::leave);
                case "mode" -> handle(rid, () -> mode(msg));
                case "sync" -> handle(rid, this::sync);
                case "queue.add" -> slow(rid, () -> add(ridFinal, msg));
                case "queue.addPlaylist" -> slow(rid, () -> addPlaylist(msg));
                case "queue.remove" -> handle(rid, () -> room((j, a) -> {
                    queue.remove(j.roomId(), a, string(msg, "entryId"));
                    return null;
                }));
                case "queue.retry" -> slow(rid, () -> room((j, a) -> {
                    YoutubeImports.Retried r = queue.retry(j.roomId(), actor, a, string(msg, "entryId"), clientIp);
                    JsonObject o = new JsonObject();
                    o.addProperty("itemId", r.itemId());
                    o.addProperty("status", r.status());
                    return o;
                }));
                case "queue.move" -> handle(rid, () -> room((j, a) -> result("moved",
                        queue.move(j.roomId(), a, string(msg, "entryId"), optionalString(msg, "beforeEntryId")))));
                case "queue.pin" -> handle(rid, () -> room((j, a) -> result("changed",
                        queue.pin(j.roomId(), a, string(msg, "entryId"), optionalBoolean(msg, "pinned", true)))));
                case "vote" -> handle(rid, () -> room((j, a) -> result("votes",
                        queue.vote(j.roomId(), a, string(msg, "entryId"), voteValue(msg)))));
                case "suggest.accept" -> handle(rid, () -> room((j, a) -> {
                    queue.accept(j.roomId(), a, string(msg, "entryId"));
                    return null;
                }));
                case "suggest.reject" -> handle(rid, () -> room((j, a) -> {
                    queue.reject(j.roomId(), a, string(msg, "entryId"));
                    return null;
                }));
                case "play" -> handle(rid, () -> room((j, a) -> result("changed", queue.play(j.roomId(), a))));
                case "pause" -> handle(rid, () -> room((j, a) -> result("changed", queue.pause(j.roomId(), a))));
                case "seek" -> handle(rid, () -> room((j, a) -> {
                    queue.seek(j.roomId(), a, position(msg));
                    return null;
                }));
                case "next" -> handle(rid, () -> room((j, a) -> {
                    queue.next(j.roomId(), a);
                    return null;
                }));
                case "previous" -> handle(rid, () -> room((j, a) -> result("changed",
                        queue.previous(j.roomId(), a))));
                case "voteSkip" -> handle(rid, () -> room((j, a) -> {
                    QueueControl.VoteSkip v = queue.voteSkip(j.roomId(), a);
                    JsonObject o = new JsonObject();
                    o.addProperty("votes", v.votes());
                    o.addProperty("needed", v.needed());
                    o.addProperty("skipped", v.skipped());
                    return o;
                }));
                default -> {
                    // Unknown types are ignored (newer clients, other features).
                }
            }
        }

        @Override
        public void onClose(WebSocketCloseInfo info) {
            closed = true;
            leaveRoom();
            outbox.clear();
            outbox.offer(POISON);
        }

        private void handle(JsonElement rid, Action action) {
            try {
                reply(rid, action.run());
            } catch (HttpError e) {
                error(rid, e.status(), e.code(), e.getMessage());
            } catch (RoomClosedException e) {
                error(rid, 503, "room_closed", "The room is not available right now; join it again");
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "WebSocket " + id + " message failed", e);
                error(rid, 500, "internal_error", "Something went wrong");
            }
            if (ending && !closed) {
                // The session ended: close once the messages before this are out.
                closed = true;
                outbox.offer(CLOSE_ENDED);
            }
        }

        /** Runs a slow action on a thread of its own, so {@code time} and others stay answered. */
        private void slow(JsonElement rid, Action action) {
            if (!slowActions.tryAcquire()) {
                error(rid, 429, "too_many_requests", "Wait for your other requests to finish");
                return;
            }
            Thread.ofVirtual().name("ws-action-" + id).start(() -> {
                try {
                    handle(rid, action);
                } finally {
                    slowActions.release();
                }
            });
        }

        // ------------------------------------------------------------ messages

        private void time(JsonElement rid, JsonObject msg) {
            JsonElement t0 = msg.get("t0");
            if (t0 == null || !t0.isJsonPrimitive() || !t0.getAsJsonPrimitive().isNumber()) {
                error(rid, 400, "invalid_message", "t0 must be a number");
                return;
            }
            JsonObject out = new JsonObject();
            out.addProperty("type", "time");
            out.add("t0", t0);
            out.addProperty("ts", clock.millis());
            send(out);
        }

        private JsonObject auth(JsonObject msg) throws Exception {
            String token = string(msg, "token");
            synchronized (lock) {
                if (joined != null) {
                    throw new HttpError(409, "already_joined", "Authenticate before joining a room");
                }
            }
            ActorResolver.Credentials c = new ActorResolver.Credentials(token, null, null, clientIp);
            Actor a = actors.resolve(c); // 401 invalid_token
            actor = a;
            credentials = c;
            sendIdentity();
            return null;
        }

        private JsonObject identity(JsonObject msg) throws Exception {
            String ticket = string(msg, "ticket");
            IdentityTickets.Ticket t = tickets.redeem(ticket);
            if (t == null) {
                throw new HttpError(400, "invalid_ticket", "The identity ticket is unknown, used or expired");
            }
            Actor current = actor;
            if (current instanceof Actor.User) {
                throw new HttpError(409, "already_identified", "This socket is logged in");
            }
            if (current instanceof Actor.Guest g) {
                if (!g.guestId().equals(t.guestId())) {
                    throw new HttpError(409, "already_identified", "This socket is another guest");
                }
                sendIdentity();
                return null;
            }
            if (!settings.guestsEnabled()) {
                throw new HttpError(403, "guests_disabled", "Guests are switched off");
            }
            Guests.GuestView guest = guests.get(t.guestId());
            if (guest == null) {
                throw new HttpError(400, "invalid_ticket", "The identity ticket is unknown, used or expired");
            }
            actor = guest.actor();
            credentials = null;
            sendIdentity();
            Joined j = joined();
            if (j != null) {
                RoomActor a;
                try {
                    a = roomGuests.roomActor(actor, j.roomId());
                } catch (HttpError refused) {
                    // e.g. this guest is banned from the room
                    leaveRoom();
                    kicked(refused.code());
                    return null;
                }
                try {
                    j.executor().call(tx -> {
                        PresenceOps.identify(tx, id, a);
                        j.broadcaster().resend(this, a, RoomSnapshot.of(tx.state()));
                        return null;
                    });
                } catch (RoomClosedException e) {
                    // The broadcaster tells the socket.
                }
            }
            return null;
        }

        private JsonObject join(JsonObject msg) throws Exception {
            String room = string(msg, "room");
            RoomPresence.Mode mode = parseMode(optionalString(msg, "mode"));
            refreshActor();
            leaveRoom();
            Actor who = actor;
            Rooms.Access access = rooms.access(who, room);
            RoomActor a = roomGuests.roomActor(who, access);
            String roomId = access.room().id();
            for (int attempt = 1; ; attempt++) {
                RoomExecutor ex = registry.executor(roomId);
                Joined j;
                try {
                    j = new Joined(roomId, ex, broadcaster(ex));
                } catch (RoomClosedException e) {
                    if (attempt >= 3) {
                        throw e;
                    }
                    continue;
                }
                synchronized (lock) {
                    joined = j;
                }
                try {
                    ex.call(tx -> {
                        PresenceOps.join(tx, id, a, mode);
                        j.broadcaster().join(this, a, RoomSnapshot.of(tx.state()));
                        return null;
                    });
                    JsonObject out = new JsonObject();
                    out.addProperty("roomId", roomId);
                    out.addProperty("slug", access.room().slug());
                    out.addProperty("mode", mode.wire());
                    return out;
                } catch (Exception e) {
                    synchronized (lock) {
                        if (joined == j) {
                            joined = null;
                        }
                    }
                    if (e instanceof RoomClosedException && attempt < 3) {
                        continue;
                    }
                    throw e;
                }
            }
        }

        private JsonObject leave() {
            leaveRoom();
            return null;
        }

        private JsonObject mode(JsonObject msg) throws Exception {
            RoomPresence.Mode mode = parseMode(string(msg, "mode"));
            Joined j = requireJoined();
            j.executor().call(tx -> PresenceOps.mode(tx, id, mode));
            return null;
        }

        private JsonObject sync() throws Exception {
            Joined j = requireJoined();
            refreshActor();
            RoomActor a = roomActor(j);
            j.executor().call(tx -> {
                j.broadcaster().resend(this, a, RoomSnapshot.of(tx.state()));
                return null;
            });
            return null;
        }

        /**
         * {@code request.progress}: a stage of the YouTube lookup of this socket's {@code queue.add}
         * with request id {@code rid}, to this socket only (nothing without a {@code rid}).
         */
        private void lookupStage(JsonElement rid, YoutubeLookupStage stage) {
            if (rid == null) {
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", "request.progress");
            o.add("rid", rid);
            o.addProperty("stage", stage.wire());
            o.addProperty("text", stage.text());
            send(o);
        }

        private JsonObject add(JsonElement rid, JsonObject msg) throws Exception {
            String itemId = optionalString(msg, "itemId");
            String uploadId = optionalString(msg, "uploadId");
            String youtubeUrl = optionalString(msg, "youtubeUrl");
            return room((j, a) -> {
                QueueOps.Added added = queue.add(j.roomId(), actor, a,
                        new RoomQueue.Source(itemId, uploadId, youtubeUrl), stage -> lookupStage(rid, stage));
                JsonObject o = new JsonObject();
                o.addProperty("entryId", added.entryId());
                o.addProperty("itemId", added.itemId());
                o.addProperty("state", added.state().wire());
                return o;
            });
        }

        private JsonObject addPlaylist(JsonObject msg) throws Exception {
            String playlistId = string(msg, "playlistId");
            boolean shuffle = optionalBoolean(msg, "shuffle", false);
            return room((j, a) -> {
                QueueOps.PlaylistAdded added = queue.addPlaylist(j.roomId(), actor, a, playlistId, shuffle);
                JsonObject o = new JsonObject();
                JsonArray ids = new JsonArray();
                added.entryIds().forEach(ids::add);
                o.add("entryIds", ids);
                o.addProperty("skipped", added.skipped());
                return o;
            });
        }

        /** A room action: the socket must be in a room; its identity and access are checked again first. */
        private JsonObject room(RoomAction action) throws Exception {
            Joined j = requireJoined();
            refreshActor();
            return action.run(j, roomActor(j));
        }

        /** The socket's room actor now; a socket that may no longer see the room is kicked out of it. */
        private RoomActor roomActor(Joined j) throws Exception {
            try {
                return roomGuests.roomActor(actor, j.roomId());
            } catch (HttpError refused) {
                if (refused.status() == 403 || refused.status() == 404) {
                    leaveRoom();
                    kicked(refused.code());
                }
                throw refused;
            }
        }

        /**
         * Checks the socket's identity again: a revoked or expired session, a disabled user or a
         * deleted guest ends the socket ({@code kicked} {@code session_ended}, then closed). An
         * anonymous socket stays anonymous (it becomes a guest only with an identity ticket).
         */
        private void refreshActor() throws Exception {
            Actor current = actor;
            if (current instanceof Actor.Anonymous) {
                return;
            }
            Actor fresh;
            ActorResolver.Credentials c = credentials;
            if (c != null) {
                try {
                    fresh = actors.resolve(c);
                } catch (HttpError invalidToken) {
                    fresh = null;
                }
            } else {
                Actor.Guest g = (Actor.Guest) current;
                Guests.GuestView view = settings.guestsEnabled() ? guests.get(g.guestId()) : null;
                fresh = view == null ? null : view.actor();
            }
            if (fresh != null && sameIdentity(current, fresh)) {
                actor = fresh;
                return;
            }
            leaveRoom();
            kicked("session_ended");
            ending = true; // handle() sends the error, then the socket closes
            throw new HttpError(401, "session_ended", "Your session ended; connect again");
        }

        private Joined requireJoined() {
            Joined j = joined();
            if (j == null) {
                throw new HttpError(409, "not_joined", "Join a room first");
            }
            return j;
        }

        private Joined joined() {
            synchronized (lock) {
                return joined;
            }
        }

        /** Leaves the room the socket is in, if any (presence and broadcasts). */
        private void leaveRoom() {
            Joined j;
            synchronized (lock) {
                j = joined;
                joined = null;
            }
            if (j == null) {
                return;
            }
            j.broadcaster().leave(this);
            j.executor().submit(tx -> PresenceOps.leave(tx, id));
        }

        @Override
        public void removedFromRoom(RoomBroadcaster from) {
            synchronized (lock) {
                if (joined != null && joined.broadcaster() == from) {
                    joined = null;
                }
            }
        }

        private void kicked(String reason) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "kicked");
            msg.addProperty("reason", reason);
            send(msg);
        }
    }

    @FunctionalInterface
    private interface RoomAction {
        JsonObject run(Joined joined, RoomActor actor) throws Exception;
    }

    private static boolean sameIdentity(Actor a, Actor b) {
        return switch (a) {
            case Actor.User u -> b instanceof Actor.User v && u.userId().equals(v.userId());
            case Actor.Guest g -> b instanceof Actor.Guest h && g.guestId().equals(h.guestId());
            case Actor.Anonymous ignored -> b instanceof Actor.Anonymous;
        };
    }

    // ------------------------------------------------------------------ message fields

    private static JsonObject result(String name, boolean value) {
        JsonObject o = new JsonObject();
        o.addProperty(name, value);
        return o;
    }

    private static JsonObject result(String name, int value) {
        JsonObject o = new JsonObject();
        o.addProperty(name, value);
        return o;
    }

    private static String string(JsonObject msg, String name) {
        String s = optionalString(msg, name);
        if (s == null) {
            throw new HttpError(400, "invalid_message", name + " is required");
        }
        return s;
    }

    private static String optionalString(JsonObject msg, String name) {
        JsonElement e = msg.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new HttpError(400, "invalid_message", name + " must be a string");
        }
        return e.getAsString();
    }

    private static boolean optionalBoolean(JsonObject msg, String name, boolean dflt) {
        JsonElement e = msg.get(name);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new HttpError(400, "invalid_message", name + " must be true or false");
        }
        return e.getAsBoolean();
    }

    private static int voteValue(JsonObject msg) {
        JsonElement e = msg.get("value");
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new HttpError(400, "invalid_vote", "value must be 1, 0 or -1");
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        double d = p.getAsDouble();
        if (d != Math.rint(d) || Math.abs(d) > 1) {
            throw new HttpError(400, "invalid_vote", "value must be 1, 0 or -1");
        }
        return (int) d;
    }

    private static long position(JsonObject msg) {
        JsonElement e = msg.get("positionMs");
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new HttpError(400, "invalid_position", "positionMs must be a number");
        }
        double d = e.getAsDouble();
        if (!Double.isFinite(d) || d < 0 || d > 1e13) {
            throw new HttpError(400, "invalid_position", "positionMs must be a position in the entry");
        }
        return (long) d;
    }

    /** {@code listen} (also when not given) or {@code remote}. */
    private static RoomPresence.Mode parseMode(String s) {
        if (s == null) {
            return RoomPresence.Mode.LISTEN;
        }
        RoomPresence.Mode m = RoomPresence.Mode.parse(s);
        if (m == null) {
            throw new HttpError(400, "invalid_mode", "mode must be listen or remote");
        }
        return m;
    }
}
