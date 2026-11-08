package io.siggi.togethertunes.auth;

import com.google.gson.JsonParser;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The admin API for guests, the audit log and session IPs ({@code docs/api.md#admin}). IP addresses
 * come only from {@link IpAccess}, and only for {@code ip.view} holders
 * ({@code docs/auth.md#ip-address-policy}). Responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/admin/guests?q=&cursor=&limit=} ({@code admin.users}) →
 *       {@code {"items": [{"id", "name", "kind", "nameChosen", "banned", "createdAt",
 *       "lastSeenAt", "ip"?}], "nextCursor"?}}, most recently seen first; {@code q} matches part of
 *       the name. {@code ip} (the guest's last IP) only for {@code ip.view}.</li>
 *   <li>{@code GET /api/admin/audit?action=&actor=&cursor=&limit=} ({@code admin.users}) →
 *       {@code {"items": [{"id", "at", "actor"?, "action", "target"?, "details"?, "ip"?}],
 *       "nextCursor"?}}, newest first; {@code action} filters by exact action, {@code actor} by
 *       user or guest id. {@code actor} is an actor ref (left out when there is none); {@code ip} only for
 *       {@code ip.view}.</li>
 *   <li>{@code GET /api/admin/ip/sessions/{userId}} ({@code ip.view}) → {@code {"user": actor,
 *       "items": [{"id", "kind", "createdAt", "lastSeenAt", "expiresAt", "userAgent"?, "ip"?}]}}:
 *       the user's live sessions with the IP each was created from; {@code 404 user_not_found}.</li>
 * </ul>
 * {@code limit} is 1–200 (default 50); {@code 400 invalid_limit}, {@code 400 invalid_cursor}.
 */
public final class AdminActivityApi {
    private final Guests guests;
    private final AuditEntries audit;
    private final ActorRefs actorRefs;
    private final IpAccess ipAccess;
    private final Permissions permissions;

    public AdminActivityApi(Guests guests, AuditEntries audit, ActorRefs actorRefs, IpAccess ipAccess,
                            Permissions permissions) {
        this.guests = Objects.requireNonNull(guests);
        this.audit = Objects.requireNonNull(audit);
        this.actorRefs = Objects.requireNonNull(actorRefs);
        this.ipAccess = Objects.requireNonNull(ipAccess);
        this.permissions = Objects.requireNonNull(permissions);
    }

    /** The API over {@code db}, with the default {@link AuditEntries}, {@link ActorRefs} and {@link IpAccess}. */
    public static AdminActivityApi create(Database db, Guests guests, Permissions permissions, Clock clock) {
        return new AdminActivityApi(guests, new AuditEntries(db), new ActorRefs(db), new IpAccess(db, permissions, clock),
                permissions);
    }

    public void register(Router router) {
        var usersPerm = permissions.hook(Permission.ADMIN_USERS);
        router.get("/api/admin/guests", this::listGuests, usersPerm);
        router.get("/api/admin/audit", this::listAudit, usersPerm);
        router.get("/api/admin/ip/sessions/{userId}", this::sessionIps, permissions.hook(Permission.IP_VIEW));
    }

    private void listGuests(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        int limit = limit(request, Guests.LIST_DEFAULT, Guests.LIST_MAX);
        Guests.Page page = guests.list(request.query("q"), emptyToNull(request.query("cursor")), limit);
        List<String> ids = new ArrayList<>();
        for (Guests.GuestListItem item : page.items()) {
            ids.add(item.guest().id());
        }
        Map<String, String> ips = ipAccess.ipsOfGuests(actor, ids);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Guests.GuestListItem item : page.items()) {
            Guests.GuestView g = item.guest();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id());
            m.put("name", g.name());
            m.put("kind", g.kind().wireName());
            m.put("nameChosen", g.nameChosen());
            m.put("banned", g.banned());
            m.put("createdAt", g.createdAt());
            m.put("lastSeenAt", item.lastSeenAt());
            String ip = ips.get(g.id());
            if (ip != null) {
                m.put("ip", ip);
            }
            items.add(m);
        }
        send(response, items, page.nextCursor());
    }

    private void listAudit(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        int limit = limit(request, AuditEntries.LIST_DEFAULT, AuditEntries.LIST_MAX);
        AuditEntries.Page page = audit.list(emptyToNull(request.query("action")),
                emptyToNull(request.query("actor")), emptyToNull(request.query("cursor")), limit);
        List<String> ids = new ArrayList<>();
        for (AuditEntries.AuditEntryView e : page.items()) {
            ids.add(e.id());
        }
        Map<String, String> ips = ipAccess.ipsOfAuditEntries(actor, ids);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditEntries.AuditEntryView e : page.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("at", e.at());
            m.put("actor", e.actor());
            m.put("action", e.action());
            m.put("target", e.target());
            m.put("details", e.detailsJson() == null ? null : JsonParser.parseString(e.detailsJson()));
            String ip = ips.get(e.id());
            if (ip != null) {
                m.put("ip", ip);
            }
            items.add(m);
        }
        send(response, items, page.nextCursor());
    }

    private void sessionIps(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        String userId = request.pathParam("userId");
        ActorRef user = actorRefs.user(userId);
        if (user == null) {
            throw new HttpError(404, "user_not_found", "No such user");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (IpAccess.SessionIp s : ipAccess.sessions(actor, userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("kind", s.kind());
            m.put("createdAt", s.createdAt());
            m.put("lastSeenAt", s.lastSeenAt());
            m.put("expiresAt", s.expiresAt());
            m.put("userAgent", s.userAgent());
            m.put("ip", s.ip());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        out.put("items", items);
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private static void send(Response response, List<Map<String, Object>> items, String nextCursor)
            throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", nextCursor);
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private static int limit(Request request, int def, int max) {
        String param = request.query("limit");
        if (param == null) {
            return def;
        }
        int limit;
        try {
            limit = Integer.parseInt(param);
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > max) {
            throw new HttpError(400, "invalid_limit", "limit must be 1.." + max);
        }
        return limit;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
