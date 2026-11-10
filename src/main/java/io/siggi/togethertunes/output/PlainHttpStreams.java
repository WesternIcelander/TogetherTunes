package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.PlainHttpRedirect;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Which plain-HTTP requests are served instead of redirected ({@link PlainHttpRedirect},
 * {@code http.insecureBaseUrl}, {@code docs/architecture.md#plain-http}): {@code GET}/{@code HEAD}
 * of a stream URL by slug of a public or unlisted room, i.e. {@code /live/{slug}.mp3},
 * {@code /live/{slug}.ts}, {@code /live/{slug}/index.m3u8} and {@code /live/{slug}/seg/{file}}.
 * Some players (internet-radio players in particular) have no TLS. Private rooms' token URLs are
 * never served over plain HTTP: the token is a capability and would travel unencrypted
 * (invariant 6). A token never has a slug's shape and a slug opens only a non-private room
 * ({@link StreamOutputs#KEY_MATCH}), so "the key is the slug of a public or unlisted room" is
 * exactly "a slug URL". Whether the room has that output enabled is left to the stream handler,
 * which answers the same way over HTTP and HTTPS.
 */
public final class PlainHttpStreams {
    private PlainHttpStreams() {
    }

    /** Whether {@code key} is the slug of a public or unlisted room. */
    @FunctionalInterface
    public interface PublicSlugs {
        boolean isPublicSlug(String key) throws SQLException;
    }

    /** {@link PublicSlugs} from the {@code rooms} table. */
    public static PublicSlugs from(Database db) {
        Objects.requireNonNull(db, "db");
        return key -> db.read(c -> {
            try (var ps = c.prepareStatement("SELECT 1 FROM rooms WHERE slug = ? AND visibility <> 'private'")) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** The exemption for {@link PlainHttpRedirect}: stream URLs by public or unlisted slug. */
    public static PlainHttpRedirect.Exemption exemption(PublicSlugs slugs) {
        Objects.requireNonNull(slugs, "slugs");
        return request -> {
            String method = request.method();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                return false;
            }
            String key = streamKey(request.path());
            return key != null && slugs.isPublicSlug(key);
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
