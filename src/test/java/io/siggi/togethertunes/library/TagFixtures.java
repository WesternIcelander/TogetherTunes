package io.siggi.togethertunes.library;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tags that ffmpeg's muxers can't write, added to files ffmpeg made, for the upload tag tests
 * ({@code docs/library-and-ingest.md#song-details}): MP4 freeform atoms
 * ({@code ----:com.apple.iTunes:<NAME>}, as iTunes, Picard and TrackSplitter write ISRC, LABEL,
 * BARCODE and ARTISTS) and repeated FLAC Vorbis comments.
 */
final class TagFixtures {
    private TagFixtures() {
    }

    /**
     * Appends one {@code ----:com.apple.iTunes:<name>} atom per entry to {@code moov/udta/meta/ilst}
     * of an MP4 that ffmpeg wrote (moov last, so no chunk offset moves), each value its own
     * {@code data} atom (UTF-8), like TrackSplitter's ARTISTS with every name.
     */
    static void mp4Freeform(Path file, Map<String, List<String>> atoms) throws IOException {
        byte[] in = Files.readAllBytes(file);
        ByteArrayOutputStream add = new ByteArrayOutputStream();
        for (Map.Entry<String, List<String>> e : atoms.entrySet()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(fullBox("mean", "com.apple.iTunes".getBytes(StandardCharsets.UTF_8)));
            body.writeBytes(fullBox("name", e.getKey().getBytes(StandardCharsets.UTF_8)));
            for (String v : e.getValue()) {
                byte[] text = v.getBytes(StandardCharsets.UTF_8);
                ByteBuffer data = ByteBuffer.allocate(8 + text.length);
                data.putInt(1); // well-known type 1: UTF-8
                data.putInt(0); // locale
                data.put(text);
                body.writeBytes(box("data", data.array()));
            }
            add.writeBytes(box("----", body.toByteArray()));
        }
        byte[] extra = add.toByteArray();
        // top level: moov must be the last box
        int moov = -1;
        for (int at = 0; at < in.length; ) {
            int size = ByteBuffer.wrap(in, at, 4).getInt();
            String type = new String(in, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (size < 8) {
                throw new IOException("unexpected box size at " + at);
            }
            if (type.equals("moov")) {
                moov = at;
                if (at + size != in.length) {
                    throw new IOException("moov isn't the last box");
                }
            }
            at += size;
        }
        if (moov < 0) {
            throw new IOException("no moov");
        }
        int udta = child(in, moov, 8, "udta");
        int meta = child(in, udta, 8, "meta");
        int ilst = child(in, meta, 12, "ilst"); // meta is a full box
        int ilstEnd = ilst + size(in, ilst);
        byte[] out = new byte[in.length + extra.length];
        System.arraycopy(in, 0, out, 0, ilstEnd);
        System.arraycopy(extra, 0, out, ilstEnd, extra.length);
        System.arraycopy(in, ilstEnd, out, ilstEnd + extra.length, in.length - ilstEnd);
        for (int box : new int[] {moov, udta, meta, ilst}) {
            ByteBuffer.wrap(out, box, 4).putInt(size(in, box) + extra.length);
        }
        Files.write(file, out);
    }

    private static int size(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).getInt();
    }

    /** The child box {@code type} of the box at {@code parent} (children from {@code headerLen}). */
    private static int child(byte[] b, int parent, int headerLen, String type) throws IOException {
        int end = parent + size(b, parent);
        for (int at = parent + headerLen; at + 8 <= end; ) {
            int size = size(b, at);
            if (size < 8) {
                break;
            }
            if (new String(b, at + 4, 4, StandardCharsets.ISO_8859_1).equals(type)) {
                return at;
            }
            at += size;
        }
        throw new IOException("no " + type + " box");
    }

    private static byte[] box(String type, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length);
        b.putInt(8 + body.length);
        b.put(type.getBytes(StandardCharsets.ISO_8859_1));
        b.put(body);
        return b.array();
    }

    private static byte[] fullBox(String type, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(4 + body.length);
        b.putInt(0); // version and flags
        b.put(body);
        return box(type, b.array());
    }

    /**
     * Replaces the Vorbis comments of a FLAC file with {@code comments} ({@code KEY=value} each,
     * repeats allowed: {@code ARTISTS=A}, {@code ARTISTS=B}), keeping the vendor string.
     */
    static void flacComments(Path file, List<String> comments) throws IOException {
        byte[] in = Files.readAllBytes(file);
        if (!new String(in, 0, 4, StandardCharsets.ISO_8859_1).equals("fLaC")) {
            throw new IOException("not a FLAC file");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(in, 0, 4);
        int at = 4;
        boolean last = false;
        while (!last) {
            int header = in[at] & 0xFF;
            last = (header & 0x80) != 0;
            int type = header & 0x7F;
            int len = ((in[at + 1] & 0xFF) << 16) | ((in[at + 2] & 0xFF) << 8) | (in[at + 3] & 0xFF);
            byte[] body = java.util.Arrays.copyOfRange(in, at + 4, at + 4 + len);
            if (type == 4) {
                ByteBuffer old = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                byte[] vendor = new byte[old.getInt()];
                old.get(vendor);
                ByteArrayOutputStream c = new ByteArrayOutputStream();
                c.writeBytes(le(vendor.length));
                c.writeBytes(vendor);
                c.writeBytes(le(comments.size()));
                for (String comment : comments) {
                    byte[] t = comment.getBytes(StandardCharsets.UTF_8);
                    c.writeBytes(le(t.length));
                    c.writeBytes(t);
                }
                body = c.toByteArray();
            }
            out.write(header);
            out.write(body.length >> 16);
            out.write(body.length >> 8);
            out.write(body.length);
            out.writeBytes(body);
            at += 4 + len;
        }
        out.write(in, at, in.length - at);
        Files.write(file, out.toByteArray());
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }
}
