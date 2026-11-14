package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** {@link LivePlayingItems}: per-room sets merged by count, owner-checked removal, no gaps. */
class LivePlayingItemsTest {
    private final Object exec1 = new Object();
    private final Object exec2 = new Object();

    @Test
    void anItemListedByTwoRoomsStaysUntilBothDropIt() {
        LivePlayingItems p = new LivePlayingItems();
        assertFalse(p.isCurrentOrNext("x"));
        assertFalse(p.isCurrentOrNext(null));
        p.update("r1", exec1, Set.of("x", "y"));
        p.update("r2", exec2, Set.of("y", "z"));
        assertTrue(p.isCurrentOrNext("x") && p.isCurrentOrNext("y") && p.isCurrentOrNext("z"));
        assertEquals(3, p.size());

        p.update("r1", exec1, Set.of("x"));
        assertTrue(p.isCurrentOrNext("y"), "r2 still lists y");
        p.remove("r2", exec2);
        assertFalse(p.isCurrentOrNext("y"));
        assertFalse(p.isCurrentOrNext("z"));
        assertTrue(p.isCurrentOrNext("x"));
        p.update("r1", exec1, Set.of());
        assertEquals(0, p.size());
    }

    /**
     * A room forcing requests-only mode is flagged (its items still count as in use, e.g. for old
     * media versions), and guests in it get no media through it ({@link RoomViewers}); the flag
     * follows the latest publish and goes with the room.
     */
    @Test
    void requestsOnlyRoomsAreFlaggedAndGiveGuestsNoMedia() {
        LivePlayingItems p = new LivePlayingItems();
        RoomViewers viewers = new RoomViewers(p);
        io.siggi.togethertunes.auth.Actor guest = new io.siggi.togethertunes.auth.Actor.Guest("g1",
                io.siggi.togethertunes.settings.GuestIdentity.COOKIE, false);
        viewers.enter("c1", guest, "198.51.100.7", "r1");
        p.update("r1", exec1, Set.of("x"), java.util.Map.of(), false);
        assertFalse(p.requestsOnly("r1"));
        assertTrue(viewers.allowed(guest, "198.51.100.7", "x"));

        p.update("r1", exec1, Set.of("x"), java.util.Map.of(), true);
        assertTrue(p.requestsOnly("r1"));
        assertTrue(p.isCurrentOrNext("x"), "still in use");
        assertFalse(viewers.allowed(guest, "198.51.100.7", "x"), "forced: no media through this room");
        assertFalse(viewers.allowed(new io.siggi.togethertunes.auth.Actor.Anonymous(), "198.51.100.7", "x"));

        // in a second room that plays it and doesn't force it: allowed again
        viewers.enter("c2", guest, "198.51.100.7", "r2");
        p.update("r2", exec2, Set.of("x"), java.util.Map.of(), false);
        assertTrue(viewers.allowed(guest, "198.51.100.7", "x"));
        viewers.exit("c2");
        assertFalse(viewers.allowed(guest, "198.51.100.7", "x"));

        p.update("r1", exec1, Set.of("x"), java.util.Map.of(), false);
        assertFalse(p.requestsOnly("r1"));
        assertTrue(viewers.allowed(guest, "198.51.100.7", "x"));
        p.update("r1", exec1, Set.of("x"), java.util.Map.of(), true);
        p.remove("r1", exec1);
        assertFalse(p.requestsOnly("r1"), "withdrawn with the room");
    }

    /** Ranks merge across rooms (the earliest wins); the listener hears changes only. */
    @Test
    void ranksMergeAcrossRoomsAndTellTheListener() {
        LivePlayingItems p = new LivePlayingItems();
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        p.changeListener(changes::incrementAndGet);
        p.update("r1", exec1, Set.of("x"), java.util.Map.of("x", 0, "y", 3));
        p.update("r2", exec2, Set.of("y"), java.util.Map.of("y", 1, "z", 2));
        assertEquals(java.util.Map.of("x", 0, "y", 1, "z", 2), p.ranks());
        assertEquals(2, changes.get());
        p.update("r2", exec2, Set.of("y"), java.util.Map.of("y", 1, "z", 2));
        assertEquals(2, changes.get(), "unchanged");
        p.remove("r2", exec1);
        assertEquals(2, changes.get(), "not the owner");
        p.remove("r2", exec2);
        assertEquals(java.util.Map.of("x", 0, "y", 3), p.ranks());
        assertEquals(3, changes.get());
    }

    @Test
    void onlyTheExecutorThatPublishedCanWithdraw() {
        LivePlayingItems p = new LivePlayingItems();
        p.update("r1", exec1, Set.of("x"));
        p.update("r1", exec2, Set.of("x", "y")); // a new executor for the same room took over
        p.remove("r1", exec1); // the old one closing late
        assertTrue(p.isCurrentOrNext("x"));
        assertTrue(p.isCurrentOrNext("y"));
        p.remove("r1", exec2);
        assertEquals(0, p.size());
    }

    @Test
    void anItemThatStaysListedIsNeverMissingDuringUpdates() throws Exception {
        LivePlayingItems p = new LivePlayingItems();
        p.update("r1", exec1, Set.of("keep", "a"));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean missed = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread reader = Thread.ofVirtual().start(() -> {
            started.countDown();
            while (!stop.get()) {
                if (!p.isCurrentOrNext("keep")) {
                    missed.set(true);
                }
            }
        });
        started.await();
        for (int i = 0; i < 100_000; i++) {
            p.update("r1", exec1, Set.of("keep", i % 2 == 0 ? "b" : "a"));
        }
        stop.set(true);
        reader.join();
        assertFalse(missed.get());
    }
}
