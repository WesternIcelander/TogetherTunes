package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.settings.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Who am I, and the guest identity ({@code docs/api.md}, {@code docs/auth.md#guests}).
 *
 * <ul>
 *   <li>{@code GET /api/me} → one of
 *     <ul>
 *       <li>{@code {"kind": "user", "id", "username", "displayName", "roles": [...],
 *           "mustChangePassword", "name", "isGuest": false, "permissions": [...]}}
 *           ({@code name} = {@code displayName}; {@code permissions}: the effective ones, with
 *           feature switches applied);</li>
 *       <li>{@code {"kind": "guest", "id", "name", "isGuest": true, "nameChosen", "customNames"}};</li>
 *       <li>{@code {"kind": "anonymous", "guestsEnabled", "customNames"}}.</li>
 *     </ul>
 *     For a user or guest the response is a superset of its {@link ActorRef}
 *     ({@code kind, id, name, isGuest}), the form every other response and message uses.
 *     {@code customNames} is {@code guests.allowCustomNames}: whether the UI may offer a name
 *     field. No IP anywhere ({@code docs/auth.md#ip-address-policy}).</li>
 *   <li>{@code POST /api/me/guest} {@code {"name"?}} (body optional) → {@code 201} and the guest
 *       (as in {@code GET /api/me}) when a guest was created ({@link Guests#create}; cookie mode
 *       sets the {@code gid} cookie); {@code 200} when the request already is a guest (a given
 *       {@code name} then renames it) or, in IP mode, its address already has one. Both add
 *       {@code "identityTicket"}: a new single-use ticket, valid for 60 s, that an already open
 *       WebSocket redeems with {@code {type:"identity", ticket}} to become this guest
 *       ({@link IdentityTickets}). A logged-in
 *       user gets {@code 409 already_logged_in}. Errors: {@code 403 guests_disabled},
 *       {@code 403 custom_names_disabled}, {@code 400 invalid_name},
 *       {@code 409 name_taken} (an account's name), {@code 400 name_blocked} (admin blocklist).</li>
 *   <li>{@code PATCH /api/me/guest} {@code {"name"}} → {@code 200} and the guest: renames the
 *       current guest ({@link Guests#rename}). An anonymous request gets
 *       {@code 401 identity_required} (create the guest first), a user {@code 409 not_a_guest}.</li>
 *   <li>{@code PATCH /api/me}: for a guest the same as {@code PATCH /api/me/guest}; for a user
 *       {@code {"displayName"?}} (blank: the username; absent: unchanged) → {@code 200} and the user as in
 *       {@code GET /api/me}; anonymous {@code 401 identity_required}.</li>
 *   <li>{@code GET}/{@code PATCH /api/me/settings}: the user's preferences, when
 *       {@link #userSettings} was given ({@link UserSettingsApi}).</li>
 * </ul>
 */
public final class MeApi {
    private final Accounts accounts;
    private final Users users;
    private final Permissions permissions;
    private final Guests guests;
    private final Settings settings;
    private final IdentityTickets tickets;
    private UserSettingsApi userSettings;

    record GuestRequest(String name) {
    }

    record PatchRequest(String name, String displayName) {
    }

    /** @param tickets the identity tickets the WebSocket redeems (shared with it) */
    public MeApi(Accounts accounts, Users users, Permissions permissions, Guests guests, Settings settings,
                 IdentityTickets tickets) {
        this.accounts = Objects.requireNonNull(accounts);
        this.users = Objects.requireNonNull(users);
        this.permissions = Objects.requireNonNull(permissions);
        this.guests = Objects.requireNonNull(guests);
        this.settings = Objects.requireNonNull(settings);
        this.tickets = Objects.requireNonNull(tickets);
    }

    /** Also serves {@code /api/me/settings} ({@link UserSettingsApi}); call before {@link #register}. */
    public void userSettings(UserSettingsApi userSettings) {
        this.userSettings = Objects.requireNonNull(userSettings);
    }

    public void register(Router router) {
        router.get("/api/me", this::get);
        router.patch("/api/me", this::patch);
        router.post("/api/me/guest", this::createGuest);
        router.patch("/api/me/guest", this::renameGuest);
        if (userSettings != null) {
            userSettings.register(router);
        }
    }

    private void get(Request request, Response response) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(me(ActorResolver.actor(request)));
    }

    private Map<String, Object> me(Actor actor) throws Exception {
        if (actor instanceof Actor.User user) {
            Accounts.Account account = accounts.findEnabled(user.userId());
            if (account == null) {
                throw new HttpError(401, "not_authenticated", "Log in first");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "user");
            out.putAll(AuthApi.userJson(account));
            out.put("name", account.displayName());
            out.put("isGuest", false);
            List<String> held = new ArrayList<>();
            for (Permission p : permissions.effective(user)) {
                held.add(p.wireName());
            }
            out.put("permissions", held);
            return out;
        }
        if (actor instanceof Actor.Guest guest) {
            Guests.GuestView view = guests.get(guest.guestId());
            if (view == null) {
                return anonymous();
            }
            return guestJson(view);
        }
        return anonymous();
    }

    private Map<String, Object> anonymous() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "anonymous");
        out.put("guestsEnabled", settings.guestsEnabled());
        out.put("customNames", settings.guestsAllowCustomNames());
        return out;
    }

    private Map<String, Object> guestJson(Guests.GuestView guest) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "guest");
        out.put("id", guest.id());
        out.put("name", guest.name());
        out.put("isGuest", true);
        out.put("nameChosen", guest.nameChosen());
        out.put("customNames", settings.guestsAllowCustomNames());
        return out;
    }

    private static boolean noBody(Request request) {
        return request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
    }

    private void createGuest(Request request, Response response) throws Exception {
        GuestRequest body = noBody(request) ? new GuestRequest(null) : request.readJson(GuestRequest.class);
        Actor actor = ActorResolver.actor(request);
        response.header("Cache-Control", "no-store");
        if (actor instanceof Actor.User) {
            throw new HttpError(409, "already_logged_in", "Logged-in users don't need a guest identity");
        }
        if (actor instanceof Actor.Guest guest) {
            Guests.GuestView view = body.name() == null ? guests.get(guest.guestId())
                    : guests.rename(guest.guestId(), body.name(), request.clientIp());
            response.json(200, withTicket(view));
            return;
        }
        Guests.Created created = guests.create(request, response, body.name());
        Guests.GuestView view = created.guest();
        if (!created.created() && body.name() != null) {
            view = guests.rename(view.id(), body.name(), request.clientIp());
        }
        response.json(created.created() ? 201 : 200, withTicket(view));
    }

    /** The guest as in {@code GET /api/me}, plus a new {@code identityTicket} for it. */
    private Map<String, Object> withTicket(Guests.GuestView view) throws Exception {
        Map<String, Object> out = guestJson(view);
        out.put("identityTicket", tickets.issue(view.id()));
        return out;
    }

    private void renameGuest(Request request, Response response) throws Exception {
        GuestRequest body = request.readJson(GuestRequest.class);
        Actor actor = ActorResolver.actor(request);
        response.header("Cache-Control", "no-store");
        if (actor instanceof Actor.User) {
            throw new HttpError(409, "not_a_guest", "Only guests have a guest name; change your display name instead");
        }
        response.json(guestJson(rename(request, actor, body.name())));
    }

    private Guests.GuestView rename(Request request, Actor actor, String name) throws Exception {
        if (!(actor instanceof Actor.Guest guest)) {
            throw new HttpError(401, "identity_required", "Create a guest identity first (POST /api/me/guest)");
        }
        if (name == null) {
            throw new HttpError(400, "invalid_name", "name is required");
        }
        return guests.rename(guest.guestId(), name, request.clientIp());
    }

    private void patch(Request request, Response response) throws Exception {
        PatchRequest body = request.readJson(PatchRequest.class);
        Actor actor = ActorResolver.actor(request);
        response.header("Cache-Control", "no-store");
        if (actor instanceof Actor.User user) {
            if (accounts.findEnabled(user.userId()) == null) {
                throw new HttpError(401, "not_authenticated", "Log in first");
            }
            if (body.displayName() != null) {
                users.setDisplayName(user.userId(), body.displayName());
            }
            response.json(me(user));
            return;
        }
        response.json(guestJson(rename(request, actor, body.name())));
    }
}
