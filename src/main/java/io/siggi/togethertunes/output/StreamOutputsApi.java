package io.siggi.togethertunes.output;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.rooms.RoomOutputs;
import io.siggi.togethertunes.rooms.Rooms;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A room's stream outputs over HTTP ({@code docs/api.md#rooms}): {@code /api/rooms/{room}/outputs…}.
 * {@code {room}} is the room's id or slug. Responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/rooms/{room}/outputs} → {@code {"items": [output]}} (rtmp, ts, hls, mp3 order);
 *       {@code GET …/outputs/{outputId}} → the output. Owner and moderators.</li>
 *   <li>{@code POST …/outputs} {@code {"kind": "rtmp"|"ts"|"hls"|"mp3", "enabled"?: true, "rtmpUrl"?,
 *       "rtmpKey"?}} → {@code 201 output}. The owner only. One output per kind and room
 *       ({@code 409 output_exists}); refused while the server switch {@code outputs.<kind>.enabled}
 *       is off ({@code 403 feature_disabled}). RTMP needs {@code rtmpUrl}.</li>
 *   <li>{@code PATCH …/outputs/{outputId}} {@code {"enabled"?, "rtmpUrl"?, "rtmpKey"?}} → the
 *       output; the owner. Turning an output on while the server switch is off: {@code 403
 *       feature_disabled} (turning it off, or other changes, always work). {@code "rtmpKey": null}
 *       or {@code ""} removes the key.</li>
 *   <li>{@code DELETE …/outputs/{outputId}} → {@code 204}; the owner.</li>
 *   <li>{@code POST …/outputs/{outputId}/rotate} → the output with a new token (ts/HLS/MP3 of a
 *       private room; RTMP, or a public or unlisted room, whose URLs use the slug: {@code 409
 *       no_token}); the owner. Open connections with the old token end.</li>
 * </ul>
 *
 * <p>An output is {@code {"id", "kind", "enabled", "serverEnabled", "active", "createdAt",
 * "stopped"?}} plus, for ts/HLS/MP3, {@code "url"} (the stream URL: by the room's slug for a public
 * or unlisted room, by token for a private one) and {@code "urlKind": "slug"|"token"}, plus
 * {@code "insecureUrl"?} (the same URL, slug or token, on {@code http.insecureBaseUrl}, for players
 * that can't use HTTPS; only when the server has one) and {@code "tokenCleartextAt"?} (a private
 * room's output whose current token has been used over plain HTTP: when, epoch ms; the panel warns),
 * and for
 * RTMP, to the room's owner only, {@code
 * "rtmpUrl"}, {@code "hasRtmpKey"} and {@code "rtmpKey"?} (the full key; moderators get nothing
 * about the key or the ingest URL, which can carry credentials too), then for everyone {@code
 * "playbackUrl"?} (the room
 * setting {@code outputs.rtmp.playbackUrl}), {@code "rtmpAvailable"} (false if the server has no
 * ffmpeg) and {@code "status"?: {"state": "connecting"|"connected"|"retrying"|"error", "detail"?,
 * "failures", "since", "retryAt"?}} while a push runs (moderators get the detail without URLs or
 * key hints, {@link RoomOutputs#moderatorDetail}). Every kind, to the owner only: {@code
 * "stopped"?: {"reason": "idle"|"no_listeners", "at", "idleMinutes"}} while the server's auto
 * turn-off keeps it off ({@link OutputAutoOff}). {@code active} = enabled AND the server
 * switch. Errors: {@code 401 not_authenticated} (not a user), {@code 403 forbidden}, {@code 404
 * room_not_found} / {@code output_not_found}, {@code 400 invalid_kind} / {@code invalid_enabled} /
 * {@code invalid_rtmp_url} / {@code invalid_rtmp_key} / {@code invalid_field} (RTMP fields on a
 * ts/HLS output) / {@code unknown_field}.
 */
public final class StreamOutputsApi {
    private static final Set<String> CREATE_FIELDS = Set.of("kind", "enabled", "rtmpUrl", "rtmpKey");
    private static final Set<String> UPDATE_FIELDS = Set.of("enabled", "rtmpUrl", "rtmpKey");

    private final Rooms rooms;
    private final StreamOutputs outputs;
    private final StreamOutputControl control;
    private final String publicBaseUrl;
    /** {@code http.insecureBaseUrl} when plain-HTTP stream URLs are offered, else null. */
    private final String insecureBaseUrl;

    public StreamOutputsApi(Rooms rooms, StreamOutputs outputs, StreamOutputControl control, String publicBaseUrl) {
        this(rooms, outputs, control, publicBaseUrl, null);
    }

    /**
     * @param insecureBaseUrl {@code http.insecureBaseUrl} if stream URLs are served over plain HTTP
     *                        ({@code Config.redirectPlainHttp()}), else null
     */
    public StreamOutputsApi(Rooms rooms, StreamOutputs outputs, StreamOutputControl control, String publicBaseUrl,
                            String insecureBaseUrl) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.control = Objects.requireNonNull(control, "control");
        this.publicBaseUrl = trim(Objects.requireNonNull(publicBaseUrl, "publicBaseUrl"));
        this.insecureBaseUrl = insecureBaseUrl == null ? null : trim(insecureBaseUrl);
    }

    private static String trim(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public void register(Router router) {
        router.get("/api/rooms/{room}/outputs", this::list);
        router.post("/api/rooms/{room}/outputs", this::create);
        router.get("/api/rooms/{room}/outputs/{outputId}", this::get);
        router.patch("/api/rooms/{room}/outputs/{outputId}", this::update);
        router.delete("/api/rooms/{room}/outputs/{outputId}", this::delete);
        router.post("/api/rooms/{room}/outputs/{outputId}/rotate", this::rotate);
    }

    /** The stream URL of a ts/HLS/MP3 output whose URL key (slug or token) is {@code token}. */
    public String url(StreamOutputs.Kind kind, String token) {
        return url(publicBaseUrl, kind, token);
    }

    private static String url(String base, StreamOutputs.Kind kind, String token) {
        return switch (kind) {
            case TS -> base + "/live/" + token + ".ts";
            case HLS -> base + "/live/" + token + "/index.m3u8";
            case MP3 -> base + "/live/" + token + ".mp3";
            case RTMP -> null;
        };
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    /** The room, if the user moderates it (owner, moderator, {@code rooms.moderateAny}). */
    private Rooms.Access moderated(Actor.User user, Request request) throws Exception {
        Rooms.Access a = rooms.access(user, request.pathParam("room"));
        if (a.role() == null || !a.role().moderates()) {
            throw HttpError.forbidden("Only the owner and moderators see a room's outputs");
        }
        return a;
    }

    private Rooms.Access owned(Actor.User user, Request request) throws Exception {
        Rooms.Access a = rooms.access(user, request.pathParam("room"));
        if (a.role() != Rooms.Role.OWNER) {
            throw HttpError.forbidden("Only the room's owner changes its outputs");
        }
        if (a.room().alone()) {
            throw new HttpError(409, "alone_room", "Your own play session has no stream outputs");
        }
        return a;
    }

    private StreamOutputs.Output output(Rooms.Access a, Request request) throws Exception {
        StreamOutputs.Output o = outputs.get(a.room().id(), request.pathParam("outputId"));
        if (o == null) {
            throw new HttpError(404, "output_not_found", "No such output in this room");
        }
        return o;
    }

    private void list(Request request, Response response) throws Exception {
        Rooms.Access a = moderated(user(request), request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (StreamOutputs.Output o : outputs.list(a.room().id())) {
            items.add(view(a, o));
        }
        response.header("Cache-Control", "no-store");
        response.json(Map.of("items", items));
    }

    private void get(Request request, Response response) throws Exception {
        Rooms.Access a = moderated(user(request), request);
        send(response, 200, view(a, output(a, request)));
    }

    private void create(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Rooms.Access a = owned(user, request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, CREATE_FIELDS);
        StreamOutputs.Kind kind = StreamOutputs.Kind.parse(string(body.get("kind"), "invalid_kind"));
        if (kind == null) {
            throw new HttpError(400, "invalid_kind", "kind must be rtmp, ts, hls or mp3");
        }
        Boolean enabledField = bool(body.get("enabled"));
        boolean enabled = enabledField == null || enabledField;
        String rtmpUrl = null;
        String rtmpKey = null;
        if (kind == StreamOutputs.Kind.RTMP) {
            rtmpUrl = StreamOutputs.rtmpUrl(string(body.get("rtmpUrl"), "invalid_rtmp_url"));
            rtmpKey = StreamOutputs.rtmpKey(rawString(body.get("rtmpKey"), "invalid_rtmp_key"));
        } else {
            rtmpOnly(body);
        }
        requireServerSwitch(kind);
        StreamOutputs.Output o = outputs.create(a.room().id(), kind, rtmpUrl, rtmpKey, enabled, user.userId(),
                request.clientIp());
        control.roomChanged(a.room().id(), enabled);
        send(response, 201, view(a, o));
    }

    private void update(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Rooms.Access a = owned(user, request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, UPDATE_FIELDS);
        StreamOutputs.Output before = output(a, request);
        Boolean enabled = bool(body.get("enabled"));
        String rtmpUrl = null;
        boolean setKey = false;
        String rtmpKey = null;
        if (before.kind() == StreamOutputs.Kind.RTMP) {
            if (body.has("rtmpUrl")) {
                rtmpUrl = StreamOutputs.rtmpUrl(string(body.get("rtmpUrl"), "invalid_rtmp_url"));
            }
            if (body.has("rtmpKey")) {
                setKey = true;
                rtmpKey = StreamOutputs.rtmpKey(rawString(body.get("rtmpKey"), "invalid_rtmp_key"));
            }
        } else {
            rtmpOnly(body);
        }
        boolean turningOn = Boolean.TRUE.equals(enabled) && !before.enabled();
        if (turningOn) {
            requireServerSwitch(before.kind());
        }
        StreamOutputs.Output o = outputs.update(a.room().id(), before.id(),
                new StreamOutputs.Patch(enabled, rtmpUrl, setKey, rtmpKey), user.userId(), request.clientIp());
        if (o == null) {
            throw new HttpError(404, "output_not_found", "No such output in this room");
        }
        control.roomChanged(a.room().id(), turningOn && o.enabled());
        send(response, 200, view(a, o));
    }

    private void delete(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Rooms.Access a = owned(user, request);
        if (outputs.delete(a.room().id(), request.pathParam("outputId"), user.userId(), request.clientIp()) == null) {
            throw new HttpError(404, "output_not_found", "No such output in this room");
        }
        control.roomChanged(a.room().id(), false);
        response.empty(204);
    }

    private void rotate(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Rooms.Access a = owned(user, request);
        if (a.room().visibility() != Rooms.Visibility.PRIVATE) {
            output(a, request); // 404 first for an unknown output
            throw new HttpError(409, "no_token", "This room's stream URLs use its link name, so there is no token to"
                    + " rotate; only private rooms' stream URLs have tokens");
        }
        StreamOutputs.Output o = outputs.rotate(a.room().id(), request.pathParam("outputId"), user.userId(),
                request.clientIp());
        if (o == null) {
            throw new HttpError(404, "output_not_found", "No such output in this room");
        }
        control.roomChanged(a.room().id(), false);
        send(response, 200, view(a, o));
    }

    private void requireServerSwitch(StreamOutputs.Kind kind) throws Exception {
        if (!control.serverEnabled(kind)) {
            throw new HttpError(403, "feature_disabled", "This server has turned off "
                    + switch (kind) {
                        case RTMP -> "RTMP push";
                        case TS -> "the .ts output";
                        case HLS -> "the HLS output";
                        case MP3 -> "the MP3 output";
                    });
        }
    }

    private Map<String, Object> view(Rooms.Access a, StreamOutputs.Output o) throws Exception {
        boolean owner = a.role() == Rooms.Role.OWNER;
        boolean serverEnabled = control.serverEnabled(o.kind());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("kind", o.kind().wire());
        m.put("enabled", o.enabled());
        m.put("serverEnabled", serverEnabled);
        m.put("active", o.enabled() && serverEnabled);
        m.put("createdAt", o.createdAt());
        StreamOutputs.Stopped stopped = o.stopped();
        if (owner && !o.enabled() && stopped != null) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("reason", stopped.reason());
            st.put("at", stopped.at());
            st.put("idleMinutes", stopped.idleMinutes());
            m.put("stopped", st);
        }
        if (o.kind().hasToken()) {
            boolean byToken = a.room().visibility() == Rooms.Visibility.PRIVATE;
            m.put("url", url(o.kind(), byToken ? o.token() : a.room().slug()));
            m.put("urlKind", byToken ? "token" : "slug");
            if (insecureBaseUrl != null) {
                // Private rooms' token URLs too (the token then travels unencrypted; tokenCleartextAt warns).
                m.put("insecureUrl", url(insecureBaseUrl, o.kind(), byToken ? o.token() : a.room().slug()));
            }
            if (byToken && o.tokenCleartextAt() != null) {
                m.put("tokenCleartextAt", o.tokenCleartextAt());
            }
        } else {
            // The target and key only for the owner: they open the owner's account at the RTMP service.
            if (owner) {
                m.put("rtmpUrl", o.rtmpUrl());
                m.put("hasRtmpKey", o.hasRtmpKey());
                if (o.rtmpKey() != null) {
                    m.put("rtmpKey", o.rtmpKey());
                }
            }
            String playback = a.room().settings().rtmpPlaybackUrl();
            if (playback != null) {
                m.put("playbackUrl", playback);
            }
            m.put("rtmpAvailable", control.rtmpAvailable());
            RtmpPusher.Status s = control.rtmpStatus(a.room().id());
            if (s != null && s.state() != RtmpPusher.State.STOPPED) {
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("state", s.state().name().toLowerCase(Locale.ROOT));
                if (s.detail() != null) {
                    st.put("detail", owner ? s.detail() : RoomOutputs.moderatorDetail(s.detail()));
                }
                st.put("failures", s.failures());
                st.put("since", s.sinceMs());
                if (s.retryAtMs() > 0) {
                    st.put("retryAt", s.retryAtMs());
                }
                m.put("status", st);
            }
        }
        return m;
    }

    private static void send(Response response, int status, Object body) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(status, body);
    }

    private static void rtmpOnly(JsonObject body) {
        if (body.has("rtmpUrl") || body.has("rtmpKey")) {
            throw new HttpError(400, "invalid_field", "rtmpUrl and rtmpKey are only for RTMP outputs");
        }
    }

    private static void fields(JsonObject body, Set<String> allowed) {
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
    }

    private static Boolean bool(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
            throw new HttpError(400, "invalid_enabled", "enabled must be true or false");
        }
        return p.getAsBoolean();
    }

    /** The string, or null for a missing field, JSON null or a blank string; else {@code 400 code}. */
    private static String string(JsonElement e, String code) {
        String s = rawString(e, code);
        return s == null || s.isBlank() ? null : s;
    }

    private static String rawString(JsonElement e, String code) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new HttpError(400, code, "Must be a string");
        }
        return p.getAsString();
    }
}
