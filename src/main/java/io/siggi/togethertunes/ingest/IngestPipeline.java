package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
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
 * yt-dlp into its scratch directory first. ({@code reencode} jobs are a later step.)
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
 * <p><b>Publish.</b> Everything is written into a new directory {@code <data>/media/.ingest-<jobId>}
 * (same file system as the item directory) and swapped in by renames: the old
 * {@code media/<itemId>} (if any) is renamed aside to {@code media/.old-<itemId>-<jobId>}, the
 * new one renamed into place, and the old one deleted. Files already open (the packager) keep
 * reading the old ones. {@link #recoverDirectories} cleans up after a crash in the middle. Then the
 * item row gets its kind, duration, profile and loudness; the queue marks it {@code ready}. The
 * staging directory is deleted after a successful publish.
 */
public final class IngestPipeline implements IngestQueue.Handler {
    private static final System.Logger LOG = System.getLogger(IngestPipeline.class.getName());

    public static final String MEZZ = "mezz.mp4";
    public static final String AUDIO = "audio.m4a";
    public static final String CARD = "card.mp4";
    public static final String COVER = "cover.jpg";

    /** Base name of the staged source file of an upload ({@code source} or {@code source.<ext>}). */
    public static final String SOURCE_NAME = "source";
    /** Base name of a staged cover image ({@code cover.<ext>}). */
    public static final String COVER_NAME = "cover";

    static final String WORK_PREFIX = ".ingest-";
    static final String OLD_PREFIX = ".old-";
    /** Longest edge of {@code cover.jpg}. */
    static final int COVER_MAX = 1200;

    /** Share of the progress bar for a YouTube download; the rest is scaled into what's left. */
    static final double DOWNLOAD_SHARE = 0.35;
    static final double MEASURE_SHARE = 0.15;
    static final double ENCODE_SHARE = 0.75;

    private static final Pattern YTDLP_PERCENT = Pattern.compile("^\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");

    private final Tools tools;
    private final DataDir dataDir;
    private final IngestItems items;
    private final ReferenceCard reference;
    private final long maxDurationUs;

    /**
     * @param reference      the reference card of the configured output profile; null if it could
     *                       not be made at startup (then every job fails with a clear message)
     * @param maxDurationSec {@code ingest.maxDurationSeconds}
     */
    public IngestPipeline(Tools tools, DataDir dataDir, IngestItems items, ReferenceCard reference,
                          int maxDurationSec) {
        this.tools = Objects.requireNonNull(tools);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.items = Objects.requireNonNull(items);
        this.reference = reference;
        this.maxDurationUs = maxDurationSec * 1_000_000L;
    }

    /** The staging directory of an item's upload: {@code <data>/tmp/ingest/<itemId>}. */
    public static Path stagingDir(DataDir dataDir, String itemId) {
        return dataDir.tmp().resolve("ingest").resolve(checkId(itemId));
    }

    /** An item's published directory: {@code <data>/media/<itemId>}. */
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
        if (reference == null) {
            throw new IngestFailure("Ingest is unavailable: the server could not create its reference card at startup"
                    + " (are ffmpeg and ffprobe installed?)");
        }
        IngestItems.Item item = items.get(job.itemId())
                .orElseThrow(() -> new IngestFailure("The item no longer exists"));
        if (job.kind() == Kind.REENCODE) {
            throw new IngestFailure("Re-encoding for a new output profile isn't implemented yet");
        }
        Path scratch = scratchDir(dataDir, job.id());
        Path work = dataDir.media().resolve(WORK_PREFIX + job.id());
        deleteTree(scratch);
        deleteTree(work);
        Files.createDirectories(scratch);
        Files.createDirectories(work);
        try {
            run(ctx, item, scratch, work);
        } finally {
            deleteQuietly(scratch);
            deleteQuietly(work);
        }
    }

    private void run(IngestQueue.Context ctx, IngestItems.Item item, Path scratch, Path work) throws Exception {
        IngestJob job = ctx.job();
        JobLog log = ctx.log();
        OutputProfile profile = reference.profile();
        Path staging = stagingDir(dataDir, item.id());
        boolean download = job.kind() == Kind.YOUTUBE || job.kind() == Kind.REFETCH;
        double base = download ? DOWNLOAD_SHARE : 0;
        double rest = 1 - base;

        // (download) / staged source
        Path source;
        if (download) {
            step(log, "download");
            source = download(item, scratch, log, range(ctx, 0, base));
        } else {
            source = staged(staging, SOURCE_NAME);
            if (source == null) {
                throw new IngestFailure("The uploaded file is missing");
            }
        }
        Path stagedCover = staged(staging, COVER_NAME);

        // probe
        step(log, "probe");
        MediaProbe probe = MediaProbe.probe(tools, source, log);
        checkProbe(probe);
        boolean video = probe.isVideo();
        log.append("[ingest] " + (video ? "video" : "audio") + " item: audio stream " + probe.audio().index() + " ("
                + probe.audio().codec() + ")" + (video ? ", video stream " + probe.video().index() + " ("
                + probe.video().codec() + ")" : "") + (probe.cover() != null ? ", cover stream " + probe.cover().index() : "")
                + ", duration " + seconds(probe.durationUs()));
        long expectedUs = probe.durationUs() > 0 ? probe.durationUs() : probe.audioEndUs();

        // loudnorm pass 1
        step(log, "loudnorm pass 1");
        double measureFrom = base;
        double measureTo = base + rest * MEASURE_SHARE;
        DoubleConsumer measureProgress = range(ctx, measureFrom, measureTo);
        Loudness.Measurement loudness = Loudness.measure(tools, source.toString(), probe.audio().map(), log,
                p -> measureProgress.accept(p.fraction(expectedUs)));
        if (loudness.usable()) {
            log.append(String.format(Locale.ROOT, "[ingest] measured %.2f LUFS, %.2f dBTP, LRA %.2f LU",
                    loudness.inputI(), loudness.inputTp(), loudness.inputLra()));
        } else {
            log.append("[ingest] the loudness could not be measured (silence?); encoding without normalisation");
        }
        long sourceAudioEndUs = probe.audioEndUs() > 0 ? probe.audioEndUs() : probe.readAudioEndUs(tools, source, log);

        // encode
        step(log, "encode");
        double encodeTo = measureTo + rest * ENCODE_SHARE;
        DoubleConsumer encodeProgress = range(ctx, measureTo, encodeTo);
        Path mezz = work.resolve(MEZZ);
        Path audio = work.resolve(AUDIO);
        List<String> encode = video
                ? videoEncodeArgs(source, probe, loudness, profile, mezz)
                : audioEncodeArgs(source, probe, loudness, audio);
        tools.ffmpeg(encode).log(log).ffmpegProgress(p -> encodeProgress.accept(p.fraction(expectedUs))).run();
        double after = encodeTo;
        double stepShare = (1 - encodeTo) / 5;

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
        BufferedImage card = new CardRenderer(profile).renderItem(
                new CardRenderer.ItemCard(item.title(), item.artist(), item.album(), artwork));
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
        IngestVerifier.verify(index, reference, sourceAudioEndUs, videoFile.getFileName().toString());
        long durationUs = IngestVerifier.audioDurationUs(index);
        log.append("[ingest] verified: audio " + seconds(durationUs) + " (source " + seconds(sourceAudioEndUs)
                + "), avcC matches the " + profile.id() + " reference, " + index.track(SampleIndex.Kind.VIDEO).sampleCount()
                + " frames with an IDR every 2 s");
        ctx.progress(after += stepShare);

        // publish
        step(log, "publish");
        publish(work, itemDir(dataDir, item.id()), item.id(), job.id());
        Double lufs = loudness.usable() ? round2(loudness.inputI()) : null;
        items.published(item.id(), video, durationUs / 1000, profile, lufs);
        deleteQuietly(staging);
        ctx.progress(1);
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

    void checkProbe(MediaProbe probe) throws IngestFailure {
        if (probe.audio() == null) {
            throw new IngestFailure("The file has no audio");
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
                                        OutputProfile profile, Path out) {
        List<String> args = new ArrayList<>(List.of("-y", "-i", source.toString(),
                "-map", probe.video().map(), "-map", probe.audio().map(),
                "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", EncodeProfile.scaleFilter(profile)));
        args.addAll(EncodeProfile.videoArgs(profile));
        args.addAll(List.of("-af", loudness.filter()));
        args.addAll(EncodeProfile.AUDIO_ARGS);
        args.addAll(List.of("-movflags", "+faststart", "-f", "mp4", out.toString()));
        return args;
    }

    static List<String> audioEncodeArgs(Path source, MediaProbe probe, Loudness.Measurement loudness, Path out) {
        List<String> args = new ArrayList<>(List.of("-y", "-i", source.toString(),
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
        List<String> args = new ArrayList<>(List.of("--no-playlist", "--newline", "--no-mtime",
                "-f", "bv*[height<=1080]+ba/b", "--merge-output-format", "mkv",
                "-o", dir.resolve("src.%(ext)s").toString()));
        if (ffmpeg != null && ffmpeg.contains("/")) {
            args.addAll(List.of("--ffmpeg-location", ffmpeg));
        }
        args.add("--");
        args.add("https://www.youtube.com/watch?v=" + youtubeId);
        return args;
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
        tools.ytdlp(downloadArgs(id, scratch, tools.ffmpegExecutable())).log(log).stdoutLines(lines).run();
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

    /**
     * Swaps {@code work} in as {@code target}: renames an existing {@code target} aside, renames
     * {@code work} to {@code target}, then deletes the old directory. If the second rename fails,
     * the old directory is put back.
     */
    static void publish(Path work, Path target, String itemId, String jobId) throws IOException {
        Path old = target.resolveSibling(OLD_PREFIX + itemId + "-" + jobId);
        deleteTree(old);
        boolean hadOld = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (hadOld) {
            rename(target, old);
        }
        try {
            rename(work, target);
        } catch (IOException e) {
            if (hadOld) {
                try {
                    rename(old, target);
                } catch (IOException e2) {
                    e.addSuppressed(e2);
                }
            }
            throw e;
        }
        if (hadOld) {
            deleteQuietly(old);
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
     * deletes half-built {@code media/.ingest-*} directories and {@code tmp/jobs/*}; an
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
                    if (n.startsWith(WORK_PREFIX)) {
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

    /** Deletes a file or directory tree (symlinks themselves, not their targets); no-op if missing. */
    static void deleteTree(Path path) throws IOException {
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

    private static void deleteQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not delete " + path + ": " + e.getMessage());
        }
    }

    /** For tests and logs. */
    @Override
    public String toString() {
        return "IngestPipeline[" + (reference == null ? "no reference" : reference.profile().id()) + "]";
    }
}
