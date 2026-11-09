package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.MediaProbe;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UploadMetadataTest {
    private static MediaProbe probe(Map<String, String> tags) {
        return new MediaProbe("mp3", 0, 1_000_000, new MediaProbe.Stream(0, "mp3", 0, 1_000_000), null, null, tags);
    }

    @Test
    void tagsThenFileName() {
        UploadMetadata m = UploadMetadata.of(probe(Map.of("title", "Song", "artist", "Band", "album", "Record")),
                "file.mp3", null, null);
        assertEquals(new UploadMetadata("Song", "Band", "Record"), m);

        m = UploadMetadata.of(probe(Map.of()), "/home/me/Some_Track.v2.flac", null, null);
        assertEquals(new UploadMetadata("Some Track.v2", null, null), m);
        assertEquals("Track", UploadMetadata.of(probe(Map.of()), "C:\\Music\\Track.mp3", null, null).title());
        assertEquals(".mp3", UploadMetadata.of(probe(Map.of()), ".mp3", null, null).title());
        assertEquals("Untitled", UploadMetadata.of(probe(Map.of("title", "  ")), " ", null, null).title());
    }

    @Test
    void artistFallsBackToAlbumArtist() {
        assertEquals("Various", UploadMetadata.of(probe(Map.of("album_artist", "Various")), "a.mp3", null, null).artist());
        assertEquals("Band", UploadMetadata.of(probe(Map.of("album_artist", "Various", "artist", "Band")), "a.mp3",
                null, null).artist());
    }

    @Test
    void uploaderWins() {
        UploadMetadata m = UploadMetadata.of(probe(Map.of("title", "Song", "artist", "Band", "album", "Record")),
                "file.mp3", "Mine", "Me");
        assertEquals(new UploadMetadata("Mine", "Me", "Record"), m);
        // a blank override falls back
        assertEquals("Song", UploadMetadata.of(probe(Map.of("title", "Song")), "f.mp3", " \n", null).title());
    }

    @Test
    void cleaning() {
        assertEquals("a b c", UploadMetadata.clean(" a\n\tb\u0000\u2028  c "));
        assertNull(UploadMetadata.clean("\u200b\ufeff "));
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDCBB x", UploadMetadata.clean("\ufeff\uD83D\uDC69\u200D\uD83D\uDCBB x"),
                "zero-width joiner kept, BOM dropped");
        String longText = "\uD83C\uDFB5".repeat(UploadMetadata.MAX_CHARS + 10);
        String cut = UploadMetadata.clean(longText);
        assertEquals(UploadMetadata.MAX_CHARS, cut.codePointCount(0, cut.length()), "cut by code points, no split pairs");
    }

    @Test
    void extensions() {
        assertEquals("mp3", UploadMetadata.extension("Song.MP3"));
        assertEquals("webm", UploadMetadata.extension("dir.x/clip.webm"));
        assertNull(UploadMetadata.extension("noext"));
        assertNull(UploadMetadata.extension("trailing."));
        assertNull(UploadMetadata.extension("bad.ex-t"));
        assertNull(UploadMetadata.extension("long.abcdefghijk"));
        assertNull(UploadMetadata.extension("dir.x/noext"));
    }

    @Test
    void probeTagsAreLowerCasedContainerFirst() throws Exception {
        String json = """
                {"format": {"format_name": "ogg", "duration": "1.0", "tags": {"TITLE": "Container", "Genre": "x"}},
                 "streams": [{"index": 0, "codec_type": "audio", "codec_name": "opus",
                              "tags": {"title": "Stream", "ARTIST": "Stream Artist"}}]}
                """;
        var parse = MediaProbe.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        MediaProbe p = (MediaProbe) parse.invoke(null, json);
        assertEquals(Map.of("title", "Container", "genre", "x", "artist", "Stream Artist"), p.tags());
        assertEquals("Stream Artist", p.tag("artist"));
    }

    @Test
    void contentRange() {
        assertEquals(new Uploads.ChunkRange(0, 99), Uploads.parseRange("bytes 0-99/1000", 1000));
        assertEquals(new Uploads.ChunkRange(900, 999), Uploads.parseRange("Bytes 900-999/*", 1000));
        assertEquals(100, Uploads.parseRange(" bytes 0-99/1000 ", 1000).length());
        for (String bad : new String[] {null, "", "bytes */1000", "bytes 0-99/999", "bytes 5-4/1000", "bytes 0-1000/1000",
                "bytes -5/1000", "bytes 0-99"}) {
            HttpError e = assertThrows(HttpError.class, () -> Uploads.parseRange(bad, 1000), String.valueOf(bad));
            assertEquals("invalid_content_range", e.code());
        }
    }
}
