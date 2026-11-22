package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.MediaProbe;
import java.util.List;
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

    private static SongDetails details(Map<String, String> tags) {
        return UploadMetadata.of(probe(tags), "a.mp3", null, null).details();
    }

    /** docs/library-and-ingest.md#song-details: what each tag becomes, and what is dropped. */
    @Test
    void songDetailsFromTags() {
        assertEquals(SongDetails.EMPTY, details(Map.of("title", "x")));
        assertEquals(new SongDetails(List.of("A", "B C"), "AA", 1, 2, 3, 4, "JPVI02201530", "2022-06-29", "Pop",
                        "\u2117 2010 X", "Lab", "4573556731873"),
                details(Map.ofEntries(Map.entry("artists", " A ;B\tC;;A"), Map.entry("album_artist", "AA"),
                        Map.entry("track", "1/2"), Map.entry("disc", " 3 / 4 "), Map.entry("isrc", "jpvi02201530"),
                        Map.entry("date", "2022-06-29"), Map.entry("genre", "Pop"), Map.entry("copyright", "\u2117 2010 X"),
                        Map.entry("label", "Lab"), Map.entry("barcode", "4573556731873"))));
        // other names for the same tags
        assertEquals(new SongDetails(null, "AA", 5, 9, 2, 3, "USS1Z9900001", "1999", null, null, "Pub", "012345678905"),
                details(Map.of("albumartist", "AA", "track", "05", "tracktotal", "9", "disc", "2", "totaldiscs", "3",
                        "tsrc", "US-S1Z-99-00001", "year", "1999", "publisher", "Pub", "upc", "012345678905")));
        assertEquals(new SongDetails(null, null, null, 12, null, 2, null, "2022-06", null, null, "Org", "12345678"),
                details(Map.of("totaltracks", "12", "disctotal", "2", "date", "2022-06", "organization", "Org",
                        "ean", "12345678")));
        // n/total wins over a separate total; a bad part is dropped, not guessed
        assertEquals(new SongDetails(null, null, 1, 2, null, null, null, null, null, null, null, null),
                details(Map.of("track", "1/2", "tracktotal", "7")));
        for (Map<String, String> bad : List.of(Map.of("track", "0"), Map.of("track", "1/2;3"), Map.of("track", "A1"),
                Map.of("track", "10000"), Map.of("disc", "-1"), Map.of("tracktotal", "x"), Map.of("isrc", "JPVI0220153"),
                Map.of("isrc", "12VI02201530"), Map.of("date", "2022-13-01"), Map.of("date", "2023-02-29"),
                Map.of("date", "June 2022"), Map.of("year", "99"), Map.of("barcode", "1234567"),
                Map.of("barcode", "123456789012345"), Map.of("barcode", "12345678x"), Map.of("artists", "Solo"),
                Map.of("genre", " \u0000 "))) {
            assertEquals(SongDetails.EMPTY, details(bad), bad.toString());
        }
        assertEquals(new SongDetails(null, null, 1, null, null, null, null, null, null, null, null, null),
                details(Map.of("track", "1/0")), "a bad total alone is dropped");
        assertEquals("2022-06-29", details(Map.of("date", "2022-06-29T00:00:00Z")).date(), "the date of a timestamp");
        assertEquals("2001", details(Map.of("date", "bad", "year", "2001")).date());
    }

    /** The artist: the tag, else the ARTISTS names joined, else the album artist; the uploader's replaces the list. */
    @Test
    void artistAndArtists() {
        UploadMetadata m = UploadMetadata.of(probe(Map.of("artists", "A;B")), "a.mp3", null, null);
        assertEquals("A, B", m.artist());
        assertEquals(List.of("A", "B"), m.details().artists());
        m = UploadMetadata.of(probe(Map.of("artists", "A;B", "artist", "A feat. B")), "a.mp3", null, null);
        assertEquals("A feat. B", m.artist(), "both kept");
        assertEquals(List.of("A", "B"), m.details().artists());
        m = UploadMetadata.of(probe(Map.of("artists", "A;B")), "a.mp3", null, "Me");
        assertEquals("Me", m.artist());
        assertNull(m.details().artists());
        m = UploadMetadata.of(probe(Map.of("artists", "A;B")), "a.mp3", null, "A, B");
        assertEquals(List.of("A", "B"), m.details().artists(), "the uploader typed the same names");
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
