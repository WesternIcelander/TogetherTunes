package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The library import API ({@code docs/api.md#admin}, {@code docs/library-and-ingest.md#library-import}),
 * {@code admin.users} only:
 * <ul>
 *   <li>{@code GET /api/admin/users/{id}/library-import} → {@code {"import": import | null,
 *       "otherImport"?: {userId, username, displayName, state}, "pathOption": {enabled, allowedPaths}}}</li>
 *   <li>{@code POST /api/admin/users/{id}/library-import} {@code {"source": {"path"} | {"uploadId"},
 *       "smartPlaylists"?}} → {@code 201 import} (state {@code checking})</li>
 *   <li>{@code POST …/start}, {@code POST …/cancel} → {@code import}</li>
 *   <li>{@code GET …/problems?cursor=} → {@code {"items", "nextCursor"?}}</li>
 * </ul>
 * {@code import} is {@code {"id", "userId", "state", "source", "smartPlaylists", "counts",
 * "problems" (the first 20), "error"?, "createdAt", "updatedAt", "startedAt"?, "finishedAt"?}};
 * never an IP address.
 */
public final class LibraryImportApi {
    private final LibraryImports imports;
    private final Permissions permissions;

    public LibraryImportApi(LibraryImports imports, Permissions permissions) {
        this.imports = Objects.requireNonNull(imports);
        this.permissions = Objects.requireNonNull(permissions);
    }

    public void register(Router router) {
        var perm = permissions.hook(Permission.ADMIN_USERS);
        String base = "/api/admin/users/{id}/library-import";
        router.get(base, this::get, perm);
        router.post(base, this::check, perm);
        router.post(base + "/start", this::start, perm);
        router.post(base + "/cancel", this::cancel, perm);
        router.get(base + "/problems", this::problems, perm);
    }

    private static Actor.User actor(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first"); // unreachable behind the permission hook
    }

    private void get(Request request, Response response) throws Exception {
        String userId = request.pathParam("id");
        imports.requireUser(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        LibraryImports.Import i = imports.latest(userId);
        out.put("import", i == null ? null : json(i));
        LibraryImports.Running other = imports.runningForOther(userId);
        if (other != null) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("userId", other.userId());
            o.put("username", other.username());
            o.put("displayName", other.displayName());
            o.put("state", other.state());
            out.put("otherImport", o);
        }
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("enabled", !imports.allowedPaths().isEmpty());
        List<String> allowed = new ArrayList<>();
        for (Path p : imports.allowedPaths()) {
            allowed.add(p.toString());
        }
        path.put("allowedPaths", allowed);
        out.put("pathOption", path);
        send(response, 200, out);
    }

    private void check(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        JsonObject body = request.readJson(JsonObject.class);
        if (body == null || !(body.get("source") instanceof JsonObject source)) {
            throw new HttpError(400, "invalid_source", "source needs a path or an uploadId");
        }
        boolean smart = false;
        JsonElement s = body.get("smartPlaylists");
        if (s != null && !s.isJsonNull()) {
            if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isBoolean()) {
                throw new HttpError(400, "invalid_smart_playlists", "smartPlaylists must be true or false");
            }
            smart = s.getAsBoolean();
        }
        LibraryImports.Import i = imports.check(actor.userId(), request.pathParam("id"), source, smart);
        send(response, 201, json(i));
    }

    private void start(Request request, Response response) throws Exception {
        send(response, 200, json(imports.start(actor(request).userId(), request.pathParam("id"))));
    }

    private void cancel(Request request, Response response) throws Exception {
        send(response, 200, json(imports.cancel(actor(request).userId(), request.pathParam("id"))));
    }

    private void problems(Request request, Response response) throws Exception {
        Object[] page = imports.problems(request.pathParam("id"), request.query("cursor"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page[0]);
        if (page[1] != null) {
            out.put("nextCursor", page[1]);
        }
        send(response, 200, out);
    }

    static Map<String, Object> json(LibraryImports.Import i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", i.id());
        out.put("userId", i.userId());
        out.put("state", i.state());
        out.put("source", i.source());
        out.put("smartPlaylists", i.smartPlaylists());
        out.put("counts", i.counts());
        JsonArray first = new JsonArray();
        for (int k = 0; k < i.problems().size() && k < LibraryImports.FIRST_PROBLEMS; k++) {
            first.add(i.problems().get(k));
        }
        out.put("problems", first);
        if (i.error() != null) {
            out.put("error", i.error());
        }
        out.put("createdAt", i.createdAt());
        out.put("updatedAt", i.updatedAt());
        if (i.startedAt() != null) {
            out.put("startedAt", i.startedAt());
        }
        if (i.finishedAt() != null) {
            out.put("finishedAt", i.finishedAt());
        }
        return out;
    }

    private static void send(Response response, int status, Object body) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(status, body);
    }
}
