package io.siggi.togethertunes.auth;

import com.google.gson.JsonObject;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.Objects;

/**
 * The logged-in user's preferences ({@code docs/api.md}, {@link UserSettings}).
 *
 * <ul>
 *   <li>{@code GET /api/me/settings} → {@code {"doubleClickAlone": "play"|"queue",
 *       "webAudio": "auto"|"on"|"off", "debug": boolean}}, every key, defaults included.</li>
 *   <li>{@code PATCH /api/me/settings} with any subset of those keys → {@code 200} and all of
 *       them as in {@code GET}; {@code 400 unknown_setting}, {@code 400 invalid_setting} (nothing
 *       is saved then).</li>
 * </ul>
 * Users only: guests and anonymous callers get {@code 401 not_authenticated} (guests have no
 * settings). {@code Cache-Control: no-store}.
 */
public final class UserSettingsApi {
    private final UserSettings settings;

    public UserSettingsApi(UserSettings settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    public void register(Router router) {
        router.get("/api/me/settings", this::get);
        router.patch("/api/me/settings", this::patch);
    }

    private static Actor.User user(Request request) throws java.sql.SQLException {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        return user;
    }

    private void get(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        response.header("Cache-Control", "no-store");
        response.json(settings.get(user.userId()).toJson());
    }

    private void patch(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        UserSettings.Prefs prefs = settings.patch(user.userId(), body);
        response.header("Cache-Control", "no-store");
        response.json(prefs.toJson());
    }
}
