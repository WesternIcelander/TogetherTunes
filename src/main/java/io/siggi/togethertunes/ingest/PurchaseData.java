package io.siggi.togethertunes.ingest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds what identifies the buyer of a music-store file in an MP4 ({@code .m4a}): the {@code apID}
 * (account email) and {@code ownr} (account name) tags in {@code moov/udta/meta/ilst}, and the
 * purchase-info box ({@code pinf}, or {@code sinf} in older purchases) in an unprotected audio
 * sample entry ({@code mp4a}). Used to check that TrackSplitter's cleaning really removed them
 * ({@code docs/library-and-ingest.md#looking-details-up-online}, "Removing personal data from uploads").
 * Protected entries ({@code drms}, {@code enca}) aren't looked into: their {@code sinf} is needed to
 * play, and such a file isn't playable here anyway.
 */
public final class PurchaseData {
    /** The largest {@code moov} read into memory (it holds only metadata; the audio is in {@code mdat}). */
    static final long MAX_MOOV = 64L * 1024 * 1024;

    private PurchaseData() {
    }

    /**
     * The purchase data in an MP4 file: a sorted set of {@code apID}, {@code ownr}, {@code pinf}
     * and {@code sinf}; empty when there is none (or the file has no {@code moov}).
     *
     * @throws IOException if the file can't be read, or its {@code moov} is too large
     */
    public static Set<String> find(Path file) throws IOException {
        Set<String> found = new TreeSet<>();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long len = ch.size();
            long off = 0;
            while (off + 8 <= len) {
                ByteBuffer hdr = ByteBuffer.allocate(16);
                ch.read(hdr, off);
                hdr.flip();
                if (hdr.remaining() < 8) {
                    break;
                }
                long size = Integer.toUnsignedLong(hdr.getInt(0));
                if (size == 1) {
                    if (hdr.remaining() < 16) {
                        break;
                    }
                    size = hdr.getLong(8);
                } else if (size == 0) {
                    size = len - off;
                }
                if (size < 8) {
                    break;
                }
                String type = new String(new byte[] {hdr.get(4), hdr.get(5), hdr.get(6), hdr.get(7)},
                        StandardCharsets.ISO_8859_1);
                if (type.equals("moov")) {
                    if (size > MAX_MOOV) {
                        throw new IOException("moov too large: " + size + " bytes");
                    }
                    ByteBuffer moov = ByteBuffer.allocate((int) size);
                    while (moov.hasRemaining()) {
                        if (ch.read(moov, off + moov.position()) < 0) {
                            throw new IOException("moov cut short");
                        }
                    }
                    walk(moov.array(), 0, (int) size, found, false);
                }
                off += size;
            }
        }
        return found;
    }

    private static void walk(byte[] d, int off, int end, Set<String> found, boolean inAudioEntry) {
        while (off + 8 <= end) {
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(d, off, 4).getInt());
            int hdr = 8;
            if (size == 1) {
                if (off + 16 > end) {
                    return;
                }
                size = ByteBuffer.wrap(d, off + 8, 8).getLong();
                hdr = 16;
            } else if (size == 0) {
                size = end - off;
            }
            if (size < hdr || off + size > end) {
                return;
            }
            int boxEnd = off + (int) size;
            String type = new String(d, off + 4, 4, StandardCharsets.ISO_8859_1);
            int body = off + hdr;
            switch (type) {
                case "moov", "trak", "mdia", "minf", "stbl", "udta", "ilst" -> walk(d, body, boxEnd, found, false);
                // full boxes: version/flags (and stsd's entry count) before the children
                case "meta" -> walk(d, body + 4, boxEnd, found, false);
                case "stsd" -> walk(d, body + 8, boxEnd, found, false);
                case "mp4a" -> {
                    if (body + 10 <= boxEnd) {
                        // audio sample entry: 8 bytes, then a sound description whose length depends on its version
                        int version = ((d[body + 8] & 0xFF) << 8) | (d[body + 9] & 0xFF);
                        int fields = switch (version) {
                            case 0 -> 28;
                            case 1 -> 44;
                            case 2 -> 64;
                            default -> -1;
                        };
                        if (fields >= 0) {
                            walk(d, body + fields, boxEnd, found, true);
                        }
                    }
                }
                case "apID", "ownr" -> found.add(type);
                case "pinf", "sinf" -> {
                    if (inAudioEntry) {
                        found.add(type);
                    }
                }
                default -> {
                }
            }
            off = boxEnd;
        }
    }
}
