package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.IngestJob.Tier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The x264 presets ingest uses ({@code docs/library-and-ingest.md#presets}): the admin's
 * {@code ingest.quickPreset} and {@code ingest.qualityPreset}, each checked once against the
 * reference avcC before it is used (invariant 1).
 *
 * <p>The presets are built to give the reference card's avcC ({@link X264Preset}), but a different
 * x264 build might not, so a preset is only used after {@link #matches} confirmed it: an encode of
 * the reference card with that preset has byte-identical avcC. A configured preset that fails the
 * check is never used: the quick tier falls back to {@link X264Preset#REFERENCE} (which made the
 * reference), the quality tier to the quick tier's preset, with a warning in the server log
 * ({@link #checkConfigured} at startup); an admin picking such a preset gets an error instead
 * ({@link #validate}). Results are cached for the life of the server. The per-encode avcC check
 * ({@link IngestVerifier}) stays in any case.
 */
public final class EncodePresets {
    private static final System.Logger LOG = System.getLogger(EncodePresets.class.getName());

    /** Whether an encode with a preset gives the reference avcC. */
    @FunctionalInterface
    public interface Check {
        boolean matches(X264Preset preset) throws Exception;
    }

    /** Reads a preset setting. */
    @FunctionalInterface
    public interface Setting {
        X264Preset get() throws Exception;
    }

    private final Check check;
    private final Setting quick;
    private final Setting quality;
    private final Map<X264Preset, Boolean> results = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public EncodePresets(Check check, Setting quick, Setting quality) {
        this.check = Objects.requireNonNull(check);
        this.quick = Objects.requireNonNull(quick);
        this.quality = Objects.requireNonNull(quality);
    }

    /** Fixed presets that are taken as matching without a check (tests). */
    public static EncodePresets fixed(X264Preset quick, X264Preset quality) {
        return new EncodePresets(p -> true, () -> quick, () -> quality);
    }

    /** Presets from settings, checked by encoding {@code reference}'s card ({@link ReferenceCard#presetMatches}). */
    public static EncodePresets checkedAgainst(ReferenceCard reference, Tools tools, Path cardsDir, Setting quick,
                                               Setting quality) {
        Objects.requireNonNull(reference);
        return new EncodePresets(p -> reference.presetMatches(tools, cardsDir, p), quick, quality);
    }

    /**
     * Presets from settings, checked against the reference card {@code reference} gives at the
     * time (the current output encoding's, {@link OutputAssets}); call {@link #forget} when it
     * changes.
     */
    public static EncodePresets checkedAgainst(java.util.function.Supplier<ReferenceCard> reference, Tools tools,
                                               Path cardsDir, Setting quick, Setting quality) {
        Objects.requireNonNull(reference);
        return new EncodePresets(p -> {
            ReferenceCard card = reference.get();
            return card != null && card.presetMatches(tools, cardsDir, p);
        }, quick, quality);
    }

    /** Forgets the checks made so far (the reference card changed); each preset is checked again on next use. */
    public void forget() {
        results.clear();
        warned.clear();
    }

    /**
     * Whether {@code preset} gives the reference avcC (checked once, then cached). A check that
     * can't run (ffmpeg missing or failing) counts as a mismatch for now and is tried again next
     * time.
     */
    public boolean matches(X264Preset preset) {
        Boolean known = results.get(preset);
        if (known != null) {
            return known;
        }
        try {
            boolean ok = check.matches(preset);
            results.put(preset, ok);
            if (!ok) {
                LOG.log(System.Logger.Level.WARNING, "x264 preset " + preset + " doesn't give the reference card's"
                        + " avcC with this ffmpeg/x264 build; it won't be used for encodes");
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Could not check x264 preset " + preset + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * The preset of quick encodes: {@code ingest.quickPreset} if it {@link #matches}, else
     * {@link X264Preset#REFERENCE}.
     */
    public X264Preset quick() {
        X264Preset configured = read(quick, "ingest.quickPreset", X264Preset.REFERENCE);
        if (configured == X264Preset.REFERENCE || matches(configured)) {
            return configured;
        }
        warnOnce("ingest.quickPreset", configured, X264Preset.REFERENCE);
        return X264Preset.REFERENCE;
    }

    /**
     * The preset of quality encodes: {@code ingest.qualityPreset} if it {@link #matches}, else the
     * quick tier's preset.
     */
    public X264Preset quality() {
        X264Preset fallback = quick();
        X264Preset configured = read(quality, "ingest.qualityPreset", fallback);
        if (configured == fallback || matches(configured)) {
            return configured;
        }
        warnOnce("ingest.qualityPreset", configured, fallback);
        return fallback;
    }

    /** {@link #quick()} or {@link #quality()}. */
    public X264Preset preset(Tier tier) {
        return tier == Tier.QUALITY ? quality() : quick();
    }

    /**
     * Refuses a preset an admin wants to set that doesn't give the reference avcC.
     *
     * @throws IllegalArgumentException with a message for the admin
     */
    public void validate(String setting, X264Preset preset) {
        if (preset != X264Preset.REFERENCE && !matches(preset)) {
            throw new IllegalArgumentException(setting + ": the x264 preset " + preset + " doesn't produce the same"
                    + " H.264 parameter sets (avcC) as the reference card with this server's ffmpeg/x264, so it"
                    + " can't be used; choose another preset");
        }
    }

    /**
     * Checks both configured presets (startup) and returns a line per problem for the server log;
     * empty when both are usable.
     */
    public List<String> checkConfigured() {
        List<String> problems = new ArrayList<>();
        X264Preset q = read(quick, "ingest.quickPreset", X264Preset.REFERENCE);
        if (q != X264Preset.REFERENCE && !matches(q)) {
            problems.add("ingest.quickPreset " + q + " doesn't give the reference avcC with this ffmpeg/x264;"
                    + " quick encodes use " + X264Preset.REFERENCE + " instead");
            warned.add("ingest.quickPreset/" + q);
        }
        X264Preset fallback = quick();
        X264Preset hq = read(quality, "ingest.qualityPreset", fallback);
        if (hq != fallback && !matches(hq)) {
            problems.add("ingest.qualityPreset " + hq + " doesn't give the reference avcC with this ffmpeg/x264;"
                    + " quality encodes use " + fallback + " instead");
            warned.add("ingest.qualityPreset/" + hq);
        }
        return problems;
    }

    private void warnOnce(String setting, X264Preset configured, X264Preset used) {
        if (warned.add(setting + "/" + configured)) {
            LOG.log(System.Logger.Level.WARNING, setting + " " + configured + " doesn't give the reference avcC;"
                    + " using " + used + " instead");
        }
    }

    private static X264Preset read(Setting setting, String name, X264Preset fallback) {
        try {
            X264Preset p = setting.get();
            return p == null ? fallback : p;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read " + name + ": " + e.getMessage());
            return fallback;
        }
    }
}
