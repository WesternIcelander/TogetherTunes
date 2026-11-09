package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Title/artist rules and the live/duration fields of {@code yt-dlp -J}. */
class YoutubeMetadataTest {
    private static YoutubeMetadata parse(String json) {
        return YoutubeMetadata.parse(json, "dQw4w9WgXcQ");
    }

    @Test
    void trackAndArtistWinOverTitleAndUploader() {
        YoutubeMetadata m = parse("{\"id\":\"dQw4w9WgXcQ\",\"title\":\"Rick Astley - Never Gonna Give You Up (Official Video)\","
                + "\"uploader\":\"Rick Astley\",\"track\":\"Never Gonna Give You Up\",\"artist\":\"Rick Astley\","
                + "\"album\":\"Whenever You Need Somebody\",\"duration\":212,\"live_status\":\"not_live\",\"is_live\":false}");
        assertEquals("dQw4w9WgXcQ", m.id());
        assertEquals("Never Gonna Give You Up", m.title());
        assertEquals("Rick Astley", m.artist());
        assertEquals("Whenever You Need Somebody", m.album());
        assertEquals(212_000L, m.durationMs());
        assertFalse(m.live());
    }

    @Test
    void titleAndUploaderOtherwise() {
        YoutubeMetadata m = parse("{\"id\":\"abcdefghijk\",\"title\":\"  My\\tvideo\\n title \",\"uploader\":\"Some Channel\","
                + "\"channel\":\"Other\",\"duration\":12.345}");
        assertEquals("abcdefghijk", m.id());
        assertEquals("My video title", m.title());
        assertEquals("Some Channel", m.artist());
        assertNull(m.album());
        assertEquals(12_345L, m.durationMs());
        // channel when there is no uploader
        assertEquals("Other", parse("{\"title\":\"t\",\"channel\":\"Other\"}").artist());
        // blank values count as missing
        assertEquals("t", parse("{\"title\":\"t\",\"track\":\"  \",\"artist\":\"\",\"uploader\":\"U\"}").title());
        assertEquals("U", parse("{\"title\":\"t\",\"track\":\"  \",\"artist\":\"\",\"uploader\":\"U\"}").artist());
    }

    @Test
    void artistsListAndTopicChannels() {
        assertEquals("A, B", parse("{\"title\":\"t\",\"artists\":[\"A\",\" B \",\"\"],\"uploader\":\"U\"}").artist());
        YoutubeMetadata topic = parse("{\"title\":\"Song\",\"uploader\":\"Some Band - Topic\"}");
        assertEquals("Some Band", topic.artist());
        assertEquals("Some Band - Topic", topic.uploader(), "the raw uploader is kept");
        assertEquals(" - Topic", YoutubeMetadata.withoutTopic(" - Topic"));
    }

    @Test
    void missingTitleFallsBackToTheId() {
        YoutubeMetadata m = parse("{}");
        assertEquals("dQw4w9WgXcQ", m.id());
        assertEquals("dQw4w9WgXcQ", m.title());
        assertNull(m.artist());
        assertNull(m.durationMs());
        assertTrue(m.durationSec() < 0);
    }

    @Test
    void longValuesAreCut() {
        YoutubeMetadata m = parse("{\"title\":\"" + "x".repeat(1000) + "\"}");
        assertEquals(UploadMetadata.MAX_CHARS, m.title().length());
    }

    @Test
    void liveStreams() {
        assertTrue(parse("{\"title\":\"t\",\"is_live\":true}").live());
        assertTrue(parse("{\"title\":\"t\",\"live_status\":\"is_live\"}").live());
        assertTrue(parse("{\"title\":\"t\",\"live_status\":\"is_upcoming\"}").live());
        assertTrue(parse("{\"title\":\"t\",\"live_status\":\"post_live\"}").live());
        assertFalse(parse("{\"title\":\"t\",\"live_status\":\"was_live\",\"is_live\":false}").live());
        assertFalse(parse("{\"title\":\"t\",\"live_status\":\"not_live\"}").live());
        assertFalse(parse("{\"title\":\"t\",\"is_live\":null}").live());
    }

    @Test
    void oddDurationsAreUnknown() {
        assertNull(parse("{\"duration\":null}").durationMs());
        assertNull(parse("{\"duration\":\"12\"}").durationMs());
        assertNull(parse("{\"duration\":-3}").durationMs());
    }

    @Test
    void itemMetadataJson() {
        assertEquals("{\"youtube\":{\"id\":\"dQw4w9WgXcQ\",\"uploader\":\"U - Topic\",\"durationSec\":3.5}}",
                parse("{\"id\":\"dQw4w9WgXcQ\",\"uploader\":\"U - Topic\",\"duration\":3.5}").toItemMetadata().toString());
    }

    @Test
    void notJson() {
        assertThrows(IllegalArgumentException.class, () -> parse("ERROR: nope"));
        assertThrows(IllegalArgumentException.class, () -> parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> parse(""));
    }
}
