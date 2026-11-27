package io.siggi.togethertunes.ingest;

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
import io.siggi.togethertunes.ytdlp.PoTokenProvider;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The admin's yt-dlp update ({@code docs/api.md#admin}, {@link YtdlpUpdater}). Both routes need
 * {@code admin.settings}; responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/admin/ytdlp} → {@code {"available", "version"?, "update"?}}: whether yt-dlp
 *       is installed, its version, and the current or last update since the server started.</li>
 *   <li>{@code POST /api/admin/ytdlp/update} (no body) → {@code 202} the update ({@code yt-dlp -U}
 *       runs in the background; poll the {@code GET}). {@code 409 ytdlp_update_running},
 *       {@code 503 ytdlp_unavailable}. Audited ({@value #AUDIT_UPDATE}, details
 *       {@code versionBefore}).</li>
 * </ul>
 * An update is {@code {"state": running|succeeded|failed, "startedAt", "finishedAt"?, "exitCode"?,
 * "versionBefore"?, "versionAfter"?, "log"}} ({@code log}: the command line, yt-dlp's output and
 * the outcome).
 *
 * <p>PO tokens ({@link PoTokenProvider}): the {@code GET} also has {@code "poTokenPlugin":
 * {"loaded", "version"?}} (whether yt-dlp loaded the provider plugin that uses
 * {@code ytdlp.poTokenProviderUrl}; absent while yt-dlp isn't available or the check couldn't run),
 * and {@code POST /api/admin/ytdlp/po-token-test} (body optional: {@code {"url"}}, the URL to test,
 * else the stored setting, else the plugin's default) checks the plugin again and pings the
 * provider server → {@code {"url", "defaultUrl", "reachable", "httpStatus"?, "version"?, "error"?,
 * "poTokenPlugin"?}}; {@code 400 invalid_url}. Not audited (it changes nothing).
 */
public final class YtdlpApi {
    /** {@code audit_log} action of an admin's update. */
    public static final String AUDIT_UPDATE = "admin.ytdlp_update";

    private final YtdlpUpdater updater;
    private final Database db;
    private final Permissions permissions;
    private final Clock clock;
    private final Supplier<String> poTokenProviderUrl;

    public YtdlpApi(YtdlpUpdater updater, Database db, Permissions permissions, Clock clock) {
        this(updater, db, permissions, clock, () -> null);
    }

    /** @param poTokenProviderUrl the {@code ytdlp.poTokenProviderUrl} setting (null = unset) */
    public YtdlpApi(YtdlpUpdater updater, Database db, Permissions permissions, Clock clock,
                    Supplier<String> poTokenProviderUrl) {
        this.updater = Objects.requireNonNull(updater);
        this.db = Objects.requireNonNull(db);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
        this.poTokenProviderUrl = Objects.requireNonNull(poTokenProviderUrl);
    }

    public void register(Router router) {
        var perm = permissions.hook(Permission.ADMIN_SETTINGS);
        router.get("/api/admin/ytdlp", this::info, perm);
        router.post("/api/admin/ytdlp/update", this::update, perm);
        router.post("/api/admin/ytdlp/po-token-test", this::poTokenTest, perm);
    }

    private void info(Request request, Response response) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", updater.available());
        out.put("version", updater.version().orElse(null));
        out.put("update", updater.status().map(YtdlpApi::json).orElse(null));
        out.put("poTokenPlugin", updater.pluginStatus().map(YtdlpApi::json).orElse(null));
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private void poTokenTest(Request request, Response response) throws Exception {
        String url = null;
        if (!emptyBody(request)) {
            JsonObject body = request.readJson(JsonObject.class);
            JsonElement u = body.get("url");
            if (u != null && !u.isJsonNull()) {
                try {
                    url = Settings.YTDLP_PO_TOKEN_PROVIDER_URL.decode(u);
                } catch (IllegalArgumentException e) {
                    throw new HttpError(400, "invalid_url", "Enter an http:// or https:// URL without user"
                            + " name, query, \",\" or \";\"");
                }
            }
        }
        if (url == null) {
            url = poTokenProviderUrl.get();
        }
        boolean usingDefault = url == null;
        if (usingDefault) {
            url = PoTokenProvider.DEFAULT_URL;
        }
        updater.recheckPlugin();
        PoTokenProvider.PingResult ping = PoTokenProvider.ping(url, PoTokenProvider.PING_TIMEOUT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("defaultUrl", usingDefault);
        out.put("reachable", ping.reachable());
        out.put("httpStatus", ping.status());
        out.put("version", ping.version());
        out.put("error", ping.error());
        out.put("poTokenPlugin", updater.pluginStatus().map(YtdlpApi::json).orElse(null));
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private static boolean emptyBody(Request request) {
        return request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
    }

    static Map<String, Object> json(PoTokenProvider.PluginStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        PoTokenProvider.Provider p = s.httpProvider();
        m.put("loaded", p != null);
        m.put("version", p == null ? null : p.version());
        return m;
    }

    private void update(Request request, Response response) throws Exception {
        if (!updater.available()) {
            throw new HttpError(503, "ytdlp_unavailable", "yt-dlp isn't installed on the server");
        }
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the permission hook let only users through
        String ip = request.clientIp();
        Optional<YtdlpUpdater.Status> started = updater.start();
        if (started.isEmpty()) {
            throw new HttpError(409, "ytdlp_update_running", "An update is already running");
        }
        YtdlpUpdater.Status status = started.get();
        db.write(c -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("versionBefore", status.versionBefore());
            AuditLog.write(c, EpochMs.now(clock), user.userId(), null, ip, AUDIT_UPDATE, null, details);
            return null;
        });
        response.header("Cache-Control", "no-store");
        response.json(202, json(status));
    }

    static Map<String, Object> json(YtdlpUpdater.Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", s.state().wire());
        m.put("startedAt", s.startedAt());
        m.put("finishedAt", s.finishedAt());
        m.put("exitCode", s.exitCode());
        m.put("versionBefore", s.versionBefore());
        m.put("versionAfter", s.versionAfter());
        m.put("log", s.log());
        return m;
    }
}
