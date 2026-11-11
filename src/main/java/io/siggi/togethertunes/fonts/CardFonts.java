package io.siggi.togethertunes.fonts;

import io.siggi.togethertunes.settings.CjkVariant;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The fonts card text is drawn with ({@code docs/library-and-ingest.md#card-fonts}), as a fallback
 * chain resolved per character run: the admin's card font ({@code cards.font}) → the bundled DejaVu
 * Sans → the downloaded script fonts → any installed font that has the character. So a title never
 * shows boxes while some available font has its glyphs. Immutable; {@link #install} makes a chain
 * the one every card renderer uses ({@link #installed}).
 *
 * <p>Runs: each character takes the first font in the chain that has it; combining marks, joiners,
 * variation selectors and spaces stay in the run before them (so a base letter and its marks are
 * shaped together). Among the downloaded CJK fonts the one of the text's variant
 * ({@link ScriptFonts#cjkVariant}) comes first. A character no font has is drawn with the first
 * font (a box) and reported to the {@link MissingListener}.
 */
public final class CardFonts {
    /** Told about characters no available font has (when a card is laid out with them). */
    @FunctionalInterface
    public interface MissingListener {
        /**
         * @param codePoints the characters without a font, in text order, no duplicates
         * @param variant    the text's CJK variant (which CJK font would cover its Han characters)
         */
        void missing(Set<Integer> codePoints, CjkVariant variant);
    }

    /** A downloaded script font. */
    public record Downloaded(FontManifest.Entry entry, Face face) {
    }

    /** A run of {@code text[start, end)} drawn with {@code face}; {@code covered} is false for characters no font has. */
    public record Run(int start, int end, Face face, boolean covered) {
    }

    private static volatile CardFonts installed;

    private final Face preferred;
    private final Face bundled;
    private final List<Downloaded> downloaded;
    private final SystemFonts system;
    private final CjkVariant cjkPreference;
    private final MissingListener listener;
    private final String id;

    /**
     * @param preferred     the admin's card font; null or the bundled face: the bundled one
     * @param bundled       the bundled face
     * @param downloaded    the downloaded script fonts, in manifest order
     * @param system        the installed fonts
     * @param cjkPreference the CJK variant for Han without kana/hangul/bopomofo
     * @param listener      told about uncovered characters, or null
     */
    public CardFonts(Face preferred, Face bundled, List<Downloaded> downloaded, SystemFonts system,
                     CjkVariant cjkPreference, MissingListener listener) {
        this.bundled = Objects.requireNonNull(bundled);
        this.preferred = preferred == null || preferred.id().equals(bundled.id()) ? bundled : preferred;
        this.downloaded = List.copyOf(downloaded);
        this.system = Objects.requireNonNull(system);
        this.cjkPreference = Objects.requireNonNull(cjkPreference);
        this.listener = listener;
        StringBuilder sb = new StringBuilder("chain\n").append(this.preferred.id()).append('\n')
                .append(bundled.id()).append('\n').append(cjkPreference.wireName()).append('\n');
        for (Downloaded d : this.downloaded) {
            sb.append(d.face().id()).append('\n');
        }
        this.id = sha256(sb.toString()).substring(0, 16);
    }

    /** The bundled font alone, then the installed fonts (before {@link #install} is called). */
    public static CardFonts bundledOnly() {
        return new CardFonts(null, BundledFonts.dejaVuSans(), List.of(), SystemFonts.installed(), CjkVariant.JP, null);
    }

    /** The chain every card renderer uses ({@link #bundledOnly()} until one is installed). */
    public static CardFonts installed() {
        CardFonts f = installed;
        if (f == null) {
            synchronized (CardFonts.class) {
                f = installed;
                if (f == null) {
                    installed = f = bundledOnly();
                }
            }
        }
        return f;
    }

    /** Makes {@code fonts} the chain every card renderer uses from now on. */
    public static void install(CardFonts fonts) {
        installed = Objects.requireNonNull(fonts);
    }

    /** The admin's card font (the bundled face when none is set). */
    public Face preferred() {
        return preferred;
    }

    public List<Downloaded> downloaded() {
        return downloaded;
    }

    public CjkVariant cjkPreference() {
        return cjkPreference;
    }

    /** Identity of the chain's own fonts (not the installed ones): changes with the card font, variant or downloads. */
    public String id() {
        return id;
    }

    /** The same chain reporting to {@code listener}. */
    public CardFonts withListener(MissingListener listener) {
        return new CardFonts(preferred, bundled, downloaded, system, cjkPreference, listener);
    }

    // ------------------------------------------------------------------ runs

    /** The face for {@code cp} in a text of CJK variant {@code variant}, or null if no font has it. */
    public Face faceFor(int cp, CjkVariant variant) {
        if (preferred.canDisplay(cp)) {
            return preferred;
        }
        if (bundled.canDisplay(cp)) {
            return bundled;
        }
        Face cjkOther = null;
        CjkVariant wanted = ScriptFonts.cjkVariantOf(cp, variant);
        for (Downloaded d : downloaded) {
            if (!d.face().canDisplay(cp)) {
                continue;
            }
            CjkVariant v = d.entry().cjk();
            if (v == null || v == wanted) {
                return d.face();
            }
            if (cjkOther == null) {
                cjkOther = d.face();
            }
        }
        if (cjkOther != null) {
            return cjkOther;
        }
        return system.find(cp);
    }

    /** Splits {@code text} into runs by font (see the class comment). Reports uncovered characters. */
    public List<Run> runs(String text) {
        return runs(text, true);
    }

    private List<Run> runs(String text, boolean report) {
        List<Run> runs = new ArrayList<>();
        if (text.isEmpty()) {
            return runs;
        }
        CjkVariant variant = ScriptFonts.cjkVariant(text, cjkPreference);
        Set<Integer> missing = null;
        int runStart = 0;
        Face runFace = null;
        boolean runCovered = true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            Face face;
            boolean covered;
            if (runFace != null && (sticky(cp) || Character.isWhitespace(cp))
                    && (runFace.canDisplay(cp) || ignorable(cp))) {
                face = runFace;
                covered = runCovered;
            } else {
                face = faceFor(cp, variant);
                covered = face != null;
                if (face == null) {
                    if (ignorable(cp) && runFace != null) {
                        face = runFace;
                        covered = runCovered;
                    } else {
                        face = preferred;
                        if (!ignorable(cp)) {
                            if (missing == null) {
                                missing = new LinkedHashSet<>();
                            }
                            missing.add(cp);
                        }
                    }
                }
            }
            if (runFace != null && (face != runFace || covered != runCovered)) {
                runs.add(new Run(runStart, i, runFace, runCovered));
                runStart = i;
            }
            runFace = face;
            runCovered = covered;
            i = next;
        }
        runs.add(new Run(runStart, text.length(), runFace, runCovered));
        if (report && missing != null && listener != null) {
            listener.missing(Collections.unmodifiableSet(missing), variant);
        }
        return runs;
    }

    /** The characters of {@code text} no available font has (without reporting them). */
    public Set<Integer> uncovered(String text) {
        Set<Integer> out = new LinkedHashSet<>();
        CjkVariant variant = ScriptFonts.cjkVariant(text, cjkPreference);
        text.codePoints().forEach(cp -> {
            if (!ignorable(cp) && !Character.isWhitespace(cp) && !Character.isISOControl(cp)
                    && faceFor(cp, variant) == null) {
                out.add(cp);
            }
        });
        return out;
    }

    /** Combining marks, joiners and variation selectors: they belong with the character before them. */
    static boolean sticky(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK || ignorable(cp);
    }

    /** Joiners, variation selectors and other format characters that need no glyph of their own. */
    static boolean ignorable(int cp) {
        return cp == 0x200C || cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0xE0020 && cp <= 0xE007F) || Character.getType(cp) == Character.FORMAT;
    }

    // ------------------------------------------------------------------ layout and drawing

    /**
     * {@code text} with each run in its font at {@code style} and {@code size}; null for an empty
     * text.
     */
    public AttributedString attributed(String text, int style, float size) {
        if (text.isEmpty()) {
            return null;
        }
        AttributedString as = new AttributedString(text);
        for (Run r : runs(text)) {
            as.addAttribute(TextAttribute.FONT, r.face().font(style, size), r.start(), r.end());
        }
        return as;
    }

    /** The laid-out text (shaped per run, bidi applied), or null for an empty text. */
    public TextLayout layout(String text, int style, float size, FontRenderContext frc) {
        AttributedString as = attributed(text, style, size);
        return as == null ? null : new TextLayout(as.getIterator(), frc);
    }

    /** The advance width of {@code text}. */
    public double advance(String text, int style, float size, FontRenderContext frc) {
        TextLayout layout = layout(text, style, size, frc);
        return layout == null ? 0 : layout.getAdvance();
    }

    /** Draws {@code text} with its baseline's left end at ({@code x}, {@code y}) in the current paint. */
    public void draw(Graphics2D g, String text, int style, float size, float x, float y) {
        TextLayout layout = layout(text, style, size, g.getFontRenderContext());
        if (layout != null) {
            layout.draw(g, x, y);
        }
    }

    // ------------------------------------------------------------------ keys

    /**
     * Which fonts draw these texts: a hash over the face of every run (and whether it was covered).
     * It changes when the card font or CJK preference changes, or when a character that had no font
     * (or only an installed one) gets a downloaded one; texts whose runs stay the same keep their
     * key. Items store it for their card ({@code items.card_fonts}).
     */
    public String key(String... texts) {
        StringBuilder sb = new StringBuilder("card fonts v1\n");
        for (String t : texts) {
            String text = t == null ? "" : t;
            for (Run r : runs(text, false)) {
                sb.append(r.end() - r.start()).append(':').append(r.covered() ? r.face().id() : "-").append(' ');
            }
            sb.append('\n');
        }
        return sha256(sb.toString()).substring(0, 16);
    }

    static String sha256(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
