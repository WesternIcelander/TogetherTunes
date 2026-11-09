package io.siggi.togethertunes.media;

import io.siggi.togethertunes.config.OutputProfile;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An item's sample index ({@code index.bin}), so the packager never parses MP4 at playback time:
 * per track, the byte offset, size, PTS, duration and keyframe flag of every sample, plus the
 * video track's avcC. Built at ingest from ffprobe ({@code ingest/IndexBuilder}); see
 * {@code docs/library-and-ingest.md#sample-index-indexbin}.
 *
 * <p>Format, big-endian:
 * <pre>
 * magic "MSIX" | version u16 (1) | profile u16 (see {@link #profileCode})
 * file-ref table: file count u8 | per file: name length u16 | name UTF-8 (a plain file name in the item directory)
 * track count u8
 * per track:
 *   kind u8 (0=video, 1=audio) | timescale u32 | file-ref u8 | sample count u32
 *   per sample: offset u64 | size u32 | pts i64 (track timescale) | duration u32 | flags u8 (bit0=keyframe)
 * avcC length u16 | avcC bytes
 * </pre>
 *
 * <p>Samples are in file (decode) order, and since no encode has B-frames that is also PTS
 * order: PTS strictly increases within a track. The reader checks that, so lookups by time can
 * use binary search. Instances are immutable.
 */
public final class SampleIndex {
    /** The file name inside an item directory. */
    public static final String FILE_NAME = "index.bin";
    public static final int VERSION = 1;
    /** Bytes per sample record. */
    public static final int SAMPLE_BYTES = 8 + 4 + 8 + 4 + 1;
    /** Sample flag: the sample is a keyframe (an IDR for video; every AAC frame is one). */
    public static final int FLAG_KEYFRAME = 1;

    private static final byte[] MAGIC = {'M', 'S', 'I', 'X'};
    private static final int MAX_FILES = 255;
    private static final int MAX_TRACKS = 255;
    private static final long U32_MAX = 0xFFFFFFFFL;

    /** Track kind; the code is what the file stores. */
    public enum Kind {
        VIDEO(0), AUDIO(1);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Kind ofCode(int code) throws IndexFormatException {
            for (Kind k : values()) {
                if (k.code == code) {
                    return k;
                }
            }
            throw new IndexFormatException("unknown track kind " + code);
        }
    }

    /** A malformed or unsupported {@code index.bin}. */
    public static final class IndexFormatException extends IOException {
        public IndexFormatException(String message) {
            super(message);
        }
    }

    private final OutputProfile profile;
    private final List<String> files;
    private final List<Track> tracks;
    private final byte[] avcC;

    /**
     * @param files plain file names (no path separators) the tracks refer to by position
     * @param avcC the video track's avcC (empty if there is no video track)
     * @throws IllegalArgumentException if a track refers to a missing file or limits are exceeded
     */
    public SampleIndex(OutputProfile profile, List<String> files, List<Track> tracks, byte[] avcC) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is null");
        }
        if (files.size() > MAX_FILES || tracks.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("too many files or tracks");
        }
        for (String f : files) {
            checkFileName(f);
        }
        for (Track t : tracks) {
            if (t.fileRef >= files.size()) {
                throw new IllegalArgumentException("track refers to file " + t.fileRef + " of " + files.size());
            }
        }
        if (avcC.length > 0xFFFF) {
            throw new IllegalArgumentException("avcC too long: " + avcC.length);
        }
        this.profile = profile;
        this.files = List.copyOf(files);
        this.tracks = List.copyOf(tracks);
        this.avcC = avcC.clone();
    }

    public OutputProfile profile() {
        return profile;
    }

    /** The file-ref table: plain file names relative to the item directory. */
    public List<String> files() {
        return files;
    }

    public List<Track> tracks() {
        return tracks;
    }

    /** The first track of this kind, or null. */
    public Track track(Kind kind) {
        for (Track t : tracks) {
            if (t.kind == kind) {
                return t;
            }
        }
        return null;
    }

    /** The file name a track's samples live in. */
    public String file(Track track) {
        return files.get(track.fileRef);
    }

    /** A copy of the video track's avcC. */
    public byte[] avcC() {
        return avcC.clone();
    }

    /** The stable code stored for a profile. Never reuse or renumber a code. */
    public static int profileCode(OutputProfile profile) {
        return switch (profile) {
            case P720_30 -> 1;
            case P1080_30 -> 2;
        };
    }

    static OutputProfile profileOfCode(int code) throws IndexFormatException {
        for (OutputProfile p : OutputProfile.values()) {
            if (profileCode(p) == code) {
                return p;
            }
        }
        throw new IndexFormatException("unknown profile code " + code);
    }

    private static void checkFileName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("not a plain file name: " + name);
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 0xFFFF) {
            throw new IllegalArgumentException("file name too long");
        }
    }

    // ---- writing ----

    /** The encoded index. */
    public byte[] toBytes() {
        try {
            long samples = 0;
            for (Track t : tracks) {
                samples += t.sampleCount();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 8,
                    64 + avcC.length + samples * SAMPLE_BYTES));
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(MAGIC);
            out.writeShort(VERSION);
            out.writeShort(profileCode(profile));
            out.writeByte(files.size());
            for (String f : files) {
                byte[] name = f.getBytes(StandardCharsets.UTF_8);
                out.writeShort(name.length);
                out.write(name);
            }
            out.writeByte(tracks.size());
            for (Track t : tracks) {
                out.writeByte(t.kind.code);
                out.writeInt((int) t.timescale);
                out.writeByte(t.fileRef);
                out.writeInt(t.sampleCount());
                for (int i = 0; i < t.sampleCount(); i++) {
                    out.writeLong(t.offsets[i]);
                    out.writeInt((int) t.sizes[i]);
                    out.writeLong(t.pts[i]);
                    out.writeInt((int) t.durations[i]);
                    out.writeByte(t.flags[i]);
                }
            }
            out.writeShort(avcC.length);
            out.write(avcC);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the index to {@code file} through a dot-temp file and a rename (atomic where supported). */
    public void write(Path file) throws IOException {
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        try {
            Files.write(tmp, toBytes());
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- reading ----

    /** Reads and validates an index file. */
    public static SampleIndex read(Path file) throws IOException {
        try {
            return parse(Files.readAllBytes(file));
        } catch (IndexFormatException e) {
            throw new IndexFormatException(file + ": " + e.getMessage());
        }
    }

    /**
     * Parses and validates an encoded index: magic, version, a known profile, plain file names,
     * file refs in range, positive timescales, sizes and durations within u32/int range, PTS
     * strictly increasing per track, and no trailing bytes.
     */
    public static SampleIndex parse(byte[] data) throws IndexFormatException {
        ByteBuffer in = ByteBuffer.wrap(data);
        try {
            byte[] magic = new byte[4];
            in.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IndexFormatException("not a sample index (bad magic)");
            }
            int version = Short.toUnsignedInt(in.getShort());
            if (version != VERSION) {
                throw new IndexFormatException("unsupported index version " + version);
            }
            OutputProfile profile = profileOfCode(Short.toUnsignedInt(in.getShort()));
            int fileCount = Byte.toUnsignedInt(in.get());
            List<String> files = new ArrayList<>(fileCount);
            for (int i = 0; i < fileCount; i++) {
                byte[] name = new byte[Short.toUnsignedInt(in.getShort())];
                in.get(name);
                String s;
                try {
                    s = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(name)).toString();
                    checkFileName(s);
                } catch (CharacterCodingException | IllegalArgumentException e) {
                    throw new IndexFormatException("bad file name in file-ref table");
                }
                files.add(s);
            }
            int trackCount = Byte.toUnsignedInt(in.get());
            List<Track> tracks = new ArrayList<>(trackCount);
            for (int t = 0; t < trackCount; t++) {
                Kind kind = Kind.ofCode(Byte.toUnsignedInt(in.get()));
                long timescale = Integer.toUnsignedLong(in.getInt());
                int fileRef = Byte.toUnsignedInt(in.get());
                long count = Integer.toUnsignedLong(in.getInt());
                if (timescale == 0) {
                    throw new IndexFormatException("track " + t + ": timescale 0");
                }
                if (fileRef >= fileCount) {
                    throw new IndexFormatException("track " + t + ": file-ref " + fileRef + " of " + fileCount);
                }
                if (count > in.remaining() / SAMPLE_BYTES) {
                    throw new IndexFormatException("track " + t + ": " + count + " samples don't fit in the file");
                }
                Track.Builder b = new Track.Builder(kind, timescale, fileRef, (int) count);
                for (int i = 0; i < count; i++) {
                    long offset = in.getLong();
                    long size = Integer.toUnsignedLong(in.getInt());
                    long pts = in.getLong();
                    long duration = Integer.toUnsignedLong(in.getInt());
                    int flags = Byte.toUnsignedInt(in.get());
                    try {
                        b.add(offset, size, pts, duration, flags);
                    } catch (IllegalArgumentException e) {
                        throw new IndexFormatException("track " + t + " sample " + i + ": " + e.getMessage());
                    }
                }
                tracks.add(b.build());
            }
            byte[] avcC = new byte[Short.toUnsignedInt(in.getShort())];
            in.get(avcC);
            if (in.hasRemaining()) {
                throw new IndexFormatException(in.remaining() + " trailing bytes");
            }
            return new SampleIndex(profile, files, tracks, avcC);
        } catch (BufferUnderflowException e) {
            throw new IndexFormatException("truncated sample index");
        }
    }

    // ---- tracks ----

    /** One track's samples, held in primitive arrays (a one-hour video is ~108 000 samples). */
    public static final class Track {
        private final Kind kind;
        private final long timescale;
        private final int fileRef;
        private final long[] offsets;
        private final long[] sizes;
        private final long[] pts;
        private final long[] durations;
        private final byte[] flags;

        private Track(Builder b) {
            this.kind = b.kind;
            this.timescale = b.timescale;
            this.fileRef = b.fileRef;
            this.offsets = Arrays.copyOf(b.offsets, b.count);
            this.sizes = Arrays.copyOf(b.sizes, b.count);
            this.pts = Arrays.copyOf(b.pts, b.count);
            this.durations = Arrays.copyOf(b.durations, b.count);
            this.flags = Arrays.copyOf(b.flags, b.count);
        }

        public Kind kind() {
            return kind;
        }

        /** Ticks per second of {@link #pts} and {@link #duration}. */
        public long timescale() {
            return timescale;
        }

        /** Position in {@link SampleIndex#files()}. */
        public int fileRef() {
            return fileRef;
        }

        public int sampleCount() {
            return pts.length;
        }

        /** Absolute byte offset of sample {@code i} in its file. */
        public long offset(int i) {
            return offsets[i];
        }

        public int size(int i) {
            return (int) sizes[i];
        }

        public long pts(int i) {
            return pts[i];
        }

        public long duration(int i) {
            return durations[i];
        }

        public int flags(int i) {
            return flags[i] & 0xFF;
        }

        public boolean isKeyframe(int i) {
            return (flags[i] & FLAG_KEYFRAME) != 0;
        }

        /** PTS just after the last sample (its PTS plus duration), or 0 for an empty track. */
        public long endPts() {
            int n = pts.length;
            return n == 0 ? 0 : pts[n - 1] + durations[n - 1];
        }

        /** The last sample whose PTS is at or before {@code t}, or -1 if every sample is later. */
        public int sampleAtOrBefore(long t) {
            int i = Arrays.binarySearch(pts, t);
            return i >= 0 ? i : -i - 2;
        }

        /** The last keyframe at or before sample {@code i}, or -1 if there is none. */
        public int keyframeAtOrBefore(int i) {
            for (int k = Math.min(i, pts.length - 1); k >= 0; k--) {
                if (isKeyframe(k)) {
                    return k;
                }
            }
            return -1;
        }

        /** The first keyframe at or after sample {@code i}, or -1 if there is none. */
        public int keyframeAtOrAfter(int i) {
            for (int k = Math.max(i, 0); k < pts.length; k++) {
                if (isKeyframe(k)) {
                    return k;
                }
            }
            return -1;
        }

        /** Collects samples in order; checks each against the format's limits. */
        public static final class Builder {
            private final Kind kind;
            private final long timescale;
            private final int fileRef;
            private int count;
            private long[] offsets;
            private long[] sizes;
            private long[] pts;
            private long[] durations;
            private byte[] flags;

            public Builder(Kind kind, long timescale, int fileRef) {
                this(kind, timescale, fileRef, 1024);
            }

            Builder(Kind kind, long timescale, int fileRef, int capacity) {
                if (kind == null) {
                    throw new IllegalArgumentException("kind is null");
                }
                if (timescale <= 0 || timescale > U32_MAX) {
                    throw new IllegalArgumentException("timescale out of range: " + timescale);
                }
                if (fileRef < 0 || fileRef > MAX_FILES - 1) {
                    throw new IllegalArgumentException("file-ref out of range: " + fileRef);
                }
                this.kind = kind;
                this.timescale = timescale;
                this.fileRef = fileRef;
                int cap = Math.max(capacity, 16);
                offsets = new long[cap];
                sizes = new long[cap];
                pts = new long[cap];
                durations = new long[cap];
                flags = new byte[cap];
            }

            public int count() {
                return count;
            }

            /**
             * Appends a sample.
             *
             * @throws IllegalArgumentException for a negative offset, a size or duration outside
             *         0..2^31-1, flags outside a byte, or a PTS not after the previous sample's
             */
            public Builder add(long offset, long size, long sampleTimestamp, long duration, int sampleFlags) {
                if (offset < 0) {
                    throw new IllegalArgumentException("negative offset " + offset);
                }
                if (size < 0 || size > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("size out of range: " + size);
                }
                if (duration < 0 || duration > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("duration out of range: " + duration);
                }
                if (sampleFlags < 0 || sampleFlags > 0xFF) {
                    throw new IllegalArgumentException("flags out of range: " + sampleFlags);
                }
                if (count > 0 && sampleTimestamp <= pts[count - 1]) {
                    throw new IllegalArgumentException("pts " + sampleTimestamp
                            + " not after the previous sample's " + pts[count - 1]);
                }
                if (count == pts.length) {
                    int cap = (int) Math.min(Integer.MAX_VALUE - 8, pts.length * 2L);
                    if (cap == pts.length) {
                        throw new IllegalArgumentException("too many samples");
                    }
                    offsets = Arrays.copyOf(offsets, cap);
                    sizes = Arrays.copyOf(sizes, cap);
                    pts = Arrays.copyOf(pts, cap);
                    durations = Arrays.copyOf(durations, cap);
                    flags = Arrays.copyOf(flags, cap);
                }
                offsets[count] = offset;
                sizes[count] = size;
                pts[count] = sampleTimestamp;
                durations[count] = duration;
                flags[count] = (byte) sampleFlags;
                count++;
                return this;
            }

            public Track build() {
                return new Track(this);
            }
        }
    }
}
