package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.Tools;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The YouTube search passes yt-dlp a private copy of the cookies file and merges what yt-dlp
 * changed in it back into the shared file, also when the search fails.
 */
class YtdlpSearchCookiesTest {
    @TempDir
    Path dir;

    @Test
    void theSearchUsesAPrivateCopyAndMergesItsChanges() throws Exception {
        Path shared = Files.writeString(dir.resolve("cookies.txt"), "# Netscape HTTP Cookie File\n\n"
                + ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tOLD\t1\n"
                + ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tKEEP\tk\n");
        Path argsFile = dir.resolve("args.txt");
        Path exitFile = dir.resolve("exit");
        Path script = dir.resolve("fake-yt-dlp");
        // like yt-dlp: rewrites its whole jar (OLD refreshed, NEW added) in the file it was given
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + argsFile + "'\n"
                + "for a; do if [ \"$prev\" = --cookies ]; then"
                + " awk 'BEGIN{FS=OFS=\"\\t\"} $6==\"OLD\"{$7=\"2\"} {print}' \"$a\" > \"$a.new\" && mv \"$a.new\" \"$a\";"
                + " printf '.youtube.com\\tTRUE\\t/\\tTRUE\\t0\\tNEW\\tn\\n' >> \"$a\"; fi; prev=\"$a\"; done\n"
                + "echo '{\"entries\":[]}'\n"
                + "if [ -f '" + exitFile + "' ]; then exit 1; fi\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        Tools tools = new Tools("ffmpeg", "ffprobe", script.toString());

        assertEquals(List.of(), VideoSearch.ytdlpSearch(tools, shared::toString, "artist title", 8));
        List<String> args = Files.readAllLines(argsFile);
        Path privateCopy = Path.of(args.get(args.indexOf("--cookies") + 1));
        assertNotEquals(shared, privateCopy);
        assertFalse(Files.exists(privateCopy), "the private copy is deleted");
        assertEquals("# Netscape HTTP Cookie File\n\n"
                + ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tOLD\t2\n"
                + ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tKEEP\tk\n"
                + ".youtube.com\tTRUE\t/\tTRUE\t0\tNEW\tn\n", Files.readString(shared));

        // a failed search still merges its changes
        Files.writeString(exitFile, "1");
        Files.writeString(shared, "# Netscape HTTP Cookie File\n\n.youtube.com\tTRUE\t/\tTRUE\t1900000000\tOLD\t1\n");
        assertThrows(HttpError.class, () -> VideoSearch.ytdlpSearch(tools, shared::toString, "artist title", 8));
        assertTrue(Files.readString(shared).contains("\tOLD\t2\n"), Files.readString(shared));
        assertTrue(Files.readString(shared).contains("\tNEW\tn\n"), Files.readString(shared));
        args = Files.readAllLines(argsFile);
        assertFalse(Files.exists(Path.of(args.get(args.indexOf("--cookies") + 1))));
    }
}
