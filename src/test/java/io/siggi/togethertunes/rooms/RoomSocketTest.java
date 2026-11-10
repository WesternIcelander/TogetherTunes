package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.IdentityTickets;
import io.siggi.togethertunes.auth.MeApi;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Csrf;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.http.WebSockets;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The room WebSocket ({@link RoomSocket}, {@code docs/api.md#websocket}) over a real socket:
 * identity, time, join/leave/mode/sync, the identity ticket, queue and playback messages with
 * {@code ack}/{@code error}, full state and deltas with consecutive versions, {@code request.new},
 * kicks, and no IP address in any message.
 */
class RoomSocketTest {
    private static final String ORIGIN = "https://tunes.example";
    private static final String CLIENT_IP = "203.0.113.77";

    @TempDir
    Path dir;

    private Database db;
    private TestUploads.RoomsWiring wiring;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final List<Client> clients = new ArrayList<>();
    private final List<String> leaks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Sessions sessions;
    private Settings settings;
    private IngestJobs jobs;
    private String aliceId;
    private String bobId;
    private String alice;
    private String bob;
    private Accounts accounts;

    @BeforeEach
    void setUp() throws Exception {
        // A slow fake yt-dlp: -J prints yt-dlp's progress lines (and others to ignore) to stderr, then the JSON.
        Path ytdlp = dir.resolve("fake-yt-dlp");
        java.nio.file.Files.writeString(ytdlp, "#!/bin/sh\n"
                + "echo '[debug] Command-line config: [\"-J\"]' >&2\n"
                + "echo '[youtube] Extracting URL: https://www.youtube.com/watch?v=jNQXAC9IVRw' >&2\n"
                + "sleep 0.2\n"
                + "echo '[youtube] jNQXAC9IVRw: Downloading webpage' >&2\n"
                + "echo 'Something yt-dlp has never printed before' >&2\n"
                + "sleep 0.2\n"
                + "echo '[youtube] jNQXAC9IVRw: Downloading android vr player API JSON' >&2\n"
                + "echo '[youtube] jNQXAC9IVRw: Downloading webpage' >&2\n"
                + "sleep 0.2\n"
                + "echo '[info] jNQXAC9IVRw: Downloading 1 format(s): 18' >&2\n"
                + "echo '{\"id\":\"jNQXAC9IVRw\",\"title\":\"Me at the zoo\",\"uploader\":\"jawed\","
                + "\"duration\":19,\"live_status\":\"not_live\",\"is_live\":false}'\n",
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.setPosixFilePermissions(ytdlp, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", ORIGIN,
                "TOGETHERTUNES_TOOLS_YTDLP", ytdlp.toString()), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        ActorResolver actors = new ActorResolver(db, settings, clock, false);
        Router router = new Router(actors.hook());
        Permissions permissions = new Permissions(db, settings);
        Passwords passwords = new Passwords(new Passwords.Params(64, 1, 1));
        accounts = new Accounts(db, passwords, clock);
        IdentityTickets tickets = new IdentityTickets(clock);
        wiring = TestUploads.rooms(db, dir, config, settings, permissions, actors, tickets, clock);
        wiring.api().register(router);
        jobs = new IngestJobs(db, clock);
        jobs.statusListener(wiring.registry()); // as Main wires it
        TestUploads.mediaApi(db, dir, settings, wiring.registry().playingItems()).register(router);
        new MeApi(accounts, new Users(db, passwords, clock), permissions, new Guests(db, settings, false), settings,
                tickets).register(router);
        wiring.socket().register(router, new WebSockets(new Csrf(ORIGIN, List.of("sid", "gid"))));
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        for (Client c : clients) {
            c.close();
        }
        server.close();
        wiring.registry().close();
        db.close();
        assertEquals(List.of(), leaks, "messages with an IP address");
    }

    // ------------------------------------------------------------------ helpers

    /** A test client: a reader thread collects every message. */
    private final class Client implements AutoCloseable {
        final SimpleWebsocket ws;
        final LinkedBlockingQueue<JsonObject> inbox = new LinkedBlockingQueue<>();
        final List<JsonObject> seen = new ArrayList<>();
        int rid;

        Client(SimpleWebsocket ws) {
            this.ws = ws;
            Thread.ofVirtual().start(() -> {
                try {
                    WebSocketMessage m;
                    while ((m = ws.read()) != null) {
                        if (!m.isControlMessage()) {
                            String text = m.getText();
                            // invariant 5: no IP in any message (checked in tearDown)
                            if (text.contains(CLIENT_IP) || text.contains("127.0.0.1")) {
                                leaks.add(text);
                            }
                            inbox.add(JsonParser.parseString(text).getAsJsonObject());
                        }
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
        }

        void send(String json) throws IOException {
            ws.send(WebSocketMessage.create(json));
        }

        /** Messages received and not consumed yet, oldest first. */
        final List<JsonObject> pending = new ArrayList<>();

        /** The oldest unconsumed message matching {@code p} (others stay unconsumed). */
        JsonObject await(Predicate<JsonObject> p) throws InterruptedException {
            for (int i = 0; i < pending.size(); i++) {
                if (p.test(pending.get(i))) {
                    return pending.remove(i);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                JsonObject m = inbox.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(m, () -> "timed out; unconsumed " + pending);
                seen.add(m);
                if (p.test(m)) {
                    return m;
                }
                pending.add(m);
            }
        }

        JsonObject await(String type) throws InterruptedException {
            return await(m -> m.get("type").getAsString().equals(type));
        }

        /** The oldest unconsumed message, which must be of {@code type}. */
        JsonObject next(String type) throws InterruptedException {
            JsonObject m = pending.isEmpty() ? inbox.poll(5, TimeUnit.SECONDS) : pending.remove(0);
            assertNotNull(m, "timed out waiting for " + type);
            if (!seen.contains(m)) {
                seen.add(m);
            }
            assertEquals(type, m.get("type").getAsString(), m.toString());
            return m;
        }

        /** No new message of {@code type} comes within 300 ms. */
        void quiet(String type) throws InterruptedException {
            Thread.sleep(300);
            List<JsonObject> fresh = new ArrayList<>();
            inbox.drainTo(fresh);
            seen.addAll(fresh);
            pending.addAll(fresh);
            for (JsonObject m : fresh) {
                assertFalse(m.get("type").getAsString().equals(type), () -> "unexpected " + m);
            }
        }

        /** Sends a request with a new rid and returns its ack or error. */
        JsonObject request(String json) throws Exception {
            int r = ++rid;
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            o.addProperty("rid", r);
            send(o.toString());
            return await(m -> (m.get("type").getAsString().equals("ack") || m.get("type").getAsString().equals("error"))
                    && m.has("rid") && m.get("rid").getAsInt() == r);
        }

        JsonObject ok(String json) throws Exception {
            JsonObject r = request(json);
            assertEquals("ack", r.get("type").getAsString(), r.toString());
            return r;
        }

        void error(String code, String json) throws Exception {
            JsonObject r = request(json);
            assertEquals("error", r.get("type").getAsString(), r.toString());
            assertEquals(code, r.get("code").getAsString(), r.toString());
        }

        @Override
        public void close() {
            try {
                ws.close();
            } catch (IOException | RuntimeException ignored) {
                // gone
            }
        }
    }

    private Client connect(String bearer, String cookie) throws IOException {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", ORIGIN);
        headers.setHeader("X-Forwarded-For", CLIENT_IP);
        if (bearer != null) {
            headers.setHeader("Authorization", "Bearer " + bearer);
        }
        if (cookie != null) {
            headers.setHeader("Cookie", cookie);
        }
        Client c = new Client(new WebSocketConnector().setMaxPayloadLength(1 << 22).connect(
                URI.create("ws://127.0.0.1:" + server.port() + RoomSocket.PATH), headers));
        clients.add(c);
        return c;
    }

    private HttpResponse<String> call(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    /** A room of alice's; returns its id. */
    private String room(String slug, String visibility, String controlMode) throws Exception {
        HttpResponse<String> r = call(alice, "POST", "/api/rooms", "{\"name\":\"" + slug + "\",\"slug\":\"" + slug
                + "\",\"visibility\":\"" + visibility + "\",\"controlMode\":\"" + controlMode + "\"}");
        assertEquals(201, r.statusCode(), r.body());
        return json(r).get("id").getAsString();
    }

    /** A ready 60 s audio upload in alice's library (so in her rooms' request scope). */
    private String item(String title) throws Exception {
        String id = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, artist, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, duration_ms, original_file, original_web, original_gain_db)"
                + " VALUES (?, 'audio', ?, 'Artist', 'upload', ?, 1, 'ready', '720p30', 1, 60000, 'original.mp3', 1, -2.5)",
                id, title, aliceId);
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'upload')", aliceId, id);
        return id;
    }

    /**
     * An upload in alice's library whose ingest is running (item {@code processing}, no duration
     * yet), as right after the upload finished; returns {itemId, jobId}.
     */
    private String[] ingesting(String title) throws Exception {
        String id = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, artist, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at) VALUES (?, 'audio', ?, 'Artist', 'upload', ?, 1, 'pending', '720p30', 1)",
                id, title, aliceId);
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'upload')", aliceId, id);
        jobs.enqueue(id, IngestJob.Kind.UPLOAD);
        IngestJob job = jobs.claimNext();
        assertEquals(id, job.itemId());
        return new String[] {id, job.id()};
    }

    /** The ingest pipeline's publish step (duration known), then the queue marks the job done. */
    private void finishIngest(String[] ingest) throws Exception {
        sql("UPDATE items SET duration_ms = 90000, original_file = 'original.mp3', original_web = 1 WHERE id = ?",
                ingest[0]);
        assertTrue(jobs.complete(ingest[1], "done"));
    }

    private static JsonObject queued(JsonObject state, String entryId) {
        for (JsonElement e : state.getAsJsonArray("queue")) {
            if (e.getAsJsonObject().get("entryId").getAsString().equals(entryId)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    private void sql(String statement, Object... args) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static long version(JsonObject m) {
        return m.get("version").getAsLong();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void identityTimeAndMessageHandling() throws Exception {
        Client anon = connect(null, null);
        JsonObject identity = anon.next("identity");
        assertFalse(identity.has("actor"), identity.toString());
        anon.send("{\"type\":\"time\",\"t0\":123456.7}");
        JsonObject time = anon.next("time");
        assertEquals(123456.7, time.get("t0").getAsDouble());
        assertEquals(clock.millis(), time.get("ts").getAsLong());
        // unknown types are ignored; broken messages get an error
        anon.send("{\"type\":\"somethingNew\",\"rid\":9}");
        anon.send("not json");
        assertEquals("invalid_message", anon.next("error").get("code").getAsString());
        anon.error("not_joined", "{\"type\":\"play\"}");
        anon.error("not_joined", "{\"type\":\"sync\"}");
        anon.error("invalid_message", "{\"type\":\"join\"}");
        anon.error("room_not_found", "{\"type\":\"join\",\"room\":\"nope\"}");

        Client user = connect(alice, null);
        JsonObject who = user.next("identity").getAsJsonObject("actor");
        assertEquals(aliceId, who.get("id").getAsString());
        assertEquals("Alice", who.get("name").getAsString());
        assertFalse(who.get("isGuest").getAsBoolean());

        // a bearer client authenticates with its first message
        Client bearer = connect(null, null);
        bearer.next("identity");
        bearer.error("invalid_token", "{\"type\":\"auth\",\"token\":\"nonsense\"}");
        bearer.ok("{\"type\":\"auth\",\"token\":\"" + bob + "\"}");
        assertEquals(bobId, bearer.await("identity").getAsJsonObject("actor").get("id").getAsString());

        // an invalid bearer header never becomes a socket
        assertThrows(IOException.class, () -> connect("nonsense", null));
    }

    @Test
    void joinSendsTheStateThenEveryChangeWithTheNextVersion() throws Exception {
        String roomId = room("party", "public", "owner");
        String song1 = item("Song One");
        String song2 = item("Song Two");
        Client owner = connect(alice, null);
        owner.next("identity");
        JsonObject joined = owner.ok("{\"type\":\"join\",\"room\":\"party\",\"mode\":\"listen\"}");
        assertEquals(roomId, joined.get("roomId").getAsString());
        JsonObject state = owner.await("state");
        long v = version(state);
        JsonObject room = state.getAsJsonObject("room");
        assertEquals("party", room.get("slug").getAsString());
        assertEquals("owner", room.get("controlMode").getAsString());
        JsonObject you = room.getAsJsonObject("you");
        assertEquals("owner", you.get("role").getAsString());
        assertTrue(you.get("canSkip").getAsBoolean());
        assertTrue(you.get("canRequest").getAsBoolean());
        assertEquals("idle", state.getAsJsonObject("timeline").get("state").getAsString());
        assertFalse(state.has("current"));
        assertEquals(0, state.getAsJsonArray("queue").size());
        assertEquals(1, state.getAsJsonObject("presence").get("listening").getAsInt());
        assertTrue(room.getAsJsonObject("settings").has("guests"));
        // the join itself bumped the version (presence)
        JsonObject presence = owner.next("presence");
        assertEquals(v + 1, version(presence));
        v++;

        // add: ack with the entry, then the full state (it started playing at once)
        JsonObject added = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song1 + "\"}");
        String entry1 = added.get("entryId").getAsString();
        assertEquals("queued", added.get("state").getAsString());
        state = owner.await("state");
        assertEquals(v + 1, version(state));
        v = version(state);
        JsonObject current = state.getAsJsonObject("current");
        assertEquals(entry1, current.get("entryId").getAsString());
        JsonObject item = current.getAsJsonObject("item");
        assertEquals("Song One", item.get("title").getAsString());
        assertEquals("/media/" + song1 + "/v/0/audio.m4a", item.get("audioUrl").getAsString(), "the version's URL");
        assertEquals("/media/" + song1 + "/original", item.get("originalUrl").getAsString());
        assertEquals(-2.5, item.get("originalGainDb").getAsDouble());
        assertFalse(item.has("videoUrl"));
        assertEquals("playing", state.getAsJsonObject("timeline").get("state").getAsString());

        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song2 + "\"}");
        state = owner.await("state");
        assertEquals(v + 1, version(state));
        v = version(state);
        JsonArray queue = state.getAsJsonArray("queue");
        assertEquals(1, queue.size());
        JsonObject e2 = queue.get(0).getAsJsonObject();
        assertEquals("ready", e2.get("status").getAsString());
        assertEquals(aliceId, e2.getAsJsonObject("addedBy").get("id").getAsString());
        assertEquals(0, e2.get("yourVote").getAsInt());

        // pause and seek: timeline deltas
        clock.advance(5000);
        owner.ok("{\"type\":\"pause\"}");
        JsonObject timeline = owner.await("timeline");
        assertEquals(v + 1, version(timeline));
        v++;
        assertEquals("paused", timeline.getAsJsonObject("timeline").get("state").getAsString());
        assertEquals(5000, timeline.getAsJsonObject("timeline").get("anchorPositionMs").getAsLong());
        assertEquals(entry1, timeline.getAsJsonObject("current").get("entryId").getAsString());
        owner.send("{\"type\":\"seek\",\"positionMs\":30000}"); // no rid: no ack
        timeline = owner.next("timeline");
        assertEquals(v + 1, version(timeline));
        v++;
        assertEquals(30000, timeline.getAsJsonObject("timeline").get("anchorPositionMs").getAsLong());
        owner.error("invalid_position", "{\"type\":\"seek\",\"positionMs\":-1}");

        // next: a new current entry (full state); previous goes back
        owner.ok("{\"type\":\"next\"}");
        state = owner.await("state");
        assertEquals(v + 1, version(state));
        v++;
        assertEquals(song2, state.getAsJsonObject("current").getAsJsonObject("item").get("id").getAsString());
        JsonArray history = state.getAsJsonArray("history");
        assertEquals(1, history.size());
        assertEquals(entry1, history.get(0).getAsJsonObject().get("entryId").getAsString());
        assertEquals("skipped", history.get(0).getAsJsonObject().get("state").getAsString());

        // a second socket: the owner sees the presence change; mode switches count too
        Client listener = connect(bob, null);
        listener.next("identity");
        listener.ok("{\"type\":\"join\",\"room\":\"" + roomId + "\",\"mode\":\"remote\"}");
        JsonObject bobState = listener.await("state");
        assertFalse(bobState.getAsJsonObject("room").getAsJsonObject("you").has("role"));
        assertFalse(bobState.getAsJsonObject("room").getAsJsonObject("you").get("canSkip").getAsBoolean());
        presence = owner.await("presence");
        assertEquals(v + 1, version(presence));
        v++;
        assertEquals(1, presence.getAsJsonObject("presence").get("remote").getAsInt());
        listener.ok("{\"type\":\"mode\",\"mode\":\"listen\"}");
        presence = owner.await("presence");
        assertEquals(v + 1, version(presence));
        v++;
        assertEquals(2, presence.getAsJsonObject("presence").get("listening").getAsInt());
        listener.error("forbidden", "{\"type\":\"next\"}");
        listener.error("invalid_mode", "{\"type\":\"mode\",\"mode\":\"loud\"}");

        // sync: the full state again, same version
        owner.ok("{\"type\":\"sync\"}");
        assertEquals(v, version(owner.await("state")));

        // leave: the others see the presence drop
        listener.ok("{\"type\":\"leave\"}");
        presence = owner.await("presence");
        assertEquals(v + 1, version(presence));
        assertEquals(1, presence.getAsJsonObject("presence").get("listening").getAsInt());
        listener.error("not_joined", "{\"type\":\"play\"}");
        listener.quiet("presence");

        // closing a socket leaves too
        listener.ok("{\"type\":\"join\",\"room\":\"party\"}");
        owner.await("presence");
        listener.close();
        presence = owner.await("presence");
        assertEquals(1, presence.getAsJsonObject("presence").get("listening").getAsInt());
    }

    @Test
    void anonymousSocketsBecomeGuestsWithATicket() throws Exception {
        room("votes", "public", "vote");
        String song = item("Song");
        String other = item("Other");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"votes\"}");
        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song + "\"}");
        String waiting = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + other + "\"}").get("entryId").getAsString();

        Client anon = connect(null, null);
        anon.ok("{\"type\":\"join\",\"room\":\"votes\",\"mode\":\"remote\"}");
        JsonObject state = anon.await("state");
        JsonObject you = state.getAsJsonObject("room").getAsJsonObject("you");
        assertFalse(you.get("identified").getAsBoolean());
        assertEquals(1, state.getAsJsonObject("presence").get("anonymous").getAsInt());
        anon.error("identity_required", "{\"type\":\"vote\",\"entryId\":\"" + waiting + "\",\"value\":1}");
        anon.error("identity_required", "{\"type\":\"voteSkip\"}");
        anon.error("identity_required", "{\"type\":\"queue.add\",\"itemId\":\"" + other + "\"}");

        // POST /api/me/guest gives a ticket; the socket redeems it
        HttpResponse<String> created = call(null, "POST", "/api/me/guest", null);
        assertEquals(201, created.statusCode(), created.body());
        String ticket = json(created).get("identityTicket").getAsString();
        String guestId = json(created).get("id").getAsString();
        anon.error("invalid_ticket", "{\"type\":\"identity\",\"ticket\":\"" + "x".repeat(43) + "\"}");
        anon.ok("{\"type\":\"identity\",\"ticket\":\"" + ticket + "\"}");
        JsonObject identity = anon.await(m -> m.get("type").getAsString().equals("identity") && m.has("actor"));
        assertEquals(guestId, identity.getAsJsonObject("actor").get("id").getAsString());
        assertTrue(identity.getAsJsonObject("actor").get("isGuest").getAsBoolean());
        state = anon.await("state");
        you = state.getAsJsonObject("room").getAsJsonObject("you");
        assertTrue(you.get("identified").getAsBoolean());
        assertTrue(you.get("canVote").getAsBoolean());
        assertEquals(1, state.getAsJsonObject("presence").get("remote").getAsInt());
        assertEquals(0, state.getAsJsonObject("presence").get("anonymous").getAsInt());
        // a ticket works once
        anon.error("invalid_ticket", "{\"type\":\"identity\",\"ticket\":\"" + ticket + "\"}");

        // now the guest votes; its own state shows yourVote, the owner's doesn't
        assertEquals(1, anon.ok("{\"type\":\"vote\",\"entryId\":\"" + waiting + "\",\"value\":1}")
                .get("votes").getAsInt());
        JsonObject mine = anon.await(m -> m.get("type").getAsString().equals("state")
                && !m.getAsJsonArray("queue").isEmpty() && m.getAsJsonArray("queue").get(0).getAsJsonObject().get("votes").getAsInt() == 1);
        assertEquals(1, mine.getAsJsonArray("queue").get(0).getAsJsonObject().get("yourVote").getAsInt());
        JsonObject theirs = owner.await(m -> m.get("type").getAsString().equals("state")
                && !m.getAsJsonArray("queue").isEmpty() && m.getAsJsonArray("queue").get(0).getAsJsonObject().get("votes").getAsInt() == 1);
        assertEquals(0, theirs.getAsJsonArray("queue").get(0).getAsJsonObject().get("yourVote").getAsInt());
        anon.error("invalid_vote", "{\"type\":\"vote\",\"entryId\":\"" + waiting + "\",\"value\":2}");
        JsonObject skip = anon.ok("{\"type\":\"voteSkip\"}");
        assertEquals(1, skip.get("votes").getAsInt());

        // banning the guest from the room kicks its socket
        assertEquals(201, call(alice, "PUT", "/api/rooms/votes/guest-bans/" + guestId, null).statusCode());
        assertEquals("banned_from_room", anon.await("kicked").get("reason").getAsString());
        anon.error("not_joined", "{\"type\":\"sync\"}");
        anon.error("banned_from_room", "{\"type\":\"join\",\"room\":\"votes\"}");
    }

    /** A ready audio item of alice's with {@code audio.m4a} on disk. */
    private String servedItem(String title) throws Exception {
        String id = item(title);
        Path folder = dir.resolve("data").resolve("media").resolve(id);
        java.nio.file.Files.createDirectories(folder);
        java.nio.file.Files.write(folder.resolve("audio.m4a"), new byte[100]);
        return id;
    }

    private int media(String cookie, String itemId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/media/"
                + itemId + "/audio.m4a"));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void roomSettings(String room, String settingsJson) throws Exception {
        HttpResponse<String> r = call(alice, "PATCH", "/api/rooms/" + room, "{\"settings\":" + settingsJson + "}");
        assertEquals(200, r.statusCode(), r.body());
    }

    /**
     * M3 acceptance: a guest votes and requests as the server AND room policy allow, and gets
     * {@code 403} for media of an item that is neither current nor next anywhere while
     * {@code guests.download} is off (the live current/next set of the room executors).
     */
    @Test
    void guestsVoteAndRequestByPolicyAndOnlyStreamCurrentAndNext() throws Exception {
        room("policy", "public", "vote");
        String song1 = servedItem("One");
        String song2 = servedItem("Two");
        String song3 = servedItem("Three");
        String elsewhere = servedItem("Not queued");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"policy\"}");
        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song1 + "\"}");
        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song2 + "\"}");
        String entry3 = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song3 + "\"}").get("entryId").getAsString();

        // a cookie guest (POST /api/me/guest), on the socket and over HTTP
        HttpResponse<String> created = call(null, "POST", "/api/me/guest", null);
        assertEquals(201, created.statusCode(), created.body());
        String guestId = json(created).get("id").getAsString();
        String cookie = created.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith("gid="))
                .map(v -> v.substring(0, v.indexOf(';'))).findFirst().orElseThrow();
        Client guest = connect(null, cookie);
        assertEquals(guestId, guest.next("identity").getAsJsonObject("actor").get("id").getAsString());
        guest.ok("{\"type\":\"join\",\"room\":\"policy\"}");
        JsonObject you = guest.await("state").getAsJsonObject("room").getAsJsonObject("you");
        assertTrue(you.get("canVote").getAsBoolean());
        assertTrue(you.get("canRequest").getAsBoolean());
        assertFalse(you.get("canSkip").getAsBoolean());

        // --- media: current and next only, while guests.download is off
        assertFalse(settings.guestsDownload());
        assertEquals(200, media(cookie, song1), "current");
        assertEquals(200, media(cookie, song2), "next");
        assertEquals(403, media(cookie, song3), "queued but not next");
        assertEquals(403, media(cookie, elsewhere), "not in any room");
        assertEquals(403, media(null, song3), "anonymous too");
        assertEquals(200, media(null, song1));
        assertEquals(200, call(alice, "GET", "/media/" + elsewhere + "/audio.m4a", null).statusCode(), "users always");

        // --- defaults (server and room on): the guest votes, vote-skips and requests
        assertEquals(1, guest.ok("{\"type\":\"vote\",\"entryId\":\"" + entry3 + "\",\"value\":1}").get("votes")
                .getAsInt());
        // 1 of 2 present reaches the default threshold (0.5): song1 is skipped, the voted-up song3 plays
        assertEquals(1, guest.ok("{\"type\":\"voteSkip\"}").get("votes").getAsInt());
        JsonObject skipped = owner.await(m -> m.get("type").getAsString().equals("state") && m.has("current")
                && m.getAsJsonObject("current").getAsJsonObject("item").get("id").getAsString().equals(song3));
        String entry2 = skipped.getAsJsonArray("queue").get(0).getAsJsonObject().get("entryId").getAsString();
        assertEquals(song2, skipped.getAsJsonArray("queue").get(0).getAsJsonObject().getAsJsonObject("item")
                .get("id").getAsString());
        assertEquals(403, media(cookie, song1), "skipped: neither current nor next");
        assertEquals(200, media(cookie, song3), "now current");
        assertEquals(200, media(cookie, song2), "still next");
        guest.error("forbidden", "{\"type\":\"next\"}");
        guest.error("forbidden", "{\"type\":\"queue.addPlaylist\",\"playlistId\":\"" + UuidV7.newId() + "\"}");
        String req1 = servedItem("Guest pick 1");
        JsonObject added = guest.ok("{\"type\":\"queue.add\",\"itemId\":\"" + req1 + "\"}");
        assertEquals("queued", added.get("state").getAsString());
        JsonObject req = owner.await("request.new").getAsJsonObject("request");
        assertEquals(guestId, req.getAsJsonObject("by").get("id").getAsString());
        assertTrue(req.getAsJsonObject("by").get("isGuest").getAsBoolean());
        HttpResponse<String> search = callCookie(cookie, "/api/rooms/policy/search?q=Guest");
        assertEquals(200, search.statusCode(), search.body());
        // requestsPerActor (3): queued entries count
        guest.ok("{\"type\":\"queue.add\",\"itemId\":\"" + servedItem("Guest pick 2") + "\"}");
        guest.ok("{\"type\":\"queue.add\",\"itemId\":\"" + servedItem("Guest pick 3") + "\"}");
        guest.error("request_limit", "{\"type\":\"queue.add\",\"itemId\":\"" + servedItem("Guest pick 4") + "\"}");
        roomSettings("policy", "{\"requestsPerActor\":10}");
        // uploads and links are off by default for guests
        guest.error("guest_youtube_disabled",
                "{\"type\":\"queue.add\",\"youtubeUrl\":\"https://youtu.be/jNQXAC9IVRw\"}");

        // --- room switches: off in the room → refused, though the server allows it
        roomSettings("policy", "{\"guests\":{\"canVote\":false}}");
        guest.error("cannot_vote", "{\"type\":\"vote\",\"entryId\":\"" + entry2 + "\",\"value\":1}");
        guest.error("cannot_vote", "{\"type\":\"voteSkip\"}");
        guest.ok("{\"type\":\"queue.add\",\"itemId\":\"" + servedItem("Still requests") + "\"}");
        roomSettings("policy", "{\"guests\":{\"canVote\":null,\"canRequestLibrary\":false}}");
        // no request path left at all: no request rights (cannot_request)
        guest.error("cannot_request", "{\"type\":\"queue.add\",\"itemId\":\"" + servedItem("Refused") + "\"}");
        assertEquals(403, callCookie(cookie, "/api/rooms/policy/search?q=Guest").statusCode());
        guest.ok("{\"type\":\"vote\",\"entryId\":\"" + entry2 + "\",\"value\":0}");
        roomSettings("policy", "{\"guests\":null}");

        // --- server switches: off on the server → refused, though the room allows it
        settings.set(Settings.GUESTS_CAN_VOTE, false);
        guest.error("cannot_vote", "{\"type\":\"vote\",\"entryId\":\"" + entry2 + "\",\"value\":1}");
        settings.set(Settings.GUESTS_CAN_VOTE, true);
        settings.set(Settings.GUESTS_CAN_REQUEST_LIBRARY, false);
        guest.error("cannot_request", "{\"type\":\"queue.add\",\"itemId\":\"" + servedItem("Refused 2") + "\"}");
        settings.set(Settings.GUESTS_CAN_REQUEST_LIBRARY, true);
        // guests off server-wide: the guest identity no longer counts at all
        settings.set(Settings.GUESTS_ENABLED, false);
        guest.error("session_ended", "{\"type\":\"vote\",\"entryId\":\"" + entry2 + "\",\"value\":1}");
        assertEquals(403, media(cookie, elsewhere));
        settings.set(Settings.GUESTS_ENABLED, true);
        guest = connect(null, cookie);
        assertEquals(guestId, guest.next("identity").getAsJsonObject("actor").get("id").getAsString());
        guest.ok("{\"type\":\"join\",\"room\":\"policy\"}");
        assertEquals(1, guest.ok("{\"type\":\"vote\",\"entryId\":\"" + entry2 + "\",\"value\":1}").get("votes")
                .getAsInt());

        // --- control modes: owner mode makes a guest's request a suggestion and has no votes
        assertEquals(200, call(alice, "PATCH", "/api/rooms/policy", "{\"controlMode\":\"owner\"}").statusCode());
        assertEquals("suggested", guest.ok("{\"type\":\"queue.add\",\"itemId\":\"" + servedItem("Suggestion")
                + "\"}").get("state").getAsString());
        guest.error("wrong_control_mode", "{\"type\":\"vote\",\"entryId\":\"" + entry2 + "\",\"value\":1}");
        guest.error("forbidden", "{\"type\":\"pause\"}");
        // open mode: request rights control playback too
        assertEquals(200, call(alice, "PATCH", "/api/rooms/policy", "{\"controlMode\":\"open\"}").statusCode());
        guest.ok("{\"type\":\"pause\"}");
        guest.ok("{\"type\":\"play\"}");
        assertEquals(200, call(alice, "PATCH", "/api/rooms/policy", "{\"controlMode\":\"vote\"}").statusCode());

        // --- the media set follows the room: after next, the old current is refused
        owner.ok("{\"type\":\"next\"}");
        JsonObject state = owner.await(m -> m.get("type").getAsString().equals("state") && m.has("current")
                && m.getAsJsonObject("current").getAsJsonObject("item").get("id").getAsString().equals(song2));
        assertEquals(req1, state.getAsJsonArray("queue").get(0).getAsJsonObject().getAsJsonObject("item")
                .get("id").getAsString(), "the guest's first request is next");
        assertEquals(403, media(cookie, song3), "played: neither current nor next");
        assertEquals(200, media(cookie, song2));
        assertEquals(200, media(cookie, req1));
        assertEquals(403, media(cookie, song1));

        // guests.download on: every item
        settings.set(Settings.GUESTS_DOWNLOAD, true);
        assertEquals(200, media(cookie, song1));
        assertEquals(200, media(cookie, elsewhere));
        assertEquals(200, media(null, elsewhere));
    }

    private HttpResponse<String> callCookie(String cookie, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestsGoToTheOwnerAndModeratorsOnly() throws Exception {
        String roomId = room("requests", "public", "vote");
        String song = item("Requested");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"requests\"}");
        Client member = connect(bob, null);
        member.ok("{\"type\":\"join\",\"room\":\"" + roomId + "\"}");
        JsonObject added = member.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song + "\"}");
        JsonObject req = owner.await("request.new").getAsJsonObject("request");
        assertEquals(added.get("entryId").getAsString(), req.get("entryId").getAsString());
        assertEquals("library", req.get("via").getAsString());
        assertEquals(bobId, req.getAsJsonObject("by").get("id").getAsString());
        assertEquals("Requested", req.getAsJsonObject("item").get("title").getAsString());
        member.await("state");
        member.quiet("request.new"); // not for a plain member

        // the owner's own adds are no requests
        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + item("Own") + "\"}");
        owner.quiet("request.new");
    }

    @Test
    void roleChangesAndBansReachTheSockets() throws Exception {
        String roomId = room("roles", "public", "owner");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"roles\"}");
        Client member = connect(bob, null);
        member.ok("{\"type\":\"join\",\"room\":\"roles\"}");
        JsonObject you = member.await("state").getAsJsonObject("room").getAsJsonObject("you");
        assertFalse(you.has("role"));
        assertFalse(you.get("canSkip").getAsBoolean());

        // promoted: a new state with the new rights
        assertEquals(201, call(alice, "PUT", "/api/rooms/roles/members/" + bobId, "{\"role\":\"moderator\"}")
                .statusCode());
        you = member.await("state").getAsJsonObject("room").getAsJsonObject("you");
        assertEquals("moderator", you.get("role").getAsString());
        assertTrue(you.get("canSkip").getAsBoolean());
        member.ok("{\"type\":\"play\"}");

        // banned: kicked, and out of the presence
        assertEquals(200, call(alice, "PUT", "/api/rooms/roles/members/" + bobId, "{\"role\":\"banned\"}")
                .statusCode());
        assertEquals("banned_from_room", member.await("kicked").get("reason").getAsString());
        JsonObject presence = owner.await(m -> m.get("type").getAsString().equals("presence")
                && m.getAsJsonObject("presence").get("listening").getAsInt() == 1);
        assertNotNull(presence);
        member.error("banned_from_room", "{\"type\":\"join\",\"room\":\"" + roomId + "\"}");

        // the room goes private: anonymous sockets lose it
        Client anon = connect(null, null);
        anon.ok("{\"type\":\"join\",\"room\":\"roles\"}");
        assertEquals(200, call(alice, "PATCH", "/api/rooms/roles", "{\"visibility\":\"private\"}").statusCode());
        assertEquals("room_not_found", anon.await("kicked").get("reason").getAsString());

        // deleted: everyone is kicked
        assertEquals(204, call(alice, "DELETE", "/api/rooms/roles", null).statusCode());
        assertEquals("room_deleted", owner.await("kicked").get("reason").getAsString());
        owner.error("not_joined", "{\"type\":\"play\"}");
    }

    /**
     * The room's active outputs are in every {@code state} with {@code estimatedDelayMs} (the room
     * setting, defaults 5000 / 2000 / 8000); a change arrives as {@code output.status}; the RTMP
     * {@code detail} only reaches the owner and moderators.
     */
    @Test
    void outputsAreInTheStateAndChangesArriveAsOutputStatus() throws Exception {
        String roomId = room("outs", "public", "vote");
        java.util.concurrent.atomic.AtomicReference<List<RoomOutputs.Status>> active =
                new java.util.concurrent.atomic.AtomicReference<>(List.of(
                        new RoomOutputs.Status("rtmp", "retrying", "Connection refused"),
                        new RoomOutputs.Status("hls", "ready", null)));
        wiring.socket().outputs(r -> r.equals(roomId) ? active.get() : List.of());
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"outs\"}");
        JsonArray outputs = owner.await("state").getAsJsonArray("outputs");
        assertEquals(2, outputs.size(), outputs.toString());
        JsonObject rtmp = outputs.get(0).getAsJsonObject();
        assertEquals("rtmp", rtmp.get("kind").getAsString());
        assertEquals("retrying", rtmp.get("status").getAsString());
        assertEquals(5000, rtmp.get("estimatedDelayMs").getAsInt());
        assertEquals("Connection refused", rtmp.get("detail").getAsString());
        JsonObject hls = outputs.get(1).getAsJsonObject();
        assertEquals("ready", hls.get("status").getAsString());
        assertEquals(8000, hls.get("estimatedDelayMs").getAsInt());
        assertFalse(hls.has("detail"));

        Client member = connect(bob, null);
        member.ok("{\"type\":\"join\",\"room\":\"outs\"}");
        JsonArray seenByMember = member.await("state").getAsJsonArray("outputs");
        assertEquals("retrying", seenByMember.get(0).getAsJsonObject().get("status").getAsString());
        assertFalse(seenByMember.get(0).getAsJsonObject().has("detail"), "detail is for moderators");
        Client anon = connect(null, null);
        anon.ok("{\"type\":\"join\",\"room\":\"outs\"}");
        assertFalse(anon.await("state").getAsJsonArray("outputs").get(0).getAsJsonObject().has("detail"));

        // the owner's delay setting reaches the outputs
        roomSettings("outs", "{\"estimatedDelayMs\":{\"hls\":9500}}");
        JsonObject state = member.await(m -> m.get("type").getAsString().equals("state")
                && m.getAsJsonArray("outputs").size() == 2
                && m.getAsJsonArray("outputs").get(1).getAsJsonObject().get("estimatedDelayMs").getAsInt() == 9500);
        assertNotNull(state);

        // a change: output.status to everyone, without a version, detail for moderators only
        active.set(List.of(new RoomOutputs.Status("rtmp", "connected", "ok"), new RoomOutputs.Status("hls", "ready", null)));
        wiring.socket().outputStatus(roomId, new RoomOutputs.Status("rtmp", "connected", "ok"));
        JsonObject toOwner = owner.await("output.status");
        assertEquals("rtmp", toOwner.get("kind").getAsString());
        assertEquals("connected", toOwner.get("status").getAsString());
        assertEquals("ok", toOwner.get("detail").getAsString());
        assertEquals(5000, toOwner.get("estimatedDelayMs").getAsInt());
        assertFalse(toOwner.has("version"));
        JsonObject toMember = member.await("output.status");
        assertEquals("connected", toMember.get("status").getAsString());
        assertFalse(toMember.has("detail"));
        assertEquals("connected", anon.await("output.status").get("status").getAsString());
        // off carries no delay; other rooms hear nothing
        wiring.socket().outputStatus(roomId, new RoomOutputs.Status("hls", "off", null));
        JsonObject off = member.await("output.status");
        assertEquals("off", off.get("status").getAsString());
        assertFalse(off.has("estimatedDelayMs"));
        wiring.socket().outputStatus("no-such-room", new RoomOutputs.Status("ts", "ready", null));
        member.quiet("output.status");

        // a moderator gets the detail without the ingest URL or the key's last 4 (the owner's only)
        String detail = "Error opening output rtmp://***@ingest.example/live/…WXYZ: Connection refused";
        active.set(List.of(new RoomOutputs.Status("rtmp", "retrying", detail)));
        assertEquals(201, call(alice, "PUT", "/api/rooms/outs/members/" + bobId, "{\"role\":\"moderator\"}")
                .statusCode());
        JsonObject modState = member.await(m -> m.get("type").getAsString().equals("state")
                && m.getAsJsonArray("outputs").size() == 1
                && m.getAsJsonArray("outputs").get(0).getAsJsonObject().has("detail"));
        assertEquals("Error opening output (ingest URL): Connection refused",
                modState.getAsJsonArray("outputs").get(0).getAsJsonObject().get("detail").getAsString());
        wiring.socket().outputStatus(roomId, new RoomOutputs.Status("rtmp", "retrying", detail));
        assertEquals(detail, owner.await(m -> m.get("type").getAsString().equals("output.status")
                && m.has("detail") && m.get("detail").getAsString().equals(detail)).get("detail").getAsString());
        assertEquals("Error opening output (ingest URL): Connection refused",
                member.await("output.status").get("detail").getAsString());
    }

    @Test
    void aRevokedSessionEndsTheSocket() throws Exception {
        room("revoke", "public", "owner");
        Sessions.Created session = sessions.create(bobId, Actor.Via.BEARER, "test", null);
        Client c = connect(session.token(), null);
        c.ok("{\"type\":\"join\",\"room\":\"revoke\"}");
        sessions.revoke(bobId, session.sessionId());
        c.error("session_ended", "{\"type\":\"play\"}");
        assertTrue(c.seen.stream().anyMatch(m -> m.get("type").getAsString().equals("kicked")
                && m.get("reason").getAsString().equals("session_ended")), c.seen.toString());
    }

    @Test
    void bigQueuesGetDeltas() throws Exception {
        String roomId = room("big", "public", "owner");
        String song = item("Many");
        int n = RoomBroadcaster.FULL_STATE_MAX + 5;
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = UuidV7.newId();
            entries.add(id);
            sql("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position)"
                    + " VALUES (?, ?, ?, ?, ?, 'queued', ?)", id, roomId, song, aliceId, 1000 + i, i + 1);
        }
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"big\"}");
        JsonObject state = owner.await("state");
        assertEquals(n, state.getAsJsonArray("queue").size());
        long v = version(owner.await("presence"));

        // move the last entry to the front: a queue delta with the new order
        owner.ok("{\"type\":\"queue.move\",\"entryId\":\"" + entries.get(n - 1) + "\",\"beforeEntryId\":\""
                + entries.get(0) + "\"}");
        JsonObject delta = owner.await("queue");
        assertEquals(v + 1, version(delta));
        JsonArray order = delta.getAsJsonArray("order");
        assertEquals(n, order.size());
        assertEquals(entries.get(n - 1), order.get(0).getAsString());
        JsonArray upsert = delta.getAsJsonArray("upsert");
        assertEquals(1, upsert.size());
        assertEquals(entries.get(n - 1), upsert.get(0).getAsJsonObject().get("entryId").getAsString());
        assertEquals(0, delta.getAsJsonArray("removed").size());

        // remove one
        owner.ok("{\"type\":\"queue.remove\",\"entryId\":\"" + entries.get(3) + "\"}");
        delta = owner.await("queue");
        assertEquals(v + 2, version(delta));
        assertEquals(List.of(entries.get(3)), strings(delta.getAsJsonArray("removed")));
        assertEquals(0, delta.getAsJsonArray("upsert").size());
        assertEquals(n - 1, delta.getAsJsonArray("order").size());
    }

    @Test
    void anIdleRoomStartsAnUploadWhenItsIngestFinishes() throws Exception {
        String roomId = room("idle", "public", "owner");
        String[] upload = ingesting("Fresh Upload");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"idle\",\"mode\":\"listen\"}");
        owner.await("state");
        owner.await("presence");
        String entry = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + upload[0] + "\"}")
                .get("entryId").getAsString();
        JsonObject state = owner.await("state");
        long v = version(state);
        assertEquals("idle", state.getAsJsonObject("timeline").get("state").getAsString());
        assertEquals("processing", queued(state, entry).get("status").getAsString());

        // the ingest finishes: the room hears about it and starts the entry, in one change
        finishIngest(upload);
        state = owner.await("state");
        assertEquals(v + 1, version(state), state.toString());
        assertEquals("playing", state.getAsJsonObject("timeline").get("state").getAsString());
        assertEquals(entry, state.getAsJsonObject("timeline").get("entryId").getAsString());
        JsonObject current = state.getAsJsonObject("current");
        assertEquals(entry, current.get("entryId").getAsString());
        assertEquals("ready", current.get("status").getAsString());
        assertEquals(0, state.getAsJsonArray("queue").size());
        assertTrue(wiring.registry().playingItems().isCurrentOrNext(upload[0]));
        // the advance task runs for the new entry's duration
        long delay = wiring.registry().executor(roomId).advanceTaskDelayMs();
        assertTrue(delay > 80_000 && delay <= 90_000, "advance in " + delay);
        owner.quiet("state");
    }

    @Test
    void aFinishedIngestTurnsTheEntryReadyWhileSomethingElsePlays() throws Exception {
        room("busy", "public", "owner");
        String song = item("Playing Song");
        String[] upload = ingesting("Fresh Upload");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"busy\",\"mode\":\"listen\"}");
        owner.await("state");
        owner.await("presence");
        String playing = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song + "\"}")
                .get("entryId").getAsString();
        owner.await("state");
        String entry = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + upload[0] + "\"}")
                .get("entryId").getAsString();
        JsonObject state = owner.await("state");
        long v = version(state);
        assertEquals("processing", queued(state, entry).get("status").getAsString());
        JsonObject timeline = state.getAsJsonObject("timeline");

        clock.advance(10_000);
        finishIngest(upload);
        state = owner.await("state");
        assertEquals(v + 1, version(state), state.toString());
        assertEquals("ready", queued(state, entry).get("status").getAsString());
        // the timeline wasn't touched: same entry, same anchor
        assertEquals(timeline, state.getAsJsonObject("timeline"));
        assertEquals(playing, state.getAsJsonObject("current").get("entryId").getAsString());
        // the ready entry is now the next one guests may fetch
        assertTrue(wiring.registry().playingItems().isCurrentOrNext(upload[0]));
        owner.quiet("state");
    }

    @Test
    void aFailedIngestShowsAsFailedAndIsSkipped() throws Exception {
        room("broken", "public", "owner");
        String[] upload = ingesting("Broken Upload");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"broken\",\"mode\":\"listen\"}");
        owner.await("state");
        owner.await("presence");
        String entry = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + upload[0] + "\"}")
                .get("entryId").getAsString();
        long v = version(owner.await("state"));

        assertTrue(jobs.fail(upload[1], "ffmpeg could not read the file", "failed"));
        JsonObject state = owner.await("state");
        assertEquals(v + 1, version(state), state.toString());
        assertEquals("failed", queued(state, entry).get("status").getAsString());
        assertEquals("idle", state.getAsJsonObject("timeline").get("state").getAsString());
        v = version(state);

        // the next item plays; the failed entry stays (shown as failed) and is skipped over
        String song = item("Good Song");
        String good = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song + "\"}").get("entryId").getAsString();
        state = owner.await("state");
        assertEquals(v + 1, version(state));
        assertEquals(good, state.getAsJsonObject("current").get("entryId").getAsString());
        assertEquals("failed", queued(state, entry).get("status").getAsString());
    }

    @Test
    void anIdleRoomThatWasNotLoadedStartsWhenTheIngestFinishes() throws Exception {
        String roomId = room("cold", "public", "owner");
        String[] upload = ingesting("Fresh Upload");
        String entry = UuidV7.newId();
        sql("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position)"
                + " VALUES (?, ?, ?, ?, 1, 'queued', 1)", entry, roomId, upload[0], aliceId);
        assertEquals(null, wiring.registry().loaded(roomId));
        finishIngest(upload);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Timeline t = null;
        while (System.nanoTime() < deadline) {
            if (wiring.registry().loaded(roomId) != null) {
                t = wiring.registry().call(roomId, tx -> tx.state().timeline());
                if (t.state() == PlaybackState.PLAYING) {
                    break;
                }
            }
            Thread.sleep(20);
        }
        assertNotNull(t);
        assertEquals(PlaybackState.PLAYING, t.state());
        assertEquals(entry, t.entryId());
    }

    @Test
    void atStartIdleRoomsWithAReadyQueuedEntryStartIt() throws Exception {
        // e.g. an ingest that finished before the rooms listened (or before this fix)
        String roomId = room("stuck", "public", "owner");
        String song = item("Stuck Song");
        String entry = UuidV7.newId();
        sql("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position)"
                + " VALUES (?, ?, ?, ?, 1, 'queued', 1)", entry, roomId, song, aliceId);
        room("empty", "public", "owner");
        assertEquals(1, wiring.registry().startIdleWithReadyEntries());
        Timeline t = wiring.registry().call(roomId, tx -> tx.state().timeline());
        assertEquals(PlaybackState.PLAYING, t.state());
        assertEquals(entry, t.entryId());
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            out.add(e.getAsString());
        }
        return out;
    }

    @Test
    void aYoutubeLookupSendsItsStagesToTheRequestingSocketOnly() throws Exception {
        room("tube", "public", "owner");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"tube\",\"mode\":\"listen\"}");
        owner.await("state");
        Client other = connect(bob, null);
        other.ok("{\"type\":\"join\",\"room\":\"tube\",\"mode\":\"listen\"}");
        other.await("state");

        JsonObject ack = owner.ok("{\"type\":\"queue.add\",\"youtubeUrl\":\"https://youtu.be/jNQXAC9IVRw\"}");
        int rid = ack.get("rid").getAsInt();
        // every stage once, in order, before the ack; unknown and debug lines left out
        List<String> stages = new ArrayList<>();
        for (JsonObject m : owner.seen) {
            if (m.get("type").getAsString().equals("request.progress")) {
                assertEquals(rid, m.get("rid").getAsInt(), m.toString());
                stages.add(m.get("stage").getAsString() + "|" + m.get("text").getAsString());
            }
        }
        assertEquals(List.of("contacting|Contacting YouTube…", "page|Reading the video page…",
                "details|Getting the video details…", "formats|Checking formats…"), stages);
        // nobody else gets them
        other.await("state");
        other.quiet("request.progress");
        for (JsonObject m : other.seen) {
            assertFalse(m.get("type").getAsString().equals("request.progress"), m.toString());
        }

        // the same video again: no lookup, so no stages
        owner.seen.clear();
        owner.ok("{\"type\":\"queue.add\",\"youtubeUrl\":\"https://www.youtube.com/watch?v=jNQXAC9IVRw\"}");
        for (JsonObject m : owner.seen) {
            assertFalse(m.get("type").getAsString().equals("request.progress"), m.toString());
        }
    }

    @Test
    void entriesWhoseItemIsNotReadyCarryTheirIngestProgress() throws Exception {
        java.util.Map<String, io.siggi.togethertunes.ingest.IngestQueue.LiveStep> live =
                new java.util.concurrent.ConcurrentHashMap<>();
        io.siggi.togethertunes.ingest.JobList list = new io.siggi.togethertunes.ingest.JobList(db, jobs, clock);
        list.steps(jobId -> java.util.Optional.ofNullable(live.get(jobId)));
        wiring.socket().ingestProgress(io.siggi.togethertunes.ingest.IngestProgress.from(list), 200);

        room("busy", "public", "owner");
        String song = item("Playing Song");
        String[] running = ingesting("Encoding Upload");
        String waitingId = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at) VALUES (?, 'audio', 'Waiting Upload', 'upload', ?, 1, 'pending', '720p30', 1)",
                waitingId, aliceId);
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'upload')", aliceId, waitingId);
        jobs.enqueue(waitingId, IngestJob.Kind.UPLOAD);

        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"busy\",\"mode\":\"listen\"}");
        owner.await("state");
        Client other = connect(bob, null);
        other.ok("{\"type\":\"join\",\"room\":\"busy\",\"mode\":\"listen\"}");
        other.await("state");
        // a room whose entries are all ready gets no ingest messages
        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + song + "\"}");
        owner.quiet("ingest");

        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + running[0] + "\"}");
        owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + waitingId + "\"}");
        JsonObject items = owner.await(m -> m.get("type").getAsString().equals("ingest")
                && m.getAsJsonObject("items").size() == 2).getAsJsonObject("items");
        assertEquals("{\"state\":\"running\",\"kind\":\"upload\"}", items.get(running[0]).toString());
        assertEquals("{\"state\":\"queued\",\"kind\":\"upload\",\"position\":1}", items.get(waitingId).toString());
        // everyone in the room gets it
        other.await(m -> m.get("type").getAsString().equals("ingest") && m.getAsJsonObject("items").size() == 2);

        // the step and its progress, rounded to whole percent
        live.put(running[1], new io.siggi.togethertunes.ingest.IngestQueue.LiveStep("encode", 0.3049));
        items = owner.await(m -> m.get("type").getAsString().equals("ingest")
                && m.getAsJsonObject("items").getAsJsonObject(running[0]).has("step")).getAsJsonObject("items");
        assertEquals("{\"state\":\"running\",\"kind\":\"upload\",\"step\":\"encode\",\"progress\":0.3}",
                items.get(running[0]).toString());

        // throttled: progress changing every 5 ms for 1 s gives at most one message per 200 ms
        owner.quiet("none");
        int before = count(owner, "ingest");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        for (int i = 0; System.nanoTime() < end; i++) {
            live.put(running[1], new io.siggi.togethertunes.ingest.IngestQueue.LiveStep("encode", (i % 100) / 100.0));
            Thread.sleep(5);
        }
        owner.quiet("none");
        int sent = count(owner, "ingest") - before;
        assertTrue(sent >= 2 && sent <= 7, sent + " ingest messages in 1 s");

        // nothing changes: nothing sent
        owner.quiet("ingest");

        // failed: a short reason
        assertTrue(jobs.fail(running[1], "ffmpeg could not read the file\nmore detail", "log"));
        items = owner.await(m -> m.get("type").getAsString().equals("ingest") && m.getAsJsonObject("items")
                .getAsJsonObject(running[0]).get("state").getAsString().equals("failed")).getAsJsonObject("items");
        assertEquals("ffmpeg could not read the file", items.getAsJsonObject(running[0]).get("error").getAsString());
        assertFalse(items.toString().contains("log"), items.toString());

        // once every waiting item is ready (or failed for good and removed), one last empty ingest
        String[] failedEntry = new String[1];
        owner.await(m -> {
            if (!m.get("type").getAsString().equals("state")) {
                return false;
            }
            for (JsonElement e : m.getAsJsonArray("queue")) {
                JsonObject o = e.getAsJsonObject();
                if (o.getAsJsonObject("item").get("id").getAsString().equals(running[0])
                        && o.get("status").getAsString().equals("failed")) {
                    failedEntry[0] = o.get("entryId").getAsString();
                    return true;
                }
            }
            return false;
        });
        owner.ok("{\"type\":\"queue.remove\",\"entryId\":\"" + failedEntry[0] + "\"}");
        IngestJob claimed = jobs.claimNext();
        assertEquals(waitingId, claimed.itemId());
        finishIngest(new String[] {waitingId, claimed.id()});
        owner.await(m -> m.get("type").getAsString().equals("ingest") && m.getAsJsonObject("items").size() == 0);
        owner.quiet("ingest");
    }

    private static int count(Client c, String type) {
        int n = 0;
        for (JsonObject m : c.seen) {
            if (m.get("type").getAsString().equals(type)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ retrying failed YouTube entries

    /** A YouTube item in alice's library whose download is queued (item {@code pending}). */
    private String youtubePending(String title) throws Exception {
        String id = UuidV7.newId();
        String hex = id.replace("-", "");
        sql("INSERT INTO items (id, kind, title, source, youtube_id, added_by_user_id, added_at, status,"
                + " output_profile, last_referenced_at) VALUES (?, 'video', ?, 'youtube', ?, ?, 1, 'pending', '720p30', 1)",
                id, title, hex.substring(hex.length() - 11), aliceId);
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'youtube')", aliceId, id);
        jobs.enqueue(id, IngestJob.Kind.YOUTUBE);
        return id;
    }

    /** The item's download starts and fails; returns the job. */
    private IngestJob failDownload(String itemId) throws Exception {
        IngestJob job = jobs.claimNext();
        assertEquals(itemId, job.itemId());
        assertTrue(jobs.fail(job.id(), "HTTP Error 429: Too Many Requests", "failed"));
        return job;
    }

    private static JsonObject entryIn(JsonObject state, String list, String entryId) {
        if (!state.has(list)) {
            return null;
        }
        for (JsonElement e : state.getAsJsonArray(list)) {
            if (e.getAsJsonObject().get("entryId").getAsString().equals(entryId)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    /** Each client's newest state version seen by {@link #entryWithStatus}. */
    private final Map<Client, Long> stateSeen = new java.util.HashMap<>();

    /**
     * The first state newer than the last one this returned for {@code c} in which the entry
     * (queued or suggested) has {@code status}.
     */
    private JsonObject entryWithStatus(Client c, String list, String entryId, String status)
            throws InterruptedException {
        long after = stateSeen.getOrDefault(c, -1L);
        JsonObject found = c.await(m -> {
            if (!m.get("type").getAsString().equals("state") || version(m) <= after) {
                return false;
            }
            JsonObject e = entryIn(m, list, entryId);
            return e != null && e.get("status").getAsString().equals(status);
        });
        stateSeen.put(c, version(found));
        return found;
    }

    private int retryAudits(String roomId) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT details_json FROM audit_log"
                    + " WHERE action = 'item.ingest_retried'")) {
                int n = 0;
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getString(1).contains(roomId)) {
                            n++;
                        }
                    }
                }
                return n;
            }
        });
    }

    @Test
    void aFailedYoutubeEntryIsRetriedFromTheRoomKeepsItsPlaceAndPlaysOnceReady() throws Exception {
        String roomId = room("retry", "public", "vote");
        String clip = youtubePending("Clip");
        Client owner = connect(alice, null);
        owner.ok("{\"type\":\"join\",\"room\":\"retry\",\"mode\":\"listen\"}");
        Client member = connect(bob, null);
        member.ok("{\"type\":\"join\",\"room\":\"retry\"}");
        String entry = member.ok("{\"type\":\"queue.add\",\"itemId\":\"" + clip + "\"}").get("entryId").getAsString();
        String now = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + item("Now") + "\"}").get("entryId").getAsString();
        String later = owner.ok("{\"type\":\"queue.add\",\"itemId\":\"" + item("Later") + "\"}")
                .get("entryId").getAsString();
        failDownload(clip);
        JsonObject state = entryWithStatus(owner, "queue", entry, "failed");
        assertEquals(now, state.getAsJsonObject("current").get("entryId").getAsString());
        assertEquals("youtube", entryIn(state, "queue", entry).getAsJsonObject("item").get("source").getAsString());

        // refused: a member who didn't request it, an anonymous visitor, a guest
        String carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        Client carol = connect(sessions.create(carolId, Actor.Via.BEARER, "test", null).token(), null);
        carol.ok("{\"type\":\"join\",\"room\":\"retry\"}");
        carol.error("forbidden", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        Client anonymous = connect(null, null);
        anonymous.ok("{\"type\":\"join\",\"room\":\"retry\"}");
        anonymous.error("identity_required", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        member.error("entry_not_found", "{\"type\":\"queue.retry\",\"entryId\":\"" + UuidV7.newId() + "\"}");
        // an upload can't be retried (its file isn't kept on failure)
        owner.error("not_youtube", "{\"type\":\"queue.retry\",\"entryId\":\"" + later + "\"}");

        // the requester retries: the entry stays where it was and waits again
        JsonObject ack = member.ok("{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        assertEquals(clip, ack.get("itemId").getAsString());
        assertEquals("pending", ack.get("status").getAsString());
        state = entryWithStatus(owner, "queue", entry, "processing");
        JsonArray queue = state.getAsJsonArray("queue");
        assertEquals(entry, queue.get(0).getAsJsonObject().get("entryId").getAsString(), state.toString());
        assertEquals(2, queue.size(), state.toString());
        assertEquals(later, queue.get(1).getAsJsonObject().get("entryId").getAsString());
        assertEquals(1, retryAudits(roomId));
        // a second click while it is queued, and while it runs
        member.error("ingest_in_progress", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");

        // it fails again at once: the next retry waits 30 s (per item, whoever asks)
        failDownload(clip);
        entryWithStatus(owner, "queue", entry, "failed");
        owner.error("retry_too_soon", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        clock.advance(30_000);

        // the owner retries
        owner.ok("{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        entryWithStatus(owner, "queue", entry, "processing");
        failDownload(clip);
        entryWithStatus(owner, "queue", entry, "failed");
        clock.advance(30_000);

        // a moderator retries
        assertEquals(201, call(alice, "PUT", "/api/rooms/retry/members/" + carolId, "{\"role\":\"moderator\"}")
                .statusCode());
        carol.ok("{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        failDownload(clip);
        entryWithStatus(owner, "queue", entry, "failed");
        clock.advance(30_000);
        assertEquals(3, retryAudits(roomId));

        // ytdlp.enabled off: nobody
        settings.set(Settings.YTDLP_ENABLED, false);
        owner.error("feature_disabled", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        // and the requester also lost the right to add YouTube links
        member.error("forbidden", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        settings.set(Settings.YTDLP_ENABLED, true);

        // retried once more, it finishes this time: still in its place, and it plays next
        member.ok("{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        IngestJob job = jobs.claimNext();
        finishIngest(new String[] {clip, job.id()});
        state = entryWithStatus(owner, "queue", entry, "ready");
        assertEquals(entry, state.getAsJsonArray("queue").get(0).getAsJsonObject().get("entryId").getAsString());
        owner.ok("{\"type\":\"next\"}");
        owner.await(m -> m.get("type").getAsString().equals("state") && m.has("current")
                && m.getAsJsonObject("current").get("entryId").getAsString().equals(entry));
    }

    @Test
    void aGuestRetriesItsOwnSuggestionWhileTheRoomLetsItAddYoutubeLinks() throws Exception {
        String roomId = room("guestretry", "public", "owner");
        String clip = youtubePending("Guest Clip");
        HttpResponse<String> created = call(null, "POST", "/api/me/guest", null);
        assertEquals(201, created.statusCode(), created.body());
        String cookie = created.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith("gid="))
                .map(v -> v.substring(0, v.indexOf(';'))).findFirst().orElseThrow();
        Client guest = connect(null, cookie);
        guest.next("identity");
        guest.ok("{\"type\":\"join\",\"room\":\"guestretry\"}");
        String entry = guest.ok("{\"type\":\"queue.add\",\"itemId\":\"" + clip + "\"}").get("entryId").getAsString();
        failDownload(clip);
        entryWithStatus(guest, "suggestions", entry, "failed");

        // guests may not add YouTube links (the default): no retry either
        guest.error("forbidden", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        settings.set(Settings.GUESTS_CAN_YOUTUBE, true);
        roomSettings("guestretry", "{\"guests\":{\"canYoutube\":false}}");
        guest.error("forbidden", "{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        roomSettings("guestretry", "{\"guests\":{\"canYoutube\":true}}");
        guest.ok("{\"type\":\"queue.retry\",\"entryId\":\"" + entry + "\"}");
        entryWithStatus(guest, "suggestions", entry, "processing");
        assertEquals(1, retryAudits(roomId));
    }
}
