package io.siggi.togethertunes.fonts;

import io.siggi.togethertunes.settings.CjkVariant;
import java.util.ArrayList;
import java.util.List;

/**
 * Which manifest font a character needs ({@code docs/library-and-ingest.md#card-fonts}).
 *
 * <p>Letters of a script map to the entry for that script. CJK: kana → the Japanese font, hangul →
 * Korean, bopomofo → Traditional Chinese, and Han characters → the variant of the text they are in
 * ({@link #cjkVariant}). Characters of no particular script (emoji, symbols, CJK punctuation) map
 * to the entries whose code point ranges contain them, in manifest order.
 */
public final class ScriptFonts {
    private ScriptFonts() {
    }

    /**
     * The CJK variant a text's Han characters are drawn in: Japanese if it has kana, else Korean if
     * it has hangul, else Traditional Chinese if it has bopomofo, else {@code preferred}.
     */
    public static CjkVariant cjkVariant(CharSequence text, CjkVariant preferred) {
        boolean hangul = false, bopomofo = false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            switch (Character.UnicodeScript.of(cp)) {
                case HIRAGANA, KATAKANA -> {
                    return CjkVariant.JP;
                }
                case HANGUL -> hangul = true;
                case BOPOMOFO -> bopomofo = true;
                default -> {
                }
            }
        }
        return hangul ? CjkVariant.KR : bopomofo ? CjkVariant.TC : preferred;
    }

    /** The CJK variant a single CJK character needs in a text of {@code textVariant}. */
    static CjkVariant cjkVariantOf(int cp, CjkVariant textVariant) {
        return switch (Character.UnicodeScript.of(cp)) {
            case HIRAGANA, KATAKANA -> CjkVariant.JP;
            case HANGUL -> CjkVariant.KR;
            case BOPOMOFO -> CjkVariant.TC;
            default -> textVariant;
        };
    }

    /**
     * The manifest entries that would give {@code cp} a glyph, best first (empty: none would). Of
     * the CJK entries only the one of the variant the character needs in a text of
     * {@code textVariant} is listed.
     */
    public static List<FontManifest.Entry> candidates(FontManifest manifest, int cp, CjkVariant textVariant) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        List<FontManifest.Entry> out = new ArrayList<>();
        boolean common = script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED;
        CjkVariant variant = cjkVariantOf(cp, textVariant);
        for (FontManifest.Entry e : manifest.entries()) {
            if (e.cjk() != null && e.cjk() != variant) {
                continue;
            }
            if (common ? e.inRanges(cp) : e.scripts().contains(script)) {
                out.add(e);
            }
        }
        return out;
    }
}
