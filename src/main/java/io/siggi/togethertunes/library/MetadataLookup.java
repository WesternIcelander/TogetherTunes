package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.OriginalCleaner;
import io.siggi.togethertunes.ingest.PurchaseData;
import io.siggi.togethertunes.ingest.ToolCall;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Looks song details up online with TrackSplitter ({@code docs/library-and-ingest.md#looking-details-up-online}):
 * runs {@code tracksplitter metadata --lookup --json …} (without {@code --lookup} it only reads its
 * cache; never {@code -o} or {@code --in-place}, so it writes no file of ours) and parses its JSON: one track (with its album's cover in TrackSplitter's cache), the
 * albums that have the track (without an album), or nothing.
 *
 * <ul>
 *   <li><b>One process at a time</b>, server-wide ({@link #exclusive}): the others wait in a short
 *       queue; a person's lookup waits at most {@link #QUEUE_WAIT} ({@code 503 lookup_busy}), and goes
 *       before background work (the automatic lookup of new uploads), which waits as long as it takes.</li>
 *   <li><b>{@link #TIMEOUT}</b> per process ({@code 504 lookup_timeout}).</li>
 *   <li><b>Per user</b> at most {@link #PER_HOUR} lookups an hour, counted when one starts
 *       ({@code 429 too_many_lookups} with {@code Retry-After}).</li>
 *   <li>Any other failure (exit code 2 or more, exit 1 without a result, JSON that can't be read)
 *       is {@code 502 lookup_failed}; stderr goes to the log, never to the caller.</li>
 * </ul>
 *
 * <p>Catalogue names (the album's {@code source}) and TrackSplitter's {@code notices} are logged and
 * kept server-side only; callers learn that there were notices ({@link Result#notices}) and show
 * {@link #NOTICE_TEXT}.
 *
 * <p>It also removes personal data from audio uploads ({@link #clean}, {@link OriginalCleaner}):
 * {@code metadata --no-lookup --in-place} on a scratch copy, never online, not counted against the
 * hourly limit, in the same one-at-a-time queue as background work.
 */
public final class MetadataLookup implements OriginalCleaner {
    private static final System.Logger LOG = System.getLogger(MetadataLookup.class.getName());

    /** How long a person's lookup waits for the running one. */
    public static final Duration QUEUE_WAIT = Duration.ofSeconds(60);
    /** How long one TrackSplitter process may take. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** How long one cleaning ({@link #clean}) may take: the whole file may be rewritten. */
    public static final Duration CLEAN_TIMEOUT = Duration.ofSeconds(120);
    /** Lookups per user per hour. */
    public static final int PER_HOUR = 30;
    /** How many albums a lookup without an album lists. */
    public static final int LIST_LIMIT = 10;
    /** What people are told when TrackSplitter had notices (e.g. a catalogue rate limiting). */
    public static final String NOTICE_TEXT = "Some catalogues couldn't be searched right now; try again later.";
    /** Most bytes of TrackSplitter's JSON read. */
    static final int MAX_STDOUT = 4 * 1024 * 1024;
    private static final long HOUR_MS = 3_600_000L;

    /** Who is waiting: a person ({@link #QUEUE_WAIT}) or background work (goes after people). */
    public enum Priority { INTERACTIVE, BACKGROUND }

    /**
     * What to look up.
     *
     * @param file    the item's original, when it is {@code .m4a}, {@code .mp3} or {@code .flac} (TrackSplitter
     *                also uses its embedded cover and store IDs; {@code --json} alone changes no file); or null
     * @param artwork the item's cover, given as {@code --artwork} when there is no such file; or null
     */
    public record Query(String title, String artist, String album, String albumArtist, Path file, Path artwork) {
    }

    /**
     * One track TrackSplitter found.
     *
     * @param details    the song details (cleaned and checked like an edit; values that don't fit are left out)
     * @param artwork    the album's cover in TrackSplitter's cache (a full lookup), or null
     * @param album      TrackSplitter's {@code album} object (source, ID, URL, how it was matched), or null;
     *                   kept in {@code items.metadata_json} under {@code lookup}, never shown
     * @param matchedBy  {@code artwork}, {@code appleId} or {@code name}; null when not identified
     * @param namesOnly  matched by names with no cover to compare
     */
    public record Track(String title, String artist, String album, SongDetails details, Path artwork,
                        JsonObject albumInfo, String matchedBy, boolean namesOnly) {
        /** Whether the album was identified by its cover or a store ID (not by names only). */
        public boolean matchedByCoverOrId() {
            return "artwork".equals(matchedBy) || "appleId".equals(matchedBy);
        }
    }

    /** A lookup's outcome; {@code notices}: TrackSplitter had notices (logged; shown as {@link #NOTICE_TEXT}). */
    public sealed interface Result permits Found, Albums, NotFound {
        boolean notices();
    }

    /** One track (a lookup with an album, or with a file). */
    public record Found(Track track, boolean notices) implements Result {
    }

    /** The albums that have the track (a lookup without an album), in TrackSplitter's order. */
    public record Albums(List<Track> albums, boolean notices) implements Result {
        public Albums {
            albums = List.copyOf(albums);
        }
    }

    /** Nothing found. */
    public record NotFound(boolean notices) implements Result {
    }

    private final String executable;
    private final Path cacheDir;
    private final String userAgent;
    private final String ffmpeg;
    private final Clock clock;
    private volatile Duration queueWait = QUEUE_WAIT;
    private volatile Duration timeout = TIMEOUT;
    private volatile Duration cleanTimeout = CLEAN_TIMEOUT;
    private final Map<String, Deque<Long>> starts = new HashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private boolean running;
    private int peopleWaiting;

    /**
     * @param executable TrackSplitter's path ({@code tools.tracksplitter}), or null when it isn't
     *                   installed ({@link #installed} false)
     * @param cacheDir   {@code tracksplitter.cacheDir} (absolute), passed as {@code --cache-dir}
     * @param userAgent  {@code --user-agent}: {@code TogetherTunes/<version> ( <publicBaseUrl> )}
     * @param ffmpeg     ffmpeg's path for TrackSplitter's {@code --ffmpeg}, or null for its default
     */
    public MetadataLookup(String executable, Path cacheDir, String userAgent, String ffmpeg, Clock clock) {
        this.executable = executable;
        this.cacheDir = cacheDir == null ? null : cacheDir.toAbsolutePath().normalize();
        this.userAgent = Objects.requireNonNull(userAgent);
        this.ffmpeg = ffmpeg;
        this.clock = Objects.requireNonNull(clock);
    }

    /** Whether TrackSplitter is installed (else the feature is off). */
    @Override
    public boolean installed() {
        return executable != null && cacheDir != null;
    }

    /** For tests: shorter waits. */
    void limits(Duration queueWait, Duration timeout) {
        this.queueWait = Objects.requireNonNull(queueWait);
        this.timeout = Objects.requireNonNull(timeout);
    }

    /** A shorter cleaning timeout than {@link #CLEAN_TIMEOUT} (for tests). */
    public void cleanTimeout(Duration cleanTimeout) {
        this.cleanTimeout = Objects.requireNonNull(cleanTimeout);
    }

    /** TrackSplitter's cache directory. */
    public Path cacheDir() {
        return cacheDir;
    }

    /** The arguments of a lookup (after the executable). */
    List<String> arguments(Query q) {
        // --lookup: without it, metadata only reads the cache
        List<String> args = new ArrayList<>(List.of("metadata", "--lookup", "--json", "--cache-dir",
                cacheDir.toString(), "--user-agent", userAgent));
        if (q.title() != null) {
            args.addAll(List.of("--title", q.title()));
        }
        if (q.artist() != null) {
            args.addAll(List.of("--artist", q.artist()));
        }
        if (q.album() != null) {
            args.addAll(List.of("--album", q.album()));
        }
        if (q.albumArtist() != null) {
            args.addAll(List.of("--album-artist", q.albumArtist()));
        }
        if (q.album() == null) {
            args.addAll(List.of("--limit", Integer.toString(LIST_LIMIT)));
        } else if (q.file() != null) {
            // its audio needn't be checked: nothing is written
            args.add("--no-decode-check");
            if (ffmpeg != null) {
                args.addAll(List.of("--ffmpeg", ffmpeg));
            }
            args.add("--");
            args.add(q.file().toString());
        } else if (q.artwork() != null) {
            args.addAll(List.of("--artwork", q.artwork().toString()));
        }
        return args;
    }

    /**
     * Looks a track up for {@code userId} (counted against their hourly limit).
     *
     * @throws HttpError {@code 429 too_many_lookups}, {@code 503 lookup_busy}, {@code 504 lookup_timeout},
     *                   {@code 502 lookup_failed}, {@code 403 feature_disabled} (not installed)
     */
    public Result lookup(String userId, Query query, Priority priority) throws InterruptedException {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(query);
        if (!installed()) {
            throw new HttpError(403, "feature_disabled", "Looking up song details online isn't available on this server");
        }
        checkLimit(userId, false);
        List<String> args = arguments(query);
        return exclusive(priority, () -> {
            checkLimit(userId, true);
            return run(args, query.artwork());
        });
    }

    /** Throws {@code 429 too_many_lookups} if {@code userId} used up the hour; {@code charge}: count one. */
    private void checkLimit(String userId, boolean charge) {
        long now = clock.millis();
        synchronized (starts) {
            Deque<Long> times = starts.computeIfAbsent(userId, k -> new ArrayDeque<>());
            while (!times.isEmpty() && times.peekFirst() <= now - HOUR_MS) {
                times.pollFirst();
            }
            if (times.size() >= PER_HOUR) {
                long retryMs = times.peekFirst() + HOUR_MS - now;
                throw new HttpError(429, "too_many_lookups", "You can look up details " + PER_HOUR
                        + " times an hour; try again later", Math.max(1, (retryMs + 999) / 1000));
            }
            if (charge) {
                times.addLast(now);
            }
        }
    }

    /**
     * Runs {@code work} while no other TrackSplitter process runs (server-wide). A person waits at
     * most {@link #QUEUE_WAIT} ({@code 503 lookup_busy}) and goes before background work, which
     * waits until nobody else is waiting.
     */
    public <T> T exclusive(Priority priority, Callable<T> work) throws InterruptedException {
        boolean person = priority == Priority.INTERACTIVE;
        lock.lockInterruptibly();
        try {
            if (person) {
                peopleWaiting++;
                try {
                    long left = queueWait.toNanos();
                    while (running) {
                        if (left <= 0) {
                            throw new HttpError(503, "lookup_busy",
                                    "Other lookups are running; try again in a minute");
                        }
                        left = changed.awaitNanos(left);
                    }
                } finally {
                    peopleWaiting--;
                }
            } else {
                while (running || peopleWaiting > 0) {
                    changed.await();
                }
            }
            running = true;
        } finally {
            lock.unlock();
        }
        try {
            return work.call();
        } catch (RuntimeException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            lock.lock();
            try {
                running = false;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private Result run(List<String> args, Path givenArtwork) throws InterruptedException {
        ToolResult result;
        try {
            result = new ToolCall("tracksplitter", executable, args).timeout(timeout).idleTimeout(timeout)
                    .stdoutToString(MAX_STDOUT).allowFailure()
                    .stderrLines(line -> LOG.log(System.Logger.Level.DEBUG, "tracksplitter: " + line))
                    .run();
        } catch (ToolException e) {
            if (e.kind() == ToolException.Kind.TIMEOUT || e.kind() == ToolException.Kind.IDLE_TIMEOUT) {
                LOG.log(System.Logger.Level.WARNING, "tracksplitter timed out: " + e.getMessage());
                throw new HttpError(504, "lookup_timeout", "The lookup took too long; try again later");
            }
            LOG.log(System.Logger.Level.WARNING, "tracksplitter failed: " + e.getMessage());
            throw failed();
        }
        Result parsed;
        try {
            parsed = parse(result.stdout(), result.exitCode(), cacheDir, givenArtwork);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "tracksplitter (exit " + result.exitCode() + "): " + e.getMessage()
                    + "; stderr: " + result.stderrTail());
            throw failed();
        }
        return parsed;
    }

    private static HttpError failed() {
        return new HttpError(502, "lookup_failed", "The details couldn't be looked up; try again later");
    }

    // ------------------------------------------------------------------ cleaning uploads

    /**
     * The arguments that clean {@code file} in place (after the executable): never online
     * ({@code --no-lookup}), the audio not decoded except where purchase info is to be removed
     * (TrackSplitter then still checks it plays without it), {@code --} before the path.
     */
    List<String> cleanArguments(Path file) {
        List<String> args = new ArrayList<>(List.of("metadata", "--no-lookup", "--json", "--in-place",
                "--no-decode-check", "--cache-dir", cacheDir.toString()));
        if (ffmpeg != null) {
            args.addAll(List.of("--ffmpeg", ffmpeg));
        }
        args.add("--");
        args.add(file.toAbsolutePath().toString());
        return args;
    }

    /**
     * Removes personal data from {@code file} (a scratch copy of an upload) with
     * {@code tracksplitter metadata --no-lookup --in-place}: waits for the other TrackSplitter
     * processes (as background work, after people's lookups), not counted against any hourly limit.
     * Clean means: exit code 0, no error for the file, and (an {@code .m4a}) no purchase data left
     * ({@link PurchaseData}).
     */
    @Override
    public String clean(Path file, JobLog log) throws InterruptedException {
        Objects.requireNonNull(file);
        if (!installed()) {
            return "TrackSplitter isn't installed";
        }
        List<String> args = cleanArguments(file);
        return exclusive(Priority.BACKGROUND, () -> runClean(args, file, log));
    }

    private String runClean(List<String> args, Path file, JobLog log) throws InterruptedException {
        boolean mp4 = file.getFileName().toString().endsWith(".m4a");
        Set<String> before;
        try {
            before = mp4 ? PurchaseData.find(file) : Set.of();
        } catch (IOException e) {
            return "the file can't be read: " + e.getMessage();
        }
        ToolResult result;
        try {
            result = new ToolCall("tracksplitter", executable, args).timeout(cleanTimeout).idleTimeout(cleanTimeout)
                    .stdoutToString(MAX_STDOUT).allowFailure()
                    .stderrLines(line -> LOG.log(System.Logger.Level.DEBUG, "tracksplitter: " + line))
                    .run();
        } catch (ToolException e) {
            if (e.kind() == ToolException.Kind.TIMEOUT || e.kind() == ToolException.Kind.IDLE_TIMEOUT) {
                return "TrackSplitter took longer than " + cleanTimeout.toSeconds() + " s";
            }
            return "TrackSplitter couldn't be run: " + e.reason();
        }
        String error = null;
        try {
            JsonElement e = JsonParser.parseString(new String(result.stdout(), StandardCharsets.UTF_8));
            if (e instanceof JsonObject doc && doc.get("tracks") instanceof JsonArray tracks && !tracks.isEmpty()
                    && tracks.get(0) instanceof JsonObject t) {
                error = string(t.get("error"));
            } else if (result.exitCode() == 0) {
                error = "its output names no file";
            }
        } catch (RuntimeException e) {
            if (result.exitCode() == 0) {
                error = "its output isn't JSON";
            }
        }
        if (result.exitCode() != 0) {
            LOG.log(System.Logger.Level.WARNING, "tracksplitter (cleaning, exit " + result.exitCode() + "): "
                    + (error != null ? error : result.stderrTail()));
            return "TrackSplitter failed (exit code " + result.exitCode() + ")" + (error != null ? ": " + error : "");
        }
        if (error != null) {
            return "TrackSplitter failed: " + error;
        }
        if (mp4) {
            Set<String> left;
            try {
                left = PurchaseData.find(file);
            } catch (IOException e) {
                return "the cleaned file can't be read: " + e.getMessage();
            }
            if (!left.isEmpty()) {
                // e.g. audio that doesn't decode without its purchase info (copy-protected or damaged)
                return "purchase data was left in place (" + String.join(", ", left)
                        + "); the audio may be copy-protected or damaged";
            }
        }
        if (log != null) {
            log.append("[ingest] personal data: " + (before.isEmpty() ? "none found"
                    : "removed " + String.join(", ", before)) + " (TrackSplitter, offline)");
        }
        return null;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses TrackSplitter's JSON. Exit code 0 with tracks: one track (a lookup with an album) or the
     * albums (without; several entries, or one whose {@code artwork} is an address). Exit code 0 or 1
     * without tracks: nothing found.
     *
     * @throws IllegalArgumentException for an exit code above 1, exit 1 with tracks, or JSON that isn't
     *                                  TrackSplitter's
     */
    static Result parse(byte[] stdout, int exitCode, Path cacheDir, Path givenArtwork) {
        if (exitCode != 0 && exitCode != 1) {
            throw new IllegalArgumentException("exit code " + exitCode);
        }
        JsonObject doc;
        try {
            JsonElement e = JsonParser.parseString(new String(stdout, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            doc = e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unreadable JSON: " + e.getMessage(), e);
        }
        boolean notices = false;
        if (doc.get("notices") instanceof JsonArray n && !n.isEmpty()) {
            notices = true;
            for (JsonElement line : n) {
                LOG.log(System.Logger.Level.INFO, "tracksplitter notice: " + line);
            }
        }
        if (!(doc.get("tracks") instanceof JsonArray tracks)) {
            throw new IllegalArgumentException("no tracks list");
        }
        if (tracks.isEmpty()) {
            return new NotFound(notices);
        }
        if (exitCode != 0) {
            throw new IllegalArgumentException("exit code 1 with tracks");
        }
        List<Track> out = new ArrayList<>();
        boolean list = tracks.size() > 1;
        for (JsonElement t : tracks) {
            if (!(t instanceof JsonObject o)) {
                throw new IllegalArgumentException("a track isn't an object");
            }
            if (o.has("error")) {
                LOG.log(System.Logger.Level.INFO, "tracksplitter: " + o.get("error"));
                continue;
            }
            String artwork = string(o.get("artwork"));
            if (artwork != null && (artwork.startsWith("http://") || artwork.startsWith("https://"))) {
                list = true; // only a list gives the cover as an address
            }
            out.add(track(o, list ? null : localArtwork(artwork, cacheDir, givenArtwork)));
        }
        if (out.isEmpty()) {
            return new NotFound(notices);
        }
        if (list) {
            List<Track> albums = new ArrayList<>();
            for (Track t : out) {
                albums.add(new Track(t.title(), t.artist(), t.album(), t.details(), null, t.albumInfo(),
                        t.matchedBy(), t.namesOnly()));
            }
            return new Albums(albums, notices);
        }
        Track only = out.get(0);
        if (only.albumInfo() == null) {
            // not identified: nothing to offer
            return new NotFound(notices);
        }
        return new Found(only, notices);
    }

    /** The cover file TrackSplitter names, if it is in its cache (never the image we gave it). */
    private static Path localArtwork(String artwork, Path cacheDir, Path givenArtwork) {
        if (artwork == null || cacheDir == null) {
            return null;
        }
        try {
            Path p = Path.of(artwork);
            if (!p.isAbsolute()) {
                p = p.toAbsolutePath();
            }
            p = p.normalize();
            if (givenArtwork != null && p.equals(givenArtwork.toAbsolutePath().normalize())) {
                return null;
            }
            Path real = p.toRealPath();
            if (!real.startsWith(cacheDir.toRealPath()) || !Files.isRegularFile(real)) {
                return null;
            }
            return real;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Track track(JsonObject o, Path artwork) {
        JsonObject tags = o.get("tags") instanceof JsonObject t ? t : new JsonObject();
        SongDetails d = SongDetails.EMPTY;
        if (tags.get("artists") instanceof JsonArray a) {
            List<String> names = new ArrayList<>();
            for (JsonElement n : a) {
                String s = string(n);
                if (s != null) {
                    names.add(s);
                }
            }
            List<String> cleaned = SongDetails.cleanNames(names);
            if (cleaned.size() >= 2 && cleaned.size() <= SongDetails.MAX_ARTISTS) {
                d = d.with("artists", cleaned);
            }
        }
        for (String f : List.of("albumArtist", "genre", "copyright", "label")) {
            d = d.with(f, text(tags.get(f)));
        }
        for (String f : List.of("track", "trackTotal", "disc", "discTotal")) {
            d = d.with(f, number(tags.get(f)));
        }
        d = d.with("isrc", SongDetails.isrc(string(tags.get("isrc"))));
        d = d.with("date", SongDetails.date(string(tags.get("date"))));
        d = d.with("barcode", SongDetails.barcode(string(tags.get("barcode"))));
        JsonObject album = o.get("album") instanceof JsonObject a ? a : null;
        String matchedBy = album == null ? null : string(album.get("matchedBy"));
        boolean namesOnly = album != null && album.get("namesOnly") instanceof JsonPrimitive p && p.isBoolean()
                && p.getAsBoolean();
        return new Track(text(tags.get("title")), text(tags.get("artist")), text(tags.get("album")), d, artwork,
                album == null ? null : album.deepCopy(), matchedBy, namesOnly);
    }

    private static String string(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static String text(JsonElement e) {
        String s = string(e);
        return s == null ? null : UploadMetadata.clean(s);
    }

    private static Integer number(JsonElement e) {
        if (e instanceof JsonPrimitive p) {
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d >= 1 && d <= SongDetails.MAX_NUMBER && d == Math.rint(d)) {
                    return (int) d;
                }
                return null;
            }
            if (p.isString()) {
                return SongDetails.number(p.getAsString());
            }
        }
        return null;
    }

    /** For tests: how long until {@code userId} may look up again (0 if now). */
    long retryInMs(String userId) {
        long now = clock.millis();
        synchronized (starts) {
            Deque<Long> times = starts.get(userId);
            if (times == null || times.size() < PER_HOUR) {
                return 0;
            }
            return Math.max(0, times.peekFirst() + HOUR_MS - now);
        }
    }
}
