package io.siggi.togethertunes.youtube;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.settings.Settings;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The admin's YouTube service box ({@code docs/api.md#admin}): every route needs
 * {@code admin.settings}; responses are {@code Cache-Control: no-store}. The token is never part of
 * a response.
 *
 * <ul>
 *   <li>{@code GET /api/admin/youtube-service} → {@code {"configured", "reachable", "error"?,
 *       "versions"?: {"ytdlp", "ytdlpEjs", "tokenPlugin", "tokenProvider", "deno", "ffmpeg", "channel"},
 *       "providers"?: {"ready", "total"}, "updating", "staging", "lastUpdate"?: {"time", "result"},
 *       "activeJobs"?}}: from the service's {@code GET /api/status} (which needs no token).</li>
 *   <li>{@code POST /api/admin/youtube-service/update} → the service's {@code POST /api/update}
 *       ({@code {"started", "reason"?}}); audited ({@value #AUDIT_UPDATE}). {@code 503
 *       youtube_service_unavailable} when it isn't set up or can't be reached, {@code 502
 *       youtube_service_refused} when it refuses the token.</li>
 *   <li>{@code GET /api/admin/youtube-service/update-log} → {@code {"log"?: text (absent when there was no update),
 *       "truncated"}}: the last update's summary and log (the service's
 *       {@code GET /api/status/update-log}), at most {@link #LOG_MAX_CHARS} (the end kept).</li>
 *   <li>{@code POST /api/admin/youtube-service/test} (body optional: {@code {"url"?, "token"?}},
 *       unsaved values from the form; each falls back to the stored setting) → {@code {"url",
 *       "reachable", "tokenAccepted"?, "httpStatus"?, "error"?}}: an authenticated call for a job
 *       that doesn't exist, which is 404 when the token is accepted and 401 when it isn't.
 *       {@code 400 invalid_url}. Not audited (it changes nothing).</li>
 * </ul>
 */
public final class YoutubeServiceApi {
    /** {@code audit_log} action of an admin's update. */
    public static final String AUDIT_UPDATE = "admin.youtube_service_update";
    /** The update log is cut to its end beyond this. */
    static final int LOG_MAX_CHARS = 256 * 1024;

    private final YoutubeService youtube;
    private final Settings settings;
    private final Database db;
    private final Permissions permissions;
    private final Clock clock;

    public YoutubeServiceApi(YoutubeService youtube, Settings settings, Database db, Permissions permissions,
                             Clock clock) {
        this.youtube = Objects.requireNonNull(youtube);
        this.settings = Objects.requireNonNull(settings);
        this.db = Objects.requireNonNull(db);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
    }

    public void register(Router router) {
        var perm = permissions.hook(Permission.ADMIN_SETTINGS);
        router.get("/api/admin/youtube-service", this::status, perm);
        router.post("/api/admin/youtube-service/update", this::update, perm);
        router.get("/api/admin/youtube-service/update-log", this::updateLog, perm);
        router.post("/api/admin/youtube-service/test", this::test, perm);
    }

    private void status(Request request, Response response) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", youtube.configured());
        if (!youtube.configured()) {
            out.put("reachable", false);
        } else {
            try {
                out.putAll(summary(youtube.status()));
                out.put("reachable", true);
            } catch (YoutubeServiceException e) {
                out.put("reachable", false);
                out.put("error", e.getMessage());
            }
        }
        send(response, 200, out);
    }

    /** The parts of the service's {@code /api/status} the admin box shows. */
    static Map<String, Object> summary(JsonObject s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.get("active") instanceof JsonObject active) {
            Map<String, Object> v = new LinkedHashMap<>();
            JsonObject pip = active.get("pip") instanceof JsonObject p ? p : new JsonObject();
            v.put("ytdlp", text(pip, "yt-dlp"));
            v.put("ytdlpEjs", text(pip, "yt-dlp-ejs"));
            v.put("tokenPlugin", text(pip, "bgutil-ytdlp-pot-provider"));
            v.put("tokenProvider", text(active, "provider"));
            v.put("deno", text(active, "deno"));
            v.put("ffmpeg", text(active, "ffmpeg"));
            v.put("channel", text(active, "channel"));
            out.put("versions", v);
        }
        if (s.get("providers") instanceof JsonArray providers) {
            int ready = 0;
            for (JsonElement p : providers) {
                if (p instanceof JsonObject o && o.get("ready") != null && o.get("ready").isJsonPrimitive()
                        && o.get("ready").getAsBoolean()) {
                    ready++;
                }
            }
            out.put("providers", Map.of("ready", ready, "total", providers.size()));
        }
        out.put("updating", s.get("updating") != null && s.get("updating").isJsonPrimitive()
                && s.get("updating").getAsBoolean());
        out.put("staging", s.get("staging") != null && !s.get("staging").isJsonNull());
        if (s.get("last_update") instanceof JsonObject last) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("time", text(last, "time"));
            l.put("result", text(last, "result"));
            out.put("lastUpdate", l);
        }
        if (s.get("active_jobs") != null && s.get("active_jobs").isJsonPrimitive()
                && s.get("active_jobs").getAsJsonPrimitive().isNumber()) {
            out.put("activeJobs", s.get("active_jobs").getAsInt());
        }
        return out;
    }

    private void update(Request request, Response response) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the permission hook let only users through
        JsonObject r;
        try {
            r = youtube.startUpdate();
        } catch (YoutubeServiceException e) {
            throw adminError(e);
        }
        boolean started = r.get("started") != null && r.get("started").isJsonPrimitive()
                && r.get("started").getAsBoolean();
        if (started) {
            String ip = request.clientIp();
            db.write(c -> {
                AuditLog.write(c, EpochMs.now(clock), user.userId(), null, ip, AUDIT_UPDATE, null, Map.of());
                return null;
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", started);
        out.put("reason", text(r, "reason"));
        send(response, 200, out);
    }

    private void updateLog(Request request, Response response) throws Exception {
        JsonElement log;
        try {
            log = youtube.updateLog();
        } catch (YoutubeServiceException e) {
            throw adminError(e);
        }
        String text = logText(log);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean truncated = text != null && text.length() > LOG_MAX_CHARS;
        out.put("log", truncated ? "…\n" + text.substring(text.length() - LOG_MAX_CHARS) : text);
        out.put("truncated", truncated);
        send(response, 200, out);
    }

    /** The update summary as text: time, result, then its log lines; null if there was no update. */
    static String logText(JsonElement log) {
        if (log == null || log.isJsonNull()) {
            return null;
        }
        if (!(log instanceof JsonObject o)) {
            return log.toString();
        }
        StringBuilder b = new StringBuilder();
        String time = text(o, "time");
        String result = text(o, "result");
        b.append(time == null ? "?" : time).append(": ").append(result == null ? "?" : result).append('\n');
        if (o.get("log") instanceof JsonArray lines) {
            for (JsonElement l : lines) {
                b.append(l.isJsonPrimitive() ? l.getAsString() : l.toString()).append('\n');
            }
        } else if (o.get("log") != null && o.get("log").isJsonPrimitive()) {
            b.append(o.get("log").getAsString()).append('\n');
        }
        return b.toString();
    }

    private void test(Request request, Response response) throws Exception {
        String url = null;
        String token = null;
        if (!emptyBody(request)) {
            JsonObject body = request.readJson(JsonObject.class);
            JsonElement u = body == null ? null : body.get("url");
            if (u != null && !u.isJsonNull()) {
                try {
                    url = Settings.YOUTUBE_SERVICE_URL.decode(u);
                } catch (IllegalArgumentException e) {
                    throw new HttpError(400, "invalid_url", "Enter an http:// or https:// URL without user name,"
                            + " query or fragment");
                }
            }
            JsonElement t = body == null ? null : body.get("token");
            if (t != null && !t.isJsonNull()) {
                try {
                    token = Settings.YOUTUBE_SERVICE_TOKEN.decode(t);
                } catch (IllegalArgumentException e) {
                    throw new HttpError(400, "invalid_token", "The token must be one line of at most 1000 characters");
                }
                if (token.isEmpty()) {
                    token = null;
                }
            }
        }
        if (url == null) {
            url = settings.youtubeServiceUrl();
        }
        if (token == null) {
            token = settings.youtubeServiceToken();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        if (url == null) {
            out.put("reachable", false);
            out.put("error", "No YouTube service URL is set");
            send(response, 200, out);
            return;
        }
        try {
            int status = YoutubeService.checkToken(new YoutubeService.Endpoint(url, token));
            out.put("reachable", true);
            out.put("httpStatus", status);
            if (status == 404) {
                out.put("tokenAccepted", true);
            } else if (status == 401) {
                out.put("tokenAccepted", false);
            } else {
                out.put("error", "Unexpected answer (HTTP " + status + "): is this the YouTube service?");
            }
        } catch (YoutubeServiceException e) {
            out.put("reachable", false);
            out.put("error", e.getMessage());
        }
        send(response, 200, out);
    }

    private static HttpError adminError(YoutubeServiceException e) {
        return switch (e.kind()) {
            case UNAUTHORIZED -> new HttpError(502, "youtube_service_refused",
                    "The YouTube service refused the token (youtube.serviceToken)");
            case NOT_CONFIGURED -> new HttpError(503, "youtube_service_unavailable",
                    "The YouTube service isn't set up (youtube.serviceUrl)");
            default -> new HttpError(503, "youtube_service_unavailable", e.getMessage());
        };
    }

    private static boolean emptyBody(Request request) {
        return request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static void send(Response response, int status, Object body) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(status, body);
    }
}
