package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.ingest.MediaProbe;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An item's song details besides title, artist and album ({@code docs/library-and-ingest.md#song-details}):
 * the usual music tags, named after TrackSplitter's JSON. Every field is optional (null). Text is
 * cleaned like upload metadata ({@link UploadMetadata#clean}).
 *
 * @param artists     each of the track's artists, only when there is more than one (else null);
 *                    the item's {@code artist} stays the one string lists and cards show
 * @param albumArtist the album's artist
 * @param track       track number, 1..{@value #MAX_NUMBER}
 * @param trackTotal  tracks on the disc, 1..{@value #MAX_NUMBER}
 * @param disc        disc number, 1..{@value #MAX_NUMBER}
 * @param discTotal   number of discs, 1..{@value #MAX_NUMBER}
 * @param isrc        the recording's ISRC: 12 characters, upper case ({@code JPVI02201530})
 * @param date        release date: {@code YYYY}, {@code YYYY-MM} or {@code YYYY-MM-DD}
 * @param genre       genre
 * @param copyright   copyright line
 * @param label       record label
 * @param barcode     the album's UPC/EAN, 8 to 14 digits
 */
public record SongDetails(List<String> artists, String albumArtist, Integer track, Integer trackTotal,
                          Integer disc, Integer discTotal, String isrc, String date, String genre, String copyright,
                          String label, String barcode) {
    /** No details. */
    public static final SongDetails EMPTY = new SongDetails(null, null, null, null, null, null, null, null, null,
            null, null, null);

    /** Largest track or disc number (and total). */
    public static final int MAX_NUMBER = 9999;

    /** Most names in {@link #artists}. */
    public static final int MAX_ARTISTS = 50;

    /** The fields' API names, in order. */
    public static final List<String> FIELDS = List.of("artists", "albumArtist", "track", "trackTotal", "disc",
            "discTotal", "isrc", "date", "genre", "copyright", "label", "barcode");

    /** The separator between the names of {@link #artists} in the item's {@code artist}. */
    public static final String ARTIST_SEPARATOR = ", ";

    private static final Pattern ISRC = Pattern.compile("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}");
    private static final Pattern DATE = Pattern.compile("(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?");
    /** A tag's date with a time after it ({@code 2022-06-29T00:00:00Z}, {@code 2022-06-29 12:00}). */
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[T ].*");
    private static final Pattern BARCODE = Pattern.compile("[0-9]{8,14}");
    private static final Pattern NUMBER = Pattern.compile("0*([1-9][0-9]{0,8})");
    private static final Pattern NUMBER_OF = Pattern.compile("\\s*([0-9]{1,9})\\s*(?:/\\s*([0-9]{1,9})\\s*)?");

    public SongDetails {
        artists = artists == null || artists.size() < 2 ? null : List.copyOf(artists);
    }

    /** Whether no field is set. */
    public boolean isEmpty() {
        return equals(EMPTY);
    }

    /** The field {@code name} (an API name from {@link #FIELDS}). */
    public Object get(String name) {
        return switch (name) {
            case "artists" -> artists;
            case "albumArtist" -> albumArtist;
            case "track" -> track;
            case "trackTotal" -> trackTotal;
            case "disc" -> disc;
            case "discTotal" -> discTotal;
            case "isrc" -> isrc;
            case "date" -> date;
            case "genre" -> genre;
            case "copyright" -> copyright;
            case "label" -> label;
            case "barcode" -> barcode;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** These details with the field {@code name} set to {@code value} (of the field's type, or null). */
    @SuppressWarnings("unchecked")
    public SongDetails with(String name, Object value) {
        List<String> a = artists;
        String aa = albumArtist;
        Integer t = track;
        Integer tt = trackTotal;
        Integer d = disc;
        Integer dt = discTotal;
        String i = isrc;
        String dd = date;
        String g = genre;
        String c = copyright;
        String l = label;
        String b = barcode;
        switch (name) {
            case "artists" -> a = (List<String>) value;
            case "albumArtist" -> aa = (String) value;
            case "track" -> t = (Integer) value;
            case "trackTotal" -> tt = (Integer) value;
            case "disc" -> d = (Integer) value;
            case "discTotal" -> dt = (Integer) value;
            case "isrc" -> i = (String) value;
            case "date" -> dd = (String) value;
            case "genre" -> g = (String) value;
            case "copyright" -> c = (String) value;
            case "label" -> l = (String) value;
            case "barcode" -> b = (String) value;
            default -> throw new IllegalArgumentException(name);
        }
        return new SongDetails(a, aa, t, tt, d, dt, i, dd, g, c, l, b);
    }

    /** The fields that differ in {@code after}, with their new values (null: cleared), in order. */
    public Map<String, Object> changesTo(SongDetails after) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : FIELDS) {
            if (!Objects.equals(get(f), after.get(f))) {
                out.put(f, after.get(f));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ database

    /** The item columns {@link #read} reads and {@link #bind} writes, prefixed with {@code alias.}. */
    public static String columns(String alias) {
        String p = alias == null || alias.isEmpty() ? "" : alias + ".";
        return p + "artists_json, " + p + "album_artist, " + p + "track_no, " + p + "track_total, " + p + "disc_no, "
                + p + "disc_total, " + p + "isrc, " + p + "release_date, " + p + "genre, " + p + "copyright, "
                + p + "label, " + p + "barcode";
    }

    /** {@link #columns} as {@code column = ?} assignments for an {@code UPDATE}. */
    public static String assignments() {
        return "artists_json = ?, album_artist = ?, track_no = ?, track_total = ?, disc_no = ?, disc_total = ?,"
                + " isrc = ?, release_date = ?, genre = ?, copyright = ?, label = ?, barcode = ?";
    }

    /** How many columns {@link #columns} has. */
    public static final int COLUMN_COUNT = 12;

    /** The details from the {@link #columns} starting at {@code index}. */
    public static SongDetails read(ResultSet rs, int index) throws SQLException {
        return new SongDetails(artistsFromJson(rs.getString(index)), rs.getString(index + 1),
                integer(rs, index + 2), integer(rs, index + 3), integer(rs, index + 4), integer(rs, index + 5),
                rs.getString(index + 6), rs.getString(index + 7), rs.getString(index + 8), rs.getString(index + 9),
                rs.getString(index + 10), rs.getString(index + 11));
    }

    /** Binds the {@link #columns} starting at parameter {@code index}; returns the next free index. */
    public int bind(PreparedStatement ps, int index) throws SQLException {
        ps.setString(index, artists == null ? null : artistsJson(artists));
        ps.setString(index + 1, albumArtist);
        setInteger(ps, index + 2, track);
        setInteger(ps, index + 3, trackTotal);
        setInteger(ps, index + 4, disc);
        setInteger(ps, index + 5, discTotal);
        ps.setString(index + 6, isrc);
        ps.setString(index + 7, date);
        ps.setString(index + 8, genre);
        ps.setString(index + 9, copyright);
        ps.setString(index + 10, label);
        ps.setString(index + 11, barcode);
        return index + COLUMN_COUNT;
    }

    private static Integer integer(ResultSet rs, int index) throws SQLException {
        int v = rs.getInt(index);
        return rs.wasNull() ? null : v;
    }

    private static void setInteger(PreparedStatement ps, int index, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, v);
        }
    }

    static String artistsJson(List<String> names) {
        JsonArray a = new JsonArray();
        names.forEach(a::add);
        return a.toString();
    }

    /** The names in {@code items.artists_json}, or null (missing or unreadable). */
    static List<String> artistsFromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonElement n : e.getAsJsonArray()) {
                if (n.isJsonPrimitive() && n.getAsJsonPrimitive().isString()) {
                    out.add(n.getAsString());
                }
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ values

    /**
     * The artist names cleaned ({@link UploadMetadata#clean}), blanks and repeats dropped, in order.
     */
    public static List<String> cleanNames(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            String c = UploadMetadata.clean(n);
            if (c != null) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /** The names joined as the item's {@code artist} ({@value #ARTIST_SEPARATOR}), or null for none. */
    public static String joinNames(List<String> names) {
        return names.isEmpty() ? null : String.join(ARTIST_SEPARATOR, names);
    }

    /** An ISRC, upper case without hyphens or spaces, or null if {@code raw} isn't one. */
    public static String isrc(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        return ISRC.matcher(s).matches() ? s : null;
    }

    /** A release date {@code YYYY}, {@code YYYY-MM} or {@code YYYY-MM-DD} (a real date), or null. */
    public static String date(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        Matcher m = DATE.matcher(s);
        if (!m.matches()) {
            return null;
        }
        int year = Integer.parseInt(m.group(1));
        if (year < 1) {
            return null;
        }
        if (m.group(2) != null) {
            int month = Integer.parseInt(m.group(2));
            if (month < 1 || month > 12) {
                return null;
            }
            if (m.group(3) != null) {
                try {
                    LocalDate.of(year, month, Integer.parseInt(m.group(3)));
                } catch (DateTimeException e) {
                    return null;
                }
            }
        }
        return s;
    }

    /** A UPC/EAN (8 to 14 digits, spaces and hyphens removed), or null. */
    public static String barcode(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\s\\-]", "");
        return BARCODE.matcher(s).matches() ? s : null;
    }

    // ------------------------------------------------------------------ tags

    /**
     * The details in an upload's container tags ({@link MediaProbe#tags}, lower-case keys): a value
     * that doesn't fit its field is dropped, never guessed.
     *
     * <ul>
     *   <li>{@code track} / {@code disc} as {@code n} or {@code n/total}; the totals also from
     *       {@code tracktotal}/{@code totaltracks} and {@code disctotal}/{@code totaldiscs}</li>
     *   <li>{@code date} (or {@code year}; a date with a time after it keeps its date)</li>
     *   <li>{@code genre}, {@code copyright}, {@code album_artist} (or {@code albumartist},
     *       {@code album artist})</li>
     *   <li>{@code label} (or {@code publisher}, {@code organization})</li>
     *   <li>{@code isrc} (or ID3's {@code tsrc}), {@code barcode} (or {@code upc}, {@code ean})</li>
     *   <li>{@code artists}: the names as ffprobe reports them (repeated values joined with
     *       {@code ;}), kept only when there are two or more</li>
     * </ul>
     */
    public static SongDetails fromTags(MediaProbe probe) {
        NumberOf track = numberOf(probe.tag("track"));
        NumberOf disc = numberOf(probe.tag("disc"));
        Integer trackTotal = track.total() != null ? track.total() : firstNumber(probe, "tracktotal", "totaltracks");
        Integer discTotal = disc.total() != null ? disc.total() : firstNumber(probe, "disctotal", "totaldiscs");
        String date = date(dateOnly(first(probe, "date")));
        if (date == null) {
            date = date(dateOnly(first(probe, "year")));
        }
        return new SongDetails(artistsTag(probe.tag("artists")),
                text(probe, "album_artist", "albumartist", "album artist"),
                track.number(), trackTotal, disc.number(), discTotal,
                isrc(first(probe, "isrc", "tsrc")), date, text(probe, "genre"), text(probe, "copyright"),
                text(probe, "label", "publisher", "organization"), barcode(first(probe, "barcode", "upc", "ean")));
    }

    private record NumberOf(Integer number, Integer total) {
    }

    /** {@code n} or {@code n/total} (whole numbers 1..{@value #MAX_NUMBER}; a bad part is dropped). */
    static NumberOf numberOf(String raw) {
        if (raw == null) {
            return new NumberOf(null, null);
        }
        Matcher m = NUMBER_OF.matcher(raw);
        if (!m.matches()) {
            return new NumberOf(null, null);
        }
        return new NumberOf(number(m.group(1)), m.group(2) == null ? null : number(m.group(2)));
    }

    /** A whole number 1..{@value #MAX_NUMBER} (leading zeros allowed), or null. */
    static Integer number(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(raw.strip());
        if (!m.matches()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        return n <= MAX_NUMBER ? n : null;
    }

    private static Integer firstNumber(MediaProbe probe, String... keys) {
        return number(first(probe, keys));
    }

    private static String dateOnly(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = DATE_TIME.matcher(raw.strip());
        return m.matches() ? m.group(1) : raw;
    }

    /** The {@code artists} tag's names (split at {@code ;}), when there are two or more. */
    static List<String> artistsTag(String raw) {
        if (raw == null) {
            return null;
        }
        List<String> names = cleanNames(List.of(raw.split("[;\\u0000]")));
        return names.size() >= 2 && names.size() <= MAX_ARTISTS ? names : null;
    }

    private static String first(MediaProbe probe, String... keys) {
        for (String k : keys) {
            String v = probe.tag(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String text(MediaProbe probe, String... keys) {
        for (String k : keys) {
            String v = UploadMetadata.clean(probe.tag(k));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ edits

    /**
     * A change to some of the fields: those in {@code fields} (API names) take their value from
     * {@code values} (null clears them); the others are left as they are.
     */
    public record Patch(Set<String> fields, SongDetails values) {
        /** Changes nothing. */
        public static final Patch NONE = new Patch(Set.of(), EMPTY);

        public Patch {
            fields = Set.copyOf(fields);
            Objects.requireNonNull(values);
        }

        public boolean isEmpty() {
            return fields.isEmpty();
        }

        /** {@code current} with this change. */
        public SongDetails apply(SongDetails current) {
            SongDetails out = current;
            for (String f : FIELDS) {
                if (fields.contains(f)) {
                    out = out.with(f, values.get(f));
                }
            }
            return out;
        }
    }
}
