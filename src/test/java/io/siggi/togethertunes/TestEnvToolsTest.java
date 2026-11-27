package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The test environment's own unit tests ({@code tools/testenv/test/testenv.test.mjs}): the fixture
 * key and the manifest shape. They start no server; building the fixture and the smoke helper are
 * tried by hand (see {@code tools/testenv/README.md}).
 */
class TestEnvToolsTest {
    @Test
    void keyAndManifestUnitTestsPass() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Path node = Path.of("web", "node", windows ? "node.exe" : "node").toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(node), "no Node at " + node);
        Path tests = Path.of("tools", "testenv", "test", "testenv.test.mjs").toAbsolutePath();
        assertTrue(Files.isRegularFile(tests), tests.toString());
        Process p = new ProcessBuilder(List.of(node.toString(), "--test", tests.toString()))
                .redirectErrorStream(true).start();
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
