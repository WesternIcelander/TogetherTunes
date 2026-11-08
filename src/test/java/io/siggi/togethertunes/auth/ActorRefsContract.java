package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.settings.GuestIdentity;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link ActorRefs} lookups, in both dialects. */
abstract class ActorRefsContract {
    static final long NOW = 1_800_000_000_000L;

    protected Database db;
    private AuthFixtures fixtures;
    private ActorRefs refs;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        fixtures = new AuthFixtures(db, NOW);
        refs = new ActorRefs(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void usersAndGuests() throws SQLException {
        String alice = fixtures.user("alice", false);
        String bob = fixtures.user("bob", true);
        String guest = fixtures.guest("ip", "k1", true);
        String guestName = db.read(c -> ActorRefs.guest(c, guest)).name();

        assertEquals(ActorRef.user(alice, "alice"), refs.user(alice));
        assertEquals(ActorRef.user(bob, "bob"), refs.user(bob), "disabled users still show up in history");
        ActorRef g = refs.guest(guest);
        assertEquals(ActorRef.guest(guest, guestName), g);
        assertEquals(true, g.isGuest());

        assertEquals(ActorRef.user(alice, "alice"), refs.of(new Actor.User(alice, "s1", Actor.Via.COOKIE)));
        assertEquals(g, refs.of(new Actor.Guest(guest, GuestIdentity.IP, true)));
        assertNull(refs.of(Actor.ANONYMOUS));
    }

    @Test
    void unknownIds() throws SQLException {
        String alice = fixtures.user("alice", false);
        assertNull(refs.user("00000000-0000-7000-8000-000000000000"));
        assertNull(refs.guest(alice), "a user id is not a guest");
        assertNull(refs.user(null));
        assertNull(refs.of(new Actor.Guest("nope", GuestIdentity.COOKIE, false)));
    }
}
