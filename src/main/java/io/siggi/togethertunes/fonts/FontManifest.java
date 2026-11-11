package io.siggi.togethertunes.fonts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.settings.CjkVariant;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The script fonts the server can download ({@code docs/library-and-ingest.md#card-fonts}), from
 * {@code manifest.json} next to this class: Noto fonts from their official sources, each pinned to
 * one URL, its size and its SHA-256. Nothing else is ever fetched: a download names a manifest
 * entry, never a URL.
 *
 * <p>Each entry says which characters it is for: the Unicode scripts
 * ({@link Character.UnicodeScript}) it covers, and for characters of no particular script
 * ({@code COMMON}/{@code INHERITED}: emoji, symbols, CJK punctuation) the code point ranges the font
 * actually has glyphs for. The four CJK entries carry their {@link CjkVariant}.
 */
public final class FontManifest {
    /** No manifest file may be bigger than this (a sanity limit on top of each entry's size). */
    public static final long MAX_FONT_BYTES = 64L * 1024 * 1024;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.(ttf|otf)");
    private static final Pattern LICENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.txt");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** The scripts the CJK entries are for; which of the four is picked follows {@link ScriptFonts#cjkVariant}. */
    static final Set<Character.UnicodeScript> CJK_SCRIPTS = Collections.unmodifiableSet(EnumSet.of(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO));

    /**
     * One downloadable font.
     *
     * @param file    the file name in {@code <data>/fonts/}
     * @param size    the exact size in bytes
     * @param licence the licence text (a resource in {@code licenses/}) written next to the font
     * @param cjk     the CJK variant, or null for a non-CJK font
     * @param ranges  sorted, non-overlapping inclusive code point ranges {@code [from0, to0, from1, to1, ...]}
     */
    public record Entry(String id, String name, String file, long size, String sha256, URI url, String licence,
                        Set<Character.UnicodeScript> scripts, CjkVariant cjk, int[] ranges) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(file);
            Objects.requireNonNull(sha256);
            Objects.requireNonNull(url);
            Objects.requireNonNull(licence);
            scripts = Collections.unmodifiableSet(scripts.isEmpty()
                    ? EnumSet.noneOf(Character.UnicodeScript.class) : EnumSet.copyOf(scripts));
            ranges = ranges.clone();
        }

        /** Whether one of its ranges contains {@code cp}. */
        public boolean inRanges(int cp) {
            int lo = 0, hi = ranges.length / 2 - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cp < ranges[2 * mid]) {
                    hi = mid - 1;
                } else if (cp > ranges[2 * mid + 1]) {
                    lo = mid + 1;
                } else {
                    return true;
                }
            }
            return false;
        }

        /** The SHA-256 as bytes. */
        byte[] sha256Bytes() {
            return HexFormat.of().parseHex(sha256);
        }
    }

    private final Map<String, Entry> entries;

    private FontManifest(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** The manifest shipped in the jar (https URLs only). */
    public static FontManifest load() {
        try (InputStream in = FontManifest.class.getResourceAsStream("manifest.json")) {
            if (in == null) {
                throw new IllegalStateException("font manifest missing from the jar");
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            throw new IllegalStateException("font manifest unreadable: " + e.getMessage(), e);
        }
    }

    /** A manifest of the given entries (tests: local URLs). */
    public static FontManifest of(List<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (map.put(e.id(), e) != null) {
                throw new IllegalArgumentException("duplicate font " + e.id());
            }
        }
        return new FontManifest(map);
    }

    /**
     * Parses a manifest. {@code httpsOnly}: every URL must be {@code https} (the shipped one); tests
     * use a local {@code http} server.
     */
    static FontManifest parse(Reader reader, boolean httpsOnly) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        List<Entry> list = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("fonts")) {
            JsonObject o = el.getAsJsonObject();
            String id = string(o, "id");
            check(ID.matcher(id).matches(), "bad id " + id);
            String file = string(o, "file");
            check(FILE.matcher(file).matches(), id + ": bad file name " + file);
            String licence = string(o, "licence");
            check(LICENCE.matcher(licence).matches(), id + ": bad licence " + licence);
            String sha = string(o, "sha256").toLowerCase(Locale.ROOT);
            check(SHA256.matcher(sha).matches(), id + ": bad sha256");
            long size = o.get("size").getAsLong();
            check(size > 0 && size <= MAX_FONT_BYTES, id + ": bad size " + size);
            URI url = URI.create(string(o, "url"));
            check(url.getHost() != null && ("https".equals(url.getScheme())
                    || (!httpsOnly && "http".equals(url.getScheme()))), id + ": bad URL " + url);
            EnumSet<Character.UnicodeScript> scripts = EnumSet.noneOf(Character.UnicodeScript.class);
            JsonArray s = o.getAsJsonArray("scripts");
            if (s != null) {
                for (JsonElement e : s) {
                    scripts.add(Character.UnicodeScript.forName(e.getAsString()));
                }
            }
            CjkVariant cjk = null;
            if (o.has("cjk") && !o.get("cjk").isJsonNull()) {
                cjk = CjkVariant.valueOf(o.get("cjk").getAsString().toUpperCase(Locale.ROOT));
            }
            int[] ranges = o.has("ranges") ? parseRanges(o.get("ranges").getAsString()) : new int[0];
            list.add(new Entry(id, string(o, "name"), file, size, sha, url, licence, scripts, cjk, ranges));
        }
        return of(list);
    }

    /** {@code "2190-21FF 2300 ..."} (hex, inclusive) → sorted pairs. */
    static int[] parseRanges(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            return new int[0];
        }
        String[] parts = t.split("\\s+");
        int[] out = new int[parts.length * 2];
        int prevEnd = -1;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            int dash = p.indexOf('-');
            int from = Integer.parseInt(dash < 0 ? p : p.substring(0, dash), 16);
            int to = dash < 0 ? from : Integer.parseInt(p.substring(dash + 1), 16);
            check(from <= to && from > prevEnd && to <= Character.MAX_CODE_POINT, "bad range " + p);
            out[2 * i] = from;
            out[2 * i + 1] = to;
            prevEnd = to;
        }
        return out;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        check(e != null && e.isJsonPrimitive(), "missing " + key);
        return e.getAsString();
    }

    private static void check(boolean ok, String problem) {
        if (!ok) {
            throw new IllegalArgumentException("font manifest: " + problem);
        }
    }

    /** Every entry, in manifest order. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** The entry with this id, or null. */
    public Entry entry(String id) {
        return entries.get(id);
    }

    /** The total size of all entries in bytes. */
    public long totalBytes() {
        long total = 0;
        for (Entry e : entries.values()) {
            total += e.size();
        }
        return total;
    }
}
