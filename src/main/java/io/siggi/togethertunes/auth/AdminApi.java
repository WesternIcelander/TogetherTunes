package io.siggi.togethertunes.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.settings.SettingKey;
import io.siggi.togethertunes.settings.Settings;
import io.siggi.togethertunes.settings.StringList;
import io.siggi.togethertunes.settings.UploadQuota;
import io.siggi.togethertunes.storage.DiskSpace;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The admin API for users, roles and server settings ({@code docs/api.md#admin}). Every route
 * needs a permission ({@link Permissions#require}: {@code 401 not_authenticated} for guests and
 * anonymous visitors, {@code 403 forbidden} without it). Every change is written to
 * {@code audit_log} with the acting user. Responses are {@code Cache-Control: no-store}.
 *
 * <p><b>Users</b> ({@code admin.users}):
 * <ul>
 *   <li>{@code GET /api/admin/users?q=&cursor=&limit=} → {@code {"items": [user], "nextCursor"?}},
 *       by username; {@code q} matches part of the username or display name.</li>
 *   <li>{@code POST /api/admin/users} {@code {"username", "password", "displayName"?, "roleIds"?}}
 *       → {@code 201 user}: creates a local account with a <b>temporary</b> password (the user is
 *       asked to change it); it gets the default roles, or exactly {@code roleIds} when given.</li>
 *   <li>{@code GET /api/admin/users/{id}} → user.</li>
 *   <li>{@code PATCH /api/admin/users/{id}} {@code {"displayName"?, "disabled"?}} → user.
 *       Disabling logs the user out everywhere; {@code 409 cannot_disable_self},
 *       {@code 409 last_admin}.</li>
 *   <li>{@code POST /api/admin/users/{id}/password} {@code {"password"}} → {@code 204}: sets a
 *       temporary password and logs the user out everywhere (except the acting session).</li>
 *   <li>{@code GET /api/admin/users/{id}/roles} → {@code {"items": [role]}};
 *       {@code PUT} {@code {"roleIds": [...]}} replaces them ({@code 409 last_admin}).</li>
 * </ul>
 * {@code user} is {@code {"id", "username", "displayName", "email"?, "createdAt", "disabled",
 * "hasPassword", "mustChangePassword", "roles": [{"id", "name"}]}}; never an IP address.
 *
 * <p><b>Roles</b> ({@code admin.roles}; the list is also readable with {@code admin.users}, for
 * the role picker on the user page):
 * <ul>
 *   <li>{@code GET /api/admin/roles} → {@code {"items": [role], "permissions": [{"name",
 *       "description", "feature"?, "featureEnabled"?}]}} (all roles, not paginated: there are
 *       few; {@code permissions} lists every permission for the editor).</li>
 *   <li>{@code POST /api/admin/roles} {@code {"name", "permissions"?, "isDefault"?}} → {@code 201 role}.</li>
 *   <li>{@code GET /api/admin/roles/{id}} → role; {@code PATCH} {@code {"name"?, "isDefault"?}} →
 *       role; {@code DELETE} → {@code 204}.</li>
 *   <li>{@code GET /api/admin/roles/{id}/permissions} → {@code {"permissions": [...]}};
 *       {@code PUT} {@code {"permissions": [...]}} → role.</li>
 * </ul>
 * {@code role} is {@code {"id", "name", "builtin", "isDefault", "editable", "permissions": [...]}};
 * the rules (immutable {@code admin}, built-in {@code user}, names) are {@link Roles}'.
 *
 * <p><b>No escalation:</b> an admin page can't hand out more than the acting user holds. Granting
 * or taking away a role, changing a role's permissions or default flag, creating or deleting a
 * role all need the actor to hold every permission involved ({@code 403 forbidden}); the
 * {@code admin} role itself can only be granted or taken away by a holder of {@code admin}. And
 * an actor can't manage (disable, reset the password of, change the roles of) another user who
 * holds a permission the actor lacks, or the {@code admin} role when the actor doesn't. Holders of
 * {@code admin} pass all of these.
 *
 * <p><b>Settings</b> ({@code admin.settings}):
 * <ul>
 *   <li>{@code GET /api/admin/settings} → {@code {"items": [{"key", "group", "type", "value"?,
 *       "default"?, "options"?}]}} in {@link Settings#KEYS} order. {@code type} is
 *       {@code boolean}, {@code integer}, {@code bytes} (a whole number of bytes; the admin page
 *       shows it in GiB), {@code string}, {@code enum} (with {@code options}),
 *       {@code uploadQuota} ({@code {"perHour", "maxItemBytes"}}) or {@code stringList} (an array
 *       of strings); a {@code null} value (e.g. no
 *       yt-dlp cookies file) is left out.</li>
 *   <li>{@code PATCH /api/admin/settings} {@code {"key": value, ...}} → the same as {@code GET}.
 *       {@code null} resets a setting to its default. All or nothing: {@code 400 unknown_setting}
 *       or {@code 400 invalid_setting} change nothing. {@code invalid_setting} also covers a value
 *       the server can't use, e.g. an x264 preset that doesn't give the reference avcC
 *       ({@link #settingCheck}). Uploads need library requests ({@link Settings#COUPLED}): a
 *       patch turning {@code guests.canUpload} on also turns {@code guests.canRequestLibrary} on,
 *       one turning {@code guests.canRequestLibrary} off also turns {@code guests.canUpload} off,
 *       and the response lists such companion changes in {@code "adjusted"}.</li>
 *   <li>{@code GET /api/admin/encoding?profile=&videoBitrateKbps=} → {@code {"current", "encoding",
 *       "reencodeItems"}}: what changing {@code output.profile} / {@code output.videoBitrateKbps}
 *       to these values would do, so the admin page can ask before saving: the encoding the server
 *       encodes for now, the one these values give (a missing {@code profile} is the current
 *       setting's, a missing or empty {@code videoBitrateKbps} the profile's default) and how many
 *       {@code ready} items aren't encoded for it (they would re-encode). Bad values →
 *       {@code 400 invalid_setting}.</li>
 *   <li>{@code GET /api/admin/storage} → {@code {"freeBytes"?, "totalBytes"?, "minFreeBytes",
 *       "low"}}: the data volume's free space now ({@link DiskSpace}; left out if it can't be
 *       measured), the minimum ({@code storage.minFreeBytes}, 0 = no check) and whether adding
 *       media is paused because the free space is below it.</li>
 * </ul>
 */
public final class AdminApi {
    public static final String AUDIT_USER_CREATED = "admin.user_created";
    public static final String AUDIT_USER_UPDATED = "admin.user_updated";
    public static final String AUDIT_USER_PASSWORD = "admin.user_password_set";
    public static final String AUDIT_USER_ROLES = "admin.user_roles_set";
    public static final String AUDIT_ROLE_CREATED = "admin.role_created";
    public static final String AUDIT_ROLE_UPDATED = "admin.role_updated";
    public static final String AUDIT_ROLE_PERMISSIONS = "admin.role_permissions_set";
    public static final String AUDIT_ROLE_DELETED = "admin.role_deleted";
    public static final String AUDIT_SETTINGS = "admin.settings_changed";

    private final Database db;
    private final Accounts accounts;
    private final Users users;
    private final Roles roles;
    private final Permissions permissions;
    private final Settings settings;
    private volatile Runnable settingsListener = () -> { };
    private volatile SettingCheck settingCheck = (key, value) -> { };
    private volatile DiskSpace diskSpace = DiskSpace.unlimited();
    private volatile EncodingInfo encodingInfo;

    /** What {@code GET /api/admin/encoding} reports on. */
    public interface EncodingInfo {
        /** The output encoding the server encodes for now. */
        OutputEncoding current();

        /** The number of {@code ready} items not encoded for {@code target}. */
        long reencodeCount(OutputEncoding target) throws SQLException;
    }

    /** A check a new setting value must pass beyond its own validation (e.g. an x264 preset that works). */
    @FunctionalInterface
    public interface SettingCheck {
        /** @throws IllegalArgumentException with a message for the admin if {@code value} can't be used */
        void check(SettingKey<?> key, Object value);
    }

    public AdminApi(Database db, Accounts accounts, Users users, Roles roles, Permissions permissions,
                    Settings settings) {
        this.db = Objects.requireNonNull(db);
        this.accounts = Objects.requireNonNull(accounts);
        this.users = Objects.requireNonNull(users);
        this.roles = Objects.requireNonNull(roles);
        this.permissions = Objects.requireNonNull(permissions);
        this.settings = Objects.requireNonNull(settings);
    }

    /**
     * Runs after the admin changed settings (on the request thread, after the commit), e.g. so the
     * stream outputs follow the {@code outputs.*.enabled} switches at once. Must not block long.
     */
    public void settingsListener(Runnable listener) {
        this.settingsListener = Objects.requireNonNull(listener);
    }

    /**
     * Sets a check every value set through {@code PATCH /api/admin/settings} must pass (on the
     * request thread, before anything is stored; may take a moment, e.g. a test encode).
     */
    public void settingCheck(SettingCheck check) {
        this.settingCheck = check == null ? (key, value) -> { } : check;
    }

    /** What {@code GET /api/admin/encoding} reports; until set, the settings' encoding and no items. */
    public void encodingInfo(EncodingInfo info) {
        this.encodingInfo = info;
    }

    /** The data volume {@code GET /api/admin/storage} reports on. */
    public void diskSpace(DiskSpace diskSpace) {
        this.diskSpace = Objects.requireNonNull(diskSpace);
    }

    public void register(Router router) {
        var usersPerm = permissions.hook(Permission.ADMIN_USERS);
        var rolesPerm = permissions.hook(Permission.ADMIN_ROLES);
        var settingsPerm = permissions.hook(Permission.ADMIN_SETTINGS);
        router.get("/api/admin/users", this::listUsers, usersPerm);
        router.post("/api/admin/users", this::createUser, usersPerm);
        router.get("/api/admin/users/{id}", this::getUser, usersPerm);
        router.patch("/api/admin/users/{id}", this::updateUser, usersPerm);
        router.post("/api/admin/users/{id}/password", this::setPassword, usersPerm);
        router.get("/api/admin/users/{id}/roles", this::getUserRoles, usersPerm);
        router.put("/api/admin/users/{id}/roles", this::setUserRoles, usersPerm);

        router.get("/api/admin/roles", this::listRoles, (request, response, next) -> {
            Actor actor = ActorResolver.actor(request);
            if (!permissions.allowed(actor, Permission.ADMIN_USERS)) {
                permissions.require(actor, Permission.ADMIN_ROLES);
            }
            next.handle(request, response);
        });
        router.post("/api/admin/roles", this::createRole, rolesPerm);
        router.get("/api/admin/roles/{id}", this::getRole, rolesPerm);
        router.patch("/api/admin/roles/{id}", this::updateRole, rolesPerm);
        router.delete("/api/admin/roles/{id}", this::deleteRole, rolesPerm);
        router.get("/api/admin/roles/{id}/permissions", this::getRolePermissions, rolesPerm);
        router.put("/api/admin/roles/{id}/permissions", this::setRolePermissions, rolesPerm);

        router.get("/api/admin/settings", this::getSettings, settingsPerm);
        router.patch("/api/admin/settings", this::patchSettings, settingsPerm);
        router.get("/api/admin/storage", this::getStorage, settingsPerm);
        router.get("/api/admin/encoding", this::getEncoding, settingsPerm);
    }

    // ------------------------------------------------------------- users

    private void listUsers(Request request, Response response) throws Exception {
        int limit = limit(request, Users.LIST_DEFAULT, Users.LIST_MAX);
        String cursor = emptyToNull(request.query("cursor"));
        Users.Page page = users.list(request.query("q"), cursor, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Users.UserView u : page.items()) {
            items.add(userJson(u));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", page.nextCursor());
        send(response, 200, out);
    }

    private void createUser(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        JsonObject body = request.readJson(JsonObject.class);
        String username = string(body, "username");
        String password = string(body, "password");
        String displayName = string(body, "displayName");
        List<String> roleIds = body.has("roleIds") && !body.get("roleIds").isJsonNull() ? strings(body, "roleIds") : null;
        if (roleIds != null) {
            Grants mine = grants(actor.userId());
            Map<String, Roles.Role> all = rolesById();
            for (String id : roleIds) {
                Roles.Role role = all.get(id);
                if (role == null) {
                    throw new HttpError(400, "unknown_role", "No such role: " + id);
                }
                requireMayHandOut(mine, role);
            }
        }
        Accounts.Account account = accounts.createLocal(username, displayName, password, false, request.clientIp(),
                AUDIT_USER_CREATED, actor.userId(), true);
        if (roleIds != null) {
            roles.setUserRoles(account.id(), roleIds);
        }
        send(response, 201, userJson(users.get(account.id())));
    }

    private void getUser(Request request, Response response) throws Exception {
        send(response, 200, userJson(users.get(request.pathParam("id"))));
    }

    private void updateUser(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        JsonObject body = request.readJson(JsonObject.class);
        for (String key : body.keySet()) {
            if (!key.equals("displayName") && !key.equals("disabled")) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
        users.get(id); // 404 first
        requireMayManage(actor, id);
        Map<String, Object> details = new LinkedHashMap<>();
        Users.UserView user = null;
        if (body.has("displayName")) {
            user = users.setDisplayName(id, string(body, "displayName"));
            details.put("displayName", user.displayName());
        }
        if (body.has("disabled")) {
            JsonElement e = body.get("disabled");
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
                throw new HttpError(400, "invalid_json", "disabled must be true or false");
            }
            boolean before = users.get(id).disabled();
            user = users.setDisabled(actor.userId(), id, e.getAsBoolean());
            if (before != user.disabled()) {
                details.put("disabled", user.disabled());
            }
        }
        if (user == null) {
            user = users.get(id);
        }
        if (!details.isEmpty()) {
            accounts.audit(actor.userId(), request.clientIp(), AUDIT_USER_UPDATED, id, details);
        }
        send(response, 200, userJson(user));
    }

    private void setPassword(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        JsonObject body = request.readJson(JsonObject.class);
        users.get(id);
        requireMayManage(actor, id);
        int revoked = users.setTemporaryPassword(id, string(body, "password"),
                id.equals(actor.userId()) ? actor.sessionId() : null);
        accounts.audit(actor.userId(), request.clientIp(), AUDIT_USER_PASSWORD, id, Map.of("sessionsRevoked", revoked));
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void getUserRoles(Request request, Response response) throws Exception {
        send(response, 200, Map.of("items", rolesJson(roles.userRoles(request.pathParam("id")))));
    }

    private void setUserRoles(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        JsonObject body = request.readJson(JsonObject.class);
        List<String> wanted = strings(body, "roleIds");
        List<Roles.Role> current = roles.userRoles(id); // 404 first
        requireMayManage(actor, id);
        Map<String, Roles.Role> all = rolesById();
        Set<String> currentIds = new HashSet<>();
        for (Roles.Role r : current) {
            currentIds.add(r.id());
        }
        Set<String> wantedIds = new LinkedHashSet<>(wanted);
        Grants mine = grants(actor.userId());
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String rid : wantedIds) {
            Roles.Role role = all.get(rid);
            if (role == null) {
                throw new HttpError(400, "unknown_role", "No such role: " + rid);
            }
            if (!currentIds.contains(rid)) {
                requireMayHandOut(mine, role);
                added.add(role.name());
            }
        }
        for (Roles.Role role : current) {
            if (!wantedIds.contains(role.id())) {
                requireMayHandOut(mine, role);
                removed.add(role.name());
            }
        }
        List<Roles.Role> after = roles.setUserRoles(id, wantedIds);
        if (!added.isEmpty() || !removed.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("added", added);
            details.put("removed", removed);
            accounts.audit(actor.userId(), request.clientIp(), AUDIT_USER_ROLES, id, details);
        }
        send(response, 200, Map.of("items", rolesJson(after)));
    }

    // ------------------------------------------------------------- roles

    private void listRoles(Request request, Response response) throws Exception {
        List<Map<String, Object>> perms = new ArrayList<>();
        for (Permission p : Permission.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.wireName());
            m.put("description", p.description());
            if (p.feature() != null) {
                m.put("feature", p.feature().setting().name());
                m.put("featureEnabled", permissions.enabled(p.feature()));
            }
            perms.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rolesJson(roles.list()));
        out.put("permissions", perms);
        send(response, 200, out);
    }

    private void createRole(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        JsonObject body = request.readJson(JsonObject.class);
        Set<Permission> perms = body.has("permissions") && !body.get("permissions").isJsonNull()
                ? Roles.parsePermissions(strings(body, "permissions")) : EnumSet.noneOf(Permission.class);
        boolean isDefault = bool(body, "isDefault", false);
        requireHolds(grants(actor.userId()), perms);
        Roles.Role role = roles.create(string(body, "name"), perms, isDefault);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", role.name());
        details.put("permissions", wireNames(role.permissions()));
        details.put("isDefault", role.isDefault());
        accounts.audit(actor.userId(), request.clientIp(), AUDIT_ROLE_CREATED, role.id(), details);
        send(response, 201, roleJson(role));
    }

    private void getRole(Request request, Response response) throws Exception {
        send(response, 200, roleJson(roles.get(request.pathParam("id"))));
    }

    private void updateRole(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        JsonObject body = request.readJson(JsonObject.class);
        for (String key : body.keySet()) {
            if (!key.equals("name") && !key.equals("isDefault")) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
        Roles.Role role = roles.get(id);
        Map<String, Object> details = new LinkedHashMap<>();
        if (body.has("name")) {
            String before = role.name();
            role = roles.rename(id, string(body, "name"));
            if (!before.equals(role.name())) {
                details.put("name", role.name());
                details.put("previousName", before);
            }
        }
        if (body.has("isDefault")) {
            boolean wanted = bool(body, "isDefault", false);
            if (wanted != role.isDefault()) {
                if (!role.isAdmin()) {
                    requireHolds(grants(actor.userId()), role.permissions());
                }
                role = roles.setDefault(id, wanted);
                details.put("isDefault", role.isDefault());
            } else if (role.isAdmin()) {
                roles.setDefault(id, wanted); // 409 role_immutable, as for any admin change
            }
        }
        if (!details.isEmpty()) {
            accounts.audit(actor.userId(), request.clientIp(), AUDIT_ROLE_UPDATED, id, details);
        }
        send(response, 200, roleJson(role));
    }

    private void deleteRole(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        Roles.Role role = roles.get(id);
        if (!role.isAdmin()) {
            requireHolds(grants(actor.userId()), role.permissions());
        }
        roles.delete(id);
        accounts.audit(actor.userId(), request.clientIp(), AUDIT_ROLE_DELETED, id, Map.of("name", role.name()));
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void getRolePermissions(Request request, Response response) throws Exception {
        send(response, 200, Map.of("permissions", wireNames(roles.get(request.pathParam("id")).permissions())));
    }

    private void setRolePermissions(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        JsonObject body = request.readJson(JsonObject.class);
        Set<Permission> wanted = Roles.parsePermissions(strings(body, "permissions"));
        Roles.Role role = roles.get(id);
        Set<Permission> before = role.permissions().isEmpty() ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(role.permissions());
        Set<Permission> added = EnumSet.copyOf(wanted);
        added.removeAll(before);
        Set<Permission> removed = EnumSet.copyOf(before);
        removed.removeAll(wanted);
        if (!role.isAdmin()) {
            Set<Permission> changed = EnumSet.copyOf(added);
            changed.addAll(removed);
            requireHolds(grants(actor.userId()), changed);
        }
        role = roles.setPermissions(id, wanted);
        if (!added.isEmpty() || !removed.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("added", wireNames(added));
            details.put("removed", wireNames(removed));
            accounts.audit(actor.userId(), request.clientIp(), AUDIT_ROLE_PERMISSIONS, id, details);
        }
        send(response, 200, roleJson(role));
    }

    // ------------------------------------------------------------- settings

    private void getSettings(Request request, Response response) throws Exception {
        send(response, 200, settingsJson());
    }

    private void patchSettings(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        JsonObject body = request.readJson(JsonObject.class);
        for (String key : body.keySet()) {
            if (!Settings.KEYS.containsKey(key)) {
                throw new HttpError(400, "unknown_setting", "Unknown setting: " + key);
            }
        }
        Map<String, JsonElement> set = new LinkedHashMap<>();
        List<String> reset = new ArrayList<>();
        List<String> adjusted;
        try {
            for (Map.Entry<String, JsonElement> e : body.entrySet()) {
                if (!e.getValue().isJsonNull()) {
                    SettingKey<?> key = Settings.KEYS.get(e.getKey());
                    settingCheck.check(key, key.decode(e.getValue()));
                }
            }
            adjusted = coupleUploads(body);
            db.write(c -> {
                for (Map.Entry<String, JsonElement> e : body.entrySet()) {
                    SettingKey<?> key = Settings.KEYS.get(e.getKey());
                    if (e.getValue().isJsonNull()) {
                        settings.reset(key);
                        reset.add(key.name());
                    } else {
                        settings.setJson(key.name(), e.getValue().toString());
                        set.put(key.name(), e.getValue());
                    }
                }
                return null;
            });
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "invalid_setting", e.getMessage());
        }
        if (!body.keySet().isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (!set.isEmpty()) {
                details.put("set", set);
            }
            if (!reset.isEmpty()) {
                details.put("reset", reset);
            }
            accounts.audit(actor.userId(), request.clientIp(), AUDIT_SETTINGS, null, details);
            settingsListener.run();
        }
        Map<String, Object> out = new LinkedHashMap<>(settingsJson());
        if (!adjusted.isEmpty()) {
            out.put("adjusted", adjusted);
        }
        send(response, 200, out);
    }

    /**
     * Uploads need library requests ({@link Settings#COUPLED}): if the patch would leave an
     * upload switch on and its library-request switch off, adds the companion change to
     * {@code body} (turning the upload switch on turns library requests on; turning library
     * requests off turns uploads off).
     *
     * @return the settings added that way
     * @throws HttpError {@code 400 invalid_setting} for a patch turning both the wrong way at once
     */
    private List<String> coupleUploads(JsonObject body) throws SQLException {
        List<String> adjusted = new ArrayList<>();
        Map<SettingKey<?>, Object> current = settings.all();
        for (Map.Entry<SettingKey<Boolean>, SettingKey<Boolean>> c : Settings.COUPLED.entrySet()) {
            SettingKey<Boolean> upload = c.getKey();
            SettingKey<Boolean> library = c.getValue();
            boolean uploadWas = Boolean.TRUE.equals(current.get(upload));
            boolean libraryWas = Boolean.TRUE.equals(current.get(library));
            if (!after(body, upload, uploadWas) || after(body, library, libraryWas)) {
                continue;
            }
            boolean uploadTurnedOn = !uploadWas;
            if (uploadTurnedOn && libraryWas) {
                throw new HttpError(400, "invalid_setting", upload.name() + ": uploads need " + library.name()
                        + " on; it can't be turned off at the same time");
            }
            SettingKey<Boolean> companion = uploadTurnedOn ? library : upload;
            body.addProperty(companion.name(), uploadTurnedOn);
            adjusted.add(companion.name());
        }
        return adjusted;
    }

    /** A boolean setting's value after {@code body} is applied ({@code null} = its default). */
    private boolean after(JsonObject body, SettingKey<Boolean> key, boolean current) {
        if (!body.has(key.name())) {
            return current;
        }
        JsonElement v = body.get(key.name());
        return v.isJsonNull() ? Boolean.TRUE.equals(settings.defaultValue(key)) : key.decode(v);
    }

    private void getEncoding(Request request, Response response) throws Exception {
        OutputEncoding target;
        try {
            String profileParam = emptyToNull(request.query("profile"));
            OutputProfile profile = profileParam == null ? settings.outputProfile()
                    : Settings.OUTPUT_PROFILE.decode(new JsonPrimitive(profileParam));
            String bitrateParam = emptyToNull(request.query("videoBitrateKbps"));
            Integer bitrate;
            if (bitrateParam == null) {
                bitrate = null;
            } else {
                long n;
                try {
                    n = Long.parseLong(bitrateParam.strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("output.videoBitrateKbps: expected a whole number, got "
                            + bitrateParam);
                }
                bitrate = Settings.OUTPUT_VIDEO_BITRATE_KBPS.decode(new JsonPrimitive(n));
            }
            target = Settings.encoding(profile, bitrate);
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "invalid_setting", e.getMessage());
        }
        EncodingInfo info = encodingInfo;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", (info == null ? settings.outputEncoding() : info.current()).id());
        out.put("encoding", target.id());
        out.put("reencodeItems", info == null ? 0 : info.reencodeCount(target));
        send(response, 200, out);
    }

    private void getStorage(Request request, Response response) throws Exception {
        DiskSpace.Status s = diskSpace.status();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.freeBytes() >= 0) {
            out.put("freeBytes", s.freeBytes());
            out.put("totalBytes", s.totalBytes());
        }
        out.put("minFreeBytes", s.minFreeBytes());
        out.put("low", s.low());
        send(response, 200, out);
    }

    private Map<String, Object> settingsJson() throws SQLException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<SettingKey<?>, Object> e : settings.all().entrySet()) {
            SettingKey<?> key = e.getKey();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key.name());
            m.put("group", group(key.name()));
            m.put("type", type(key));
            m.put("value", encode(key, e.getValue()));
            m.put("default", encode(key, settings.defaultValue(key)));
            if (key.type().isEnum()) {
                List<JsonElement> options = new ArrayList<>();
                for (Object constant : key.type().getEnumConstants()) {
                    options.add(encode(key, constant));
                }
                m.put("options", options);
            }
            items.add(m);
        }
        return Map.of("items", items);
    }

    private static <T> JsonElement encode(SettingKey<T> key, Object value) {
        return value == null ? null : key.encode(key.type().cast(value));
    }

    private static String type(SettingKey<?> key) {
        Class<?> t = key.type();
        if (t == Boolean.class) {
            return "boolean";
        }
        if (t == Integer.class) {
            return "integer";
        }
        if (t == Long.class) {
            return "bytes";
        }
        if (t == String.class) {
            return "string";
        }
        if (t == UploadQuota.class) {
            return "uploadQuota";
        }
        if (t == StringList.class) {
            return "stringList";
        }
        if (t.isEnum()) {
            return "enum";
        }
        throw new IllegalStateException("No API type for setting " + key.name());
    }

    /** The admin UI group of a setting ({@code docs/auth.md#server-settings}). */
    static String group(String name) {
        if (name.startsWith("registration.")) {
            return "accounts";
        }
        if (name.startsWith("guests.")) {
            return "guests";
        }
        if (name.startsWith("retention.")) {
            return "retention";
        }
        if (name.startsWith("storage.")) {
            return "storage";
        }
        if (name.startsWith("output.")) {
            return "output";
        }
        if (name.startsWith("outputs.")) {
            return "outputs";
        }
        if (name.startsWith("overlay.")) {
            return "overlay";
        }
        if (name.startsWith("cards.") || name.startsWith("fonts.")) {
            return "fonts";
        }
        return "library";
    }

    // ------------------------------------------------------------- escalation checks

    /** What a user holds through their roles (ignoring the disabled flag and feature switches). */
    private record Grants(boolean admin, Set<Permission> permissions) {
    }

    private Grants grants(String userId) throws SQLException {
        Set<Permission> perms = EnumSet.noneOf(Permission.class);
        boolean admin = false;
        for (Roles.Role r : roles.userRoles(userId)) {
            admin |= r.isAdmin();
            perms.addAll(r.permissions());
        }
        return new Grants(admin, perms);
    }

    private static void requireHolds(Grants mine, Collection<Permission> perms) {
        if (mine.admin()) {
            return;
        }
        for (Permission p : perms) {
            if (!mine.permissions().contains(p)) {
                throw HttpError.forbidden("You can't hand out a permission you don't hold: " + p.wireName());
            }
        }
    }

    private static void requireMayHandOut(Grants mine, Roles.Role role) {
        if (role.isAdmin() && !mine.admin()) {
            throw HttpError.forbidden("Only administrators can grant or take away the admin role");
        }
        requireHolds(mine, role.permissions());
    }

    /** The actor may manage {@code targetId} unless the target holds more than the actor. */
    private void requireMayManage(Actor.User actor, String targetId) throws SQLException {
        if (targetId.equals(actor.userId())) {
            return;
        }
        Grants mine = grants(actor.userId());
        if (mine.admin()) {
            return;
        }
        Grants theirs = grants(targetId);
        if (theirs.admin() || !mine.permissions().containsAll(theirs.permissions())) {
            throw HttpError.forbidden("This user holds permissions you don't have");
        }
    }

    // ------------------------------------------------------------- helpers

    private static Actor.User actor(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw Permissions.notAuthenticated(); // unreachable behind the permission hooks
    }

    private Map<String, Roles.Role> rolesById() throws SQLException {
        Map<String, Roles.Role> out = new LinkedHashMap<>();
        for (Roles.Role r : roles.list()) {
            out.put(r.id(), r);
        }
        return out;
    }

    private static void send(Response response, int status, Object body) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(status, body);
    }

    static Map<String, Object> userJson(Users.UserView u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("displayName", u.displayName());
        m.put("email", u.email());
        m.put("createdAt", u.createdAt());
        m.put("disabled", u.disabled());
        m.put("hasPassword", u.hasPassword());
        m.put("mustChangePassword", u.mustChangePassword());
        List<Map<String, Object>> r = new ArrayList<>();
        for (Users.RoleRef ref : u.roles()) {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("id", ref.id());
            role.put("name", ref.name());
            r.add(role);
        }
        m.put("roles", r);
        return m;
    }

    static Map<String, Object> roleJson(Roles.Role r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("builtin", r.builtin());
        m.put("isDefault", r.isDefault());
        m.put("editable", r.editable());
        m.put("permissions", wireNames(r.permissions()));
        return m;
    }

    private static List<Map<String, Object>> rolesJson(List<Roles.Role> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Roles.Role r : list) {
            out.add(roleJson(r));
        }
        return out;
    }

    private static List<String> wireNames(Collection<Permission> perms) {
        List<String> out = new ArrayList<>();
        for (Permission p : perms) {
            out.add(p.wireName());
        }
        return out;
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

    /** A string field, {@code null} if absent or null; {@code 400 invalid_json} for another type. */
    private static String string(JsonObject body, String name) {
        JsonElement e = body.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new HttpError(400, "invalid_json", name + " must be a string");
        }
        return e.getAsString();
    }

    private static boolean bool(JsonObject body, String name, boolean def) {
        JsonElement e = body.get(name);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new HttpError(400, "invalid_json", name + " must be true or false");
        }
        return e.getAsBoolean();
    }

    /** A required array of strings; {@code 400 invalid_json} otherwise. */
    private static List<String> strings(JsonObject body, String name) {
        JsonElement e = body.get(name);
        if (e == null || !e.isJsonArray()) {
            throw new HttpError(400, "invalid_json", name + " must be an array of strings");
        }
        JsonArray array = e.getAsJsonArray();
        List<String> out = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new HttpError(400, "invalid_json", name + " must be an array of strings");
            }
            out.add(item.getAsString());
        }
        return out;
    }
}
