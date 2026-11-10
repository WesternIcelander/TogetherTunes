package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link YoutubeLookupStage}: yt-dlp's progress lines to stages, unknown lines ignored, never backwards. */
class YoutubeLookupStageTest {
    @Test
    void realLinesMapToStages() {
        // as yt-dlp 2026.10 prints them with -J --verbose (Siggi's session timed one, 2026-11-10)
        assertEquals(YoutubeLookupStage.CONTACTING,
                YoutubeLookupStage.of("[youtube] Extracting URL: https://www.youtube.com/watch?v=jNQXAC9IVRw"));
        assertEquals(YoutubeLookupStage.PAGE, YoutubeLookupStage.of("[youtube] jNQXAC9IVRw: Downloading webpage"));
        assertEquals(YoutubeLookupStage.DETAILS,
                YoutubeLookupStage.of("[youtube] jNQXAC9IVRw: Downloading android vr player API JSON"));
        assertEquals(YoutubeLookupStage.DETAILS,
                YoutubeLookupStage.of("[youtube] jNQXAC9IVRw: Downloading visionos player API JSON"));
        assertEquals(YoutubeLookupStage.DETAILS, YoutubeLookupStage.of("[youtube] jNQXAC9IVRw: Downloading tv client config"));
        assertEquals(YoutubeLookupStage.DETAILS, YoutubeLookupStage.of("[youtube] jNQXAC9IVRw: Downloading player a5c2aafe-main"));
        assertEquals(YoutubeLookupStage.FORMATS,
                YoutubeLookupStage.of("[youtube] jNQXAC9IVRw: Downloading m3u8 information"));
        assertEquals(YoutubeLookupStage.FORMATS,
                YoutubeLookupStage.of("[info] jNQXAC9IVRw: Downloading 1 format(s): 395+251"));
        assertEquals("Contacting YouTube…", YoutubeLookupStage.CONTACTING.text());
        assertEquals("Reading the video page…", YoutubeLookupStage.PAGE.text());
        assertEquals("Getting the video details…", YoutubeLookupStage.DETAILS.text());
        assertEquals("Checking formats…", YoutubeLookupStage.FORMATS.text());
    }

    @Test
    void otherLinesAreIgnored() {
        for (String line : new String[] {null, "", "   ", "[debug] Command-line config: ['-J', '--verbose']",
                "[debug] [youtube] Downloading webpage", "WARNING: [youtube] No supported JavaScript runtime",
                "ERROR: [youtube] jNQXAC9IVRw: Video unavailable", "        original url = /s/player/base.js",
                "{\"id\": \"jNQXAC9IVRw\"}", "[youtube] jNQXAC9IVRw: Something new entirely",
                "Traceback (most recent call last):"}) {
            assertNull(YoutubeLookupStage.of(line), line);
        }
    }

    @Test
    void theTrackerReportsEachStageOnceAndNeverBackwards() {
        List<YoutubeLookupStage> seen = new ArrayList<>();
        YoutubeLookupStage.Tracker t = new YoutubeLookupStage.Tracker(seen::add);
        t.line("[youtube] jNQXAC9IVRw: Downloading webpage");
        t.line("[youtube] Extracting URL: https://www.youtube.com/watch?v=jNQXAC9IVRw"); // late: ignored
        t.line("[youtube] jNQXAC9IVRw: Downloading webpage"); // again: ignored
        t.line("nonsense");
        t.line("[youtube] jNQXAC9IVRw: Downloading m3u8 information");
        t.line("[youtube] jNQXAC9IVRw: Downloading android vr player API JSON"); // backwards: ignored
        assertEquals(List.of(YoutubeLookupStage.PAGE, YoutubeLookupStage.FORMATS), seen);

        YoutubeLookupStage.Tracker throwing = new YoutubeLookupStage.Tracker(s -> {
            throw new IllegalStateException("gone");
        });
        throwing.line("[youtube] jNQXAC9IVRw: Downloading webpage"); // doesn't throw
    }
}
