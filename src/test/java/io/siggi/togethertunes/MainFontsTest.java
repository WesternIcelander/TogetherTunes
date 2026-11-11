package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.fonts.TextRenderingCheck;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Main's startup text check: exit status 3 and the message, no stack trace, when Java2D can't draw text. */
class MainFontsTest {
    @Test
    void aFailedTextCheckExitsWithStatus3AndAMessage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Main.checkTextRendering(() -> {
            throw new UnsatisfiedLinkError("libfontmanager.so: libfreetype.so.6: cannot open shared object file");
        }, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(Main.EXIT_STARTUP, status);
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("FreeType") && text.contains("apt-get install libfreetype6"), text);
        assertTrue(!text.contains("\tat "), "no stack trace: " + text);
    }

    @Test
    void aWorkingCheckPrintsNothing() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, Main.checkTextRendering(TextRenderingCheck.JAVA2D,
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertEquals(0, err.size());
    }
}
