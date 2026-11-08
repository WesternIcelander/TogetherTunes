package io.siggi.togethertunes.config;

import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the configuration: a JSON config file, then environment-variable overrides, then
 * defaults. Every value is validated, and all problems are reported together in one
 * {@link ConfigException}, so a bad config stops the server at startup.
 *
 * <h2>Config file</h2>
 * A JSON object. Keys can be nested ({@code {"http": {"port": 8080}}}) or written flat with
 * dots ({@code {"http.port": 8080}}), or a mix. Numbers may also be given as strings. A
 * {@code null} value means "use the default". Unknown keys are errors (they are usually
 * typos). The file is optional: without one, everything comes from the environment and the
 * defaults.
 *
 * <h2>Environment variables</h2>
 * Each key {@code a.bKey} maps to {@code TOGETHERTUNES_A_B_KEY} (dots become underscores,
 * camelCase becomes snake case), e.g. {@code http.publicBaseUrl} is
 * {@code TOGETHERTUNES_HTTP_PUBLIC_BASE_URL}. An environment variable wins over the file. A
 * blank variable counts as unset. List values ({@code http.trustedProxies}) are
 * comma-separated. {@link #CONFIG_FILE_ENV} names the config file.
 */
public final class ConfigLoader {
    /** Prefix of every environment variable the server reads. */
    public static final String ENV_PREFIX = "TOGETHERTUNES_";

    /** Environment variable naming the config file (the {@code --config} option wins). */
    public static final String CONFIG_FILE_ENV = ENV_PREFIX + "CONFIG";

    /** Config file used when neither {@code --config} nor {@link #CONFIG_FILE_ENV} is given, if it exists. */
    public static final String DEFAULT_CONFIG_FILE = "config.json";

    static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";
    static final String POSTGRES_URL_PREFIX = "jdbc:postgresql:";

    public static final String HTTP_BIND = "http.bind";
    public static final String HTTP_PORT = "http.port";
    public static final String HTTP_PUBLIC_BASE_URL = "http.publicBaseUrl";
    public static final String HTTP_TRUSTED_PROXIES = "http.trustedProxies";
    public static final String DATA_DIR = "data.dir";
    public static final String DB_URL = "db.url";
    public static final String TOOLS_FFMPEG = "tools.ffmpeg";
    public static final String TOOLS_FFPROBE = "tools.ffprobe";
    public static final String TOOLS_YTDLP = "tools.ytdlp";
    public static final String OUTPUT_PROFILE = "output.profile";
    public static final String INGEST_WORKERS = "ingest.workers";
    public static final String INGEST_MAX_UPLOAD_BYTES = "ingest.maxUploadBytes";
    public static final String INGEST_MAX_DURATION_SECONDS = "ingest.maxDurationSeconds";
    public static final String OVERLAY_WORKERS = "overlay.workers";
    public static final String OVERLAY_CACHE_MAX_BYTES = "overlay.cacheMaxBytes";

    /** Every key, in documentation order ({@code docs/architecture.md#configuration}). */
    public static final List<String> KEYS = List.of(
            HTTP_BIND, HTTP_PORT, HTTP_PUBLIC_BASE_URL, DATA_DIR, DB_URL,
            TOOLS_FFMPEG, TOOLS_FFPROBE, TOOLS_YTDLP, OUTPUT_PROFILE,
            INGEST_WORKERS, INGEST_MAX_UPLOAD_BYTES, INGEST_MAX_DURATION_SECONDS,
            OVERLAY_WORKERS, OVERLAY_CACHE_MAX_BYTES, HTTP_TRUSTED_PROXIES);

    private static final long KIB = 1024L;
    private static final long GIB = KIB * KIB * KIB;
    static final long DEFAULT_MAX_UPLOAD_BYTES = 4 * GIB;
    static final int DEFAULT_MAX_DURATION_SECONDS = 1800;
    static final long DEFAULT_OVERLAY_CACHE_MAX_BYTES = 20 * GIB;
    /** Sanity cap for worker counts; anything larger is almost certainly a mistake. */
    static final int MAX_WORKERS = 256;

    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*([KMGT]i?B|B)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    /** The result of a load: the config plus non-fatal warnings to log. */
    public record Loaded(Config config, Path configFile, List<String> warnings) {
        public Loaded {
            warnings = List.copyOf(warnings);
        }
    }

    /** A raw value before validation: a scalar ({@code text}) or a list, plus where it came from. */
    private record Raw(String source, String text, List<String> list) {
    }

    private final Map<String, String> env;
    private final int availableProcessors;
    private final Path workingDir;

    /**
     * @param env                 the environment (normally {@link System#getenv()})
     * @param availableProcessors CPU count, for the {@code ingest.workers} default
     * @param workingDir          directory relative paths are resolved against
     */
    public ConfigLoader(Map<String, String> env, int availableProcessors, Path workingDir) {
        this.env = Map.copyOf(env);
        this.availableProcessors = availableProcessors;
        this.workingDir = workingDir.toAbsolutePath().normalize();
    }

    /** A loader for this process: real environment, CPU count and working directory. */
    public static ConfigLoader forProcess() {
        return new ConfigLoader(System.getenv(), Runtime.getRuntime().availableProcessors(), Path.of(""));
    }

    /** The environment variable that overrides {@code key}. */
    public static String envName(String key) {
        StringBuilder sb = new StringBuilder(ENV_PREFIX);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                sb.append('_');
            } else if (Character.isUpperCase(c)) {
                sb.append('_').append(c);
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * Loads the configuration.
     *
     * @param explicitConfigFile the file given with {@code --config}, or null. Without it,
     *                           {@link #CONFIG_FILE_ENV} is used, then {@link #DEFAULT_CONFIG_FILE}
     *                           if it exists. An explicitly named file must exist.
     */
    public Loaded load(Path explicitConfigFile) throws ConfigException {
        Path file;
        boolean required;
        if (explicitConfigFile != null) {
            file = explicitConfigFile;
            required = true;
        } else if (!isBlank(env.get(CONFIG_FILE_ENV))) {
            try {
                file = Path.of(env.get(CONFIG_FILE_ENV).trim());
            } catch (InvalidPathException e) {
                throw new ConfigException(CONFIG_FILE_ENV + ": invalid path: " + e.getMessage());
            }
            required = true;
        } else {
            file = Path.of(DEFAULT_CONFIG_FILE);
            required = false;
        }
        file = workingDir.resolve(file).normalize();

        String json = null;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            if (required) {
                throw new ConfigException("Config file not found: " + file);
            }
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file " + file + ": " + e);
        }
        if (json == null) {
            return loadFromJson(null, null);
        }
        return loadFromJson(file, json);
    }

    /**
     * Loads the configuration from JSON text (null for "no config file") plus the environment.
     *
     * @param file only used in messages
     */
    public Loaded loadFromJson(Path file, String json) throws ConfigException {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Raw> raw = new LinkedHashMap<>();

        if (json != null) {
            String where = file == null ? "config" : "config file " + file;
            try {
                readJson(new StringReader(json), raw);
            } catch (IOException | JsonParseException | IllegalStateException e) {
                throw new ConfigException(where + ": invalid JSON: " + e.getMessage());
            }
            for (String key : raw.keySet()) {
                if (!KEYS.contains(key)) {
                    problems.add(where + ": unknown key '" + key + "'" + suggestion(key));
                }
            }
            raw.keySet().retainAll(KEYS);
        }

        for (String key : KEYS) {
            String name = envName(key);
            String value = env.get(name);
            if (isBlank(value)) {
                continue;
            }
            String source = "environment variable " + name;
            if (key.equals(HTTP_TRUSTED_PROXIES)) {
                List<String> items = new ArrayList<>();
                for (String part : value.split(",")) {
                    if (!part.isBlank()) {
                        items.add(part.trim());
                    }
                }
                raw.put(key, new Raw(source, null, items));
            } else {
                raw.put(key, new Raw(source, value.trim(), null));
            }
        }
        Set<String> known = new TreeSet<>();
        for (String key : KEYS) {
            known.add(envName(key));
        }
        known.add(CONFIG_FILE_ENV);
        for (String name : new TreeSet<>(env.keySet())) {
            if (name.startsWith(ENV_PREFIX) && !known.contains(name)) {
                warnings.add("Ignoring unknown environment variable " + name);
            }
        }

        Values v = new Values(raw, problems);

        String bind = v.string(HTTP_BIND, "0.0.0.0");
        if (bind != null && bind.isEmpty()) {
            problems.add(v.source(HTTP_BIND) + ": must not be empty");
        }
        int port = v.integer(HTTP_PORT, 8080, 1, 65535);
        String publicBaseUrl = v.check(HTTP_PUBLIC_BASE_URL, null, ConfigLoader::parsePublicBaseUrl);
        if (publicBaseUrl == null && !v.isSet(HTTP_PUBLIC_BASE_URL)) {
            problems.add(HTTP_PUBLIC_BASE_URL + " is required (set it in the config file or with "
                    + envName(HTTP_PUBLIC_BASE_URL) + "), e.g. https://music.example.com");
        }
        List<String> trustedProxies = v.list(HTTP_TRUSTED_PROXIES, ConfigLoader::parseCidr);

        Path dataDir = v.check(DATA_DIR, workingDir.resolve("data"), s -> {
            if (s.isEmpty()) {
                throw new IllegalArgumentException("must not be empty");
            }
            try {
                return workingDir.resolve(s).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("invalid path: " + e.getMessage());
            }
        });
        if (dataDir != null && Files.exists(dataDir) && !Files.isDirectory(dataDir)) {
            problems.add(v.source(DATA_DIR) + ": " + dataDir + " exists but is not a directory");
        }
        String defaultDbUrl = dataDir == null ? null
                : SQLITE_URL_PREFIX + dataDir.resolve("db").resolve("media.db");
        String dbUrl = v.check(DB_URL, defaultDbUrl, s -> {
            if (s.startsWith(SQLITE_URL_PREFIX)) {
                if (s.length() == SQLITE_URL_PREFIX.length()) {
                    throw new IllegalArgumentException("SQLite URL needs a file path");
                }
            } else if (s.startsWith(POSTGRES_URL_PREFIX)) {
                if (s.length() == POSTGRES_URL_PREFIX.length()) {
                    throw new IllegalArgumentException("PostgreSQL URL needs a host and database");
                }
            } else {
                throw new IllegalArgumentException("must start with " + SQLITE_URL_PREFIX + " or " + POSTGRES_URL_PREFIX);
            }
            return s;
        });

        String ffmpeg = tool(v, TOOLS_FFMPEG, "ffmpeg", problems, warnings);
        String ffprobe = tool(v, TOOLS_FFPROBE, "ffprobe", problems, warnings);
        String ytdlp = tool(v, TOOLS_YTDLP, "yt-dlp", problems, warnings);

        OutputProfile profile = v.check(OUTPUT_PROFILE, OutputProfile.DEFAULT, s -> {
            OutputProfile p = OutputProfile.byId(s);
            if (p == null) {
                List<String> ids = new ArrayList<>();
                for (OutputProfile o : OutputProfile.values()) {
                    ids.add(o.id());
                }
                throw new IllegalArgumentException("must be one of " + String.join(", ", ids));
            }
            return p;
        });

        int ingestWorkers = v.integer(INGEST_WORKERS, Math.max(1, availableProcessors / 2), 1, MAX_WORKERS);
        long maxUpload = v.size(INGEST_MAX_UPLOAD_BYTES, DEFAULT_MAX_UPLOAD_BYTES, 1);
        int maxDuration = v.integer(INGEST_MAX_DURATION_SECONDS, DEFAULT_MAX_DURATION_SECONDS, 1, Integer.MAX_VALUE);
        int overlayWorkers = v.integer(OVERLAY_WORKERS, 1, 1, MAX_WORKERS);
        long overlayCache = v.size(OVERLAY_CACHE_MAX_BYTES, DEFAULT_OVERLAY_CACHE_MAX_BYTES, 0);

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        Config config = new Config(bind, port, publicBaseUrl, trustedProxies, dataDir, dbUrl,
                ffmpeg, ffprobe, ytdlp, profile, ingestWorkers, maxUpload, maxDuration,
                overlayWorkers, overlayCache);
        return new Loaded(config, json == null ? null : file, warnings);
    }

    // ---- JSON ----

    private static void readJson(Reader reader, Map<String, Raw> out) throws IOException {
        JsonReader in = new JsonReader(reader);
        in.setStrictness(Strictness.STRICT);
        if (in.peek() != JsonToken.BEGIN_OBJECT) {
            throw new JsonParseException("top level must be an object");
        }
        readObject(in, "", out);
        if (in.peek() != JsonToken.END_DOCUMENT) {
            throw new JsonParseException("unexpected content after the top-level object");
        }
    }

    private static void readObject(JsonReader in, String prefix, Map<String, Raw> out) throws IOException {
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            String key = prefix + name;
            switch (in.peek()) {
                case BEGIN_OBJECT -> readObject(in, key + ".", out);
                case BEGIN_ARRAY -> {
                    List<String> items = new ArrayList<>();
                    in.beginArray();
                    while (in.hasNext()) {
                        JsonToken t = in.peek();
                        if (t != JsonToken.STRING && t != JsonToken.NUMBER) {
                            throw new JsonParseException("'" + key + "': array items must be strings");
                        }
                        items.add(in.nextString());
                    }
                    in.endArray();
                    put(out, key, new Raw("config key '" + key + "'", null, items));
                }
                case NULL -> {
                    in.nextNull();
                    put(out, key, null);
                }
                case BOOLEAN -> put(out, key, new Raw("config key '" + key + "'", Boolean.toString(in.nextBoolean()), null));
                default -> put(out, key, new Raw("config key '" + key + "'", in.nextString(), null));
            }
        }
        in.endObject();
    }

    private static void put(Map<String, Raw> out, String key, Raw value) {
        if (out.containsKey(key)) {
            throw new JsonParseException("duplicate key '" + key + "'");
        }
        out.put(key, value);
    }

    private static String suggestion(String key) {
        for (String known : KEYS) {
            if (known.equalsIgnoreCase(key) || known.replace(".", "").equalsIgnoreCase(key.replace(".", ""))) {
                return " (did you mean '" + known + "'?)";
            }
        }
        return "";
    }

    // ---- value parsing ----

    /** Typed access to the raw values; problems are collected rather than thrown. */
    private static final class Values {
        private final Map<String, Raw> raw;
        private final List<String> problems;

        Values(Map<String, Raw> raw, List<String> problems) {
            this.raw = raw;
            this.problems = problems;
        }

        String source(String key) {
            Raw r = raw.get(key);
            return r == null ? key : r.source();
        }

        boolean isSet(String key) {
            return raw.get(key) != null;
        }

        String string(String key, String dflt) {
            return check(key, dflt, s -> s);
        }

        <T> T check(String key, T dflt, Parser<T> parser) {
            Raw r = raw.get(key);
            if (r == null) {
                return dflt;
            }
            if (r.text() == null) {
                problems.add(r.source() + ": must be a single value, not a list");
                return null;
            }
            try {
                return parser.parse(r.text());
            } catch (IllegalArgumentException e) {
                problems.add(r.source() + ": " + e.getMessage() + " (got '" + r.text() + "')");
                return null;
            }
        }

        int integer(String key, int dflt, int min, int max) {
            Integer value = check(key, dflt, s -> {
                long n = parseLong(s);
                if (n < min || n > max) {
                    throw new IllegalArgumentException(max == Integer.MAX_VALUE
                            ? "must be at least " + min
                            : "must be between " + min + " and " + max);
                }
                return (int) n;
            });
            return value == null ? 0 : value;
        }

        long size(String key, long dflt, long min) {
            Long value = check(key, dflt, s -> {
                long n = parseSize(s);
                if (n < min) {
                    throw new IllegalArgumentException("must be at least " + min + " bytes");
                }
                return n;
            });
            return value == null ? 0 : value;
        }

        List<String> list(String key, Parser<String> itemParser) {
            Raw r = raw.get(key);
            if (r == null) {
                return List.of();
            }
            List<String> items = r.list();
            if (items == null) {
                problems.add(r.source() + ": must be a list");
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String item : items) {
                try {
                    result.add(itemParser.parse(item.trim()));
                } catch (IllegalArgumentException e) {
                    problems.add(r.source() + ": " + e.getMessage() + " (got '" + item + "')");
                }
            }
            return result;
        }
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(String s);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("must be a whole number");
        }
    }

    /**
     * Parses a byte size: a plain number of bytes, or a number with a binary unit
     * ({@code B}, {@code KiB}, {@code MiB}, {@code GiB}, {@code TiB}; {@code KB} etc. are read
     * as binary units too, and case is ignored).
     */
    static long parseSize(String s) {
        Matcher m = SIZE.matcher(s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("must be a byte count, optionally with a unit (KiB, MiB, GiB, TiB)");
        }
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("is too large");
        }
        String unit = m.group(2) == null ? "B" : m.group(2).toUpperCase(Locale.ROOT);
        int shift = switch (unit.charAt(0)) {
            case 'K' -> 10;
            case 'M' -> 20;
            case 'G' -> 30;
            case 'T' -> 40;
            default -> 0;
        };
        if (n > (Long.MAX_VALUE >> shift)) {
            throw new IllegalArgumentException("is too large");
        }
        return n << shift;
    }

    /**
     * Validates {@code http.publicBaseUrl}: an absolute http(s) URL with a host, no user
     * info, query or fragment. Returns it without a trailing slash.
     */
    static String parsePublicBaseUrl(String s) {
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("is not a valid URL: " + e.getReason());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("must be an absolute http:// or https:// URL");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("must include a host name");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("must not contain user info");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("must not contain a query or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(scheme.toLowerCase(Locale.ROOT)).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(path);
        return sb.toString();
    }

    /**
     * Validates a CIDR ({@code 10.0.0.0/8}, {@code 2001:db8::/32}) or a single address (taken
     * as {@code /32} or {@code /128}). Never does DNS lookups. Returns the normalised form.
     */
    static String parseCidr(String s) {
        int slash = s.indexOf('/');
        String addr = slash < 0 ? s : s.substring(0, slash);
        boolean v4 = IPV4.matcher(addr).matches();
        if (v4) {
            for (String octet : addr.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    throw new IllegalArgumentException("not a valid IPv4 address");
                }
            }
        } else if (!(addr.contains(":") && IPV6_CHARS.matcher(addr).matches())) {
            throw new IllegalArgumentException("must be an IPv4 or IPv6 address or CIDR");
        }
        InetAddress inet;
        try {
            // Only literals reach this point, so this never resolves a host name.
            inet = InetAddress.getByName(v4 ? addr : "[" + addr + "]");
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not a valid IP address");
        }
        int bits = inet.getAddress().length * 8;
        int prefix = bits;
        if (slash >= 0) {
            String p = s.substring(slash + 1);
            if (!p.matches("\\d{1,3}")) {
                throw new IllegalArgumentException("invalid prefix length");
            }
            prefix = Integer.parseInt(p);
            if (prefix > bits) {
                throw new IllegalArgumentException("prefix length must be at most " + bits);
            }
        }
        byte[] bytes = inet.getAddress();
        for (int i = 0; i < bytes.length; i++) {
            int keep = Math.max(0, Math.min(8, prefix - i * 8));
            if ((bytes[i] & (0xFF >>> keep)) != 0) {
                throw new IllegalArgumentException("host bits set beyond the /" + prefix + " prefix");
            }
        }
        String host = inet.getHostAddress();
        return host + "/" + prefix;
    }

    // ---- tools ----

    /**
     * A tool given as a path must be an executable file. A bare command name is looked up on
     * {@code PATH} and replaced by its absolute path. A configured name that isn't on
     * {@code PATH} is an error; a missing default is only a warning (the features that need
     * the tool fail later with a clear message).
     */
    private String tool(Values v, String key, String dflt, List<String> problems, List<String> warnings) {
        boolean explicit = v.isSet(key);
        String value = v.string(key, dflt);
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            problems.add(v.source(key) + ": must not be empty");
            return null;
        }
        if (value.indexOf('/') >= 0 || value.indexOf(java.io.File.separatorChar) >= 0) {
            Path p;
            try {
                p = workingDir.resolve(value).normalize();
            } catch (InvalidPathException e) {
                problems.add(v.source(key) + ": invalid path (got '" + value + "')");
                return null;
            }
            if (!Files.isRegularFile(p) || !Files.isExecutable(p)) {
                problems.add(v.source(key) + ": not an executable file: " + p);
                return null;
            }
            return p.toString();
        }
        Path found = findOnPath(value);
        if (found != null) {
            return found.toString();
        }
        if (explicit) {
            problems.add(v.source(key) + ": '" + value + "' was not found on PATH");
        } else {
            warnings.add(value + " was not found on PATH; set " + key + " (or " + envName(key)
                    + ") if it is installed elsewhere");
        }
        return value;
    }

    private Path findOnPath(String command) {
        String path = env.get("PATH");
        if (path == null) {
            return null;
        }
        boolean windows = java.io.File.separatorChar == '\\';
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            try {
                for (String suffix : windows ? List.of("", ".exe") : List.of("")) {
                    Path candidate = workingDir.resolve(dir).resolve(command + suffix);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().normalize();
                    }
                }
            } catch (InvalidPathException ignored) {
                // A malformed PATH entry; skip it.
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
