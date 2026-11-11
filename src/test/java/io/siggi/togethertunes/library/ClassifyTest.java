package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.http.HttpError;
import org.junit.jupiter.api.Test;

/** The classify rules and suggestions without a database ({@link Classify}). */
class ClassifyTest {
    private static Classify.Suggestion show(String name, Integer season, Integer episode) {
        return new Classify.Suggestion("show", new Classify.SuggestedShow(name, season, episode));
    }

    @Test
    void fileNamesWithSeasonAndEpisode() {
        assertEquals(show("My Great Show", 2, 5), Classify.fromFileName("My.Great_Show.S02E05.1080p.mp4"));
        assertEquals(show("Some Show", 1, 12), Classify.fromFileName("Some_Show - s1e12 [720p].mkv"));
        assertEquals(show("Show", 3, 4), Classify.fromFileName("Show S03 E04.webm"));
        assertEquals(show("Show", 10, 100), Classify.fromFileName("Show.S10.E100"));
        assertEquals(show(null, 1, 2), Classify.fromFileName("S01E02.mp4"), "no name before it");
        assertNull(Classify.fromFileName("Movie Night (2020).mkv"));
        assertNull(Classify.fromFileName("ABS01E02.mp4"), "part of a longer word");
        assertNull(Classify.fromFileName("S01E023456789"), "too many digits for an episode");
    }

    @Test
    void youtubeMetadata() {
        JsonObject series = JsonParser.parseString("{\"series\":\"The Show\",\"seasonNumber\":2,\"episodeNumber\":3}")
                .getAsJsonObject();
        assertEquals(show("The Show", 2, 3), Classify.fromYoutube(series));
        assertEquals(show(null, null, 7), Classify.fromYoutube(JsonParser.parseString("{\"episodeNumber\":7}")
                .getAsJsonObject()));
        assertEquals(new Classify.Suggestion("music_video", null),
                Classify.fromYoutube(JsonParser.parseString("{\"track\":\"Song\"}").getAsJsonObject()));
        assertEquals(new Classify.Suggestion("music_video", null),
                Classify.fromYoutube(JsonParser.parseString("{\"artist\":\"Band\"}").getAsJsonObject()));
        assertNull(Classify.fromYoutube(JsonParser.parseString("{\"uploader\":\"Somebody\"}").getAsJsonObject()),
                "the uploader alone says nothing");
    }

    @Test
    void parsedFromABody() {
        assertNull(Classify.of(JsonParser.parseString("{}").getAsJsonObject()));
        assertNull(Classify.of(null));
        assertEquals(new Classify.Classification(MediaType.MOVIE, null),
                Classify.of(JsonParser.parseString("{\"mediaType\":\"movie\"}").getAsJsonObject()));
        assertEquals(new Classify.Classification(MediaType.SHOW, new MediaType.Show("X", null, 0)),
                Classify.of(JsonParser.parseString("{\"mediaType\":\"show\",\"show\":{\"name\":\"X\",\"episode\":0}}")
                        .getAsJsonObject()));
        for (String[] bad : new String[][] {
                {"{\"mediaType\":\"uncategorized\"}", "invalid_media_type"},
                {"{\"mediaType\":\"podcast\"}", "invalid_media_type"},
                {"{\"mediaType\":3}", "invalid_media_type"},
                {"{\"mediaType\":\"show\"}", "show_required"},
                {"{\"show\":{\"name\":\"X\",\"episode\":1}}", "not_a_show"},
                {"{\"mediaType\":\"audio\",\"show\":{\"name\":\"X\",\"episode\":1}}", "not_a_show"},
                {"{\"mediaType\":\"show\",\"show\":{\"name\":\"X\",\"episode\":-1}}", "invalid_show"},
        }) {
            assertEquals(bad[1], assertThrows(HttpError.class,
                    () -> Classify.of(JsonParser.parseString(bad[0]).getAsJsonObject()), bad[0]).code(), bad[0]);
        }
    }
}
