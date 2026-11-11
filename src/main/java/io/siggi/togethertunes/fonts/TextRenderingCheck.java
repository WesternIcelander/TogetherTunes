package io.siggi.togethertunes.fonts;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The startup check that Java2D can draw text ({@code docs/architecture.md#runtime-requirements}):
 * the cards are rendered with Java2D, which needs the FreeType library even when headless. Without
 * it the first card would crash the server with an {@code UnsatisfiedLinkError}; this check runs
 * before anything renders, and {@code Main} exits with a clear message instead.
 *
 * <p>The probe loads the bundled DejaVu Sans, measures a string and draws it into a small image.
 * Run with {@code java -cp TogetherTunes.jar io.siggi.togethertunes.fonts.TextRenderingCheck} it
 * prints {@code text rendering works} or the message, and exits with 0 or 3.
 */
public final class TextRenderingCheck {
    /** The install lines printed with the message. */
    public static final String INSTALL_HELP = """
            Install them with your system's package manager, for example:
              Debian/Ubuntu: apt-get install libfreetype6 fontconfig fonts-dejavu-core
              Alpine:        apk add freetype fontconfig font-dejavu
              Fedora:        dnf install freetype fontconfig dejavu-sans-fonts""";

    private TextRenderingCheck() {
    }

    /** What the probe does: anything thrown means text rendering doesn't work. */
    @FunctionalInterface
    public interface Probe {
        void run() throws Exception;
    }

    /**
     * The real probe: a logical font first (it starts Java2D's font manager, which reports the real
     * cause: FreeType or fontconfig missing, or no font at all), then the bundled font, each
     * measured and drawn into a small image.
     */
    public static final Probe JAVA2D = () -> {
        probe(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 20));
        probe(BundledFonts.dejaVuSans().font(java.awt.Font.BOLD, 20f));
    };

    private static void probe(java.awt.Font font) {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            if (g.getFont().getStringBounds("Ag", g.getFontRenderContext()).getWidth() <= 0) {
                throw new IllegalStateException("the bundled font measures text as empty");
            }
            new TextLayout("Ag", g.getFont(), g.getFontRenderContext()).draw(g, 2, 24);
        } finally {
            g.dispose();
        }
    }

    /** Runs {@link #JAVA2D}; null if text rendering works, else the message for the admin. */
    public static String check() {
        return check(JAVA2D);
    }

    /** Runs {@code probe}; null if it worked, else the message for the admin (no stack trace). */
    public static String check(Probe probe) {
        try {
            probe.run();
            return null;
        } catch (OutOfMemoryError | StackOverflowError e) {
            throw e;
        } catch (Throwable t) {
            return message(t);
        }
    }

    /** What is installed, as far as a look at the usual places shows; null fields: unknown (not Linux). */
    record Host(Boolean freetype, Boolean fontconfig, Boolean fonts) {
        static final Host UNKNOWN = new Host(null, null, null);
    }

    private static final List<String> LIB_DIRS = List.of("/lib", "/lib64", "/usr/lib", "/usr/lib64", "/usr/local/lib");
    private static final List<String> FONT_DIRS = List.of("/usr/share/fonts", "/usr/local/share/fonts");

    /** Looks for the FreeType and fontconfig libraries and for font files where Linux systems keep them. */
    static Host scanHost() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return Host.UNKNOWN;
        }
        return new Host(find(LIB_DIRS, 2, n -> n.startsWith("libfreetype.so")),
                find(LIB_DIRS, 2, n -> n.startsWith("libfontconfig.so")),
                find(FONT_DIRS, 4, n -> n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")
                        || n.endsWith(".pfb") || n.endsWith(".pcf.gz")));
    }

    private static boolean find(List<String> dirs, int depth, Predicate<String> name) {
        for (String d : dirs) {
            Path dir = Path.of(d);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.find(dir, depth,
                    (p, attrs) -> name.test(p.getFileName().toString().toLowerCase(Locale.ROOT)))) {
                if (files.findAny().isPresent()) {
                    return true;
                }
            } catch (IOException | UncheckedIOException e) {
                // unreadable: keep looking elsewhere
            }
        }
        return false;
    }

    /** The message for a failed probe: what broke, what is probably missing, how to install it. */
    static String message(Throwable t) {
        return message(t, scanHost());
    }

    /** {@link #message(Throwable)} with what {@code host} says is installed. */
    static String message(Throwable t, Host host) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null && all.length() < 4000; c = c.getCause()) {
            all.append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
        }
        String text = all.toString().toLowerCase(Locale.ROOT);
        String missing;
        if (text.contains("libfreetype")) {
            missing = "the FreeType library (libfreetype.so.6)";
        } else if (text.contains("libfontconfig")) {
            missing = "fontconfig (libfontconfig.so.1)";
        } else if (text.contains("fontconfig head is null") || text.contains("no fonts")) {
            missing = "fontconfig or an installed font (Java's font manager found no fonts)";
        } else if (text.contains("libfontmanager") || text.contains("unsatisfiedlinkerror")) {
            missing = "a native library Java's font code needs (FreeType or fontconfig)";
        } else {
            missing = "unknown; see the error";
        }
        // What a look at the system shows beats a guess from the message (e.g. without FreeType,
        // fontconfig can't load either, and Java then only says it found no fonts).
        List<String> absent = new ArrayList<>();
        if (Boolean.FALSE.equals(host.freetype())) {
            absent.add("the FreeType library (libfreetype.so.6)");
        }
        if (Boolean.FALSE.equals(host.fontconfig())) {
            absent.add("fontconfig (libfontconfig.so.1)");
        }
        if (Boolean.FALSE.equals(host.fonts())) {
            absent.add("an installed font (nothing in /usr/share/fonts)");
        }
        if (!absent.isEmpty()) {
            missing = String.join(", ", absent);
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "Java can't render text on this system, so the server can't draw its cards.\n"
                + "  Error: " + root.getClass().getSimpleName() + ": " + root.getMessage() + "\n"
                + "  Missing: " + missing + "\n"
                + "Java's font code needs the FreeType library, fontconfig and at least one installed font,\n"
                + "even though the cards use the server's own font (DejaVu Sans).\n"
                + INSTALL_HELP;
    }

    /** Prints whether text rendering works; exit status 0, or 3 when it doesn't. */
    public static void main(String[] args) {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        String problem = check();
        if (problem != null) {
            System.err.println(problem);
            System.exit(3);
        }
        System.out.println("text rendering works");
    }
}
