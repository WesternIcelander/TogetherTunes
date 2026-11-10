package io.siggi.togethertunes.library;

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
 * {@code POST /api/imports/youtube} {@code {url, presentAsAudio?, addToLibrary?}} →
 * {@code 200 {itemId, cached, status}} ({@code docs/api.md#adding-media}); the work is in
 * {@link YoutubeImports}. {@code cached} is true when the item was already ready (nothing to
 * download); {@code status} is the item's status ({@code ready}, {@code pending} or
 * {@code processing}). Needs {@code library.youtube}, which the {@code ytdlp.enabled} switch gates
 * ({@code 403 feature_disabled} when it is off; {@link AddMediaPolicy#requireYoutube}). Guests get
 * {@code 401} here: their YouTube links are requests inside a room, made with the room's
 * {@code queue.add} (M3), which checks {@link AddMediaPolicy#requireYoutube} with the room.
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

    record ImportRequest(String url, Boolean presentAsAudio, Boolean addToLibrary) {
    }

    public YoutubeImportsApi(YoutubeImports imports, AddMediaPolicy policy) {
        this.imports = Objects.requireNonNull(imports);
        this.policy = Objects.requireNonNull(policy);
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
        if (!wantsStages(request.header("Accept"))) {
            response.json(result(imports.importUrl(ActorResolver.actor(request), body.url(),
                    body.presentAsAudio(), body.addToLibrary())));
            return;
        }
        StageStream stages = new StageStream(response);
        YoutubeImports.Imported done;
        try {
            done = imports.importUrl(ActorResolver.actor(request), body.url(), body.presentAsAudio(),
                    body.addToLibrary(), stages::stage);
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
        Map<String, Object> out = result(done);
        if (!stages.started()) {
            response.json(out);
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "result");
        line.putAll(out);
        stages.line(line);
    }

    private static Map<String, Object> result(YoutubeImports.Imported done) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", done.itemId());
        out.put("cached", done.cached());
        out.put("status", done.status());
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
