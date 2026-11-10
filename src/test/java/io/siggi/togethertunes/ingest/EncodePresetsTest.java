package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.IngestJob.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** {@link EncodePresets}: the startup and admin checks, cached, and the fallbacks of a preset that fails. */
class EncodePresetsTest {
    @Test
    void matchingPresetsAreUsedAsConfigured() {
        List<X264Preset> checked = new ArrayList<>();
        EncodePresets p = new EncodePresets(preset -> {
            checked.add(preset);
            return true;
        }, () -> X264Preset.FASTER, () -> X264Preset.SLOWER);
        assertEquals(List.of(), p.checkConfigured());
        assertEquals(X264Preset.FASTER, p.quick());
        assertEquals(X264Preset.SLOWER, p.quality());
        assertEquals(X264Preset.FASTER, p.preset(Tier.QUICK));
        assertEquals(X264Preset.SLOWER, p.preset(Tier.QUALITY));
        assertEquals(List.of(X264Preset.FASTER, X264Preset.SLOWER), checked, "each checked once, then cached");
    }

    /** A forced mismatch (another x264 build): the tier falls back and the startup check says so. */
    @Test
    void aMismatchingPresetFallsBack() {
        EncodePresets slowBroken = new EncodePresets(preset -> preset != X264Preset.SLOW,
                () -> X264Preset.FASTER, () -> X264Preset.SLOW);
        List<String> problems = slowBroken.checkConfigured();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("ingest.qualityPreset slow doesn't give the reference avcC"), problems.get(0));
        assertTrue(problems.get(0).contains("quality encodes use faster instead"), problems.get(0));
        assertEquals(X264Preset.FASTER, slowBroken.quality(), "quality falls back to the quick preset");

        EncodePresets bothBroken = new EncodePresets(preset -> false, () -> X264Preset.MEDIUM, () -> X264Preset.SLOW);
        assertEquals(2, bothBroken.checkConfigured().size());
        assertEquals(X264Preset.REFERENCE, bothBroken.quick(), "the reference preset made the reference");
        assertEquals(X264Preset.REFERENCE, bothBroken.quality());

        // the reference preset itself is never re-checked here (ReferenceCard#presetMatches covers it)
        EncodePresets reference = new EncodePresets(preset -> {
            throw new AssertionError("checked " + preset);
        }, () -> X264Preset.VERYFAST, () -> X264Preset.VERYFAST);
        assertEquals(List.of(), reference.checkConfigured());
        assertEquals(X264Preset.VERYFAST, reference.quality());
    }

    @Test
    void anAdminCantPickAMismatchingPreset() {
        EncodePresets p = new EncodePresets(preset -> preset != X264Preset.VERYSLOW,
                () -> X264Preset.VERYFAST, () -> X264Preset.SLOW);
        p.validate("ingest.qualityPreset", X264Preset.SLOWER);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> p.validate("ingest.qualityPreset", X264Preset.VERYSLOW));
        assertTrue(e.getMessage().startsWith("ingest.qualityPreset: the x264 preset veryslow doesn't produce"), e.getMessage());
    }

    /** A check that can't run (no ffmpeg) counts as a mismatch for now and is tried again later. */
    @Test
    void aCheckThatFailsIsRetried() {
        AtomicReference<Boolean> ffmpegWorks = new AtomicReference<>(false);
        EncodePresets p = new EncodePresets(preset -> {
            if (!ffmpegWorks.get()) {
                throw new java.io.IOException("ffmpeg crashed");
            }
            return true;
        }, () -> X264Preset.FAST, () -> X264Preset.SLOW);
        assertEquals(X264Preset.REFERENCE, p.quick());
        assertFalse(p.matches(X264Preset.FAST));
        ffmpegWorks.set(true);
        assertTrue(p.matches(X264Preset.FAST));
        assertEquals(X264Preset.FAST, p.quick());
        assertEquals(X264Preset.SLOW, p.quality());
    }

    /** A setting that can't be read falls back like a mismatch. */
    @Test
    void unreadableSettings() {
        EncodePresets p = new EncodePresets(preset -> true, () -> {
            throw new java.sql.SQLException("db down");
        }, () -> {
            throw new java.sql.SQLException("db down");
        });
        assertEquals(X264Preset.REFERENCE, p.quick());
        assertEquals(X264Preset.REFERENCE, p.quality());
    }
}
