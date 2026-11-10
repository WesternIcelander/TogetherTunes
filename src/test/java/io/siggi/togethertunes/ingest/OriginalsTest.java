package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static MediaProbe videoProbe(String format, String audioCodec) {
        return new MediaProbe(format, 0, 3_000_000, new MediaProbe.Stream(1, audioCodec, 0, 3_000_000),
                new MediaProbe.Stream(0, "h264", 0, 3_000_000), null);
    }

    /** A video item's original uses the containers' video names. */
    @Test
    void videoExtensionComesFromTheContainer() {
        assertEquals("mp4", Originals.extension(videoProbe(MP4, "aac"), "source.m4a"));
        assertEquals("mp4", Originals.extension(videoProbe(MP4, "aac"), "source"));
        assertEquals("mov", Originals.extension(videoProbe(MP4, "aac"), "source.MOV"));
        assertEquals("m4v", Originals.extension(videoProbe(MP4, "aac"), "source.m4v"));
        assertEquals("mkv", Originals.extension(videoProbe(MKV, "opus"), "src.mkv"));
        assertEquals("mkv", Originals.extension(videoProbe(MKV, "opus"), "source.mka"));
        assertEquals("mkv", Originals.extension(videoProbe(MKV, "opus"), "source"));
        assertEquals("webm", Originals.extension(videoProbe(MKV, "opus"), "src.webm"));
        assertEquals("avi", Originals.extension(videoProbe("avi", "pcm_s16le"), "source.avi"));
        assertEquals("bin", Originals.extension(videoProbe("avi", "pcm_s16le"), "source"));
    }

    /** A video's or a YouTube download's original is kept for re-encodes only: never web-playable, no gain. */
    @Test
    void keptOnlyIsNeverWebPlayable() {
        assertEquals(new Originals.Original("original.mkv", "opus/" + MKV, false, null),
                Originals.keptOnly(videoProbe(MKV, "opus"), "src.mkv"));
        // a YouTube download that turned out to be audio only: kept, but not played either
        assertEquals(new Originals.Original("original.m4a", "aac/" + MP4, false, null),
                Originals.keptOnly(probe(MP4, "aac"), "src.m4a"));
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
    void gainMatchesAudioM4aCappedAtZeroDbtp() {
        // linear loudnorm: -20 LUFS, -10 dBTP → audio.m4a at -14.05 → +5.95 dB (peaks would allow +10)
        assertEquals(5.95, Originals.gainDb(loudness(-20, -10), loudness(-14.05, -4.1)));
        assertNull(Originals.loudnessMismatch(loudness(-20, -10), loudness(-14.05, -4.1)));
        // loud master: -8.4 LUFS, +0.6 dBTP → -5.6 dB
        assertEquals(-5.6, Originals.gainDb(loudness(-8.4, 0.6), loudness(-14, -5)));
        assertNull(Originals.loudnessMismatch(loudness(-8.4, 0.6), loudness(-14, -5)));
        // dynamic loudnorm on a high loudness range: whatever audio.m4a ended up at is matched
        assertEquals(10.9, Originals.gainDb(loudness(-25, -12), loudness(-14.1, -1)));
        assertNull(Originals.loudnessMismatch(loudness(-25, -12), loudness(-14.1, -1)));
        // peak-limited by up to 0.5 dB: capped, still matched closely enough
        assertEquals(3.0, Originals.gainDb(loudness(-17.5, -3), loudness(-14, -1)));
        assertNull(Originals.loudnessMismatch(loudness(-17.5, -3), loudness(-14, -1)));
        // quiet with high peaks: loudnorm compressed it; the original would need +16.3 dB but may get +3.2
        assertEquals(3.23, Originals.gainDb(loudness(-31.43, -3.23), loudness(-15.1, -1)));
        assertNotNull(Originals.loudnessMismatch(loudness(-31.43, -3.23), loudness(-15.1, -1)));
        // silence: no gain, nothing to match
        Loudness.Measurement silence = loudness(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        assertNull(Originals.gainDb(silence, null));
        assertNull(Originals.loudnessMismatch(silence, null));
        // audio.m4a not measurable (shouldn't happen): not matched
        assertNull(Originals.gainDb(loudness(-20, -10), silence));
        assertNotNull(Originals.loudnessMismatch(loudness(-20, -10), silence));
    }

    @Test
    void describe() {
        Originals.Original o = Originals.describe(probe("flac", "flac"), "source.flac", loudness(-20, -10),
                loudness(-14, -4), 3_010_000, 3_000_000);
        assertEquals(new Originals.Original("original.flac", "flac/flac", true, 6.0), o);
        // out of the 50 ms window: kept, not web-playable
        o = Originals.describe(probe("mp3", "mp3"), "source.mp3", loudness(-20, -10), loudness(-14, -4),
                3_100_000, 3_000_000);
        assertEquals(new Originals.Original("original.mp3", "mp3/mp3", false, 6.0), o);
        // loudness can't be matched without clipping: kept, not web-playable
        o = Originals.describe(probe("flac", "flac"), "source.flac", loudness(-30, -3), loudness(-15, -1),
                3_000_000, 3_000_000);
        assertEquals(new Originals.Original("original.flac", "flac/flac", false, 3.0), o);
        // silence: web-playable without a gain
        o = Originals.describe(probe("flac", "flac"), "source.flac", loudness(Double.NEGATIVE_INFINITY, 0), null,
                3_000_000, 3_000_000);
        assertEquals(new Originals.Original("original.flac", "flac/flac", true, null), o);
        o = Originals.describe(probe("asf", "wmav2"), "source.wma", loudness(Double.NEGATIVE_INFINITY, 0), null,
                3_000_000, 3_000_000);
        assertEquals(new Originals.Original("original.wma", "wmav2/asf", false, null), o);
    }
}
