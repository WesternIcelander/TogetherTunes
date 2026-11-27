package io.siggi.togethertunes.ytdlp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * yt-dlp's PO tokens through a provider server ({@code ytdlp.poTokenProviderUrl},
 * {@code docs/library-and-ingest.md#youtube-caching}).
 *
 * <p>yt-dlp (2025.05.22 and newer) gets PO tokens from provider plugins. The usual one,
 * bgutil-ytdlp-pot-provider, has an HTTP-server mode: a separate server (Docker image or Node app,
 * by default on {@code http://127.0.0.1:4416}) that its {@code bgutil:http} provider asks; yt-dlp
 * passes it the server's URL as the extractor argument
 * {@code youtubepot-bgutilhttp:base_url=<url>} (the old {@code youtube:getpot_bgutil_baseurl} is
 * refused since plugin 1.1.0). TogetherTunes neither downloads the plugin nor the server; this class
 * builds the argument, reads whether yt-dlp loaded the plugin, and checks that the server answers.
 */
public final class PoTokenProvider {
    /** The extractor-args key of the plugin's HTTP provider ({@code youtubepot-<PROVIDER_KEY lowercased>}). */
    public static final String EXTRACTOR_KEY = "youtubepot-bgutilhttp";
    /** The plugin's own default server URL, used when no URL is passed. */
    public static final String DEFAULT_URL = "http://127.0.0.1:4416";
    /** The HTTP provider's name in yt-dlp's provider list ({@code PROVIDER_NAME}). */
    static final String HTTP_PROVIDER = "bgutil:http";
    /** How long the Test button waits for the server's {@code /ping}. */
    public static final Duration PING_TIMEOUT = Duration.ofSeconds(5);

    /** A video ID for the plugin check; nothing is fetched (see {@link #pluginCheckArgs}). */
    private static final String CHECK_VIDEO = "https://www.youtube.com/watch?v=jNQXAC9IVRw";
    /** {@code [debug] [youtube] [pot] PO Token Providers: <list>}, printed with {@code --verbose}. */
    private static final Pattern PROVIDERS_LINE = Pattern.compile("\\[pot\\] PO Token Providers: (.*)$");
    /** One list entry: {@code name[-version][ (status, status)]}. */
    private static final Pattern ENTRY = Pattern.compile("(.+?)(?: \\(([^)]*)\\))?");

    private PoTokenProvider() {
    }

    /**
     * The arguments every yt-dlp call that talks to YouTube gets: nothing when {@code url} is null
     * or blank, else {@code --extractor-args youtubepot-bgutilhttp:base_url=<url>}. yt-dlp merges
     * several {@code --extractor-args} options by extractor key, so this doesn't replace arguments
     * for other keys (the same key given twice: the later wins).
     *
     * @throws IllegalArgumentException if the URL contains {@code ,} or {@code ;} (which would
     *                                  split the value; the setting refuses such URLs) or whitespace
     */
    public static List<String> args(String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        String u = url.strip();
        if (u.indexOf(',') >= 0 || u.indexOf(';') >= 0 || u.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("The PO token provider URL must not contain , ; or spaces");
        }
        return List.of("--extractor-args", EXTRACTOR_KEY + ":base_url=" + u);
    }

    /**
     * The command that shows whether yt-dlp loaded a PO token provider plugin, without contacting
     * YouTube: {@code --verbose} on a video URL makes the YouTube extractor print its provider list
     * when it starts, before it fetches anything; the unreachable proxy then fails the fetch at once.
     * (The plugin's own server check doesn't use the proxy, but it only runs when a token is needed.)
     */
    public static List<String> pluginCheckArgs() {
        return List.of("--verbose", "--simulate", "--no-playlist", "--no-cache-dir",
                "--proxy", "http://127.0.0.1:9", "--socket-timeout", "5", "--extractor-retries", "0",
                "--", CHECK_VIDEO);
    }

    /** One entry of yt-dlp's PO token provider list. */
    public record Provider(String name, String version, boolean external, boolean available) {
    }

    /**
     * What yt-dlp's verbose output says about PO token providers.
     *
     * @param listed    whether the output had the provider list (no list: yt-dlp too old for
     *                  provider plugins, or the check didn't get that far)
     * @param providers the listed providers (empty for "none")
     */
    public record PluginStatus(boolean listed, List<Provider> providers) {
        public PluginStatus {
            providers = List.copyOf(providers);
        }

        /** The plugin's HTTP provider, which uses the provider URL, if yt-dlp loaded it. */
        public Provider httpProvider() {
            for (Provider p : providers) {
                if (p.name().equals(HTTP_PROVIDER)) {
                    return p;
                }
            }
            return null;
        }

        /** Whether yt-dlp loaded a plugin provider that uses the provider URL. */
        public boolean loaded() {
            return httpProvider() != null;
        }
    }

    /**
     * Parses yt-dlp's verbose output (stderr) for the provider list, e.g.
     * {@code [debug] [youtube] [pot] PO Token Providers: bgutil:http-2.0.2 (external),
     * bgutil:script-deno-2.0.2 (external, unavailable)}, or {@code ... Providers: none}.
     */
    public static PluginStatus parse(String output) {
        if (output == null) {
            return new PluginStatus(false, List.of());
        }
        for (String raw : output.split("\n")) {
            Matcher m = PROVIDERS_LINE.matcher(raw.strip());
            if (m.find()) {
                return new PluginStatus(true, parseList(m.group(1).strip()));
            }
        }
        return new PluginStatus(false, List.of());
    }

    private static List<Provider> parseList(String list) {
        List<Provider> out = new ArrayList<>();
        if (list.isEmpty() || list.equals("none")) {
            return out;
        }
        // entries are separated by ", ", but a status list in parentheses has ", " too
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= list.length(); i++) {
            char c = i < list.length() ? list.charAt(i) : ',';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                String entry = list.substring(start, i).strip();
                if (!entry.isEmpty()) {
                    out.add(entry(entry));
                }
                start = i + 1;
            }
        }
        return out;
    }

    private static Provider entry(String entry) {
        Matcher m = ENTRY.matcher(entry);
        String nameVersion = entry;
        List<String> statuses = List.of();
        if (m.matches()) {
            nameVersion = m.group(1).strip();
            if (m.group(2) != null) {
                statuses = List.of(m.group(2).split(",")).stream().map(String::strip).toList();
            }
        }
        // the version is joined to the name with "-" and starts with a digit (e.g. bgutil:http-2.0.2)
        String name = nameVersion;
        String version = null;
        Matcher v = Pattern.compile("^(.*?)-(\\d[^-]*)$").matcher(nameVersion);
        if (v.matches()) {
            name = v.group(1);
            version = v.group(2);
        }
        return new Provider(name, version, statuses.contains("external"), !statuses.contains("unavailable"));
    }

    /**
     * The Test button's answer.
     *
     * @param reachable whether the server answered {@code /ping} with 200 and a JSON object
     * @param status    the HTTP status, or null if there was no answer
     * @param version   the server's version from the ping answer, or null
     * @param error     what went wrong, or null if it is reachable
     */
    public record PingResult(boolean reachable, Integer status, String version, String error) {
    }

    /**
     * {@code GET <baseUrl>/ping} (what the plugin itself asks; the answer is
     * {@code {"server_uptime": …, "version": "…"}}), without a proxy or redirects.
     */
    public static PingResult ping(String baseUrl, Duration timeout) throws InterruptedException {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(base + "/ping");
        } catch (IllegalArgumentException e) {
            return new PingResult(false, null, null, "not a valid URL");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();
        try (client) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new PingResult(false, response.statusCode(), null,
                        "the server answered HTTP " + response.statusCode() + " instead of a PO token provider's answer");
            }
            JsonElement json;
            try {
                json = JsonParser.parseString(response.body());
            } catch (RuntimeException e) {
                json = null;
            }
            if (!(json instanceof JsonObject o)) {
                return new PingResult(false, 200, null, "the server's answer isn't a PO token provider's answer");
            }
            JsonElement v = o.get("version");
            String version = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                    && !v.getAsString().isBlank() ? v.getAsString().strip() : null;
            if (version != null && version.length() > 64) {
                version = version.substring(0, 64);
            }
            return new PingResult(true, 200, version, null);
        } catch (HttpConnectTimeoutException e) {
            return new PingResult(false, null, null, "no connection within " + timeout.toSeconds() + " s");
        } catch (HttpTimeoutException e) {
            return new PingResult(false, null, null, "no answer within " + timeout.toSeconds() + " s");
        } catch (ConnectException e) {
            return new PingResult(false, null, null, "the connection was refused (is the server running?)");
        } catch (UnknownHostException e) {
            return new PingResult(false, null, null, "unknown host");
        } catch (IOException e) {
            Throwable cause = e.getCause() instanceof UnknownHostException ? e.getCause() : e;
            if (cause instanceof UnknownHostException) {
                return new PingResult(false, null, null, "unknown host");
            }
            return new PingResult(false, null, null, "it couldn't be reached (" + e.getClass().getSimpleName() + ")");
        } catch (IllegalArgumentException e) {
            return new PingResult(false, null, null, "not a valid URL");
        }
    }
}
