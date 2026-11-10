package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.rooms.RoomOutputs;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OutputStatusFeed}: which outputs a room's page lists (active = row enabled AND server
 * switch; RTMP with its push state and last message, {@code .ts}/HLS {@code ready}) and the
 * {@code output.status} changes it sends (only what changed, {@code off} once when an output stops
 * being active), also after every reconcile of {@link StreamOutputControl}.
 */
class OutputStatusFeedTest {
    @TempDir
    Path dir;

    Database db;
    StreamOutputs rows;
    StreamOutputControl control;
    OutputStatusFeed feed;
    final TestClock clock = new TestClock(1_800_000_000_000L);
    final Map<String, Boolean> switches = new ConcurrentHashMap<>(Map.of("rtmp", true, "ts", true, "hls", true));
    final Map<String, RtmpPusher.Status> pushes = new ConcurrentHashMap<>();
    final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    String userId;
    String roomId;
    String otherRoomId;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Rooms rooms = new Rooms(db, new Permissions(db, settings), clock);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        rows = new StreamOutputs(db, new SecretCipher(key), new SecureRandom(), clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        userId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        Actor.User owner = new Actor.User(userId, "s", Actor.Via.BEARER);
        roomId = rooms.create(owner, "Club", "club", Rooms.Visibility.PUBLIC, null, null).id();
        otherRoomId = rooms.create(owner, "Other", "other", Rooms.Visibility.PUBLIC, null, null).id();
        control = control(true);
        feed = feed(control);
    }

    @AfterEach
    void tearDown() {
        feed.close();
        control.close();
        db.close();
    }

    StreamOutputControl control(boolean withPushers) {
        StreamOutputControl.Pushers pushers = !withPushers ? null : new StreamOutputControl.Pushers() {
            @Override
            public void start(String id, String rtmpUrl, String k) {
            }

            @Override
            public void stop(String id) {
            }

            @Override
            public RtmpPusher.Status status(String id) {
                return pushes.get(id);
            }
        };
        return new StreamOutputControl(rows, new StreamOutputControl.Switches() {
            @Override
            public boolean rtmp() {
                return switches.get("rtmp");
            }

            @Override
            public boolean ts() {
                return switches.get("ts");
            }

            @Override
            public boolean hls() {
                return switches.get("hls");
            }
        }, new StreamOutputControl.Outputs() {
            @Override
            public void start(String id) {
            }

            @Override
            public void stop(String id) {
            }
        }, pushers, () -> {
        });
    }

    OutputStatusFeed feed(StreamOutputControl c) {
        return new OutputStatusFeed(rows, c, (room, s) -> sent.add(
                (room.equals(roomId) ? "club" : "other") + " " + s.kind() + " " + s.status()
                        + (s.detail() == null ? "" : " " + s.detail())));
    }

    StreamOutputs.Output create(String room, StreamOutputs.Kind kind, boolean enabled) throws Exception {
        return rows.create(room, kind, kind == StreamOutputs.Kind.RTMP ? "rtmp://ingest.example/live" : null,
                kind == StreamOutputs.Kind.RTMP ? "live_abcdefgh1234" : null, enabled, userId, null);
    }

    static RtmpPusher.Status push(RtmpPusher.State state, String detail) {
        return new RtmpPusher.Status(state, detail, 0, 1, 0);
    }

    List<String> drain() throws Exception {
        feed.flush();
        synchronized (sent) {
            List<String> out = List.copyOf(sent);
            sent.clear();
            return out;
        }
    }

    @Test
    void listsTheActiveOutputsInOrderWithTheirStatus() throws Exception {
        assertEquals(List.of(), feed.active(roomId));
        create(roomId, StreamOutputs.Kind.HLS, false); // off: not listed
        create(roomId, StreamOutputs.Kind.TS, true);
        assertEquals(List.of(new RoomOutputs.Status("ts", "ready", null)), feed.active(roomId));
        create(roomId, StreamOutputs.Kind.RTMP, true);
        // no push running yet: connecting
        assertEquals(List.of(new RoomOutputs.Status("rtmp", "connecting", null),
                new RoomOutputs.Status("ts", "ready", null)), feed.active(roomId));
        pushes.put(roomId, push(RtmpPusher.State.RETRYING, "Connection refused"));
        assertEquals(new RoomOutputs.Status("rtmp", "retrying", "Connection refused"), feed.active(roomId).get(0));
        pushes.put(roomId, push(RtmpPusher.State.CONNECTED, null));
        assertEquals(new RoomOutputs.Status("rtmp", "connected", null), feed.active(roomId).get(0));
        pushes.put(roomId, push(RtmpPusher.State.STOPPED, null));
        assertEquals(new RoomOutputs.Status("rtmp", "connecting", null), feed.active(roomId).get(0));
        // the server switch takes an output out
        switches.put("ts", false);
        assertEquals(List.of(new RoomOutputs.Status("rtmp", "connecting", null)), feed.active(roomId));
        // other rooms are their own
        assertEquals(List.of(), feed.active(otherRoomId));
    }

    @Test
    void withoutFfmpegRtmpIsAnError() throws Exception {
        StreamOutputControl noFfmpeg = control(false);
        try (OutputStatusFeed f = feed(noFfmpeg)) {
            create(roomId, StreamOutputs.Kind.RTMP, true);
            assertEquals(List.of(new RoomOutputs.Status("rtmp", "error", OutputStatusFeed.NO_FFMPEG)),
                    f.active(roomId));
        } finally {
            noFfmpeg.close();
        }
    }

    @Test
    void sendsOnlyWhatChangedAndOffOnce() throws Exception {
        control.reconcileListener(feed::allChanged);
        create(roomId, StreamOutputs.Kind.TS, true);
        StreamOutputs.Output rtmp = create(roomId, StreamOutputs.Kind.RTMP, true);
        create(otherRoomId, StreamOutputs.Kind.HLS, true);
        control.reconcile();
        List<String> first = drain();
        assertEquals(3, first.size(), first.toString());
        assertEquals(List.of("club rtmp connecting", "club ts ready"),
                first.stream().filter(s -> s.startsWith("club")).toList());
        assertEquals(List.of("other hls ready"), first.stream().filter(s -> s.startsWith("other")).toList());
        // nothing changed: nothing sent
        control.reconcile();
        assertEquals(List.of(), drain());

        // the push changes status: that room's RTMP only
        pushes.put(roomId, push(RtmpPusher.State.RETRYING, "Connection refused"));
        feed.roomChanged(roomId);
        assertEquals(List.of("club rtmp retrying Connection refused"), drain());
        pushes.put(roomId, push(RtmpPusher.State.RETRYING, "Connection timed out"));
        feed.roomChanged(roomId);
        assertEquals(List.of("club rtmp retrying Connection timed out"), drain());
        pushes.put(roomId, push(RtmpPusher.State.CONNECTED, null));
        feed.roomChanged(roomId);
        assertEquals(List.of("club rtmp connected"), drain());

        // turned off, server switch off: off, once
        rows.update(roomId, rtmp.id(), new StreamOutputs.Patch(false, null, false, null), userId, null);
        control.reconcile();
        assertEquals(List.of("club rtmp off"), drain());
        switches.put("ts", false);
        control.reconcile();
        assertEquals(List.of("club ts off"), drain());
        control.reconcile();
        assertEquals(List.of(), drain());
        // back on
        switches.put("ts", true);
        control.reconcile();
        assertEquals(List.of("club ts ready"), drain());

        // a deleted output (or room) goes off too
        for (StreamOutputs.Output o : rows.list(otherRoomId)) {
            rows.delete(otherRoomId, o.id(), userId, null);
        }
        control.reconcile();
        assertEquals(List.of("other hls off"), drain());
        control.reconcile();
        assertEquals(List.of(), drain());
    }
}
