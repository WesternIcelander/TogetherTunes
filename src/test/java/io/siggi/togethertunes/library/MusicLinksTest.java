package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.library.MusicLinks.Kind;
import io.siggi.togethertunes.library.MusicLinks.Link;
import io.siggi.togethertunes.library.MusicLinks.Service;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The link forms MVFinder's README lists, and everything that must not be sent to it. */
class MusicLinksTest {
    private static final String SP = "6khnYqsyOTFq1SgpCi3Ucx";

    private static Link parse(String s) {
        return MusicLinks.parse(s).orElseThrow(() -> new AssertionError("not recognised: " + s));
    }

    private static void none(String s) {
        assertEquals(Optional.empty(), MusicLinks.parse(s), s);
    }

    @Test
    void spotifyTrackLinks() {
        Link expected = new Link(Service.SPOTIFY, Kind.TRACK, SP);
        for (String s : new String[] {
                "https://open.spotify.com/track/" + SP,
                "open.spotify.com/track/" + SP,
                "http://open.spotify.com/track/" + SP,
                "  https://open.spotify.com/track/" + SP + "?si=abc123def456  ",
                "https://open.spotify.com/intl-de/track/" + SP,
                "https://open.spotify.com/intl-pt/track/" + SP + "?si=x",
                "https://open.spotify.com/embed/track/" + SP,
                "https://open.spotify.com/track/" + SP + "/",
                "https://OPEN.SPOTIFY.COM/track/" + SP,
        }) {
            assertEquals(expected, parse(s), s);
        }
        assertEquals("open.spotify.com/track/" + SP, expected.path());
        assertTrue(expected.isTrack());
    }

    @Test
    void spotifyAlbumsAndPlaylistsAreNotTracks() {
        assertEquals(new Link(Service.SPOTIFY, Kind.ALBUM, "4m2880jivSbbyEGAKfITCa"),
                parse("https://open.spotify.com/album/4m2880jivSbbyEGAKfITCa"));
        assertEquals(new Link(Service.SPOTIFY, Kind.PLAYLIST, "33YxQ9R7gaOhNOjA1nKph1"),
                parse("https://open.spotify.com/playlist/33YxQ9R7gaOhNOjA1nKph1?si=1"));
        assertEquals(Kind.PLAYLIST, parse("https://open.spotify.com/embed/playlist/33YxQ9R7gaOhNOjA1nKph1").kind());
    }

    @Test
    void tidalLinks() {
        Link track = new Link(Service.TIDAL, Kind.TRACK, "251380837");
        for (String s : new String[] {
                "https://tidal.com/track/251380837",
                "tidal.com/track/251380837",
                "https://listen.tidal.com/track/251380837",
                "https://www.tidal.com/track/251380837",
                "https://tidal.com/browse/track/251380837",
                "https://tidal.com/browse/track/251380837/u",
                "https://tidal.com/track/251380837/u",
                "https://tidal.com/track/251380837?u",
                "https://listen.tidal.com/album/251380836/track/251380837",
                "https://tidal.com/browse/album/251380836/track/251380837",
        }) {
            assertEquals(track, parse(s), s);
        }
        assertEquals("tidal.com/track/251380837", track.path());
        Link album = new Link(Service.TIDAL, Kind.ALBUM, "251380836");
        assertEquals(album, parse("https://tidal.com/album/251380836"));
        assertEquals(album, parse("https://tidal.com/browse/album/251380836/u"));
        assertEquals(album, parse("https://tidal.com/album/renaissance/251380836"));
        assertEquals(new Link(Service.TIDAL, Kind.PLAYLIST, "36ea71a8-445e-41a4-82ab-6628c581535d"),
                parse("https://tidal.com/playlist/36ea71a8-445e-41a4-82ab-6628c581535d"));
        assertEquals(Kind.PLAYLIST, parse("https://listen.tidal.com/browse/playlist/36ea71a8-445e-41a4-82ab-6628c581535d").kind());
    }

    @Test
    void otherHostsAndFormsAreRefused() {
        none(null);
        none("");
        none("   ");
        none("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        none("https://spotify.com/track/" + SP);
        none("https://evil.example/open.spotify.com/track/" + SP);
        none("https://open.spotify.com.evil.example/track/" + SP);
        none("https://open.spotify.com@evil.example/track/" + SP);
        none("https://user@open.spotify.com/track/" + SP);
        none("https://open.spotify.com:8443/track/" + SP);
        none("ftp://open.spotify.com/track/" + SP);
        none("https://open.spotify.com/track/short");
        none("https://open.spotify.com/track/" + SP + "/extra");
        none("https://open.spotify.com/artist/" + SP);
        none("https://open.spotify.com/episode/" + SP);
        none("https://open.spotify.com/track/../track/" + SP + "x");
        none("https://tidal.com/track/abc");
        none("https://tidal.com/artist/1566");
        none("https://tidal.com/playlist/123");
        none("https://evil.tidal.com/track/251380837");
        none("https://tidal.com.evil.example/track/251380837");
        none("spotify:track:" + SP);
        none("https://open.spotify.com/track/" + SP + "x".repeat(3000));
    }
}
