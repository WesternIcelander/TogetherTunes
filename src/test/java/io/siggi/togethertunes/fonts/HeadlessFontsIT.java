package io.siggi.togethertunes.fonts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The startup text check ({@link TextRenderingCheck}) on Linux hosts without font libraries, run
 * from the built jar in a container ({@code docs/architecture.md#runtime-requirements}): the Temurin
 * JRE image (Ubuntu, which comes with FreeType, fontconfig and DejaVu) with packages removed. Text
 * renders only with all three of FreeType, fontconfig and an installed font, also though the cards
 * use the bundled DejaVu Sans (Java's font manager starts through fontconfig and needs a font);
 * without any of them the check exits with status 3 and names what is missing, with the install
 * lines and no stack trace.
 */
class HeadlessFontsIT {
    private static Path jar;

    @BeforeAll
    static void findJar() throws Exception {
        try (var files = Files.list(Path.of("target"))) {
            jar = files.filter(p -> p.getFileName().toString().matches("TogetherTunes-.*\\.jar")
                    && !p.getFileName().toString().startsWith("original-")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("build the jar first (mvn package)"));
        }
    }

    private static final String FONTCONFIG = "fontconfig fontconfig-config libfontconfig1";
    private static final String FONTS = "fonts-dejavu-core fonts-dejavu-mono";

    /**
     * Runs the check in the JRE image with {@code purge} (packages) removed; returns its output and
     * exit status. One image for all cases, so its architecture always matches the JRE's.
     */
    private static String run(String purge) {
        String dockerfile = """
                FROM eclipse-temurin:21-jre
                RUN if [ -n "%s" ]; then dpkg --purge --force-depends %s && (fc-list 2>/dev/null || true); fi
                COPY app.jar /app.jar
                """.formatted(purge, purge);
        ImageFromDockerfile image = new ImageFromDockerfile("togethertunes-fonts-it", false)
                .withFileFromString("Dockerfile", dockerfile)
                .withFileFromPath("app.jar", jar);
        try (GenericContainer<?> c = new GenericContainer<>(image)
                .withCommand("sh", "-c", "java -cp /app.jar "
                        + TextRenderingCheck.class.getName() + " 2>&1; echo \"exit=$?\"")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
            c.start();
            return c.getLogs();
        }
    }

    private static void failsNaming(String out, String missing) {
        assertTrue(out.contains("exit=3"), out);
        assertTrue(out.contains("Java can't render text"), out);
        assertTrue(out.contains("Missing: " + missing + "\n"), out);
        assertTrue(out.contains("apt-get install libfreetype6 fontconfig fonts-dejavu-core"), out);
        assertTrue(!out.contains("\tat "), "no stack trace: " + out);
    }

    @Test
    void withoutAnyOfThemAllThreeAreNamed() {
        failsNaming(run(FONTCONFIG + " " + FONTS + " libfreetype6"), "the FreeType library (libfreetype.so.6),"
                + " fontconfig (libfontconfig.so.1), an installed font (nothing in /usr/share/fonts)");
    }

    /** The reported case (the startup crash this check replaces). */
    @Test
    void missingFreeTypeIsNamed() {
        failsNaming(run("libfreetype6"), "the FreeType library (libfreetype.so.6)");
    }

    /** The bundled font doesn't make fontconfig optional. */
    @Test
    void missingFontconfigIsNamed() {
        failsNaming(run(FONTCONFIG), "fontconfig (libfontconfig.so.1)");
    }

    /** Nor a system font. */
    @Test
    void missingFontsAreNamed() {
        failsNaming(run(FONTS), "an installed font (nothing in /usr/share/fonts)");
    }

    @Test
    void withFreeTypeFontconfigAndAFontTextRenders() {
        String out = run("");
        assertTrue(out.contains("text rendering works") && out.contains("exit=0"), out);
    }
}
