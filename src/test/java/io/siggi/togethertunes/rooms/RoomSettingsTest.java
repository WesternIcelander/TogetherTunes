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
        assertEquals(ControlSettings.DEFAULTS, d.control());
        assertEquals(3, d.control().requestsPerActor());
        assertFalse(d.control().allowDownvotes());
        assertEquals(0.5, d.control().voteSkipThreshold());
        assertEquals(10, d.control().noRepeatWindow());
        assertFalse(d.transitionCard());
        assertEquals(0, d.gapMs());
        assertTrue(d.guestsCanVote());
        assertTrue(d.guestsCanRequestLibrary());
        assertFalse(d.guestsCanUpload());
        assertFalse(d.guestsCanYoutube());
        assertFalse(d.overlayEnabled());
        assertEquals("br", d.overlayCorner());
        assertEquals(22, d.overlayHeightPct());
        assertEquals(3, d.overlayMargin());
        assertNull(d.rtmpPlaybackUrl());
        assertFalse(d.tsJoinLatest());
        assertEquals(10, d.rtmpIdleStopMinutes());
        assertEquals(5000, d.estimatedDelayMsRtmp());
        assertEquals(2000, d.estimatedDelayMsTs());
        assertEquals(8000, d.estimatedDelayMsHls());
        assertEquals("{}", d.toStoredJson());
        assertEquals(d, RoomSettings.parse("{}"));
        assertEquals(d, RoomSettings.parse(null));
        assertEquals(d, RoomSettings.parse("not json"));
        assertEquals(d, RoomSettings.parse("[1]"));
    }

    @Test
    void wireFormIsNestedAndComplete() {
        JsonObject j = RoomSettings.DEFAULTS.toJson();
        assertEquals(obj("{\"libraryRequests\":true,\"requestsPerActor\":3,\"allowDownvotes\":false,"
                + "\"voteSkipThreshold\":0.5,\"noRepeatWindow\":10,\"transitionCard\":false,\"gapMs\":0,"
                + "\"guests\":{\"canVote\":true,\"canRequestLibrary\":true,\"canUpload\":false,\"canYoutube\":false},"
                + "\"overlay\":{\"enabled\":false,\"corner\":\"br\",\"heightPct\":22,\"margin\":3},"
                + "\"outputs\":{\"rtmp\":{\"idleStopMinutes\":10,\"playbackUrl\":\"\"},\"ts\":{\"join\":\"next\"}},"
                + "\"estimatedDelayMs\":{\"rtmp\":5000,\"ts\":2000,\"hls\":8000}}"), j);
        assertEquals(j, JsonParser.parseString(Json.GSON.toJson(j)), "nothing left out by the serialiser");
        assertEquals(RoomSettings.keys().size(), 21);
    }

    @Test
    void rtmpIdleStopMinutesIsAWholeNumberFrom1To1440() {
        assertEquals(1, RoomSettings.DEFAULTS.patch(obj("{\"outputs\":{\"rtmp\":{\"idleStopMinutes\":1}}}"))
                .rtmpIdleStopMinutes());
        assertEquals(1440, RoomSettings.DEFAULTS.patch(obj("{\"outputs\":{\"rtmp\":{\"idleStopMinutes\":1440}}}"))
                .rtmpIdleStopMinutes());
        for (String bad : List.of("0", "1441", "2.5", "\"10\"", "true")) {
            HttpError e = assertThrows(HttpError.class, () -> RoomSettings.DEFAULTS.patch(
                    obj("{\"outputs\":{\"rtmp\":{\"idleStopMinutes\":" + bad + "}}}")), bad);
            assertEquals("invalid_setting", e.code(), bad);
        }
        assertEquals(10, RoomSettings.parse("{\"outputs\":{\"rtmp\":{\"idleStopMinutes\":0}}}").rtmpIdleStopMinutes());
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
        assertEquals(obj("{\"libraryRequests\":false,\"guests\":{\"canYoutube\":true},"
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
}
