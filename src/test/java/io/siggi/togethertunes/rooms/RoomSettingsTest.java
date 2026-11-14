package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link RoomSettings}: defaults, lenient reading, strict merge patches, storage form. */
class RoomSettingsTest {
    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static HttpError patchError(String json) {
        return assertThrows(HttpError.class, () -> RoomSettings.DEFAULTS.patch(obj(json)));
    }

    @Test
    void defaultsMatchTheDocs() {
        RoomSettings d = RoomSettings.DEFAULTS;
        assertTrue(d.libraryRequests());
        assertTrue(d.uploadRequests());
        assertEquals(ControlSettings.DEFAULTS, d.control());
        assertEquals(3, d.control().requestsPerActor());
        assertFalse(d.control().allowDownvotes());
        assertEquals(0.5, d.control().voteSkipThreshold());
        assertEquals(10, d.control().noRepeatWindow());
        assertFalse(d.transitionCard());
        assertEquals(0, d.gapMs());
        assertFalse(d.audioOnly());
        assertTrue(d.guestsCanVote());
        assertTrue(d.guestsCanRequestLibrary());
        assertFalse(d.guestsCanUpload());
        assertFalse(d.guestsCanYoutube());
        assertFalse(d.overlayEnabled());
        assertEquals(RoomSettings.OverlayMode.OFF, d.overlayMode());
        assertEquals("br", d.overlayCorner());
        assertEquals(22, d.overlayHeightPct());
        assertEquals(3, d.overlayMargin());
        assertNull(d.rtmpPlaybackUrl());
        assertFalse(d.tsJoinLatest());
        assertTrue(d.autoOff());
        assertEquals(10, d.autoOffMinutes());
        assertEquals(5000, d.estimatedDelayMsRtmp());
        assertEquals(2000, d.estimatedDelayMsTs());
        assertEquals(8000, d.estimatedDelayMsHls());
        assertEquals(3000, d.estimatedDelayMsMp3());
        assertEquals("{}", d.toStoredJson());
        assertEquals(d, RoomSettings.parse("{}"));
        assertEquals(d, RoomSettings.parse(null));
        assertEquals(d, RoomSettings.parse("not json"));
        assertEquals(d, RoomSettings.parse("[1]"));
    }

    /** {@code audioOnly}: a boolean; it changes nothing else (remote mode's delays stay as they are). */
    @Test
    void transitionGapIsAtLeastOneCardWhileTheCardIsOn() {
        assertEquals(0, RoomSettings.DEFAULTS.transitionGapMs());
        assertEquals(1500, RoomSettings.DEFAULTS.patch(obj("{\"gapMs\":1500}")).transitionGapMs());
        assertEquals(2000, RoomSettings.DEFAULTS.patch(obj("{\"transitionCard\":true}")).transitionGapMs());
        assertEquals(2000, RoomSettings.DEFAULTS.patch(obj("{\"transitionCard\":true,\"gapMs\":500}")).transitionGapMs());
        assertEquals(7000, RoomSettings.DEFAULTS.patch(obj("{\"transitionCard\":true,\"gapMs\":7000}")).transitionGapMs());
    }

    /** {@code requestsOnly} (forced requests-only mode): a boolean, off by default. */
    @Test
    void requestsOnlySetting() {
        assertFalse(RoomSettings.DEFAULTS.requestsOnly());
        RoomSettings on = RoomSettings.DEFAULTS.patch(obj("{\"requestsOnly\":true}"));
        assertTrue(on.requestsOnly());
        assertEquals("{\"requestsOnly\":true}", on.toStoredJson());
        assertEquals(java.util.List.of("requestsOnly"), on.changedFrom(RoomSettings.DEFAULTS));
        assertFalse(on.patch(obj("{\"requestsOnly\":null}")).requestsOnly());
        assertEquals("invalid_setting", patchError("{\"requestsOnly\":\"yes\"}").code());
        assertFalse(RoomSettings.parse("{\"requestsOnly\":1}").requestsOnly());
    }

    @Test
    void audioOnlySetting() {
        RoomSettings on = RoomSettings.DEFAULTS.patch(obj("{\"audioOnly\":true}"));
        assertTrue(on.audioOnly());
        assertEquals("{\"audioOnly\":true}", on.toStoredJson());
        assertEquals(java.util.List.of("audioOnly"), on.changedFrom(RoomSettings.DEFAULTS));
        assertEquals(5000, on.estimatedDelayMsRtmp());
        assertEquals(2000, on.estimatedDelayMsTs());
        assertEquals(8000, on.estimatedDelayMsHls());
        assertEquals("invalid_setting", patchError("{\"audioOnly\":1}").code());
        assertFalse(RoomSettings.parse("{\"audioOnly\":\"yes\"}").audioOnly());
    }

    @Test
    void wireFormIsNestedAndComplete() {
        JsonObject j = RoomSettings.DEFAULTS.toJson();
        assertEquals(obj("{\"libraryRequests\":true,\"uploadRequests\":true,\"requestsPerActor\":3,\"allowDownvotes\":false,"
                + "\"voteSkipThreshold\":0.5,\"noRepeatWindow\":10,\"transitionCard\":false,\"gapMs\":0,"
                + "\"audioOnly\":false,\"requestsOnly\":false,\"guests\":{\"canVote\":true,\"canRequestLibrary\":true,\"canUpload\":false,\"canYoutube\":false},"
                + "\"overlay\":{\"mode\":\"off\",\"corner\":\"br\",\"heightPct\":22,\"margin\":3},"
                + "\"outputs\":{\"rtmp\":{\"playbackUrl\":\"\"},\"autoOff\":true,\"autoOffMinutes\":10,\"ts\":{\"join\":\"next\"}},"
                + "\"autoplay\":{\"selectors\":\"owner\",\"upcoming\":5,\"mix\":\"random\",\"recentPct\":50,"
                + "\"requestsFirst\":\"first\"},"
                + "\"estimatedDelayMs\":{\"rtmp\":5000,\"ts\":2000,\"hls\":8000,\"mp3\":3000}}"), j);
        assertEquals(j, JsonParser.parseString(Json.GSON.toJson(j)), "nothing left out by the serialiser");
        assertEquals(RoomSettings.keys().size(), 31);
    }

    @Test
    void autoOffIsABooleanAndItsMinutesAWholeNumberFrom1To1440() {
        assertEquals(1, RoomSettings.DEFAULTS.patch(obj("{\"outputs\":{\"autoOffMinutes\":1}}")).autoOffMinutes());
        assertEquals(1440, RoomSettings.DEFAULTS.patch(obj("{\"outputs\":{\"autoOffMinutes\":1440}}"))
                .autoOffMinutes());
        for (String bad : List.of("0", "1441", "2.5", "\"10\"", "true")) {
            HttpError e = assertThrows(HttpError.class, () -> RoomSettings.DEFAULTS.patch(
                    obj("{\"outputs\":{\"autoOffMinutes\":" + bad + "}}")), bad);
            assertEquals("invalid_setting", e.code(), bad);
        }
        assertEquals(10, RoomSettings.parse("{\"outputs\":{\"autoOffMinutes\":0}}").autoOffMinutes());
        RoomSettings alwaysOn = RoomSettings.DEFAULTS.patch(obj("{\"outputs\":{\"autoOff\":false}}"));
        assertFalse(alwaysOn.autoOff());
        assertEquals("{\"outputs\":{\"autoOff\":false}}", alwaysOn.toStoredJson());
        assertEquals("invalid_setting", patchError("{\"outputs\":{\"autoOff\":1}}").code());
        // the old RTMP-only name is gone (migration 0017 moved stored values)
        assertEquals("unknown_setting", patchError("{\"outputs\":{\"rtmp\":{\"idleStopMinutes\":5}}}").code());
    }

    @Test
    void storedJsonReadsLeniently() {
        RoomSettings s = RoomSettings.parse("{\"requestScope\":\"none\",\"libraryRequests\":false,\"allowDownvotes\":\"yes\","
                + "\"gapMs\":20000,\"guests\":{\"canYoutube\":true,\"canFly\":true},\"overlay\":5,"
                + "\"outputs\":{\"ts\":{\"join\":\"latest\"},\"rtmp\":{\"playbackUrl\":\"javascript:alert(1)\"}},"
                + "\"futureSetting\":1}");
        assertFalse(s.libraryRequests());
        assertFalse(s.control().allowDownvotes(), "invalid -> default");
        assertEquals(0, s.gapMs(), "out of range -> default");
        assertTrue(s.guestsCanYoutube());
        assertTrue(s.tsJoinLatest());
        assertNull(s.rtmpPlaybackUrl());
        // the removed requestScope is dropped like any unknown name
        // uploadRequests (default on) reads as off while libraryRequests is off
        assertFalse(s.uploadRequests());
        assertEquals(obj("{\"libraryRequests\":false,\"uploadRequests\":false,\"guests\":{\"canYoutube\":true},"
                + "\"outputs\":{\"ts\":{\"join\":\"latest\"}}}"), obj(s.toStoredJson()));
        assertEquals(s, RoomSettings.parse(s.toStoredJson()), "round trip");
    }

    @Test
    void patchMergesAndResets() {
        RoomSettings s = RoomSettings.DEFAULTS.patch(obj("{\"voteSkipThreshold\":\"off\",\"allowDownvotes\":true,"
                + "\"guests\":{\"canUpload\":true,\"canVote\":false},\"estimatedDelayMs\":{\"hls\":12000},"
                + "\"outputs\":{\"rtmp\":{\"playbackUrl\":\"  https://stream.example/live/x.live.ts \"}}}"));
        assertNull(s.control().voteSkipThreshold());
        assertTrue(s.control().allowDownvotes());
        assertTrue(s.guestsCanUpload());
        assertFalse(s.guestsCanVote());
        assertEquals(12000, s.estimatedDelayMsHls());
        assertEquals(2000, s.estimatedDelayMsTs());
        assertEquals("https://stream.example/live/x.live.ts", s.rtmpPlaybackUrl(), "trimmed");

        RoomSettings t = s.patch(obj("{\"guests\":{\"canVote\":null},\"voteSkipThreshold\":0.25,"
                + "\"estimatedDelayMs\":null,\"outputs\":{\"rtmp\":{\"playbackUrl\":\"\"}}}"));
        assertTrue(t.guestsCanVote(), "null resets one setting");
        assertTrue(t.guestsCanUpload(), "siblings untouched");
        assertEquals(0.25, t.control().voteSkipThreshold());
        assertEquals(8000, t.estimatedDelayMsHls(), "null resets a group");
        assertNull(t.rtmpPlaybackUrl(), "empty URL = none");
        assertEquals(List.of("voteSkipThreshold", "guests.canVote", "outputs.rtmp.playbackUrl",
                "estimatedDelayMs.hls"), t.changedFrom(s));
        assertEquals(s, s.patch(obj("{}")));
        assertEquals(RoomSettings.DEFAULTS, RoomSettings.DEFAULTS.patch(obj("{\"requestsPerActor\":3}")));
        assertEquals(1, RoomSettings.DEFAULTS.patch(obj("{\"voteSkipThreshold\":1}")).control().voteSkipThreshold());
        assertEquals(1000, RoomSettings.DEFAULTS.patch(obj("{\"requestsPerActor\":1000.0}")).control().requestsPerActor());
    }

    /** Uploads need library requests: the companion change on a patch, and older rows read as upload off. */
    @Test
    void uploadsNeedLibraryRequests() {
        // turning library requests off turns uploads off too
        RoomSettings.Patched p = RoomSettings.DEFAULTS.patchAdjusting(obj("{\"libraryRequests\":false}"));
        assertFalse(p.settings().libraryRequests());
        assertFalse(p.settings().uploadRequests());
        assertEquals(List.of("uploadRequests"), p.adjusted());
        // turning uploads on while library requests are off turns them on too
        RoomSettings.Patched q = p.settings().patchAdjusting(obj("{\"uploadRequests\":true}"));
        assertTrue(q.settings().libraryRequests());
        assertTrue(q.settings().uploadRequests());
        assertEquals(List.of("libraryRequests"), q.adjusted());
        // turning library requests back on leaves uploads as they are
        RoomSettings.Patched r = p.settings().patchAdjusting(obj("{\"libraryRequests\":true}"));
        assertFalse(r.settings().uploadRequests());
        assertEquals(List.of(), r.adjusted());
        // both switches turned the wrong way at once
        HttpError e = assertThrows(HttpError.class, () -> RoomSettings.DEFAULTS.patch(obj(
                "{\"uploadRequests\":false}")).patchAdjusting(obj("{\"uploadRequests\":true,\"libraryRequests\":false}")));
        assertEquals("invalid_setting", e.code());
        // both in a consistent way: no adjustment
        assertEquals(List.of(), RoomSettings.DEFAULTS.patchAdjusting(
                obj("{\"uploadRequests\":false,\"libraryRequests\":false}")).adjusted());

        // the same for the guest switches
        RoomSettings g = RoomSettings.DEFAULTS.patch(obj("{\"guests\":{\"canRequestLibrary\":false}}"));
        RoomSettings.Patched gp = g.patchAdjusting(obj("{\"guests\":{\"canUpload\":true}}"));
        assertTrue(gp.settings().guestsCanUpload());
        assertTrue(gp.settings().guestsCanRequestLibrary());
        assertEquals(List.of("guests.canRequestLibrary"), gp.adjusted());
        RoomSettings.Patched gq = gp.settings().patchAdjusting(obj("{\"guests\":{\"canRequestLibrary\":false}}"));
        assertFalse(gq.settings().guestsCanUpload());
        assertEquals(List.of("guests.canUpload"), gq.adjusted());
        // a reset of the library switch counts like any change
        RoomSettings.Patched both = RoomSettings.DEFAULTS.patch(obj("{\"guests\":{\"canUpload\":true}}"))
                .patchAdjusting(obj("{\"libraryRequests\":false,\"guests\":null}"));
        assertEquals(List.of("uploadRequests"), both.adjusted());
        assertFalse(both.settings().guestsCanUpload(), "the group reset turned guest uploads back off");

        // stored rows that break the rule read as upload off
        RoomSettings old = RoomSettings.parse("{\"libraryRequests\":false,\"uploadRequests\":true,"
                + "\"guests\":{\"canRequestLibrary\":false,\"canUpload\":true}}");
        assertFalse(old.uploadRequests());
        assertFalse(old.guestsCanUpload());
        assertFalse(old.toJson().get("uploadRequests").getAsBoolean(), "clients see it off too");
    }

    @Test
    void patchIsStrict() {
        assertEquals("unknown_setting", patchError("{\"nope\":1}").code());
        assertEquals("unknown_setting", patchError("{\"guests\":{\"canFly\":true}}").code());
        assertEquals("unknown_setting", patchError("{\"guests.canVote\":true}").code());
        assertEquals("invalid_setting", patchError("{\"guests\":true}").code());
        assertEquals("unknown_setting", patchError("{\"requestScope\":\"owner\"}").code(), "removed");
        assertEquals("invalid_setting", patchError("{\"libraryRequests\":\"yes\"}").code());
        assertEquals("invalid_setting", patchError("{\"requestsPerActor\":0}").code());
        assertEquals("invalid_setting", patchError("{\"requestsPerActor\":2.5}").code());
        assertEquals("invalid_setting", patchError("{\"requestsPerActor\":\"3\"}").code());
        assertEquals("invalid_setting", patchError("{\"noRepeatWindow\":1001}").code());
        assertEquals("invalid_setting", patchError("{\"voteSkipThreshold\":0}").code());
        assertEquals("invalid_setting", patchError("{\"voteSkipThreshold\":1.01}").code());
        assertEquals("invalid_setting", patchError("{\"voteSkipThreshold\":\"on\"}").code());
        assertEquals("invalid_setting", patchError("{\"allowDownvotes\":1}").code());
        assertEquals("invalid_setting", patchError("{\"gapMs\":10001}").code());
        assertEquals("invalid_setting", patchError("{\"overlay\":{\"corner\":\"middle\"}}").code());
        assertEquals("invalid_setting", patchError("{\"overlay\":{\"heightPct\":4}}").code());
        assertEquals("invalid_setting", patchError("{\"overlay\":{\"heightPct\":51}}").code());
        assertEquals("invalid_setting", patchError("{\"overlay\":{\"margin\":21}}").code());
        assertEquals("invalid_setting", patchError("{\"overlay\":{\"mode\":\"sometimes\"}}").code());
        assertEquals("invalid_setting", patchError("{\"overlay\":{\"mode\":true}}").code());
        // The old room switch is gone (migration 0025 moved it to overlay.mode).
        assertEquals("unknown_setting", patchError("{\"overlay\":{\"enabled\":true}}").code());
        assertEquals("invalid_setting", patchError("{\"outputs\":{\"ts\":{\"join\":\"first\"}}}").code());
        assertEquals("invalid_setting", patchError("{\"outputs\":{\"rtmp\":{\"playbackUrl\":\"javascript:x\"}}}").code());
        assertEquals("invalid_setting", patchError("{\"outputs\":{\"rtmp\":{\"playbackUrl\":\"https://\"}}}").code());
        assertEquals("invalid_setting", patchError("{\"outputs\":{\"rtmp\":{\"playbackUrl\":\"https://x.example/"
                + "a".repeat(2048) + "\"}}}").code());
        assertEquals("invalid_setting", patchError("{\"estimatedDelayMs\":{\"ts\":-1}}").code());
        HttpError e = patchError("{\"overlay\":{\"heightPct\":99}}");
        assertEquals(400, e.status());
        assertTrue(e.getMessage().startsWith("overlay.heightPct: "), e.getMessage());
    }

    @Test
    void overlayModes() {
        for (RoomSettings.OverlayMode m : RoomSettings.OverlayMode.values()) {
            RoomSettings s = RoomSettings.DEFAULTS.patch(JsonParser.parseString("{\"overlay\":{\"mode\":\""
                    + m.wire() + "\"}}").getAsJsonObject());
            assertEquals(m, s.overlayMode());
            assertEquals(m != RoomSettings.OverlayMode.OFF, s.overlayEnabled());
            assertEquals(s, RoomSettings.parse(s.toStoredJson()));
        }
        assertEquals("idleOnly", RoomSettings.OverlayMode.IDLE_ONLY.wire());
        // Stored leniently: an unknown mode (or the old switch) reads as off.
        assertEquals(RoomSettings.OverlayMode.OFF, RoomSettings.parse("{\"overlay\":{\"mode\":\"sparkles\"}}").overlayMode());
        assertEquals(RoomSettings.OverlayMode.OFF, RoomSettings.parse("{\"overlay\":{\"enabled\":true}}").overlayMode());
    }
}
