package io.siggi.togethertunes.fonts;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The card font shipped in the jar: DejaVu Sans 2.37, regular and bold, from the official DejaVu
 * release (licence in {@code META-INF/fonts/DejaVu-LICENSE}). Loaded with {@link Font#createFont},
 * so cards look the same on every host and only the FreeType library is a system requirement.
 */
public final class BundledFonts {
    /** The face id used in card keys. */
    public static final String ID = "bundled:dejavu-sans-2.37";
    public static final String LABEL = "DejaVu Sans (built in)";

    private BundledFonts() {
    }

    private static final class Holder {
        static final Face FACE = new Face(ID, LABEL, load("DejaVuSans.ttf"), load("DejaVuSans-Bold.ttf"));
    }

    /** DejaVu Sans. The first call loads it (Java2D's font code is initialised then). */
    public static Face dejaVuSans() {
        return Holder.FACE;
    }

    static Font load(String resource) {
        try (InputStream in = BundledFonts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("bundled font " + resource + " missing from the jar");
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            throw new IllegalStateException("bundled font " + resource + " unreadable: " + e.getMessage(), e);
        }
    }
}
