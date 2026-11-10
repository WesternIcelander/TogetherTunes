package io.siggi.togethertunes.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The content ID of an upload ({@code items.content_id}, {@code docs/library-and-ingest.md#content-id}):
 * a <b>chunked SHA-256</b> that a browser can compute with {@code crypto.subtle.digest} without
 * holding the whole file in memory. The file is cut into consecutive {@value #PIECE_BYTES}-byte
 * pieces (the last one shorter); the ID is the SHA-256 over the concatenation of the pieces'
 * SHA-256 digests, written with the file size as {@code sha256c8m:<size>:<hex>} (lower-case hex).
 * {@code web/src/contentid.ts} is the browser side.
 *
 * <p>Incremental: {@link #update} takes the bytes in any slices; {@link #value} doesn't disturb
 * the state, so more bytes may follow. Not thread-safe.
 */
public final class ContentId {
    /** The piece size: 8 MiB. */
    public static final int PIECE_BYTES = 8 * 1024 * 1024;
    public static final String PREFIX = "sha256c8m:";
    private static final Pattern FORMAT = Pattern.compile("sha256c8m:[1-9][0-9]{0,17}:[0-9a-f]{64}");

    private MessageDigest outer = sha256();
    private MessageDigest piece = sha256();
    private int pieceFill;
    private long size;

    /** Adds bytes that follow the ones added so far. */
    public void update(byte[] b, int off, int len) {
        while (len > 0) {
            int n = Math.min(len, PIECE_BYTES - pieceFill);
            piece.update(b, off, n);
            pieceFill += n;
            size += n;
            off += n;
            len -= n;
            if (pieceFill == PIECE_BYTES) {
                outer.update(piece.digest());
                pieceFill = 0;
            }
        }
    }

    /** Adds the buffer's remaining bytes (its position moves to its limit). */
    public void update(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            int n = Math.min(buffer.remaining(), PIECE_BYTES - pieceFill);
            ByteBuffer slice = buffer.slice(buffer.position(), n);
            piece.update(slice);
            buffer.position(buffer.position() + n);
            pieceFill += n;
            size += n;
            if (pieceFill == PIECE_BYTES) {
                outer.update(piece.digest());
                pieceFill = 0;
            }
        }
    }

    /** The bytes added so far. */
    public long size() {
        return size;
    }

    /** The ID of the bytes added so far; {@code null} for none (an empty file has no ID). */
    public String value() {
        if (size == 0) {
            return null;
        }
        MessageDigest o = clone(outer);
        if (pieceFill > 0) {
            o.update(clone(piece).digest());
        }
        return PREFIX + size + ":" + HexFormat.of().formatHex(o.digest());
    }

    /** Starts over. */
    public void reset() {
        outer = sha256();
        piece = sha256();
        pieceFill = 0;
        size = 0;
    }

    /** The ID of a whole stream ({@code null} if it is empty). */
    public static String of(InputStream in) throws IOException {
        ContentId id = new ContentId();
        byte[] buffer = new byte[256 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            id.update(buffer, 0, n);
        }
        return id.value();
    }

    /** The ID of a file ({@code null} if it is empty). */
    public static String of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return of(in);
        }
    }

    /** Whether {@code s} is a well-formed content ID. */
    public static boolean isValid(String s) {
        return s != null && s.length() <= 120 && FORMAT.matcher(s).matches();
    }

    /** The size a well-formed content ID names. */
    public static long sizeOf(String contentId) {
        if (!isValid(contentId)) {
            throw new IllegalArgumentException("Not a content ID");
        }
        int a = PREFIX.length();
        return Long.parseLong(contentId.substring(a, contentId.indexOf(':', a)));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest clone(MessageDigest digest) {
        try {
            return (MessageDigest) digest.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }
}
