package io.siggi.togethertunes.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Music-store purchase data added to an {@code .m4a} that ffmpeg wrote, for the tests of removing
 * personal data from uploads: the {@code apID} and {@code ownr} tags in {@code moov/udta/meta/ilst}
 * and a {@code pinf} box at the end of the {@code mp4a} sample entry, the way a store purchase
 * carries them (ffmpeg's {@code -metadata} can't write any of them). Made-up values only.
 */
public final class PurchaseFixtures {
    /** The made-up buyer. */
    public static final String EMAIL = "buyer.fixture@example.invalid";
    public static final String NAME = "Fixture Buyer Person";

    private PurchaseFixtures() {
    }

    /**
     * Adds the tags and the box to {@code file} (ffmpeg's MP4 with {@code moov} last and some
     * {@code -metadata}, so {@code ilst} exists and no chunk offset moves).
     */
    public static void addPurchaseData(Path file) throws IOException {
        byte[] in = Files.readAllBytes(file);
        int moov = lastTopLevel(in, "moov");
        int udta = child(in, moov, 8, "udta");
        int meta = child(in, udta, 8, "meta");
        int ilst = child(in, meta, 12, "ilst");
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        tags.writeBytes(textItem("apID", EMAIL));
        tags.writeBytes(textItem("ownr", NAME));
        in = insert(in, new int[] {moov, udta, meta, ilst}, tags.toByteArray());

        moov = lastTopLevel(in, "moov");
        int trak = child(in, moov, 8, "trak");
        int mdia = child(in, trak, 8, "mdia");
        int minf = child(in, mdia, 8, "minf");
        int stbl = child(in, minf, 8, "stbl");
        int stsd = child(in, stbl, 8, "stsd");
        int mp4a = child(in, stsd, 16, "mp4a"); // stsd: version/flags and the entry count first
        // the purchase-info box: the buyer's name and account, as a store writes it (key/value boxes)
        ByteArrayOutputStream pinf = new ByteArrayOutputStream();
        pinf.writeBytes(box("name", NAME.getBytes(StandardCharsets.UTF_8)));
        pinf.writeBytes(box("user", EMAIL.getBytes(StandardCharsets.UTF_8)));
        pinf.writeBytes(box("key ", new byte[] {0, 0, 0, 42}));
        in = insert(in, new int[] {moov, trak, mdia, minf, stbl, stsd, mp4a}, box("pinf", pinf.toByteArray()));
        Files.write(file, in);
    }

    /**
     * Blanks the first {@code pinf} box in place (a {@code free} box of the same size, all zeros),
     * as a stand-in cleaner for tests that don't need TrackSplitter; false if there is none.
     */
    public static boolean blankPinf(Path file) throws IOException {
        byte[] b = Files.readAllBytes(file);
        int at = indexOf(b, "pinf".getBytes(StandardCharsets.ISO_8859_1));
        if (at < 4) {
            return false;
        }
        int size = ByteBuffer.wrap(b, at - 4, 4).getInt();
        System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, b, at, 4);
        java.util.Arrays.fill(b, at + 4, at - 4 + size, (byte) 0);
        Files.write(file, b);
        return true;
    }

    /** The printable strings (6 characters or more) in the first {@code pinf} box of an MP4; empty if none. */
    public static List<String> pinfStrings(byte[] file) {
        int at = indexOf(file, "pinf".getBytes(StandardCharsets.ISO_8859_1));
        List<String> out = new ArrayList<>();
        if (at < 4) {
            return out;
        }
        int size = ByteBuffer.wrap(file, at - 4, 4).getInt();
        String body = new String(file, at + 4, size - 8, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("[\\x20-\\x7e]{6,}").matcher(body);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Whether {@code needle} occurs in {@code hay}. */
    public static boolean contains(byte[] hay, String needle) {
        return indexOf(hay, needle.getBytes(StandardCharsets.ISO_8859_1)) >= 0;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] textItem(String type, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(8 + text.length);
        data.putInt(1); // well-known type 1: UTF-8
        data.putInt(0);
        data.put(text);
        return box(type, box("data", data.array()));
    }

    /** Inserts {@code extra} at the end of the last box in {@code chain}, growing every box in it. */
    private static byte[] insert(byte[] in, int[] chain, byte[] extra) {
        int innermost = chain[chain.length - 1];
        int end = innermost + size(in, innermost);
        byte[] out = new byte[in.length + extra.length];
        System.arraycopy(in, 0, out, 0, end);
        System.arraycopy(extra, 0, out, end, extra.length);
        System.arraycopy(in, end, out, end + extra.length, in.length - end);
        for (int b : chain) {
            ByteBuffer.wrap(out, b, 4).putInt(size(in, b) + extra.length);
        }
        return out;
    }

    private static int lastTopLevel(byte[] in, String type) throws IOException {
        int found = -1;
        for (int at = 0; at < in.length; ) {
            int size = size(in, at);
            if (size < 8) {
                throw new IOException("unexpected box size at " + at);
            }
            if (new String(in, at + 4, 4, StandardCharsets.ISO_8859_1).equals(type)) {
                if (at + size != in.length) {
                    throw new IOException(type + " isn't the last box");
                }
                found = at;
            }
            at += size;
        }
        if (found < 0) {
            throw new IOException("no " + type);
        }
        return found;
    }

    private static int size(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).getInt();
    }

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
}
