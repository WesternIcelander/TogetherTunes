package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ActorResolver#hook()} and {@link ActorResolver#actor} through a real server (SQLite). */
class ActorHookTest {
    @TempDir
    Path dir;

    private Database db;
    private Settings settings;
    private WebServer server;
    private AuthFixtures fixtures;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        settings = new Settings(db);
        fixtures = new AuthFixtures(db, System.currentTimeMillis());
        ActorResolver resolver = new ActorResolver(db, settings);

        Router router = new Router(resolver.hook());
        router.get("/whoami", (rq, rs) -> {
            Actor a = ActorResolver.actor(rq);
            assertSame(a, ActorResolver.actor(rq)); // memoised per request
            Map<String, Object> out = new LinkedHashMap<>();
            switch (a) {
                case Actor.User u -> {
                    out.put("kind", "user");
                    out.put("id", u.userId());
                    out.put("via", u.via().name());
                }
                case Actor.Guest g -> {
                    out.put("kind", "guest");
                    out.put("id", g.guestId());
                }
                case Actor.Anonymous ignored -> out.put("kind", "anonymous");
            }
            out.put("cookie", a.cookieAuthenticated());
            rs.json(out);
        });
        router.get("/static", (rq, rs) -> rs.text(200, "ok")); // never asks for the actor
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        client.close();
        db.close();
    }

    private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject whoami(String... headers) throws IOException, InterruptedException {
        HttpResponse<String> r = get("/whoami", headers);
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private String sessionToken(String kind) throws SQLException {
        String alice = fixtures.user("user-" + UuidV7.newId(), false);
        return fixtures.session(alice, kind, System.currentTimeMillis() + ActorResolverContract.DAY, null);
    }

    @Test
    void anonymousWithoutCredentials() throws Exception {
        JsonObject o = whoami();
        assertEquals("anonymous", o.get("kind").getAsString());
        assertEquals(false, o.get("cookie").getAsBoolean());
    }

    @Test
    void sidCookie() throws Exception {
        JsonObject o = whoami("Cookie", "theme=dark; sid=" + sessionToken("cookie"));
        assertEquals("user", o.get("kind").getAsString());
        assertEquals("COOKIE", o.get("via").getAsString());
        assertEquals(true, o.get("cookie").getAsBoolean());
    }

    @Test
    void bearerHeader() throws Exception {
        JsonObject o = whoami("Authorization", "Bearer " + sessionToken("bearer"));
        assertEquals("user", o.get("kind").getAsString());
        assertEquals("BEARER", o.get("via").getAsString());
        assertEquals(false, o.get("cookie").getAsBoolean());
        // scheme is case-insensitive
        assertEquals("user", whoami("Authorization", "bearer " + sessionToken("bearer")).get("kind").getAsString());
        // other schemes are ignored
        assertEquals("anonymous", whoami("Authorization", "Basic YWxpY2U6c2VjcmV0").get("kind").getAsString());
    }

    @Test
    void invalidBearerIs401OnlyWhereTheActorIsNeeded() throws Exception {
        HttpResponse<String> r = get("/whoami", "Authorization", "Bearer " + Tokens.newToken());
        assertEquals(401, r.statusCode(), r.body());
        assertEquals("invalid_token", JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("error").get("code").getAsString());
        assertEquals(401, get("/whoami", "Authorization", "Bearer").statusCode());
        assertEquals(200, get("/static", "Authorization", "Bearer " + Tokens.newToken()).statusCode());
    }

    @Test
    void gidCookie() throws Exception {
        String gid = Tokens.newToken();
        String guestId = fixtures.guest("cookie", Tokens.hash(gid), false);
        JsonObject o = whoami("Cookie", "gid=" + gid);
        assertEquals("guest", o.get("kind").getAsString());
        assertEquals(guestId, o.get("id").getAsString());
        assertEquals(true, o.get("cookie").getAsBoolean());
    }

    @Test
    void ipModeUsesTheResolvedClientIp() throws Exception {
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        String loopback = fixtures.guest("ip", GuestKeys.ipKeyHash("127.0.0.1"), false);
        String forwarded = fixtures.guest("ip", GuestKeys.ipKeyHash("2001:db8:5:6::1"), false);
        JsonObject o = whoami();
        assertEquals(loopback, o.get("id").getAsString());
        assertEquals(false, o.get("cookie").getAsBoolean());
        // the loopback peer is a trusted proxy, so X-Forwarded-For counts
        assertEquals(forwarded, whoami("X-Forwarded-For", "2001:db8:5:6:aaaa::9").get("id").getAsString());
        assertEquals("anonymous", whoami("X-Forwarded-For", "198.51.100.1").get("kind").getAsString());
    }

    @Test
    void missingHookIsAProgrammingError() throws Exception {
        Router bare = new Router();
        bare.get("/x", (rq, rs) -> rs.json(Map.of("kind", ActorResolver.actor(rq).toString())));
        server.close();
        server = WebServer.start("127.0.0.1", 0, bare, dir.toFile());
        HttpResponse<String> r = get("/x");
        assertEquals(500, r.statusCode());
        assertNull(r.headers().firstValue("Set-Cookie").orElse(null));
    }
}
