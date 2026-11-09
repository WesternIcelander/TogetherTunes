package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
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
 * ({@code 403 feature_disabled} when it is off). Guests get {@code 401} here: their YouTube links
 * are requests inside a room (M3).
 */
public final class YoutubeImportsApi {
    private final YoutubeImports imports;
    private final Permissions permissions;

    record ImportRequest(String url, Boolean presentAsAudio, Boolean addToLibrary) {
    }

    public YoutubeImportsApi(YoutubeImports imports, Permissions permissions) {
        this.imports = Objects.requireNonNull(imports);
        this.permissions = Objects.requireNonNull(permissions);
    }

    public void register(Router router) {
        router.post("/api/imports/youtube", this::importUrl, permissions.hook(Permission.LIBRARY_YOUTUBE));
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
