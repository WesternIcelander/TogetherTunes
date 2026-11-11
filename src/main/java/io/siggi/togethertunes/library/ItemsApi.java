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
 *   <li>{@code GET /api/items/search?q=&kind=&cursor=&limit=} (signed-in users; {@code 401
 *       not_authenticated} otherwise) → {@code {"items": [{"item", "inYourLibrary"}], "nextCursor"?}}:
 *       every item on the server that isn't purged ({@link ItemSearch}, the Library page's "Search the
 *       whole server"), newest item first. Nothing about whose libraries hold an item. {@code limit}
 *       1..{@value ItemSearch#LIST_MAX}, default {@value ItemSearch#LIST_DEFAULT}; {@code 400
 *       invalid_query} / {@code invalid_kind} / {@code invalid_cursor} / {@code invalid_limit}.</li>
 *   <li>{@code GET /api/items/{id}} (any actor, by direct link) → {@code {"id", "kind", "title",
 *       "artist"?, "album"?, "durationMs"?, "source", "youtubeId"?, "status", "presentAsAudio",
 *       "mediaType", "show"?: {"name", "season"?, "episode"},
 *       "addedAt", "error"?, "mediaVersion", "hasCover", "you": {"inLibrary", "canEdit", "canPurge", "canRetry", "canPin", "canDeleteRecord"}, "pinned"?: {"pinnedAt"},
 *       "undo"?: {"changedAt"}}}. {@code hasCover}: the current version has a {@code cover.jpg}. {@code error} only for a failed item; {@code undo} only for callers
 *       who may edit the item, while a change can be undone. Nothing about who added the item or whose libraries it is in.
 *       {@code 404 item_not_found}.</li>
 *   <li>{@code PATCH /api/items/{id}} {@code {"title"?, "artist"?, "album"?, "mediaType"?, "show"?}} →
 *       the item page. The adder while it is in their library, or {@code library.editAny}
 *       ({@code 401 not_authenticated} for guests and anonymous callers, {@code 403 forbidden} for
 *       other users). Values are
 *       cleaned like upload metadata (control characters and runs of whitespace become one space,
 *       trimmed); {@code artist}/{@code album} {@code null} or blank clears them. {@code 400
 *       invalid_title} (missing, blank or not a string), {@code invalid_artist},
 *       {@code invalid_album}, {@code invalid_media_type}, {@code invalid_show} ({@code show}:
 *       {@code {"name", "season"?, "episode"}}, name required, numbers whole and ≥ 0),
 *       {@code metadata_too_long} (over {@value UploadMetadata#MAX_CHARS} characters),
 *       {@code unknown_field}, {@code not_a_video} (a type other than {@code audio} on an audio
 *       item), {@code show_required} (type {@code show} without show details), {@code not_a_show}
 *       (show details for another type). Show details are cleared when the type changes away from
 *       {@code show}. {@code presentAsAudio} is derived: a video item of type {@code audio}.</li>
 *   <li>{@code POST /api/items/{id}/undo} (no body) → the item page: go back to the previous media
 *       version ({@code docs/library-and-ingest.md#undo}, {@link Items#undo}). Users who may edit
 *       the item ({@code 401 not_authenticated} for guests and anonymous callers, {@code 403
 *       forbidden} for other users); {@code 404 item_not_found}, {@code 409 nothing_to_undo},
 *       {@code 409 item_busy}.</li>
 *   <li>{@code POST /api/items/{id}/trim} {@code {"videoStartMs", "videoEndMs"?}} and/or
 *       {@code {"audioStartMs", "audioEndMs"?}} → {@code 202}, the item page: trim an item
 *       ({@code docs/library-and-ingest.md#trim}, {@code #audio-trim}, {@link Items#trim}): queues a
 *       {@code trim} job. Times in ms on the original's timeline; an end null or absent (or at/after
 *       the original's end) = to the end; {@code audioStartMs: null} removes the audio trim. The video
 *       trim is for video items only ({@code 409 not_a_video}). Users who may edit the item ({@code 401
 *       not_authenticated}, {@code 403 forbidden}); {@code 404 item_not_found}; {@code 400
 *       invalid_trim} / {@code unknown_field}; {@code 409 not_a_video}, {@code item_not_ready},
 *       {@code no_original}, {@code no_change}, {@code item_busy}. The item page carries the current
 *       trims as {@code trim: {"videoStartMs", "videoEndMs"?, "originalMs", "audioStartMs"?, "audioEndMs"?,
 *       "audioOnlyMs"?, "firstGopFrames"?}} for those who may trim it ({@code firstGopFrames}: a video's
 *       current encode's first GOP, 1..60).</li>
 *   <li>{@code PUT} / {@code DELETE /api/items/{id}/pin} (no body) → the item page: pin or unpin
 *       the item ({@code docs/media-types.md#views-auto-delete-and-pins}, {@link Items#pin}): a
 *       pinned item is never deleted automatically. {@code library.pin} (admins hold every
 *       permission; {@code 401 not_authenticated} for guests and anonymous callers, {@code 403
 *       forbidden} for other users); idempotent; {@code 404 item_not_found}. The item page carries
 *       {@code pinned: {"pinnedAt"}} while pinned (for every caller; not who pinned it) and
 *       {@code you.canPin}. Views ({@code POST /api/items/{id}/view}) are {@link MediaApi}'s.</li>
 *   <li>{@code DELETE /api/items/{id}} → {@code 204}: purge now. {@code library.purge}.
 *       {@code 404 item_not_found}, {@code 409 item_busy} (an ingest job is running). With
 *       {@code retention.purgedEntryDays} 0 the item's library and playlist entries go too.</li>
 *   <li>{@code DELETE /api/admin/items/{id}} → {@code 204}: Delete record ({@link Items#deleteRecord}):
 *       the item row and everything referencing it; audit {@code item.deleted}. Admins only (the
 *       admin role; {@code 401 not_authenticated}, {@code 403 forbidden}). {@code 404
 *       item_not_found}, {@code 409 item_busy}, {@code 409 item_in_room}.</li>
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
    private static final Set<String> FIELDS = Set.of("title", "artist", "album", "mediaType", "show");

    private final Items items;
    private final Permissions permissions;
    private final YoutubeImports youtube;
    private final ItemSearch search;

    /** Without retries ({@code POST …/retry} answers {@code 404}). */
    public ItemsApi(Items items, Permissions permissions) {
        this(items, permissions, null);
    }

    /** @param youtube retries of failed YouTube items ({@code POST /api/items/{id}/retry}) */
    public ItemsApi(Items items, Permissions permissions, YoutubeImports youtube) {
        this.items = Objects.requireNonNull(items);
        this.permissions = Objects.requireNonNull(permissions);
        this.youtube = youtube;
        this.search = new ItemSearch(items.database());
    }

    public void register(Router router) {
        router.get("/api/items/search", this::search);
        router.get("/api/items/{id}", this::get);
        router.patch("/api/items/{id}", this::update);
        router.delete("/api/items/{id}", this::purge, permissions.hook(Permission.LIBRARY_PURGE));
        router.delete("/api/admin/items/{id}", this::deleteRecord, permissions.adminHook());
        router.get("/api/items/{id}/job", this::job);
        router.post("/api/items/{id}/undo", this::undo);
        router.post("/api/items/{id}/trim", this::trim);
        router.put("/api/items/{id}/pin", this::pin, permissions.hook(Permission.LIBRARY_PIN));
        router.delete("/api/items/{id}/pin", this::unpin, permissions.hook(Permission.LIBRARY_PIN));
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
                youtube != null && permissions.enabled(Feature.YOUTUBE),
                permissions.allowed(actor, Permission.LIBRARY_PIN), permissions.isAdmin(actor));
    }

    private void pin(Request request, Response response) throws Exception {
        setPinned(request, response, true);
    }

    private void unpin(Request request, Response response) throws Exception {
        setPinned(request, response, false);
    }

    private void setPinned(Request request, Response response, boolean pinned) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the permission hook let only users through
        Items.ItemPage page = items.pin(user, request.pathParam("id"), pinned, rights(user), request.clientIp());
        response.header("Cache-Control", "no-store");
        response.json(page);
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

    private void search(Request request, Response response) throws Exception {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        int limit = ItemSearch.LIST_DEFAULT;
        String param = request.query("limit");
        if (param != null && !param.isEmpty()) {
            try {
                limit = Integer.parseInt(param);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > ItemSearch.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + ItemSearch.LIST_MAX);
            }
        }
        ItemSearch.Page page = search.search(ItemSearch.Scope.server(), user.userId(), request.query("q"),
                emptyToNull(request.query("kind")), emptyToNull(request.query("type")),
                emptyToNull(request.query("cursor")), limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
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

    private void undo(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        if (!(actor instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        Items.ItemPage page = items.undo(user, request.pathParam("id"), rights(user), request.clientIp());
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void trim(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        if (!(actor instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        Items.Trim trim = parseTrim(request.readJson(JsonObject.class));
        Items.ItemPage page = items.trim(user, request.pathParam("id"), trim, rights(user), request.clientIp());
        response.header("Cache-Control", "no-store");
        response.json(202, page);
    }

    /**
     * The body of {@code POST /api/items/{id}/trim}: the video trim {@code {"videoStartMs",
     * "videoEndMs"?}}, the audio trim {@code {"audioStartMs", "audioEndMs"?}} ({@code audioStartMs}
     * null: remove it), or both.
     */
    static Items.Trim parseTrim(JsonObject body) {
        if (body == null) {
            throw new HttpError(400, "invalid_trim", "Expected {\"videoStartMs\", \"videoEndMs\"} and/or"
                    + " {\"audioStartMs\", \"audioEndMs\"}");
        }
        for (String key : body.keySet()) {
            if (!key.equals("videoStartMs") && !key.equals("videoEndMs") && !key.equals("audioStartMs")
                    && !key.equals("audioEndMs")) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
        boolean video = body.has("videoStartMs") || body.has("videoEndMs");
        boolean audio = body.has("audioStartMs") || body.has("audioEndMs");
        if (!video && !audio) {
            throw new HttpError(400, "invalid_trim", "Expected videoStartMs or audioStartMs");
        }
        long start = 0;
        Long end = null;
        if (video) {
            Long s = millis(body.get("videoStartMs"));
            if (s == null) {
                throw new HttpError(400, "invalid_trim", "videoStartMs must be a whole number of milliseconds ≥ 0");
            }
            start = s;
            end = end(body, "videoEndMs");
        }
        Long audioStart = null;
        Long audioEnd = null;
        if (audio) {
            JsonElement a = body.get("audioStartMs");
            if (a == null) {
                throw new HttpError(400, "invalid_trim", "audioStartMs must be given with audioEndMs");
            }
            if (!a.isJsonNull()) {
                audioStart = millis(a);
                if (audioStart == null) {
                    throw new HttpError(400, "invalid_trim",
                            "audioStartMs must be a whole number of milliseconds ≥ 0, or null to remove the audio trim");
                }
                audioEnd = end(body, "audioEndMs");
            } else if (body.has("audioEndMs") && !body.get("audioEndMs").isJsonNull()) {
                throw new HttpError(400, "invalid_trim", "audioEndMs needs an audioStartMs");
            }
        }
        return new Items.Trim(video, start, end, audio, audioStart, audioEnd);
    }

    private static Long end(JsonObject body, String key) {
        Long end = millis(body.get(key));
        if (end == null && body.has(key) && !body.get(key).isJsonNull()) {
            throw new HttpError(400, "invalid_trim", key + " must be a whole number of milliseconds, or null");
        }
        return end;
    }

    /** A whole, non-negative number of milliseconds (at most 30 days), or null. */
    private static Long millis(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
            return null;
        }
        double d = p.getAsDouble();
        if (!Double.isFinite(d) || d < 0 || d > 30L * 24 * 3_600_000 || d != Math.rint(d)) {
            return null;
        }
        return (long) d;
    }

    private void purge(Request request, Response response) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the permission hook let only users through
        items.purge(request.pathParam("id"), items.auditedBy(user.userId(), request.clientIp()));
        response.empty(204);
    }

    private void deleteRecord(Request request, Response response) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the admin hook let only admins through
        items.deleteRecord(user, request.pathParam("id"), request.clientIp());
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    /** The edit in a PATCH body; see the class comment for the rules. */
    static Items.Edit parse(JsonObject body) {
        if (body == null) {
            return new Items.Edit(null, null, false, null, false);
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
        MediaType mediaType = null;
        if (body.has("mediaType")) {
            JsonElement e = body.get("mediaType");
            mediaType = e instanceof JsonPrimitive p && p.isString() ? MediaType.of(p.getAsString()) : null;
            if (mediaType == null) {
                throw new HttpError(400, "invalid_media_type",
                        "mediaType must be audio, uncategorized, music_video, movie or show");
            }
        }
        MediaType.Show show = body.has("show") ? show(body.get("show")) : null;
        return new Items.Edit(title, artist, body.has("artist") && artist == null, album,
                body.has("album") && album == null, mediaType, show);
    }

    /**
     * A show's details: {@code {"name", "season"?, "episode"}} (name required, numbers whole and
     * ≥ 0; season null or absent for none). {@code 400 invalid_show} otherwise.
     */
    static MediaType.Show show(JsonElement e) {
        if (!(e instanceof JsonObject o)) {
            throw new HttpError(400, "invalid_show", "show must be {name, season?, episode}");
        }
        for (String key : o.keySet()) {
            if (!key.equals("name") && !key.equals("season") && !key.equals("episode")) {
                throw new HttpError(400, "unknown_field", "Unknown field: show." + key);
            }
        }
        String name = text(o.get("name"), "invalid_show");
        if (name == null) {
            throw new HttpError(400, "invalid_show", "A show episode needs the show's name");
        }
        Integer season = null;
        JsonElement s = o.get("season");
        if (s != null && !s.isJsonNull()) {
            season = number(s);
            if (season == null) {
                throw new HttpError(400, "invalid_show", "The season must be a whole number ≥ 0, or null");
            }
        }
        Integer episode = number(o.get("episode"));
        if (episode == null) {
            throw new HttpError(400, "invalid_show", "The episode must be a whole number ≥ 0");
        }
        return new MediaType.Show(name, season, episode);
    }

    /** A whole number 0..{@link MediaType.Show#MAX_NUMBER}, or null. */
    private static Integer number(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
            return null;
        }
        double d = p.getAsDouble();
        if (!Double.isFinite(d) || d < 0 || d > MediaType.Show.MAX_NUMBER || d != Math.rint(d)) {
            return null;
        }
        return (int) d;
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
