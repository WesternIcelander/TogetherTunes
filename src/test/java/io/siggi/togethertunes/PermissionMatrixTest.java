package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AdminActivityApi;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.AuthRateLimits;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.IdentityTickets;
import io.siggi.togethertunes.auth.MeApi;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.SessionsApi;
import io.siggi.togethertunes.auth.SetupApi;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The permission matrix ({@code docs/milestones.md}, M1 security): every route of
 * {@link Main#router} is called by each kind of actor, and the status must be exactly the one in
 * the {@link #MATRIX} table.
 *
 * <ul>
 *   <li><b>Columns</b>: {@link Who#ANONYMOUS}, {@link Who#GUEST} (a guest identity, run once in
 *       cookie mode and once in IP mode), {@link Who#USER} (a fresh account with the default
 *       {@code user} role) and {@link Who#ADMIN} (a fresh account holding the built-in
 *       {@code admin} role).</li>
 *   <li><b>Fresh state per cell</b>: each call gets a new caller and, where the route needs one,
 *       a new target (a user, a role, a spare session of the caller), so destructive calls (logout,
 *       password change, revoking sessions, deleting a role) can't affect other cells.</li>
 *   <li><b>Completeness</b>: the rows must name exactly the routes the router has, so a new route
 *       fails this test until its row is added. Later milestones extend the table with their
 *       routes (and, for room-scoped routes, room-role columns as they become relevant).</li>
 *   <li><b>Denials</b>: every {@code 401} must be {@code not_authenticated} and every {@code 403}
 *       must carry an error code, so a refusal can't be mistaken for an unrelated client error.</li>
 * </ul>
 *
 * <p>Requests send a valid body for the caller, so an allowed cell shows the route's real success
 * status rather than a validation error that would hide a missing permission check.
 */
class PermissionMatrixTest {
    private static final String PUBLIC_BASE_URL = "https://tunes.example";
    private static final int WS_UPGRADED = 101;

    /** The kinds of caller, in table column order. */
    enum Who { ANONYMOUS, GUEST, USER, ADMIN }

    /**
     * What a cell sends. {@code body} may be null. {@code websocket}: open a socket instead.
     * {@code headers}: extra request headers.
     */
    record Call(String method, String path, String body, boolean websocket, Map<String, String> headers) {
        Call(String method, String path, String body, boolean websocket) {
            this(method, path, body, websocket, Map.of());
        }

        static Call get(String path) {
            return new Call("GET", path, null, false);
        }

        static Call delete(String path) {
            return new Call("DELETE", path, null, false);
        }

        static Call post(String path, String body) {
            return new Call("POST", path, body, false);
        }

        static Call patch(String path, String body) {
            return new Call("PATCH", path, body, false);
        }

        static Call put(String path, String body) {
            return new Call("PUT", path, body, false);
        }

        static Call socket(String path) {
            return new Call("GET", path, null, true);
        }

        Call withHeader(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Call(method, path, body, websocket, Map.copyOf(h));
        }
    }

    /**
     * One route: how to call it in a cell, and the expected status for anonymous / guest / user /
     * admin.
     */
    record Row(String route, Function<Cell, Call> call, int anonymous, int guest, int user, int admin) {
        int expected(Who who) {
            return switch (who) {
                case ANONYMOUS -> anonymous;
                case GUEST -> guest;
                case USER -> user;
                case ADMIN -> admin;
            };
        }
    }

    private static Row row(String route, Function<Cell, Call> call, int anonymous, int guest, int user, int admin) {
        return new Row(route, call, anonymous, guest, user, admin);
    }

    /** An admin route: 401 for anonymous and guests, 403 for a plain user, {@code ok} for an admin. */
    private static Row admin(String route, Function<Cell, Call> call, int ok) {
        return row(route, call, 401, 401, 403, ok);
    }

    // ------------------------------------------------------------------ the table

    /** Every route, as {@code "METHOD pattern"} (see {@link Router#routes()}). Extend per milestone. */
    private static final List<Row> MATRIX = List.of(
            // Setup: done once the first account exists, whoever asks.
            row("GET /api/setup", c -> Call.get("/api/setup"), 200, 200, 200, 200),
            row("POST /api/setup", c -> Call.post("/api/setup", c.newAccountJson()), 409, 409, 409, 409),

            // Auth: open to every caller (registration is open in this fixture).
            row("POST /api/auth/register", c -> Call.post("/api/auth/register", c.newAccountJson()), 201, 201, 201, 201),
            row("POST /api/auth/login", c -> Call.post("/api/auth/login", c.targetCredentialsJson()), 200, 200, 200, 200),
            row("POST /api/auth/logout", c -> Call.post("/api/auth/logout", null), 204, 204, 204, 204),
            row("POST /api/auth/token", c -> Call.post("/api/auth/token", c.targetCredentialsJson()), 201, 201, 201, 201),

            // Me: users and guests manage themselves; an anonymous caller only reads.
            row("GET /api/me", c -> Call.get("/api/me"), 200, 200, 200, 200),
            row("PATCH /api/me", c -> Call.patch("/api/me", c.selfRenameJson()), 401, 200, 200, 200),
            row("POST /api/me/password", c -> Call.post("/api/me/password", c.passwordChangeJson()), 401, 401, 204, 204),
            row("POST /api/me/guest", c -> Call.post("/api/me/guest", null), 201, 200, 409, 409),
            row("PATCH /api/me/guest", c -> Call.patch("/api/me/guest", c.guestRenameJson()), 401, 200, 409, 409),
            row("GET /api/me/sessions", c -> Call.get("/api/me/sessions"), 401, 401, 200, 200),
            row("DELETE /api/me/sessions", c -> Call.delete("/api/me/sessions"), 401, 401, 200, 200),
            row("DELETE /api/me/sessions/{id}", c -> Call.delete("/api/me/sessions/" + c.spareSessionId()),
                    401, 401, 204, 204),

            // Admin: admin.users / admin.roles / admin.settings / ip.view.
            admin("GET /api/admin/users", c -> Call.get("/api/admin/users"), 200),
            admin("POST /api/admin/users", c -> Call.post("/api/admin/users", c.newAccountJson()), 201),
            admin("GET /api/admin/users/{id}", c -> Call.get("/api/admin/users/" + c.targetUserId()), 200),
            admin("PATCH /api/admin/users/{id}", c -> Call.patch("/api/admin/users/" + c.targetUserId(),
                    "{\"displayName\":\"Renamed\"}"), 200),
            admin("POST /api/admin/users/{id}/password", c -> Call.post("/api/admin/users/" + c.targetUserId()
                    + "/password", "{\"password\":\"temporary-password\"}"), 204),
            admin("GET /api/admin/users/{id}/roles", c -> Call.get("/api/admin/users/" + c.targetUserId() + "/roles"), 200),
            admin("PUT /api/admin/users/{id}/roles", c -> Call.put("/api/admin/users/" + c.targetUserId() + "/roles",
                    "{\"roleIds\":[\"" + c.targetRoleId() + "\"]}"), 200),
            admin("GET /api/admin/roles", c -> Call.get("/api/admin/roles"), 200),
            admin("POST /api/admin/roles", c -> Call.post("/api/admin/roles",
                    "{\"name\":\"role " + c.n + "\",\"permissions\":[\"rooms.create\"]}"), 201),
            admin("GET /api/admin/roles/{id}", c -> Call.get("/api/admin/roles/" + c.targetRoleId()), 200),
            admin("PATCH /api/admin/roles/{id}", c -> Call.patch("/api/admin/roles/" + c.targetRoleId(),
                    "{\"name\":\"renamed " + c.n + "\"}"), 200),
            admin("DELETE /api/admin/roles/{id}", c -> Call.delete("/api/admin/roles/" + c.targetRoleId()), 204),
            admin("GET /api/admin/roles/{id}/permissions",
                    c -> Call.get("/api/admin/roles/" + c.targetRoleId() + "/permissions"), 200),
            admin("PUT /api/admin/roles/{id}/permissions", c -> Call.put("/api/admin/roles/" + c.targetRoleId()
                    + "/permissions", "{\"permissions\":[\"library.upload\"]}"), 200),
            admin("GET /api/admin/settings", c -> Call.get("/api/admin/settings"), 200),
            admin("PATCH /api/admin/settings", c -> Call.patch("/api/admin/settings", "{\"overlay.enabled\":false}"), 200),
            admin("GET /api/admin/storage", c -> Call.get("/api/admin/storage"), 200),
            admin("GET /api/admin/encoding", c -> Call.get("/api/admin/encoding"), 200),
            admin("GET /api/admin/guests", c -> Call.get("/api/admin/guests"), 200),
            admin("GET /api/admin/audit", c -> Call.get("/api/admin/audit"), 200),
            admin("GET /api/admin/ip/sessions/{userId}", c -> Call.get("/api/admin/ip/sessions/" + c.targetUserId()), 200),

            // Uploads: library.upload (the default user role has it). Guests upload only as a request
            // in a room (no roomId here → 401), and with guests.canUpload off (the default) the
            // routes of an upload in progress refuse them with 403 guest_uploads_disabled
            // (GuestUploadsContract covers the guest path). complete is sent before any chunk, so an
            // allowed caller gets 409 upload_incomplete (UploadsContract covers a full complete).
            row("POST /api/uploads", c -> Call.post("/api/uploads", "{\"filename\":\"song.mp3\",\"size\":10}"),
                    401, 401, 201, 201),
            row("PUT /api/uploads/{id}", c -> Call.put("/api/uploads/" + c.uploadId(), "hello")
                    .withHeader("Content-Range", "bytes 0-4/10"), 401, 403, 200, 200),
            row("GET /api/uploads/{id}", c -> Call.get("/api/uploads/" + c.uploadId()), 401, 403, 200, 200),
            row("POST /api/uploads/{id}/complete", c -> Call.post("/api/uploads/" + c.uploadId() + "/complete", null),
                    401, 403, 409, 409),

            // YouTube imports: library.youtube (default user role), gated by ytdlp.enabled. Guests'
            // YouTube links are room requests (M3). A link that isn't a YouTube video gets an
            // allowed caller 400 without running yt-dlp (YoutubeImportsContract covers the rest).
            row("POST /api/imports/youtube", c -> Call.post("/api/imports/youtube", "{\"url\":\"https://example.com/\"}"),
                    401, 401, 400, 400),

            // The caller's own library: users only (guests have no library).
            row("GET /api/library", c -> Call.get("/api/library?q=song&kind=audio"), 401, 401, 200, 200),
            row("PUT /api/library/{itemId}", c -> Call.put("/api/library/" + c.itemId(), null), 401, 401, 201, 201),
            row("DELETE /api/library/{itemId}", c -> Call.delete("/api/library/" + c.libraryItemId()),
                    401, 401, 204, 204),

            // Items by id: anyone reads an item page and its job by direct link; editing needs the
            // adder (with the item in their library) or library.editAny (the matrix item was added
            // by the setup admin, so only the admin column may edit it); purging needs library.purge.
            // ItemsContract covers the adder rule.
            row("GET /api/items/{id}", c -> Call.get("/api/items/" + c.itemId()), 200, 200, 200, 200),
            row("PATCH /api/items/{id}", c -> Call.patch("/api/items/" + c.itemId(), "{\"title\":\"Renamed\"}"),
                    401, 401, 403, 200),
            row("DELETE /api/items/{id}", c -> Call.delete("/api/items/" + c.itemId()), 401, 401, 403, 204),
            row("GET /api/items/{id}/job", c -> Call.get("/api/items/" + c.jobItemId() + "/job"), 200, 200, 200, 200),
            // Media files: users always; guests only with guests.download (off) or a playing item (no rooms yet).
            row("GET /media/{id}/{file}", c -> Call.get("/media/" + c.mediaItemId() + "/audio.m4a"), 403, 403, 200, 200),

            // The caller's own playlists: users only (guests have none); someone else's playlist is
            // 404 (PlaylistsContract covers that). Adding the matrix item needs no permission.
            row("GET /api/playlists", c -> Call.get("/api/playlists?limit=10"), 401, 401, 200, 200),
            row("POST /api/playlists", c -> Call.post("/api/playlists", "{\"name\":\"Mix\"}"), 401, 401, 201, 201),
            row("GET /api/playlists/{id}", c -> Call.get("/api/playlists/" + c.playlistId()), 401, 401, 200, 200),
            row("PATCH /api/playlists/{id}", c -> Call.patch("/api/playlists/" + c.playlistId(),
                    "{\"name\":\"Renamed\"}"), 401, 401, 200, 200),
            row("DELETE /api/playlists/{id}", c -> Call.delete("/api/playlists/" + c.playlistId()), 401, 401, 204, 204),
            row("POST /api/playlists/{id}/entries", c -> Call.post("/api/playlists/" + c.playlistId() + "/entries",
                    "{\"itemId\":\"" + c.itemId() + "\"}"), 401, 401, 201, 201),
            row("PATCH /api/playlists/{id}/entries/{entryId}", c -> Call.patch(c.playlistEntryPath(),
                    "{\"beforeEntryId\":null}"), 401, 401, 200, 200),
            row("DELETE /api/playlists/{id}/entries/{entryId}", c -> Call.delete(c.playlistEntryPath()),
                    401, 401, 204, 204),

            // Sharing: users only (users can't share with guests, and guests have no library to
            // receive into). Any user may share any item. SharesContract covers the rules.
            row("GET /api/shares", c -> Call.get("/api/shares?direction=in&limit=10"), 401, 401, 200, 200),
            row("POST /api/shares", c -> Call.post("/api/shares", "{\"itemId\":\"" + c.itemId()
                    + "\",\"toUsername\":\"" + c.targetUsername() + "\"}"), 401, 401, 201, 201),
            row("POST /api/shares/seen", c -> Call.post("/api/shares/seen", "{\"all\":true}"), 401, 401, 200, 200),

            // Other users' libraries and playlists, read-only: library.viewAll (admins only by
            // default). UserLibrariesContract covers the contents.
            admin("GET /api/users", c -> Call.get("/api/users?q=target&limit=10"), 200),
            admin("GET /api/users/{id}/library", c -> Call.get("/api/users/" + c.targetUserId() + "/library?q=x"), 200),
            admin("GET /api/users/{id}/playlists", c -> Call.get("/api/users/" + c.targetUserId() + "/playlists"),
                    200),
            admin("GET /api/users/{id}/playlists/{playlistId}", c -> Call.get(c.ownPlaylistPathForViewAll()), 200),

            // Rooms (M3). Everyone lists rooms and reads a public one; creating needs rooms.create
            // (the default user role has it). The other rows act on a public room of the setup
            // admin: changing it is the owner's alone (so even an admin gets 403); deleting it,
            // its member list and its roles are open to its owner and moderators, which an admin
            // is everywhere through rooms.moderateAny. RoomsContract covers the room roles.
            row("GET /api/rooms", c -> Call.get("/api/rooms?limit=10"), 200, 200, 200, 200),
            row("POST /api/rooms", c -> Call.post("/api/rooms", "{\"name\":\"Room " + c.n + "\"}"), 401, 401, 201, 201),
            row("GET /api/rooms/{room}", c -> Call.get("/api/rooms/" + c.rootRoomSlug()), 200, 200, 200, 200),
            row("PATCH /api/rooms/{room}", c -> Call.patch("/api/rooms/" + c.rootRoomSlug(), "{\"name\":\"Renamed\"}"),
                    401, 401, 403, 403),
            row("DELETE /api/rooms/{room}", c -> Call.delete("/api/rooms/" + c.rootRoomSlug()), 401, 401, 403, 204),
            row("GET /api/rooms/{room}/members", c -> Call.get("/api/rooms/" + c.rootRoomSlug() + "/members"),
                    401, 401, 403, 200),
            row("PUT /api/rooms/{room}/members/{userId}", c -> Call.put("/api/rooms/" + c.rootRoomSlug() + "/members/"
                    + c.targetUserId(), "{\"role\":\"member\"}"), 401, 401, 403, 201),
            row("DELETE /api/rooms/{room}/members/{userId}", c -> Call.delete(c.rootRoomMemberPath()),
                    401, 401, 403, 204),
            // Adding a member by username: as PUT by id (owner and moderators).
            row("POST /api/rooms/{room}/members", c -> Call.post("/api/rooms/" + c.rootRoomSlug() + "/members",
                    "{\"username\":\"" + c.targetUsername() + "\",\"role\":\"member\"}"), 401, 401, 403, 201),
            // The server switches behind the room settings, for the management view: owner and moderators.
            row("GET /api/rooms/{room}/server-switches", c -> Call.get("/api/rooms/" + c.rootRoomSlug()
                    + "/server-switches"), 401, 401, 403, 200),
            // Guest bans: owner and moderators (an admin through rooms.moderateAny). RoomGuestsContract
            // covers moderators and members.
            row("GET /api/rooms/{room}/guest-bans", c -> Call.get("/api/rooms/" + c.rootRoomSlug() + "/guest-bans"),
                    401, 401, 403, 200),
            row("GET /api/rooms/{room}/guest-bans/{guestId}", c -> Call.get(c.rootRoomGuestBanPath(false)),
                    401, 401, 403, 200),
            row("PUT /api/rooms/{room}/guest-bans/{guestId}", c -> Call.put(c.rootRoomGuestBanPath(false), null),
                    401, 401, 403, 201),
            row("DELETE /api/rooms/{room}/guest-bans/{guestId}", c -> Call.delete(c.rootRoomGuestBanPath(true)),
                    401, 401, 403, 204),
            // Request search: anyone who may open the room and request from the library (guests and
            // anonymous visitors by guests.canRequestLibrary, on by default). RequestSearchContract
            // covers the scopes and refusals.
            row("GET /api/rooms/{room}/search", c -> Call.get("/api/rooms/" + c.rootRoomSlug() + "/search?q=x"),
                    200, 200, 200, 200),
            // Request history: owner and moderators read it (an admin through rooms.moderateAny);
            // only the owner adds from it to their library (so an admin gets 403). RoomRequestsContract
            // covers moderators, members and the results.
            row("GET /api/rooms/{room}/requests", c -> Call.get("/api/rooms/" + c.rootRoomSlug() + "/requests"),
                    401, 401, 403, 200),
            row("POST /api/rooms/{room}/requests/add-to-library", c -> Call.post("/api/rooms/" + c.rootRoomSlug()
                    + "/requests/add-to-library", "{\"requestIds\":[\"" + c.itemId() + "\"]}"), 401, 401, 403, 403),

            // The M0 WebSocket test page: open to everyone.
            row("GET /ws/echo", c -> Call.socket("/ws/echo"), WS_UPGRADED, WS_UPGRADED, WS_UPGRADED, WS_UPGRADED),
            row("GET /ws", c -> Call.socket("/ws"), WS_UPGRADED, WS_UPGRADED, WS_UPGRADED, WS_UPGRADED),
            row("GET /test/ws-echo/closes", c -> Call.get("/test/ws-echo/closes?tag=matrix"), 200, 200, 200, 200),
            row("POST /test/ws-echo/report", c -> Call.post("/test/ws-echo/report", "{\"ok\":true}"), 204, 204, 204, 204),
            // The room page's listen-mode diagnostics report: anyone may send one (it is only logged).
            row("POST /api/diagnostics", c -> Call.post("/api/diagnostics", "{\"ref\":\"matrix\"}"), 204, 204, 204, 204),

            // The item page by direct link: open to everyone (the page then calls /api/items/{id}).
            row("GET /items/{id}", c -> Call.get("/items/" + c.itemId()), 200, 200, 200, 200),
            // The room page and its QR short link (→ 302 to the page in remote mode): open to
            // everyone; the page then calls /api/rooms/{room}, which applies the room's visibility.
            row("GET /rooms/{room}", c -> Call.get("/rooms/" + c.rootRoomSlug()), 200, 200, 200, 200),
            row("GET /r/{slug}", c -> Call.get("/r/" + c.rootRoomSlug()), 302, 302, 302, 302),
            // The management view's page: served to anyone, the page checks through the API.
            row("GET /rooms/{room}/manage", c -> Call.get("/rooms/" + c.rootRoomSlug() + "/manage"), 200, 200, 200, 200),

            // Static files.
            row("GET /{path...}", c -> Call.get("/test/ws-echo.html"), 200, 200, 200, 200));

    // ------------------------------------------------------------------ fixture

    @TempDir
    Path dir;

    private StaticFiles staticFiles;
    private Database database;
    private Router router;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(System.currentTimeMillis());
    private String root; // sid cookie of the setup admin
    private String adminRoleId;
    private int counter;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(PermissionMatrixTest.class.getClassLoader(), dir);
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        Settings settings = new Settings(database, config);
        ActorResolver actors = new ActorResolver(database, settings, clock, config.secureCookies());
        Passwords passwords = new Passwords(new Passwords.Params(64, 1, 1));
        Accounts accounts = new Accounts(database, passwords, clock);
        Sessions sessions = new Sessions(database, clock);
        Users users = new Users(database, passwords, clock);
        Permissions permissions = new Permissions(database, settings);
        Guests guests = new Guests(database, settings, config.secureCookies());
        AuthRateLimits limits = new AuthRateLimits(new RateLimiter("setup", 100_000, 1, clock),
                new RateLimiter("register", 100_000, 1, clock), new RateLimiter("login", 100_000, 1, clock));
        TestUploads.RoomsWiring roomsWiring = TestUploads.rooms(database, dir, config, settings,
                permissions, actors, new IdentityTickets(), clock);
        router = Main.router(config, staticFiles, actors,
                new SetupApi(accounts, sessions, config.secureCookies(), limits),
                new AuthApi(accounts, sessions, settings, config.secureCookies(), limits),
                new SessionsApi(sessions, accounts, config.secureCookies()),
                new MeApi(accounts, users, permissions, guests, settings, new IdentityTickets()),
                new AdminApi(database, accounts, users, new Roles(database), permissions, settings),
                AdminActivityApi.create(database, guests, permissions, clock),
                TestUploads.api(database, dir, config, permissions, clock),
                TestUploads.youtubeApi(database, config, permissions, clock),
                TestUploads.libraryApi(database, config, permissions, clock),
                TestUploads.itemsApi(database, dir, permissions, clock),
                TestUploads.playlistsApi(database, config, permissions, clock),
                TestUploads.sharesApi(database, config, permissions, clock),
                TestUploads.userLibrariesApi(database, config, permissions, clock),
                TestUploads.mediaApi(database, dir, settings, io.siggi.togethertunes.rooms.PlayingItems.NO_ROOMS),
                roomsWiring.api(), roomsWiring.socket());
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());

        HttpResponse<String> setup = send(null, null, Call.post("/api/setup",
                "{\"username\":\"root\",\"password\":\"root-password\"}"));
        assertEquals(201, setup.statusCode(), setup.body());
        root = cookie(setup, "sid");
        ok(Call.patch("/api/admin/settings", "{\"registration.mode\":\"open\",\"guests.allowCustomNames\":true}"));
        for (JsonElement e : json(ok(Call.get("/api/admin/roles"))).getAsJsonArray("items")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(BuiltinRoles.ADMIN)) {
                adminRoleId = e.getAsJsonObject().get("id").getAsString();
            }
        }
        assertTrue(adminRoleId != null, "the admin role exists");
    }

    @AfterEach
    void tearDown() {
        server.close();
        staticFiles.close();
        database.close();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void everyRouteHasARow() {
        Set<String> rows = new TreeSet<>();
        for (Row r : MATRIX) {
            assertTrue(rows.add(r.route()), "duplicate row " + r.route());
        }
        assertEquals(new TreeSet<>(router.routes()), rows, "the matrix names exactly the router's routes");
    }

    @ParameterizedTest(name = "guests identified by {0}")
    @ValueSource(strings = {"cookie", "ip"})
    void everyActorGetsTheExpectedStatus(String identifyBy) throws Exception {
        ok(Call.patch("/api/admin/settings", "{\"guests.identifyBy\":\"" + identifyBy + "\"}"));
        List<String> wrong = new ArrayList<>();
        for (Row row : MATRIX) {
            for (Who who : Who.values()) {
                Cell cell = newCell(who);
                Call call = row.call().apply(cell);
                assertEquals(row.route(), routeOf(call), "the row's call reaches its route");
                Result got = cell.perform(call);
                int want = row.expected(who);
                if (got.status() != want) {
                    wrong.add(row.route() + " as " + who + ": want " + want + ", got " + got.status() + " " + got.body());
                } else if ((want == 401 || want == 403) && got.errorCode() == null) {
                    wrong.add(row.route() + " as " + who + ": " + want + " without an error code: " + got.body());
                }
            }
        }
        assertEquals(List.of(), wrong, "permission matrix mismatches (guests by " + identifyBy + ")");
    }

    @Test
    void callerKindsAreWhatTheyClaim() throws Exception {
        Map<Who, String> kinds = new LinkedHashMap<>();
        for (String identifyBy : List.of("cookie", "ip")) {
            ok(Call.patch("/api/admin/settings", "{\"guests.identifyBy\":\"" + identifyBy + "\"}"));
            for (Who who : Who.values()) {
                Cell cell = newCell(who);
                JsonObject me = json(cell.perform(Call.get("/api/me")).body());
                kinds.put(who, me.get("kind").getAsString());
                if (who == Who.ADMIN || who == Who.USER) {
                    boolean holdsAdminUsers = me.getAsJsonArray("permissions").asList().stream()
                            .anyMatch(p -> p.getAsString().equals("admin.users"));
                    assertEquals(who == Who.ADMIN, holdsAdminUsers, who + " " + me);
                }
            }
            assertEquals(Map.of(Who.ANONYMOUS, "anonymous", Who.GUEST, "guest", Who.USER, "user", Who.ADMIN, "user"),
                    kinds, "guests by " + identifyBy);
        }
    }

    // ------------------------------------------------------------------ cells

    /**
     * One cell's caller and targets. Targets are created lazily, by the setup admin, so each cell
     * only pays for what its row uses.
     */
    final class Cell {
        final Who who;
        final int n;
        final String ip;
        final String password;
        String cookie; // "sid=…" or "gid=…", or null
        private String targetUserId;
        private String targetUsername;
        private String targetRoleId;

        Cell(Who who, int n) {
            this.who = who;
            this.n = n;
            this.ip = "2001:db8:0:" + Integer.toHexString(n) + "::1"; // its own /64, so IP-mode guests never share a key
            this.password = "password-" + n + "-long";
        }

        /** A username and password nobody has used yet. */
        String newAccountJson() {
            return "{\"username\":\"new-" + n + "\",\"password\":\"new-password-" + n + "\"}";
        }

        /** The credentials of this cell's target user. */
        String targetCredentialsJson() throws RuntimeException {
            targetUserId();
            return "{\"username\":\"" + targetUsername + "\",\"password\":\"target-password\"}";
        }

        String selfRenameJson() {
            return who == Who.GUEST || who == Who.ANONYMOUS ? "{\"name\":\"Guest " + n + "\"}"
                    : "{\"displayName\":\"Person " + n + "\"}";
        }

        String guestRenameJson() {
            return "{\"name\":\"Guest " + n + "\"}";
        }

        String passwordChangeJson() {
            return "{\"currentPassword\":\"" + password + "\",\"newPassword\":\"" + password + "-changed\"}";
        }

        /** Another live session of the caller (for a user or admin), or a random id otherwise. */
        String spareSessionId() {
            try {
                if (cookie == null || !cookie.startsWith("sid=")) {
                    return "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
                }
                login("user-" + n, password);
                for (JsonElement e : json(perform(Call.get("/api/me/sessions")).body()).getAsJsonArray("items")) {
                    JsonObject s = e.getAsJsonObject();
                    if (!s.get("current").getAsBoolean()) {
                        return s.get("id").getAsString();
                    }
                }
                throw new AssertionError("no spare session");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** A user nobody else touches. */
        String targetUserId() {
            if (targetUserId == null) {
                try {
                    targetUsername = "target-" + n;
                    HttpResponse<String> r = send(null, ip, Call.post("/api/auth/register",
                            "{\"username\":\"" + targetUsername + "\",\"password\":\"target-password\"}"));
                    assertEquals(201, r.statusCode(), r.body());
                    targetUserId = json(r.body()).getAsJsonObject("user").get("id").getAsString();
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            return targetUserId;
        }

        /** The username of this cell's target user. */
        String targetUsername() {
            targetUserId();
            return targetUsername;
        }

        /**
         * An upload of 10 bytes started by this caller (a user or admin), or a made-up id for
         * callers who can't start one.
         */
        String uploadId() {
            try {
                if (cookie == null || !cookie.startsWith("sid=")) {
                    return "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
                }
                HttpResponse<String> r = send(cookie, ip, Call.post("/api/uploads",
                        "{\"filename\":\"song.mp3\",\"size\":10}"));
                assertEquals(201, r.statusCode(), r.body());
                return json(r.body()).get("uploadId").getAsString();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** A new ready audio item (in nobody's library). */
        String itemId() {
            try {
                String id = UuidV7.newId();
                database.write(c -> {
                    try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title,"
                            + " source, added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                            + " SELECT ?, 'audio', 'Song', 'upload', id, 1, 'ready', '720p30', 1 FROM users"
                            + " WHERE username = 'root'")) {
                        ps.setString(1, id);
                        assertEquals(1, ps.executeUpdate());
                    }
                    return null;
                });
                return id;
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        }

        /** A new ready audio item with an {@code audio.m4a} on disk. */
        String mediaItemId() {
            String id = itemId();
            try {
                Path itemDir = dir.resolve("data").resolve("media").resolve(id);
                java.nio.file.Files.createDirectories(itemDir);
                java.nio.file.Files.write(itemDir.resolve("audio.m4a"), new byte[16]);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return id;
        }

        /** A new item with a finished ingest job. */
        String jobItemId() {
            String id = itemId();
            try {
                database.write(c -> {
                    try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO ingest_jobs (id, item_id, kind,"
                            + " state, attempts, progress, created_at, started_at, finished_at, log)"
                            + " VALUES (?, ?, 'upload', 'done', 1, 1, 1, 1, 2, 'log')")) {
                        ps.setString(1, UuidV7.newId());
                        ps.setString(2, id);
                        assertEquals(1, ps.executeUpdate());
                    }
                    return null;
                });
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
            return id;
        }

        /** A new item in this caller's library (a user or admin), or in nobody's. */
        String libraryItemId() {
            String id = itemId();
            try {
                if (cookie != null && cookie.startsWith("sid=")) {
                    HttpResponse<String> r = send(cookie, ip, Call.put("/api/library/" + id, null));
                    assertEquals(201, r.statusCode(), r.body());
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            return id;
        }

        /** A new playlist of this caller (a user or admin), or a made-up id for other callers. */
        String playlistId() {
            try {
                if (cookie == null || !cookie.startsWith("sid=")) {
                    return "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c";
                }
                HttpResponse<String> r = send(cookie, ip, Call.post("/api/playlists", "{\"name\":\"List " + n + "\"}"));
                assertEquals(201, r.statusCode(), r.body());
                return json(r.body()).get("id").getAsString();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * {@code /api/users/{id}/playlists/{playlistId}} for a new playlist of this caller (a user
         * or admin), or made-up ids for other callers.
         */
        String ownPlaylistPathForViewAll() {
            String playlistId = playlistId();
            try {
                String userId = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5e";
                if (cookie != null && cookie.startsWith("sid=")) {
                    HttpResponse<String> r = send(cookie, ip, Call.get("/api/me"));
                    assertEquals(200, r.statusCode(), r.body());
                    userId = json(r.body()).get("id").getAsString();
                }
                return "/api/users/" + userId + "/playlists/" + playlistId;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** The path of an entry in a new playlist of this caller (made-up ids for other callers). */
        String playlistEntryPath() {
            String playlistId = playlistId();
            try {
                if (cookie == null || !cookie.startsWith("sid=")) {
                    return "/api/playlists/" + playlistId + "/entries/0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5d";
                }
                HttpResponse<String> r = send(cookie, ip, Call.post("/api/playlists/" + playlistId + "/entries",
                        "{\"itemId\":\"" + itemId() + "\"}"));
                assertEquals(201, r.statusCode(), r.body());
                return "/api/playlists/" + playlistId + "/entries/" + json(r.body()).get("id").getAsString();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** The slug of a new public room of the setup admin. */
        String rootRoomSlug() {
            try {
                return json(ok(Call.post("/api/rooms", "{\"name\":\"Root room " + n + "\",\"visibility\":\"public\"}"))
                        .body()).get("slug").getAsString();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** The member path of this cell's target user, a member of a new public room of the setup admin. */
        String rootRoomMemberPath() {
            try {
                String path = "/api/rooms/" + rootRoomSlug() + "/members/" + targetUserId();
                ok(Call.put(path, "{\"role\":\"member\"}"));
                return path;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * {@code /api/rooms/{room}/guest-bans/{guestId}} for a new public room of the setup admin and
         * a new guest (made from another address); with {@code banned}, the guest is banned there.
         */
        String rootRoomGuestBanPath(boolean banned) {
            try {
                HttpResponse<String> r = send(null, "2001:db8:1:" + Integer.toHexString(n) + "::7",
                        Call.post("/api/me/guest", null));
                assertTrue(r.statusCode() / 100 == 2, r.body());
                String path = "/api/rooms/" + rootRoomSlug() + "/guest-bans/" + json(r.body()).get("id").getAsString();
                if (banned) {
                    ok(Call.put(path, null));
                }
                return path;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** A custom role nobody else touches. */
        String targetRoleId() {
            if (targetRoleId == null) {
                try {
                    targetRoleId = json(ok(Call.post("/api/admin/roles", "{\"name\":\"target " + n + "\"}")).body())
                            .get("id").getAsString();
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            return targetRoleId;
        }

        Result perform(Call call) throws IOException, InterruptedException {
            if (call.websocket()) {
                return openSocket(call.path());
            }
            HttpResponse<String> r = send(cookie, ip, call);
            return new Result(r.statusCode(), r.body());
        }

        private Result openSocket(String path) {
            WebsocketHeaders headers = new WebsocketHeaders();
            headers.setHeader("Origin", PUBLIC_BASE_URL);
            headers.setHeader("X-Forwarded-For", ip);
            if (cookie != null) {
                headers.setHeader("Cookie", cookie);
            }
            try (SimpleWebsocket ws = new WebSocketConnector()
                    .connect(URI.create("ws://127.0.0.1:" + server.port() + path + "?tag=matrix"), headers)) {
                if (path.equals("/ws/echo")) {
                    ws.send(WebSocketMessage.create("ping " + n));
                    assertEquals("ping " + n, ws.read().getText());
                } else {
                    // the room socket starts by saying who it thinks the caller is
                    String first = ws.read().getText();
                    assertTrue(first.startsWith("{\"type\":\"identity\""), first);
                }
                return new Result(WS_UPGRADED, "");
            } catch (Exception e) {
                return new Result(-1, String.valueOf(e));
            }
        }

        private void login(String username, String pw) throws IOException, InterruptedException {
            HttpResponse<String> r = send(null, ip, Call.post("/api/auth/login",
                    "{\"username\":\"" + username + "\",\"password\":\"" + pw + "\"}"));
            assertEquals(200, r.statusCode(), r.body());
            if (cookie == null) {
                cookie = cookie(r, "sid");
            }
        }
    }

    record Result(int status, String body) {
        String errorCode() {
            try {
                JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
                return error == null ? null : error.get("code").getAsString();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** A fresh caller of the given kind, on its own IP (so IP-mode guests don't share one). */
    private Cell newCell(Who who) throws Exception {
        Cell cell = new Cell(who, ++counter);
        switch (who) {
            case ANONYMOUS -> {
            }
            case GUEST -> {
                HttpResponse<String> r = send(null, cell.ip, Call.post("/api/me/guest", null));
                assertEquals(201, r.statusCode(), r.body());
                cell.cookie = r.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("gid="))
                        ? cookie(r, "gid") : null; // IP mode: the IP alone identifies the guest
            }
            case USER, ADMIN -> {
                HttpResponse<String> r = send(null, cell.ip, Call.post("/api/auth/register",
                        "{\"username\":\"user-" + cell.n + "\",\"password\":\"" + cell.password + "\"}"));
                assertEquals(201, r.statusCode(), r.body());
                cell.cookie = cookie(r, "sid");
                if (who == Who.ADMIN) {
                    String id = json(r.body()).getAsJsonObject("user").get("id").getAsString();
                    ok(Call.put("/api/admin/users/" + id + "/roles", "{\"roleIds\":[\"" + adminRoleId + "\"]}"));
                }
            }
        }
        return cell;
    }

    // ------------------------------------------------------------------ helpers

    /** Calls as the setup admin and requires a 2xx. */
    private HttpResponse<String> ok(Call call) throws IOException, InterruptedException {
        HttpResponse<String> r = send(root, "192.0.2.1", call);
        assertTrue(r.statusCode() / 100 == 2, call + ": " + r.statusCode() + " " + r.body());
        return r;
    }

    private HttpResponse<String> send(String cookie, String forwardedFor, Call call)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + call.path()))
                .method(call.method(), call.body() == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(call.body()))
                .header("Origin", PUBLIC_BASE_URL);
        if (call.body() != null) {
            b.header("Content-Type", "application/json");
        }
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (forwardedFor != null) {
            b.header("X-Forwarded-For", forwardedFor); // the loopback peer is a trusted proxy
        }
        call.headers().forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The route ({@code "METHOD pattern"}) that answers {@code call}, as the router picks it. */
    private String routeOf(Call call) {
        String path = call.path();
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String best = null;
        io.siggi.togethertunes.http.PathPattern bestPattern = null;
        for (String route : router.routes()) {
            int space = route.indexOf(' ');
            if (!route.substring(0, space).equals(call.method())) {
                continue;
            }
            io.siggi.togethertunes.http.PathPattern p =
                    io.siggi.togethertunes.http.PathPattern.parse(route.substring(space + 1));
            if (p.match(bare) != null && (bestPattern == null || p.compareTo(bestPattern) < 0)) {
                best = route;
                bestPattern = p;
            }
        }
        return best == null ? call.method() + " (no route) " + bare : best;
    }

    private static JsonObject json(HttpResponse<String> r) {
        return json(r.body());
    }

    private static JsonObject json(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String cookie(HttpResponse<String> r, String name) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith(name + "=")) {
                return c.substring(0, c.indexOf(';'));
            }
        }
        throw new AssertionError("no " + name + " cookie: " + r.headers().map());
    }
}
