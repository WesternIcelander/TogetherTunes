package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.auth.Actor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One window with sound per person ({@link SoundHolders}), without sockets. */
class SoundHoldersTest {
    private static final String ALICE = "u:alice";
    private static final String BOB = "u:bob";

    /** A socket that records what it was told: "holder", "muted" or "released". */
    private static final class Sock implements SoundHolders.Window {
        final List<String> told = new ArrayList<>();

        @Override
        public synchronized void sound(boolean holder, boolean released) {
            told.add(released ? "released" : holder ? "holder" : "muted");
        }

        synchronized String last() {
            return told.isEmpty() ? null : told.get(told.size() - 1);
        }
    }

    @Test
    void firstWindowHoldsLaterOnesAreMutedAndAClaimMovesIt() {
        SoundHolders s = new SoundHolders(10_000);
        Sock w1 = new Sock();
        Sock w2 = new Sock();
        Sock w3 = new Sock();
        s.open(ALICE, "window-1", w1);
        s.open(ALICE, "window-2", w2);
        s.open(ALICE, "window-3", w3);
        assertEquals(List.of("holder"), w1.told);
        assertEquals(List.of("muted"), w2.told);
        assertEquals(List.of("muted"), w3.told);
        s.claim(ALICE, "window-2");
        assertEquals("window-2", s.holder(ALICE));
        assertEquals("released", w1.last(), "the old holder is told");
        assertEquals("holder", w2.last());
        assertEquals("released", w3.last());
        // a claim from a window that isn't open changes nothing
        s.claim(ALICE, "window-9");
        assertEquals("window-2", s.holder(ALICE));
    }

    @Test
    void twoActorsAreIndependentAndAnonymousIsNotKept() {
        SoundHolders s = new SoundHolders(10_000);
        Sock a = new Sock();
        Sock b = new Sock();
        s.open(ALICE, "window-a", a);
        s.open(BOB, "window-b", b);
        assertEquals("holder", a.last());
        assertEquals("holder", b.last());
        Sock a2 = new Sock();
        s.open(ALICE, "window-a2", a2);
        s.claim(ALICE, "window-a2");
        assertEquals("released", a.last());
        assertEquals(List.of("holder"), b.told, "bob's window isn't touched");
        assertNull(SoundHolders.keyOf(new Actor.Anonymous()));
        assertEquals("g:g1", SoundHolders.keyOf(new Actor.Guest("g1", io.siggi.togethertunes.settings.GuestIdentity.COOKIE, false)));
    }

    @Test
    void severalSocketsOfOneWindowAndAReconnectKeepTheSound() {
        SoundHolders s = new SoundHolders(10_000);
        Sock first = new Sock();
        s.open(ALICE, "window-1", first);
        Sock other = new Sock();
        s.open(ALICE, "window-2", other);
        // the holder window reloads: its socket closes, the new one comes with the same id
        s.close(ALICE, "window-1", first);
        assertEquals("window-1", s.holder(ALICE), "kept during the grace period");
        Sock again = new Sock();
        s.open(ALICE, "window-1", again);
        assertEquals(List.of("holder"), again.told);
        // a second socket of the same window holds too, and a claim tells both
        Sock twin = new Sock();
        s.open(ALICE, "window-1", twin);
        assertEquals("holder", twin.last());
        s.claim(ALICE, "window-2");
        assertEquals("released", again.last());
        assertEquals("released", twin.last());
    }

    @Test
    void holderGoneOthersStayMutedUntilOneClaims() throws Exception {
        SoundHolders s = new SoundHolders(50);
        Sock w1 = new Sock();
        Sock w2 = new Sock();
        s.open(ALICE, "window-1", w1);
        s.open(ALICE, "window-2", w2);
        s.close(ALICE, "window-1", w1);
        long deadline = System.currentTimeMillis() + 5000;
        while (s.holder(ALICE) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNull(s.holder(ALICE), "nobody holds it after the grace period");
        assertEquals(List.of("muted"), w2.told, "the other window isn't unmuted");
        // a new window while another is open: muted too
        Sock w3 = new Sock();
        s.open(ALICE, "window-3", w3);
        assertEquals(List.of("muted"), w3.told);
        // the old holder comes back late: muted as well
        Sock back = new Sock();
        s.open(ALICE, "window-1", back);
        assertEquals(List.of("muted"), back.told);
        s.claim(ALICE, "window-3");
        assertEquals("holder", w3.last());
        // everything closes: nothing is kept, and the next window takes the sound
        s.close(ALICE, "window-1", back);
        s.close(ALICE, "window-2", w2);
        s.close(ALICE, "window-3", w3);
        deadline = System.currentTimeMillis() + 5000;
        while (s.size() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, s.size());
        Sock fresh = new Sock();
        s.open(ALICE, "window-4", fresh);
        assertEquals(List.of("holder"), fresh.told);
    }

    @Test
    void windowIds() {
        assertTrue(SoundHolders.validWindowId("0123456789abcdef0123456789abcdef"));
        assertTrue(SoundHolders.validWindowId("window-1"));
        assertFalse(SoundHolders.validWindowId("short"));
        assertFalse(SoundHolders.validWindowId("has space in it"));
        assertFalse(SoundHolders.validWindowId("x".repeat(65)));
        assertFalse(SoundHolders.validWindowId(null));
    }
}
