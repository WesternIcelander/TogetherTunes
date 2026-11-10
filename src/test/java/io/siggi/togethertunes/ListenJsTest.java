package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Listen mode (docs/rooms.md#listen-mode): runs the unit tests of the sync decisions
 * ({@code src/test/resources/web-tests/listensync.test.mjs}) against the compiled
 * {@code web/js/listensync.js}, and checks that the player and the room page use what the design asks
 * for (the element's GainNode, pitch kept, the autoplay overlay, the video / audio-only crossfade), and
 * that the sync loop records its decisions for the "Send diagnostics" report
 * ({@code diagnostics.test.mjs} tests the rings).
 */
class ListenJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void listenSyncUnitTestsPass() throws Exception {
        NodeTests.run("listensync.test.mjs", Map.of("LISTENSYNC_JS", "web/js/listensync.js"));
    }

    @Test
    void diagnosticsUnitTestsPass() throws Exception {
        NodeTests.run("diagnostics.test.mjs", Map.of("DIAGNOSTICS_JS", "web/js/diagnostics.js"));
    }

    @Test
    void audioHealthUnitTestsPass() throws Exception {
        NodeTests.run("audiohealth.test.mjs", Map.of("AUDIOHEALTH_JS", "web/js/audiohealth.js"));
    }

    /**
     * The iOS stutter / mute (report dfh3l7): the output level is measured after each GainNode and
     * recorded once a second, a silent Web Audio path offers "Tap to restore sound" (rebuild without
     * Web Audio), {@code ?webaudio=0} starts without it, and a stuck seek on a routed element hands
     * the entry to the other element instead of reloading it.
     */
    @Test
    void playerMeasuresItsOutputAndCanPlayWithoutWebAudio() throws Exception {
        String player = read("web/js/listen.js");
        for (String s : List.of("from \"./audiohealth.js\"", "createAnalyser(", "getFloatTimeDomainData(", "recordHealth(",
                "new MuteDetector(", "\"webaudio-mute\"", "Tap to restore sound", "\"restore\"", "\"handoff\"",
                "getVideoPlaybackQuality", "webkitAudioDecodedByteCount", "visibilitychange", "pagehide", "\"resume\"",
                "sampleRate")) {
            assertTrue(player.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("webAudioOverride(", "get(\"webaudio\")", "webAudioOverride", "\"health\"")) {
            assertTrue(room.contains(s), s);
        }
    }

    /**
     * The iOS sample-rate mismatch (report uhmxlu): the player checks the Web Audio path
     * (WebAudioCheck), rebuilds the context at the implied rate on fresh elements, falls back to the
     * direct path in Auto, and the room page has the per-device "Web Audio" setting (Auto / On / Off)
     * with Auto's memory in localStorage and the note when the sound plays direct.
     */
    @Test
    void playerChecksTheWebAudioPathAndTheRoomHasTheSetting() throws Exception {
        String player = read("web/js/listen.js");
        for (String s : List.of("new WebAudioCheck(", "\"webaudio-check\"", "\"rebuild\"", "sampleRate }", "impliedRate",
                "\"rate-fix-start\"", "setWebAudioPlan(", "#fillReserve(", "remember")) {
            assertTrue(player.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("webAudioPlan(", "parseWebAudioMemory(", "\"togethertunes.webAudio\"",
                "\"togethertunes.webAudioAuto\"", "Sound: direct (no volume control)", "setWebAudioPlan(")) {
            assertTrue(room.contains(s), s);
        }
        String html = read("web/room.html");
        for (String s : List.of("id=\"room-webaudio\"", "value=\"auto\"", "value=\"on\"", "value=\"off\"",
                "id=\"room-webaudio-note\"", "no\n        crossfade")) {
            assertTrue(html.contains(s), s);
        }
    }

    @Test
    void syncLoopRecordsDiagnosticsAndTheRoomPageSendsThem() throws Exception {
        String player = read("web/js/listen.js");
        for (String s : List.of("from \"./diagnostics.js\"", "recordDecision(", "recordError(\"tick\"",
                "new DriftController(", "new SeekLead(", "\"playing\"", "\"seeked\"", "landed(")) {
            assertTrue(player.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("captureErrors(window", "\"/api/diagnostics\"", "syncModeFor(engineHints()", "maxTouchPoints", "mozPreservesPitch",
                "togetherTunesClock")) {
            assertTrue(room.contains(s), s);
        }
        String html = read("web/room.html");
        assertTrue(html.contains("id=\"room-diagnostics\""), html);
        assertTrue(html.contains("Send diagnostics"), html);
    }

    @Test
    void playerUsesGainNodesKeepsPitchAndChecksCanPlayType() throws Exception {
        String player = read("web/js/listen.js");
        for (String s : List.of("from \"./listensync.js\"", "createMediaElementSource(", "createGain(",
                "preservesPitch", "canPlayType(", "NotAllowedError", "playsinline", ".resume()",
                "linearRampToValueAtTime(", "SwitchGate(", "CROSSFADE_MS", "removeAttribute(\"src\")")) {
            assertTrue(player.contains(s), s);
        }
        // The end of an item: no seeks in the last second (inEndGuard); a seek that never finishes
        // reloads the element instead of seeking again (the iPod touch 7 report f552z8).
        for (String s : List.of("inEndGuard(", "\"ending\"", "\"seek-stuck\"", "\"reload\"")) {
            assertTrue(player.contains(s), s);
        }
        assertTrue(!player.contains("\"stuck\""), "a stuck seek is no longer redone as a seek");
        String room = read("web/js/room.js");
        assertTrue(room.contains("from \"./listen.js\""), room);
        assertTrue(room.contains("new ListenPlayer("), room);
        assertTrue(room.contains("setAudioOnly("), room);
        String html = read("web/room.html");
        assertTrue(html.contains("id=\"room-listen-overlay\""), html);
        assertTrue(html.contains("Click to listen"), html);
        assertTrue(html.contains("id=\"room-player\""), html);
        assertTrue(html.contains("id=\"room-audio-only\""), html);
    }
}
