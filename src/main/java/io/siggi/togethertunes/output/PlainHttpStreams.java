package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.PlainHttpRedirect;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Which plain-HTTP requests are served instead of redirected ({@link PlainHttpRedirect},
 * {@code http.insecureBaseUrl}, {@code docs/architecture.md#plain-http}): {@code GET}/{@code HEAD}
 * of a stream URL, i.e. {@code /live/{key}.mp3}, {@code /live/{key}.ts},
 * {@code /live/{key}/index.m3u8} and {@code /live/{key}/seg/{file}}, whose key is the slug of a
 * public or unlisted room or the token of one of a private room's outputs (the same keys
 * {@link StreamOutputs#KEY_MATCH} opens). Some players (internet-radio players in particular) have
 * no TLS. A private room's token then travels unencrypted (Siggi, 2026-11-10: acceptable): the
 * stream endpoints record its first plain-HTTP use ({@link CleartextTokens}) and the outputs panel
 * warns the owner, who can rotate it. Whether the room has that output enabled, and whether the
 * token belongs to that output's kind, is left to the stream handler, which answers the same way
 * over HTTP and HTTPS (token check, rate limits, connection caps).
 */
public final class PlainHttpStreams {
    private PlainHttpStreams() {
    }

    /**
     * Whether {@code key} is a stream URL key: the slug of a public or unlisted room, or the token of
     * a private room's output.
     */
    @FunctionalInterface
    public interface StreamKeys {
        boolean isStreamKey(String key) throws SQLException;
    }

    /** {@link StreamKeys} from the {@code rooms} and {@code stream_outputs} tables. */
    public static StreamKeys from(Database db) {
        Objects.requireNonNull(db, "db");
        return key -> db.read(c -> {
            try (var ps = c.prepareStatement("SELECT 1 FROM rooms r WHERE (r.slug = ? AND r.visibility <> 'private')"
                    + " OR (r.visibility = 'private' AND EXISTS (SELECT 1 FROM stream_outputs so"
                    + " WHERE so.room_id = r.id AND so.token = ?))")) {
                ps.setString(1, key);
                ps.setString(2, key);
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** The exemption for {@link PlainHttpRedirect}: stream URLs by slug or private token. */
    public static PlainHttpRedirect.Exemption exemption(StreamKeys keys) {
        Objects.requireNonNull(keys, "keys");
        return request -> {
            String method = request.method();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                return false;
            }
            String key = streamKey(request.path());
            return key != null && keys.isStreamKey(key);
        };
    }

    /**
     * The URL key (slug or token) of a stream path ({@code /live/{key}.ts}, {@code .mp3},
     * {@code /live/{key}/index.m3u8}, {@code /live/{key}/seg/{file}}), or null for any other path.
     */
    static String streamKey(String path) {
        String prefix = "/live/";
        if (path == null || !path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String key;
        if (slash < 0) {
            if (rest.endsWith(".ts")) {
                key = rest.substring(0, rest.length() - ".ts".length());
            } else if (rest.endsWith(".mp3")) {
                key = rest.substring(0, rest.length() - ".mp3".length());
            } else {
                return null;
            }
        } else {
            key = rest.substring(0, slash);
            String tail = rest.substring(slash + 1);
            boolean playlist = tail.equals("index.m3u8");
            boolean segment = tail.startsWith("seg/") && tail.length() > "seg/".length()
                    && tail.indexOf('/', "seg/".length()) < 0;
            if (!playlist && !segment) {
                return null;
            }
        }
        return StreamOutputs.KEY_SHAPE.matcher(key).matches() ? key : null;
    }
}
