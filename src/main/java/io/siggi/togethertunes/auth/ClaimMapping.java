package io.siggi.togethertunes.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A login provider's {@code claim_mapping_json} ({@code docs/auth.md#oidc}): where the server
 * finds who signed in, and for plain OAuth 2.0 providers also the endpoints (they have no
 * discovery document).
 *
 * <ul>
 *   <li><b>OpenID Connect</b> ({@link Providers.Kind#OIDC}): optional, and only the claim names
 *       {@code username}, {@code displayName} and {@code email} (defaults
 *       {@value #OIDC_USERNAME}, {@value #OIDC_DISPLAY_NAME}, {@value #OIDC_EMAIL}). The subject is
 *       always the ID token's {@code sub}; the endpoints come from discovery.</li>
 *   <li><b>Plain OAuth 2.0</b> ({@link Providers.Kind#OAUTH2}): required, with
 *       {@code authorizationEndpoint}, {@code tokenEndpoint}, {@code userinfoUrl} and
 *       {@code subject} (the path of the stable user id in the userinfo response), and optionally
 *       {@code username}, {@code displayName} and {@code email}.</li>
 * </ul>
 *
 * <p>A path is one or more names separated by {@code .}; a name that is a number indexes an array
 * (e.g. {@code data.0.id}). Names are 1–64 characters without whitespace; a whole path at most
 * {@value #PATH_MAX}. URLs as {@link Providers#requireUrl}. Invalid input is
 * {@code 400 invalid_provider}.
 */
public record ClaimMapping(String authorizationEndpoint, String tokenEndpoint, String userinfoUrl, String subject,
                           String username, String displayName, String email) {
    public static final String OIDC_USERNAME = "preferred_username";
    public static final String OIDC_DISPLAY_NAME = "name";
    public static final String OIDC_EMAIL = "email";
    public static final int PATH_MAX = 256;

    private static final Set<String> CLAIM_KEYS = Set.of("username", "displayName", "email");
    private static final Set<String> OAUTH2_KEYS = Set.of("authorizationEndpoint", "tokenEndpoint", "userinfoUrl",
            "subject", "username", "displayName", "email");

    /**
     * Validates a mapping from the admin API.
     *
     * @param json {@code null} or JSON null for none (allowed for OpenID Connect only)
     * @return the mapping, or {@code null} when there is none
     */
    public static ClaimMapping parse(JsonElement json, Providers.Kind kind) {
        if (json == null || json.isJsonNull()) {
            if (kind == Providers.Kind.OAUTH2) {
                throw invalid("An OAuth provider needs claimMapping with authorizationEndpoint, tokenEndpoint,"
                        + " userinfoUrl and subject");
            }
            return null;
        }
        if (!json.isJsonObject()) {
            throw invalid("claimMapping must be an object");
        }
        JsonObject o = json.getAsJsonObject();
        Set<String> allowed = kind == Providers.Kind.OAUTH2 ? OAUTH2_KEYS : CLAIM_KEYS;
        for (String key : o.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(OAUTH2_KEYS.contains(key)
                        ? "claimMapping." + key + " is only for OAuth providers (OpenID Connect uses discovery and sub)"
                        : "Unknown field: claimMapping." + key);
            }
        }
        String username = path(o, "username");
        String displayName = path(o, "displayName");
        String email = path(o, "email");
        if (kind == Providers.Kind.OIDC) {
            if (username == null && displayName == null && email == null) {
                return null;
            }
            return new ClaimMapping(null, null, null, null, username, displayName, email);
        }
        String authorization = url(o, "authorizationEndpoint");
        String token = url(o, "tokenEndpoint");
        String userinfo = url(o, "userinfoUrl");
        String subject = path(o, "subject");
        if (subject == null) {
            throw invalid("claimMapping.subject is required for an OAuth provider");
        }
        return new ClaimMapping(authorization, token, userinfo, subject, username, displayName, email);
    }

    /**
     * The value at {@code path} in {@code root} (a userinfo response): each name looks up an object
     * member, or for an array, a name of digits indexes it ({@code data.0.id}).
     *
     * @return the element, or {@code null} when the path doesn't lead anywhere (or {@code path} is
     *         {@code null})
     */
    public static JsonElement resolve(JsonElement root, String path) {
        if (root == null || path == null) {
            return null;
        }
        JsonElement at = root;
        for (String name : path.split("\\.", -1)) {
            if (at == null || at.isJsonNull()) {
                return null;
            }
            if (at.isJsonObject()) {
                at = at.getAsJsonObject().get(name);
            } else if (at.isJsonArray() && !name.isEmpty() && name.length() <= 9
                    && name.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                int i = Integer.parseInt(name);
                at = i < at.getAsJsonArray().size() ? at.getAsJsonArray().get(i) : null;
            } else {
                return null;
            }
        }
        return at == null || at.isJsonNull() ? null : at;
    }

    /** The stored form; {@code null} for {@code null}. */
    public static String toStored(ClaimMapping mapping) {
        return mapping == null ? null : Json.GSON.toJson(mapping.toMap());
    }

    /** Reads the stored form; {@code null} for {@code null}. */
    public static ClaimMapping fromStored(String stored) {
        if (stored == null) {
            return null;
        }
        JsonObject o = JsonParser.parseString(stored).getAsJsonObject();
        return new ClaimMapping(str(o, "authorizationEndpoint"), str(o, "tokenEndpoint"), str(o, "userinfoUrl"),
                str(o, "subject"), str(o, "username"), str(o, "displayName"), str(o, "email"));
    }

    /** The fields that are set, in a fixed order (for JSON). */
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "authorizationEndpoint", authorizationEndpoint);
        put(m, "tokenEndpoint", tokenEndpoint);
        put(m, "userinfoUrl", userinfoUrl);
        put(m, "subject", subject);
        put(m, "username", username);
        put(m, "displayName", displayName);
        put(m, "email", email);
        return m;
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw invalid("claimMapping." + key + " must be a string");
        }
        String s = e.getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String url(JsonObject o, String key) {
        String value = string(o, key);
        if (value == null) {
            throw invalid("claimMapping." + key + " is required for an OAuth provider");
        }
        return Providers.requireUrl("claimMapping." + key, value, true);
    }

    private static String path(JsonObject o, String key) {
        String value = string(o, key);
        if (value == null) {
            return null;
        }
        if (value.length() > PATH_MAX) {
            throw invalid("claimMapping." + key + " is longer than " + PATH_MAX + " characters");
        }
        for (String name : value.split("\\.", -1)) {
            if (name.isEmpty() || name.length() > 64 || !name.codePoints().noneMatch(
                    cp -> Character.isWhitespace(cp) || Character.isISOControl(cp))) {
                throw invalid("claimMapping." + key + " must be names separated by dots, without spaces");
            }
        }
        return value;
    }

    private static HttpError invalid(String message) {
        return new HttpError(400, "invalid_provider", message);
    }
}
