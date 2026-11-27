package io.siggi.togethertunes.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One server setting: its name, its type (as a JSON codec), its default and its validation.
 * The keys themselves are the constants in {@link Settings}.
 *
 * @param <T> the Java type of the value
 */
public final class SettingKey<T> {
    /** Converts between a value and its JSON form, rejecting anything invalid. */
    interface Codec<T> {
        /** Parses and validates; throws {@link IllegalArgumentException} for a bad value. */
        T decode(JsonElement json);

        JsonElement encode(T value);
    }

    private final String name;
    private final Class<T> type;
    private final T defaultValue;
    private final Codec<T> codec;
    private final boolean nullable;
    /** Whether a string setting is text of several lines (the admin page shows a text area). */
    private boolean multiline;
    /**
     * Whether the value is a secret ({@link #secret}): the admin API never returns it, and no
     * message (validation errors, the audit log) ever contains it.
     */
    private boolean secret;

    private SettingKey(String name, Class<T> type, T defaultValue, boolean nullable, Codec<T> codec) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.codec = Objects.requireNonNull(codec);
        this.nullable = nullable;
        if (defaultValue == null && !nullable) {
            throw new IllegalArgumentException(name + ": a non-nullable setting needs a default");
        }
        // The default must itself pass validation.
        this.defaultValue = defaultValue == null ? null : codec.decode(codec.encode(defaultValue));
    }

    /** The setting's name, which is its key in the {@code settings} table. */
    public String name() {
        return name;
    }

    /** The Java type of the value. */
    public Class<T> type() {
        return type;
    }

    /** The value used while the setting isn't stored (or the stored value is unusable). */
    public T defaultValue() {
        return defaultValue;
    }

    /** Whether {@code null} is a valid value ("not set", e.g. no cookies file). */
    public boolean nullable() {
        return nullable;
    }

    /** Whether this is a string setting holding text of several lines ({@link #text}). */
    public boolean multiline() {
        return multiline;
    }

    /** Whether this setting's value is a secret ({@link #secret}). */
    public boolean secret() {
        return secret;
    }

    /** Parses and validates a JSON value; {@link IllegalArgumentException} if it is invalid. */
    public T decode(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            if (nullable) {
                return null;
            }
            throw new IllegalArgumentException(name + " must not be null");
        }
        try {
            return codec.decode(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ": " + e.getMessage(), secret ? null : e);
        } catch (RuntimeException e) {
            // Gson accessors throw UnsupportedOperationException / IllegalStateException /
            // NumberFormatException for the wrong JSON type.
            throw new IllegalArgumentException(name + ": invalid value" + (secret ? "" : " " + json),
                    secret ? null : e);
        }
    }

    /** The JSON form of a value, after validating it. */
    public JsonElement encode(T value) {
        if (value == null) {
            if (nullable) {
                return JsonNull.INSTANCE;
            }
            throw new IllegalArgumentException(name + " must not be null");
        }
        JsonElement json = codec.encode(type.cast(value));
        decode(json); // validates
        return json;
    }

    @Override
    public String toString() {
        return name;
    }

    // ------------------------------------------------------------- factories

    static SettingKey<Boolean> bool(String name, boolean defaultValue) {
        return new SettingKey<>(name, Boolean.class, defaultValue, false, new Codec<>() {
            @Override
            public Boolean decode(JsonElement json) {
                JsonPrimitive p = primitive(json);
                if (!p.isBoolean()) {
                    throw new IllegalArgumentException("expected true or false, got " + json);
                }
                return p.getAsBoolean();
            }

            @Override
            public JsonElement encode(Boolean value) {
                return new JsonPrimitive(value);
            }
        });
    }

    static SettingKey<Integer> integer(String name, int defaultValue, int min, int max) {
        return new SettingKey<>(name, Integer.class, defaultValue, false, new Codec<>() {
            @Override
            public Integer decode(JsonElement json) {
                long v = wholeNumber(json);
                if (v < min || v > max) {
                    throw new IllegalArgumentException("must be between " + min + " and " + max + ", got " + v);
                }
                return (int) v;
            }

            @Override
            public JsonElement encode(Integer value) {
                return new JsonPrimitive(value);
            }
        });
    }

    /**
     * A whole number from {@code min} to {@code max}, or {@code null} for "not set" (e.g. the
     * output profile's own video bitrate).
     */
    static SettingKey<Integer> optionalInteger(String name, int min, int max) {
        return new SettingKey<>(name, Integer.class, null, true, new Codec<>() {
            @Override
            public Integer decode(JsonElement json) {
                long v = wholeNumber(json);
                if (v < min || v > max) {
                    throw new IllegalArgumentException("must be between " + min + " and " + max + ", got " + v);
                }
                return (int) v;
            }

            @Override
            public JsonElement encode(Integer value) {
                return new JsonPrimitive(value);
            }
        });
    }

    /** A size in bytes, as a JSON whole number from {@code min} to {@code max}. */
    static SettingKey<Long> bytes(String name, long defaultValue, long min, long max) {
        return new SettingKey<>(name, Long.class, defaultValue, false, new Codec<>() {
            @Override
            public Long decode(JsonElement json) {
                long v = wholeNumber(json);
                if (v < min || v > max) {
                    throw new IllegalArgumentException("must be between " + min + " and " + max + " bytes, got " + v);
                }
                return v;
            }

            @Override
            public JsonElement encode(Long value) {
                return new JsonPrimitive(value);
            }
        });
    }

    /** A free-form string; {@code null} (and the empty string, stored as null) means unset. */
    static SettingKey<String> optionalString(String name) {
        return new SettingKey<>(name, String.class, null, true, new Codec<>() {
            @Override
            public String decode(JsonElement json) {
                JsonPrimitive p = primitive(json);
                if (!p.isString()) {
                    throw new IllegalArgumentException("expected a string, got " + json);
                }
                return p.getAsString();
            }

            @Override
            public JsonElement encode(String value) {
                return value.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive(value);
            }
        });
    }

    /**
     * Plain text of several lines (e.g. a message shown on a page), empty by default. Line breaks
     * are normalised to {@code \n} and kept; leading and trailing whitespace is dropped, so
     * whitespace alone is empty. At most {@code maxLength} code points.
     */
    static SettingKey<String> text(String name, int maxLength) {
        SettingKey<String> key = new SettingKey<>(name, String.class, "", false, new Codec<>() {
            @Override
            public String decode(JsonElement json) {
                JsonPrimitive p = primitive(json);
                if (!p.isString()) {
                    throw new IllegalArgumentException("expected a string, got " + json);
                }
                String s = p.getAsString().replace("\r\n", "\n").replace('\r', '\n').strip();
                if (s.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("must not contain NUL characters");
                }
                int length = s.codePointCount(0, s.length());
                if (length > maxLength) {
                    throw new IllegalArgumentException("at most " + maxLength + " characters, got " + length);
                }
                return s;
            }

            @Override
            public JsonElement encode(String value) {
                return new JsonPrimitive(value.replace("\r\n", "\n").replace('\r', '\n').strip());
            }
        });
        key.multiline = true;
        return key;
    }

    /**
     * A single-line secret string (e.g. a header value an API checks), empty by default (= none).
     * Leading and trailing whitespace is dropped; control characters and more than
     * {@code maxLength} characters are refused. Validation messages never contain the value.
     */
    static SettingKey<String> secret(String name, int maxLength) {
        SettingKey<String> key = new SettingKey<>(name, String.class, "", false, new Codec<>() {
            @Override
            public String decode(JsonElement json) {
                if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("expected a string");
                }
                String s = json.getAsString().strip();
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c < 0x20 || c == 0x7f) {
                        throw new IllegalArgumentException("must not contain control characters");
                    }
                }
                if (s.length() > maxLength) {
                    throw new IllegalArgumentException("at most " + maxLength + " characters");
                }
                return s;
            }

            @Override
            public JsonElement encode(String value) {
                return new JsonPrimitive(value.strip());
            }
        });
        key.secret = true;
        return key;
    }

    /**
     * An HTTP header name (an RFC 9110 token of at most 100 characters), e.g. {@code X-Api-Key}.
     */
    static SettingKey<String> headerName(String name, String defaultValue) {
        return new SettingKey<>(name, String.class, defaultValue, false, new Codec<>() {
            @Override
            public String decode(JsonElement json) {
                JsonPrimitive p = primitive(json);
                if (!p.isString()) {
                    throw new IllegalArgumentException("expected a string, got " + json);
                }
                String s = p.getAsString().strip();
                if (s.isEmpty() || s.length() > 100 || !s.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    throw new IllegalArgumentException("expected an HTTP header name such as X-Api-Key");
                }
                return s;
            }

            @Override
            public JsonElement encode(String value) {
                return new JsonPrimitive(value.strip());
            }
        });
    }

    /**
     * An optional {@code http://} or {@code https://} base URL (host required; no query, fragment
     * or user info). {@code null} (and the empty string, stored as null) means unset. A trailing
     * slash is dropped.
     */
    static SettingKey<String> optionalUrl(String name) {
        return optionalUrl(name, false);
    }

    /**
     * Like {@link #optionalUrl(String)}; with {@code extractorArg}, the URL also mustn't contain
     * {@code ,} or {@code ;}, which split a yt-dlp {@code --extractor-args} value.
     */
    static SettingKey<String> optionalUrl(String name, boolean extractorArg) {
        return new SettingKey<>(name, String.class, null, true, new Codec<>() {
            @Override
            public String decode(JsonElement json) {
                JsonPrimitive p = primitive(json);
                if (!p.isString()) {
                    throw new IllegalArgumentException("expected a string, got " + json);
                }
                String s = p.getAsString().strip();
                if (s.isEmpty()) {
                    return null;
                }
                java.net.URI uri;
                try {
                    uri = new java.net.URI(s);
                } catch (java.net.URISyntaxException e) {
                    throw new IllegalArgumentException("not a valid URL");
                }
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
                if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                    throw new IllegalArgumentException("expected an http:// or https:// URL without query or user info");
                }
                if (extractorArg && (s.indexOf(',') >= 0 || s.indexOf(';') >= 0)) {
                    throw new IllegalArgumentException("the URL must not contain , or ;");
                }
                while (s.endsWith("/")) {
                    s = s.substring(0, s.length() - 1);
                }
                return s;
            }

            @Override
            public JsonElement encode(String value) {
                return value.isBlank() ? JsonNull.INSTANCE : new JsonPrimitive(value);
            }
        });
    }

    /** An enum stored by its {@code wireName}. */
    static <E extends Enum<E>> SettingKey<E> enumeration(String name, Class<E> type, E defaultValue,
                                                         Function<E, String> wireName) {
        return new SettingKey<>(name, type, defaultValue, false, new Codec<>() {
            @Override
            public E decode(JsonElement json) {
                JsonPrimitive p = primitive(json);
                if (p.isString()) {
                    for (E e : type.getEnumConstants()) {
                        if (wireName.apply(e).equals(p.getAsString())) {
                            return e;
                        }
                    }
                }
                StringBuilder allowed = new StringBuilder();
                for (E e : type.getEnumConstants()) {
                    allowed.append(allowed.isEmpty() ? "" : " | ").append(wireName.apply(e));
                }
                throw new IllegalArgumentException("expected " + allowed + ", got " + json);
            }

            @Override
            public JsonElement encode(E value) {
                return new JsonPrimitive(wireName.apply(value));
            }
        });
    }

    static SettingKey<UploadQuota> uploadQuota(String name, UploadQuota defaultValue) {
        return new SettingKey<>(name, UploadQuota.class, defaultValue, false, new Codec<>() {
            @Override
            public UploadQuota decode(JsonElement json) {
                if (!json.isJsonObject()) {
                    throw new IllegalArgumentException("expected an object, got " + json);
                }
                JsonObject o = json.getAsJsonObject();
                for (String k : o.keySet()) {
                    if (!k.equals("perHour") && !k.equals("maxItemBytes")) {
                        throw new IllegalArgumentException("unknown field " + k);
                    }
                }
                if (!o.has("perHour") || !o.has("maxItemBytes")) {
                    throw new IllegalArgumentException("needs perHour and maxItemBytes");
                }
                long perHour = wholeNumber(o.get("perHour"));
                long maxItemBytes = wholeNumber(o.get("maxItemBytes"));
                if (perHour > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("perHour is too large");
                }
                return new UploadQuota((int) perHour, maxItemBytes);
            }

            @Override
            public JsonElement encode(UploadQuota value) {
                JsonObject o = new JsonObject();
                o.addProperty("perHour", value.perHour());
                o.addProperty("maxItemBytes", value.maxItemBytes());
                return o;
            }
        });
    }

    /**
     * A list of strings, as a JSON array. Each entry is trimmed and its whitespace collapsed;
     * blank entries and exact duplicates are dropped (first one kept). At most {@code maxItems}
     * entries of at most {@code maxLength} code points each.
     */
    static SettingKey<StringList> stringList(String name, int maxItems, int maxLength) {
        return new SettingKey<>(name, StringList.class, StringList.EMPTY, false, new Codec<>() {
            @Override
            public StringList decode(JsonElement json) {
                if (!json.isJsonArray()) {
                    throw new IllegalArgumentException("expected an array of strings, got " + json);
                }
                LinkedHashSet<String> items = new LinkedHashSet<>();
                for (JsonElement e : json.getAsJsonArray()) {
                    JsonPrimitive p = primitive(e);
                    if (!p.isString()) {
                        throw new IllegalArgumentException("expected a string, got " + e);
                    }
                    String s = p.getAsString().replaceAll("(?U)\\s+", " ").strip();
                    if (s.isEmpty()) {
                        continue;
                    }
                    if (s.codePointCount(0, s.length()) > maxLength) {
                        throw new IllegalArgumentException("entries are at most " + maxLength
                                + " characters long: " + s);
                    }
                    items.add(s);
                }
                if (items.size() > maxItems) {
                    throw new IllegalArgumentException("at most " + maxItems + " entries, got " + items.size());
                }
                return new StringList(List.copyOf(items));
            }

            @Override
            public JsonElement encode(StringList value) {
                JsonArray a = new JsonArray();
                value.items().forEach(a::add);
                return a;
            }
        });
    }

    private static JsonPrimitive primitive(JsonElement json) {
        if (!json.isJsonPrimitive()) {
            throw new IllegalArgumentException("expected a single value, got " + json);
        }
        return json.getAsJsonPrimitive();
    }

    private static long wholeNumber(JsonElement json) {
        JsonPrimitive p = primitive(json);
        if (!p.isNumber()) {
            throw new IllegalArgumentException("expected a whole number, got " + json);
        }
        try {
            return p.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("expected a whole number, got " + json, e);
        }
    }
}
