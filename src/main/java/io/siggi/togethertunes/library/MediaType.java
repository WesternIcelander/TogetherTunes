package io.siggi.togethertunes.library;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * An item's media type ({@code items.media_type}, {@code docs/media-types.md#media-types}): what
 * the item is, while {@code items.kind} says what files it has. An {@code audio}-type item plays
 * like an audio item, with its card, even when it has video files (this replaced
 * {@code present_as_audio}).
 */
public enum MediaType {
    AUDIO("audio"),
    UNCATEGORIZED("uncategorized"),
    MUSIC_VIDEO("music_video"),
    MOVIE("movie"),
    SHOW("show");

    private final String wire;

    MediaType(String wire) {
        this.wire = wire;
    }

    /** The stored and API name ({@code music_video}, ...). */
    public String wire() {
        return wire;
    }

    /** The type with this stored/API name, or null if there's none. */
    public static MediaType of(String wire) {
        if (wire != null) {
            for (MediaType t : values()) {
                if (t.wire.equals(wire)) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * The {@code type=} filter of the library and search APIs: one or more media types, comma
     * separated ({@code movie,show}); null or blank for all.
     *
     * @return the types' stored names, or null for no filter
     * @throws io.siggi.togethertunes.http.HttpError {@code 400 invalid_type}
     */
    public static java.util.List<String> filter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            MediaType t = of(part.trim());
            if (t == null) {
                throw new io.siggi.togethertunes.http.HttpError(400, "invalid_type",
                        "type must be audio, uncategorized, music_video, movie or show (comma separated)");
            }
            out.add(t.wire);
        }
        return java.util.List.copyOf(out);
    }

    /** Appends {@code AND i.media_type IN (...)} for {@code types} (none if null) and its parameters. */
    static void appendFilter(StringBuilder sql, java.util.List<? super String> params, java.util.List<String> types) {
        if (types == null) {
            return;
        }
        sql.append(" AND (CASE WHEN i.kind = 'audio' THEN 'audio' ELSE i.media_type END) IN (");
        for (int i = 0; i < types.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            params.add(types.get(i));
        }
        sql.append(")");
    }

    /** Whether {@code wire} names a media type. */
    public static boolean valid(String wire) {
        return of(wire) != null;
    }

    /**
     * The type a newly created item of {@code kind} gets: {@code audio} for audio items, else
     * {@code uncategorized} ({@code audio} as well when {@code asAudio}).
     */
    public static MediaType initial(String kind, boolean asAudio) {
        return "audio".equals(kind) || asAudio ? AUDIO : UNCATEGORIZED;
    }

    /**
     * The media type of an item with {@code kind} files and the stored {@code mediaType}: an audio
     * item's is always {@code audio} (whatever is stored), a missing one is {@code uncategorized}.
     */
    public static String effective(String kind, String mediaType) {
        if ("audio".equals(kind)) {
            return AUDIO.wire;
        }
        return mediaType == null ? UNCATEGORIZED.wire : mediaType;
    }

    /**
     * Whether an item with {@code kind} files and {@code mediaType} is a video item played like
     * an audio item (the API's derived {@code presentAsAudio}).
     */
    public static boolean presentAsAudio(String kind, String mediaType) {
        return "video".equals(kind) && AUDIO.wire.equals(mediaType);
    }

    /**
     * A show episode's details ({@code show} type only).
     *
     * @param name    the show's name
     * @param season  its season number (≥ 0), or null if it has none
     * @param episode its episode number (≥ 0)
     */
    public record Show(String name, Integer season, int episode) {
        /** Largest season or episode number accepted. */
        public static final int MAX_NUMBER = 1_000_000;
    }

    /** The item columns {@link #show(ResultSet, int)} reads, prefixed with {@code alias.}. */
    public static String columns(String alias) {
        String p = alias == null || alias.isEmpty() ? "" : alias + ".";
        return p + "media_type, " + p + "show_name, " + p + "season_number, " + p + "episode_number";
    }

    /**
     * The show details from the four {@link #columns} at {@code index} (media type, show name,
     * season, episode), or null when the type isn't {@code show}.
     */
    public static Show show(ResultSet rs, int index) throws SQLException {
        if (!SHOW.wire.equals(rs.getString(index))) {
            return null;
        }
        String name = rs.getString(index + 1);
        int season = rs.getInt(index + 2);
        Integer s = rs.wasNull() ? null : season;
        int episode = rs.getInt(index + 3);
        return new Show(name == null ? "" : name, s, episode);
    }
}
