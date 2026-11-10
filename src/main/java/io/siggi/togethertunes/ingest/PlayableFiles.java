package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Whether the program output can play a {@code ready} item's files: <b>one notion of "playable"
 * shared by the rooms and the packager</b> ({@code docs/rooms.md#room-state}, "Entries that aren't
 * ready"). A room treats a {@code ready} item whose files fail this check like one that is still
 * processing: the entry stays in the queue and is skipped over when choosing the next entry, so the
 * program output never sits on "Loading…" for a whole item it can't open.
 *
 * <p>The files are usable when the item's {@code output_profile} is the output encoding in use and
 * the {@code index.bin} of its current media version reads and passes {@link #requireUsable} (the
 * same checks {@code output.ProgramMedia} makes before playing it), the files it names exist, and a
 * video item has its {@code card.mp4} (needed when it is presented as audio).
 *
 * <p>Media versions are immutable once published, so a positive result is cached per item, media
 * version and encoding (a re-encode or an encoding change makes a new key). Negative results aren't
 * cached: the rooms check again whenever they choose. Each distinct failure is logged once.
 * Thread-safe. Hand it to the rooms as a {@link Check} ({@code playableFiles::usable}).
 */
public final class PlayableFiles {
    private static final System.Logger LOG = System.getLogger(PlayableFiles.class.getName());
    private static final int CACHE_SIZE = 4096;

    /** What the check needs from an item's row. */
    public record Item(String itemId, String kind, String outputProfile, int mediaVersion) {
        public boolean videoItem() {
            return "video".equals(kind);
        }
    }

    /** Whether a {@code ready} item's files can be played by the program output. */
    @FunctionalInterface
    public interface Check {
        /** Trusts the item status alone (tests without media files). */
        Check ANY = item -> true;

        /** Must return quickly once an item was checked, and must not throw. */
        boolean usable(Item item);
    }

    /** Where an item version's files are ({@link MediaVersions#versionDir(io.siggi.togethertunes.DataDir, String, int)}). */
    @FunctionalInterface
    public interface VersionDirs {
        Path dir(String itemId, int version) throws IOException;
    }

    private final VersionDirs dirs;
    private final Supplier<OutputEncoding> encoding;
    private final Supplier<byte[]> referenceAvcC;
    private final Map<String, Boolean> usable = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final Map<String, String> logged = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * @param dirs          where each item version's files are
     * @param encoding      the output encoding in use (read at every check)
     * @param referenceAvcC its reference avcC (invariant 1), or a supplier of null to skip that check
     */
    public PlayableFiles(VersionDirs dirs, Supplier<OutputEncoding> encoding, Supplier<byte[]> referenceAvcC) {
        this.dirs = Objects.requireNonNull(dirs, "dirs");
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.referenceAvcC = Objects.requireNonNull(referenceAvcC, "referenceAvcC");
    }

    /** The {@link Check}: whether the program output can play this {@code ready} item's files now. */
    public boolean usable(Item item) {
        OutputEncoding enc = encoding.get();
        if (item.outputProfile() == null || !enc.id().equals(item.outputProfile())) {
            return false; // a re-encode for the encoding in use is pending
        }
        String key = item.itemId() + "/v" + item.mediaVersion() + "/" + enc.id() + "/" + item.kind();
        synchronized (this) {
            if (usable.containsKey(key)) {
                return true;
            }
        }
        try {
            Path dir = dirs.dir(item.itemId(), item.mediaVersion());
            SampleIndex index = SampleIndex.read(dir.resolve(SampleIndex.FILE_NAME));
            requireUsable(index, enc, referenceAvcC.get(), item.videoItem());
            for (String f : index.files()) {
                if (!Files.isRegularFile(dir.resolve(f))) {
                    throw new IOException(f + " is missing");
                }
            }
            if (item.videoItem() && !Files.isRegularFile(dir.resolve(IngestPipeline.CARD))) {
                throw new IOException(IngestPipeline.CARD + " is missing");
            }
        } catch (IOException | RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            boolean log;
            synchronized (this) {
                log = !msg.equals(logged.put(key, msg));
            }
            if (log) {
                LOG.log(System.Logger.Level.WARNING, "Item " + item.itemId() + " (media version "
                        + item.mediaVersion() + ") is ready but the program output can't play its files;"
                        + " rooms skip it: " + e);
            }
            return false;
        }
        synchronized (this) {
            usable.put(key, Boolean.TRUE);
            logged.remove(key);
        }
        return true;
    }

    /**
     * The checks an item's index must pass before the packager plays it (shared with
     * {@code output.ProgramMedia}): made for {@code encoding}, with the reference avcC (invariant 1;
     * skipped if {@code referenceAvcC} is null), an audio track, and for an audio item its card track.
     *
     * @return {@code index}
     * @throws IOException saying what is wrong
     */
    public static SampleIndex requireUsable(SampleIndex index, OutputEncoding encoding, byte[] referenceAvcC,
            boolean videoItem) throws IOException {
        if (!index.encoding().equals(encoding)) {
            throw new IOException("index is for " + index.encoding().id() + ", not " + encoding.id()
                    + " (re-encode pending)");
        }
        if (referenceAvcC != null && !Arrays.equals(referenceAvcC, index.avcC())) {
            throw new IOException("avcC differs from the reference");
        }
        if (index.track(Kind.AUDIO) == null) {
            throw new IOException("index has no audio track");
        }
        if (!videoItem && index.track(Kind.VIDEO) == null) {
            throw new IOException("audio item's index has no card track");
        }
        return index;
    }
}
