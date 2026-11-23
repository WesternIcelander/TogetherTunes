package io.siggi.togethertunes.library;

import io.siggi.togethertunes.http.HttpError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A music library export's {@code Library.xml} ({@code docs/library-and-ingest.md#library-import}):
 * an XML property list as Apple Music / iTunes write it. Parsed with the JDK's StAX parser with DTD
 * support and external entities off, so a DOCTYPE is skipped and nothing is ever fetched or
 * expanded (a document that uses an entity it declares is refused).
 */
public record LibraryXml(String musicFolder, Map<String, Track> tracks, List<Playlist> playlists) {
    /** The largest {@code Library.xml} read. */
    public static final long MAX_BYTES = 200L * 1024 * 1024;

    /**
     * One track.
     *
     * @param id        its {@code Track ID} (what playlists name)
     * @param location  the decoded path of its {@code Location} URL, or null (no file)
     * @param dateAdded epoch ms, or null
     * @param date      {@code YYYY} or {@code YYYY-MM-DD}, or null
     */
    public record Track(String id, String persistentId, String location, String name, String artist,
                        String albumArtist, String album, String genre, Integer track, Integer trackCount,
                        Integer disc, Integer discCount, String date, Long totalTimeMs, Long dateAdded,
                        String kind, boolean loved) {
    }

    /** One playlist (or folder) of {@code Playlists}. */
    public record Playlist(String persistentId, String parentPersistentId, String name, boolean smart,
                           boolean folder, boolean master, boolean distinguished, List<String> trackIds) {
        /** A regular playlist: made by hand, not a folder, not the whole library, not a built-in one. */
        public boolean regular() {
            return !smart && !folder && !master && !distinguished;
        }
    }

    /** {@code 400 invalid_library_xml}. */
    static HttpError invalid(String why) {
        return new HttpError(400, "invalid_library_xml", "Library.xml can't be read: " + why);
    }

    /**
     * Parses {@code Library.xml} (at most {@link #MAX_BYTES}).
     *
     * @throws HttpError {@code 400 invalid_library_xml}, {@code 400 library_xml_too_large}
     */
    public static LibraryXml parse(InputStream in) throws IOException {
        byte[] bytes = readLimited(in);
        Object root;
        try {
            root = new PlistReader(bytes).read();
        } catch (XMLStreamException | RuntimeException e) {
            if (e instanceof HttpError h) {
                throw h;
            }
            throw invalid(e.getMessage() == null ? "not a property list" : firstLine(e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> top)) {
            throw invalid("the top level isn't a dictionary");
        }
        String musicFolder = top.get("Music Folder") instanceof String s ? decodeLocation(s) : null;
        Map<String, Track> tracks = new LinkedHashMap<>();
        if (top.get("Tracks") instanceof Map<?, ?> t) {
            for (Map.Entry<?, ?> e : t.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> d) {
                    Track track = track(String.valueOf(e.getKey()), d);
                    tracks.put(track.id(), track);
                }
            }
        }
        List<Playlist> playlists = new ArrayList<>();
        if (top.get("Playlists") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> d) {
                    playlists.add(playlist(d));
                }
            }
        }
        return new LibraryXml(musicFolder, tracks, playlists);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > MAX_BYTES) {
                throw new HttpError(400, "library_xml_too_large", "Library.xml is larger than 200 MB");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static Track track(String key, Map<?, ?> d) {
        String id = d.get("Track ID") instanceof Long l ? Long.toString(l) : key;
        String location = d.get("Location") instanceof String s ? decodeLocation(s) : null;
        Long year = d.get("Year") instanceof Long l ? l : null;
        String date = null;
        if (d.get("Release Date") instanceof Instant release) {
            String day = release.toString().substring(0, 10);
            if (year == null || day.startsWith(String.format(Locale.ROOT, "%04d-", year))) {
                date = day;
            }
        }
        if (date == null && year != null && year >= 1 && year <= 9999) {
            date = String.format(Locale.ROOT, "%04d", year);
        }
        boolean loved = Boolean.TRUE.equals(d.get("Loved")) || Boolean.TRUE.equals(d.get("Favorited"));
        return new Track(id, str(d, "Persistent ID"), location, str(d, "Name"), str(d, "Artist"),
                str(d, "Album Artist"), str(d, "Album"), str(d, "Genre"), num(d, "Track Number"),
                num(d, "Track Count"), num(d, "Disc Number"), num(d, "Disc Count"), date,
                d.get("Total Time") instanceof Long l ? l : null,
                d.get("Date Added") instanceof Instant i ? i.toEpochMilli() : null, str(d, "Kind"), loved);
    }

    private static Playlist playlist(Map<?, ?> d) {
        List<String> ids = new ArrayList<>();
        if (d.get("Playlist Items") instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> e && e.get("Track ID") instanceof Long l) {
                    ids.add(Long.toString(l));
                }
            }
        }
        return new Playlist(str(d, "Playlist Persistent ID"), str(d, "Parent Persistent ID"), str(d, "Name"),
                d.containsKey("Smart Info") || d.containsKey("Smart Criteria"), Boolean.TRUE.equals(d.get("Folder")),
                Boolean.TRUE.equals(d.get("Master")), d.containsKey("Distinguished Kind"), List.copyOf(ids));
    }

    private static String str(Map<?, ?> d, String key) {
        return d.get(key) instanceof String s ? s : null;
    }

    private static Integer num(Map<?, ?> d, String key) {
        return d.get(key) instanceof Long l && l >= 1 && l <= SongDetails.MAX_NUMBER ? l.intValue() : null;
    }

    /**
     * The path of a {@code file://} URL ({@code file:///Users/a/My%20Music/x.m4a} →
     * {@code /Users/a/My Music/x.m4a}), percent-decoded as UTF-8 ({@code +} stays a plus). Anything
     * else is returned as it is.
     */
    public static String decodeLocation(String url) {
        String s = url;
        if (s.regionMatches(true, 0, "file://", 0, 7)) {
            s = s.substring(7);
            if (s.regionMatches(true, 0, "localhost/", 0, 10)) {
                s = s.substring(9);
            }
        }
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()
                    && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                out.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16));
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i += Character.charCount(cp);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the property list reader

    /** A property list as Java values: Map, List, String, Long, Double, Boolean, Instant, byte[] (data is skipped as null). */
    static final class PlistReader {
        private final XMLStreamReader xml;

        PlistReader(byte[] bytes) throws XMLStreamException {
            XMLInputFactory f = XMLInputFactory.newDefaultFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
            f.setProperty(XMLInputFactory.IS_COALESCING, true);
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            xml = f.createXMLStreamReader(new java.io.ByteArrayInputStream(bytes));
        }

        Object read() throws XMLStreamException {
            int ev = nextStart();
            if (ev < 0 || !xml.getLocalName().equals("plist")) {
                throw invalid("there is no <plist>");
            }
            int next = nextStart();
            if (next < 0) {
                return null;
            }
            return value();
        }

        /** Moves to the next start element; -1 at an end element or the end of the document. */
        private int nextStart() throws XMLStreamException {
            while (xml.hasNext()) {
                int ev = xml.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    return ev;
                }
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    return -1;
                }
                if (ev == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw invalid("it uses an entity (" + xml.getLocalName() + ")");
                }
            }
            return -1;
        }

        /** The value whose start element the reader is on; leaves it on the value's end element. */
        private Object value() throws XMLStreamException {
            String name = xml.getLocalName();
            switch (name) {
                case "dict" -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    while (nextStart() >= 0) {
                        if (!xml.getLocalName().equals("key")) {
                            throw invalid("a dictionary entry without a key");
                        }
                        String key = text();
                        if (nextStart() < 0) {
                            throw invalid("the key " + key + " has no value");
                        }
                        map.put(key, value());
                    }
                    return map;
                }
                case "array" -> {
                    List<Object> list = new ArrayList<>();
                    while (nextStart() >= 0) {
                        list.add(value());
                    }
                    return list;
                }
                case "string" -> {
                    return text();
                }
                case "integer" -> {
                    String t = text().strip();
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                case "real" -> {
                    try {
                        return Double.parseDouble(text().strip());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                case "true" -> {
                    text();
                    return Boolean.TRUE;
                }
                case "false" -> {
                    text();
                    return Boolean.FALSE;
                }
                case "date" -> {
                    try {
                        return Instant.parse(text().strip());
                    } catch (DateTimeParseException e) {
                        return null;
                    }
                }
                case "data" -> {
                    text();
                    return null;
                }
                default -> throw invalid("unknown element <" + name + ">");
            }
        }

        /** The text of the element the reader is on, up to its end element. */
        private String text() throws XMLStreamException {
            StringBuilder sb = new StringBuilder();
            while (xml.hasNext()) {
                int ev = xml.next();
                switch (ev) {
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                            sb.append(xml.getText());
                    case XMLStreamConstants.END_ELEMENT -> {
                        return sb.toString();
                    }
                    case XMLStreamConstants.ENTITY_REFERENCE ->
                            throw invalid("it uses an entity (" + xml.getLocalName() + ")");
                    case XMLStreamConstants.START_ELEMENT -> throw invalid("unexpected <" + xml.getLocalName() + ">");
                    default -> {
                    }
                }
            }
            throw invalid("it ends early");
        }
    }
}
