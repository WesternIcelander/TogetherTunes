package io.siggi.togethertunes.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
            throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Gson accessors throw UnsupportedOperationException / IllegalStateException /
            // NumberFormatException for the wrong JSON type.
            throw new IllegalArgumentException(name + ": invalid value " + json, e);
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
