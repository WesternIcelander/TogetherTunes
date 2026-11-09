package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AdminActivityApi;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.AuditEntries;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.AuthRateLimits;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.IdentityTickets;
import io.siggi.togethertunes.auth.IpAccess;
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
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.http.PathPattern;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The DTO IP-leak test ({@code docs/auth.md#ip-address-policy}, invariant 5): nothing an actor
 * without {@code ip.view} receives contains anything that looks like an IP address.
 *
 * <ul>
 *   <li><b>Every DTO type</b>: every record in the main classes is classified below as a DTO
 *       (something that reaches clients, directly or field by field) or as internal, so a new
 *       record fails this test until someone decides which it is. Each DTO type is produced by
 *       the real services from a database full of IPs (IPv4 and IPv6, written through sessions,
 *       guests in IP mode and audit rows), serialised with {@link Json#GSON} and scanned; the test
 *       fails if a DTO type was never produced. No DTO may have an IP-like component name, except
 *       {@link IpAccess.SessionIp}, which only {@code ip.view} holders get.</li>
 *   <li><b>Every endpoint</b>: every route of {@link Main#router} is called successfully at least
 *       once while seeding, and every {@code GET} route is then called as an anonymous visitor, a
 *       cookie guest, an IP-mode guest, a user and an admin without {@code ip.view}. All bodies
 *       and headers are scanned. An {@code ip.view} holder is the control: it sees the seeded IPs,
 *       but only in fields named {@code ip}, and only on the {@code /api/admin} endpoints that
 *       {@link IpAccess} builds.</li>
 * </ul>
 */
class DtoIpLeakTest {
    private static final String PUBLIC_BASE_URL = "https://tunes.example";

    // Documentation ranges (RFC 5737, RFC 3849): never real clients.
    private static final String V4_SETUP = "203.0.113.7";
    private static final String V4_ALICE = "192.0.2.44";
    private static final String V4_GUEST = "203.0.113.250";
    private static final String V6_ALICE = "2001:db8:1234:5678::42";
    private static final String V6_GUEST = "2001:db8:aaaa:bbbb:1:2:3:4";
    private static final String V6_SERVICE = "2001:db8:ffff::99";
    private static final List<String> SEEDED = List.of("127.0.0.1", V4_SETUP, V4_ALICE, V4_GUEST, V6_ALICE,
            V6_GUEST, V6_SERVICE, "2001:db8:1234:5678:0:0:0:42");

    /** Records that reach clients (directly, or field by field into a JSON body). */
    private static final Set<String> DTOS = Set.of(
            "io.siggi.togethertunes.auth.ActorRef",
            "io.siggi.togethertunes.auth.Accounts$Account",
            "io.siggi.togethertunes.auth.AuditEntries$AuditEntryView",
            "io.siggi.togethertunes.auth.AuditEntries$Page",
            "io.siggi.togethertunes.auth.Guests$Created",
            "io.siggi.togethertunes.auth.Guests$GuestListItem",
            "io.siggi.togethertunes.auth.Guests$GuestView",
            "io.siggi.togethertunes.auth.Guests$Page",
            "io.siggi.togethertunes.auth.Roles$Role",
            "io.siggi.togethertunes.auth.Sessions$Created",
            "io.siggi.togethertunes.auth.Sessions$Page",
            "io.siggi.togethertunes.auth.Sessions$SessionView",
            "io.siggi.togethertunes.auth.Users$Page",
            "io.siggi.togethertunes.auth.Users$RoleRef",
            "io.siggi.togethertunes.auth.Users$UserView",
            "io.siggi.togethertunes.http.EchoEndpoint$Closed",
            "io.siggi.togethertunes.settings.StringList",
            "io.siggi.togethertunes.settings.UploadQuota");

    /** The one DTO that carries IPs: built by {@link IpAccess} for {@code ip.view} holders only. */
    private static final String IP_VIEW_DTO = "io.siggi.togethertunes.auth.IpAccess$SessionIp";

    /** Records that never reach a client, and why. */
    private static final Map<String, String> INTERNAL = Map.ofEntries(
            Map.entry("io.siggi.togethertunes.DataDir", "server paths"),
            Map.entry("io.siggi.togethertunes.Main$Startup", "startup status"),
            Map.entry("io.siggi.togethertunes.config.Config", "server configuration"),
            Map.entry("io.siggi.togethertunes.config.ConfigLoader$Loaded", "server configuration"),
            Map.entry("io.siggi.togethertunes.config.ConfigLoader$Raw", "server configuration"),
            Map.entry("io.siggi.togethertunes.secrets.ServerSecret$Loaded", "the server secret"),
            Map.entry("io.siggi.togethertunes.db.Database$PoolSettings", "database plumbing"),
            Map.entry("io.siggi.togethertunes.db.Database$Tx", "database plumbing"),
            Map.entry("io.siggi.togethertunes.db.Migration", "database plumbing"),
            Map.entry("io.siggi.togethertunes.db.MigrationRunner$Applied", "database plumbing"),
            Map.entry("io.siggi.togethertunes.http.PathPattern$Segment", "routing"),
            Map.entry("io.siggi.togethertunes.http.Route", "routing"),
            Map.entry("io.siggi.togethertunes.auth.Actor$User", "request identity, never serialised"),
            Map.entry("io.siggi.togethertunes.auth.Actor$Guest", "request identity, never serialised"),
            Map.entry("io.siggi.togethertunes.auth.Actor$Anonymous", "request identity, never serialised"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$Credentials", "request input (holds the client IP)"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$Resolved", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$SessionUser", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$1Row", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.ActorResolver$GuestRow", "actor resolution"),
            Map.entry("io.siggi.togethertunes.auth.Accounts$Credential", "password hash"),
            Map.entry("io.siggi.togethertunes.auth.AdminApi$Grants", "permission check"),
            Map.entry("io.siggi.togethertunes.auth.AuthRateLimits", "rate limiters"),
            Map.entry("io.siggi.togethertunes.auth.LoginThrottle$Policy", "rate limiting"),
            Map.entry("io.siggi.togethertunes.auth.LoginThrottle$Refusal", "rate limiting"),
            Map.entry("io.siggi.togethertunes.auth.IdentityTickets$Ticket", "redeemed server-side by the WebSocket"),
            Map.entry("io.siggi.togethertunes.auth.IdentityTickets$Entry", "ticket store"),
            Map.entry("io.siggi.togethertunes.auth.Passwords$Params", "password hashing"),
            Map.entry("io.siggi.togethertunes.auth.Passwords$Parsed", "password hashing"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$RegisterRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$LoginRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$TokenRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.AuthApi$PasswordChangeRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.MeApi$GuestRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.MeApi$PatchRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.auth.SetupApi$SetupRequest", "request body (inbound)"),
            Map.entry("io.siggi.togethertunes.ingest.FfmpegProgress", "tool output, turned into a job's progress number"),
            Map.entry("io.siggi.togethertunes.ingest.ToolResult", "tool output, server-side only"),
            Map.entry("io.siggi.togethertunes.media.Mp4Boxes$Box", "MP4 box position in a media file, server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.CardRenderer$ItemCard", "card renderer input (item metadata + artwork image), server-side only"),
            Map.entry("io.siggi.togethertunes.ingest.CardRenderer$Fitted", "card text layout result, server-side only"));

    /** Component names that suggest an IP address. */
    private static final Pattern IP_NAME =
            Pattern.compile("(?i:ips?)|.*[a-z]Ips?|ip[A-Z_].*|(?i:.*addr|.*address(es)?|.*forwarded.*|.*host)");

    private static final Pattern IPV4 =
            Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?!\\d|\\.\\d)");
    /** Candidates only: at least two colons; validated by {@link #isIpv6}. */
    private static final Pattern IPV6_CANDIDATE =
            Pattern.compile("(?<![0-9A-Za-z_:.])[0-9A-Fa-f]*:[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[0-9A-Za-z_.]+)?");

    @TempDir
    Path dir;

    private StaticFiles staticFiles;
    private Database database;
    private Settings settings;
    private Router router;
    private WebServer server;
    private Passwords passwords;
    private Accounts accounts;
    private Sessions sessions;
    private Users users;
    private Roles roles;
    private Guests guests;
    private Permissions permissions;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(System.currentTimeMillis());

    /** Routes ("METHOD pattern") answered with a 2xx at least once. */
    private final Set<String> succeeded = new TreeSet<>();
    private final List<String> leaks = new ArrayList<>();
    /** Where the ip.view control saw IPs (path, then the IP). */
    private final Set<String> controlHits = new TreeSet<>();

    /** Who is calling. {@code ipView}: holds {@code ip.view} (the control). */
    private record Who(String label, String cookie, String bearer, String forwardedFor, boolean ipView) {
        Who withCookie(String c) {
            return new Who(label, c, bearer, forwardedFor, ipView);
        }

        Who withForwarded(String ip) {
            return new Who(label, cookie, bearer, ip, ipView);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(DtoIpLeakTest.class.getClassLoader(), dir);
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        settings = new Settings(database, config);
        ActorResolver actors = new ActorResolver(database, settings, clock, config.secureCookies());
        passwords = new Passwords(new Passwords.Params(64, 1, 1));
        accounts = new Accounts(database, passwords, clock);
        sessions = new Sessions(database, clock);
        users = new Users(database, passwords, clock);
        roles = new Roles(database);
        permissions = new Permissions(database, settings);
        guests = new Guests(database, settings, config.secureCookies());
        AuthRateLimits limits = new AuthRateLimits(new RateLimiter("setup", 1000, 1, clock),
                new RateLimiter("register", 1000, 1, clock), new RateLimiter("login", 1000, 1, clock));
        router = Main.router(config, staticFiles, actors,
                new SetupApi(accounts, sessions, config.secureCookies(), limits),
                new AuthApi(accounts, sessions, settings, config.secureCookies(), limits),
                new SessionsApi(sessions, accounts, config.secureCookies()),
                new MeApi(accounts, users, permissions, guests, settings, new IdentityTickets()),
                new AdminApi(database, accounts, users, roles, permissions, settings),
                AdminActivityApi.create(database, guests, permissions, clock));
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
        staticFiles.close();
        database.close();
    }

    // ------------------------------------------------------------------ the detector

    /** Everything in {@code text} that looks like an IPv4 or IPv6 address. */
    static List<String> ipsIn(String text) {
        List<String> found = new ArrayList<>();
        Matcher v4 = IPV4.matcher(text);
        while (v4.find()) {
            boolean ok = true;
            for (int i = 1; i <= 4; i++) {
                ok &= Integer.parseInt(v4.group(i)) <= 255;
            }
            if (ok) {
                found.add(v4.group());
            }
        }
        Matcher v6 = IPV6_CANDIDATE.matcher(text);
        while (v6.find()) {
            String candidate = v6.group();
            int zone = candidate.indexOf('%');
            String address = zone < 0 ? candidate : candidate.substring(0, zone);
            // "…::1:" or "…::1." at the end of a sentence
            while (!address.isEmpty() && (address.endsWith(".") || (address.endsWith(":") && !address.endsWith("::")))) {
                address = address.substring(0, address.length() - 1);
            }
            if (isIpv6(address)) {
                found.add(zone < 0 ? address : address + candidate.substring(zone));
            }
        }
        return found;
    }

    /** Whether {@code s} is an IPv6 literal. A string with a colon is never looked up in DNS. */
    private static boolean isIpv6(String s) {
        if (s.indexOf(':') < 0 || s.chars().filter(ch -> ch == ':').count() < 2) {
            return false;
        }
        try {
            InetAddress.getByName(s);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Test
    void detectorFindsIpAddresses() {
        for (String ip : List.of("203.0.113.7", "0.0.0.0", "255.255.255.255", "10.1.2.3", "::1", "::",
                "2001:db8::42", "2001:0db8:0000:0000:0000:ff00:0042:8329", "fe80::1%eth0", "::ffff:192.0.2.1",
                "2001:db8:1234:5678::", "64:ff9b::192.0.2.33")) {
            assertFalse(ipsIn(ip).isEmpty(), ip);
            assertFalse(ipsIn("seen from " + ip + ".").isEmpty(), ip + " in a sentence");
            assertFalse(ipsIn("{\"x\":\"" + ip + "\"}").isEmpty(), ip + " in JSON");
        }
        assertEquals(List.of("192.0.2.1"), ipsIn("addr=192.0.2.1:8080"));
        assertEquals(List.of("2001:db8::1"), ipsIn("[2001:db8::1]:8080"));
        assertEquals(List.of("2001:db8::1"), ipsIn("last seen at 2001:db8::1: twice"));
    }

    @Test
    void detectorIgnoresOrdinaryValues() {
        for (String s : List.of("0190d4f3-6c2a-7b3e-9f00-1a2b3c4d5e6f", "Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0",
                "Java-http-client/21.0.5", "12:34:56", "1.2.3", "256.1.1.1", "1.2.3.4.5", "ab:cd", "admin.users",
                "1731000000000", "dGhpcyBpcyBhIHRva2VuIG9mIDMyIGJ5dGVzIGxvbmc", "Bearer", "x264 veryfast",
                "a:b:c:d:e:f:g:h", "https://tunes.example/api/me", "sid=abc; Path=/; HttpOnly; SameSite=Lax",
                "Sun, 08 Nov 2026 12:00:00 GMT")) {
            assertEquals(List.of(), ipsIn(s), s);
        }
    }

    // ------------------------------------------------------------------ every DTO type

    /** Every record class in the main output, by binary name. */
    private static Set<String> mainRecords() throws Exception {
        Path root = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(root), "main classes are a directory: " + root);
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = root.relativize(f).toString().replace(f.getFileSystem().getSeparator(), ".");
                String name = rel.substring(0, rel.length() - ".class".length());
                if (name.startsWith("io.siggi.togethertunes.")
                        && Class.forName(name, false, DtoIpLeakTest.class.getClassLoader()).isRecord()) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    @Test
    void everyRecordIsClassifiedAndNoDtoHasAnIpField() throws Exception {
        Set<String> records = mainRecords();
        assertTrue(records.size() > 30, "the scan sees the main records: " + records);
        List<String> unclassified = new ArrayList<>();
        for (String r : records) {
            if (!DTOS.contains(r) && !INTERNAL.containsKey(r) && !r.equals(IP_VIEW_DTO)) {
                unclassified.add(r);
            }
        }
        assertEquals(List.of(), unclassified, "new records: add each to DTOS (it reaches clients; produce it in"
                + " everyDtoTypeSerialisesWithoutIps) or INTERNAL (with the reason)");
        Set<String> stale = new TreeSet<>(DTOS);
        stale.addAll(INTERNAL.keySet());
        stale.add(IP_VIEW_DTO);
        stale.removeAll(records);
        assertEquals(Set.of(), stale, "classified records that no longer exist");

        List<String> ipFields = new ArrayList<>();
        for (String name : DTOS) {
            for (RecordComponent c : Class.forName(name).getRecordComponents()) {
                if (IP_NAME.matcher(c.getName()).matches()) {
                    ipFields.add(name + "." + c.getName());
                }
            }
        }
        assertEquals(List.of(), ipFields, "general DTOs have no IP field; IPs come only from IpAccess");
        boolean sessionIpHasIp = false;
        for (RecordComponent c : Class.forName(IP_VIEW_DTO).getRecordComponents()) {
            sessionIpHasIp |= c.getName().equals("ip");
        }
        assertTrue(sessionIpHasIp, "the name check would catch an ip field");
        assertTrue(IP_NAME.matcher("clientIp").matches() && IP_NAME.matcher("ipLast").matches()
                && IP_NAME.matcher("remoteAddr").matches() && IP_NAME.matcher("ips").matches());
        assertFalse(IP_NAME.matcher("description").matches() || IP_NAME.matcher("ship").matches()
                || IP_NAME.matcher("permissions").matches() || IP_NAME.matcher("isGuest").matches()
                || IP_NAME.matcher("expiresAt").matches() || IP_NAME.matcher("remoteReason").matches()
                || IP_NAME.matcher("initiatedByPeer").matches() || IP_NAME.matcher("tips").matches());
    }

    @Test
    void everyDtoTypeSerialisesWithoutIps() throws Exception {
        seed();

        String aliceId = userId("alice");
        String adminId = userId("siggi");
        List<Object> produced = new ArrayList<>();
        // written with IPs, so the views below have IP-bearing rows behind them
        produced.add(sessions.create(aliceId, Actor.Via.BEARER, "dto-test", V6_SERVICE));
        produced.add(guests.create(GuestIdentity.IP, V6_SERVICE, null));
        produced.add(guests.create(GuestIdentity.COOKIE, V4_GUEST, null));
        accounts.audit(adminId, V6_SERVICE, "test.dto", aliceId, Map.of("note", "dto test"));

        Users.Page userPage = users.list(null, null, Users.LIST_MAX);
        produced.add(userPage);
        for (Users.UserView u : userPage.items()) {
            produced.add(users.get(u.id()));
            produced.add(sessions.list(u.id(), null, Sessions.LIST_MAX));
            produced.add(roles.userRoles(u.id()));
            produced.add(accounts.findEnabled(u.id()));
        }
        Guests.Page guestPage = guests.list(null, null, 200);
        produced.add(guestPage);
        for (Guests.GuestListItem g : guestPage.items()) {
            produced.add(guests.get(g.guest().id()));
        }
        produced.add(new AuditEntries(database).list(null, null, null, 200));
        produced.add(roles.list());
        produced.add(new ArrayList<>(settings.all().values())); // some are null (unset)
        String closes = expect(200, anon(), "GET", "/test/ws-echo/closes?tag=dto-leak", null).body();
        Class<?> closed = Class.forName("io.siggi.togethertunes.http.EchoEndpoint$Closed");
        for (JsonElement e : JsonParser.parseString(closes).getAsJsonObject().getAsJsonArray("closes")) {
            produced.add(Json.GSON.fromJson(e, closed));
        }

        Set<String> seen = new TreeSet<>();
        List<String> found = new ArrayList<>();
        for (Object o : produced) {
            assertNotNull(o);
            collectRecordTypes(o, seen);
            String json = Json.GSON.toJson(o);
            List<String> hits = ipsInJson(JsonParser.parseString(json), "$", new ArrayList<>());
            if (!hits.isEmpty()) {
                found.add(o.getClass().getName() + " " + hits + ": " + json);
            }
            for (String ip : SEEDED) {
                if (json.contains(ip)) {
                    found.add(o.getClass().getName() + " contains " + ip + ": " + json);
                }
            }
        }
        assertEquals(List.of(), found, "DTOs must not carry IP addresses");
        Set<String> missing = new TreeSet<>(DTOS);
        missing.removeAll(seen);
        assertEquals(Set.of(), missing, "every DTO type is produced and scanned");
        assertTrue(guestPage.items().size() >= 3 && userPage.items().size() >= 3, "the fixture has rows");

        // The control: the IP-only DTO does carry the IPs the views above left out.
        Actor admin = new Actor.User(adminId, "control", Actor.Via.COOKIE);
        List<IpAccess.SessionIp> withIps = new IpAccess(database, permissions, clock).sessions(admin, aliceId);
        Set<String> ips = new TreeSet<>();
        withIps.forEach(s -> ips.add(s.ip()));
        assertTrue(ips.containsAll(Set.of(V6_SERVICE, V4_ALICE)), ips.toString());
        for (IpAccess.SessionIp s : withIps) {
            assertFalse(ipsIn(Json.GSON.toJson(s)).isEmpty(), "the detector sees SessionIp's ip");
        }
    }

    /** Marks the record classes in {@code o}'s object graph (records, collections, maps). */
    private static void collectRecordTypes(Object o, Set<String> seen) throws Exception {
        if (o == null) {
            return;
        }
        if (o instanceof Collection<?> c) {
            for (Object e : c) {
                collectRecordTypes(e, seen);
            }
        } else if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                collectRecordTypes(e.getKey(), seen);
                collectRecordTypes(e.getValue(), seen);
            }
        } else if (o.getClass().isRecord()) {
            seen.add(o.getClass().getName());
            for (RecordComponent c : o.getClass().getRecordComponents()) {
                c.getAccessor().setAccessible(true);
                collectRecordTypes(c.getAccessor().invoke(o), seen);
            }
        }
    }

    /** Every string (value or key) in {@code e} that holds an IP, as {@code "path: value"}. */
    private static List<String> ipsInJson(JsonElement e, String path, List<String> out) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                if (!ipsIn(m.getKey()).isEmpty()) {
                    out.add(path + " key " + m.getKey());
                }
                ipsInJson(m.getValue(), path + "." + m.getKey(), out);
            }
        } else if (e.isJsonArray()) {
            int i = 0;
            for (JsonElement x : e.getAsJsonArray()) {
                ipsInJson(x, path + "[" + i++ + "]", out);
            }
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            if (!ipsIn(e.getAsString()).isEmpty()) {
                out.add(path + ": " + e.getAsString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ every endpoint

    @Test
    void noEndpointShowsIpsWithoutIpView() throws Exception {
        Fixture f = seed();

        // An IP-mode guest (IPv6), swept while IP mode is on.
        expect(200, f.admin, "PATCH", "/api/admin/settings", "{\"guests.identifyBy\":\"ip\"}");
        Who ipGuest = new Who("ip guest", null, null, V6_GUEST, false);
        expect(201, ipGuest, "POST", "/api/me/guest", null);
        JsonObject me = json(expect(200, ipGuest, "GET", "/api/me", null));
        assertEquals("guest", me.get("kind").getAsString(), me.toString());
        sweep(ipGuest, f);
        expect(200, f.admin, "PATCH", "/api/admin/settings", "{\"guests.identifyBy\":\"cookie\"}");

        for (Who who : List.of(anon(), f.cookieGuest, f.alice, f.aliceBearer, f.help, f.admin)) {
            sweep(who, f);
        }

        assertEquals(List.of(), leaks, "IP addresses in responses");
        Set<String> missing = new TreeSet<>(router.routes());
        missing.removeAll(succeeded);
        assertEquals(Set.of(), missing, "every route answered 2xx at least once");

        // The control: ip.view sees the seeded IPs where IpAccess puts them.
        for (String expected : List.of(
                "/api/admin/guests " + V4_GUEST, "/api/admin/guests " + V6_GUEST,
                "/api/admin/audit " + V4_SETUP, "/api/admin/audit " + V4_ALICE,
                "/api/admin/ip/sessions/" + f.aliceId + " " + V4_ALICE)) {
            assertTrue(controlHits.contains(expected), expected + " in " + controlHits);
        }
        assertTrue(controlHits.stream().anyMatch(h -> h.startsWith("/api/admin/ip/sessions/" + f.aliceId + " 2001:db8:1234:5678:")),
                controlHits.toString());
    }

    /** GETs every GET route as {@code who} (whatever the status) and scans the answers. */
    private void sweep(Who who, Fixture f) throws Exception {
        List<String> paths = List.of("/", "/test/ws-echo.html", "/js/test/ws-echo.js", "/ws/echo",
                "/test/ws-echo/closes?tag=dto-leak", "/api/setup", "/api/me", "/api/me/sessions",
                "/api/admin/users", "/api/admin/users?q=ali", "/api/admin/users/" + f.aliceId,
                "/api/admin/users/" + f.aliceId + "/roles", "/api/admin/roles", "/api/admin/roles/" + f.roleId,
                "/api/admin/roles/" + f.roleId + "/permissions", "/api/admin/settings", "/api/admin/guests",
                "/api/admin/audit", "/api/admin/audit?action=auth.login_failed", "/api/admin/ip/sessions/" + f.aliceId,
                "/api/admin/ip/sessions/" + f.adminId);
        Set<String> getRoutes = new TreeSet<>();
        router.routes().stream().filter(r -> r.startsWith("GET ")).forEach(getRoutes::add);
        Set<String> swept = new TreeSet<>();
        for (String p : paths) {
            swept.add(routeOf("GET", p));
            send(who, "GET", p, null);
        }
        assertEquals(getRoutes, swept, "the sweep covers every GET route");
    }

    private record Fixture(Who admin, Who alice, Who aliceBearer, Who help, Who cookieGuest, String adminId,
                           String aliceId, String roleId) {
    }

    /**
     * Seeds IP-bearing data through every route (each answering 2xx at least once): setup,
     * registration, logins (one failing), a bearer token, a password change, sessions listed and
     * revoked, roles and users administered, a cookie guest renamed, the echo socket closed.
     */
    private Fixture seed() throws Exception {
        Who anon = anon();
        expect(200, anon, "GET", "/api/setup", null);
        HttpResponse<String> setup = expect(201, anon.withForwarded(V4_SETUP), "POST", "/api/setup",
                "{\"username\":\"siggi\",\"password\":\"correct horse\"}");
        Who admin = new Who("admin (ip.view)", cookie(setup, "sid"), null, V4_SETUP, true);
        String adminId = json(setup).getAsJsonObject("user").get("id").getAsString();
        expect(200, admin, "PATCH", "/api/admin/settings",
                "{\"registration.mode\":\"open\",\"guests.allowCustomNames\":true}");

        HttpResponse<String> registered = expect(201, anon.withForwarded(V4_ALICE), "POST", "/api/auth/register",
                "{\"username\":\"alice\",\"password\":\"alice-password-1\",\"displayName\":\"Alice\"}");
        String aliceId = json(registered).getAsJsonObject("user").get("id").getAsString();
        Who aliceFirst = new Who("alice", cookie(registered, "sid"), null, V4_ALICE, false);
        expect(401, anon.withForwarded(V4_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"wrong password\"}");
        Who alice = new Who("alice", cookie(expect(200, anon.withForwarded(V6_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-password-1\"}"), "sid"), null, V6_ALICE, false);
        String token = json(expect(201, anon.withForwarded(V6_ALICE), "POST", "/api/auth/token",
                "{\"username\":\"alice\",\"password\":\"alice-password-1\"}")).get("token").getAsString();
        Who aliceBearer = new Who("alice (bearer)", null, token, V6_ALICE, false);
        expect(200, aliceBearer, "GET", "/api/me", null);
        expect(200, alice, "PATCH", "/api/me", "{\"displayName\":\"Alice A\"}");

        // sessions: list, revoke one, revoke the others, change the password, log out a spare one
        String firstSession = null;
        for (JsonElement e : json(expect(200, alice, "GET", "/api/me/sessions", null)).getAsJsonArray("items")) {
            JsonObject s = e.getAsJsonObject();
            if (!s.get("current").getAsBoolean() && s.get("kind").getAsString().equals("cookie")) {
                firstSession = s.get("id").getAsString();
            }
        }
        assertNotNull(firstSession);
        expectOk(alice, "DELETE", "/api/me/sessions/" + firstSession, null);
        expectOk(alice, "DELETE", "/api/me/sessions", null);
        expectOk(alice, "POST", "/api/me/password",
                "{\"currentPassword\":\"alice-password-1\",\"newPassword\":\"alice-password-2\"}");
        Who spare = new Who("alice", cookie(expect(200, anon.withForwarded(V4_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-password-2\"}"), "sid"), null, V4_ALICE, false);
        expectOk(spare, "POST", "/api/auth/logout", null);
        Who aliceAgain = new Who("alice", cookie(expect(200, anon.withForwarded(V4_ALICE), "POST", "/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-password-2\"}"), "sid"), null, V4_ALICE, false);
        aliceBearer = new Who("alice (bearer)", null, json(expect(201, anon.withForwarded(V6_ALICE), "POST",
                "/api/auth/token", "{\"username\":\"alice\",\"password\":\"alice-password-2\"}"))
                .get("token").getAsString(), V6_ALICE, false);
        expect(200, aliceFirst.withCookie(aliceAgain.cookie()), "GET", "/api/me", null);

        // roles and users, administered by the admin
        String roleId = json(expect(201, admin, "POST", "/api/admin/roles",
                "{\"name\":\"helpdesk\",\"permissions\":[\"admin.users\"]}")).get("id").getAsString();
        expect(200, admin, "GET", "/api/admin/roles", null);
        expect(200, admin, "GET", "/api/admin/roles/" + roleId, null);
        expect(200, admin, "PATCH", "/api/admin/roles/" + roleId, "{\"name\":\"help desk\"}");
        expect(200, admin, "GET", "/api/admin/roles/" + roleId + "/permissions", null);
        expect(200, admin, "PUT", "/api/admin/roles/" + roleId + "/permissions",
                "{\"permissions\":[\"admin.users\",\"admin.roles\",\"admin.settings\"]}");
        String helpId = json(expect(201, admin, "POST", "/api/admin/users",
                "{\"username\":\"help\",\"password\":\"temporary-1\"}")).get("id").getAsString();
        expect(200, admin, "GET", "/api/admin/users", null);
        expect(200, admin, "GET", "/api/admin/users/" + helpId, null);
        expect(200, admin, "PATCH", "/api/admin/users/" + helpId, "{\"displayName\":\"Help Desk\"}");
        expect(200, admin, "GET", "/api/admin/users/" + helpId + "/roles", null);
        expect(200, admin, "PUT", "/api/admin/users/" + helpId + "/roles", "{\"roleIds\":[\"" + roleId + "\"]}");
        expectOk(admin, "POST", "/api/admin/users/" + helpId + "/password", "{\"password\":\"temporary-2\"}");
        Who help = new Who("help desk (no ip.view)", cookie(expect(200, anon.withForwarded(V4_SETUP), "POST",
                "/api/auth/login", "{\"username\":\"help\",\"password\":\"temporary-2\"}"), "sid"), null, V4_SETUP, false);
        String spareRole = json(expect(201, help, "POST", "/api/admin/roles", "{\"name\":\"spare\"}"))
                .get("id").getAsString();
        expectOk(help, "DELETE", "/api/admin/roles/" + spareRole, null);
        expect(200, help, "GET", "/api/admin/settings", null);
        expect(200, help, "PATCH", "/api/admin/settings", "{\"guests.nameBlocklist\":[\"badword\"]}");
        expect(200, help, "GET", "/api/admin/guests", null);
        expect(200, help, "GET", "/api/admin/audit", null);
        expect(200, admin, "GET", "/api/admin/ip/sessions/" + aliceId, null);

        // a cookie guest, renamed both ways
        HttpResponse<String> guestCreated = expect(201, anon.withForwarded(V4_GUEST), "POST", "/api/me/guest", null);
        Who cookieGuest = new Who("cookie guest", cookie(guestCreated, "gid"), null, V4_GUEST, false);
        expect(200, cookieGuest, "PATCH", "/api/me/guest", "{\"name\":\"Blue Fox\"}");
        expect(200, cookieGuest, "PATCH", "/api/me", "{\"name\":\"Blue Fox Two\"}");
        expect(200, cookieGuest, "GET", "/api/me", null);

        // the test socket: a tagged close, its report, a plain GET, the test page
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", PUBLIC_BASE_URL);
        headers.setHeader("X-Forwarded-For", V6_ALICE);
        try (SimpleWebsocket ws = new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo?tag=dto-leak"), headers)) {
            ws.send(WebSocketMessage.create("hello"));
            assertEquals("hello", ws.read().getText());
        }
        succeeded.add("GET /ws/echo"); // the 101 above
        for (int i = 0; i < 100 && json(expect(200, anon, "GET", "/test/ws-echo/closes?tag=dto-leak", null))
                .getAsJsonArray("closes").isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertFalse(json(expect(200, anon, "GET", "/test/ws-echo/closes?tag=dto-leak", null))
                .getAsJsonArray("closes").isEmpty(), "the close was recorded");
        expectOk(anon, "POST", "/test/ws-echo/report", "{\"ok\":true,\"from\":\"dto test\"}");
        expect(200, anon, "GET", "/test/ws-echo.html", null);

        return new Fixture(admin, alice, aliceBearer, help, cookieGuest, adminId, aliceId, roleId);
    }

    // ------------------------------------------------------------------ helpers

    private static Who anon() {
        return new Who("anonymous", null, null, null, false);
    }

    private String userId(String username) throws Exception {
        for (Users.UserView u : users.list(null, null, Users.LIST_MAX).items()) {
            if (u.username().equals(username)) {
                return u.id();
            }
        }
        throw new AssertionError("no user " + username);
    }

    private HttpResponse<String> expect(int status, Who who, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(who, method, path, body);
        assertEquals(status, r.statusCode(), who.label() + " " + method + " " + path + ": " + r.body());
        return r;
    }

    private HttpResponse<String> expectOk(Who who, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(who, method, path, body);
        assertTrue(r.statusCode() / 100 == 2, who.label() + " " + method + " " + path + ": " + r.statusCode() + " " + r.body());
        return r;
    }

    /** Sends one request, notes its route if it succeeded, and scans the answer. */
    private HttpResponse<String> send(Who who, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Origin", PUBLIC_BASE_URL);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (who.cookie() != null) {
            b.header("Cookie", who.cookie());
        }
        if (who.bearer() != null) {
            b.header("Authorization", "Bearer " + who.bearer());
        }
        if (who.forwardedFor() != null) {
            b.header("X-Forwarded-For", who.forwardedFor()); // the loopback peer is a trusted proxy
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 == 2) {
            succeeded.add(routeOf(method, path));
        }
        scan(who, method + " " + path, path, r);
        return r;
    }

    private void scan(Who who, String what, String path, HttpResponse<String> r) {
        String where = who.label() + " " + what + " (" + r.statusCode() + ")";
        r.headers().map().forEach((name, values) -> {
            for (String v : values) {
                if (!ipsIn(v).isEmpty()) {
                    leaks.add(where + " header " + name + ": " + v);
                }
            }
        });
        String bodyText = r.body();
        if (bodyText.isEmpty()) {
            return;
        }
        String type = r.headers().firstValue("Content-Type").orElse("");
        if (!type.startsWith("application/json")) {
            if (!ipsIn(bodyText).isEmpty()) {
                leaks.add(where + " body: " + ipsIn(bodyText));
            }
            return;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(bodyText);
        } catch (JsonSyntaxException e) {
            leaks.add(where + " body is not JSON: " + bodyText);
            return;
        }
        boolean ipEndpoint = path.startsWith("/api/admin/guests") || path.startsWith("/api/admin/audit")
                || path.startsWith("/api/admin/ip/");
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        walk(root, "$", (keyPath, key, value) -> {
            if (key != null && key.equals("ip")) {
                if (!who.ipView() || !ipEndpoint) {
                    leaks.add(where + " has an ip field at " + keyPath);
                }
                if (value != null) {
                    if (ipsIn(value).isEmpty() && who.ipView()) {
                        leaks.add(where + " ip field holds a non-IP at " + keyPath + ": " + value);
                    }
                    controlHits.add(bare + " " + value);
                }
                return;
            }
            if (key != null && !ipsIn(key).isEmpty()) {
                leaks.add(where + " key " + keyPath);
            }
            if (value != null && !ipsIn(value).isEmpty()) {
                leaks.add(where + " " + keyPath + ": " + value);
            }
            if (value != null) {
                for (String ip : SEEDED) {
                    if (value.contains(ip)) {
                        leaks.add(where + " " + keyPath + " contains " + ip);
                    }
                }
            }
        });
    }

    private interface Visitor {
        /** {@code key}: the member name, or {@code null} in arrays; {@code value}: a string value, or null. */
        void visit(String path, String key, String value);
    }

    private static void walk(JsonElement e, String path, Visitor v) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                String p = path + "." + m.getKey();
                JsonElement x = m.getValue();
                v.visit(p, m.getKey(), x.isJsonPrimitive() && x.getAsJsonPrimitive().isString() ? x.getAsString() : null);
                walk(x, p, v);
            }
        } else if (e.isJsonArray()) {
            int i = 0;
            for (JsonElement x : e.getAsJsonArray()) {
                String p = path + "[" + i++ + "]";
                if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) {
                    v.visit(p, null, x.getAsString());
                }
                walk(x, p, v);
            }
        }
    }

    /** The route ({@code "METHOD pattern"}) that answers {@code method path}, as the router picks it. */
    private String routeOf(String method, String path) {
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String best = null;
        PathPattern bestPattern = null;
        for (String route : router.routes()) {
            int space = route.indexOf(' ');
            if (!route.substring(0, space).equals(method)) {
                continue;
            }
            PathPattern p = PathPattern.parse(route.substring(space + 1));
            if (p.match(bare) != null && (bestPattern == null || p.compareTo(bestPattern) < 0)) {
                best = route;
                bestPattern = p;
            }
        }
        return best == null ? method + " (no route) " + bare : best;
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
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
