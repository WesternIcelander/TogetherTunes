package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * Runs a web unit-test file ({@code src/test/resources/web-tests/*.test.mjs}) with Node's test
 * runner, using the Node that the frontend build downloaded into {@code web/node/}. The compiled
 * modules under test are passed in environment variables.
 */
final class NodeTests {
    private NodeTests() {
    }

    static Path resource(String path) throws URISyntaxException {
        URL url = NodeTests.class.getClassLoader().getResource(path);
        assertNotNull(url, path + " is missing from the classpath");
        return Path.of(url.toURI());
    }

    /**
     * The markup of one view of the single-page app: the {@code <template id="<template>">} in
     * {@code web/index.html} (e.g. {@code view-room}).
     */
    static String view(String template) throws Exception {
        String index = Files.readString(resource("web/index.html"), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<template id=\"" + java.util.regex.Pattern.quote(template) + "\"[^>]*>(.*?)</template>",
                java.util.regex.Pattern.DOTALL).matcher(index);
        assertTrue(m.find(), template + " is missing from web/index.html");
        return m.group(1);
    }

    /**
     * Runs {@code web-tests/<testFile>} and asserts that it ran tests and none failed.
     *
     * @param env environment variable → classpath resource of a compiled module (e.g. {@code web/js/x.js})
     */
    static void run(String testFile, Map<String, String> env) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Path node = Path.of("web", "node", windows ? "node.exe" : "node").toAbsolutePath();
        // Skipped only when the frontend build (and with it Node) was skipped, e.g. -Dskip.npm.
        Assumptions.assumeTrue(Files.isExecutable(node), "no Node at " + node);
        Path tests = resource("web-tests/" + testFile);
        ProcessBuilder pb = new ProcessBuilder(List.of(node.toString(), "--test", tests.toString()))
                .redirectErrorStream(true);
        for (Map.Entry<String, String> e : env.entrySet()) {
            pb.environment().put(e.getKey(), resource(e.getValue()).toString());
        }
        Process p = pb.start();
        String out;
        try {
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "node --test timed out");
        } finally {
            p.destroyForcibly();
        }
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("ℹ fail 0") && !out.contains("ℹ pass 0"), out);
    }
}
