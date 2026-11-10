package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoomViewsTest {
    private static final String ID = "01900000-0000-7000-8000-000000000001";

    private static JsonObject item(String source, String file, boolean web, Double gain) {
        RoomViews.ItemRow row = new RoomViews.ItemRow(ID, "audio", "T", null, 1000L, "ready", source, false, file, web,
                gain);
        return RoomViews.item(new RoomViews.Lookup(Map.of(ID, row), Map.of()), ID);
    }

    @Test
    void webPlayableOriginalsCarryTheirTypeForCanPlayType() {
        JsonObject o = item("upload", "original.flac", true, -3.5);
        assertEquals("/media/" + ID + "/original", o.get("originalUrl").getAsString());
        assertEquals(-3.5, o.get("originalGainDb").getAsDouble());
        assertEquals("audio/flac", o.get("originalType").getAsString());
        assertEquals("audio/mp4; codecs=\"mp4a.40.2\"", item("upload", "original.m4a", true, null)
                .get("originalType").getAsString());
        assertEquals(0.0, item("upload", "original.m4a", true, null).get("originalGainDb").getAsDouble());
        assertEquals("audio/ogg; codecs=\"opus\"", RoomViews.originalType("original.opus"));
        assertEquals("audio/webm; codecs=\"opus\"", RoomViews.originalType("original.webm"));
        assertEquals("audio/mpeg", RoomViews.originalType("original.mp3"));
        assertEquals("audio/wav", RoomViews.originalType("original.wav"));
        assertEquals("audio/aac", RoomViews.originalType("original.aac"));
        assertNull(RoomViews.originalType("original.bin"));
        assertNull(RoomViews.originalType(null));
        // An unlisted extension still offers the original (ingest said web-playable), without a type.
        JsonObject bin = item("upload", "original.bin", true, null);
        assertEquals("/media/" + ID + "/original", bin.get("originalUrl").getAsString());
        assertFalse(bin.has("originalType"));
    }

    @Test
    void mediaUrlsNameTheCurrentVersion() {
        RoomViews.ItemRow row = new RoomViews.ItemRow(ID, "video", "T", null, 1000L, "ready", "youtube", false, null,
                false, null, 4);
        JsonObject o = RoomViews.item(new RoomViews.Lookup(Map.of(ID, row), Map.of()), ID);
        assertEquals("/media/" + ID + "/v/4/mezz.mp4", o.get("videoUrl").getAsString());
        assertEquals("/media/" + ID + "/v/4/audio.m4a", o.get("audioUrl").getAsString());
        assertEquals("/media/" + ID + "/v/4/cover.jpg", o.get("coverUrl").getAsString());
    }

    /**
     * Audio-only rooms: an entry that plays audio-only gives web clients audio URLs only (no
     * {@code videoUrl}, so {@code mezz.mp4} is never requested); a video with an audio trim plays
     * its {@code audio-only.m4a} for that file's length. Normal entries are unchanged.
     */
    @Test
    void audioOnlyEntriesCarryAudioUrlsOnly() {
        RoomViews.ItemRow trimmed = new RoomViews.ItemRow(ID, "video", "T", null, 60_000L, "ready", "youtube", false,
                null, false, null, 4, 20_000L);
        String plainId = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
        RoomViews.ItemRow plain = new RoomViews.ItemRow(plainId, "video", "T", null, 45_000L, "ready", "youtube",
                false, null, false, null, 2);
        RoomViews.Lookup l = new RoomViews.Lookup(Map.of(ID, trimmed, plainId, plain), Map.of());

        JsonObject o = RoomViews.item(l, ID, true);
        assertFalse(o.has("videoUrl"));
        assertEquals("/media/" + ID + "/v/4/audio-only.m4a", o.get("audioUrl").getAsString());
        assertEquals(20_000, o.get("durationMs").getAsLong());
        o = RoomViews.item(l, plainId, true);
        assertFalse(o.has("videoUrl"));
        assertEquals("/media/" + plainId + "/v/2/audio.m4a", o.get("audioUrl").getAsString());
        assertEquals(45_000, o.get("durationMs").getAsLong());
        // the same item in a normal entry
        o = RoomViews.item(l, ID);
        assertEquals("/media/" + ID + "/v/4/mezz.mp4", o.get("videoUrl").getAsString());
        assertEquals("/media/" + ID + "/v/4/audio.m4a", o.get("audioUrl").getAsString());
        assertEquals(60_000, o.get("durationMs").getAsLong());

        QueueEntry e = new QueueEntry("e1", ID, "u", null,
                1, QueueEntry.State.PLAYING, 1, false, 1L, "ready", 60_000L, true,
                20_000L, true);
        JsonObject cur = RoomViews.current(l, e);
        assertTrue(cur.get("audioOnly").getAsBoolean());
        assertFalse(cur.getAsJsonObject("item").has("videoUrl"));
        assertFalse(RoomViews.current(l, e.withAudioOnly(false)).has("audioOnly"));
        assertTrue(RoomViews.current(l, e.withAudioOnly(false)).getAsJsonObject("item").has("videoUrl"));
    }

    @Test
    void noOriginalUnlessWebPlayableUpload() {
        assertFalse(item("upload", "original.wma", false, -1.0).has("originalUrl"));
        assertFalse(item("upload", "original.wma", false, -1.0).has("originalType"));
        assertFalse(item("youtube", "original.m4a", true, -1.0).has("originalUrl"));
    }
}
