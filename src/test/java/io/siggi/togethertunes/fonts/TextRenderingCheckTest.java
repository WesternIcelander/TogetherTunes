package io.siggi.togethertunes.fonts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The startup text check ({@link TextRenderingCheck}) and its message, with the failures simulated. */
class TextRenderingCheckTest {
    @Test
    void textRenderingWorksHere() {
        assertNull(TextRenderingCheck.check());
    }

    @Test
    void missingFreeTypeIsNamedWithTheInstallLines() {
        String message = TextRenderingCheck.check(() -> {
            throw new UnsatisfiedLinkError("/usr/local/jdk/lib/libfontmanager.so: libfreetype.so.6: cannot open"
                    + " shared object file: No such file or directory");
        });
        assertTrue(message.contains("FreeType library (libfreetype.so.6)"), message);
        assertTrue(message.contains("UnsatisfiedLinkError: /usr/local/jdk/lib/libfontmanager.so"), message);
        assertTrue(message.contains("Debian/Ubuntu: apt-get install libfreetype6 fontconfig fonts-dejavu-core"), message);
        assertTrue(message.contains("Alpine:        apk add freetype fontconfig font-dejavu"), message);
        assertTrue(message.contains("Fedora:        dnf install freetype fontconfig dejavu-sans-fonts"), message);
        assertFalse(message.contains("\tat ") || message.contains("at java."), "no stack trace");
    }

    @Test
    void noFontsOrNoFontconfigIsNamedToo() {
        // What a container without fontconfig or without any font gives (seen in Debian slim), wrapped
        // the way a class initialiser would.
        String message = TextRenderingCheck.check(() -> {
            throw new ExceptionInInitializerError(
                    new RuntimeException("Fontconfig head is null, check your fonts or fonts configuration"));
        });
        assertTrue(message.contains("Missing: "), message);
        assertTrue(message.contains("RuntimeException: Fontconfig head is null"), "the root cause: " + message);
        String other = TextRenderingCheck.check(() -> {
            throw new IllegalStateException("something else");
        });
        assertTrue(other.contains("Missing: "), other);
        assertTrue(other.contains("apt-get install"), other);
    }

    @Test
    void whatTheHostLacksIsNamedFirst() {
        Throwable noFonts = new RuntimeException("Fontconfig head is null, check your fonts or fonts configuration");
        String noFreeType = TextRenderingCheck.message(noFonts, new TextRenderingCheck.Host(false, true, true));
        assertTrue(noFreeType.contains("Missing: the FreeType library (libfreetype.so.6)\n"), noFreeType);
        String nothing = TextRenderingCheck.message(noFonts, new TextRenderingCheck.Host(false, false, false));
        assertTrue(nothing.contains("Missing: the FreeType library (libfreetype.so.6), fontconfig (libfontconfig.so.1),"
                + " an installed font"), nothing);
        String unknown = TextRenderingCheck.message(noFonts, TextRenderingCheck.Host.UNKNOWN);
        assertTrue(unknown.contains("Missing: fontconfig or an installed font"), unknown);
        String allThere = TextRenderingCheck.message(noFonts, new TextRenderingCheck.Host(true, true, true));
        assertTrue(allThere.contains("Missing: fontconfig or an installed font"), allThere);
    }

    @Test
    void outOfMemoryIsNotAFontProblem() {
        assertThrows(OutOfMemoryError.class, () -> TextRenderingCheck.check(() -> {
            throw new OutOfMemoryError("heap");
        }));
    }
}
