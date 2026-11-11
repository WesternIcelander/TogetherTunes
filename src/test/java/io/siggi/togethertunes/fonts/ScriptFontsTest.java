package io.siggi.togethertunes.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.settings.CjkVariant;
import java.io.StringReader;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The shipped font manifest and the character → script font mapping ({@link ScriptFonts}). */
class ScriptFontsTest {
    private static final FontManifest MANIFEST = FontManifest.load();

    private static String first(int cp, CjkVariant variant) {
        List<FontManifest.Entry> c = ScriptFonts.candidates(MANIFEST, cp, variant);
        return c.isEmpty() ? null : c.get(0).id();
    }

    @Test
    void theManifestCoversTheRequiredScriptsWithPinnedOfficialFiles() {
        Set<Character.UnicodeScript> covered = EnumSet.noneOf(Character.UnicodeScript.class);
        Set<String> ids = new HashSet<>();
        for (FontManifest.Entry e : MANIFEST.entries()) {
            covered.addAll(e.scripts());
            ids.add(e.id());
            assertEquals("https", e.url().getScheme(), e.id());
            assertTrue(Set.of("raw.githubusercontent.com").contains(e.url().getHost()), e.url().toString());
            String path = e.url().getPath();
            assertTrue(path.startsWith("/notofonts/") || path.startsWith("/google/fonts/"), path);
            assertTrue(e.sha256().matches("[0-9a-f]{64}"), e.id());
            assertTrue(e.size() > 1000 && e.size() <= FontManifest.MAX_FONT_BYTES, e.id());
            assertNotNull(FontDownloader.class.getResource("licenses/" + e.licence()), e.licence());
        }
        for (Character.UnicodeScript s : List.of(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL, Character.UnicodeScript.THAI,
                Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.TAMIL,
                Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM,
                Character.UnicodeScript.GUJARATI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.ORIYA,
                Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW, Character.UnicodeScript.ETHIOPIC,
                Character.UnicodeScript.KHMER, Character.UnicodeScript.LAO, Character.UnicodeScript.MYANMAR,
                Character.UnicodeScript.SINHALA, Character.UnicodeScript.TIBETAN)) {
            assertTrue(covered.contains(s), "no font for " + s);
        }
        assertTrue(ids.containsAll(Set.of("cjk-jp", "cjk-kr", "cjk-sc", "cjk-tc", "emoji", "symbols", "symbols2")));
        long total = MANIFEST.totalBytes();
        assertTrue(total > 20_000_000 && total < 100_000_000, "total " + total);
    }

    @Test
    void theCjkVariantFollowsTheText() {
        assertEquals(CjkVariant.JP, ScriptFonts.cjkVariant("東京の夜", CjkVariant.SC), "kana → Japanese");
        assertEquals(CjkVariant.JP, ScriptFonts.cjkVariant("トウキョウ", CjkVariant.KR), "katakana → Japanese");
        assertEquals(CjkVariant.KR, ScriptFonts.cjkVariant("서울 漢字", CjkVariant.JP), "hangul → Korean");
        assertEquals(CjkVariant.TC, ScriptFonts.cjkVariant("ㄅㄆ 中文", CjkVariant.JP), "bopomofo → Traditional");
        assertEquals(CjkVariant.SC, ScriptFonts.cjkVariant("中文歌曲", CjkVariant.SC), "Han alone → preferred");
        assertEquals(CjkVariant.JP, ScriptFonts.cjkVariant("中文歌曲", CjkVariant.JP));
        assertEquals(CjkVariant.TC, ScriptFonts.cjkVariant("Hello", CjkVariant.TC));
    }

    @Test
    void charactersMapToTheirScriptsFont() {
        assertEquals("cjk-jp", first('漢', CjkVariant.JP));
        assertEquals("cjk-sc", first('漢', CjkVariant.SC), "Han alone: the text's variant");
        assertEquals("cjk-jp", first('の', CjkVariant.SC), "kana is always Japanese");
        assertEquals("cjk-kr", first('한', CjkVariant.JP), "hangul is always Korean");
        assertEquals("cjk-tc", first('ㄅ', CjkVariant.JP), "bopomofo: Traditional Chinese");
        assertEquals("cjk-kr", first('。', CjkVariant.KR), "CJK punctuation: the text's variant");
        assertEquals(1, ScriptFonts.candidates(MANIFEST, '漢', CjkVariant.JP).size(), "one CJK variant only");
        assertEquals("thai", first('ก', CjkVariant.JP));
        assertEquals("devanagari", first('क', CjkVariant.JP));
        assertEquals("arabic", first('ع', CjkVariant.JP));
        assertEquals("hebrew", first('ש', CjkVariant.JP));
        assertEquals("ethiopic", first('ሀ', CjkVariant.JP));
        assertEquals("khmer", first('ក', CjkVariant.JP));
        assertEquals("lao", first('ກ', CjkVariant.JP));
        assertEquals("myanmar", first('က', CjkVariant.JP));
        assertEquals("sinhala", first('ක', CjkVariant.JP));
        assertEquals("tibetan", first('ཀ', CjkVariant.JP));
        assertEquals("emoji", first(0x1F3B5, CjkVariant.JP), "🎵");
        assertEquals("symbols2", first(0x2800 + 5, CjkVariant.JP), "braille");
        assertEquals(null, first('A', CjkVariant.JP), "Latin is the bundled font's job");
        assertTrue(ScriptFonts.candidates(MANIFEST, 0x10FFFD, CjkVariant.JP).isEmpty(), "private use: nothing");
    }

    @Test
    void manifestsAreValidated() {
        String ok = "{\"fonts\": [{\"id\": \"x\", \"name\": \"X\", \"file\": \"X.ttf\", \"size\": 10,"
                + " \"sha256\": \"" + "a".repeat(64) + "\", \"url\": \"%s\", \"licence\": \"OFL.txt\","
                + " \"scripts\": [\"THAI\"], \"ranges\": \"20-7E 2190-21FF\"}]}";
        FontManifest m = FontManifest.parse(new StringReader(ok.formatted("http://127.0.0.1/x.ttf")), false);
        assertTrue(m.entry("x").inRanges(0x41));
        assertTrue(m.entry("x").inRanges(0x21FF));
        assertFalse(m.entry("x").inRanges(0x7F));
        assertThrows(IllegalArgumentException.class,
                () -> FontManifest.parse(new StringReader(ok.formatted("http://127.0.0.1/x.ttf")), true),
                "the shipped manifest takes https only");
        assertThrows(IllegalArgumentException.class,
                () -> FontManifest.parse(new StringReader(ok.formatted("https://h/x.ttf").replace("X.ttf", "../X.ttf")),
                        true), "file names stay in the fonts directory");
        assertThrows(IllegalArgumentException.class, () -> FontManifest.parseRanges("30-20"));
        assertThrows(IllegalArgumentException.class, () -> FontManifest.parseRanges("20-30 25"), "overlap");
    }
}
