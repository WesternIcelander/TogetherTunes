package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
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
 */
public final class YoutubeImportsApi {
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
        YoutubeImports.Imported done = imports.importUrl(ActorResolver.actor(request), body.url(),
                body.presentAsAudio(), body.addToLibrary());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", done.itemId());
        out.put("cached", done.cached());
        out.put("status", done.status());
        response.json(out);
    }
}
