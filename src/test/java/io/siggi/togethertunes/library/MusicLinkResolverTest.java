package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.LinkPreference;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link MusicLinkResolver} against a fake MVFinder. */
class MusicLinkResolverTest {
    private static final String SECRET = "s3cr3t-header-value-1234";
    private static final MusicLinks.Link TRACK = MusicLinks.parse(
            "https://open.spotify.com/intl-de/track/6khnYqsyOTFq1SgpCi3Ucx?si=abc").orElseThrow();

    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final Actor.User alice = new Actor.User("u-alice", "s1", Actor.Via.BEARER);
    private final Actor.User bob = new Actor.User("u-bob", "s2", Actor.Via.BEARER);
    private final Actor.Guest guest = new Actor.Guest("g-1", GuestIdentity.COOKIE, false);
    private FakeMvfinder fake;
    private volatile MusicLinkResolver.Options options;
    private MusicLinkResolver resolver;
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private Handler handler;
    private Level oldLevel;
    private final Logger root = Logger.getLogger("");

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeMvfinder();
        options = options(fake.url(), SECRET, LinkPreference.MUSIC_VIDEO, false, 15);
        resolver = new MusicLinkResolver(() -> options, clock, Duration.ofMillis(800));
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r.getLoggerName() + " " + r.getMessage() + " " + r.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        oldLevel = root.getLevel();
        root.setLevel(Level.ALL);
        root.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        root.removeHandler(handler);
        root.setLevel(oldLevel);
        fake.close();
    }

    private static MusicLinkResolver.Options options(String url, String headerValue, LinkPreference prefer,
                                                     boolean allowGuests, int maxPerHour) {
        return new MusicLinkResolver.Options(url, "X-Api-Key", headerValue, prefer, allowGuests, maxPerHour);
    }

    private HttpError error(Actor actor, MusicLinks.Link link) {
        return assertThrows(HttpError.class, () -> resolver.resolve(actor, link));
    }

    private void assertNoSecretLogged() {
        for (String line : logs) {
            assertFalse(line.contains(SECRET), "the header value was logged: " + line);
        }
    }

    @Test
    void sendsTheCanonicalPathWithSingleAndTheHeader() throws Exception {
        MusicLinkResolver.Resolved r = resolver.resolve(alice, TRACK);
        assertEquals(FakeMvfinder.VIDEO_ID, r.videoId(), "music_video preferred");
        assertEquals(1, fake.seen.size());
        FakeMvfinder.Seen seen = fake.seen.get(0);
        assertEquals("/open.spotify.com/track/6khnYqsyOTFq1SgpCi3Ucx", seen.path(), "canonical link, not the pasted one");
        assertEquals("single=1&type=all", seen.query());
        assertEquals(SECRET, seen.header("X-Api-Key"));

        MusicLinkResolver.Track t = r.track();
        assertEquals(MusicLinks.Service.SPOTIFY, t.service());
        assertEquals("6khnYqsyOTFq1SgpCi3Ucx", t.id());
        assertEquals("リモコン-NT ver.- (feat. 鏡音リン&鏡音レン)", t.title());
        assertEquals(List.of("Wonderful★opportunity!", "Kagamine Rin", "Kagamine Len"), t.artists());
        assertEquals("リモコン-NT ver.-", t.album());
        assertEquals("JP92Q2503558", t.isrc());
        assertNoSecretLogged();
        assertFalse(options.toString().contains(SECRET));
    }

    @Test
    void otherHeaderNameAndNoHeaderWhenEmpty() throws Exception {
        options = new MusicLinkResolver.Options(fake.url() + "/", "X-Other", SECRET, LinkPreference.MUSIC_VIDEO, false, 15);
        resolver.resolve(alice, TRACK);
        assertEquals(SECRET, fake.seen.get(0).header("X-Other"));
        assertNull(fake.seen.get(0).header("X-Api-Key"));
        assertEquals("/open.spotify.com/track/6khnYqsyOTFq1SgpCi3Ucx", fake.seen.get(0).path(), "trailing slash dropped");

        options = options(fake.url(), "", LinkPreference.MUSIC_VIDEO, false, 15);
        resolver.resolve(alice, TRACK);
        assertNull(fake.seen.get(1).header("X-Api-Key"), "no header without a value");
    }

    @Test
    void preferSongAndFallbacks() throws Exception {
        options = options(fake.url(), SECRET, LinkPreference.SONG, false, 15);
        assertEquals(FakeMvfinder.SONG_ID, resolver.resolve(alice, TRACK).videoId());

        // only a song: music_video falls back to it
        fake.body = "{\"source\":{},\"matches\":[{\"type\":\"song\",\"videoId\":\"" + FakeMvfinder.SONG_ID + "\"}]}";
        options = options(fake.url(), SECRET, LinkPreference.MUSIC_VIDEO, false, 15);
        assertEquals(FakeMvfinder.SONG_ID, resolver.resolve(alice, TRACK).videoId());

        // only a lyric video standing in for the music video: song falls back to it
        fake.body = "{\"source\":{},\"matches\":[{\"type\":\"lyric_video\",\"videoId\":\"" + FakeMvfinder.VIDEO_ID + "\"},"
                + "{\"type\":\"user_video\",\"videoId\":\"aaaaaaaaaaa\"}]}";
        options = options(fake.url(), SECRET, LinkPreference.SONG, false, 15);
        assertEquals(FakeMvfinder.VIDEO_ID, resolver.resolve(alice, TRACK).videoId());

        // a user upload alone is never picked
        fake.body = "{\"source\":{},\"matches\":[{\"type\":\"user_video\",\"videoId\":\"aaaaaaaaaaa\"}]}";
        assertEquals("link_not_resolved", error(alice, TRACK).code());

        // no matches
        fake.body = "{\"source\":{},\"matches\":[]}";
        assertEquals("link_not_resolved", error(alice, TRACK).code());

        // an invalid videoId is skipped: the other match is used
        fake.body = "{\"source\":{},\"matches\":[{\"type\":\"music_video\",\"videoId\":\"../../etc\"},"
                + "{\"type\":\"song\",\"videoId\":\"" + FakeMvfinder.SONG_ID + "\"}]}";
        options = options(fake.url(), SECRET, LinkPreference.MUSIC_VIDEO, false, 15);
        assertEquals(FakeMvfinder.SONG_ID, resolver.resolve(alice, TRACK).videoId());
        fake.body = "{\"source\":{},\"matches\":[{\"type\":\"music_video\",\"videoId\":\"tooShort\"}]}";
        assertEquals("link_not_resolved", error(alice, TRACK).code());
    }

    @Test
    void songIsTheDefaultAndTheVideoMatchesAreKept() throws Exception {
        assertEquals(LinkPreference.SONG, io.siggi.togethertunes.settings.Settings.MVFINDER_PREFER.defaultValue());
        options = options(fake.url(), SECRET, io.siggi.togethertunes.settings.Settings.MVFINDER_PREFER.defaultValue(),
                false, 15);
        MusicLinkResolver.Resolved r = resolver.resolve(alice, TRACK);
        assertEquals(FakeMvfinder.SONG_ID, r.videoId());
        assertEquals(List.of(new MusicLinkResolver.VideoMatch(FakeMvfinder.VIDEO_ID, "リモコン -NT ver.-/ 鏡音リンレンNT - ワンオポ",
                List.of("Wonderfulopportunity"), 314_000L, "music_video", null)), r.videos());

        // every video type is kept, in MVFinder's order; songs, user uploads, bad IDs and repeats aren't
        fake.body = "{\"source\":{},\"matches\":["
                + "{\"type\":\"song\",\"videoId\":\"" + FakeMvfinder.SONG_ID + "\"},"
                + "{\"type\":\"lyric_video\",\"videoId\":\"lllllllllll\",\"title\":\"L\"},"
                + "{\"type\":\"user_video\",\"videoId\":\"uuuuuuuuuuu\"},"
                + "{\"type\":\"visualizer\",\"videoId\":\"vvvvvvvvvvv\"},"
                + "{\"type\":\"official_video\",\"videoId\":\"ooooooooooo\"},"
                + "{\"type\":\"music_video\",\"videoId\":\"bad\"},"
                + "{\"type\":\"music_video\",\"videoId\":\"lllllllllll\"}]}";
        r = resolver.resolve(alice, TRACK);
        assertEquals(FakeMvfinder.SONG_ID, r.videoId());
        assertEquals(List.of("lllllllllll", "vvvvvvvvvvv", "ooooooooooo"),
                r.videos().stream().map(MusicLinkResolver.VideoMatch::videoId).toList());
        assertEquals(List.of("lyric_video", "visualizer", "official_video"),
                r.videos().stream().map(MusicLinkResolver.VideoMatch::type).toList());
        assertNull(r.videos().get(1).durationMs());

        // the imported video isn't among them
        options = options(fake.url(), SECRET, LinkPreference.MUSIC_VIDEO, false, 15);
        fake.body = FakeMvfinder.README_TRACK;
        r = resolver.resolve(alice, TRACK);
        assertEquals(FakeMvfinder.VIDEO_ID, r.videoId());
        assertEquals(List.of(), r.videos());
    }

    @Test
    void errors() {
        fake.status = 404;
        fake.body = "{\"error\":{\"status\":404,\"message\":\"Spotify track not found\"}}";
        HttpError e = error(alice, TRACK);
        assertEquals(422, e.status());
        assertEquals("link_not_resolved", e.code());
        assertEquals("Couldn't find this song", e.getMessage());

        fake.status = 502;
        e = error(alice, TRACK);
        assertEquals(503, e.status());
        assertEquals("resolver_unavailable", e.code());
        assertEquals("Can't look up this link right now", e.getMessage());

        fake.status = 503;
        assertEquals("resolver_unavailable", error(alice, TRACK).code());

        fake.status = 422;
        e = error(alice, TRACK);
        assertEquals("not_a_track", e.code());

        fake.status = 200;
        fake.body = "<html>not json";
        assertEquals("resolver_unavailable", error(alice, TRACK).code());
        fake.body = "[1, 2]";
        assertEquals("resolver_unavailable", error(alice, TRACK).code());
        fake.body = "{\"source\":{}}";
        assertEquals("link_not_resolved", error(alice, TRACK).code());

        fake.body = FakeMvfinder.README_TRACK;
        fake.delayMs = 3000;
        long start = System.nanoTime();
        assertEquals("resolver_unavailable", error(alice, TRACK).code(), "timeout");
        assertTrue(System.nanoTime() - start < 2_500_000_000L, "gave up at the timeout");
        fake.delayMs = 0;

        options = options("http://127.0.0.1:1", SECRET, LinkPreference.MUSIC_VIDEO, false, 15);
        assertEquals("resolver_unavailable", error(alice, TRACK).code(), "unreachable");
        assertNoSecretLogged();
    }

    @Test
    void albumsAndPlaylistsAreRefusedWithoutACall() {
        HttpError e = error(alice, MusicLinks.parse("https://open.spotify.com/album/4m2880jivSbbyEGAKfITCa").orElseThrow());
        assertEquals(422, e.status());
        assertEquals("not_a_track", e.code());
        assertEquals("Only links to a single song work here", e.getMessage());
        assertEquals("not_a_track", error(alice,
                MusicLinks.parse("https://tidal.com/playlist/36ea71a8-445e-41a4-82ab-6628c581535d").orElseThrow()).code());
        assertTrue(fake.seen.isEmpty());
    }

    @Test
    void featureOff() {
        options = options(null, SECRET, LinkPreference.MUSIC_VIDEO, true, 15);
        assertFalse(resolver.enabled());
        HttpError e = error(alice, TRACK);
        assertEquals(400, e.status());
        assertEquals("invalid_youtube_url", e.code());
        assertTrue(fake.seen.isEmpty());
    }

    @Test
    void guestsOnlyWithAllowGuests() throws Exception {
        HttpError e = error(guest, TRACK);
        assertEquals(401, e.status());
        assertEquals("not_authenticated", e.code());
        assertEquals("Log in to request links like this", e.getMessage());
        assertEquals("not_authenticated", error(Actor.ANONYMOUS, TRACK).code());
        assertTrue(fake.seen.isEmpty());

        options = options(fake.url(), SECRET, LinkPreference.MUSIC_VIDEO, true, 15);
        assertEquals(FakeMvfinder.VIDEO_ID, resolver.resolve(guest, TRACK).videoId());
        assertEquals("not_authenticated", error(Actor.ANONYMOUS, TRACK).code(), "never anonymous visitors");
    }

    @Test
    void perActorLimitPerHour() throws Exception {
        options = options(fake.url(), SECRET, LinkPreference.MUSIC_VIDEO, true, 3);
        for (int i = 0; i < 3; i++) {
            resolver.resolve(alice, TRACK);
            clock.advance(60_000);
        }
        HttpError e = error(alice, TRACK);
        assertEquals(429, e.status());
        assertEquals("too_many_lookups", e.code());
        assertEquals(3600 - 180, e.retryAfterSeconds(), "until the oldest one is an hour old");
        assertEquals(3, fake.seen.size(), "a refused one isn't sent");
        resolver.resolve(bob, TRACK); // per actor
        resolver.resolve(guest, TRACK);
        clock.advance(3600_000 - 180_000);
        resolver.resolve(alice, TRACK);
        assertEquals("too_many_lookups", error(alice, TRACK).code());
    }
}
