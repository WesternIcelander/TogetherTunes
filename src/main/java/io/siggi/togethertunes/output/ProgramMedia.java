package io.siggi.togethertunes.output;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.ingest.FillerCards;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.PlayableFiles;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.output.FillerSource.Filler;
import java.io.IOException;
import java.nio.file.Path;
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
import java.util.function.Supplier;

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
 * from the reference (invariant 1) — counts as not loadable. The rooms make the same checks before
 * choosing an entry ({@link PlayableFiles}), so such an item is normally skipped rather than shown
 * as Loading for its whole length. It is retried no sooner than
 * {@link #RETRY_MS} later, and each distinct failure is logged once.</li>
 * </ul>
 *
 * <p><b>Overlay cards</b> ({@code docs/overlay.md#cards-cheap-path}), when the output is given the
 * room's {@link OverlayCards}: an entry played as audio (current or next) gets its overlaid card
 * made and loaded in the background, and the room's overlaid filler cards are looked up when the
 * cue changes and every {@link #FILLER_RECHECK_MS} while a filler plays. Sources ask for them at
 * every 2 s GOP ({@link CardGop#repeat(java.util.function.Supplier)}) and show the plain card until
 * they are ready, so the overlay never delays a play; it appears at the next GOP boundary. An entry
 * played as video gets the room's overlay re-encode from {@link OverlayCards#video}, and its
 * {@link VideoItemSource} plays each GOP from it once that GOP is complete.
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

    /** How often the room's overlaid filler cards are looked up again while a filler plays. */
    public static final long FILLER_RECHECK_MS = 5000;
    /** A cue change looks the filler cards up again no more often than this. */
    static final long FILLER_CUE_RECHECK_MS = 1000;
    /** Overlaid cards kept in memory (by content key). */
    static final int OVERLAY_CARDS_KEPT = 16;

    /**
     * A card file made for this room's output, and a key that changes whenever its content does
     * (cards with equal keys are interchangeable, so a loaded card is reused by key).
     */
    public record CardFile(Path file, String key) {
        public CardFile {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * The room's overlaid cards ({@code docs/overlay.md#cards-cheap-path}). Both methods may block
     * (database reads, a card encode); they run on the loading executor, never on the packager
     * thread.
     */
    public interface OverlayCards {
        /** The room's overlaid filler cards as things are now, or null when the room's overlay is off. */
        Map<Filler, CardFile> fillers() throws IOException, InterruptedException;

        /**
         * The overlaid card for an entry played as audio, or null when the entry gets no overlay
         * (its recorded decision is off).
         */
        CardFile item(ProgramCue.Entry entry) throws IOException, InterruptedException;

        /**
         * The room's overlay re-encode for an entry played as video
         * ({@code docs/overlay.md#packager-substitution}), as a supplier the source asks at every
         * GOP start: it gives the re-encode once there is one (null until then, or when the entry
         * gets no overlay), and the source plays each GOP from it when that GOP is complete.
         * Unlike the other two methods this one runs on the packager thread, when the entry
         * starts playing, and neither it nor the supplier may block. Default: none.
         *
         * @param itemDir the media version directory the play reads (a re-encode is only valid
         *                for the version it was made from)
         * @param index   that version's index
         */
        default Supplier<VideoOverlay> video(ProgramCue.Entry entry, Path itemDir, SampleIndex index) {
            return null;
        }

        /**
         * The cue's next entry, played as video, with the files the output loaded for it: its
         * overlay re-encode can start ahead ({@code docs/overlay.md#scheduling-and-caching}).
         * Called on the room's thread or the loading executor with the output's lock held: must
         * not block. Default: nothing.
         */
        default void videoNext(ProgramCue.Entry entry, Path itemDir, SampleIndex index) {
        }

        /**
         * The entries the output still wants (the cue's current and next, and the one playing);
         * every other entry's re-encode is given up (cancelled unless more than half done). Empty
         * when the output closes. Must not block. Default: nothing.
         */
        default void videoWanted(Set<String> entryIds) {
        }
    }

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

    /**
     * What is known about one item (or a video's audio-only variant, which is a slot of its own):
     * its loaded index and data, or the load in progress or failed.
     */
    private static final class Slot {
        final String key;
        final String itemId;
        /**
         * A video item's audio-only file ({@link IngestPipeline#AUDIO_ONLY} with
         * {@link IngestPipeline#AUDIO_ONLY_INDEX}): loaded like an audio item, card in the index.
         */
        final boolean variant;
        /** The version directory {@link #data} was loaded from (null until loaded). */
        Path dir;
        boolean videoItem;
        /** Whether some entry plays this video item as audio, so its {@code card.mp4} is needed. */
        boolean cardWanted;
        /**
         * Whether a transition card wants this video item's {@code card.mp4}
         * ({@code docs/program-output.md#transitions}). Optional: a failed load leaves the card
         * out (the gap shows the blank card) and doesn't fail the item.
         */
        boolean transitionCardWanted;
        long cardFailedAtMs;

        SampleIndex index;
        FileSampleData data;
        /** Audio item: the card in its index; video item: its {@code card.mp4} (once wanted). */
        CardGop card;

        /** The load in progress (completes when it ends either way), or null. */
        CompletableFuture<Void> loading;
        IOException failure;
        long failedAtMs;
        String loggedFailure;

        Slot(String key, String itemId, boolean variant) {
            this.key = key;
            this.itemId = itemId;
            this.variant = variant;
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

    /** By {@link #key}: the item id, with a suffix for a video's audio-only variant. */
    private final Map<String, Slot> slots = new HashMap<>();

    /** An entry's overlaid card: made and loaded in the background, null until then (or if none). */
    private static final class EntryCard {
        final ProgramCue.Entry entry;
        volatile CardGop card;
        boolean loading;
        boolean done;
        long failedAtMs;
        String loggedFailure;

        EntryCard(ProgramCue.Entry entry) {
            this.entry = entry;
        }
    }

    private final OverlayCards overlay;
    /** By entry id: the overlaid cards of the wanted entries and of the one playing. */
    private final Map<String, EntryCard> entryCards = new HashMap<>();
    /** The entry id of the source last handed out, until a filler. */
    private String playingEntry;
    /** The entry ids of the latest cue's current and next entries. */
    private Set<String> wantedEntries = Set.of();
    /** The latest cue's next entry, or null. */
    private ProgramCue.Entry nextEntry;
    /** The entry ids last given to {@link OverlayCards#videoWanted}. */
    private Set<String> videoWantedSent = Set.of();
    /** The room's overlaid filler cards, or null (overlay off, or not loaded yet). */
    private volatile Map<Filler, CardGop> roomFillers;
    private long fillersCheckedMs;
    private boolean fillersRunning;
    private boolean fillersAgain;
    private String fillersLoggedFailure;
    /** Loaded overlaid cards by {@link CardFile#key()}, least recently used first. */
    private final Map<String, CardGop> overlayCardCache = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CardGop> eldest) {
            return size() > OVERLAY_CARDS_KEPT;
        }
    };
    /** The slot keys of the latest cue's current and next entries. */
    private Set<String> wanted = Set.of();
    /** The slot key of the source last handed to the follower, until it switches to a filler. */
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
        this(dirs, profile, referenceAvcC, cardLoader, fillers, silence, executor, waitMs, nowMs, null);
    }

    /**
     * @param overlay the room's overlaid cards (loaded with {@code cardLoader}), or null for none;
     *                the rest as above
     */
    public ProgramMedia(ItemDirs dirs, OutputEncoding profile, byte[] referenceAvcC, CardLoader cardLoader,
            Map<Filler, CardGop> fillers, byte[] silence, Executor executor, long waitMs, LongSupplier nowMs,
            OverlayCards overlay) {
        this.overlay = overlay;
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

    /** A video item played as audio with its {@code card.mp4} and the audio of its {@code index.bin}. */
    private static boolean asAudioVideo(ProgramCue.Entry e) {
        return e.videoItem() && (e.presentAsAudio() || e.audioOnly()) && !e.audioOnlyFile();
    }

    /** The slot key for an entry: its item, or its item's audio-only variant. */
    private static String key(ProgramCue.Entry e) {
        return e.audioOnlyFile() ? e.itemId() + "#audio-only" : e.itemId();
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
                    w.add(key(e));
                }
            }
            wanted = w;
            evict(null);
            Set<String> we = new HashSet<>();
            for (ProgramCue.Entry e : entries) {
                if (e != null) {
                    we.add(e.entryId());
                }
            }
            wantedEntries = we;
            nextEntry = cue == null ? null : cue.next();
            evictEntryCards();
            sendVideoWanted();
            boolean transition = cue != null && cue.gap().card() && cue.gap().audioFrames() > 0;
            for (ProgramCue.Entry e : entries) {
                if (e != null) {
                    ensure(e, transition);
                    ensureEntryCard(e, transition);
                }
            }
            planNextVideo();
            refreshFillers(FILLER_CUE_RECHECK_MS);
        }
    }

    @Override
    public ProgramSource item(ProgramCue.Entry entry, long startMs) throws IOException {
        CompletableFuture<Void> pending;
        synchronized (this) {
            if (closed) {
                throw new IOException("closed");
            }
            evict(key(entry));
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
            Slot s = slots.get(key(entry));
            if (s != null && s.ready(entry)) {
                return open(s, entry, startMs);
            }
            return null;
        }
    }

    /** Called with the lock held. */
    private ProgramSource open(Slot s, ProgramCue.Entry entry, long startMs) throws IOException {
        EntryCard ec = ensureEntryCard(entry);
        Supplier<CardGop> overlaid = ec == null ? null : () -> ec.card;
        ItemSource src;
        if (s.variant) {
            src = new AudioItemSource(entry.itemId(), s.index, s.data, s.card, overlaid, startMs, true);
        } else {
            CardGop card = asAudioVideo(entry) ? s.card : null;
            Supplier<VideoOverlay> video = overlay != null && entry.videoItem() && !entry.playsAsAudio()
                    ? overlay.video(entry, s.dir, s.index) : null;
            src = ItemSources.forItem(entry.itemId(), entry.videoItem(), entry.playsAsAudio(), s.index,
                    s.data, entry.videoItem() ? card : s.card, overlaid, video, startMs);
        }
        playing = s.key;
        playingEntry = entry.entryId();
        sendVideoWanted();
        return src;
    }

    @Override
    public ProgramSource filler(Filler filler) {
        synchronized (this) {
            if (!closed) {
                evict(null);
                refreshFillers(FILLER_RECHECK_MS);
            }
            playing = null;
            playingEntry = null;
            if (!closed) {
                sendVideoWanted();
            }
        }
        if (overlay == null) {
            return new FillerSource(filler, fillers.get(filler), silence);
        }
        return new FillerSource(filler, fillers.get(filler), () -> {
            synchronized (this) {
                if (closed) {
                    return null;
                }
                refreshFillers(FILLER_RECHECK_MS);
            }
            Map<Filler, CardGop> m = roomFillers;
            return m == null ? null : m.get(filler);
        }, silence);
    }

    /**
     * The transition gap before {@code next} ({@code docs/program-output.md#transitions}): its card
     * (the room's overlaid version while the overlay is on) with silence, if {@code gap.card()} and
     * the card is loaded; else the blank card. Never blocks.
     */
    @Override
    public ProgramSource gap(ProgramCue.Entry next, ProgramCue.Gap gap) {
        if (gap.card()) {
            CardGop card = null;
            EntryCard ec = null;
            synchronized (this) {
                if (!closed) {
                    Slot sl = slots.get(key(next));
                    if (sl != null && sl.data != null) {
                        card = sl.card;
                    }
                    ec = ensureEntryCard(next, true);
                }
            }
            if (card != null) {
                synchronized (this) {
                    if (!closed) {
                        evict(null);
                    }
                    playing = null;
                    playingEntry = null;
                    if (!closed) {
                        sendVideoWanted();
                    }
                }
                EntryCard overlaid = ec;
                FillerSource src = overlaid == null ? new FillerSource(Filler.GAP, card, silence)
                        : new FillerSource(Filler.GAP, card, () -> overlaid.card, silence);
                return new GapSource(src, gap.audioFrames(), true);
            }
            LOG.log(System.Logger.Level.DEBUG, "Program output: card of item " + next.itemId()
                    + " not loaded, blank transition gap");
        }
        return new GapSource(filler(Filler.GAP), gap.audioFrames(), false);
    }

    // ------------------------------------------------------------------ overlay cards

    /**
     * The entry's overlaid card holder, starting its load if needed (lock held); null when the
     * output has no overlay or the entry doesn't play as audio.
     */
    private EntryCard ensureEntryCard(ProgramCue.Entry e) {
        return ensureEntryCard(e, false);
    }

    /** {@link #ensureEntryCard(ProgramCue.Entry)}; {@code transition}: also for an entry played as video (its transition card). */
    private EntryCard ensureEntryCard(ProgramCue.Entry e, boolean transition) {
        if (overlay == null || !(e.playsAsAudio() || transition) || closed) {
            return null;
        }
        EntryCard ec = entryCards.computeIfAbsent(e.entryId(), k -> new EntryCard(e));
        if (ec.done || ec.loading) {
            return ec;
        }
        if (ec.failedAtMs != 0 && nowMs.getAsLong() - ec.failedAtMs < RETRY_MS) {
            return ec;
        }
        ec.loading = true;
        try {
            executor.execute(() -> loadEntryCard(ec));
        } catch (RuntimeException ex) {
            ec.loading = false;
            ec.failedAtMs = nowMs.getAsLong();
        }
        return ec;
    }

    /**
     * Tells the room's overlay which entries are still wanted (current, next, playing), when that
     * changed (lock held), so the re-encodes of the others are given up.
     */
    private void sendVideoWanted() {
        if (overlay == null) {
            return;
        }
        Set<String> ids = new HashSet<>(wantedEntries);
        if (playingEntry != null) {
            ids.add(playingEntry);
        }
        if (ids.equals(videoWantedSent)) {
            return;
        }
        videoWantedSent = Set.copyOf(ids);
        try {
            overlay.videoWanted(videoWantedSent);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Program output: overlay scheduling failed: " + e);
        }
    }

    /**
     * Hands the cue's next entry to the room's overlay once its files are loaded, if it plays as
     * video, so its re-encode can start ahead (lock held).
     */
    private void planNextVideo() {
        ProgramCue.Entry e = nextEntry;
        if (overlay == null || closed || e == null || !e.videoItem() || e.playsAsAudio()) {
            return;
        }
        Slot s = slots.get(key(e));
        if (s == null || s.variant || s.data == null) {
            return;
        }
        try {
            overlay.videoNext(e, s.dir, s.index);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Program output: overlay scheduling failed: " + ex);
        }
    }

    /** Drops the overlaid cards of entries that are neither wanted nor playing (lock held). */
    private void evictEntryCards() {
        entryCards.keySet().removeIf(id -> !wantedEntries.contains(id) && !id.equals(playingEntry));
    }

    /** Runs on the executor. */
    private void loadEntryCard(EntryCard ec) {
        try {
            CardFile f = overlay.item(ec.entry);
            CardGop card = f == null ? null : loadOverlayCard(f);
            synchronized (this) {
                ec.card = card;
                ec.done = true;
                ec.loading = false;
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                ec.loading = false;
                ec.failedAtMs = nowMs.getAsLong();
                String msg = String.valueOf(e.getMessage());
                if (!msg.equals(ec.loggedFailure)) {
                    ec.loggedFailure = msg;
                    LOG.log(System.Logger.Level.WARNING, "Program output: no overlay card for entry "
                            + ec.entry.entryId() + " (item " + ec.entry.itemId() + "), playing its plain card: " + e);
                }
            }
        } catch (InterruptedException e) {
            synchronized (this) {
                ec.loading = false;
                ec.failedAtMs = nowMs.getAsLong();
            }
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Looks the room's overlaid filler cards up again if the last look is at least
     * {@code minGapMs} old (lock held). One look runs at a time; one asked for meanwhile follows it.
     */
    private void refreshFillers(long minGapMs) {
        if (overlay == null || closed) {
            return;
        }
        long now = nowMs.getAsLong();
        if (fillersRunning) {
            fillersAgain = true;
            return;
        }
        if (fillersCheckedMs != 0 && now - fillersCheckedMs < minGapMs) {
            return;
        }
        fillersRunning = true;
        fillersCheckedMs = now;
        try {
            executor.execute(this::loadFillers);
        } catch (RuntimeException ex) {
            fillersRunning = false;
        }
    }

    /** Runs on the executor. */
    private void loadFillers() {
        try {
            Map<Filler, CardFile> files = overlay.fillers();
            Map<Filler, CardGop> cards = null;
            if (files != null) {
                cards = new EnumMap<>(Filler.class);
                for (Map.Entry<Filler, CardFile> f : files.entrySet()) {
                    cards.put(f.getKey(), loadOverlayCard(f.getValue()));
                }
            }
            roomFillers = cards;
            synchronized (this) {
                fillersLoggedFailure = null;
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                String msg = String.valueOf(e.getMessage());
                if (!msg.equals(fillersLoggedFailure)) {
                    fillersLoggedFailure = msg;
                    LOG.log(System.Logger.Level.WARNING, "Program output: no overlaid filler cards, keeping the"
                            + " previous ones: " + e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                fillersRunning = false;
                if (fillersAgain && !closed) {
                    fillersAgain = false;
                    refreshFillers(0);
                }
            }
        }
    }

    /** The card for {@code f}, from memory when one with its key was loaded (blocking; executor). */
    private CardGop loadOverlayCard(CardFile f) throws IOException, InterruptedException {
        synchronized (this) {
            CardGop known = overlayCardCache.get(f.key());
            if (known != null) {
                return known;
            }
        }
        CardGop card = cardLoader.load(f.file());
        synchronized (this) {
            overlayCardCache.put(f.key(), card);
        }
        return card;
    }

    /**
     * Drops the items that are neither wanted nor playing (nor {@code keep}). Runs before
     * {@link #playing} changes, so an item the output switches away from stays open until the next
     * call: the builder closes its source only after the follower has picked the new one.
     */
    private void evict(String keep) {
        for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
            Slot s = it.next();
            if (wanted.contains(s.key) || s.key.equals(playing) || s.key.equals(keep)) {
                continue;
            }
            it.remove();
            closeQuietly(s);
        }
    }

    /** The slot for {@code e}'s item, starting its load if needed (lock held). */
    private Slot ensure(ProgramCue.Entry e) {
        return ensure(e, false);
    }

    /**
     * {@link #ensure(ProgramCue.Entry)}; {@code transition}: the room shows transition cards, so a
     * video item's {@code card.mp4} is loaded too (optional, after the item's files).
     */
    private Slot ensure(ProgramCue.Entry e, boolean transition) {
        Slot s = slots.computeIfAbsent(key(e), k -> new Slot(k, e.itemId(), e.audioOnlyFile()));
        if (s.data == null) {
            // the audio-only variant is laid out like an audio item (its card is in its index)
            s.videoItem = e.videoItem() && !s.variant;
        }
        if (asAudioVideo(e)) {
            s.cardWanted = true;
        }
        if (transition) {
            s.transitionCardWanted = true;
        }
        boolean optionalCard = s.data != null && s.videoItem && s.transitionCardWanted && s.card == null
                && (s.cardFailedAtMs == 0 || nowMs.getAsLong() - s.cardFailedAtMs >= RETRY_MS);
        if ((s.ready(e) && !optionalCard) || s.loading != null) {
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

    /**
     * Loads a video item's {@code card.mp4} for its transition card after its files are in (runs on
     * the executor). Optional: a failure is logged and retried after {@link #RETRY_MS}.
     */
    private void loadTransitionCard(Slot s, Path dir, CompletableFuture<Void> done) {
        CardGop card = null;
        try {
            card = cardLoader.load(dir.resolve(IngestPipeline.CARD));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Program output: no transition card for item " + s.itemId
                    + ", the gap shows the blank card: " + e);
        } finally {
            synchronized (this) {
                if (s.loading == done) {
                    s.loading = null;
                }
                if (card != null && !closed && slots.get(s.key) == s && s.card == null && dir.equals(s.dir)) {
                    s.card = card;
                } else if (card == null) {
                    s.cardFailedAtMs = nowMs.getAsLong();
                }
            }
        }
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
            boolean wantCard;
            synchronized (this) {
                index = s.index;
                data = s.data;
                card = s.card;
                dir = s.dir;
                videoItem = s.videoItem;
                needCard = videoItem && s.cardWanted && s.card == null;
                wantCard = videoItem && !needCard && s.transitionCardWanted && s.card == null;
            }
            boolean newData = false;
            try {
                if (data == null) {
                    dir = dirs.dir(s.itemId);
                    index = readIndex(s, dir, s.variant ? IngestPipeline.AUDIO_ONLY_INDEX : SampleIndex.FILE_NAME);
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
                if (closed || slots.get(s.key) != s) {
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
                planNextVideo();
                if (wantCard) {
                    s.loading = done; // the item plays meanwhile; no second load starts
                }
            }
            if (wantCard) {
                loadTransitionCard(s, dir, done);
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

    /** The item's index, if it passes the checks the rooms make too ({@link PlayableFiles#requireUsable}). */
    private SampleIndex readIndex(Slot s, Path dir, String indexFile) throws IOException {
        return PlayableFiles.requireUsable(SampleIndex.read(dir.resolve(indexFile)), profile,
                referenceAvcC, s.videoItem);
    }

    /** Records a failed load (lock held); logs each distinct failure of an item once. */
    private void fail(Slot s, IOException e) {
        s.failure = e;
        s.failedAtMs = nowMs.getAsLong();
        String msg = String.valueOf(e.getMessage());
        if (!msg.equals(s.loggedFailure)) {
            s.loggedFailure = msg;
            LOG.log(System.Logger.Level.WARNING, "Program output: can't load item " + s.itemId
                    + (s.variant ? " (audio-only file)" : "") + ": " + e);
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
        if (overlay != null && !closed) {
            try {
                overlay.videoWanted(Set.of());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Program output: overlay scheduling failed: " + e);
            }
        }
        closed = true;
        entryCards.clear();
        overlayCardCache.clear();
        roomFillers = null;
        for (Slot s : slots.values()) {
            closeQuietly(s);
        }
        slots.clear();
    }
}
