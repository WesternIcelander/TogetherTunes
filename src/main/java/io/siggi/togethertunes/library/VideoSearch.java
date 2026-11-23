package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * "Find a music video" ({@code docs/library-and-ingest.md#linked-media}): the candidates for a
 * song without a linked music or lyric video, and picking one (import, link, then the client adds
 * it instead of the song).
 *
 * <p><b>Candidates</b>, in this order, deduplicated by video ID: MVFinder's video matches for the
 * item's ISRC ({@link MusicLinkResolver#isrcVideos}, when it is configured and the item has an
 * ISRC; a failure there is logged and skipped), then the video matches kept from the link the item
 * was imported from ({@code metadata_json.link.videos}); only when those found nothing, a YouTube
 * search through yt-dlp ({@code --flat-playlist -J "ytsearch8:<artist> <title>"}, nothing
 * downloaded, {@link #SEARCH_TIMEOUT}). Each search counts against the person's
 * {@code mvfinder.maxPerHour} ({@code 429 too_many_searches}).
 *
 * <p><b>Picking</b> takes only a video this person was offered for this item in the past
 * {@link #REMEMBER} ({@code 400 not_a_candidate}): the normal YouTube import (not into a library);
 * the video gets the media type {@code music_video} when it is still {@code uncategorized} (also
 * for a {@code lyric_video} match; through {@link Classify#classifyUncategorized}, audited
 * {@code item.updated} with {@code via: "search"}; a video someone already classified keeps its
 * type); then the link is saved on the song (Lyric Video for MVFinder's {@code lyric_video}, else Music
 * Video; at the end of the list; audited {@code item.link_added} with {@code via: "search"}) unless
 * the song already links that item or has {@link ItemLinks#MAX_LINKS} links. Anyone who may search
 * may link this way, not only the song's editors.
 */
public final class VideoSearch {
    /** yt-dlp search results asked for. */
    static final int SEARCH_RESULTS = 8;
    static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private static final int SEARCH_MAX_BYTES = 4 * 1024 * 1024;
    /** How long a search's candidates may be picked. */
    static final Duration REMEMBER = Duration.ofHours(1);
    /** {@code audit_log} action of a link saved from a pick. */
    public static final String AUDIT_LINK_ADDED = "item.link_added";
    private static final long HOUR_MS = 3_600_000L;
    private static final int MAX_REMEMBERED = 10_000;
    private static final System.Logger LOG = System.getLogger(VideoSearch.class.getName());
    private static final Classify.Classification MUSIC_VIDEO =
            new Classify.Classification(MediaType.MUSIC_VIDEO, null);

    /**
     * One candidate as sent to the client.
     *
     * @param channel    the uploader (yt-dlp) or the artists joined (MVFinder), or null
     * @param durationMs null when unknown
     * @param type       MVFinder's match type ({@code music_video}, {@code lyric_video},
     *                   {@code official_video}, {@code visualizer}), null for a search result
     */
    public record Candidate(String videoId, String title, String channel, Long durationMs, String type) {
    }

    /**
     * What a pick did.
     *
     * @param linked   whether the song now links it ({@code false} at the cap, or a link to itself)
     * @param linkType the link's type ({@code music_video} or {@code lyric_video})
     */
    public record Picked(String itemId, String status, boolean linked, String linkType) {
    }

    /** The song's fields a search needs. */
    private record Song(String title, String artist, String isrc, String metadataJson) {
    }

    /** A search's candidates for a person and an item, until {@code expiresAt}. */
    private record Offered(long expiresAt, Map<String, String> types) {
    }

    private final Database db;
    private final Tools tools;
    private final Supplier<String> cookiesFile;
    private final YoutubeImports imports;
    private final IntSupplier maxPerHour;
    private final Clock clock;
    private volatile MusicLinkResolver resolver;
    private final Map<String, ArrayDeque<Long>> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Long>> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };
    private final Map<String, Offered> offered = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Offered> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    /**
     * @param cookiesFile the {@code ytdlp.cookiesFile} setting, read for every search
     * @param maxPerHour  {@code mvfinder.maxPerHour}, read for every search
     * @param resolver    MVFinder, or null (then only the kept matches and yt-dlp)
     */
    public VideoSearch(Database db, Tools tools, Supplier<String> cookiesFile, YoutubeImports imports,
                       MusicLinkResolver resolver, IntSupplier maxPerHour, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.tools = Objects.requireNonNull(tools);
        this.cookiesFile = Objects.requireNonNull(cookiesFile);
        this.imports = Objects.requireNonNull(imports);
        this.resolver = resolver;
        this.maxPerHour = Objects.requireNonNull(maxPerHour);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The candidates for {@code itemId}; see the class comment. */
    public List<Candidate> search(Actor.User user, String itemId) throws SQLException, InterruptedException {
        Song song = db.read(c -> song(c, itemId));
        if (song == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        take(user.userId());
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        MusicLinkResolver r = resolver;
        if (r != null && song.isrc() != null) {
            try {
                for (MusicLinkResolver.VideoMatch m : r.isrcVideos(song.isrc())) {
                    add(out, seen, fromMatch(m));
                }
            } catch (HttpError e) {
                LOG.log(System.Logger.Level.INFO, "No ISRC matches for item " + itemId + ": " + e.code());
            }
        }
        for (MusicLinkResolver.VideoMatch m : kept(song.metadataJson())) {
            add(out, seen, fromMatch(m));
        }
        if (out.isEmpty()) {
            for (Candidate c : ytdlpSearch(query(song))) {
                add(out, seen, c);
            }
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (Candidate c : out) {
            types.put(c.videoId(), c.type() == null ? "" : c.type());
        }
        synchronized (offered) {
            offered.put(user.userId() + "\u0000" + itemId,
                    new Offered(clock.millis() + REMEMBER.toMillis(), Map.copyOf(types)));
        }
        return List.copyOf(out);
    }

    /** Sets MVFinder (null: off). */
    public void resolver(MusicLinkResolver resolver) {
        this.resolver = resolver;
    }

    /** Picks {@code videoId} for {@code itemId}; see the class comment. */
    public Picked pick(Actor.User user, String itemId, String videoId, String clientIp)
            throws SQLException, InterruptedException {
        if (!YoutubeIds.isValidId(videoId)) {
            throw new HttpError(400, "not_a_candidate", "Pick one of the videos found");
        }
        String matchType;
        synchronized (offered) {
            Offered o = offered.get(user.userId() + "\u0000" + itemId);
            matchType = o == null || o.expiresAt() <= clock.millis() ? null : o.types().get(videoId);
        }
        if (matchType == null) {
            throw new HttpError(400, "not_a_candidate", "Pick one of the videos found");
        }
        if (db.read(c -> song(c, itemId)) == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        YoutubeImports.Imported imported = imports.importUrl(user, "https://www.youtube.com/watch?v=" + videoId,
                null, Boolean.FALSE);
        String linkType = matchType.equals("lyric_video") ? "lyric_video" : "music_video";
        long now = EpochMs.now(clock);
        boolean linked = db.write(c -> {
            if (!imported.itemId().equals(itemId)) {
                // the picked video is a music video (also a lyric video: there's no lyric-video
                // type), unless someone gave it another type before
                Classify.classifyUncategorized(c, imported.itemId(), MUSIC_VIDEO, user.userId(), clientIp, now,
                        Map.of("via", "search", "linkedTo", itemId));
            }
            return link(c, user, itemId, imported.itemId(), linkType, clientIp, now);
        });
        return new Picked(imported.itemId(), imported.status(), linked, linkType);
    }

    /** Saves the link (at the end) unless it is there, would be to itself, or the list is full. */
    private static boolean link(Connection c, Actor.User user, String itemId, String targetId, String type,
                                String clientIp, long now) throws SQLException {
        if (targetId.equals(itemId)) {
            return false;
        }
        int count = 0;
        int maxPosition = -1;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT target_item_id, position FROM item_links WHERE item_id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (targetId.equals(rs.getString(1))) {
                        return true;
                    }
                    count++;
                    maxPosition = Math.max(maxPosition, rs.getInt(2));
                }
            }
        }
        if (count >= ItemLinks.MAX_LINKS) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO item_links (id, item_id, position, kind,"
                + " target_item_id, url, label, added_by, added_at) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?)")) {
            ps.setString(1, UuidV7.newId());
            ps.setString(2, itemId);
            ps.setInt(3, maxPosition + 1);
            ps.setString(4, type);
            ps.setString(5, targetId);
            ps.setString(6, user.userId());
            ps.setLong(7, now);
            ps.executeUpdate();
        }
        AuditLog.write(c, now, user.userId(), null, clientIp, AUDIT_LINK_ADDED, itemId,
                Map.of("via", "search", "type", type, "itemId", targetId));
        return true;
    }

    private static Song song(Connection c, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT title, artist, isrc, metadata_json FROM items WHERE id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Song(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null;
            }
        }
    }

    /** Counts one search for the person, or throws {@code 429 too_many_searches}. */
    private void take(String userId) {
        int max = Math.max(1, maxPerHour.getAsInt());
        long now = clock.millis();
        synchronized (recent) {
            ArrayDeque<Long> times = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
            while (!times.isEmpty() && times.peekFirst() <= now - HOUR_MS) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                long waitSec = Math.max(1, (times.peekFirst() + HOUR_MS - now + 999) / 1000);
                throw new HttpError(429, "too_many_searches", "Too many searches in the past hour; try again in "
                        + Math.max(1, (waitSec + 59) / 60) + " min", waitSec);
            }
            times.addLast(now);
        }
    }

    private static void add(List<Candidate> out, Set<String> seen, Candidate c) {
        if (c != null && YoutubeIds.isValidId(c.videoId()) && seen.add(c.videoId())) {
            out.add(c);
        }
    }

    private static Candidate fromMatch(MusicLinkResolver.VideoMatch m) {
        if (!MusicLinkResolver.VIDEO_TYPES.contains(m.type())) {
            return null;
        }
        String channel = m.artists().isEmpty() ? null : String.join(", ", m.artists());
        return new Candidate(m.videoId(), m.title() != null ? m.title() : m.videoId(), channel, m.durationMs(), m.type());
    }

    /** The video matches kept in {@code metadata_json.link.videos}. */
    static List<MusicLinkResolver.VideoMatch> kept(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(metadataJson);
            if (!root.isJsonObject() || !(root.getAsJsonObject().get("link") instanceof JsonObject link)
                    || !(link.get("videos") instanceof JsonArray videos)) {
                return List.of();
            }
            JsonArray matches = new JsonArray();
            for (JsonElement v : videos) {
                if (v.isJsonObject()) {
                    matches.add(v);
                }
            }
            return MusicLinkResolver.videos(matches, null);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The search text: the artist and the title. */
    static String query(Song song) {
        String artist = song.artist() == null ? "" : song.artist().strip();
        String title = song.title() == null ? "" : song.title().strip();
        return (artist.isEmpty() ? title : artist + " " + title).replaceAll("\\s+", " ");
    }

    /** {@code yt-dlp --flat-playlist -J "ytsearch8:<query>"}'s videos. */
    List<Candidate> ytdlpSearch(String query) throws InterruptedException {
        if (query.isBlank()) {
            return List.of();
        }
        if (!tools.hasYtdlp()) {
            throw new HttpError(503, "youtube_unavailable", "Searching YouTube isn't available right now");
        }
        Path cookies = IngestPipeline.cookiesFile(cookiesFile.get(), w -> LOG.log(System.Logger.Level.WARNING, w));
        List<String> args = new ArrayList<>(List.of("--flat-playlist", "-J", "--no-warnings"));
        if (cookies != null) {
            args.add("--cookies");
            args.add(cookies.toString());
        }
        args.add("--");
        args.add("ytsearch" + SEARCH_RESULTS + ":" + query);
        JobLog log = new JobLog();
        ToolResult result;
        try {
            result = tools.ytdlp(args).timeout(SEARCH_TIMEOUT).idleTimeout(SEARCH_TIMEOUT).log(log)
                    .stdoutToString(SEARCH_MAX_BYTES).run();
        } catch (ToolException e) {
            LOG.log(System.Logger.Level.INFO, "yt-dlp search failed (" + e.kind() + "):\n" + log.tail());
            throw switch (e.kind()) {
                case TIMEOUT, IDLE_TIMEOUT -> new HttpError(504, "youtube_timeout",
                        "YouTube didn't answer in time; try again later");
                default -> new HttpError(502, "search_failed", "Searching YouTube failed; try again later");
            };
        }
        return parseSearch(result.stdoutString());
    }

    /** The videos of yt-dlp's {@code --flat-playlist -J} answer (live and upcoming streams left out). */
    static List<Candidate> parseSearch(String json) {
        List<Candidate> out = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!root.isJsonObject() || !(root.getAsJsonObject().get("entries") instanceof JsonArray entries)) {
            return out;
        }
        for (JsonElement e : entries) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String id = string(o, "id");
            String live = string(o, "live_status");
            if (!YoutubeIds.isValidId(id) || "is_live".equals(live) || "is_upcoming".equals(live)) {
                continue;
            }
            String title = UploadMetadata.clean(string(o, "title"));
            String channel = UploadMetadata.clean(string(o, "channel"));
            if (channel == null) {
                channel = UploadMetadata.clean(string(o, "uploader"));
            }
            Long durationMs = null;
            JsonElement d = o.get("duration");
            if (d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
                double v = d.getAsDouble();
                if (v > 0 && v < 1e9) {
                    durationMs = Math.round(v * 1000);
                }
            }
            out.add(new Candidate(id, title != null ? title : id, channel, durationMs, null));
        }
        return out;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
