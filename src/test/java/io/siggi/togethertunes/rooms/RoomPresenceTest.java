package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link RoomPresence}: identified actors count once (listening wins), anonymous connections each. */
class RoomPresenceTest {
    @Test
    void counts() {
        RoomPresence p = new RoomPresence();
        assertEquals(RoomPresence.Counts.NONE, p.counts());
        p.join("a1", null, RoomPresence.Mode.LISTEN);
        p.join("a2", null, RoomPresence.Mode.REMOTE);
        p.join("u1", "u:alice", RoomPresence.Mode.REMOTE);
        p.join("u2", "u:alice", RoomPresence.Mode.REMOTE);
        p.join("g1", "g:walrus", RoomPresence.Mode.LISTEN);
        assertEquals(new RoomPresence.Counts(1, 1, 2), p.counts());
        assertEquals(2, p.identified());
        assertEquals(2, p.counts().identified());
        assertEquals(5, p.connections());

        p.mode("u2", RoomPresence.Mode.LISTEN);
        assertEquals(new RoomPresence.Counts(2, 0, 2), p.counts(), "one listening tab makes the user listening");
        p.join("u2", "u:alice", RoomPresence.Mode.REMOTE); // joins again: replaced
        assertEquals(new RoomPresence.Counts(1, 1, 2), p.counts());

        assertTrue(p.identify("a1", "g:heron"));
        assertEquals("g:heron", p.actorKey("a1"));
        assertEquals(new RoomPresence.Counts(2, 1, 1), p.counts());
        assertTrue(p.leave("a2"));
        assertFalse(p.leave("a2"));
        assertFalse(p.mode("a2", RoomPresence.Mode.LISTEN));
        assertFalse(p.identify("a2", "g:x"));
        assertNull(p.actorKey("a2"));

        assertEquals(List.of("u1", "u2"), p.removeActor("u:alice"));
        assertEquals(List.of(), p.removeActor(null), "anonymous connections are never removed as an actor");
        assertFalse(p.contains("u1"));
        assertEquals(new RoomPresence.Counts(2, 0, 0), p.counts());
    }

    @Test
    void people() {
        RoomPresence p = new RoomPresence();
        assertEquals(List.of(), p.people());
        p.join("a1", null, RoomPresence.Mode.LISTEN);
        p.join("g1", "g:walrus", RoomPresence.Mode.REMOTE);
        p.join("u1", "u:alice", RoomPresence.Mode.REMOTE);
        p.join("u2", "u:alice", RoomPresence.Mode.LISTEN);
        assertEquals(List.of(new RoomPresence.Person("g:walrus", false), new RoomPresence.Person("u:alice", true)),
                p.people(), "identified only, each once, in the order they came; one listening tab wins");
        p.leave("u2");
        assertEquals(List.of(new RoomPresence.Person("g:walrus", false), new RoomPresence.Person("u:alice", false)),
                p.people());
        p.identify("a1", "g:heron");
        assertEquals(3, p.people().size());
    }

    @Test
    void modes() {
        assertEquals(RoomPresence.Mode.LISTEN, RoomPresence.Mode.parse("listen"));
        assertEquals(RoomPresence.Mode.REMOTE, RoomPresence.Mode.parse("remote"));
        assertNull(RoomPresence.Mode.parse("LISTEN"));
        assertEquals("remote", RoomPresence.Mode.REMOTE.wire());
    }
}
