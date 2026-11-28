package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Feature;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A YouTube search over HTTP ({@link YoutubeSearch}, {@code docs/api.md}):
 * {@code GET /api/youtube/search?q=&limit=} → {@code 200 {items: [{videoId, title, channel?,
 * durationMs?, thumbnailUrl, type?}]}}. Signed-in users only ({@code 401 not_authenticated}), with
 * {@code ytdlp.enabled} on ({@code 403 feature_disabled}) and {@code library.youtube}
 * ({@code 403 forbidden}); {@code q} 1..200 characters after trimming ({@code 400 invalid_query}),
 * {@code limit} 1..20, default 10 ({@code 400 invalid_limit}); {@code 429 too_many_searches}
 * over {@code youtubeSearch.maxPerHour}.
 *
 * <p>The room form, {@code GET /api/rooms/{room}/youtube-search?q=&limit=} (the room view's
 * request search), answers the same for anyone who may request YouTube links in that room
 * ({@link RoomRules}: {@code 404 room_not_found}, {@code 403 feature_disabled},
 * {@code banned_from_room} / {@code timed_out}, {@code forbidden} / {@code guest_youtube_disabled},
 * {@code 401 identity_required} for an anonymous visitor who could as a guest), guests included.
 * The same per-person limit (a guest counted by its guest identity); MVFinder is asked for a
 * guest only while {@code mvfinder.allowGuests} is on (else the YouTube service's search).
 */
public final class YoutubeSearchApi {
    private final YoutubeSearch search;
    private final Permissions permissions;
    private volatile RoomRules roomRules;

    /** The room's rules for YouTube link requests ({@code RoomGuests#requireYoutubeRequests}). */
    @FunctionalInterface
    public interface RoomRules {
        /** Returns if {@code actor} (a user or a guest) may request YouTube links in the room, else throws. */
        void check(Actor actor, String roomIdOrSlug) throws Exception;
    }

    /** Turns on the room form ({@code GET /api/rooms/{room}/youtube-search}). Set before {@link #register}. */
    public YoutubeSearchApi roomRules(RoomRules rules) {
        this.roomRules = rules;
        return this;
    }

    public YoutubeSearchApi(YoutubeSearch search, Permissions permissions) {
        this.search = Objects.requireNonNull(search);
        this.permissions = Objects.requireNonNull(permissions);
    }

    void register(Router router) {
        router.get("/api/youtube/search", this::search);
        if (roomRules != null) {
            router.get("/api/rooms/{room}/youtube-search", this::roomSearch);
        }
    }

    private void roomSearch(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        roomRules.check(actor, request.pathParam("room"));
        String who;
        boolean useResolver;
        if (actor instanceof Actor.User u) {
            who = u.userId(); // the same count as the header search
            useResolver = true;
        } else if (actor instanceof Actor.Guest g) {
            who = "guest:" + g.guestId();
            useResolver = search.resolverAllowsGuests();
        } else {
            throw new HttpError(401, "identity_required", "Join as a guest first");
        }
        String q = query(request.query("q"));
        int limit = limit(request.query("limit"));
        List<VideoSearch.Candidate> found = search.search(who, q, limit, useResolver);
        response.header("Cache-Control", "no-store");
        response.json(Map.of("items", items(found)));
    }

    private void search(Request request, Response response) throws Exception {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        permissions.requireFeature(Feature.YOUTUBE);
        permissions.require(user, Permission.LIBRARY_YOUTUBE);
        String q = query(request.query("q"));
        int limit = limit(request.query("limit"));
        List<VideoSearch.Candidate> found = search.search(user.userId(), q, limit, true);
        response.header("Cache-Control", "no-store");
        response.json(Map.of("items", items(found)));
    }

    /** The search text, trimmed: 1..{@link YoutubeSearch#MAX_QUERY} characters, else {@code 400 invalid_query}. */
    static String query(String param) {
        String q = param == null ? "" : param.strip();
        if (q.isEmpty() || q.length() > YoutubeSearch.MAX_QUERY) {
            throw new HttpError(400, "invalid_query", "Type 1 to " + YoutubeSearch.MAX_QUERY + " characters to search for");
        }
        return q;
    }

    /** {@code limit}: 1..{@link YoutubeSearch#MAX_LIMIT}, default {@link YoutubeSearch#DEFAULT_LIMIT}. */
    static int limit(String param) {
        if (param == null || param.isEmpty()) {
            return YoutubeSearch.DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(param);
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > YoutubeSearch.MAX_LIMIT) {
            throw new HttpError(400, "invalid_limit", "limit must be 1.." + YoutubeSearch.MAX_LIMIT);
        }
        return limit;
    }

    /** The results as sent: {@code videoId, title, channel?, durationMs?, thumbnailUrl, type?}. */
    static List<Map<String, Object>> items(List<VideoSearch.Candidate> found) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VideoSearch.Candidate c : found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("videoId", c.videoId());
            m.put("title", c.title());
            if (c.channel() != null) {
                m.put("channel", c.channel());
            }
            if (c.durationMs() != null) {
                m.put("durationMs", c.durationMs());
            }
            m.put("thumbnailUrl", c.thumbnailUrl());
            if (c.type() != null) {
                m.put("type", c.type());
            }
            out.add(m);
        }
        return out;
    }
}
