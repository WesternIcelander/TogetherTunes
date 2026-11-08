package io.siggi.togethertunes.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** The shared Gson instance for request and response bodies. */
public final class Json {
    /** Thread-safe; nulls are left out of output, HTML characters are not escaped. */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }
}
