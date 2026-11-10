package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.PackagerTest.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** {@link Mp3Encoders}: one encoder per room, shared by its listeners, stopped after the last left. */
class Mp3EncodersTest {
    final List<Mp3TestMedia.FakeFeed> feeds = new CopyOnWriteArrayList<>();
    final List<Mp3Encoder> created = new CopyOnWriteArrayList<>();

    Mp3Encoders encoders(long lingerMs) {
        return new Mp3Encoders((roomId, onIdle) -> {
            Mp3TestMedia.FakeFeed feed = new Mp3TestMedia.FakeFeed(false, List.of());
            feeds.add(feed);
            // never launched: the feed stays empty, so the attempt waits for units
            Mp3Encoder e = new Mp3Encoder(roomId, "ffmpeg-not-needed", () -> 128, feed, id -> id,
                    cmd -> new ProcessBuilder("sleep", "60").start(), ms -> Thread.sleep(ms),
                    System::currentTimeMillis, onIdle);
            created.add(e);
            return e;
        }, lingerMs);
    }

    @Test
    void listenersOfARoomShareOneEncoder() throws Exception {
        try (Mp3Encoders enc = encoders(60_000)) {
            Mp3Encoder.Listener a = enc.join("room-1");
            Mp3Encoder.Listener b = enc.join("room-1");
            Mp3Encoder.Listener c = enc.join("room-2");
            assertEquals(2, created.size());
            assertSame(a.encoder(), b.encoder());
            assertNotSame(a.encoder(), c.encoder());
            assertEquals(2, enc.listeners("room-1"));
            assertTrue(enc.running("room-1"));
            assertEquals(128, enc.bitrateKbps("room-1", 64));
            assertEquals(64, enc.bitrateKbps("room-3", 64));
        }
        for (Mp3Encoder e : created) {
            assertTrue(e.isStopped(), "close stops every encoder");
        }
    }

    @Test
    void encoderStopsAfterItsLastListenerLeftAndTheLingerTime() throws Exception {
        try (Mp3Encoders enc = encoders(50)) {
            Mp3Encoder.Listener a = enc.join("room-1");
            Mp3Encoder.Listener b = enc.join("room-1");
            await(() -> feeds.get(0).opens.get() == 1);
            a.close();
            Thread.sleep(150);
            assertTrue(enc.running("room-1"), "one listener is left");
            b.close();
            await(() -> !enc.running("room-1"));
            assertTrue(created.get(0).isStopped());
            await(() -> feeds.get(0).closes.get() == 1); // its lease on the program output is released
            // a new listener starts a new encoder
            Mp3Encoder.Listener c = enc.join("room-1");
            assertEquals(2, created.size());
            assertTrue(enc.running("room-1"));
            // leaving and coming back within the linger time keeps the encoder
            c.close();
            Mp3Encoder.Listener d = enc.join("room-1");
            Thread.sleep(150);
            assertTrue(enc.running("room-1"));
            assertSame(c.encoder(), d.encoder());
            assertFalse(created.get(1).isStopped());
        }
    }

    @Test
    void stopEndsTheRoomsListeners() throws Exception {
        try (Mp3Encoders enc = encoders(60_000)) {
            Mp3Encoder.Listener a = enc.join("room-1");
            enc.stop("room-1");
            assertEquals(Mp3Encoder.CloseReason.STOPPED, a.closeReason());
            assertFalse(enc.running("room-1"));
        }
    }

    @Test
    void streamTitles() {
        assertEquals("Artist - Title", Mp3Encoders.streamTitle(" Artist ", "Title"));
        assertEquals("Title", Mp3Encoders.streamTitle(null, "Title"));
        assertEquals("Title", Mp3Encoders.streamTitle("  ", "Title"));
        assertEquals("Artist", Mp3Encoders.streamTitle("Artist", ""));
    }
}
