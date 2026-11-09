package io.siggi.togethertunes.media;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A small MP4 (ISO BMFF) box walker. It reads box headers straight from the file and seeks past
 * payloads, so a multi-GB mezzanine costs a handful of small reads wherever {@code moov} sits.
 *
 * <p>Its main job is {@link #readAvcC}: the H.264 decoder configuration from
 * {@code moov/trak/mdia/minf/stbl/stsd/avc1/avcC}, used for the avcC invariant check (invariant 1)
 * and, later, for SPS/PPS and the NAL length size in the packager.
 */
public final class Mp4Boxes {
    /** Largest avcC payload accepted (a real one is under 100 bytes). */
    static final int MAX_AVCC = 64 * 1024;
    /** Bytes of a {@code stsd} payload before its sample entries: version/flags and entry_count. */
    private static final int STSD_HEADER = 8;
    /** Bytes of a VisualSampleEntry payload before its child boxes (ISO/IEC 14496-12, 12.1.3). */
    private static final int VISUAL_SAMPLE_ENTRY = 78;

    private Mp4Boxes() {
    }

    /**
     * One box: its four-character type, where it starts in the file, and its total size (header
     * included). The payload is {@code [payloadStart, end)}.
     */
    public record Box(String type, long start, long size, int headerSize) {
        public long payloadStart() {
            return start + headerSize;
        }

        public long payloadSize() {
            return size - headerSize;
        }

        public long end() {
            return start + size;
        }
    }

    /** The file isn't a well-formed MP4, or lacks what was asked for. */
    public static final class Mp4FormatException extends IOException {
        public Mp4FormatException(String message) {
            super(message);
        }
    }

    /**
     * Reads the payload of the first video track's {@code avcC} box (the
     * AVCDecoderConfigurationRecord), i.e. what ffprobe reports as the stream's extradata.
     *
     * @throws Mp4FormatException if the file is malformed or has no {@code avc1} track with an
     *                            {@code avcC}
     */
    public static byte[] readAvcC(Path mp4) throws IOException {
        try (FileChannel ch = FileChannel.open(mp4, StandardOpenOption.READ)) {
            return readAvcC(ch);
        } catch (Mp4FormatException e) {
            throw new Mp4FormatException(mp4 + ": " + e.getMessage());
        }
    }

    /** {@link #readAvcC(Path)} on an open channel. */
    public static byte[] readAvcC(SeekableByteChannel ch) throws IOException {
        Box moov = find(ch, 0, ch.size(), "moov", true);
        if (moov == null) {
            throw new Mp4FormatException("no moov box");
        }
        for (Box trak : children(ch, moov.payloadStart(), moov.end(), false)) {
            if (!trak.type().equals("trak")) {
                continue;
            }
            Box stsd = path(ch, trak, "mdia", "minf", "stbl", "stsd");
            if (stsd == null) {
                continue;
            }
            if (stsd.payloadSize() < STSD_HEADER) {
                throw new Mp4FormatException("stsd box too short");
            }
            Box avc1 = find(ch, stsd.payloadStart() + STSD_HEADER, stsd.end(), "avc1", false);
            if (avc1 == null) {
                continue;
            }
            if (avc1.payloadSize() < VISUAL_SAMPLE_ENTRY) {
                throw new Mp4FormatException("avc1 sample entry too short");
            }
            Box avcC = find(ch, avc1.payloadStart() + VISUAL_SAMPLE_ENTRY, avc1.end(), "avcC", false);
            if (avcC == null) {
                throw new Mp4FormatException("avc1 sample entry has no avcC box");
            }
            if (avcC.payloadSize() > MAX_AVCC) {
                throw new Mp4FormatException("avcC box too large (" + avcC.payloadSize() + " bytes)");
            }
            return read(ch, avcC.payloadStart(), (int) avcC.payloadSize());
        }
        throw new Mp4FormatException("no avc1 track with an avcC box");
    }

    /** Follows a chain of child box types below {@code parent}; null if any link is missing. */
    private static Box path(SeekableByteChannel ch, Box parent, String... types) throws IOException {
        Box box = parent;
        for (String type : types) {
            box = find(ch, box.payloadStart(), box.end(), type, false);
            if (box == null) {
                return null;
            }
        }
        return box;
    }

    /** The first box of {@code type} among the boxes in {@code [from, to)}, or null. */
    public static Box find(SeekableByteChannel ch, long from, long to, String type, boolean topLevel)
            throws IOException {
        long pos = from;
        while (pos < to) {
            Box box = header(ch, pos, to, topLevel);
            if (box.type().equals(type)) {
                return box;
            }
            pos = box.end();
        }
        return null;
    }

    /**
     * Lists the boxes in {@code [from, to)}, in file order. {@code topLevel} allows a final box
     * with size 0 ("extends to the end of the file").
     */
    public static List<Box> children(SeekableByteChannel ch, long from, long to, boolean topLevel)
            throws IOException {
        List<Box> boxes = new ArrayList<>();
        long pos = from;
        while (pos < to) {
            Box box = header(ch, pos, to, topLevel);
            boxes.add(box);
            pos = box.end();
        }
        return boxes;
    }

    /** Reads the box header at {@code pos}; the box must end by {@code limit}. */
    static Box header(SeekableByteChannel ch, long pos, long limit, boolean topLevel) throws IOException {
        if (limit - pos < 8) {
            throw new Mp4FormatException("truncated box header at offset " + pos);
        }
        ByteBuffer head = ByteBuffer.wrap(read(ch, pos, 8));
        long size = Integer.toUnsignedLong(head.getInt());
        String type = fourCc(head);
        int headerSize = 8;
        if (size == 1) {
            if (limit - pos < 16) {
                throw new Mp4FormatException("truncated largesize header for " + type + " at offset " + pos);
            }
            size = ByteBuffer.wrap(read(ch, pos + 8, 8)).getLong();
            headerSize = 16;
        } else if (size == 0) {
            if (!topLevel) {
                throw new Mp4FormatException("box " + type + " at offset " + pos + " has size 0 inside a parent");
            }
            size = limit - pos;
        }
        if (type.equals("uuid")) {
            headerSize += 16;
        }
        if (size < headerSize || size > limit - pos) {
            throw new Mp4FormatException("box " + type + " at offset " + pos + " has bad size " + size);
        }
        return new Box(type, pos, size, headerSize);
    }

    private static String fourCc(ByteBuffer buf) {
        byte[] t = new byte[4];
        buf.get(t);
        return new String(t, StandardCharsets.ISO_8859_1);
    }

    private static byte[] read(SeekableByteChannel ch, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        ch.position(pos);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) {
                throw new EOFException("unexpected end of file at offset " + ch.position());
            }
        }
        return buf.array();
    }
}
