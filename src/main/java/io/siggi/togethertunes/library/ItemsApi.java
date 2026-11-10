package io.siggi.togethertunes.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Feature;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/**
 * Items by id ({@code docs/api.md#items-libraries-playlists-sharing}, {@link Items}). Responses
 * are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/items/{id}} (any actor, by direct link) → {@code {"id", "kind", "title",
 *       "artist"?, "album"?, "durationMs"?, "source", "youtubeId"?, "status", "presentAsAudio",
 *       "addedAt", "error"?, "you": {"inLibrary", "canEdit", "canPurge", "canRetry"}}}. {@code error} only for
 *       a failed item. Nothing about who added the item or whose libraries it is in.
 *       {@code 404 item_not_found}.</li>
 *   <li>{@code PATCH /api/items/{id}} {@code {"title"?, "artist"?, "album"?, "presentAsAudio"?}} →
 *       the item page. The adder while it is in their library, or {@code library.editAny}
 *       ({@code 401 not_authenticated} for guests and anonymous callers, {@code 403 forbidden} for
 *       other users). Values are
 *       cleaned like upload metadata (control characters and runs of whitespace become one space,
 *       trimmed); {@code artist}/{@code album} {@code null} or blank clears them. {@code 400
 *       invalid_title} (missing, blank or not a string), {@code invalid_artist},
 *       {@code invalid_album}, {@code invalid_present_as_audio}, {@code metadata_too_long} (over
 *       {@value UploadMetadata#MAX_CHARS} characters), {@code unknown_field}, {@code not_a_video}
 *       ({@code presentAsAudio: true} on an audio item).</li>
 *   <li>{@code DELETE /api/items/{id}} → {@code 204}: purge now. {@code library.purge}.
 *       {@code 404 item_not_found}, {@code 409 item_busy} (an ingest job is running).</li>
 *   <li>{@code GET /api/items/{id}/job} (any actor) → the latest ingest job {@code {"id", "kind",
 *       "state", "progress", "attempts", "createdAt", "startedAt"?, "finishedAt"?, "log"?}};
 *       {@code log} only for callers who may edit the item. {@code 404 item_not_found},
 *       {@code 404 job_not_found}.</li>
 *   <li>{@code POST /api/items/{id}/retry} → {@code 202 {"itemId", "jobId", "status": "pending"}}:
 *       fetch a failed YouTube item again now ({@link YoutubeImports#retry}). Users who may edit the
 *       item, or hold {@code admin.jobs} ({@code 401 not_authenticated} for guests and anonymous
 *       callers, {@code 403 forbidden} for other users); {@code 403 feature_disabled} while
 *       {@code ytdlp.enabled} is off; and {@link YoutubeImports#retry}'s errors.</li>
 * </ul>
 */
public final class ItemsApi {
    private static final Set<String> FIELDS = Set.of("title", "artist", "album", "presentAsAudio");

    private final Items items;
    private final Permissions permissions;
    private final YoutubeImports youtube;

    /** Without retries ({@code POST …/retry} answers {@code 404}). */
    public ItemsApi(Items items, Permissions permissions) {
        this(items, permissions, null);
    }

    /** @param youtube retries of failed YouTube items ({@code POST /api/items/{id}/retry}) */
    public ItemsApi(Items items, Permissions permissions, YoutubeImports youtube) {
        this.items = Objects.requireNonNull(items);
        this.permissions = Objects.requireNonNull(permissions);
        this.youtube = youtube;
    }

    public void register(Router router) {
        router.get("/api/items/{id}", this::get);
        router.patch("/api/items/{id}", this::update);
        router.delete("/api/items/{id}", this::purge, permissions.hook(Permission.LIBRARY_PURGE));
        router.get("/api/items/{id}/job", this::job);
        if (youtube != null) {
            router.post("/api/items/{id}/retry", this::retry);
        }
    }

    private Items.Rights rights(Actor actor) throws SQLException {
        if (!(actor instanceof Actor.User)) {
            return new Items.Rights(false, false);
        }
        return new Items.Rights(permissions.allowed(actor, Permission.LIBRARY_EDIT_ANY),
                permissions.allowed(actor, Permission.LIBRARY_PURGE), permissions.allowed(actor, Permission.ADMIN_JOBS),
                youtube != null && permissions.enabled(Feature.YOUTUBE));
    }

    private void retry(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        if (!(actor instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        permissions.requireFeature(Feature.YOUTUBE);
        String itemId = request.pathParam("id");
        items.requireRetry(user, itemId, rights(user));
        YoutubeImports.Retried retried = youtube.retry(itemId, user, request.clientIp(), null);
        response.header("Cache-Control", "no-store");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("itemId", retried.itemId());
        out.put("jobId", retried.jobId());
        out.put("status", retried.status());
        response.json(202, out);
    }

    private void get(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        Items.ItemPage page = items.get(actor, request.pathParam("id"), rights(actor));
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void job(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        Items.JobView job = items.job(actor, request.pathParam("id"), rights(actor));
        response.header("Cache-Control", "no-store");
        response.json(job);
    }

    private void update(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        if (!(actor instanceof Actor.User user)) {
            // Guests have no library, so they can never edit (like a permission-gated route).
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        Items.Edit edit = parse(request.readJson(JsonObject.class));
        Items.ItemPage page = items.update(user, request.pathParam("id"), edit, rights(user), request.clientIp());
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void purge(Request request, Response response) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the permission hook let only users through
        items.purge(request.pathParam("id"), items.auditedBy(user.userId(), request.clientIp()));
        response.empty(204);
    }

    /** The edit in a PATCH body; see the class comment for the rules. */
    static Items.Edit parse(JsonObject body) {
        if (body == null) {
            return new Items.Edit(null, null, false, null, false, null);
        }
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
        String title = null;
        if (body.has("title")) {
            title = text(body.get("title"), "invalid_title");
            if (title == null) {
                throw new HttpError(400, "invalid_title", "The title can't be empty");
            }
        }
        String artist = body.has("artist") ? text(body.get("artist"), "invalid_artist") : null;
        String album = body.has("album") ? text(body.get("album"), "invalid_album") : null;
        Boolean presentAsAudio = null;
        if (body.has("presentAsAudio")) {
            JsonElement e = body.get("presentAsAudio");
            if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
                throw new HttpError(400, "invalid_present_as_audio", "presentAsAudio must be true or false");
            }
            presentAsAudio = p.getAsBoolean();
        }
        return new Items.Edit(title, artist, body.has("artist") && artist == null, album,
                body.has("album") && album == null, presentAsAudio);
    }

    /** A cleaned string, or null for JSON null / blank. */
    private static String text(JsonElement e, String code) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new HttpError(400, code, "Must be a string");
        }
        String raw = p.getAsString();
        if (raw.codePointCount(0, raw.length()) > UploadMetadata.MAX_CHARS) {
            throw new HttpError(400, "metadata_too_long", "At most " + UploadMetadata.MAX_CHARS + " characters");
        }
        return UploadMetadata.clean(raw);
    }
}
