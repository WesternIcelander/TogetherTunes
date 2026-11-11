package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.fonts.CardFonts;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.Tier;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ingest job steps ({@code docs/library-and-ingest.md#ingest-job-states}), as the
 * {@link IngestQueue.Handler} of the ingest queue:
 *
 * <pre>probe → (download) → loudnorm pass 1 → encode → extract audio.m4a → card render+encode → index → verify → publish</pre>
 *
 * <p><b>Where the source comes from.</b> An {@code upload} job reads the file that the upload
 * code placed in the item's staging directory, {@code <data>/tmp/ingest/<itemId>/}, named
 * {@code source} or {@code source.<ext>}; a {@code cover.<ext>} next to it (e.g. a YouTube
 * thumbnail) is used as artwork instead of the cover embedded in the source. A {@code youtube}
 * or {@code refetch} job downloads {@code https://www.youtube.com/watch?v=<youtube_id>} with
 * yt-dlp into its scratch directory first, together with the video's best thumbnail, which becomes
 * the cover. yt-dlp gets {@code --cookies <file>} when the {@code ytdlp.cookiesFile} setting names
 * a readable file ({@link #cookiesArgs}).
 *
 * <p><b>Re-encodes.</b> A {@code reencode} job (queued at startup for every ready item encoded for
 * another output profile, {@link IngestJobs#enqueueReencodes}) encodes a ready item again for the
 * current profile: from its kept original if it has one (every item ingested since originals are
 * kept for all items); otherwise (items made before that) a YouTube item by downloading it again
 * (if that fails, from its old files, so the item isn't stuck on the old profile), and anything
 * else from its old {@code mezz.mp4}, or {@code audio.m4a} for an audio item. Old files were
 * normalised when first ingested, so they are encoded without
 * loudnorm and the stored {@code loudness_lufs} is kept. The item's {@code cover.jpg} is reused as
 * the staged cover. The item stays {@code ready} with its old files until the publish switches to
 * the new ones. A job for an item that is no longer ready, or that is already encoded for the current
 * profile, ends without doing anything. The duration limit isn't applied to re-encodes.
 *
 * <p><b>Steps.</b> ffprobe decides the item kind: a real video stream makes a video item
 * ({@code mezz.mp4} + {@code audio.m4a} copied out of it + {@code card.mp4}); otherwise it is an
 * audio item ({@code audio.m4a} + {@code card.mp4}, no {@code mezz.mp4}). Files without audio or
 * longer than {@code ingest.maxDurationSeconds} are rejected. Loudnorm pass 1 measures the source;
 * the encode applies pass 2 with the shared profile arguments ({@link EncodeProfile}). The card
 * shows the cover (embedded picture or staged cover, converted to {@code cover.jpg}), title,
 * artist and album, and is checked against the reference avcC. The index is built and verified
 * ({@link IngestVerifier}).
 *
 * <p><b>Publish</b> ({@link MediaVersions}). Everything is written into a new directory
 * {@code <data>/media/.ingest-<jobId>} (same file system as the item directory). The publish moves
 * a newly kept original to {@code media/<itemId>/original.<ext>}, renames the rest to the item's
 * next version directory {@code media/<itemId>/v<N>/}, and then switches the item row to it
 * ({@code media_version}, together with its kind, duration, profile, loudness and original, in one
 * update); the queue marks it {@code ready}. It never waits: new plays use the new version from
 * then on, a web player in the middle of the item keeps fetching the old version's URL, and the
 * program output keeps the files it opened. The old version is handed to {@link MediaVersions} to
 * be deleted once nobody can still be playing it. A {@code v<N>} left by a crash before the
 * switch is deleted at the next start ({@link MediaVersions#cleanUpAtStartup}); half-built
 * {@code .ingest-*} directories by {@link #recoverDirectories}. The staging directory is deleted
 * after a successful publish.
 *
 * <p><b>Originals.</b> Every item keeps its source as {@code original.<ext>} in the item directory
 * ({@link Originals}): the upload (hard-linked from the staging directory), or the merged YouTube
 * download (moved out of the scratch directory), with {@code original_file} and
 * {@code original_codec}. Only an audio upload's original gets {@code original_web} and
 * {@code original_gain_db} (the web client may play it); a video item's or a YouTube item's
 * original only feeds re-encodes. A re-encode from the original keeps it; one from the previous
 * encode (no original) clears the {@code original_*} columns.
 *
 * <p><b>Presets</b> ({@code docs/library-and-ingest.md#presets}). Each job picks its x264 preset
 * when it starts ({@link #tier}): the quick preset when a room wants the item now, the quality
 * preset for an item that is only wanted for a library. A {@code quality} job re-encodes a ready
 * item that was made with the quick preset, from its original (a YouTube item without one is
 * downloaded again; an item with neither is left as it is, since re-encoding its quick encode
 * would only lose quality). The preset used and whether it was the quality encode are recorded
 * on the item ({@code encode_preset}, {@code encode_quality}).
 *
 * <p><b>Trims</b> ({@code docs/library-and-ingest.md#trim}). A video trim (the edit settings,
 * {@link EditSettings}) is applied by every encode from an original or a download
 * ({@code -ss}/{@code -t}); a {@code trim} job first tries a smart re-encode ({@link TrimPlan},
 * {@link #smartTrim}) that keeps the current version's GOPs where it can, and falls back to a full
 * re-encode from the original. A trim's publish can be undone. An audio trim
 * ({@code docs/library-and-ingest.md#audio-trim}) is applied to an audio item's {@code audio.m4a}
 * (the original then isn't played in browsers); for a video it makes the separate audio-only file
 * {@link #AUDIO_ONLY} with its index ({@link #audioOnlyVariant}), and a trim job changing only that
 * keeps the rest of the current version ({@link #audioOnlyTrim}).
 */
public final class IngestPipeline implements IngestQueue.Handler {
    private static final System.Logger LOG = System.getLogger(IngestPipeline.class.getName());

    public static final String MEZZ = "mezz.mp4";
    public static final String AUDIO = "audio.m4a";
    public static final String CARD = "card.mp4";
    public static final String COVER = "cover.jpg";
    /**
     * A video item's audio-only file, made from its audio trim and played only in audio-only rooms
     * ({@code docs/library-and-ingest.md#audio-trim}); without an audio trim there is none and
     * {@link #AUDIO} is used.
     */
    public static final String AUDIO_ONLY = "audio-only.m4a";
    /** The index of {@link #AUDIO_ONLY} with {@link #CARD} as its video, as an audio item's {@code index.bin}. */
    public static final String AUDIO_ONLY_INDEX = "index-audio-only.bin";

    /** Base name of the staged source file of an upload ({@code source} or {@code source.<ext>}). */
    public static final String SOURCE_NAME = "source";
    /** Base name of a staged cover image ({@code cover.<ext>}). */
    public static final String COVER_NAME = "cover";

    static final String WORK_PREFIX = ".ingest-";
    /**
     * Prefix of an item directory moved aside by the publish of releases before media versions
     * existed ({@code .old-<itemId>-<jobId>}); only {@link #recoverDirectories} still handles it.
     */
    static final String OLD_PREFIX = ".old-";
    /**
     * Prefix of a directory moved aside to be deleted ({@link #moveAside}): a purged item's
     * {@code media/<itemId>} or staging directory. Leftovers are deleted by {@link #recoverDirectories}.
     */
    public static final String PURGE_PREFIX = ".purge-";
    /** Longest edge of {@code cover.jpg}. */
    static final int COVER_MAX = 1200;

    /** Share of the progress bar for a YouTube download; the rest is scaled into what's left. */
    static final double DOWNLOAD_SHARE = 0.35;
    static final double MEASURE_SHARE = 0.15;
    static final double ENCODE_SHARE = 0.75;
    /** The steps a job reports for people ({@link IngestQueue.Context#stage}). */
    public static final String STEP_DOWNLOAD = "download";
    public static final String STEP_ENCODE = "encode";
    public static final String STEP_FINISH = "finish";

    private static final Pattern YTDLP_PERCENT = Pattern.compile("^\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");

    private final Tools tools;
    private final DataDir dataDir;
    private final IngestItems items;
    private volatile Supplier<ReferenceCard> reference;
    private volatile java.util.function.IntSupplier maxDurationSec;
    private final Supplier<String> cookiesFile;
    private final EncodePresets presets;
    private volatile MediaVersions versions;

    /** Without a cookies file. */
    public IngestPipeline(Tools tools, DataDir dataDir, IngestItems items, ReferenceCard reference,
                          int maxDurationSec) {
        this(tools, dataDir, items, reference, maxDurationSec, () -> null);
    }

    /**
     * @param reference      the reference card of the configured output profile; null if it could
     *                       not be made at startup (then every job fails with a clear message)
     * @param maxDurationSec {@code ingest.maxDurationSeconds}
     * @param cookiesFile    the {@code ytdlp.cookiesFile} setting, read for every download (null or
     *                       blank = none)
     */
    public IngestPipeline(Tools tools, DataDir dataDir, IngestItems items, ReferenceCard reference,
                          int maxDurationSec, Supplier<String> cookiesFile) {
        this(tools, dataDir, items, reference, maxDurationSec, cookiesFile,
                EncodePresets.fixed(X264Preset.REFERENCE, X264Preset.REFERENCE));
    }

    /** @param presets the quick and quality x264 presets ({@code ingest.quickPreset}, {@code ingest.qualityPreset}) */
    public IngestPipeline(Tools tools, DataDir dataDir, IngestItems items, ReferenceCard reference,
                          int maxDurationSec, Supplier<String> cookiesFile, EncodePresets presets) {
        this.presets = Objects.requireNonNull(presets);
        this.tools = Objects.requireNonNull(tools);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.items = Objects.requireNonNull(items);
        this.reference = () -> reference;
        this.maxDurationSec = () -> maxDurationSec;
        this.cookiesFile = Objects.requireNonNull(cookiesFile);
    }

    /**
     * Where a publish hands the version it replaced, to be deleted once nobody can still be playing
     * it. Without one, old versions stay until the clean-up at the next start.
     */
    public void mediaVersions(MediaVersions versions) {
        this.versions = versions;
    }

    /**
     * Where the reference card of the current output encoding comes from ({@link OutputAssets}),
     * read once when each job starts; replaces the fixed one given to the constructor. A job that
     * started before the encoding changed finishes for the old one (its item then gets a
     * {@code reencode} job when it finishes, {@link IngestJobs#currentEncoding}).
     */
    public void reference(Supplier<ReferenceCard> reference) {
        this.reference = Objects.requireNonNull(reference);
    }

    /** Where {@code ingest.maxDurationSeconds} comes from (the admin setting), read for every job. */
    public void maxDurationSeconds(java.util.function.IntSupplier maxDurationSec) {
        this.maxDurationSec = Objects.requireNonNull(maxDurationSec);
    }

    /** The presets this pipeline encodes with. */
    public EncodePresets presets() {
        return presets;
    }

    /** The staging directory of an item's upload: {@code <data>/tmp/ingest/<itemId>}. */
    public static Path stagingDir(DataDir dataDir, String itemId) {
        return dataDir.tmp().resolve("ingest").resolve(checkId(itemId));
    }

    /**
     * An item's directory: {@code <data>/media/<itemId>}, holding its kept original and its
     * version directories ({@link MediaVersions#versionDir}).
     */
    public static Path itemDir(DataDir dataDir, String itemId) {
        return dataDir.media().resolve(checkId(itemId));
    }

    /** A job's scratch directory (downloads, intermediates): {@code <data>/tmp/jobs/<jobId>}. */
    static Path scratchDir(DataDir dataDir, String jobId) {
        return dataDir.tmp().resolve("jobs").resolve(checkId(jobId));
    }

    private static String checkId(String id) {
        if (id == null || id.isEmpty() || id.startsWith(".") || id.contains("/") || id.contains("\\")) {
            throw new IllegalArgumentException("bad id: " + id);
        }
        return id;
    }

    @Override
    public void run(IngestQueue.Context ctx) throws Exception {
        IngestJob job = ctx.job();
        JobLog log = ctx.log();
        ReferenceCard reference = this.reference.get();
        if (reference == null) {
            throw new IngestFailure("Ingest is unavailable: the server could not create its reference card at startup"
                    + " (are ffmpeg and ffprobe installed?)");
        }
        IngestItems.Item item = items.get(job.itemId())
                .orElseThrow(() -> new IngestFailure("The item no longer exists"));
        boolean cardOnly = false;
        if (job.kind() == Kind.REENCODE) {
            if (!"ready".equals(item.status())) {
                log.append("[ingest] the item is " + item.status() + "; nothing to re-encode");
                return;
            }
            if (reference.encoding().id().equals(item.outputProfile())) {
                if (items.cardVersion(item.id()) >= CardEncoder.CARD_VERSION && cardFontsCurrent(item)) {
                    log.append("[ingest] the item is already encoded for " + reference.encoding().id());
                    return;
                }
                cardOnly = true;
            }
        }
        if (job.kind() == Kind.QUALITY) {
            if (!"ready".equals(item.status())) {
                log.append("[ingest] the item is " + item.status() + "; nothing to re-encode");
                return;
            }
            if (item.encodeQuality()) {
                log.append("[ingest] the item already has its quality encode (" + item.encodePreset() + ")");
                return;
            }
            if ("audio".equals(item.kind())) {
                log.append("[ingest] an audio item has no video to re-encode");
                items.markQuality(item.id());
                return;
            }
            X264Preset quality = presets.quality();
            if (quality.id().equals(item.encodePreset()) && reference.encoding().id().equals(item.outputProfile())) {
                log.append("[ingest] the item was already encoded with the quality preset " + quality);
                items.markQuality(item.id());
                return;
            }
            if (sourceKind(job.kind(), item, itemDir(dataDir, item.id())) == SourceKind.PREVIOUS) {
                log.append("[ingest] the item has no original to re-encode from (and isn't from YouTube);"
                        + " re-encoding its quick encode would only lose quality, so it stays as it is");
                items.markQuality(item.id());
                return;
            }
        }
        if (job.kind() == Kind.TRIM) {
            if (!"ready".equals(item.status())) {
                log.append("[ingest] the item is " + item.status() + "; nothing to trim");
                return;
            }
            if (!"video".equals(item.kind()) && EditSettings.parts(job.editJson()).contains(EditSettings.PART_VIDEO)) {
                throw new IngestFailure("Only video items have a video trim");
            }
            String original = item.originalFile();
            if (original == null || !isPlainName(original) || !Files.isRegularFile(itemDir(dataDir, item.id()).resolve(original))) {
                throw new IngestFailure("The item has no original file to trim from");
            }
        }
        Path scratch = scratchDir(dataDir, job.id());
        Path work = dataDir.media().resolve(WORK_PREFIX + job.id());
        deleteTree(scratch);
        deleteTree(work);
        Files.createDirectories(scratch);
        Files.createDirectories(work);
        try {
            String currentJson = items.editJson(item.id(), item.mediaVersion());
            if (job.kind() == Kind.TRIM) {
                // the trim asked for, on top of the current settings (an undo meanwhile may have changed them)
                String targetJson = EditSettings.applyJob(currentJson, job.editJson());
                EditSettings current = EditSettings.parse(currentJson);
                EditSettings target = EditSettings.parse(targetJson);
                if (current.sameVideoTrim(target) && current.sameAudioTrim(target)) {
                    log.append("[ingest] the item already has these trims; nothing to do");
                    return;
                }
                if ("video".equals(item.kind())) {
                    if (current.sameVideoTrim(target)
                            ? audioOnlyTrim(ctx, item, work, reference, target, targetJson)
                            : smartTrim(ctx, item, scratch, work, reference, current, target, targetJson)) {
                        return;
                    }
                }
                run(ctx, item, scratch, work, reference, targetJson, true);
            } else if (!cardOnly || !remakeCard(ctx, item, work, reference)) {
                run(ctx, item, scratch, work, reference, currentJson, false);
            }
        } finally {
            deleteQuietly(scratch);
            deleteQuietly(work);
        }
    }

    /** Where a job's source file comes from. */
    enum SourceKind {
        /** The upload in the staging directory. */
        STAGED,
        /** Downloaded with yt-dlp. */
        DOWNLOAD,
        /** The item's kept original (re-encode). */
        ORIGINAL,
        /** The item's previous encode, {@code mezz.mp4} or {@code audio.m4a} (re-encode). */
        PREVIOUS
    }

    /**
     * Where a job takes its source from. For a re-encode: an item with its original file present
     * re-encodes from it; without one (made before originals were kept for every item), a YouTube
     * item is downloaded again, anything else re-encodes from its old files.
     */
    static SourceKind sourceKind(Kind kind, IngestItems.Item item, Path itemDir) {
        return switch (kind) {
            case UPLOAD -> SourceKind.STAGED;
            case YOUTUBE, REFETCH -> SourceKind.DOWNLOAD;
            case REENCODE, QUALITY, TRIM -> {
                String original = item.originalFile();
                if (original != null && isPlainName(original) && Files.isRegularFile(itemDir.resolve(original))) {
                    yield SourceKind.ORIGINAL;
                }
                if (item.fromYoutube() && item.youtubeId() != null) {
                    yield SourceKind.DOWNLOAD;
                }
                yield SourceKind.PREVIOUS;
            }
        };
    }

    private static boolean isPlainName(String name) {
        return !name.isEmpty() && !name.startsWith(".") && !name.contains("/") && !name.contains("\\");
    }

    /**
     * The previous encode to re-encode from: {@code mezz.mp4}, else {@code audio.m4a}, in the
     * current version's directory.
     */
    static Path previousSource(Path versionDir) throws IngestFailure {
        for (String name : List.of(MEZZ, AUDIO)) {
            Path f = versionDir.resolve(name);
            if (Files.isRegularFile(f)) {
                return f;
            }
        }
        throw new IngestFailure("The item has no files to re-encode from");
    }

    /**
     * The preset tier of a job, decided when it starts: a {@code quality} job uses the quality
     * preset; any other job uses it only if the item is wanted for a library and no room wants it
     * now (it is queued, suggested or playing nowhere): a new item whose job was queued with the
     * quality tier (added straight to a library), or a {@code reencode} of an item in a library.
     * Everything else (requests in a room, guests, playlists, jobs queued before tiers existed)
     * uses the quick preset.
     */
    Tier tier(IngestJob job, IngestItems.Item item) throws java.sql.SQLException {
        return switch (job.kind()) {
            case QUALITY -> Tier.QUALITY;
            case REENCODE, TRIM -> items.inLibrary(item.id()) && !items.wantedInRoom(item.id()) ? Tier.QUALITY : Tier.QUICK;
            default -> job.tier() == Tier.QUALITY && !items.wantedInRoom(item.id()) ? Tier.QUALITY : Tier.QUICK;
        };
    }

    /**
     * The full encode.
     *
     * @param editJson the edit settings the new version is made with (a video trim is applied when
     *                 encoding from an original or a download; a previous encode already has it)
     * @param undoable whether the switch is a person's change (a trim), which Undo can take back
     */
    private void run(IngestQueue.Context ctx, IngestItems.Item item, Path scratch, Path work, ReferenceCard reference,
                     String editJson, boolean undoable)
            throws Exception {
        IngestJob job = ctx.job();
        JobLog log = ctx.log();
        OutputEncoding profile = reference.encoding();
        boolean reencode = job.kind().replacesReadyFiles();
        Tier tier = tier(job, item);
        X264Preset preset = presets.preset(tier);
        boolean qualityEncode = preset == presets.quality();
        log.append("[ingest] x264 preset " + preset + " (" + tier.wire() + (qualityEncode && tier == Tier.QUICK
                ? ", the same as the quality preset" : "") + ")");
        Path staging = stagingDir(dataDir, item.id());
        Path itemDir = itemDir(dataDir, item.id());
        Path currentDir = MediaVersions.versionDir(itemDir, item.mediaVersion());
        SourceKind sourceKind = sourceKind(job.kind(), item, itemDir);
        if (reencode) {
            log.append("[ingest] " + (job.kind() == Kind.QUALITY ? "quality re-encode"
                    : job.kind() == Kind.TRIM ? "trim: full re-encode" : "re-encoding")
                    + " for " + profile.id() + " (was " + item.outputProfile() + ", preset "
                    + (item.encodePreset() == null ? "unknown" : item.encodePreset()) + ") from "
                    + switch (sourceKind) {
                        case DOWNLOAD -> "a new download";
                        case ORIGINAL -> "the original " + item.originalFile();
                        default -> "the previous encode";
                    });
        }
        boolean download = sourceKind == SourceKind.DOWNLOAD;
        double base = download ? DOWNLOAD_SHARE : 0;
        double rest = 1 - base;

        // (download) / staged source
        Path source;
        switch (sourceKind) {
            case DOWNLOAD -> {
                step(log, "download");
                ctx.stage(STEP_DOWNLOAD, 0, base);
                try {
                    source = download(item, scratch, log, range(ctx, 0, base));
                } catch (IngestFailure | ToolException | IOException e) {
                    if (job.kind() != Kind.REENCODE || Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    source = previousSource(currentDir);
                    sourceKind = SourceKind.PREVIOUS;
                    log.append("[ingest] the download failed (" + e.getMessage() + "); re-encoding from the previous "
                            + source.getFileName() + " instead");
                }
            }
            case ORIGINAL -> source = itemDir.resolve(item.originalFile());
            case PREVIOUS -> source = previousSource(currentDir);
            default -> {
                source = staged(staging, SOURCE_NAME);
                if (source == null) {
                    throw new IngestFailure("The uploaded file is missing");
                }
            }
        }
        boolean fromPrevious = sourceKind == SourceKind.PREVIOUS;
        // probe, loudness measurement and encode read as one "encode" step for people
        ctx.stage(STEP_ENCODE, base, base + rest * (MEASURE_SHARE + ENCODE_SHARE));
        // the item's own cover for a re-encode; the downloaded thumbnail for a YouTube item
        Path stagedCover = reencode ? existingFile(currentDir.resolve(COVER)) : null;
        if (stagedCover == null) {
            stagedCover = staged(sourceKind == SourceKind.DOWNLOAD ? scratch : staging, COVER_NAME);
        }

        // probe
        step(log, "probe");
        MediaProbe probe = MediaProbe.probe(tools, source, log);
        checkProbe(probe, !reencode);
        boolean video = probe.isVideo();
        log.append("[ingest] " + (video ? "video" : "audio") + " item: audio stream " + probe.audio().index() + " ("
                + probe.audio().codec() + ")" + (video ? ", video stream " + probe.video().index() + " ("
                + probe.video().codec() + ")" : "") + (probe.cover() != null ? ", cover stream " + probe.cover().index() : "")
                + ", duration " + seconds(probe.durationUs()));
        long expectedUs = probe.durationUs() > 0 ? probe.durationUs() : probe.audioEndUs();
        long sourceAudioEndUs = probe.audioEndUs() > 0 ? probe.audioEndUs() : probe.readAudioEndUs(tools, source, log);
        // the video trim, on the original's timeline (not for a previous encode: it already has it)
        EditSettings edit = EditSettings.parse(editJson);
        List<String> range = List.of();
        // a full encode has the normal 60-frame first GOP, whatever the version it replaces had
        String publishJson = EditSettings.withFirstGop(editJson, EncodeProfile.GOP_FRAMES);
        long originalAudioEndUs = sourceAudioEndUs;
        // an audio item's audio trim applies to its audio.m4a; a video's makes its audio-only file (below)
        boolean audioTrimApplied = !video && !fromPrevious && edit.audioTrimmed();
        if (!fromPrevious && (video ? edit.videoTrimmed() : edit.audioTrimmed())) {
            long[] r = video ? trimRange(edit, probe, sourceAudioEndUs)
                    : trimRange(edit.audioStartMs(), edit.audioEndMs(), probe, sourceAudioEndUs);
            range = rangeArgs(r[0], r[1]);
            expectedUs = r[1] - r[0];
            sourceAudioEndUs = Math.min(sourceAudioEndUs, r[1]) - r[0];
            publishJson = EditSettings.withOriginalMs(publishJson, r[2] / 1000);
            log.append("[ingest] " + (video ? "video" : "audio") + " trim: " + seconds(r[0]) + " to " + seconds(r[1])
                    + " of the original (" + seconds(r[2]) + ")");
        }
        long lengthUs = expectedUs;

        // loudnorm pass 1 (not for a previous encode: its audio was normalised when first ingested)
        double measureFrom = base;
        double measureTo = base + rest * MEASURE_SHARE;
        DoubleConsumer measureProgress = range(ctx, measureFrom, measureTo);
        long[] decodedUs = {-1};
        Loudness.Measurement loudness;
        if (fromPrevious) {
            loudness = NOT_MEASURED;
            log.append("[ingest] the previous encode is already normalised; encoding without loudnorm");
        } else {
            step(log, "loudnorm pass 1");
            loudness = Loudness.measure(tools, range, source.toString(), probe.audio().map(), log, p -> {
                if (p.outTimeUs() > decodedUs[0]) {
                    decodedUs[0] = p.outTimeUs();
                }
                measureProgress.accept(p.fraction(lengthUs));
            });
        }
        if (fromPrevious) {
            // nothing measured
        } else if (loudness.usable()) {
            log.append(String.format(Locale.ROOT, "[ingest] measured %.2f LUFS, %.2f dBTP, LRA %.2f LU",
                    loudness.inputI(), loudness.inputTp(), loudness.inputLra()));
        } else {
            log.append("[ingest] the loudness could not be measured (silence?); encoding without normalisation");
        }

        // encode
        step(log, "encode");
        double encodeTo = measureTo + rest * ENCODE_SHARE;
        DoubleConsumer encodeProgress = range(ctx, measureTo, encodeTo);
        Path mezz = work.resolve(MEZZ);
        Path audio = work.resolve(AUDIO);
        List<String> encode = video
                ? videoEncodeArgs(range, source, probe, loudness, profile, preset, mezz)
                : audioEncodeArgs(range, source, probe, loudness, audio);
        tools.ffmpeg(encode).log(log).ffmpegProgress(p -> encodeProgress.accept(p.fraction(lengthUs))).run();
        double after = encodeTo;
        double stepShare = (1 - encodeTo) / 5;
        ctx.stage(STEP_FINISH, encodeTo, 1);

        // extract audio.m4a
        if (video) {
            step(log, "extract audio.m4a");
            tools.ffmpeg(extractAudioArgs(mezz, audio)).log(log).run();
        }
        ctx.progress(after += stepShare);

        // card render + encode
        step(log, "card");
        Path cover = work.resolve(COVER);
        BufferedImage artwork = cover(source, probe, stagedCover, cover, log);
        CardRenderer renderer = new CardRenderer(profile.profile());
        BufferedImage card = renderer.renderItem(
                new CardRenderer.ItemCard(item.title(), item.artist(), item.album(), artwork));
        String cardFonts = CardRenderer.fontKey(renderer.fonts(), item.title(), item.artist(), item.album());
        Path cardFile = work.resolve(CARD);
        CardEncoder.encode(tools, card, cardFile, reference, log);
        ctx.progress(after += stepShare);

        // index
        step(log, "index");
        Path videoFile = video ? mezz : cardFile;
        SampleIndex index = IndexBuilder.build(tools, profile, videoFile, audio, work.resolve(SampleIndex.FILE_NAME), log);
        ctx.progress(after += stepShare);

        // verify
        step(log, "verify");
        long matchedUs = IngestVerifier.verify(index, reference, sourceAudioEndUs, decodedUs[0],
                videoFile.getFileName().toString());
        long durationUs = IngestVerifier.audioDurationUs(index);
        log.append("[ingest] verified: audio " + seconds(durationUs) + " (source " + seconds(sourceAudioEndUs)
                + (matchedUs != sourceAudioEndUs ? ", decoded " + seconds(matchedUs) : "")
                + "), avcC matches the " + profile.id() + " reference, " + index.track(SampleIndex.Kind.VIDEO).sampleCount()
                + " frames with an IDR every 2 s");

        // a video's audio-only file, from its audio trim (docs/library-and-ingest.md#audio-trim)
        Long audioOnlyMs = null;
        if (video && edit.audioTrimmed()) {
            if (fromPrevious) {
                log.append("[ingest] the audio trim can't be applied without the original; the audio-only file is"
                        + " audio.m4a now");
                publishJson = EditSettings.withAudioTrim(publishJson, null, null);
            } else {
                audioOnlyMs = audioOnlyVariant(log, source, probe, originalAudioEndUs, edit, work, reference);
                publishJson = EditSettings.withOriginalMs(publishJson,
                        Math.max(probe.durationUs(), originalAudioEndUs) / 1000);
            }
        }
        ctx.progress(after += stepShare);

        // original: every source except a previous encode is kept; only an audio upload's may be played
        Originals.Original original = null;
        if (sourceKind != SourceKind.PREVIOUS && (video || !"upload".equals(item.source()))) {
            original = Originals.keptOnly(probe, source.getFileName().toString());
            if (sourceKind == SourceKind.DOWNLOAD) {
                Files.move(source, work.resolve(original.file())); // the scratch copy isn't needed any more
            } else {
                linkOrCopy(source, work.resolve(original.file()));
            }
            log.append("[ingest] kept the original as " + original.file() + " (" + original.codec()
                    + ", " + Files.size(work.resolve(original.file())) + " bytes) for re-encodes");
        } else if (sourceKind != SourceKind.PREVIOUS && audioTrimApplied) {
            // a trimmed audio upload plays its audio.m4a, not the original (docs/library-and-ingest.md#audio-trim)
            original = Originals.keptOnly(probe, source.getFileName().toString());
            linkOrCopy(source, work.resolve(original.file()));
            log.append("[ingest] kept the original as " + original.file() + " (" + original.codec()
                    + "); not played in browsers while the item is trimmed");
        } else if (sourceKind != SourceKind.PREVIOUS) {
            long originalUs = probe.durationUs() > 0 ? probe.durationUs() : sourceAudioEndUs;
            // the original is played at the loudness audio.m4a actually has (loudnorm may have compressed it)
            Loudness.Measurement output = null;
            if (loudness.usable()) {
                output = Loudness.measure(tools, audio.toString(), "0:a:0", null, null);
                log.append(String.format(Locale.ROOT, "[ingest] audio.m4a measures %.2f LUFS, %.2f dBTP",
                        output.inputI(), output.inputTp()));
            }
            original = Originals.describe(probe, source.getFileName().toString(), loudness, output, originalUs, durationUs);
            linkOrCopy(source, work.resolve(original.file()));
            String mismatch = Originals.loudnessMismatch(loudness, output);
            log.append("[ingest] kept the original as " + original.file() + " (" + original.codec() + ", "
                    + (original.web() ? "web-playable" : "not web-playable") + ", duration " + seconds(originalUs)
                    + (original.gainDb() == null ? "" : String.format(Locale.ROOT, ", gain %.2f dB", original.gainDb()))
                    + ")" + (mismatch == null ? "" : "; not played in browsers: " + mismatch));
        }

        // publish: a new version, then the switch in the database; never waits for a play to end
        step(log, "publish");
        Double lufs = fromPrevious ? item.loudnessLufs() : loudness.usable() ? round2(loudness.inputI()) : null;
        Originals.Original kept = original;
        boolean hadOld = MediaVersions.exists(itemDir, item.mediaVersion());
        // A re-encode made by the system keeps the previous version for the undo window too, but
        // without Undo (docs/library-and-ingest.md#undo); it is made with the current edit settings.
        // A trim is a person's change: undoable, with its new settings.
        IngestItems.VersionChange change = new IngestItems.VersionChange(hadOld ? item.mediaVersion() : null,
                undoable, publishJson, System.currentTimeMillis());
        // an audio item has no video, so its files are as good as they get with any preset
        Long variantMs = audioOnlyMs;
        int version = publish(work, itemDir, item.mediaVersion(), original == null ? null : original.file(),
                v -> items.published(item.id(), video, durationUs / 1000, profile, preset, qualityEncode || !video,
                        lufs, kept, v, change, variantMs, CardEncoder.CARD_VERSION, cardFonts));
        log.append("[ingest] published as version " + version + (hadOld ? "; version " + item.mediaVersion()
                + " is kept until nobody can still be playing it (and for the undo window)" : ""));
        retire(item, itemDir, hadOld, original, durationUs / 1000);
        if (!reencode) {
            deleteQuietly(staging);
        }
        ctx.progress(1);
    }

    // ------------------------------------------------------------------ trim

    /** The shortest trim: 1 s of the original. */
    public static final long MIN_TRIM_MS = 1000;

    /**
     * Where a video trim cuts the original, in microseconds: {@code {start, end, originalLength}},
     * the end clamped to the original's length (its container duration, or where its audio ends).
     *
     * @throws IngestFailure if less than {@link #MIN_TRIM_MS} would be left
     */
    static long[] trimRange(EditSettings edit, MediaProbe probe, long sourceAudioEndUs) throws IngestFailure {
        return trimRange(edit.videoStartMs(), edit.videoEndMs(), probe, sourceAudioEndUs);
    }

    /** {@link #trimRange(EditSettings, MediaProbe, long)} for any range (the video or the audio trim). */
    static long[] trimRange(long startMs, Long endMs, MediaProbe probe, long sourceAudioEndUs) throws IngestFailure {
        long originalUs = Math.max(probe.durationUs(), sourceAudioEndUs);
        if (originalUs <= 0) {
            throw new IngestFailure("The length of the original is unknown, so it can't be trimmed");
        }
        long startUs = startMs * 1000;
        long endUs = endMs == null ? originalUs : Math.min(endMs * 1000, originalUs);
        if (endUs - startUs < MIN_TRIM_MS * 1000) {
            throw new IngestFailure("The trim leaves less than " + MIN_TRIM_MS / 1000 + " s of the original (which is "
                    + seconds(originalUs) + " long)");
        }
        return new long[] {startUs, endUs, originalUs};
    }

    /** The input options that read {@code [startUs, endUs)} of a file (accurate seek). */
    static List<String> rangeArgs(long startUs, long endUs) {
        return List.of("-ss", secondsArg(startUs), "-t", secondsArg(endUs - startUs));
    }

    private static String secondsArg(long us) {
        return String.format(Locale.ROOT, "%d.%06d", us / 1_000_000, us % 1_000_000);
    }

    /**
     * A smart trim ({@code docs/library-and-ingest.md#trim}, {@link TrimPlan}): keeps the current
     * version's GOPs where the new range allows it, encodes only what is new from the original,
     * re-encodes the whole audio track from the original for the new range (loudness measured on
     * it), splices, indexes, verifies and publishes. Returns false (nothing published, the work
     * and scratch directories emptied) when a full re-encode is needed instead: nothing of the
     * current encode can be kept, the current version is for another encoding or unreadable, or
     * anything about the splice fails. A new start off the current keyframes is encoded from the
     * original up to the next one as a short first GOP (invariant 2); the published version records
     * its first GOP in its edit settings ({@link EditSettings#withFirstGop}).
     *
     * @throws IngestFailure if the trim itself is impossible (too short); a full re-encode would fail too
     */
    private boolean smartTrim(IngestQueue.Context ctx, IngestItems.Item item, Path scratch, Path work,
                              ReferenceCard reference, EditSettings current, EditSettings target, String targetJson)
            throws Exception {
        JobLog log = ctx.log();
        OutputEncoding profile = reference.encoding();
        Path itemDir = itemDir(dataDir, item.id());
        Path currentDir = MediaVersions.versionDir(itemDir, item.mediaVersion());
        Path source = itemDir.resolve(item.originalFile());
        step(log, "probe");
        MediaProbe probe = MediaProbe.probe(tools, source, log);
        checkProbe(probe, false);
        if (!probe.isVideo()) {
            throw new IngestFailure("The original has no video to trim");
        }
        long sourceAudioEndUs = probe.audioEndUs() > 0 ? probe.audioEndUs() : probe.readAudioEndUs(tools, source, log);
        long[] r = trimRange(target, probe, sourceAudioEndUs);
        long startMs = r[0] / 1000;
        long endMs = r[1] / 1000;
        log.append("[ingest] trim: " + seconds(r[0]) + " to " + seconds(r[1]) + " of the original (" + seconds(r[2])
                + "); the current encode starts at " + seconds(current.videoStartMs() * 1000));

        // what the current version offers
        String why = null;
        SampleIndex currentIndex = null;
        X264Preset preset = item.encodePreset() == null ? X264Preset.REFERENCE : X264Preset.byId(item.encodePreset());
        if (!profile.id().equals(item.outputProfile())) {
            why = "the current version was made for another output encoding";
        } else if (preset == null) {
            why = "the current version's preset " + item.encodePreset() + " is unknown";
        } else if (!Files.isRegularFile(currentDir.resolve(MEZZ)) || !Files.isRegularFile(currentDir.resolve(CARD))) {
            why = "the current version's files are missing";
        } else {
            try {
                currentIndex = SampleIndex.read(currentDir.resolve(SampleIndex.FILE_NAME));
                if (!profile.equals(currentIndex.encoding()) || currentIndex.track(SampleIndex.Kind.VIDEO) == null) {
                    why = "the current version's index is for another encoding";
                }
            } catch (IOException e) {
                why = "the current version's index can't be read (" + e.getMessage() + ")";
            }
        }
        TrimPlan.Plan plan = null;
        if (why == null) {
            SampleIndex.Track v = currentIndex.track(SampleIndex.Kind.VIDEO);
            plan = TrimPlan.plan(current.videoStartMs(), v.sampleCount(), firstGop(v), startMs, endMs);
            if (plan.mode() == TrimPlan.Mode.FULL) {
                why = plan.reason();
            }
        }
        if (why != null) {
            log.append("[ingest] full re-encode from the original: " + why);
            return false;
        }
        log.append("[ingest] smart trim: " + describe(plan));

        Path original = null;
        int[] expectedSizes;
        Loudness.Measurement loudness;
        long[] decodedUs = {-1};
        Originals.Original kept;
        Long audioOnlyMs = null;
        try {
            ctx.stage(STEP_ENCODE, 0, 0.85);
            List<String> range = rangeArgs(r[0], r[1]);
            long lengthUs = r[1] - r[0];
            step(log, "loudnorm pass 1");
            loudness = Loudness.measure(tools, range, source.toString(), probe.audio().map(), log, p -> {
                if (p.outTimeUs() > decodedUs[0]) {
                    decodedUs[0] = p.outTimeUs();
                }
                ctx.progress(0.25 * p.fraction(lengthUs));
            });
            if (loudness.usable()) {
                log.append(String.format(Locale.ROOT, "[ingest] measured %.2f LUFS, %.2f dBTP, LRA %.2f LU",
                        loudness.inputI(), loudness.inputTp(), loudness.inputLra()));
            }
            step(log, "encode audio");
            Path trimmedAudio = scratch.resolve("trim-audio.m4a");
            List<String> audioArgs = new ArrayList<>(range);
            audioArgs.addAll(List.of("-i", source.toString(), "-map", probe.audio().map(), "-vn", "-sn", "-dn",
                    "-map_metadata", "-1", "-map_chapters", "-1", "-af", loudness.filter()));
            audioArgs.addAll(EncodeProfile.AUDIO_ARGS);
            audioArgs.addAll(List.of("-movflags", "+faststart", "-f", "mp4", trimmedAudio.toString()));
            tools.ffmpeg(prepend("-y", audioArgs)).log(log).run();
            ctx.progress(0.35);

            // the video pieces
            SampleIndex.Track currentVideo = currentIndex.track(SampleIndex.Kind.VIDEO);
            List<Integer> sizes = new ArrayList<>();
            StringBuilder list = new StringBuilder();
            int encodeTotal = Math.max(1, plan.encodedFrames());
            int encodedSoFar = 0;
            for (int i = 0; i < plan.pieces().size(); i++) {
                TrimPlan.Piece piece = plan.pieces().get(i);
                Path file = scratch.resolve("piece-" + i + ".mp4");
                if (piece.source() == TrimPlan.Source.REUSE) {
                    step(log, "copy frames " + piece.from() + ".." + (piece.to() - 1) + " of the current version");
                    // the IDR's time in the current version, rounded up so the seek lands on it, not the one before
                    long atUs = ((long) piece.from() * 1_000_000 + TrimPlan.FPS - 1) / TrimPlan.FPS;
                    tools.ffmpeg(List.of("-y", "-ss", secondsArg(atUs),
                            "-i", currentDir.resolve(MEZZ).toString(), "-map", "0:v:0", "-c", "copy",
                            "-frames:v", Integer.toString(piece.frames()), "-map_metadata", "-1",
                            "-f", "mp4", file.toString())).log(log).run();
                    SampleIndex pieceIndex = videoIndex(file, profile, log);
                    SampleIndex.Track t = pieceIndex.track(SampleIndex.Kind.VIDEO);
                    if (t.sampleCount() != piece.frames()) {
                        throw new IngestFailure("the copied piece has " + t.sampleCount() + " frames instead of "
                                + piece.frames());
                    }
                    for (int k = 0; k < t.sampleCount(); k++) {
                        if (t.size(k) != currentVideo.size(piece.from() + k)) {
                            throw new IngestFailure("the copied piece's frame " + k + " isn't frame "
                                    + (piece.from() + k) + " of the current version");
                        }
                        sizes.add(t.size(k));
                    }
                } else {
                    step(log, "encode " + piece.frames() + " frames from the original at " + seconds(piece.startUs())
                            + (piece.firstGop() < TrimPlan.GOP ? ", first GOP " + piece.firstGop() + " frames" : ""));
                    List<String> args = new ArrayList<>(List.of("-y", "-ss", secondsArg(piece.startUs()),
                            "-i", source.toString(), "-map", probe.video().map(), "-an", "-sn", "-dn",
                            "-map_metadata", "-1", "-map_chapters", "-1", "-vf", EncodeProfile.scaleFilter(profile)));
                    args.addAll(EncodeProfile.videoArgs(profile, preset, piece.firstGop()));
                    args.addAll(List.of("-frames:v", Integer.toString(piece.frames()), "-movflags", "+faststart",
                            "-f", "mp4", file.toString()));
                    double from = 0.35 + 0.5 * encodedSoFar / encodeTotal;
                    double to = 0.35 + 0.5 * (encodedSoFar + piece.frames()) / encodeTotal;
                    long pieceUs = (long) piece.frames() * 1_000_000 / TrimPlan.FPS;
                    tools.ffmpeg(args).log(log).ffmpegProgress(p -> ctx.progress(from + (to - from)
                            * Math.max(0, Math.min(1, p.fraction(pieceUs))))).run();
                    encodedSoFar += piece.frames();
                    SampleIndex pieceIndex = videoIndex(file, profile, log);
                    reference.verify(pieceIndex.avcC(), "the encoded piece " + i);
                    IngestVerifier.checkKeyframes(pieceIndex, "the encoded piece " + i, piece.firstGop());
                    SampleIndex.Track t = pieceIndex.track(SampleIndex.Kind.VIDEO);
                    boolean last = i == plan.pieces().size() - 1;
                    // only the last piece may come out short (the original's video ends there)
                    if (t.sampleCount() > piece.frames() || (!last && t.sampleCount() != piece.frames())) {
                        throw new IngestFailure("the encoded piece " + i + " has " + t.sampleCount()
                                + " frames instead of " + piece.frames());
                    }
                    for (int k = 0; k < t.sampleCount(); k++) {
                        sizes.add(t.size(k));
                    }
                }
                list.append("file '").append(file.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
            }
            expectedSizes = sizes.stream().mapToInt(Integer::intValue).toArray();
            ctx.stage(STEP_FINISH, 0.85, 1);

            // splice: the pieces' video and the new audio
            step(log, "splice");
            Path listFile = scratch.resolve("pieces.txt");
            Files.writeString(listFile, list.toString());
            Path mezz = work.resolve(MEZZ);
            Path audio = work.resolve(AUDIO);
            // -auto_convert 0: the concat demuxer would otherwise put the SPS/PPS in-band into the
            // first frame of every piece (measured with ffmpeg 6.1.1), so no frame would match its
            // piece and every smart trim fell back to a full re-encode
            tools.ffmpeg(List.of("-y", "-auto_convert", "0", "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                    "-i", trimmedAudio.toString(), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
                    "-map_metadata", "-1", "-map_chapters", "-1", "-movflags", "+faststart",
                    "-f", "mp4", mezz.toString())).log(log).run();
            tools.ffmpeg(extractAudioArgs(mezz, audio)).log(log).run();
            linkOrCopy(currentDir.resolve(CARD), work.resolve(CARD));
            Path cover = currentDir.resolve(COVER);
            if (Files.isRegularFile(cover)) {
                linkOrCopy(cover, work.resolve(COVER));
            }
            ctx.progress(0.9);

            step(log, "index");
            SampleIndex index = IndexBuilder.build(tools, profile, mezz, audio, work.resolve(SampleIndex.FILE_NAME), log);
            step(log, "verify");
            long expectedAudioUs = Math.min(sourceAudioEndUs, r[1]) - r[0];
            IngestVerifier.verify(index, reference, expectedAudioUs, decodedUs[0], MEZZ, plan.firstGop());
            SampleIndex.Track video = index.track(SampleIndex.Kind.VIDEO);
            if (video.sampleCount() != expectedSizes.length) {
                throw new IngestFailure("the spliced video has " + video.sampleCount() + " frames instead of "
                        + expectedSizes.length);
            }
            for (int k = 0; k < expectedSizes.length; k++) {
                if (video.size(k) != expectedSizes[k]) {
                    throw new IngestFailure("the spliced video's frame " + k + " isn't the frame it was made from");
                }
            }
            kept = Originals.keptOnly(probe, source.getFileName().toString());
            original = work.resolve(kept.file());
            linkOrCopy(source, original);
            log.append("[ingest] verified: audio " + seconds(IngestVerifier.audioDurationUs(index)) + ", avcC matches the "
                    + profile.id() + " reference, " + video.sampleCount() + " frames with an IDR every 2 s"
                    + (plan.firstGop() < TrimPlan.GOP ? " after a first GOP of " + plan.firstGop() + " frames" : ""));
            if (target.audioTrimmed()) {
                audioOnlyMs = audioOnlyVariant(log, source, probe, sourceAudioEndUs, target, work, reference);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                throw e;
            }
            log.append("[ingest] the smart trim failed (" + e.getMessage() + "); full re-encode from the original instead");
            emptyDirectory(work);
            emptyDirectory(scratch);
            return false;
        }

        step(log, "publish");
        long durationMs;
        {
            SampleIndex index = SampleIndex.read(work.resolve(SampleIndex.FILE_NAME));
            durationMs = IngestVerifier.audioDurationUs(index) / 1000;
        }
        Double lufs = loudness.usable() ? round2(loudness.inputI()) : null;
        boolean hadOld = MediaVersions.exists(itemDir, item.mediaVersion());
        IngestItems.VersionChange change = new IngestItems.VersionChange(hadOld ? item.mediaVersion() : null, true,
                EditSettings.withFirstGop(EditSettings.withOriginalMs(targetJson, r[2] / 1000), plan.firstGop()),
                System.currentTimeMillis());
        X264Preset usedPreset = preset;
        Originals.Original keptOriginal = kept;
        Long variantMs = audioOnlyMs;
        int version = publish(work, itemDir, item.mediaVersion(), kept.file(),
                v -> items.published(item.id(), true, durationMs, profile, usedPreset, item.encodeQuality(), lufs,
                        keptOriginal, v, change, variantMs));
        log.append("[ingest] published as version " + version + (hadOld ? "; version " + item.mediaVersion()
                + " is kept for undo" : ""));
        retire(item, itemDir, hadOld, kept, durationMs);
        ctx.progress(1);
        return true;
    }

    /**
     * A trim job of a video that changes only its audio trim ({@code docs/library-and-ingest.md#audio-trim}):
     * the video, {@code audio.m4a}, card and index stay as they are (linked from the current version),
     * only the audio-only file is made again from the original (or left out when the audio trim is
     * removed), and the result is published as an undoable version. Returns false (nothing done) when
     * the current version can't be reused (another encoding, files missing): then a full re-encode.
     */
    private boolean audioOnlyTrim(IngestQueue.Context ctx, IngestItems.Item item, Path work, ReferenceCard reference,
                                  EditSettings target, String targetJson) throws Exception {
        JobLog log = ctx.log();
        OutputEncoding profile = reference.encoding();
        Path itemDir = itemDir(dataDir, item.id());
        Path currentDir = MediaVersions.versionDir(itemDir, item.mediaVersion());
        String why = null;
        if (!profile.id().equals(item.outputProfile())) {
            why = "the current version was made for another output encoding";
        } else {
            for (String f : List.of(MEZZ, AUDIO, CARD, SampleIndex.FILE_NAME)) {
                if (!Files.isRegularFile(currentDir.resolve(f))) {
                    why = "the current version's " + f + " is missing";
                    break;
                }
            }
        }
        if (why != null) {
            log.append("[ingest] full re-encode from the original: " + why);
            return false;
        }
        log.append("[ingest] audio trim only: the video and audio.m4a of version " + item.mediaVersion() + " are kept");
        Path source = itemDir.resolve(item.originalFile());
        ctx.stage(STEP_ENCODE, 0, 0.9);
        step(log, "probe");
        MediaProbe probe = MediaProbe.probe(tools, source, log);
        checkProbe(probe, false);
        long sourceAudioEndUs = probe.audioEndUs() > 0 ? probe.audioEndUs() : probe.readAudioEndUs(tools, source, log);
        for (String f : List.of(MEZZ, AUDIO, CARD, COVER, SampleIndex.FILE_NAME)) {
            if (Files.isRegularFile(currentDir.resolve(f))) {
                linkOrCopy(currentDir.resolve(f), work.resolve(f));
            }
        }
        Long audioOnlyMs = null;
        if (target.audioTrimmed()) {
            audioOnlyMs = audioOnlyVariant(log, source, probe, sourceAudioEndUs, target, work, reference);
        } else {
            log.append("[ingest] no audio trim: the audio-only file is audio.m4a again");
        }
        ctx.stage(STEP_FINISH, 0.9, 1);
        long durationMs = IngestVerifier.audioDurationUs(SampleIndex.read(work.resolve(SampleIndex.FILE_NAME))) / 1000;
        Originals.Original kept = Originals.keptOnly(probe, source.getFileName().toString());
        linkOrCopy(source, work.resolve(kept.file()));
        step(log, "publish");
        boolean hadOld = MediaVersions.exists(itemDir, item.mediaVersion());
        IngestItems.VersionChange change = new IngestItems.VersionChange(hadOld ? item.mediaVersion() : null, true,
                EditSettings.withOriginalMs(targetJson, Math.max(probe.durationUs(), sourceAudioEndUs) / 1000),
                System.currentTimeMillis());
        X264Preset preset = item.encodePreset() == null ? X264Preset.REFERENCE : X264Preset.byId(item.encodePreset());
        X264Preset usedPreset = preset == null ? X264Preset.REFERENCE : preset;
        Long variantMs = audioOnlyMs;
        int version = publish(work, itemDir, item.mediaVersion(), kept.file(),
                v -> items.published(item.id(), true, durationMs, profile, usedPreset, item.encodeQuality(),
                        item.loudnessLufs(), kept, v, change, variantMs));
        log.append("[ingest] published as version " + version + (hadOld ? "; version " + item.mediaVersion()
                + " is kept for undo" : ""));
        retire(item, itemDir, hadOld, kept, durationMs);
        ctx.progress(1);
        return true;
    }

    /**
     * A card-only re-encode ({@code docs/library-and-ingest.md#cards}): a {@code reencode} job of an
     * item that is encoded for the current encoding but whose card is in an older card format
     * ({@link CardEncoder#CARD_VERSION}) or was drawn with other fonts than its text gets now
     * ({@code items.card_fonts}, {@code docs/library-and-ingest.md#card-fonts}). Every file of the current version is linked into the new
     * one except the card, which is rendered again (from {@code cover.jpg} and the item's metadata)
     * and encoded, and the indexes that use it (an audio item's {@code index.bin}, a video's
     * {@link #AUDIO_ONLY_INDEX}), which are built again; the video is untouched. Published as a
     * system change (the previous version is kept for the undo window, without Undo). Returns
     * false (nothing done) when the current version can't be reused (files missing): then a full
     * re-encode.
     */
    private boolean remakeCard(IngestQueue.Context ctx, IngestItems.Item item, Path work, ReferenceCard reference)
            throws Exception {
        JobLog log = ctx.log();
        OutputEncoding profile = reference.encoding();
        Path itemDir = itemDir(dataDir, item.id());
        Path currentDir = MediaVersions.versionDir(itemDir, item.mediaVersion());
        boolean video = Files.isRegularFile(currentDir.resolve(MEZZ));
        boolean audioOnly = video && Files.isRegularFile(currentDir.resolve(AUDIO_ONLY));
        List<String> needed = new ArrayList<>(List.of(AUDIO, CARD, SampleIndex.FILE_NAME));
        if (audioOnly) {
            needed.add(AUDIO_ONLY_INDEX);
        }
        if ("video".equals(item.kind()) != video) {
            log.append("[ingest] full re-encode: the current version's files don't match the item kind");
            return false;
        }
        for (String f : needed) {
            if (!Files.isRegularFile(currentDir.resolve(f))) {
                log.append("[ingest] full re-encode: the current version's " + f + " is missing");
                return false;
            }
        }
        log.append("[ingest] card only: the card is in an older format or its fonts changed; the rest of version "
                + item.mediaVersion() + " is kept");
        ctx.stage(STEP_FINISH, 0, 1);
        for (String f : List.of(MEZZ, AUDIO, COVER, AUDIO_ONLY, SampleIndex.FILE_NAME)) {
            if (Files.isRegularFile(currentDir.resolve(f))) {
                linkOrCopy(currentDir.resolve(f), work.resolve(f));
            }
        }

        step(log, "card");
        BufferedImage artwork = null;
        if (Files.isRegularFile(work.resolve(COVER))) {
            try {
                artwork = CardRenderer.readImage(work.resolve(COVER));
            } catch (IOException e) {
                log.append("[ingest] the cover image could not be read: " + e.getMessage());
            }
        }
        CardRenderer renderer = new CardRenderer(profile.profile());
        BufferedImage card = renderer.renderItem(
                new CardRenderer.ItemCard(item.title(), item.artist(), item.album(), artwork));
        String cardFonts = CardRenderer.fontKey(renderer.fonts(), item.title(), item.artist(), item.album());
        CardEncoder.encode(tools, card, work.resolve(CARD), reference, log);
        ctx.progress(0.5);

        step(log, "index");
        SampleIndex old = SampleIndex.read(currentDir.resolve(SampleIndex.FILE_NAME));
        long durationUs = IngestVerifier.audioDurationUs(old);
        if (!video) {
            Files.delete(work.resolve(SampleIndex.FILE_NAME));
            SampleIndex index = IndexBuilder.build(tools, profile, work.resolve(CARD), work.resolve(AUDIO),
                    work.resolve(SampleIndex.FILE_NAME), log);
            checkCardIndex(index, reference, durationUs);
        }
        if (audioOnly) {
            long audioOnlyUs = IngestVerifier.audioDurationUs(SampleIndex.read(currentDir.resolve(AUDIO_ONLY_INDEX)));
            SampleIndex index = IndexBuilder.build(tools, profile, work.resolve(CARD), work.resolve(AUDIO_ONLY),
                    work.resolve(AUDIO_ONLY_INDEX), log);
            checkCardIndex(index, reference, audioOnlyUs);
        }
        ctx.progress(0.8);

        step(log, "publish");
        boolean hadOld = MediaVersions.exists(itemDir, item.mediaVersion());
        int version = publish(work, itemDir, item.mediaVersion(), null,
                v -> items.cardRemade(item.id(), item.mediaVersion(), v, CardEncoder.CARD_VERSION, cardFonts, hadOld,
                        System.currentTimeMillis()));
        log.append("[ingest] published the new card as version " + version + (hadOld ? "; version "
                + item.mediaVersion() + " is kept until nobody can still be playing it" : ""));
        MediaVersions v = versions;
        if (hadOld && v != null) {
            v.retired(item.id(), item.mediaVersion(), durationUs / 1000);
        }
        ctx.progress(1);
        return true;
    }

    /**
     * Whether the item's card was drawn with the fonts its text gets now ({@code items.card_fonts}
     * equals {@link CardRenderer#fontKey}; {@code docs/library-and-ingest.md#card-fonts}).
     */
    private boolean cardFontsCurrent(IngestItems.Item item) throws java.sql.SQLException {
        String stored = items.cardFonts(item.id());
        return stored != null && stored.equals(CardRenderer.fontKey(CardFonts.installed(), item.title(), item.artist(),
                item.album()));
    }

    /** Checks an index rebuilt around a new card: the reference avcC, the GOP grid, the same audio length. */
    private static void checkCardIndex(SampleIndex index, ReferenceCard reference, long audioUs)
            throws IngestFailure, ReferenceCard.AvcCMismatchException {
        IngestVerifier.verify(index, reference, audioUs, -1, CARD);
    }

    /**
     * Makes a video's audio-only file ({@link #AUDIO_ONLY}) in {@code work} from the original for
     * the audio trim of {@code edit}, loudness measured on that range, and its index
     * ({@link #AUDIO_ONLY_INDEX}, with {@code work}'s {@link #CARD} as the video, which must be
     * there), verified like an audio item's files.
     *
     * @return its length in ms
     */
    private long audioOnlyVariant(JobLog log, Path source, MediaProbe probe, long sourceAudioEndUs, EditSettings edit,
                                  Path work, ReferenceCard reference) throws Exception {
        long[] r = trimRange(edit.audioStartMs(), edit.audioEndMs(), probe, sourceAudioEndUs);
        log.append("[ingest] audio trim (audio-only rooms): " + seconds(r[0]) + " to " + seconds(r[1])
                + " of the original (" + seconds(r[2]) + ")");
        List<String> range = rangeArgs(r[0], r[1]);
        step(log, "audio-only file: loudnorm pass 1");
        long[] decodedUs = {-1};
        Loudness.Measurement loudness = Loudness.measure(tools, range, source.toString(), probe.audio().map(), log, p -> {
            if (p.outTimeUs() > decodedUs[0]) {
                decodedUs[0] = p.outTimeUs();
            }
        });
        if (loudness.usable()) {
            log.append(String.format(Locale.ROOT, "[ingest] audio-only range measured %.2f LUFS, %.2f dBTP",
                    loudness.inputI(), loudness.inputTp()));
        }
        step(log, "audio-only file: encode");
        Path out = work.resolve(AUDIO_ONLY);
        tools.ffmpeg(audioEncodeArgs(range, source, probe, loudness, out)).log(log).run();
        step(log, "audio-only file: index");
        OutputEncoding profile = reference.encoding();
        SampleIndex index = IndexBuilder.build(tools, profile, work.resolve(CARD), out, work.resolve(AUDIO_ONLY_INDEX), log);
        IngestVerifier.verify(index, reference, Math.min(sourceAudioEndUs, r[1]) - r[0], decodedUs[0], CARD);
        long us = IngestVerifier.audioDurationUs(index);
        log.append("[ingest] verified the audio-only file: " + seconds(us));
        return us / 1000;
    }

    /** The plan in words, for the job log. */
    static String describe(TrimPlan.Plan plan) {
        StringBuilder sb = new StringBuilder();
        for (TrimPlan.Piece p : plan.pieces()) {
            if (sb.length() > 0) {
                sb.append(", then ");
            }
            if (p.source() == TrimPlan.Source.REUSE) {
                sb.append("keep frames ").append(p.from()).append("..").append(p.to() - 1)
                        .append(" (").append(p.frames()).append(")");
            } else {
                sb.append("encode ").append(p.frames()).append(" frames from ").append(seconds(p.startUs()));
                if (p.firstGop() < TrimPlan.GOP) {
                    sb.append(" (first GOP ").append(p.firstGop()).append(")");
                }
            }
        }
        return sb.append(plan.cutOnly() ? "; no video is encoded" : "").toString();
    }

    /**
     * The first GOP of an encode in frames: where its second IDR is; 60 when it has none (then the
     * whole encode is shorter than a GOP, and a trim may only cut it).
     */
    static int firstGop(SampleIndex.Track video) {
        int k = video.keyframeAtOrAfter(1);
        return k > 0 && k < TrimPlan.GOP ? k : TrimPlan.GOP;
    }

    private SampleIndex videoIndex(Path file, OutputEncoding profile, JobLog log) throws Exception {
        return IndexBuilder.build(tools, profile, List.of(new IndexBuilder.Source(file, SampleIndex.Kind.VIDEO)), log);
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> all = new ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return all;
    }

    /** Deletes what is in {@code dir}, keeping the directory. */
    private static void emptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                deleteTree(f);
            }
        }
    }

    /**
     * After the switch: hands the replaced version to {@link MediaVersions} (a play that started on
     * it just before the switch lasts about the item's duration), and deletes an old original that
     * the new one replaced under another name.
     */
    private void retire(IngestItems.Item item, Path itemDir, boolean hadOld, Originals.Original original,
                        long durationMs) {
        MediaVersions v = versions;
        if (hadOld && v != null) {
            v.retired(item.id(), item.mediaVersion(), durationMs);
        }
        String oldOriginal = item.originalFile();
        if (oldOriginal != null && isPlainName(oldOriginal) && oldOriginal.startsWith("original.")
                && (original == null || !oldOriginal.equals(original.file()))) {
            try {
                Files.deleteIfExists(itemDir.resolve(oldOriginal));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not delete the old original " + oldOriginal
                        + " of item " + item.id() + ": " + e.getMessage());
            }
        }
    }

    private static void step(JobLog log, String name) {
        log.append("[ingest] step: " + name);
    }

    private static String seconds(long us) {
        return us < 0 ? "unknown" : String.format(Locale.ROOT, "%.3f s", us / 1e6);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** Reports progress within [from, to] of the whole job. */
    private static DoubleConsumer range(IngestQueue.Context ctx, double from, double to) {
        return f -> ctx.progress(from + (to - from) * Math.max(0, Math.min(1, f)));
    }

    /** A measurement that isn't {@link Loudness.Measurement#usable() usable}: encode without loudnorm. */
    static final Loudness.Measurement NOT_MEASURED =
            new Loudness.Measurement(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    private static Path existingFile(Path f) {
        return Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) ? f : null;
    }

    void checkProbe(MediaProbe probe) throws IngestFailure {
        checkProbe(probe, true);
    }

    /** @param limitDuration whether {@code ingest.maxDurationSeconds} applies (not for re-encodes) */
    void checkProbe(MediaProbe probe, boolean limitDuration) throws IngestFailure {
        checkSource(probe, limitDuration ? maxDurationSec.getAsInt() * 1_000_000L : -1);
    }

    /**
     * Rejects a source that can't become an item: no audio stream, or (when
     * {@code maxDurationUs >= 0}) longer than that. Shared with the upload's {@code complete},
     * which rejects such files before creating an item.
     */
    public static void checkSource(MediaProbe probe, long maxDurationUs) throws IngestFailure {
        if (probe.audio() == null) {
            throw new IngestFailure("The file has no audio");
        }
        if (maxDurationUs < 0) {
            return;
        }
        long duration = Math.max(probe.durationUs(), probe.audioEndUs());
        if (duration > maxDurationUs) {
            throw new IngestFailure("The file is too long (" + seconds(duration) + "; the limit is "
                    + maxDurationUs / 1_000_000 + " s)");
        }
    }

    /** The staged file {@code <name>} or {@code <name>.<ext>} in {@code dir}, or null. */
    static Path staged(Path dir, String name) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path found = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                if ((n.equals(name) || n.startsWith(name + ".")) && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    if (found == null || n.compareTo(found.getFileName().toString()) < 0) {
                        found = f;
                    }
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ commands

    static List<String> videoEncodeArgs(Path source, MediaProbe probe, Loudness.Measurement loudness,
                                        OutputEncoding profile, Path out) {
        return videoEncodeArgs(source, probe, loudness, profile, X264Preset.REFERENCE, out);
    }

    static List<String> videoEncodeArgs(Path source, MediaProbe probe, Loudness.Measurement loudness,
                                        OutputEncoding profile, X264Preset preset, Path out) {
        return videoEncodeArgs(List.of(), source, probe, loudness, profile, preset, out);
    }

    /** @param range input options before {@code -i} that select a trim ({@link #rangeArgs}), or none */
    static List<String> videoEncodeArgs(List<String> range, Path source, MediaProbe probe, Loudness.Measurement loudness,
                                        OutputEncoding profile, X264Preset preset, Path out) {
        List<String> args = new ArrayList<>(List.of("-y"));
        args.addAll(range);
        args.addAll(List.of("-i", source.toString(),
                "-map", probe.video().map(), "-map", probe.audio().map(),
                "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", EncodeProfile.scaleFilter(profile)));
        args.addAll(EncodeProfile.videoArgs(profile, preset));
        args.addAll(List.of("-af", loudness.filter()));
        args.addAll(EncodeProfile.AUDIO_ARGS);
        int t = range.indexOf("-t");
        if (t >= 0) {
            // also as an output option: the fps filter would otherwise fill up to the end of the last
            // source frame read; this keeps exactly the frames with k / 30 s < length (TrimPlan.frames)
            args.addAll(List.of("-t", range.get(t + 1)));
        }
        args.addAll(List.of("-movflags", "+faststart", "-f", "mp4", out.toString()));
        return args;
    }

    static List<String> audioEncodeArgs(Path source, MediaProbe probe, Loudness.Measurement loudness, Path out) {
        return audioEncodeArgs(List.of(), source, probe, loudness, out);
    }

    /** @param range input options before {@code -i} that select a trim ({@link #rangeArgs}), or none */
    static List<String> audioEncodeArgs(List<String> range, Path source, MediaProbe probe, Loudness.Measurement loudness,
                                        Path out) {
        List<String> args = new ArrayList<>(List.of("-y"));
        args.addAll(range);
        args.addAll(List.of("-i", source.toString(),
                "-map", probe.audio().map(), "-vn", "-map_metadata", "-1", "-map_chapters", "-1",
                "-af", loudness.filter()));
        args.addAll(EncodeProfile.AUDIO_ARGS);
        args.addAll(List.of("-movflags", "+faststart", "-f", "mp4", out.toString()));
        return args;
    }

    static List<String> extractAudioArgs(Path mezz, Path out) {
        return List.of("-y", "-i", mezz.toString(), "-map", "0:a", "-c", "copy", "-movflags", "+faststart",
                "-f", "mp4", out.toString());
    }

    static List<String> coverArgs(Path input, String map, Path out) {
        return List.of("-y", "-i", input.toString(), "-map", map, "-frames:v", "1",
                "-vf", "scale=w='min(" + COVER_MAX + ",iw)':h='min(" + COVER_MAX + ",ih)'"
                        + ":force_original_aspect_ratio=decrease",
                "-q:v", "2", "-update", "1", "-f", "image2", out.toString());
    }

    static List<String> downloadArgs(String youtubeId, Path dir, String ffmpeg) {
        return downloadArgs(youtubeId, dir, ffmpeg, null);
    }

    /**
     * The download command ({@code docs/library-and-ingest.md#youtube-caching}, step 4), plus the
     * best thumbnail as {@code cover.<ext>} next to {@code src.<ext>}.
     *
     * @param cookies a cookies file for {@code --cookies}, or null
     */
    static List<String> downloadArgs(String youtubeId, Path dir, String ffmpeg, Path cookies) {
        List<String> args = new ArrayList<>(List.of("--no-playlist", "--newline", "--no-mtime",
                "-f", "bv*[height<=1080]+ba/b", "--merge-output-format", "mkv",
                "--write-thumbnail", "-o", "thumbnail:" + dir.resolve(COVER_NAME + ".%(ext)s"),
                "-o", dir.resolve("src.%(ext)s").toString()));
        if (ffmpeg != null && ffmpeg.contains("/")) {
            args.addAll(List.of("--ffmpeg-location", ffmpeg));
        }
        args.addAll(cookiesArgs(cookies));
        args.add("--");
        args.add(canonicalUrl(youtubeId));
        return args;
    }

    /**
     * The metadata command: {@code yt-dlp -J --no-playlist} on the canonical URL (never the URL
     * as pasted), with the cookies file if there is one.
     */
    public static List<String> metadataArgs(String youtubeId, Path cookies) {
        // --verbose: -J is quiet otherwise; verbose mode prints the progress lines ("[youtube] …:
        // Downloading webpage") to stderr, which the lookup's live feedback follows (stdout stays the JSON)
        List<String> args = new ArrayList<>(List.of("-J", "--no-playlist", "--no-warnings", "--verbose"));
        args.addAll(cookiesArgs(cookies));
        args.add("--");
        args.add(canonicalUrl(youtubeId));
        return args;
    }

    /** {@code https://www.youtube.com/watch?v=<id>}; the ID must be 11 characters of [A-Za-z0-9_-]. */
    static String canonicalUrl(String youtubeId) {
        if (youtubeId == null || !youtubeId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Not a YouTube video ID: " + youtubeId);
        }
        return "https://www.youtube.com/watch?v=" + youtubeId;
    }

    /** {@code --cookies <file>}, or nothing. */
    static List<String> cookiesArgs(Path cookies) {
        return cookies == null ? List.of() : List.of("--cookies", cookies.toString());
    }

    /**
     * The cookies file named by the {@code ytdlp.cookiesFile} setting, if it is a readable regular
     * file; null if the setting is empty. A file that is missing or unreadable is reported to
     * {@code warn} and ignored (yt-dlp then runs without cookies), so a mistyped path doesn't stop
     * every import. yt-dlp writes refreshed cookies back to the file, so the server should be able
     * to write it too.
     */
    public static Path cookiesFile(String setting, Consumer<String> warn) {
        if (setting == null || setting.isBlank()) {
            return null;
        }
        Path file;
        try {
            file = Path.of(setting.strip());
        } catch (RuntimeException e) {
            warn.accept("the cookies file setting (ytdlp.cookiesFile) isn't a valid path; continuing without cookies");
            return null;
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            warn.accept("the cookies file " + file + " (ytdlp.cookiesFile) can't be read; continuing without cookies");
            return null;
        }
        return file;
    }

    // ------------------------------------------------------------------ steps

    private Path download(IngestItems.Item item, Path scratch, JobLog log, DoubleConsumer progress)
            throws Exception {
        String id = item.youtubeId();
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) {
            throw new IngestFailure("The item has no YouTube video ID");
        }
        if (!tools.hasYtdlp()) {
            throw new IngestFailure("yt-dlp isn't available on this server");
        }
        Consumer<String> lines = line -> {
            Matcher m = YTDLP_PERCENT.matcher(line);
            if (m.find()) {
                progress.accept(Double.parseDouble(m.group(1)) / 100);
            } else if (!line.isBlank()) {
                log.append(line);
            }
        };
        Path cookies = cookiesFile(cookiesFile.get(), w -> log.append("[ingest] " + w));
        tools.ytdlp(downloadArgs(id, scratch, tools.ffmpegExecutable(), cookies)).log(log).stdoutLines(lines).run();
        Path file = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(scratch, "src.*")) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                if (n.endsWith(".part") || n.endsWith(".ytdl") || !Files.isRegularFile(f)) {
                    continue;
                }
                if (file == null || n.equals("src.mkv")) {
                    file = f;
                }
            }
        }
        if (file == null) {
            throw new IngestFailure("yt-dlp finished but produced no file");
        }
        log.append("[ingest] downloaded " + file.getFileName() + " (" + Files.size(file) + " bytes)");
        Path thumbnail = staged(scratch, COVER_NAME);
        log.append(thumbnail == null ? "[ingest] no thumbnail" : "[ingest] thumbnail " + thumbnail.getFileName());
        return file;
    }

    /**
     * Writes {@code cover.jpg} from the staged cover, else the source's embedded picture, and
     * returns it as an image for the card; null (placeholder art) if there is none or it can't be
     * converted. A broken cover never fails the job.
     */
    private BufferedImage cover(Path source, MediaProbe probe, Path stagedCover, Path out, JobLog log)
            throws InterruptedException {
        List<String[]> candidates = new ArrayList<>();
        if (stagedCover != null) {
            candidates.add(new String[] {stagedCover.toString(), "0:v:0"});
        }
        if (probe.cover() != null) {
            candidates.add(new String[] {source.toString(), probe.cover().map()});
        }
        for (String[] c : candidates) {
            try {
                tools.ffmpeg(coverArgs(Path.of(c[0]), c[1], out)).log(log).run();
                BufferedImage image = CardRenderer.readImage(out);
                if (image != null) {
                    return image;
                }
                log.append("[ingest] the cover image could not be read");
            } catch (ToolException | IOException e) {
                log.append("[ingest] no cover from " + Path.of(c[0]).getFileName() + ": " + e.getMessage());
            }
            try {
                Files.deleteIfExists(out);
            } catch (IOException e) {
                // left in the work dir; deleted with it
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ publish

    /** The database switch of a publish: makes {@code version} the item's current one. */
    @FunctionalInterface
    interface Switch {
        void to(int version) throws java.sql.SQLException;
    }

    /**
     * Publishes {@code work} as the item's next version: moves the kept original {@code original}
     * (if any) out of {@code work} into {@code itemDir}, renames {@code work} to
     * {@code itemDir/v<N>} and runs {@code sw} for {@code N}. If the switch fails, the new version
     * directory is deleted again (the original, if it replaced one of the same name, is the same
     * source anyway); the old version is never touched here.
     *
     * @param current  the item's current {@code media_version}
     * @param original the file name of the original in {@code work} to keep, or null
     * @return the new version
     */
    static int publish(Path work, Path itemDir, int current, String original, Switch sw)
            throws IOException, java.sql.SQLException {
        Files.createDirectories(itemDir);
        int version = MediaVersions.nextVersion(itemDir, current);
        Path target = MediaVersions.versionDir(itemDir, version);
        if (original != null) {
            Path from = work.resolve(original);
            if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                renameReplacing(from, itemDir.resolve(original));
                // a rename between two links to the same file (a re-encode from the original) does nothing
                Files.deleteIfExists(from);
            }
        }
        rename(work, target);
        try {
            sw.to(version);
        } catch (java.sql.SQLException | RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return version;
    }

    /**
     * Puts {@code source} at {@code target} as a hard link (both live in the data dir, usually on
     * one file system), or as a copy if linking isn't possible. The staged source stays until
     * the publish succeeded, so a failed job can be retried.
     */
    static void linkOrCopy(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException e) {
            Files.deleteIfExists(target);
            Files.copy(source, target);
        }
    }

    /** Renames a file over an existing one (atomic where the file system allows it). */
    private static void renameReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void rename(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /**
     * Cleans up after jobs a crash or kill interrupted. Call at startup, before the queue starts:
     * deletes half-built {@code media/.ingest-*} directories, {@code tmp/jobs/*} and the
     * {@code .purge-*} directories a purge left in {@code media/} and {@code tmp/ingest/}; an
     * {@code media/.old-<itemId>-<jobId>} directory is put back if its item directory is missing
     * (the crash came between the two renames of a publish) and deleted otherwise.
     *
     * @return the number of directories removed or restored
     */
    public static int recoverDirectories(DataDir dataDir) throws IOException {
        int count = 0;
        if (Files.isDirectory(dataDir.media())) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(dataDir.media(), ".*")) {
                for (Path d : dirs) {
                    String n = d.getFileName().toString();
                    if (n.startsWith(WORK_PREFIX) || n.startsWith(PURGE_PREFIX)) {
                        deleteTree(d);
                        count++;
                    } else if (n.startsWith(OLD_PREFIX)) {
                        String itemId = oldItemId(n);
                        Path target = itemId == null ? null : dataDir.media().resolve(itemId);
                        if (target != null && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            rename(d, target);
                            LOG.log(System.Logger.Level.WARNING, "Restored " + target
                                    + " after an interrupted publish");
                        } else {
                            deleteTree(d);
                        }
                        count++;
                    }
                }
            }
        }
        Path staging = dataDir.tmp().resolve("ingest");
        if (Files.isDirectory(staging)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(staging, PURGE_PREFIX + "*")) {
                for (Path d : dirs) {
                    deleteTree(d);
                    count++;
                }
            }
        }
        Path jobs = dataDir.tmp().resolve("jobs");
        if (Files.isDirectory(jobs)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(jobs)) {
                for (Path d : dirs) {
                    deleteTree(d);
                    count++;
                }
            }
        }
        return count;
    }

    /** The item id in {@code .old-<itemId>-<jobId>} (both UUIDs, 36 characters). */
    static String oldItemId(String name) {
        String rest = name.substring(OLD_PREFIX.length());
        if (rest.length() != 36 + 1 + 36 || rest.charAt(36) != '-') {
            return null;
        }
        return rest.substring(0, 36);
    }

    /**
     * Renames {@code dir} to {@code <parent>/.purge-<name>-<random>} (same file system, so the
     * rename is atomic), to be deleted with {@link #deleteTree} later. Files already open keep
     * working.
     *
     * @return the new path, or null if {@code dir} doesn't exist
     */
    public static Path moveAside(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path target = dir.resolveSibling(PURGE_PREFIX + dir.getFileName() + "-" + UuidV7.newId());
        rename(dir, target);
        return target;
    }

    /** Renames a directory {@link #moveAside moved aside} back to where it was. */
    public static void moveBack(Path aside, Path dir) throws IOException {
        rename(aside, dir);
    }

    /** Deletes a file or directory tree (symlinks themselves, not their targets); no-op if missing. */
    public static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (exc instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null && !(exc instanceof NoSuchFileException)) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not delete " + path + ": " + e.getMessage());
        }
    }

    /** For tests and logs. */
    @Override
    public String toString() {
        ReferenceCard r = reference.get();
        return "IngestPipeline[" + (r == null ? "no reference" : r.encoding().id()) + "]";
    }
}
