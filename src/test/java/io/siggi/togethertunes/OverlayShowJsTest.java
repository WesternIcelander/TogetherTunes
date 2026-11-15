package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Show QR code in the Now Playing View ({@code docs/ui.md} "Show QR code"): runs
 * {@code src/test/resources/web-tests/overlayshow.test.mjs} against the compiled
 * {@code web/js/overlayshow.js} (when the stream shows the overlay, where the frame lies, the SVG),
 * and checks that the frame wires it into the player bar's ⋯ menu and the video panel.
 */
class OverlayShowJsTest {
    @Test
    void overlayShowUnitTestsPass() throws Exception {
        NodeTests.run("overlayshow.test.mjs", Map.of("OVERLAYSHOW_JS", "web/js/overlayshow.js"));
    }

    @Test
    void frameUsesIt() throws Exception {
        String frame = Files.readString(NodeTests.resource("web/js/frame.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./overlayshow.js\"", "/overlay/layout", "overlayShown", "frameRect",
                "pb-show-qr", "now-qr", "SHOW_QR_KEY")) {
            assertTrue(frame.contains(s), s);
        }
        String html = Files.readString(NodeTests.resource("web/index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"pb-show-qr\""));
        assertTrue(html.contains("Show QR code"));
        assertTrue(html.contains("id=\"now-qr\""));
        String css = Files.readString(NodeTests.resource("web/css/main.css"), StandardCharsets.UTF_8);
        int at = css.indexOf(".now-qr {");
        assertTrue(at >= 0);
        assertTrue(css.substring(at, css.indexOf('}', at)).contains("pointer-events: none"), "never takes clicks");
        assertFalse(frame.contains("localStorage.setItem(SHOW_QR_KEY"), "through the try/catch helpers");
    }
}
