package io.siggi.togethertunes.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.settings.CjkVariant;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The per-run fallback chain ({@link CardFonts}) over mixed-script text. */
class CardFontsTest {
    private static final Face BUNDLED = BundledFonts.dejaVuSans();
    private static final Face THAI = new Face("noto:thai:test", "Thai", TestFonts.font(TestFonts.thai()), null);
    private static final Font LAO = TestFonts.font(TestFonts.lao());
    private static final FontManifest.Entry THAI_ENTRY = TestFonts.entry("thai", "NotoSansThai-Regular.ttf",
            TestFonts.thai(), "http://127.0.0.1/thai.ttf", Character.UnicodeScript.THAI);

    /** Chain: bundled; Thai downloaded if {@code withThai}; no installed fonts. */
    private static CardFonts chain(boolean withThai, CardFonts.MissingListener listener) {
        return new CardFonts(null, BUNDLED, withThai ? List.of(new CardFonts.Downloaded(THAI_ENTRY, THAI)) : List.of(),
                SystemFonts.NONE, CjkVariant.JP, listener);
    }

    private static String describe(String text, List<CardFonts.Run> runs) {
        StringBuilder sb = new StringBuilder();
        for (CardFonts.Run r : runs) {
            sb.append('[').append(text, r.start(), r.end()).append("|").append(r.covered() ? r.face().id() : "-")
                    .append(']');
        }
        return sb.toString();
    }

    @Test
    void eachRunTakesTheFirstFontInTheChainThatHasIt() {
        List<Set<Integer>> reported = new ArrayList<>();
        List<CjkVariant> variants = new ArrayList<>();
        CardFonts fonts = chain(true, (cps, v) -> {
            reported.add(cps);
            variants.add(v);
        });
        String text = "Hello สวัสดี ສະບາຍດີ 漢字!";
        // DejaVu Sans has Lao but not Thai or Han.
        assertEquals("[Hello |" + BundledFonts.ID + "][สวัสดี |noto:thai:test][ສະບາຍດີ |" + BundledFonts.ID
                + "][漢字|-][!|" + BundledFonts.ID + "]", describe(text, fonts.runs(text)));
        assertEquals(List.of(Set.of((int) '漢', (int) '字')), reported, "uncovered characters are reported once");
        assertEquals(List.of(CjkVariant.JP), variants);
        assertEquals(Set.of((int) '漢', (int) '字'), fonts.uncovered(text));
        assertEquals(1, reported.size(), "uncovered() doesn't report");
    }

    @Test
    void installedFontsComeLastAndOnlyForWhatNothingElseHas() {
        Font thaiInstalled = TestFonts.font(TestFonts.thai());
        String installed = "system:" + thaiInstalled.getFamily(java.util.Locale.ROOT);
        CardFonts onlyInstalled = new CardFonts(null, BUNDLED, List.of(), SystemFonts.of(List.of(thaiInstalled)),
                CjkVariant.JP, null);
        assertEquals("[A |" + BundledFonts.ID + "][ก|" + installed + "]", describe("A ก", onlyInstalled.runs("A ก")));
        CardFonts both = new CardFonts(null, BUNDLED, List.of(new CardFonts.Downloaded(THAI_ENTRY, THAI)),
                SystemFonts.of(List.of(thaiInstalled)), CjkVariant.JP, null);
        assertEquals("[ก|noto:thai:test]", describe("ก", both.runs("ก")), "a downloaded font before an installed one");
        assertTrue(onlyInstalled.uncovered("ก").isEmpty());
    }

    @Test
    void withoutTheDownloadedFontThaiIsUncoveredAndTheKeyChanges() {
        String title = "สวัสดี";
        CardFonts without = chain(false, null);
        CardFonts with = chain(true, null);
        assertEquals("[สวัสดี|-]", describe(title, without.runs(title)));
        assertNotEquals(without.key(title, "", ""), with.key(title, "", ""), "a Thai title's card gets made again");
        assertEquals(without.key("Plain Latin", "Artist", ""), with.key("Plain Latin", "Artist", ""),
                "a Latin title's card stays");
        assertNotEquals(without.id(), with.id());
    }

    @Test
    void thePreferredFontComesFirstAndTheBundledOneFillsIn() {
        Face preferred = new Face("upload:lao", "Lao", LAO, null);
        CardFonts fonts = new CardFonts(preferred, BUNDLED, List.of(), SystemFonts.NONE, CjkVariant.JP, null);
        String text = "ກ Ω";
        List<CardFonts.Run> runs = fonts.runs(text);
        assertSame(preferred, runs.get(0).face(), describe(text, runs));
        assertEquals(LAO.canDisplay('Ω') ? "upload:lao" : BundledFonts.ID, runs.get(runs.size() - 1).face().id());
        assertNotEquals(chain(false, null).key("ກ"), fonts.key("ກ"), "another card font: another key");
        assertEquals(chain(false, null).key("Title"), fonts.key("Title"), "text it has no glyphs for keeps its key");
        assertNotEquals(chain(false, null).id(), fonts.id());
    }

    @Test
    void marksJoinersAndSpacesStayWithTheirRun() {
        CardFonts fonts = chain(true, null);
        assertEquals(1, fonts.runs("é a‍b").size(), "combining mark and ZWJ");
        assertEquals("[ก ข|noto:thai:test]", describe("ก ข", fonts.runs("ก ข")), "the space stays in the Thai run");
        assertTrue(fonts.uncovered("a‍️ b").isEmpty(), "joiners and selectors need no glyph");
    }

    @Test
    void theCjkFontOfTheTextsVariantComesFirst() {
        Face jp = new Face("noto:cjk-jp:test", "JP", THAI.regular(), null); // stand-ins: both "have" Thai letters
        Face sc = new Face("noto:cjk-sc:test", "SC", THAI.regular(), null);
        CardFonts fonts = new CardFonts(null, BUNDLED, List.of(
                new CardFonts.Downloaded(TestFonts.cjk(CjkVariant.JP, "http://127.0.0.1/jp"), jp),
                new CardFonts.Downloaded(TestFonts.cjk(CjkVariant.SC, "http://127.0.0.1/sc"), sc)),
                SystemFonts.NONE, CjkVariant.SC, null);
        assertSame(sc, fonts.faceFor('ก', CjkVariant.SC));
        assertSame(jp, fonts.faceFor('ก', CjkVariant.JP));
        assertNull(chain(false, null).faceFor('漢', CjkVariant.JP), "no CJK font: uncovered");
    }

    @Test
    void layoutAndDrawingUseTheRunsFonts() {
        CardFonts fonts = chain(true, null);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        assertTrue(fonts.advance("สวัสดี", Font.BOLD, 40f, frc) > 40);
        assertEquals(0, fonts.advance("", Font.BOLD, 40f, frc));
        // A Thai title drawn with and without the Thai font: boxes vs glyphs.
        CardRenderer withThai = new CardRenderer(OutputProfile.P720_30, fonts);
        CardRenderer without = new CardRenderer(OutputProfile.P720_30, chain(false, null));
        CardRenderer.ItemCard card = new CardRenderer.ItemCard("สวัสดีชาวโลก", "Artist", null, null);
        assertFalse(same(withThai.renderItem(card), without.renderItem(card)));
        CardRenderer.ItemCard latin = new CardRenderer.ItemCard("Hello", "Artist", null, null);
        assertTrue(same(withThai.renderItem(latin), without.renderItem(latin)), "Latin cards don't change");
        assertNotEquals(CardRenderer.fontKey(fonts, "สวัสดีชาวโลก", "Artist", null),
                CardRenderer.fontKey(chain(false, null), "สวัสดีชาวโลก", "Artist", null));
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
