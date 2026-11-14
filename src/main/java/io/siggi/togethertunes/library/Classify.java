package io.siggi.togethertunes.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.MediaVersions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classify on library add ({@code docs/media-types.md#media-types}): whoever adds an
 * {@code uncategorized} video to a library must give it a media type first. Every path that makes
 * a library entry ({@code PUT /api/library/{itemId}}, YouTube imports and uploads with
 * {@code addToLibrary}) goes through {@link #prepareAdd} in the transaction that adds it:
 * with a {@link Classification} the item gets that type (and show details) and the add goes ahead;
 * without one the add is left out and the API answers {@link NeedsClassification} ({@code 409
 * needs_classification} with the item's id and a {@link Suggestion}). An item that is already
 * classified (or already in that library) never asks, and a classification sent for it is ignored
 * (changing a type is a metadata edit, {@code PATCH /api/items/{id}}). Requests from a pasted link
 * (no library) never ask: the item stays {@code uncategorized}.
 */
public final class Classify {
    /** {@code S01E02}, {@code s1e2}, {@code S01.E02}, {@code S01 E02} in a file name. */
    private static final Pattern SXXEYY =
            Pattern.compile("(?i)(?<![A-Za-z0-9])S(\\d{1,4})[ ._-]?E(\\d{1,5})(?![0-9])");

    private Classify() {
    }

    /**
     * The media type an adder gives an {@code uncategorized} video, with show details for (and only
     * for) {@code show}. {@code uncategorized} itself is the adder's explicit choice to leave it
     * uncategorized ({@link #LEAVE}): the add goes ahead and the item keeps its type (the
     * Uncategorized Videos view lists it until someone classifies it).
     */
    public record Classification(MediaType type, MediaType.Show show) {
        /** "Leave it uncategorized": the add goes ahead without classifying. */
        public static final Classification LEAVE = new Classification(MediaType.UNCATEGORIZED, null);

        public Classification {
            if (type == null) {
                throw new IllegalArgumentException("A classification needs a type");
            }
            if ((type == MediaType.SHOW) != (show != null)) {
                throw new IllegalArgumentException("Show details go with (and only with) the type show");
            }
        }
    }

    /**
     * The classification in a request body's {@code mediaType} and {@code show} fields, or null
     * when neither is there.
     *
     * @throws HttpError {@code 400 invalid_media_type} (unknown, or {@code uncategorized}),
     *                   {@code invalid_show}, {@code show_required}, {@code not_a_show}
     */
    public static Classification of(JsonElement mediaType, JsonElement show) {
        boolean hasType = mediaType != null && !mediaType.isJsonNull();
        boolean hasShow = show != null && !show.isJsonNull();
        if (!hasType && !hasShow) {
            return null;
        }
        if (!hasType) {
            throw new HttpError(400, "not_a_show", "Show details are only for the media type show");
        }
        MediaType type = mediaType.isJsonPrimitive() && mediaType.getAsJsonPrimitive().isString()
                ? MediaType.of(mediaType.getAsString()) : null;
        if (type == null) {
            throw new HttpError(400, "invalid_media_type",
                    "mediaType must be audio, music_video, movie, show or uncategorized");
        }
        MediaType.Show details = hasShow ? ItemsApi.show(show) : null;
        if (type == MediaType.SHOW && details == null) {
            throw new HttpError(400, "show_required", "A show episode needs a show name and an episode number");
        }
        if (type != MediaType.SHOW && details != null) {
            throw new HttpError(400, "not_a_show", "Show details are only for the media type show");
        }
        return type == MediaType.UNCATEGORIZED ? Classification.LEAVE : new Classification(type, details);
    }

    /** {@link #of(JsonElement, JsonElement)} from a JSON body's fields (null body: none). */
    public static Classification of(JsonObject body) {
        return body == null ? null : of(body.get("mediaType"), body.get("show"));
    }

    /**
     * A preselection for the classify prompt: a media type and, for {@code show}, what is known of
     * the show (any field may be missing).
     */
    public record Suggestion(String mediaType, SuggestedShow show) {
    }

    /** The show details a {@link Suggestion} prefills; each may be null. */
    public record SuggestedShow(String name, Integer season, Integer episode) {
    }

    /**
     * {@code 409 needs_classification}: the video is {@code uncategorized} and the library add
     * needs a media type. The error carries {@code itemId} (the item exists; an import or upload
     * has already made or fetched it) and {@code suggested} ({@link Suggestion}, absent when
     * nothing is known). The client asks and then adds again with {@code mediaType}/{@code show}
     * ({@code PUT /api/library/{itemId}}).
     */
    public static final class NeedsClassification extends HttpError {
        private static final long serialVersionUID = 1L;
        private final String itemId;
        private final transient Suggestion suggested;
        private final transient Map<String, Object> more;

        public NeedsClassification(String itemId, Suggestion suggested) {
            this(itemId, suggested, Map.of());
        }

        private NeedsClassification(String itemId, Suggestion suggested, Map<String, Object> more) {
            super(409, "needs_classification", "Choose what kind of video this is before adding it to a library");
            this.itemId = itemId;
            this.suggested = suggested;
            this.more = more;
        }

        /** This error with one more field (e.g. the {@code uploadId} of a pre-check hit). */
        public NeedsClassification with(String key, Object value) {
            Map<String, Object> m = new LinkedHashMap<>(more);
            m.put(key, value);
            return new NeedsClassification(itemId, suggested, Map.copyOf(m));
        }

        public String itemId() {
            return itemId;
        }

        public Suggestion suggested() {
            return suggested;
        }

        @Override
        public Map<String, Object> extra() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("itemId", itemId);
            if (suggested != null) {
                out.put("suggested", suggested);
            }
            more.forEach(out::putIfAbsent);
            return out;
        }
    }

    /**
     * Whether adding {@code itemId} to {@code userId}'s library needs a classification: the item
     * is a video of type {@code uncategorized} that isn't in that library yet.
     */
    public static boolean needs(Connection c, String userId, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM items i WHERE i.id = ? AND i.kind = 'video'"
                + " AND i.media_type = 'uncategorized' AND NOT EXISTS (SELECT 1 FROM library_entries e"
                + " WHERE e.user_id = ? AND e.item_id = i.id)")) {
            ps.setString(1, itemId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Run in the transaction that adds {@code itemId} to {@code libraryUserId}'s library, before
     * the entry is written: whether the add may go ahead. When the item needs a classification
     * ({@link #needs}) and {@code classification} is given, the item gets it (the current media
     * version's type too, as a PATCH does; audited as {@code item.updated} by {@code actorUserId});
     * without one it returns false and the caller leaves the entry out (and answers
     * {@link #needsClassification}).
     */
    public static boolean prepareAdd(Connection c, String libraryUserId, String itemId,
                                     Classification classification, String actorUserId, long now)
            throws SQLException {
        if (!needs(c, libraryUserId, itemId)) {
            return true;
        }
        if (classification == null) {
            return false;
        }
        if (classification.type() == MediaType.UNCATEGORIZED) {
            return true; // left uncategorized on purpose
        }
        MediaType.Show show = classification.show();
        int updated;
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET media_type = ?, show_name = ?,"
                + " season_number = ?, episode_number = ? WHERE id = ? AND kind = 'video'"
                + " AND media_type = 'uncategorized'")) {
            ps.setString(1, classification.type().wire());
            ps.setString(2, show == null ? null : show.name());
            if (show == null || show.season() == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, show.season());
            }
            if (show == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, show.episode());
            }
            ps.setString(5, itemId);
            updated = ps.executeUpdate();
        }
        if (updated == 0) {
            return true;
        }
        Integer version = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT media_version FROM items WHERE id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int v = rs.getInt(1);
                    version = rs.wasNull() ? null : v;
                }
            }
        }
        if (version != null) {
            MediaVersions.typeChanged(c, itemId, version, classification.type().wire());
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("mediaType", classification.type().wire());
        if (show != null) {
            changed.put("show", show);
        }
        changed.put("classifiedOnLibraryAdd", true);
        AuditLog.write(c, now, actorUserId, null, null, Items.AUDIT_UPDATED, itemId, changed);
        return true;
    }

    /** The {@code 409 needs_classification} for {@code itemId}, with its {@linkplain #suggest suggestion}. */
    public static NeedsClassification needsClassification(Database db, String itemId) throws SQLException {
        return new NeedsClassification(itemId, db.read(c -> suggest(c, itemId)));
    }

    /**
     * What the classify prompt preselects for {@code itemId} ({@code docs/media-types.md#media-types}):
     * yt-dlp's {@code series} / {@code season_number} / {@code episode_number} → {@code show}
     * (prefilled); yt-dlp's {@code track} / {@code artist} → {@code music_video}; an upload's file
     * name (or, when it isn't known any more, its title) with {@code SxxEyy} → {@code show}.
     * Null when nothing is known.
     */
    public static Suggestion suggest(Connection c, String itemId) throws SQLException {
        String source;
        String title;
        String metadata;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source, title, metadata_json FROM items WHERE id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                source = rs.getString(1);
                title = rs.getString(2);
                metadata = rs.getString(3);
            }
        }
        JsonObject meta = null;
        if (metadata != null) {
            try {
                JsonElement e = JsonParser.parseString(metadata);
                meta = e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (RuntimeException e) {
                meta = null;
            }
        }
        if ("youtube".equals(source)) {
            JsonElement yt = meta == null ? null : meta.get("youtube");
            return yt != null && yt.isJsonObject() ? fromYoutube(yt.getAsJsonObject()) : null;
        }
        String fileName = null;
        JsonElement up = meta == null ? null : meta.get("upload");
        if (up != null && up.isJsonObject()) {
            fileName = string(up.getAsJsonObject(), "filename");
        }
        if (fileName == null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT filename FROM uploads WHERE item_id = ? ORDER BY created_at LIMIT 1")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    fileName = rs.next() ? rs.getString(1) : null;
                }
            }
        }
        Suggestion s = fileName == null ? null : fromFileName(fileName);
        return s != null ? s : title == null ? null : fromFileName(title);
    }

    /** The suggestion from the {@code youtube} object of {@code metadata_json} ({@link YoutubeMetadata#toItemMetadata}). */
    static Suggestion fromYoutube(JsonObject yt) {
        String series = string(yt, "series");
        Integer season = integer(yt, "seasonNumber");
        Integer episode = integer(yt, "episodeNumber");
        if (series != null || season != null || episode != null) {
            return new Suggestion(MediaType.SHOW.wire(), new SuggestedShow(series, season, episode));
        }
        if (string(yt, "track") != null || string(yt, "artist") != null) {
            return new Suggestion(MediaType.MUSIC_VIDEO.wire(), null);
        }
        return null;
    }

    /**
     * {@code show} when {@code name} (a file name or title) has {@code SxxEyy}: the season and
     * episode from it, the show name from what comes before (separators become spaces); else null.
     */
    static Suggestion fromFileName(String name) {
        String base = name;
        Matcher m = SXXEYY.matcher(base);
        if (!m.find()) {
            return null;
        }
        Integer season = parse(m.group(1));
        Integer episode = parse(m.group(2));
        String show = base.substring(0, m.start()).replaceAll("[._]+", " ")
                .replaceAll("[\\s\\-\\[(]+$", "").replaceAll("\\s+", " ").strip();
        show = UploadMetadata.clean(show);
        return new Suggestion(MediaType.SHOW.wire(), new SuggestedShow(show, season, episode));
    }

    private static Integer parse(String digits) {
        int n = Integer.parseInt(digits);
        return n <= MediaType.Show.MAX_NUMBER ? n : null;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    private static Integer integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double d = e.getAsDouble();
        return d >= 0 && d <= MediaType.Show.MAX_NUMBER && d == Math.rint(d) ? (int) d : null;
    }
}
