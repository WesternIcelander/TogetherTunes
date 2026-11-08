package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.settings.GuestIdentity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The {@code {kind, id, name, isGuest}} actor form ({@code docs/api.md}). */
class ActorRefTest {
    private static JsonObject json(ActorRef ref) {
        return JsonParser.parseString(Json.GSON.toJson(ref)).getAsJsonObject();
    }

    @Test
    void serialisesExactlyTheDocumentedKeys() {
        JsonObject guest = json(ActorRef.guest("g1", "Quiet Walrus"));
        assertEquals(Set.of("kind", "id", "name", "isGuest"), guest.keySet());
        assertEquals("guest", guest.get("kind").getAsString());
        assertEquals("g1", guest.get("id").getAsString());
        assertEquals("Quiet Walrus", guest.get("name").getAsString());
        assertEquals(true, guest.get("isGuest").getAsBoolean());

        JsonObject user = json(ActorRef.user("u1", "Siggi"));
        assertEquals(Set.of("kind", "id", "name", "isGuest"), user.keySet());
        assertEquals("user", user.get("kind").getAsString());
        assertEquals(false, user.get("isGuest").getAsBoolean());
    }

    @Test
    void factories() {
        assertEquals(ActorRef.user("u1", "Siggi B"),
                ActorRef.of(new Accounts.Account("u1", "siggi", "Siggi B", List.of("admin"))));
        // A guest's mode and ban flag stay out of it.
        assertEquals(ActorRef.guest("g1", "Night Owl"),
                ActorRef.of(new Guests.GuestView("g1", GuestIdentity.IP, "Night Owl", true, true, 1L)));
        assertEquals(ActorRef.user("u1", "A"), ActorRef.fromColumns("u1", "A", null, null));
        assertEquals(ActorRef.guest("g1", "B"), ActorRef.fromColumns(null, null, "g1", "B"));
        assertNull(ActorRef.fromColumns(null, null, null, null));
    }

    @Test
    void badgeMatchesKind() {
        assertThrows(IllegalArgumentException.class, () -> new ActorRef("user", "u1", "A", true));
        assertThrows(IllegalArgumentException.class, () -> new ActorRef("guest", "g1", "A", false));
        assertThrows(IllegalArgumentException.class, () -> new ActorRef("anonymous", "x", "A", false));
        assertThrows(NullPointerException.class, () -> ActorRef.user("u1", null));
    }
}
