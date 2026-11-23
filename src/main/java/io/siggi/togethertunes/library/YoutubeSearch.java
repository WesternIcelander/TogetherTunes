package io.siggi.togethertunes.library;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.Tools;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A plain YouTube search for a text (the header search's <b>Search YouTube</b> and the search
 * page's YouTube section, {@code docs/ui.md}): through MVFinder's {@code /search}
 * ({@link MusicLinkResolver#search}, normal YouTube mode, YouTube's order) when it is configured
 * (and allowed for the caller), else, or when it fails or finds nothing, a yt-dlp
 * {@code ytsearch<limit>:} flat search ({@link VideoSearch#ytdlpSearch}). Live streams are left
 * out (by both); {@code song} uploads stay (they import like any video). Deduplicated by video ID,
 * at most {@code limit}; every result has an allowed thumbnail ({@link VideoSearch#thumbnail}).
 * Items already in the library are not looked up: the results are their own list.
 *
 * <p>Each search counts once against the person's {@code youtubeSearch.maxPerHour}
 * ({@code 429 too_many_searches}), before anything is asked.
 */
public final class YoutubeSearch {
    /** Results when the caller doesn't say. */
    public static final int DEFAULT_LIMIT = 10;
    /** Most results per search. */
    public static final int MAX_LIMIT = MusicLinkResolver.MAX_SEARCH_RESULTS;
    /** Longest search text. */
    public static final int MAX_QUERY = MusicLinkResolver.MAX_QUERY;
    private static final System.Logger LOG = System.getLogger(YoutubeSearch.class.getName());

    private final Tools tools;
    private final Supplier<String> cookiesFile;
    private final PerHourLimit limit;
    private volatile MusicLinkResolver resolver;

    /**
     * @param cookiesFile the {@code ytdlp.cookiesFile} setting, read for every search
     * @param maxPerHour  {@code youtubeSearch.maxPerHour}, read for every search
     * @param resolver    MVFinder, or null (then only yt-dlp)
     */
    public YoutubeSearch(Tools tools, Supplier<String> cookiesFile, MusicLinkResolver resolver, IntSupplier maxPerHour,
                         java.time.Clock clock) {
        this.tools = Objects.requireNonNull(tools);
        this.cookiesFile = Objects.requireNonNull(cookiesFile);
        this.resolver = resolver;
        this.limit = new PerHourLimit(maxPerHour, clock);
    }

    /** Sets MVFinder (null: off). */
    public void resolver(MusicLinkResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Searches YouTube for {@code query} (already checked: 1..{@link #MAX_QUERY} characters) for
     * {@code who} (a user ID, or a guest's identity).
     *
     * @param count        1..{@link #MAX_LIMIT}
     * @param useResolver  whether MVFinder may be asked for this caller
     * @throws HttpError {@code 429 too_many_searches}; from yt-dlp {@code 503 youtube_unavailable},
     *                   {@code 504 youtube_timeout}, {@code 502 search_failed}
     */
    public List<VideoSearch.Candidate> search(String who, String query, int count, boolean useResolver)
            throws InterruptedException {
        int n = Math.max(1, Math.min(MAX_LIMIT, count));
        String q = query.strip().replaceAll("\\s+", " ");
        limit.take(who);
        List<VideoSearch.Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        MusicLinkResolver r = useResolver ? resolver : null;
        if (r != null) {
            try {
                for (MusicLinkResolver.SearchMatch m : r.search(q, n)) {
                    add(out, seen, n, new VideoSearch.Candidate(m.videoId(), m.title(), m.channel(), m.durationMs(),
                            m.type(), VideoSearch.thumbnail(m.thumbnailUrl(), m.videoId())));
                }
            } catch (HttpError e) {
                LOG.log(System.Logger.Level.INFO, "No YouTube search results from the link resolver: " + e.code());
            }
        }
        if (out.isEmpty()) {
            for (VideoSearch.Candidate c : VideoSearch.ytdlpSearch(tools, cookiesFile, q, n)) {
                add(out, seen, n, c);
            }
        }
        return List.copyOf(out);
    }

    private static void add(List<VideoSearch.Candidate> out, Set<String> seen, int max, VideoSearch.Candidate c) {
        if (out.size() < max && YoutubeIds.isValidId(c.videoId()) && seen.add(c.videoId())) {
            out.add(c);
        }
    }
}
