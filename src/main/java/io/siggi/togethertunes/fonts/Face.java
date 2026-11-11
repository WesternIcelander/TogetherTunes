package io.siggi.togethertunes.fonts;

import java.awt.Font;
import java.util.Objects;

/**
 * One font a card can draw text with: a regular face and, when the font has one, a bold face
 * (otherwise Java2D emboldens the regular one).
 *
 * @param id      a stable identity for card keys, e.g. {@code bundled:dejavu-sans},
 *                {@code noto:thai:<sha256 prefix>}, {@code upload:<id>}, {@code system:<font name>}
 * @param label   what the admin panel shows
 * @param regular the regular face (any size; it is derived)
 * @param bold    the bold face, or null
 */
public record Face(String id, String label, Font regular, Font bold) {
    public Face {
        Objects.requireNonNull(id);
        Objects.requireNonNull(label);
        Objects.requireNonNull(regular);
    }

    /** The face for {@code style} ({@link Font#PLAIN} or {@link Font#BOLD}) at {@code size} points. */
    public Font font(int style, float size) {
        if ((style & Font.BOLD) != 0 && bold != null) {
            return bold.deriveFont(style & ~Font.BOLD, size);
        }
        return regular.deriveFont(style, size);
    }

    /** Whether the font has a glyph for {@code cp}. */
    public boolean canDisplay(int cp) {
        return regular.canDisplay(cp);
    }
}
