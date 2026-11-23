package io.siggi.togethertunes.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * A fake yt-dlp for "Find a music video": {@code --flat-playlist -J -- ytsearch8:…} prints
 * {@link #searchJson} (and records the arguments in {@code searches}, one call per line, exit code
 * from {@code search.exit}); {@code -J … <url>} prints metadata for the video in the URL, like
 * {@code yt-dlp -J} does for an import.
 */
public final class FakeYtdlpSearch {
    public final Path script;
    private final Path dir;

    public FakeYtdlpSearch(Path dir) throws IOException {
        this.dir = Files.createDirectories(dir);
        script = dir.resolve("fake-yt-dlp");
        Files.writeString(script, "#!/bin/sh\n"
                + "for a; do last=\"$a\"; done\n"
                + "if [ \"$1\" = \"--flat-playlist\" ]; then\n"
                + "  echo \"$*\" >> '" + dir.resolve("searches") + "'\n"
                + "  cat '" + dir.resolve("search.json") + "' 2>/dev/null || echo '{\"entries\":[]}'\n"
                + "  if [ -f '" + dir.resolve("search.exit") + "' ]; then exit $(cat '" + dir.resolve("search.exit") + "'); fi\n"
                + "  exit 0\n"
                + "fi\n"
                + "id=\"${last##*v=}\"\n"
                + "echo \"{\\\"id\\\":\\\"$id\\\",\\\"title\\\":\\\"Video $id\\\",\\\"uploader\\\":\\\"Chan\\\","
                + "\\\"duration\\\":200,\\\"live_status\\\":\\\"not_live\\\",\\\"is_live\\\":false}\"\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    }

    /** What the next searches print. */
    public void searchJson(String json) throws IOException {
        Files.writeString(dir.resolve("search.json"), json, StandardCharsets.UTF_8);
    }

    /** The exit code of the next searches. */
    public void searchExit(int code) throws IOException {
        Files.writeString(dir.resolve("search.exit"), Integer.toString(code), StandardCharsets.UTF_8);
    }

    /** The searches made so far: each call's arguments, joined by spaces. */
    public List<String> searches() throws IOException {
        Path p = dir.resolve("searches");
        return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : List.of();
    }

    /**
     * A search answer with these {@code [id, title, channel, durationSec, live_status?, thumbnails?]}
     * entries ({@code thumbnails} as raw JSON; an empty live_status is left out).
     */
    public static String entries(String[]... entries) {
        StringBuilder b = new StringBuilder("{\"_type\":\"playlist\",\"id\":\"q\",\"entries\":[");
        for (int i = 0; i < entries.length; i++) {
            String[] e = entries[i];
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"_type\":\"url\",\"ie_key\":\"Youtube\",\"id\":\"").append(e[0]).append("\",\"title\":\"")
                    .append(e[1]).append("\",\"channel\":\"").append(e[2]).append("\",\"duration\":").append(e[3])
                    .append(e.length > 4 && !e[4].isEmpty() ? ",\"live_status\":\"" + e[4] + "\"" : "")
                    .append(e.length > 5 ? ",\"thumbnails\":" + e[5] : "").append('}');
        }
        return b.append("]}").toString();
    }
}
