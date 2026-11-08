package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Checks that the Maven build put the web UI on the classpath under {@code /web/}: static
 * assets from {@code web/static/} and the tsc output of {@code web/src/} in {@code /web/js/}.
 */
class WebBuildTest {
    private static String read(String path) throws IOException {
        try (InputStream in = WebBuildTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void staticAssetsArePackaged() throws IOException {
        assertTrue(read("web/index.html").contains("src=\"/js/main.js\""));
        read("web/css/main.css");
    }

    @Test
    void typeScriptIsCompiledToEsModules() throws IOException {
        String main = read("web/js/main.js");
        // ES module output: the import survives as-is (no bundler, no CommonJS).
        assertTrue(main.contains("from \"./version.js\""), main);
        assertTrue(!main.contains("require("), main);
        read("web/js/version.js");
        read("web/js/main.js.map");
    }

    @Test
    void echoTestPageIsPackaged() throws IOException {
        String page = read("web/test/ws-echo.html");
        assertTrue(page.contains("src=\"/js/test/ws-echo.js\""), page);
        assertTrue(page.contains("href=\"/css/test.css\""), page);
        read("web/css/test.css");
        String script = read("web/js/test/ws-echo.js");
        assertTrue(script.contains("\"/ws/echo\""), script);
        assertTrue(read("web/index.html").contains("href=\"/test/ws-echo.html\""));
    }

    @Test
    void setupPageIsPackaged() throws IOException {
        String page = read("web/setup.html");
        assertTrue(page.contains("src=\"/js/setup.js\""), page);
        assertTrue(read("web/js/setup.js").contains("from \"./api.js\""));
        assertTrue(read("web/js/api.js").contains("\"/api/setup\""));
    }
}
