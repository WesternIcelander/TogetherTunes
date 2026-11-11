package io.siggi.togethertunes.fonts;

import io.siggi.togethertunes.settings.CjkVariant;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntToLongFunction;
import java.util.regex.Pattern;

/**
 * The server's card fonts ({@code docs/library-and-ingest.md#card-fonts}): the bundled DejaVu Sans,
 * the script fonts downloaded from the {@link FontManifest} into {@code <data>/fonts/}, the fonts
 * an admin uploaded ({@code <data>/fonts/uploads/}) and the installed system fonts. It builds the
 * {@link CardFonts} chain from them and the settings ({@code cards.font}, {@code fonts.cjkVariant})
 * and installs it for every card renderer.
 *
 * <p><b>Downloads</b> run one at a time on their own thread, deduplicated (a font queued or
 * downloading isn't queued again); a failed download is retried after a backoff
 * ({@link #DEFAULT_BACKOFF}) up to {@link #MAX_ATTEMPTS} times. A download that lands, a removed
 * font or a changed setting rebuilds the chain, and when the chain changed, the
 * {@link #changeListener change listeners} re-render the cards. <b>On demand:</b> the chain reports
 * characters no font has ({@link CardFonts.MissingListener}); with {@code fonts.autoDownload} on,
 * the manifest font for each is queued.
 */
public final class FontLibrary implements CardFonts.MissingListener, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(FontLibrary.class.getName());

    /** Automatic attempts per font before it stays failed (a manual Download starts over). */
    public static final int MAX_ATTEMPTS = 5;
    /** 1 min, 5 min, 25 min, then 1 h between attempts. */
    public static final IntToLongFunction DEFAULT_BACKOFF = attempt -> Math.min(3_600_000L,
            60_000L * (long) Math.pow(5, Math.max(0, attempt - 1)));
    /** The largest font file an admin can upload. */
    public static final long MAX_UPLOAD_BYTES = 32L * 1024 * 1024;
    /** How many uncovered characters {@link #uncovered} lists at most. */
    public static final int MAX_UNCOVERED = 200;

    static final String UPLOADS = "uploads";
    private static final Pattern UPLOAD_ID = Pattern.compile("[0-9a-f]{16}");

    /** Where a font is. */
    public enum State {
        NOT_DOWNLOADED, QUEUED, DOWNLOADING, INSTALLED, FAILED;

        /** {@code not_downloaded}, {@code queued}, ... */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A manifest font's state; {@code error} and {@code retryAtMs} only after a failure (retryAtMs null: no retry). */
    public record FontStatus(FontManifest.Entry entry, State state, String error, int attempts, Long retryAtMs) {
    }

    /** An uploaded font. {@code id} is the first 16 hex digits of its SHA-256. */
    public record Upload(String id, String family, String fileName, long size) {
    }

    /** A character of the item texts that no available font has, how often it occurs, and the font that would cover it. */
    public record Uncovered(int codePoint, String script, int count, String fontId) {
    }

    /** The settings the chain is built from (read on each rebuild). */
    public interface Prefs {
        /** {@code cards.font}: null for the bundled font. */
        String cardFont() throws Exception;

        boolean autoDownload() throws Exception;

        CjkVariant cjkVariant() throws Exception;
    }

    /** The texts cards are drawn from (item titles, artists, albums), for {@link #uncovered}. */
    @FunctionalInterface
    public interface Texts {
        void forEach(java.util.function.Consumer<String> text) throws Exception;
    }

    private final Path dir;
    private final FontManifest manifest;
    private final FontDownloader downloader;
    private final SystemFonts system;
    private final Prefs prefs;
    private final IntToLongFunction backoff;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private final Map<String, Face> installedFonts = new HashMap<>();
    private final Map<String, Status> states = new HashMap<>();
    private final Map<String, Upload> uploads = new TreeMap<>();
    private final Map<String, Face> uploadFaces = new HashMap<>();
    private volatile CardFonts current;

    /** Mutable download state of one manifest font (guarded by {@link #lock}). */
    private static final class Status {
        State state = State.NOT_DOWNLOADED;
        String error;
        int attempts;
        Long retryAtMs;
        ScheduledFuture<?> retry;
    }

    /**
     * @param dir     {@code <data>/fonts}
     * @param backoff the wait before automatic attempt {@code n + 1} after {@code n} failures, in ms
     */
    public FontLibrary(Path dir, FontManifest manifest, FontDownloader downloader, SystemFonts system, Prefs prefs,
                       IntToLongFunction backoff, Clock clock) {
        this.dir = Objects.requireNonNull(dir);
        this.manifest = Objects.requireNonNull(manifest);
        this.downloader = Objects.requireNonNull(downloader);
        this.system = Objects.requireNonNull(system);
        this.prefs = Objects.requireNonNull(prefs);
        this.backoff = Objects.requireNonNull(backoff);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("font-download").factory());
        for (FontManifest.Entry e : manifest.entries()) {
            states.put(e.id(), new Status());
        }
    }

    public FontManifest manifest() {
        return manifest;
    }

    public SystemFonts system() {
        return system;
    }

    /** Runs after the chain changed (a font landed or was removed, or a setting changed), on the thread that changed it. */
    public void changeListener(Runnable listener) {
        changeListeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Reads {@code <data>/fonts/}: downloaded fonts whose size and SHA-256 still match the manifest
     * are used (others and leftover partial downloads are deleted), uploads that load are listed;
     * then builds and installs the chain.
     */
    public CardFonts start() throws IOException {
        Files.createDirectories(dir.resolve(UPLOADS));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, ".*.part")) {
            for (Path p : files) {
                Files.deleteIfExists(p);
            }
        }
        for (FontManifest.Entry e : manifest.entries()) {
            Path file = dir.resolve(e.file());
            if (!Files.exists(file)) {
                continue;
            }
            if (!FontDownloader.matches(e, file)) {
                LOG.log(System.Logger.Level.WARNING, "Deleting " + file + ": it doesn't match the font manifest");
                Files.deleteIfExists(file);
                continue;
            }
            try {
                Face face = downloadedFace(e, file);
                synchronized (lock) {
                    installedFonts.put(e.id(), face);
                    states.get(e.id()).state = State.INSTALLED;
                }
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Can't load the font " + file + ": " + ex.getMessage());
            }
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir.resolve(UPLOADS))) {
            for (Path p : files) {
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot < 0 || !UPLOAD_ID.matcher(name.substring(0, dot)).matches()) {
                    continue;
                }
                try {
                    Font font = FontDownloader.loadFont(p);
                    addUploadFace(name.substring(0, dot), font, name, Files.size(p));
                } catch (IOException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Can't load the uploaded font " + p + ": " + ex.getMessage());
                }
            }
        }
        return rebuild();
    }

    /** The chain in use. */
    public CardFonts current() {
        CardFonts c = current;
        return c != null ? c : CardFonts.installed();
    }

    /**
     * Builds the chain from the fonts and settings now and installs it ({@link CardFonts#install});
     * tells the change listeners if it differs from the previous one.
     */
    public CardFonts rebuild() {
        CardFonts built;
        boolean changed;
        synchronized (lock) {
            Face bundled = BundledFonts.dejaVuSans();
            Face preferred = preferredFace(read(prefs::cardFont, null));
            List<CardFonts.Downloaded> downloaded = new ArrayList<>();
            for (FontManifest.Entry e : manifest.entries()) {
                Face f = installedFonts.get(e.id());
                if (f != null) {
                    downloaded.add(new CardFonts.Downloaded(e, f));
                }
            }
            CjkVariant cjk = read(prefs::cjkVariant, CjkVariant.JP);
            built = new CardFonts(preferred, bundled, downloaded, system, cjk == null ? CjkVariant.JP : cjk, this);
            CardFonts previous = current;
            changed = previous == null || !previous.id().equals(built.id());
            current = built;
            CardFonts.install(built);
        }
        if (changed) {
            for (Runnable l : changeListeners) {
                try {
                    l.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "A font change listener failed", e);
                }
            }
        }
        return built;
    }

    /** The face {@code cards.font} names; the bundled one when unset or not available. */
    private Face preferredFace(String setting) {
        Face bundled = BundledFonts.dejaVuSans();
        if (setting == null || setting.isBlank() || setting.equals("bundled")) {
            return bundled;
        }
        Face face = null;
        if (setting.startsWith("system:")) {
            face = system.family(setting.substring("system:".length()));
        } else if (setting.startsWith("upload:")) {
            face = uploadFaces.get(setting.substring("upload:".length()));
        }
        if (face == null) {
            LOG.log(System.Logger.Level.WARNING, "cards.font " + setting + " isn't available; using " + bundled.label());
            return bundled;
        }
        return face;
    }

    /**
     * Checks a {@code cards.font} value: unset, {@code bundled}, {@code system:<installed family>}
     * or {@code upload:<uploaded id>}.
     *
     * @throws IllegalArgumentException with the reason
     */
    public void validateCardFont(String value) {
        if (value == null || value.isEmpty() || value.equals("bundled")) {
            return;
        }
        if (value.startsWith("system:")) {
            if (system.family(value.substring("system:".length())) == null) {
                throw new IllegalArgumentException("cards.font: no installed font family "
                        + value.substring("system:".length()));
            }
            return;
        }
        if (value.startsWith("upload:")) {
            synchronized (lock) {
                if (!uploadFaces.containsKey(value.substring("upload:".length()))) {
                    throw new IllegalArgumentException("cards.font: no uploaded font " + value.substring("upload:".length()));
                }
            }
            return;
        }
        throw new IllegalArgumentException("cards.font: expected bundled, system:<family> or upload:<id>, got " + value);
    }

    private static <T> T read(java.util.concurrent.Callable<T> setting, T fallback) {
        try {
            return setting.call();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read a font setting: " + e.getMessage());
            return fallback;
        }
    }

    private Face downloadedFace(FontManifest.Entry e, Path file) throws IOException {
        return new Face("noto:" + e.id() + ":" + e.sha256().substring(0, 12), e.name(), FontDownloader.loadFont(file),
                null);
    }

    // ------------------------------------------------------------------ downloads

    /**
     * Queues the download of manifest font {@code id} (nothing if it is installed, queued or
     * downloading). {@code manual}: an admin asked, so a failed font starts over.
     *
     * @return false if there is no such font
     */
    public boolean download(String id, boolean manual) {
        FontManifest.Entry entry = manifest.entry(id);
        if (entry == null) {
            return false;
        }
        synchronized (lock) {
            Status s = states.get(id);
            switch (s.state) {
                case INSTALLED, QUEUED, DOWNLOADING -> {
                    return true;
                }
                case FAILED -> {
                    if (!manual && (s.retry != null || s.attempts >= MAX_ATTEMPTS)) {
                        return true;
                    }
                    if (s.retry != null) {
                        s.retry.cancel(false);
                        s.retry = null;
                    }
                    if (manual) {
                        s.attempts = 0;
                    }
                }
                default -> {
                }
            }
            s.state = State.QUEUED;
            s.retryAtMs = null;
            executor.execute(() -> run(entry));
        }
        return true;
    }

    /** Queues every manifest font that isn't installed; returns their total size in bytes. */
    public long downloadAll() {
        long bytes = 0;
        for (FontManifest.Entry e : manifest.entries()) {
            synchronized (lock) {
                if (states.get(e.id()).state == State.INSTALLED) {
                    continue;
                }
            }
            bytes += e.size();
            download(e.id(), true);
        }
        return bytes;
    }

    private void run(FontManifest.Entry entry) {
        synchronized (lock) {
            Status s = states.get(entry.id());
            if (s.state != State.QUEUED) {
                return;
            }
            s.state = State.DOWNLOADING;
            s.retry = null;
        }
        try {
            Path file = downloader.download(entry, dir);
            Face face = downloadedFace(entry, file);
            synchronized (lock) {
                installedFonts.put(entry.id(), face);
                Status s = states.get(entry.id());
                s.state = State.INSTALLED;
                s.error = null;
                s.attempts = 0;
                s.retryAtMs = null;
            }
            LOG.log(System.Logger.Level.INFO, "Downloaded the font " + entry.name());
            rebuild();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(entry, "interrupted");
        } catch (Exception e) {
            failed(entry, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void failed(FontManifest.Entry entry, String error) {
        synchronized (lock) {
            Status s = states.get(entry.id());
            s.state = State.FAILED;
            s.error = error;
            s.attempts++;
            s.retryAtMs = null;
            if (s.attempts < MAX_ATTEMPTS && !executor.isShutdown()) {
                long wait = backoff.applyAsLong(s.attempts);
                s.retryAtMs = clock.millis() + wait;
                s.retry = executor.schedule(() -> {
                    synchronized (lock) {
                        if (s.state != State.FAILED || s.retry == null) {
                            return;
                        }
                        s.state = State.QUEUED;
                        s.retryAtMs = null;
                    }
                    run(entry);
                }, wait, TimeUnit.MILLISECONDS);
            }
        }
        LOG.log(System.Logger.Level.WARNING, "Could not download the font " + entry.name() + ": " + error);
    }

    /**
     * Deletes the downloaded font {@code id} and rebuilds the chain.
     *
     * @return false if there is no such font
     * @throws IllegalStateException while it is queued or downloading
     */
    public boolean remove(String id) throws IOException {
        FontManifest.Entry entry = manifest.entry(id);
        if (entry == null) {
            return false;
        }
        synchronized (lock) {
            Status s = states.get(id);
            if (s.state == State.QUEUED || s.state == State.DOWNLOADING) {
                throw new IllegalStateException("The font is being downloaded");
            }
            if (s.retry != null) {
                s.retry.cancel(false);
                s.retry = null;
            }
            Files.deleteIfExists(dir.resolve(entry.file()));
            installedFonts.remove(id);
            s.state = State.NOT_DOWNLOADED;
            s.error = null;
            s.attempts = 0;
            s.retryAtMs = null;
        }
        rebuild();
        return true;
    }

    /** Every manifest font with its state, in manifest order. */
    public List<FontStatus> status() {
        List<FontStatus> out = new ArrayList<>();
        synchronized (lock) {
            for (FontManifest.Entry e : manifest.entries()) {
                Status s = states.get(e.id());
                out.add(new FontStatus(e, s.state, s.error, s.attempts, s.retryAtMs));
            }
        }
        return out;
    }

    /** One font's state, or null if there is no such font. */
    public FontStatus status(String id) {
        for (FontStatus s : status()) {
            if (s.entry().id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ on demand

    @Override
    public void missing(Set<Integer> codePoints, CjkVariant variant) {
        if (!Boolean.TRUE.equals(read(prefs::autoDownload, Boolean.TRUE))) {
            return;
        }
        for (int cp : codePoints) {
            for (FontManifest.Entry e : ScriptFonts.candidates(manifest, cp, variant)) {
                State state;
                synchronized (lock) {
                    state = states.get(e.id()).state;
                }
                if (state == State.INSTALLED) {
                    continue; // installed but without this glyph: try the next candidate
                }
                download(e.id(), false);
                break;
            }
        }
    }

    /**
     * The characters of {@code texts} that no available font has, most frequent first (at most
     * {@link #MAX_UNCOVERED}), each with the manifest font that would cover it (null: none).
     */
    public List<Uncovered> uncovered(Texts texts) throws Exception {
        CardFonts fonts = current();
        Map<Integer, int[]> counts = new LinkedHashMap<>();
        Map<Integer, CjkVariant> variants = new HashMap<>();
        texts.forEach(text -> {
            if (text == null || text.isEmpty()) {
                return;
            }
            Set<Integer> missing = fonts.uncovered(text);
            if (missing.isEmpty()) {
                return;
            }
            CjkVariant v = ScriptFonts.cjkVariant(text, fonts.cjkPreference());
            for (int cp : missing) {
                counts.computeIfAbsent(cp, k -> new int[1])[0]++;
                variants.putIfAbsent(cp, v);
            }
        });
        List<Uncovered> out = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : counts.entrySet()) {
            int cp = e.getKey();
            List<FontManifest.Entry> candidates = ScriptFonts.candidates(manifest, cp, variants.get(cp));
            out.add(new Uncovered(cp, Character.UnicodeScript.of(cp).name(), e.getValue()[0],
                    candidates.isEmpty() ? null : candidates.get(0).id()));
        }
        out.sort(Comparator.comparingInt(Uncovered::count).reversed().thenComparingInt(Uncovered::codePoint));
        return out.size() > MAX_UNCOVERED ? List.copyOf(out.subList(0, MAX_UNCOVERED)) : out;
    }

    // ------------------------------------------------------------------ uploads

    /**
     * Stores an uploaded font (TrueType or OpenType, at most {@link #MAX_UPLOAD_BYTES}) after
     * checking that Java loads it. Uploading the same file again returns the existing one.
     *
     * @throws IllegalArgumentException if it is too big or not a usable font
     */
    public Upload addUpload(InputStream body) throws IOException {
        Path uploadsDir = dir.resolve(UPLOADS);
        Files.createDirectories(uploadsDir);
        Path tmp = Files.createTempFile(uploadsDir, ".upload-", ".part");
        try {
            MessageDigest sha = FontDownloader.sha256();
            long total = 0;
            byte[] head = new byte[4];
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                for (int n; (n = body.read(buf)) > 0; ) {
                    if (total < 4) {
                        System.arraycopy(buf, 0, head, (int) total, (int) Math.min(n, 4 - total));
                    }
                    total += n;
                    if (total > MAX_UPLOAD_BYTES) {
                        throw new IllegalArgumentException("The font file is bigger than "
                                + MAX_UPLOAD_BYTES / (1024 * 1024) + " MiB");
                    }
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            Font font;
            try {
                font = FontDownloader.loadFont(tmp);
            } catch (IOException e) {
                throw new IllegalArgumentException("Not a usable TrueType or OpenType font: " + e.getMessage());
            }
            String id = FontDownloader.hex(sha.digest()).substring(0, 16);
            boolean otf = head[0] == 'O' && head[1] == 'T' && head[2] == 'T' && head[3] == 'O';
            String fileName = id + (otf ? ".otf" : ".ttf");
            synchronized (lock) {
                Upload existing = uploads.get(id);
                if (existing != null) {
                    return existing;
                }
                FontDownloader.move(tmp, uploadsDir.resolve(fileName));
                return addUploadFace(id, font, fileName, total);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Upload addUploadFace(String id, Font font, String fileName, long size) {
        String family = font.getFamily(Locale.ROOT);
        Upload upload = new Upload(id, family, fileName, size);
        synchronized (lock) {
            uploads.put(id, upload);
            uploadFaces.put(id, new Face("upload:" + id, family + " (uploaded)", font, null));
        }
        return upload;
    }

    /** The uploaded fonts, by id. */
    public List<Upload> uploads() {
        synchronized (lock) {
            return List.copyOf(uploads.values());
        }
    }

    /**
     * Deletes an uploaded font.
     *
     * @return false if there is no such upload
     * @throws IllegalStateException while it is the card font
     */
    public boolean removeUpload(String id) throws IOException {
        if (!UPLOAD_ID.matcher(id).matches()) {
            return false;
        }
        if (("upload:" + id).equals(read(prefs::cardFont, null))) {
            throw new IllegalStateException("The font is the card font; pick another one first");
        }
        synchronized (lock) {
            Upload u = uploads.remove(id);
            if (u == null) {
                return false;
            }
            uploadFaces.remove(id);
            Files.deleteIfExists(dir.resolve(UPLOADS).resolve(u.fileName()));
        }
        return true;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
