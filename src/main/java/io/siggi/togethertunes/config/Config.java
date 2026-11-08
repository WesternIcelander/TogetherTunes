package io.siggi.togethertunes.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * The validated server configuration. Built by {@link ConfigLoader}; every value has been
 * checked and defaulted, so consumers never see a missing or malformed value.
 *
 * @param httpBind                 address to listen on ({@code http.bind})
 * @param httpPort                 port to listen on ({@code http.port})
 * @param publicBaseUrl            public origin (plus optional path prefix) of the server, without
 *                                 a trailing slash ({@code http.publicBaseUrl})
 * @param trustedProxies           CIDRs whose {@code X-Forwarded-For} is trusted, normalised to
 *                                 {@code address/prefix} ({@code http.trustedProxies})
 * @param dataDir                  absolute, normalised data directory ({@code data.dir})
 * @param dbUrl                    JDBC URL, SQLite or PostgreSQL ({@code db.url})
 * @param ffmpeg                   ffmpeg command or path ({@code tools.ffmpeg})
 * @param ffprobe                  ffprobe command or path ({@code tools.ffprobe})
 * @param ytdlp                    yt-dlp command or path ({@code tools.ytdlp})
 * @param outputProfile            output profile ({@code output.profile})
 * @param ingestWorkers            ingest worker count ({@code ingest.workers})
 * @param ingestMaxUploadBytes     upload size limit ({@code ingest.maxUploadBytes})
 * @param ingestMaxDurationSeconds media duration limit for uploads and yt-dlp
 *                                 ({@code ingest.maxDurationSeconds})
 * @param overlayWorkers           concurrent overlay re-encodes ({@code overlay.workers})
 * @param overlayCacheMaxBytes     size limit of the overlay re-encode cache
 *                                 ({@code overlay.cacheMaxBytes})
 */
public record Config(
        String httpBind,
        int httpPort,
        String publicBaseUrl,
        List<String> trustedProxies,
        Path dataDir,
        String dbUrl,
        String ffmpeg,
        String ffprobe,
        String ytdlp,
        OutputProfile outputProfile,
        int ingestWorkers,
        long ingestMaxUploadBytes,
        int ingestMaxDurationSeconds,
        int overlayWorkers,
        long overlayCacheMaxBytes) {

    public Config {
        trustedProxies = List.copyOf(trustedProxies);
    }

    /** {@link #publicBaseUrl()} as a URI. */
    public URI publicBaseUri() {
        return URI.create(publicBaseUrl);
    }

    /** Whether {@link #dbUrl()} points at PostgreSQL (otherwise SQLite). */
    public boolean isPostgres() {
        return dbUrl.startsWith(ConfigLoader.POSTGRES_URL_PREFIX);
    }
}
