package io.siggi.togethertunes.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Find a music video" over HTTP ({@code docs/library-and-ingest.md#linked-media}, {@link VideoSearch}):
 * <ul>
 *   <li>{@code POST /api/items/{id}/video-search} → {@code 200 {candidates: [{videoId, title,
 *       channel?, durationMs?, type?, thumbnailUrl}]}} ({@code thumbnailUrl} an https URL on
 *       {@code *.ytimg.com} or {@code *.googleusercontent.com}, else YouTube's {@code hqdefault.jpg});</li>
 *   <li>{@code POST /api/items/{id}/video-search/pick {videoId}} → {@code 200 {itemId, status,
 *       linked, linkType}}: the picked video imported (not into a library) and linked to the song.</li>
 * </ul>
 * Signed-in users only ({@code 401 not_authenticated} for guests and anonymous visitors), with
 * {@code ytdlp.enabled} on ({@code 403 feature_disabled}) and {@code library.youtube}
 * ({@code 403 forbidden}).
 */
public final class VideoSearchApi {
    private final VideoSearch search;
    private final Permissions permissions;

    public VideoSearchApi(VideoSearch search, Permissions permissions) {
        this.search = Objects.requireNonNull(search);
        this.permissions = Objects.requireNonNull(permissions);
    }

    void register(Router router) {
        router.post("/api/items/{id}/video-search", this::search);
        router.post("/api/items/{id}/video-search/pick", this::pick);
    }

    private Actor.User caller(Request request) throws SQLException {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        permissions.requireFeature(Feature.YOUTUBE);
        permissions.require(user, Permission.LIBRARY_YOUTUBE);
        return user;
    }

    private void search(Request request, Response response) throws Exception {
        Actor.User user = caller(request);
        // no body needed (any is ignored)
        List<Map<String, Object>> out = new ArrayList<>();
        for (VideoSearch.Candidate c : search.search(user, request.pathParam("id"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("videoId", c.videoId());
            m.put("title", c.title());
            if (c.channel() != null) {
                m.put("channel", c.channel());
            }
            if (c.durationMs() != null) {
                m.put("durationMs", c.durationMs());
            }
            if (c.type() != null) {
                m.put("type", c.type());
            }
            m.put("thumbnailUrl", c.thumbnailUrl());
            out.add(m);
        }
        response.header("Cache-Control", "no-store");
        response.json(Map.of("candidates", out));
    }

    private void pick(Request request, Response response) throws Exception {
        Actor.User user = caller(request);
        JsonObject body = request.readJson(JsonObject.class);
        if (body == null) {
            throw new HttpError(400, "not_a_candidate", "Pick one of the videos found");
        }
        for (String key : body.keySet()) {
            if (!key.equals("videoId")) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
        JsonElement v = body.get("videoId");
        String videoId = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
        VideoSearch.Picked picked = search.pick(user, request.pathParam("id"), videoId, request.clientIp());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", picked.itemId());
        out.put("status", picked.status());
        out.put("linked", picked.linked());
        out.put("linkType", picked.linkType());
        response.header("Cache-Control", "no-store");
        response.json(out);
    }
}
