package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps the Docker image's {@code Dockerfile} and {@code .dockerignore} in step with the build and
 * the config: the jar it copies is the one {@code mvn package} makes, the tool and data dir
 * variables are the ones {@link ConfigLoader} reads, and ffmpeg stays pinned to a version from the
 * skip-frame card version matrix. (The image itself was checked by hand: see PLAN.md, M7.)
 */
class DockerfileTest {
    private static String read(String file) throws Exception {
        return Files.readString(Path.of(file));
    }

    private static String jarPath() throws Exception {
        String pom = read("pom.xml");
        Matcher m = Pattern.compile("<artifactId>([^<]+)</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertTrue(m.find(), "project artifactId/version in pom.xml");
        return "target/" + m.group(1) + "-" + m.group(2) + ".jar";
    }

    @Test
    void copiesTheShadedJarTheBuildMakes() throws Exception {
        String jar = jarPath();
        assertTrue(read("Dockerfile").contains("\nCOPY " + jar + " "), "Dockerfile copies " + jar);
        List<String> ignore = read(".dockerignore").lines().map(String::strip).toList();
        assertTrue(ignore.contains("*") && ignore.contains("!" + jar), ".dockerignore lets only " + jar + " in");
    }

    @Test
    void setsTheToolsAndDataDirByTheirConfigVariables() throws Exception {
        String dockerfile = read("Dockerfile");
        for (String[] kv : new String[][] {
                {ConfigLoader.DATA_DIR, "/data"},
                {ConfigLoader.TOOLS_FFMPEG, "/usr/bin/ffmpeg"},
                {ConfigLoader.TOOLS_FFPROBE, "/usr/bin/ffprobe"},
                {ConfigLoader.TOOLS_YTDLP, "/opt/yt-dlp/yt-dlp"}}) {
            String line = ConfigLoader.envName(kv[0]) + "=" + kv[1];
            assertTrue(dockerfile.contains(line), "Dockerfile sets " + line);
        }
    }

    /** 6.1.1 (Ubuntu 24.04) is in the skip-frame card version matrix (PLAN.md M5). */
    @Test
    void pinsFfmpegFromTheVersionMatrix() throws Exception {
        String dockerfile = read("Dockerfile");
        Matcher base = Pattern.compile("(?m)^FROM (\\S+)$").matcher(dockerfile);
        assertTrue(base.find());
        assertEquals("eclipse-temurin:21-jre-noble", base.group(1));
        Matcher ffmpeg = Pattern.compile("(?m)^ARG FFMPEG_VERSION=(\\S+)$").matcher(dockerfile);
        assertTrue(ffmpeg.find(), "ffmpeg version pinned");
        assertTrue(ffmpeg.group(1).startsWith("7:6.1.1-"), ffmpeg.group(1));
        assertTrue(dockerfile.contains("\"ffmpeg=${FFMPEG_VERSION}\""));
        for (String pkg : List.of("libfreetype6", "fontconfig", "fonts-dejavu-core")) {
            assertTrue(dockerfile.contains(pkg), "installs " + pkg + " for the card renderer");
        }
    }
}
