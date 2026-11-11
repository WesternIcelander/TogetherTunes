package io.siggi.togethertunes.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns an MP4 holding one H.264 frame (as ffmpeg writes it: {@code ftyp}, {@code mdat},
 * {@code moov}, one video track) into an MP4 holding that frame followed by more frames, each one
 * frame duration later: the skip-frame cards' container ({@code docs/library-and-ingest.md#cards}).
 *
 * <p>Nothing about the track's description changes: the whole sample description ({@code stsd},
 * with the {@code avcC} and whatever else the installed ffmpeg puts there) and every other box
 * ffmpeg wrote are copied byte for byte, so the card's avcC is exactly what this ffmpeg writes for
 * every other encode (invariant 1). Only what depends on the number of samples is written anew:
 * the durations in {@code mvhd}, {@code tkhd}, {@code mdhd} and the edit list, and the sample
 * tables ({@code stts}, {@code stss}, {@code stsc}, {@code stsz}, {@code stco}). The frame times
 * come from the input's own {@code stts} (frame {@code n} at {@code n·delta}), so no ffmpeg
 * timestamp option is involved: those changed meaning between ffmpeg versions (an ffmpeg remux with
 * {@code setts} put frames at the wrong times on ffmpeg 5.1 and on 2026 builds).
 *
 * <p>Anything unexpected (fragmented files, more than one track or sample, composition offsets, an
 * edit list that shifts the media, sample-table boxes this class doesn't know) is refused with an
 * {@link IOException} rather than guessed at.
 */
public final class StillGopMp4 {
    /** Boxes whose children are boxes, on the path to the sample tables and the edit list. */
    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "stbl", "edts");
    /** Sample-table boxes this class writes anew; any other {@code stbl} child but {@code stsd} is refused. */
    private static final Set<String> SAMPLE_TABLES = Set.of("stts", "stss", "stsc", "stsz", "stco");

    private StillGopMp4() {
    }

    /** The parameter sets and NAL length size of an AVCDecoderConfigurationRecord. */
    public record AvcConfig(byte[] sps, byte[] pps, int nalLengthSize) {
    }

    /**
     * Parses an {@code avcC} payload with exactly one SPS and one PPS.
     *
     * @throws IOException if it is malformed or holds more or fewer parameter sets
     */
    public static AvcConfig avcConfig(byte[] avcC) throws IOException {
        try {
            ByteBuffer b = ByteBuffer.wrap(avcC);
            if (b.get() != 1) {
                throw new IOException("avcC version isn't 1");
            }
            b.position(4);
            int lengthSize = (b.get() & 3) + 1;
            int spsCount = b.get() & 0x1F;
            if (spsCount != 1) {
                throw new IOException("avcC has " + spsCount + " SPS, not 1");
            }
            byte[] sps = new byte[b.getShort() & 0xFFFF];
            b.get(sps);
            int ppsCount = b.get() & 0xFF;
            if (ppsCount != 1) {
                throw new IOException("avcC has " + ppsCount + " PPS, not 1");
            }
            byte[] pps = new byte[b.getShort() & 0xFFFF];
            b.get(pps);
            return new AvcConfig(sps, pps, lengthSize);
        } catch (RuntimeException e) {
            throw new IOException("truncated avcC", e);
        }
    }

    /** What {@link #read} found in a one-frame MP4. */
    public record OneFrame(byte[] avcC, byte[] sample, int frameTicks) {
    }

    /**
     * Reads a one-frame MP4: its avcC, its one sample (length-prefixed NAL units) and that sample's
     * duration in the track's time base.
     *
     * @throws IOException if the file isn't a one-sample, one-track, unfragmented H.264 MP4
     */
    public static OneFrame read(byte[] mp4) throws IOException {
        Layout l = layout(mp4);
        return new OneFrame(l.avcC, l.sample, l.frameTicks);
    }

    /**
     * The MP4 {@code oneFrameMp4} with {@code more} samples appended after its own (each a
     * complete sample: length-prefixed NAL units), each lasting as long as the first. Only the
     * first sample is a sync sample. The result is laid out {@code ftyp}, {@code mdat},
     * {@code moov}.
     *
     * @throws IOException if {@code oneFrameMp4} isn't what {@link #read} accepts, or holds a box this
     *                     class won't rewrite
     */
    public static byte[] extend(byte[] oneFrameMp4, List<byte[]> more) throws IOException {
        Layout l = layout(oneFrameMp4);
        List<byte[]> samples = new ArrayList<>(more.size() + 1);
        samples.add(l.sample);
        samples.addAll(more);
        long mediaDuration = (long) l.frameTicks * samples.size();
        long movieDuration = ceilDiv(mediaDuration * l.movieTimescale, l.mediaTimescale);

        ByteArrayOutputStream mdat = new ByteArrayOutputStream();
        for (byte[] s : samples) {
            mdat.writeBytes(s);
        }
        long mdatSize = 8L + mdat.size();
        if (mdatSize > 0xFFFF_FFFFL) {
            throw new IOException("card too large");
        }
        long firstSample = l.ftyp.length + 8L;

        Rewrite rw = new Rewrite(samples, l.frameTicks, mediaDuration, movieDuration, firstSample);
        byte[] moov = rw.box(oneFrameMp4, l.moov);

        ByteArrayOutputStream out = new ByteArrayOutputStream((int) (l.ftyp.length + mdatSize + moov.length));
        out.writeBytes(l.ftyp);
        out.writeBytes(header((int) mdatSize, "mdat"));
        out.writeBytes(mdat.toByteArray());
        out.writeBytes(moov);
        return out.toByteArray();
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /** A box in a byte array: type, start, total size, header size. */
    private record Box(String type, int start, int size, int headerSize) {
        int payload() {
            return start + headerSize;
        }

        int end() {
            return start + size;
        }
    }

    private static List<Box> children(byte[] d, int from, int to) throws IOException {
        List<Box> boxes = new ArrayList<>();
        int pos = from;
        while (pos < to) {
            if (to - pos < 8) {
                throw new IOException("truncated box header at " + pos);
            }
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(d, pos, 4).getInt());
            String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (size < 8 || size > to - pos) {
                // size 1 (64-bit) and 0 (to the end) don't occur in a small card file ffmpeg wrote
                throw new IOException("box " + type + " at " + pos + " has unsupported size " + size);
            }
            boxes.add(new Box(type, pos, (int) size, 8));
            pos += (int) size;
        }
        return boxes;
    }

    private static Box only(List<Box> boxes, String type, String where) throws IOException {
        Box found = null;
        for (Box b : boxes) {
            if (b.type.equals(type)) {
                if (found != null) {
                    throw new IOException("more than one " + type + " in " + where);
                }
                found = b;
            }
        }
        if (found == null) {
            throw new IOException("no " + type + " in " + where);
        }
        return found;
    }

    private static Box path(byte[] d, Box from, String... types) throws IOException {
        Box box = from;
        String where = from.type;
        for (String t : types) {
            box = only(children(d, box.payload(), box.end()), t, where);
            where += "/" + t;
        }
        return box;
    }

    private static final class Layout {
        byte[] ftyp;
        Box moov;
        int movieTimescale;
        int mediaTimescale;
        int frameTicks;
        byte[] avcC;
        byte[] sample;
    }

    private static Layout layout(byte[] d) throws IOException {
        Layout l = new Layout();
        List<Box> top = children(d, 0, d.length);
        for (Box b : top) {
            if (b.type.equals("moof") || b.type.equals("mvex")) {
                throw new IOException("fragmented MP4");
            }
        }
        Box ftyp = only(top, "ftyp", "file");
        if (ftyp.start != 0) {
            throw new IOException("ftyp isn't first");
        }
        l.ftyp = java.util.Arrays.copyOfRange(d, ftyp.start, ftyp.end());
        l.moov = only(top, "moov", "file");
        Box trak = only(children(d, l.moov.payload(), l.moov.end()), "trak", "moov");
        ByteBuffer mvhd = payload(d, path(d, l.moov, "mvhd"));
        l.movieTimescale = mvhd.getInt(mvhd.get(0) == 1 ? 20 : 12);
        ByteBuffer hdlr = payload(d, path(d, trak, "mdia", "hdlr"));
        if (hdlr.getInt(8) != fourCc("vide")) {
            throw new IOException("the track isn't video");
        }
        ByteBuffer mdhd = payload(d, path(d, trak, "mdia", "mdhd"));
        l.mediaTimescale = mdhd.getInt(mdhd.get(0) == 1 ? 20 : 12);
        if (l.movieTimescale <= 0 || l.mediaTimescale <= 0) {
            throw new IOException("bad time scale");
        }
        Box stbl = path(d, trak, "mdia", "minf", "stbl");
        for (Box b : children(d, stbl.payload(), stbl.end())) {
            if (!b.type.equals("stsd") && !SAMPLE_TABLES.contains(b.type) && !b.type.equals("co64")) {
                throw new IOException("unsupported sample table box " + b.type);
            }
        }
        Box stsd = path(d, stbl, "stsd");
        if (ByteBuffer.wrap(d, stsd.payload() + 4, 4).getInt() != 1) {
            throw new IOException("stsd doesn't hold exactly one sample entry");
        }
        Box entry = children(d, stsd.payload() + 8, stsd.end()).get(0);
        if (!entry.type.equals("avc1")) {
            throw new IOException("the sample entry is " + entry.type + ", not avc1");
        }
        Box avcC = only(children(d, entry.payload() + 78, entry.end()), "avcC", "avc1");
        l.avcC = java.util.Arrays.copyOfRange(d, avcC.payload(), avcC.end());

        ByteBuffer stts = payload(d, path(d, stbl, "stts"));
        if (stts.getInt(4) != 1 || stts.getInt(8) != 1) {
            throw new IOException("the MP4 doesn't hold exactly one sample");
        }
        l.frameTicks = stts.getInt(12);
        if (l.frameTicks <= 0) {
            throw new IOException("bad frame duration " + l.frameTicks);
        }
        ByteBuffer stsz = payload(d, path(d, stbl, "stsz"));
        if (stsz.getInt(8) != 1) {
            throw new IOException("stsz doesn't count one sample");
        }
        int size = stsz.getInt(4) != 0 ? stsz.getInt(4) : stsz.getInt(12);
        List<Box> stblKids = children(d, stbl.payload(), stbl.end());
        long offset;
        if (stblKids.stream().anyMatch(b -> b.type.equals("stco"))) {
            ByteBuffer stco = payload(d, path(d, stbl, "stco"));
            if (stco.getInt(4) != 1) {
                throw new IOException("stco doesn't hold one chunk");
            }
            offset = Integer.toUnsignedLong(stco.getInt(8));
        } else {
            ByteBuffer co64 = payload(d, path(d, stbl, "co64"));
            if (co64.getInt(4) != 1) {
                throw new IOException("co64 doesn't hold one chunk");
            }
            offset = co64.getLong(8);
        }
        if (offset < 0 || size <= 0 || offset + size > d.length) {
            throw new IOException("the sample lies outside the file");
        }
        l.sample = java.util.Arrays.copyOfRange(d, (int) offset, (int) offset + size);

        Box edts = null;
        for (Box b : children(d, trak.payload(), trak.end())) {
            if (b.type.equals("edts")) {
                edts = b;
            }
        }
        if (edts != null) {
            ByteBuffer elst = payload(d, path(d, edts, "elst"));
            boolean v1 = elst.get(0) == 1;
            if (elst.getInt(4) != 1) {
                throw new IOException("the edit list has " + elst.getInt(4) + " entries");
            }
            long mediaTime = v1 ? elst.getLong(16) : elst.getInt(12);
            if (mediaTime != 0) {
                throw new IOException("the edit list starts the media at " + mediaTime + ", not 0");
            }
        }
        return l;
    }

    private static ByteBuffer payload(byte[] d, Box b) {
        return ByteBuffer.wrap(java.util.Arrays.copyOfRange(d, b.payload(), b.end()));
    }

    private static int fourCc(String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        return ByteBuffer.wrap(b).getInt();
    }

    private static byte[] header(int size, String type) {
        return ByteBuffer.allocate(8).putInt(size).put(type.getBytes(StandardCharsets.ISO_8859_1)).array();
    }

    private static byte[] box(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        out.writeBytes(header(payload.length + 8, type));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** Rebuilds {@code moov} with the new durations and sample tables. */
    private record Rewrite(List<byte[]> samples, int frameTicks, long mediaDuration, long movieDuration,
                           long firstSample) {
        byte[] box(byte[] d, Box b) throws IOException {
            if (CONTAINERS.contains(b.type)) {
                ByteArrayOutputStream kids = new ByteArrayOutputStream();
                if (b.type.equals("stbl")) {
                    for (Box k : children(d, b.payload(), b.end())) {
                        if (k.type.equals("stsd")) {
                            kids.writeBytes(java.util.Arrays.copyOfRange(d, k.start, k.end()));
                        }
                    }
                    kids.writeBytes(sampleTables());
                } else {
                    for (Box k : children(d, b.payload(), b.end())) {
                        kids.writeBytes(box(d, k));
                    }
                }
                return StillGopMp4.box(b.type, kids.toByteArray());
            }
            byte[] p = java.util.Arrays.copyOfRange(d, b.payload(), b.end());
            ByteBuffer buf = ByteBuffer.wrap(p);
            boolean v1 = p.length > 0 && p[0] == 1;
            switch (b.type) {
                case "mvhd" -> putDuration(buf, v1 ? 24 : 16, v1, movieDuration);
                case "tkhd" -> putDuration(buf, v1 ? 28 : 20, v1, movieDuration);
                case "mdhd" -> putDuration(buf, v1 ? 24 : 16, v1, mediaDuration);
                case "elst" -> putDuration(buf, 8, v1, movieDuration);
                default -> {
                    return java.util.Arrays.copyOfRange(d, b.start, b.end());
                }
            }
            return StillGopMp4.box(b.type, p);
        }

        private static void putDuration(ByteBuffer buf, int at, boolean v1, long value) throws IOException {
            if (v1) {
                buf.putLong(at, value);
            } else {
                if (value > 0xFFFF_FFFFL) {
                    throw new IOException("duration too long for a version 0 box");
                }
                buf.putInt(at, (int) value);
            }
        }

        private byte[] sampleTables() {
            int n = samples.size();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(StillGopMp4.box("stts", ByteBuffer.allocate(16).putInt(0).putInt(1).putInt(n)
                    .putInt(frameTicks).array()));
            out.writeBytes(StillGopMp4.box("stss", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(1).array()));
            out.writeBytes(StillGopMp4.box("stsc", ByteBuffer.allocate(20).putInt(0).putInt(1).putInt(1).putInt(n)
                    .putInt(1).array()));
            ByteBuffer stsz = ByteBuffer.allocate(12 + 4 * n).putInt(0).putInt(0).putInt(n);
            for (byte[] s : samples) {
                stsz.putInt(s.length);
            }
            out.writeBytes(StillGopMp4.box("stsz", stsz.array()));
            out.writeBytes(StillGopMp4.box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1)
                    .putInt((int) firstSample).array()));
            return out.toByteArray();
        }
    }
}
