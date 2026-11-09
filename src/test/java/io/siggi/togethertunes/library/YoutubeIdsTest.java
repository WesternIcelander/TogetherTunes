package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** URL → video ID normalisation ({@code docs/library-and-ingest.md#youtube-caching}, step 1). */
class YoutubeIdsTest {
    private static final String ID = "dQw4w9WgXcQ";

    @Test
    void everyDocumentedFormYieldsTheId() {
        List<String> links = List.of(
                // watch?v=, with and without scheme/www, extra parameters in any order
                "https://www.youtube.com/watch?v=" + ID,
                "http://www.youtube.com/watch?v=" + ID,
                "https://youtube.com/watch?v=" + ID,
                "www.youtube.com/watch?v=" + ID,
                "youtube.com/watch?v=" + ID,
                "//www.youtube.com/watch?v=" + ID,
                "https://www.youtube.com/watch?v=" + ID + "&t=42s",
                "https://www.youtube.com/watch?v=" + ID + "&t=1m2s&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&index=3",
                "https://www.youtube.com/watch?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&v=" + ID,
                "https://www.youtube.com/watch?feature=share&v=" + ID + "&si=AbCdEfGhIjKlMnOp&pp=ygUJcmljayByb2xs",
                "https://www.youtube.com/watch/?v=" + ID,
                "https://www.youtube.com/watch?v=" + ID + "#t=30",
                "  https://www.youtube.com/watch?v=" + ID + "  \n",
                "HTTPS://WWW.YOUTUBE.COM/watch?v=" + ID,
                "https://www.youtube.com./watch?v=" + ID,
                // m. and music. variants
                "https://m.youtube.com/watch?v=" + ID + "&feature=youtu.be",
                "m.youtube.com/watch?v=" + ID,
                "https://music.youtube.com/watch?v=" + ID + "&list=RDAMVM" + ID,
                "https://m.youtube.com/shorts/" + ID,
                // youtu.be
                "https://youtu.be/" + ID,
                "youtu.be/" + ID,
                "https://youtu.be/" + ID + "?t=42",
                "https://youtu.be/" + ID + "?si=AbCdEfGhIjKlMnOp&t=10",
                "https://youtu.be/" + ID + "/",
                // path forms
                "https://www.youtube.com/shorts/" + ID,
                "https://youtube.com/shorts/" + ID + "?feature=share",
                "https://www.youtube.com/embed/" + ID,
                "https://www.youtube.com/embed/" + ID + "?start=30&autoplay=1",
                "https://www.youtube-nocookie.com/embed/" + ID,
                "https://www.youtube.com/live/" + ID,
                "https://www.youtube.com/live/" + ID + "?si=abc",
                "https://www.youtube.com/v/" + ID,
                // percent-encoded query
                "https://www.youtube.com/watch?%76=" + ID + "&t=1");
        for (String link : links) {
            assertEquals(Optional.of(ID), YoutubeIds.parse(link), link);
        }
    }

    @Test
    void idsAreCaseSensitiveAndMayContainDashAndUnderscore() {
        assertEquals(Optional.of("a-B_c1D2e3F"), YoutubeIds.parse("https://youtu.be/a-B_c1D2e3F"));
        assertEquals(Optional.of("A-b_C1d2E3f"), YoutubeIds.parse("https://www.youtube.com/watch?v=A-b_C1d2E3f"));
        assertEquals(Optional.of("-_-_-_-_-_-"), YoutubeIds.parse("youtu.be/-_-_-_-_-_-"));
    }

    @Test
    void anythingElseIsRejected() {
        List<String> links = List.of(
                "",
                "   ",
                ID, // a bare ID isn't a link
                "not a url",
                "https://www.youtube.com/",
                "https://www.youtube.com/watch",
                "https://www.youtube.com/watch?v=",
                "https://www.youtube.com/watch?v=tooShort",
                "https://www.youtube.com/watch?v=" + ID + "X", // 12 characters
                "https://www.youtube.com/watch?v=dQw4w9WgXc!",
                "https://www.youtube.com/watch?vv=" + ID,
                "https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
                "https://www.youtube.com/@RickAstleyYT",
                "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
                "https://www.youtube.com/shorts/",
                "https://www.youtube.com/shorts/" + ID + "X",
                "https://www.youtube.com/watch/" + ID,
                "https://www.youtube.com/foo/" + ID,
                "https://youtu.be/",
                "https://youtu.be/watch?v=" + ID,
                "https://www.youtube-nocookie.com/watch?v=" + ID,
                "https://evil.example/watch?v=" + ID,
                "https://youtube.com.evil.example/watch?v=" + ID,
                "https://evilyoutube.com/watch?v=" + ID,
                "https://notyoutu.be/" + ID,
                "https://www.youtube.com@evil.example/watch?v=" + ID,
                "ftp://www.youtube.com/watch?v=" + ID,
                "javascript:alert(1)//youtu.be/" + ID,
                "file:///youtu.be/" + ID,
                "https://www.youtube.com/watch?v=" + ID + "x".repeat(YoutubeIds.MAX_LENGTH));
        for (String link : links) {
            assertEquals(Optional.empty(), YoutubeIds.parse(link), link);
        }
        assertEquals(Optional.empty(), YoutubeIds.parse(null));
    }

    @Test
    void canonicalUrl() {
        assertEquals("https://www.youtube.com/watch?v=" + ID, YoutubeIds.canonicalUrl(ID));
        assertThrows(IllegalArgumentException.class, () -> YoutubeIds.canonicalUrl("x"));
        assertThrows(IllegalArgumentException.class, () -> YoutubeIds.canonicalUrl(ID + "&list=x"));
        assertTrue(YoutubeIds.isValidId(ID));
        assertFalse(YoutubeIds.isValidId(null));
        assertFalse(YoutubeIds.isValidId("dQw4w9WgXc/"));
    }
}
