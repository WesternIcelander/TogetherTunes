package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void noOriginalUnlessWebPlayableUpload() {
        assertFalse(item("upload", "original.wma", false, -1.0).has("originalUrl"));
        assertFalse(item("upload", "original.wma", false, -1.0).has("originalType"));
        assertFalse(item("youtube", "original.m4a", true, -1.0).has("originalUrl"));
    }
}
