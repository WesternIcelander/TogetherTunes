package io.siggi.togethertunes.output;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.ingest.FillerCards;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.output.FillerSource.Filler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The media one program output plays from ({@code docs/program-output.md#lifecycle}): it implements
 * the {@link TimelineFollower.SourceFactory} and keeps item indexes in memory <b>for the current and
 * next entry only</b> (plus the item still playing, until the output switches away from it). Sample
 * bytes are read from the item files with positional reads ({@link FileSampleData}); only an item's
 * card (one 2 s GOP) is held in memory.
 *
 * <ul>
 * <li>{@link #prepare} (called by the follower for every cue) starts loading the current and next
 * items in the background and drops every other loaded item, so the next item is normally ready
 * before the output gets to it.</li>
 * <li>{@link #item} returns a source if the item is loaded; otherwise it starts the load and waits
 * up to {@code waitMs} for it, then gives up (null: the follower plays the Loading filler and asks
 * again at the next unit boundary).</li>
 * <li>An index that can't be used — missing ({@code index.bin} isn't published yet, or the item was
 * purged), unreadable, for another output encoding (a re-encode for a new profile or video bitrate is still running,
 * {@code docs/library-and-ingest.md#re-encoding-on-a-profile-change}) or with an avcC that differs
 * from the reference (invariant 1) — counts as not loadable. It is retried no sooner than
 * {@link #RETRY_MS} later, and each distinct failure is logged once.</li>
 * </ul>
 *
 * <p>One instance per {@link TimelineFollower}: it relies on the follower playing every item source
 * it returns right away, and on the unit builder playing one source at a time. The item playing is
 * kept loaded even when it is neither current nor next (output behind the room). Threads:
 * {@link #prepare} runs on the room's thread, {@link #item} and {@link #filler} on the packager
 * thread, loads on {@code executor}.
 */
public final class ProgramMedia implements TimelineFollower.SourceFactory, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ProgramMedia.class.getName());

    /** A failed load is retried no sooner than this. */
    public static final long RETRY_MS = 5000;
    /** How long {@link #item} waits for an index that isn't loaded yet, by default. */
    public static final long DEFAULT_WAIT_MS = 300;

    /** Loads a card file that has no index (a video item's {@code card.mp4}, a filler card). */
    @FunctionalInterface
    public interface CardLoader {
        CardGop load(Path cardFile) throws IOException, InterruptedException;
    }

    /**
     * Where an item's files are: the directory of its current media version
     * ({@code media/<itemId>/v<N>}, {@code ingest.MediaVersions}). Asked at every load, so a play
     * that starts after a re-encode was published uses the new files, while a loaded item keeps
     * the files it opened.
     */
    @FunctionalInterface
    public interface ItemDirs {
        Path dir(String itemId) throws IOException;
    }

    /** A {@link CardLoader} that indexes the card with ffprobe ({@link CardGop#load}). */
    public static CardLoader ffprobeCardLoader(Tools tools, OutputEncoding profile) {
        return file -> {
            try {
                return CardGop.load(tools, profile, file, null);
            } catch (ToolException e) {
                throw new IOException("can't index card " + file.getFileName() + ": " + e.getMessage(), e);
            }
        };
    }

    /** The server's filler cards, loaded into memory. */
    public static Map<Filler, CardGop> loadFillerCards(FillerCards cards, CardLoader loader)
            throws IOException, InterruptedException {
        Map<Filler, CardGop> m = new EnumMap<>(Filler.class);
        for (Filler f : Filler.values()) {
            m.put(f, loader.load(cards.card(f.serverCard())));
        }
        return m;
    }

    /** What is known about one item: its loaded index and data, or the load in progress or failed. */
    private static final class Slot {
        final String itemId;
        /** The version directory {@link #data} was loaded from (null until loaded). */
        Path dir;
        boolean videoItem;
        /** Whether some entry plays this video item as audio, so its {@code card.mp4} is needed. */
        boolean cardWanted;

        SampleIndex index;
        FileSampleData data;
        /** Audio item: the card in its index; video item: its {@code card.mp4} (once wanted). */
        CardGop card;

        /** The load in progress (completes when it ends either way), or null. */
        CompletableFuture<Void> loading;
        IOException failure;
        long failedAtMs;
        String loggedFailure;

        Slot(String itemId) {
            this.itemId = itemId;
        }

        boolean ready(ProgramCue.Entry e) {
            return data != null && (!asAudioVideo(e) || card != null);
        }
    }

    private final ItemDirs dirs;
    private final OutputEncoding profile;
    private final byte[] referenceAvcC;
    private final CardLoader cardLoader;
    private final Map<Filler, CardGop> fillers;
    private final byte[] silence;
    private final Executor executor;
    private final long waitMs;
    private final LongSupplier nowMs;

    private final Map<String, Slot> slots = new HashMap<>();
    /** The items of the latest cue's current and next entries. */
    private Set<String> wanted = Set.of();
    /** The item of the source last handed to the follower, until it switches to a filler. */
    private String playing;
    private boolean closed;

    /**
     * @param mediaDir      {@code <data>/media}: item {@code id}'s files are in {@code mediaDir/id}
     *                      (the flat layout; tests)
     * @param profile       the output encoding; indexes for another profile or video bitrate aren't played
     * @param referenceAvcC the profile's reference avcC (invariant 1), or null to skip the check
     * @param cardLoader    loads a video item's {@code card.mp4} when it is played as audio
     * @param fillers       a card for every {@link Filler}
     * @param silence       the raw AAC silence frame
     * @param executor      runs the loads (e.g. a virtual thread per task)
     * @param waitMs        how long {@link #item} waits for a load
     * @param nowMs         the clock for {@link #RETRY_MS}
     */
    public ProgramMedia(Path mediaDir, OutputEncoding profile, byte[] referenceAvcC, CardLoader cardLoader,
            Map<Filler, CardGop> fillers, byte[] silence, Executor executor, long waitMs, LongSupplier nowMs) {
        this(flat(mediaDir), profile, referenceAvcC, cardLoader, fillers, silence, executor, waitMs, nowMs);
    }

    /** @param dirs where each item's current files are (see {@link ItemDirs}); the rest as above */
    public ProgramMedia(ItemDirs dirs, OutputEncoding profile, byte[] referenceAvcC, CardLoader cardLoader,
            Map<Filler, CardGop> fillers, byte[] silence, Executor executor, long waitMs, LongSupplier nowMs) {
        this.dirs = Objects.requireNonNull(dirs, "dirs");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.referenceAvcC = referenceAvcC == null ? null : referenceAvcC.clone();
        this.cardLoader = Objects.requireNonNull(cardLoader, "cardLoader");
        this.fillers = new EnumMap<>(Filler.class);
        for (Filler f : Filler.values()) {
            this.fillers.put(f, Objects.requireNonNull(fillers.get(f), "no card for filler " + f));
        }
        if (silence == null || silence.length == 0) {
            throw new IllegalArgumentException("no silence frame");
        }
        this.silence = silence.clone();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.waitMs = Math.max(0, waitMs);
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
    }

    private static boolean asAudioVideo(ProgramCue.Entry e) {
        return e.videoItem() && e.presentAsAudio();
    }

    /**
     * Keeps the cue's current and next items loaded (starting their loads) and drops every other
     * item except the one playing.
     */
    @Override
    public void prepare(ProgramCue cue) {
        Set<String> w = new HashSet<>();
        ProgramCue.Entry[] entries = cue == null ? new ProgramCue.Entry[0]
                : new ProgramCue.Entry[] {cue.current(), cue.next()};
        synchronized (this) {
            if (closed) {
                return;
            }
            for (ProgramCue.Entry e : entries) {
                if (e != null) {
                    w.add(e.itemId());
                }
            }
            wanted = w;
            evict(null);
            for (ProgramCue.Entry e : entries) {
                if (e != null) {
                    ensure(e);
                }
            }
        }
    }

    @Override
    public ProgramSource item(ProgramCue.Entry entry, long startMs) throws IOException {
        CompletableFuture<Void> pending;
        synchronized (this) {
            if (closed) {
                throw new IOException("closed");
            }
            evict(entry.itemId());
            Slot s = ensure(entry);
            if (s.ready(entry)) {
                return open(s, entry, startMs);
            }
            pending = s.loading;
        }
        if (pending != null && waitMs > 0) {
            try {
                pending.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // still loading (or failed: recorded in the slot)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        synchronized (this) {
            if (closed) {
                throw new IOException("closed");
            }
            Slot s = slots.get(entry.itemId());
            if (s != null && s.ready(entry)) {
                return open(s, entry, startMs);
            }
            return null;
        }
    }

    /** Called with the lock held. */
    private ProgramSource open(Slot s, ProgramCue.Entry entry, long startMs) throws IOException {
        CardGop card = asAudioVideo(entry) ? s.card : null;
        ItemSource src = ItemSources.forItem(entry.itemId(), entry.videoItem(), entry.presentAsAudio(), s.index,
                s.data, entry.videoItem() ? card : s.card, startMs);
        playing = entry.itemId();
        return src;
    }

    @Override
    public ProgramSource filler(Filler filler) {
        synchronized (this) {
            if (!closed) {
                evict(null);
            }
            playing = null;
        }
        return new FillerSource(filler, fillers.get(filler), silence);
    }

    /**
     * Drops the items that are neither wanted nor playing (nor {@code keep}). Runs before
     * {@link #playing} changes, so an item the output switches away from stays open until the next
     * call: the builder closes its source only after the follower has picked the new one.
     */
    private void evict(String keep) {
        for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
            Slot s = it.next();
            if (wanted.contains(s.itemId) || s.itemId.equals(playing) || s.itemId.equals(keep)) {
                continue;
            }
            it.remove();
            closeQuietly(s);
        }
    }

    /** The slot for {@code e}'s item, starting its load if needed (lock held). */
    private Slot ensure(ProgramCue.Entry e) {
        Slot s = slots.computeIfAbsent(e.itemId(), Slot::new);
        if (s.data == null) {
            s.videoItem = e.videoItem();
        }
        if (asAudioVideo(e)) {
            s.cardWanted = true;
        }
        if (s.ready(e) || s.loading != null) {
            return s;
        }
        if (s.failure != null && nowMs.getAsLong() - s.failedAtMs < RETRY_MS) {
            return s;
        }
        CompletableFuture<Void> f = new CompletableFuture<>();
        s.loading = f;
        try {
            executor.execute(() -> load(s, f));
        } catch (RuntimeException ex) {
            s.loading = null;
            fail(s, new IOException("can't start loading: " + ex, ex));
            f.complete(null);
        }
        return s;
    }

    /** Every item's files directly in {@code mediaDir/<itemId>}. */
    private static ItemDirs flat(Path mediaDir) {
        Objects.requireNonNull(mediaDir, "mediaDir");
        return itemId -> {
            Path dir = mediaDir.resolve(itemId).normalize();
            if (!dir.getParent().equals(mediaDir.normalize()) || itemId.isEmpty() || itemId.startsWith(".")) {
                throw new IOException("bad item id " + itemId);
            }
            return dir;
        };
    }

    /** Runs on the executor. */
    private void load(Slot s, CompletableFuture<Void> done) {
        try {
            SampleIndex index;
            FileSampleData data;
            CardGop card;
            Path dir;
            boolean videoItem;
            boolean needCard;
            synchronized (this) {
                index = s.index;
                data = s.data;
                card = s.card;
                dir = s.dir;
                videoItem = s.videoItem;
                needCard = videoItem && s.cardWanted && s.card == null;
            }
            boolean newData = false;
            try {
                if (data == null) {
                    dir = dirs.dir(s.itemId);
                    index = readIndex(s, dir);
                    data = new FileSampleData(dir, index);
                    newData = true;
                    if (!videoItem) {
                        card = CardGop.fromTrack(index, index.track(Kind.VIDEO), data);
                    }
                }
                if (needCard) {
                    card = cardLoader.load(dir.resolve(IngestPipeline.CARD));
                }
            } catch (IOException | RuntimeException e) {
                if (newData) {
                    data.close();
                }
                IOException io = e instanceof IOException x ? x : new IOException(e.toString(), e);
                synchronized (this) {
                    s.loading = null;
                    fail(s, io);
                }
                return;
            }
            synchronized (this) {
                s.loading = null;
                if (closed || slots.get(s.itemId) != s) {
                    // dropped while loading
                    if (newData) {
                        data.close();
                    }
                    return;
                }
                s.index = index;
                s.data = data;
                s.dir = dir;
                s.card = card;
                s.failure = null;
                s.loggedFailure = null;
            }
        } catch (InterruptedException e) {
            synchronized (this) {
                s.loading = null;
                fail(s, new IOException("interrupted"));
            }
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            synchronized (this) {
                s.loading = null;
                fail(s, e);
            }
        } finally {
            done.complete(null);
        }
    }

    private SampleIndex readIndex(Slot s, Path dir) throws IOException {
        SampleIndex index = SampleIndex.read(dir.resolve(SampleIndex.FILE_NAME));
        if (!index.encoding().equals(profile)) {
            throw new IOException("index is for " + index.encoding().id() + ", not " + profile.id()
                    + " (re-encode pending)");
        }
        if (referenceAvcC != null && !Arrays.equals(referenceAvcC, index.avcC())) {
            throw new IOException("avcC differs from the reference");
        }
        if (index.track(Kind.AUDIO) == null) {
            throw new IOException("index has no audio track");
        }
        if (!s.videoItem && index.track(Kind.VIDEO) == null) {
            throw new IOException("audio item's index has no card track");
        }
        return index;
    }

    /** Records a failed load (lock held); logs each distinct failure of an item once. */
    private void fail(Slot s, IOException e) {
        s.failure = e;
        s.failedAtMs = nowMs.getAsLong();
        String msg = String.valueOf(e.getMessage());
        if (!msg.equals(s.loggedFailure)) {
            s.loggedFailure = msg;
            LOG.log(System.Logger.Level.WARNING, "Program output: can't load item " + s.itemId + ": " + e);
        }
    }

    private static void closeQuietly(Slot s) {
        if (s.data != null) {
            try {
                s.data.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "closing item " + s.itemId + ": " + e);
            }
            s.data = null;
        }
        s.dir = null;
        s.index = null;
        s.card = null;
    }

    /** The items whose indexes are in memory now (for status and tests). */
    public synchronized Set<String> loadedItems() {
        Set<String> l = new HashSet<>();
        for (Slot s : slots.values()) {
            if (s.data != null) {
                l.add(s.itemId);
            }
        }
        return l;
    }

    /** Closes every loaded item. Sources still open fail on their next read. */
    @Override
    public synchronized void close() {
        closed = true;
        for (Slot s : slots.values()) {
            closeQuietly(s);
        }
        slots.clear();
    }
}
