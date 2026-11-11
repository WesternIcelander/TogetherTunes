package io.siggi.togethertunes.fonts;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fonts installed on the host ({@code GraphicsEnvironment}): the families an admin can pick as
 * the card font, and the last step of the fallback chain ({@link CardFonts}).
 */
public interface SystemFonts {
    /** The installed font families, sorted (no logical fonts like {@code SansSerif}). */
    List<String> families();

    /** The installed family {@code name}, or null if it isn't installed. */
    Face family(String name);

    /** The first installed family (in {@link #families()} order) with a glyph for {@code cp}, or null. */
    Face find(int cp);

    /** No installed fonts (tests, and hosts without fontconfig). */
    SystemFonts NONE = of(List.of());

    /** The host's fonts, read once. */
    static SystemFonts installed() {
        return Installed.INSTANCE;
    }

    /** The given fonts as the installed ones, one family each (tests). */
    static SystemFonts of(List<Font> fonts) {
        return new Listed(fonts);
    }

    /** {@link SystemFonts} over a fixed list of fonts. */
    final class Listed implements SystemFonts {
        private static final Set<String> LOGICAL = Set.of(Font.DIALOG, Font.DIALOG_INPUT, Font.MONOSPACED,
                Font.SERIF, Font.SANS_SERIF);

        private final List<String> families;
        private final Map<String, Face> faces;
        private final Map<Integer, Optional<Face>> byCodePoint = new ConcurrentHashMap<>();

        Listed(List<Font> fonts) {
            Map<String, Face> map = new java.util.TreeMap<>();
            for (Font f : fonts) {
                String family = f.getFamily(java.util.Locale.ROOT);
                if (LOGICAL.contains(family) || map.containsKey(family)) {
                    continue;
                }
                map.put(family, new Face("system:" + family, family, f.deriveFont(Font.PLAIN, 1f), null));
            }
            this.faces = Collections.unmodifiableMap(map);
            this.families = List.copyOf(map.keySet());
        }

        @Override
        public List<String> families() {
            return families;
        }

        @Override
        public Face family(String name) {
            return faces.get(name);
        }

        @Override
        public Face find(int cp) {
            return byCodePoint.computeIfAbsent(cp, c -> {
                for (Face f : faces.values()) {
                    if (f.canDisplay(c)) {
                        return Optional.of(f);
                    }
                }
                return Optional.empty();
            }).orElse(null);
        }
    }

    /** The host's fonts, loaded on first use; none if Java2D can't list them. */
    final class Installed implements SystemFonts {
        static final Installed INSTANCE = new Installed();
        private volatile SystemFonts fonts;

        private SystemFonts fonts() {
            SystemFonts f = fonts;
            if (f == null) {
                synchronized (this) {
                    f = fonts;
                    if (f == null) {
                        List<Font> list = new ArrayList<>();
                        try {
                            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                            for (String family : ge.getAvailableFontFamilyNames(java.util.Locale.ROOT)) {
                                list.add(new Font(family, Font.PLAIN, 1));
                            }
                        } catch (RuntimeException | LinkageError e) {
                            System.getLogger(SystemFonts.class.getName()).log(System.Logger.Level.WARNING,
                                    "Could not list the installed fonts: " + e);
                        }
                        fonts = f = new Listed(list);
                    }
                }
            }
            return f;
        }

        @Override
        public List<String> families() {
            return fonts().families();
        }

        @Override
        public Face family(String name) {
            Face f = fonts().family(name);
            // The family's own bold face when it has one (Java2D picks it by style).
            return f == null ? null : new Face(f.id(), f.label(), f.regular(), new Font(name, Font.BOLD, 1));
        }

        @Override
        public Face find(int cp) {
            return fonts().find(cp);
        }
    }
}
