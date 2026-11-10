package io.siggi.togethertunes.rooms;

import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.Hooks;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.time.Clock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code POST /api/diagnostics}: the room page's "Send diagnostics" link (listen mode) posts what
 * its player did (user agent, player and clock state, the last sync-loop decisions, player events
 * and page errors; see {@code web/src/diagnostics.ts}), and this logs it on one line, so a phone's
 * sync problems can be seen without Web Inspector.
 *
 * <p>Like the WebSocket test report ({@code /test/ws-echo/report}): anyone may send (guests and
 * anonymous listeners have sync problems too), the body is limited to {@value #MAX_BYTES} bytes,
 * the route is rate-limited per IP, and the log line names the actor by kind and id only. No IP
 * is logged (the client doesn't know its own, and the server adds none), and URL parameters that
 * could carry a capability ({@code token}, {@code key}, {@code ticket}, …) are masked in case a
 * page error message ever quotes such a URL.
 */
public final class DiagnosticsApi {
    private static final System.Logger LOG = System.getLogger(DiagnosticsApi.class.getName());

    /** The route. */
    public static final String PATH = "/api/diagnostics";

    /** Largest accepted report body. */
    static final int MAX_BYTES = 256 * 1024;

    /** URL query parameters whose values are masked in the logged line. */
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)([?&](?:token|key|ticket|secret|password|code|sid|gid|t)=)[^&\\s\"\\\\]+");

    private final RateLimiter limiter;
    private final Consumer<String> log;

    /** Logs to the server log; at most 10 reports at once per IP, then one every 30 s. */
    public static DiagnosticsApi create(Clock clock) {
        return new DiagnosticsApi(new RateLimiter("diagnostics", 10, 30_000, clock),
                line -> LOG.log(System.Logger.Level.INFO, line));
    }

    DiagnosticsApi(RateLimiter limiter, Consumer<String> log) {
        this.limiter = limiter;
        this.log = log;
    }

    public void register(Router router) {
        router.post(PATH, this::report, Hooks.rateLimit(limiter));
    }

    private void report(Request request, Response response) throws Exception {
        JsonObject report = request.readJson(JsonObject.class, MAX_BYTES);
        // Gson writes it on one line, with control characters escaped
        log.accept("Client diagnostics from " + describe(ActorResolver.actor(request)) + ": "
                + mask(Json.GSON.toJson(report)));
        response.empty(204);
    }

    /** The actor by kind and id (never a session id or an IP). */
    static String describe(Actor actor) {
        if (actor instanceof Actor.User user) {
            return "user " + user.userId();
        }
        if (actor instanceof Actor.Guest guest) {
            return "guest " + guest.guestId();
        }
        return "anonymous";
    }

    /** Masks the values of URL parameters that could be capabilities. */
    static String mask(String text) {
        return SECRET_PARAM.matcher(text).replaceAll("$1***");
    }
}
