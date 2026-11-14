package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StreamBody;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code POST /api/imports/youtube} {@code {url, presentAsAudio?, addToLibrary?, mediaType?, show?,
 * playlistId?, beforeEntryId?}} → {@code 200 {itemId, cached, status, entry?}}
 * ({@code docs/api.md#adding-media}); the work is in
 * {@link YoutubeImports}. Going into the caller's library, an {@code uncategorized} video needs
 * {@code mediaType} (and {@code show}): without it the import still happens but the answer is
 * {@code 409 needs_classification} with {@code itemId} and {@code suggested} ({@link Classify};
 * the client then uses {@code PUT /api/library/{itemId}}). {@code cached} is true when the item was already ready (nothing to
 * download); {@code status} is the item's status ({@code ready}, {@code pending} or
 * {@code processing}). Needs {@code library.youtube}, which the {@code ytdlp.enabled} switch gates
 * ({@code 403 feature_disabled} when it is off; {@link AddMediaPolicy#requireYoutube}). Guests get
 * {@code 401} here: their YouTube links are requests inside a room, made with the room's
 * {@code queue.add} (M3), which checks {@link AddMediaPolicy#requireYoutube} with the room.
 *
 * <p><b>Add from link</b> ({@code playlistId}, users only): the import goes into the caller's
 * library and then into that playlist of theirs, before {@code beforeEntryId} or at the end, as
 * {@code POST /api/playlists/{id}/entries} would add it ({@link Playlists#add}); {@code entry} is
 * the playlist entry. The playlist is checked first ({@code 404 playlist_not_found}, nothing
 * imported). After {@code 409 needs_classification} the item is imported but in neither; the
 * client finishes with {@code POST /api/playlists/{id}/entries {itemId, mediaType, show?}}, which
 * adds it to both. A playlist error after the import ({@code 409 playlist_full},
 * {@code before_entry_not_found}) leaves the item in the library.
 *
 * <p><b>Lookup stages:</b> with {@code Accept: application/x-ndjson}, a request that runs a
 * {@code yt-dlp -J} lookup is answered as a stream of JSON lines as soon as the first stage is
 * known: {@code {"type":"progress","stage","text"}} per stage ({@link YoutubeLookupStage}), then
 * {@code {"type":"result","itemId","cached","status"}} or
 * {@code {"type":"error","status","error":{"code","message"},"retryAfter"?}}. Without a lookup
 * (a known item) or when it fails before its first stage, the answer is the plain JSON one.
 */
public final class YoutubeImportsApi {
    private static final System.Logger LOG = System.getLogger(YoutubeImportsApi.class.getName());
    static final String NDJSON = "application/x-ndjson";
    private static final String NDJSON_TYPE = NDJSON + "; charset=utf-8";

    private final YoutubeImports imports;
    private final AddMediaPolicy policy;
    private final Playlists playlists;

    record ImportRequest(String url, Boolean presentAsAudio, Boolean addToLibrary,
                         com.google.gson.JsonElement mediaType, com.google.gson.JsonElement show,
                         String playlistId, String beforeEntryId) {
    }

    /** Without playlists: {@code playlistId} is refused ({@code 400 unknown_field}). */
    public YoutubeImportsApi(YoutubeImports imports, AddMediaPolicy policy) {
        this(imports, policy, null);
    }

    /** With {@code playlists}: "Add from link" into a playlist ({@code playlistId}). */
    public YoutubeImportsApi(YoutubeImports imports, AddMediaPolicy policy, Playlists playlists) {
        this.imports = Objects.requireNonNull(imports);
        this.policy = Objects.requireNonNull(policy);
        this.playlists = playlists;
    }

    public void register(Router router) {
        router.post("/api/imports/youtube", this::importUrl, policy.youtubeHook());
    }

    private void importUrl(Request request, Response response) throws Exception {
        ImportRequest body = request.readJson(ImportRequest.class);
        response.header("Cache-Control", "no-store");
        if (body == null || body.url() == null || body.url().isBlank()) {
            throw new HttpError(400, "invalid_youtube_url", "url is required");
        }
        Classify.Classification classification = Classify.of(body.mediaType(), body.show());
        Actor actor = ActorResolver.actor(request);
        Actor.User playlistOwner = playlistTarget(actor, body);
        if (!wantsStages(request.header("Accept"))) {
            response.json(run(actor, body, classification, playlistOwner, YoutubeLookupStage.Listener.NONE));
            return;
        }
        StageStream stages = new StageStream(response);
        Map<String, Object> out;
        try {
            out = run(actor, body, classification, playlistOwner, stages::stage);
        } catch (HttpError e) {
            if (!stages.started()) {
                throw e;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "error");
            line.put("status", e.status());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", e.code());
            error.put("message", e.getMessage() == null ? "" : e.getMessage());
            for (Map.Entry<String, Object> x : e.extra().entrySet()) {
                error.putIfAbsent(x.getKey(), x.getValue());
            }
            line.put("error", error);
            if (e.retryAfterSeconds() > 0) {
                line.put("retryAfter", e.retryAfterSeconds());
            }
            stages.line(line);
            return;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (!stages.started()) {
                throw e;
            }
            LOG.log(System.Logger.Level.ERROR, "YouTube import failed", e);
            stages.line(Map.of("type", "error", "status", 500,
                    "error", Map.of("code", "internal_error", "message", "Something went wrong")));
            return;
        }
        if (!stages.started()) {
            response.json(out);
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "result");
        line.putAll(out);
        stages.line(line);
    }

    /**
     * "Add from link" into a playlist: checks the request before anything is imported and returns
     * the playlist's owner, or {@code null} without a {@code playlistId}.
     *
     * @throws HttpError {@code 400 unknown_field} (no playlists here), {@code 401 not_authenticated}
     *                   (not a user), {@code 400 invalid_add_to_library} ({@code addToLibrary: false}:
     *                   an item in a playlist is always in its owner's library), {@code 404 playlist_not_found}
     */
    private Actor.User playlistTarget(Actor actor, ImportRequest body) throws Exception {
        if (body.playlistId() == null) {
            if (body.beforeEntryId() != null) {
                throw new HttpError(400, "invalid_before_entry_id", "beforeEntryId needs a playlistId");
            }
            return null;
        }
        if (playlists == null) {
            throw new HttpError(400, "unknown_field", "Unknown field: playlistId");
        }
        if (!(actor instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        if (Boolean.FALSE.equals(body.addToLibrary())) {
            throw new HttpError(400, "invalid_add_to_library",
                    "An item added to a playlist is always added to your library too");
        }
        playlists.summary(user.userId(), body.playlistId());
        return user;
    }

    /** The import, then (with a playlist) the playlist add; the answer's fields. */
    private Map<String, Object> run(Actor actor, ImportRequest body, Classify.Classification classification,
                                    Actor.User playlistOwner, YoutubeLookupStage.Listener progress) throws Exception {
        YoutubeImports.Imported done = imports.importUrl(actor, body.url(), body.presentAsAudio(),
                playlistOwner != null ? Boolean.TRUE : body.addToLibrary(), classification, progress);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", done.itemId());
        out.put("cached", done.cached());
        out.put("status", done.status());
        if (playlistOwner != null) {
            Playlists.Added added = playlists.add(playlistOwner, body.playlistId(), done.itemId(),
                    body.beforeEntryId(), classification, () -> policy.requireYoutube(playlistOwner, null));
            out.put("entry", added.entry());
        }
        return out;
    }

    /** Whether the client asked for the lookup's stages ({@code Accept: application/x-ndjson}). */
    static boolean wantsStages(String accept) {
        if (accept == null) {
            return false;
        }
        for (String part : accept.split(",")) {
            String type = part.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
            if (type.equals(NDJSON)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The streamed answer: started by the first stage of a lookup (never before, so an item that
     * is already known, and every error before the lookup, answer as plain JSON). A client that
     * left only stops the lines, not the import.
     */
    private static final class StageStream {
        private final Response response;
        private StreamBody body;
        private boolean broken;

        StageStream(Response response) {
            this.response = response;
        }

        synchronized boolean started() {
            return body != null;
        }

        void stage(YoutubeLookupStage stage) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "progress");
            line.put("stage", stage.wire());
            line.put("text", stage.text());
            line(line);
        }

        synchronized void line(Map<String, Object> line) {
            if (broken) {
                return;
            }
            try {
                if (body == null) {
                    // nginx and friends: pass the lines on as they come
                    response.header("X-Accel-Buffering", "no");
                    body = response.stream(NDJSON_TYPE, StreamBody.Framing.CHUNKED);
                }
                body.write((Json.GSON.toJson(line) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                broken = true;
            }
        }
    }
}
