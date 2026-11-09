package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OriginalsTest {
    private static final String MP4 = "mov,mp4,m4a,3gp,3g2,mj2";
    private static final String MKV = "matroska,webm";

    private static MediaProbe probe(String format, String codec) {
        return new MediaProbe(format, 0, 3_000_000, new MediaProbe.Stream(0, codec, 0, 3_000_000), null, null);
    }

    private static Loudness.Measurement loudness(double i, double tp) {
        return new Loudness.Measurement(i, tp, 5, i - 10, 0.1);
    }

    @Test
    void codecIsCodecSlashContainer() {
        assertEquals("flac/flac", Originals.codec(probe("flac", "flac")));
        assertEquals("aac/" + MP4, Originals.codec(probe(MP4, "aac")));
    }

    @Test
    void extensionComesFromTheContainer() {
        assertEquals("mp3", Originals.extension(probe("mp3", "mp3"), "source"));
        assertEquals("mp3", Originals.extension(probe("mp3", "mp3"), "source.wav"));
        assertEquals("flac", Originals.extension(probe("flac", "flac"), "source.flac"));
        assertEquals("wav", Originals.extension(probe("wav", "pcm_s16le"), "source.WAVE"));
        assertEquals("aac", Originals.extension(probe("aac", "aac"), "source.adts"));
        assertEquals("opus", Originals.extension(probe("ogg", "opus"), "source.ogg"));
        assertEquals("ogg", Originals.extension(probe("ogg", "vorbis"), "source.opus"));
        assertEquals("m4a", Originals.extension(probe(MP4, "aac"), "source.mp4"));
        assertEquals("m4a", Originals.extension(probe(MP4, "alac"), "source"));
        assertEquals("webm", Originals.extension(probe(MKV, "opus"), "source.WebM"));
        assertEquals("mkv", Originals.extension(probe(MKV, "opus"), "source.mkv"));
        assertEquals("mka", Originals.extension(probe(MKV, "vorbis"), "source.ogg"));
        assertEquals("mka", Originals.extension(probe(MKV, "vorbis"), "source"));
        // other containers keep a plain upload extension, else .bin
        assertEquals("wma", Originals.extension(probe("asf", "wmav2"), "source.wma"));
        assertEquals("ape", Originals.extension(probe("ape", "ape"), "source.APE"));
        assertEquals("bin", Originals.extension(probe("ape", "ape"), "source"));
        assertEquals("bin", Originals.extension(probe("ape", "ape"), "source."));
        assertEquals("bin", Originals.extension(probe("ape", "ape"), "source.a-b"));
        assertEquals("bin", Originals.extension(probe("ape", "ape"), "source.toolongextension"));
    }

    @Test
    void webCodecWhitelist() {
        assertTrue(Originals.webCodec(probe("mp3", "mp3"), "mp3"));
        assertTrue(Originals.webCodec(probe(MP4, "aac"), "m4a"));
        assertTrue(Originals.webCodec(probe("aac", "aac"), "aac"));
        assertTrue(Originals.webCodec(probe("flac", "flac"), "flac"));
        assertTrue(Originals.webCodec(probe("ogg", "opus"), "opus"));
        assertTrue(Originals.webCodec(probe(MKV, "opus"), "webm"));
        assertTrue(Originals.webCodec(probe("wav", "pcm_s16le"), "wav"));
        assertTrue(Originals.webCodec(probe("wav", "pcm_s24le"), "wav"));
        assertTrue(Originals.webCodec(probe("wav", "pcm_u8"), "wav"));
        assertTrue(Originals.webCodec(probe("wav", "pcm_f32le"), "wav"));

        assertFalse(Originals.webCodec(probe(MKV, "opus"), "mkv"));
        assertFalse(Originals.webCodec(probe(MKV, "aac"), "mka"));
        assertFalse(Originals.webCodec(probe(MP4, "alac"), "m4a"));
        assertFalse(Originals.webCodec(probe(MP4, "mp3"), "m4a"));
        assertFalse(Originals.webCodec(probe("asf", "wmav2"), "wma"));
        assertFalse(Originals.webCodec(probe("ape", "ape"), "ape"));
        assertFalse(Originals.webCodec(probe("ogg", "vorbis"), "ogg"));
        assertFalse(Originals.webCodec(probe("ogg", "flac"), "ogg"));
        assertFalse(Originals.webCodec(probe("wav", "pcm_s16be"), "wav"));
        assertFalse(Originals.webCodec(probe("wav", "adpcm_ms"), "wav"));
        assertFalse(Originals.webCodec(probe("aiff", "pcm_s16be"), "aiff"));
    }

    @Test
    void durationWithin50Ms() {
        assertTrue(Originals.durationMatches(3_000_000, 3_000_000));
        assertTrue(Originals.durationMatches(3_050_000, 3_000_000));
        assertTrue(Originals.durationMatches(2_950_000, 3_000_000));
        assertFalse(Originals.durationMatches(3_050_001, 3_000_000));
        assertFalse(Originals.durationMatches(2_949_999, 3_000_000));
        assertFalse(Originals.durationMatches(-1, 3_000_000));
        assertFalse(Originals.durationMatches(3_000_000, 0));
    }

    @Test
    void gainIsTheSmallerOfLoudnessAndPeakHeadroom() {
        // loudness-limited: -20 LUFS, -10 dBTP → +6 dB (peak would allow +9)
        assertEquals(6.0, Originals.gainDb(loudness(-20, -10)));
        // peak-limited: -20 LUFS, -3 dBTP → +2 dB (loudness would want +6)
        assertEquals(2.0, Originals.gainDb(loudness(-20, -3)));
        // loud master: -8.4 LUFS, +0.6 dBTP → -5.6 dB vs -1.6 dB → -5.6
        assertEquals(-5.6, Originals.gainDb(loudness(-8.4, 0.6)));
        assertEquals(-1.23, Originals.gainDb(loudness(-12.77, -0.1)));
        assertNull(Originals.gainDb(loudness(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)));
    }

    @Test
    void describe() {
        Originals.Original o = Originals.describe(probe("flac", "flac"), "source.flac", loudness(-20, -10),
                3_010_000, 3_000_000);
        assertEquals(new Originals.Original("original.flac", "flac/flac", true, 6.0), o);
        // out of the 50 ms window: kept, not web-playable
        o = Originals.describe(probe("mp3", "mp3"), "source.mp3", loudness(-20, -10), 3_100_000, 3_000_000);
        assertEquals(new Originals.Original("original.mp3", "mp3/mp3", false, 6.0), o);
        o = Originals.describe(probe("asf", "wmav2"), "source.wma", loudness(Double.NEGATIVE_INFINITY, 0), 3_000_000,
                3_000_000);
        assertEquals(new Originals.Original("original.wma", "wmav2/asf", false, null), o);
    }
}
