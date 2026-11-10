package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.ingest.IngestFailure;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.MediaProbe;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.retention.References;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Chunked, resumable uploads ({@code docs/library-and-ingest.md#upload}): the {@code uploads}
 * table, the bytes in {@code <data>/tmp/uploads/<id>.part}, and {@code complete}, which turns a
 * finished upload into an item (or finds the item it duplicates).
 *
 * <p><b>Chunks</b> are appended in order: a chunk's {@code Content-Range} may start anywhere up
 * to the bytes received so far. Bytes the server already has are read and dropped, so repeating a
 * chunk (e.g. after a lost response) is harmless; a chunk that starts past the received bytes is
 * refused with {@code 409 upload_offset_mismatch}. The received count is the size of the part
 * file, which survives a restart; a chunk that breaks off keeps the bytes that arrived. The
 * <b>SHA-256</b> is updated as the bytes are written; after a restart (or a write error) it is
 * computed again from the part file once. Only one request works on an upload at a time; a
 * concurrent one gets {@code 409 upload_busy}.
 *
 * <p><b>Complete</b> hashes the file and looks for an upload item with that
 * {@code content_sha256}:
 * <ul>
 *   <li>ready, pending or processing → reused ({@code deduplicated: true}); the upload is
 *       discarded;</li>
 *   <li>purged, or failed → ingested again into the same row ({@code deduplicated: true}, since
 *       no new item is made; title and artist stay as they are). A purged upload can only come
 *       back this way ({@code docs/libraries.md#retention});</li>
 *   <li>none → ffprobe checks the file (unreadable → {@code 422 unreadable_media}, no audio →
 *       {@code 422 no_audio}, longer than {@code ingest.maxDurationSeconds} →
 *       {@code 422 too_long}; a rejected upload is deleted), and a new {@code pending} item is
 *       created with title/artist/album from the request, the tags or the file name
 *       ({@link UploadMetadata}) and its kind from the probe.</li>
 * </ul>
 * For a new item or a re-ingest the file is moved to the item's staging directory
 * ({@link IngestPipeline#stagingDir}) as {@code source.<ext>} (the upload's own extension if it is
 * plain), where the {@code upload} job picks it up; the pipeline takes the cover from the embedded
 * picture. The item row, its job, the uploader's library entry ({@code via='upload'}, unless
 * {@code addToLibrary} is false; users only) and the upload's result are written in one
 * transaction; {@code last_referenced_at} becomes now. The upload row keeps the result, so a
 * repeated {@code complete} answers the same. Concurrent completes are serialised around the
 * lookup, so two uploads of the same file make one item.
 *
 * <p><b>Cleanup</b>: uploads untouched for {@link #STALE_AFTER} (finished or not) are deleted with
 * their part file by {@link #sweep}, which runs at startup and at most every
 * {@link #SWEEP_EVERY} when an upload is created; part files without a row go too.
 */
public final class Uploads {
    /** The chunk size suggested to clients. */
    public static final long CHUNK_SIZE = 8L * 1024 * 1024;
    /** Uploads not touched for this long are deleted. */
    public static final Duration STALE_AFTER = Duration.ofHours(24);
    static final Duration SWEEP_EVERY = Duration.ofMinutes(10);
    /** Longest file name stored, in characters. */
    static final int MAX_FILENAME_CHARS = 255;
    static final String PART_SUFFIX = ".part";

    private static final Pattern CONTENT_RANGE =
            Pattern.compile("(?i)bytes\\s+(\\d{1,19})-(\\d{1,19})/(\\d{1,19}|\\*)");

    private final Database db;
    private final DataDir dataDir;
    private final Tools tools;
    private final IngestJobs jobs;
    private final Runnable wake;
    private final OutputEncoding profile;
    private final long maxBytes;
    private final long maxDurationUs;
    private final Clock clock;
    private final GuestUploadQuota guestQuota;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** Held around the content-hash lookup and the item insert. */
    private final ReentrantLock completeLock = new ReentrantLock();
    private volatile long nextSweepAt;

    /**
     * An {@code uploads} row.
     *
     * @param itemId       the item {@code complete} produced, or null while unfinished
     * @param deduplicated whether {@code complete} reused an existing item
     */
    public record Upload(String id, String userId, String guestId, String filename, long size, long createdAt,
                         long updatedAt, String itemId, boolean deduplicated) {
        public boolean completed() {
            return itemId != null;
        }
    }

    /** What {@code complete} produced. */
    public record Completed(String itemId, boolean deduplicated) {
    }

    /** A chunk's byte range, from {@code Content-Range: bytes start-end/total} (end inclusive). */
    public record ChunkRange(long start, long end) {
        public long length() {
            return end - start + 1;
        }
    }

    /** In-memory state of an upload in progress: who is writing, and the running hash. */
    private static final class Session {
        final ReentrantLock lock = new ReentrantLock();
        MessageDigest digest;
        long hashed;
    }

    /**
     * @param wake           called after a job was queued (the ingest queue's {@code wake})
     * @param profile        the output encoding new items are recorded with
     * @param maxBytes       {@code ingest.maxUploadBytes}
     * @param maxDurationSec {@code ingest.maxDurationSeconds}
     * @param guestQuota     {@code guests.uploadQuota}, applied to uploads guests start
     */
    public Uploads(Database db, DataDir dataDir, Tools tools, IngestJobs jobs, Runnable wake, OutputEncoding profile,
                   long maxBytes, int maxDurationSec, GuestUploadQuota guestQuota, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.tools = Objects.requireNonNull(tools);
        this.jobs = Objects.requireNonNull(jobs);
        this.wake = Objects.requireNonNull(wake);
        this.profile = Objects.requireNonNull(profile);
        this.maxBytes = maxBytes;
        this.maxDurationUs = maxDurationSec * 1_000_000L;
        this.guestQuota = Objects.requireNonNull(guestQuota);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The guest upload quota. */
    public GuestUploadQuota guestQuota() {
        return guestQuota;
    }

    /** {@code <data>/tmp/uploads}. */
    public Path dir() {
        return dataDir.tmp().resolve("uploads");
    }

    Path partFile(String uploadId) {
        if (!UuidV7.isValid(uploadId)) {
            throw new IllegalArgumentException("Not an upload id: " + uploadId);
        }
        return dir().resolve(uploadId + PART_SUFFIX);
    }

    // ------------------------------------------------------------------ create / read

    /**
     * Checks the file name and size of a new upload, as {@link #create} does.
     *
     * @return the file name as it is stored
     * @throws HttpError {@code 400 invalid_filename} / {@code 400 invalid_size}, or
     *                   {@code 413 payload_too_large} above {@code ingest.maxUploadBytes}
     */
    public String validate(String filename, long size) {
        String name = filename == null ? null : UploadMetadata.clean(filename);
        if (name == null) {
            throw new HttpError(400, "invalid_filename", "filename is required");
        }
        if (name.length() > MAX_FILENAME_CHARS) {
            int end = name.offsetByCodePoints(0, name.codePointCount(0, MAX_FILENAME_CHARS));
            name = name.substring(0, end);
        }
        if (size < 1) {
            throw new HttpError(400, "invalid_size", "size must be at least 1 byte");
        }
        if (size > maxBytes) {
            throw HttpError.payloadTooLarge("The file is larger than the upload limit (" + maxBytes + " bytes)");
        }
        return name;
    }

    /** {@link #create(Actor, String, long, String, String)} for a user, outside a room. */
    public Upload create(Actor owner, String filename, long size) throws SQLException, IOException {
        return create(owner, filename, size, null, null);
    }

    /**
     * Starts an upload of {@code size} bytes for {@code owner} (a user or a guest). Who may do so
     * is {@link AddMediaPolicy}'s business; a guest's upload also counts against the guest upload
     * quota ({@link GuestUploadQuota}), checked and recorded in the same transaction as the row.
     *
     * @param roomId   the room the upload is a request in, or null
     * @param clientIp the request's IP, for a guest's per-IP quota (never stored)
     * @throws HttpError {@code 400 invalid_filename} / {@code 400 invalid_size}, or
     *                   {@code 413 payload_too_large} above {@code ingest.maxUploadBytes} (or, for
     *                   a guest, the quota's per-item size), {@code 429 upload_quota_exceeded}
     */
    public Upload create(Actor owner, String filename, long size, String roomId, String clientIp)
            throws SQLException, IOException {
        String userId = userId(owner);
        String guestId = guestId(owner);
        String name = validate(filename, size);
        maybeSweep();
        String id = UuidV7.newId();
        long now = EpochMs.now(clock);
        Files.createDirectories(dir());
        Files.newByteChannel(partFile(id), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
        String finalName = name;
        if (guestId != null) {
            guestQuota.lock().lock();
        }
        try {
            db.write(c -> {
                if (guestId != null) {
                    guestQuota.reserve(c, guestId, clientIp, size, now);
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO uploads (id, user_id, guest_id, room_id,"
                        + " filename, size, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, userId);
                    ps.setString(3, guestId);
                    ps.setString(4, roomId);
                    ps.setString(5, finalName);
                    ps.setLong(6, size);
                    ps.setLong(7, now);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            Files.deleteIfExists(partFile(id));
            throw e;
        } finally {
            if (guestId != null) {
                guestQuota.lock().unlock();
            }
        }
        return new Upload(id, userId, guestId, finalName, size, now, now, null, false);
    }

    /** {@code owner}'s upload {@code id}; {@code 404 upload_not_found} for anyone else's or an unknown id. */
    public Upload get(Actor owner, String id) throws SQLException {
        String userId = userId(owner);
        String guestId = guestId(owner);
        if (!UuidV7.isValid(id)) {
            throw notFound();
        }
        Upload upload = db.read(c -> find(c, id));
        if (upload == null || !(Objects.equals(upload.userId(), userId) && Objects.equals(upload.guestId(), guestId))) {
            throw notFound();
        }
        return upload;
    }

    private static Upload find(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, user_id, guest_id, filename, size, created_at,"
                + " updated_at, item_id, deduplicated FROM uploads WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Upload(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getInt(9) != 0);
            }
        }
    }

    /** The bytes received so far: the part file's size, or the full size once completed. */
    public long received(Upload upload) throws IOException {
        if (upload.completed()) {
            return upload.size();
        }
        try {
            return Files.size(partFile(upload.id()));
        } catch (NoSuchFileException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ chunks

    /**
     * Parses {@code Content-Range: bytes start-end/total} for an upload of {@code size} bytes.
     *
     * @throws HttpError {@code 400 invalid_content_range} if it is missing or malformed, the total
     *                   isn't {@code size} (or {@code *}), or the range doesn't fit in the file
     */
    public static ChunkRange parseRange(String header, long size) {
        if (header == null) {
            throw invalidRange("Content-Range is required (bytes start-end/total)");
        }
        Matcher m = CONTENT_RANGE.matcher(header.strip());
        if (!m.matches()) {
            throw invalidRange("Content-Range must be bytes start-end/total");
        }
        long start;
        long end;
        try {
            start = Long.parseLong(m.group(1));
            end = Long.parseLong(m.group(2));
            if (!m.group(3).equals("*") && Long.parseLong(m.group(3)) != size) {
                throw invalidRange("Content-Range total must be the upload's size, " + size);
            }
        } catch (NumberFormatException e) {
            throw invalidRange("Content-Range numbers are out of range");
        }
        if (start > end || end >= size) {
            throw invalidRange("Content-Range " + start + "-" + end + " doesn't fit an upload of " + size + " bytes");
        }
        return new ChunkRange(start, end);
    }

    private static HttpError invalidRange(String message) {
        return new HttpError(400, "invalid_content_range", message);
    }

    /**
     * Writes one chunk (the request body, its range from {@code Content-Range}).
     *
     * @return the bytes received after it
     * @throws HttpError {@code 409 upload_offset_mismatch} if the chunk starts after the received
     *                   bytes, {@code 409 upload_completed}, {@code 409 upload_busy},
     *                   {@code 400 invalid_content_range}, {@code 400 chunk_length_mismatch} if the
     *                   body is shorter than the range (the bytes that came are kept), or the body
     *                   errors of {@link Request#copyBody}
     */
    public long writeChunk(Actor owner, String id, Request request) throws SQLException, IOException {
        Upload upload = get(owner, id);
        if (upload.completed()) {
            throw completedError();
        }
        ChunkRange range = parseRange(request.header("Content-Range"), upload.size());
        Session session = sessions.computeIfAbsent(upload.id(), k -> new Session());
        if (!session.lock.tryLock()) {
            throw busy();
        }
        try {
            Path part = partFile(upload.id());
            Files.createDirectories(dir());
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.READ)) {
                long received = channel.size();
                if (range.start() > received) {
                    throw new HttpError(409, "upload_offset_mismatch",
                            "The server has " + received + " bytes; send the chunk starting there");
                }
                ensureDigest(session, channel, received);
                long skip = received - range.start();
                ChunkSink sink = new ChunkSink(channel, received, skip, session);
                long total = request.copyBody(sink, range.length());
                if (total != range.length()) {
                    throw new HttpError(400, "chunk_length_mismatch", "The body has " + total
                            + " bytes but Content-Range names " + range.length());
                }
                touch(upload.id());
                return channel.size();
            }
        } finally {
            session.lock.unlock();
        }
    }

    /**
     * Makes {@code session}'s digest cover exactly the first {@code length} bytes of the file:
     * kept as is when it already does, else computed again from the file.
     */
    private static void ensureDigest(Session session, FileChannel channel, long length) throws IOException {
        if (session.digest != null && session.hashed == length) {
            return;
        }
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        long position = 0;
        while (position < length) {
            buffer.clear();
            if (length - position < buffer.capacity()) {
                buffer.limit((int) (length - position));
            }
            int n = channel.read(buffer, position);
            if (n < 0) {
                throw new IOException("The upload's file is shorter than expected");
            }
            buffer.flip();
            digest.update(buffer);
            position += n;
        }
        session.digest = digest;
        session.hashed = length;
    }

    /**
     * Drops the first {@code skip} bytes (already received), then appends at {@code position}
     * and updates the session's digest with exactly what reached the file.
     */
    private static final class ChunkSink extends OutputStream {
        private final FileChannel channel;
        private final Session session;
        private long position;
        private long skip;

        ChunkSink(FileChannel channel, long position, long skip, Session session) {
            this.channel = channel;
            this.position = position;
            this.skip = skip;
            this.session = session;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (skip > 0) {
                int s = (int) Math.min(skip, len);
                skip -= s;
                off += s;
                len -= s;
            }
            if (len == 0) {
                return;
            }
            // The digest no longer matches the file until this write has fully succeeded.
            session.hashed = -1;
            ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
            session.digest.update(b, off, len);
            session.hashed = position;
        }
    }

    private void touch(String id) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE uploads SET updated_at = ? WHERE id = ?")) {
                ps.setLong(1, EpochMs.now(clock));
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ complete

    /**
     * Finishes an upload (see the class comment).
     *
     * @param title        the uploader's title, or null for the tags/file name
     * @param artist       the uploader's artist, or null for the tags
     * @param addToLibrary whether a user's library gets the item ({@code null} = yes)
     * @throws HttpError {@code 409 upload_incomplete}, {@code 409 upload_busy}, {@code 422
     *                   unreadable_media}, {@code 422 no_audio}, {@code 422 too_long},
     *                   {@code 503 ingest_unavailable} (no ffprobe)
     */
    public Completed complete(Actor owner, String id, String title, String artist, Boolean addToLibrary)
            throws SQLException, IOException, InterruptedException {
        Upload upload = get(owner, id);
        if (upload.completed()) {
            return new Completed(upload.itemId(), upload.deduplicated());
        }
        Session session = sessions.computeIfAbsent(upload.id(), k -> new Session());
        if (!session.lock.tryLock()) {
            throw busy();
        }
        try {
            // Another request may have completed it while we waited for nothing (tryLock); look again.
            Upload current = get(owner, id);
            if (current.completed()) {
                return new Completed(current.itemId(), current.deduplicated());
            }
            Path part = partFile(upload.id());
            String sha256;
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.READ)) {
                long received = channel.size();
                if (received != upload.size()) {
                    throw new HttpError(409, "upload_incomplete", "The server has " + received + " of "
                            + upload.size() + " bytes");
                }
                ensureDigest(session, channel, received);
                sha256 = HexFormat.of().formatHex(clone(session.digest).digest());
            } catch (NoSuchFileException e) {
                throw new HttpError(409, "upload_incomplete", "The server has 0 of " + upload.size() + " bytes");
            }
            boolean library = owner instanceof Actor.User && !Boolean.FALSE.equals(addToLibrary);

            Completed done = dedupe(upload, sha256, library);
            if (done != null) {
                return done;
            }
            MediaProbe probe = probe(upload, part);
            completeLock.lock();
            try {
                Existing existing = db.read(c -> existing(c, sha256));
                if (existing != null && existing.reusable()) {
                    return reuse(upload, existing.id(), library);
                }
                return ingest(upload, part, probe, sha256, existing == null ? null : existing.id(), title, artist,
                        library);
            } finally {
                completeLock.unlock();
            }
        } finally {
            session.lock.unlock();
            sessions.remove(upload.id(), session);
        }
    }

    /** The reusable item with this hash, recorded as the upload's result; null if there is none. */
    private Completed dedupe(Upload upload, String sha256, boolean library) throws SQLException, IOException {
        completeLock.lock();
        try {
            Existing existing = db.read(c -> existing(c, sha256));
            return existing != null && existing.reusable() ? reuse(upload, existing.id(), library) : null;
        } finally {
            completeLock.unlock();
        }
    }

    /** An upload item with a given content hash. */
    private record Existing(String id, String status) {
        /** Ready or on its way there: use it. Purged or failed: ingest the new copy into it. */
        boolean reusable() {
            return status.equals("ready") || status.equals("pending") || status.equals("processing");
        }
    }

    private static Existing existing(Connection c, String sha256) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, status FROM items WHERE content_sha256 = ?"
                + " AND source = 'upload' ORDER BY added_at, id")) {
            ps.setString(1, sha256);
            Existing purged = null;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Existing e = new Existing(rs.getString(1), rs.getString(2));
                    if (e.reusable()) {
                        return e;
                    }
                    if (purged == null) {
                        purged = e;
                    }
                }
            }
            return purged;
        }
    }

    private Completed reuse(Upload upload, String itemId, boolean library) throws SQLException, IOException {
        long now = EpochMs.now(clock);
        db.write(c -> {
            References.touch(c, itemId, now);
            if (library) {
                addToLibrary(c, upload.userId(), itemId, now);
            }
            finish(c, upload.id(), itemId, true, now);
            return null;
        });
        Files.deleteIfExists(partFile(upload.id()));
        return new Completed(itemId, true);
    }

    /** ffprobe's view of the upload; a file that can't become an item is deleted and refused. */
    private MediaProbe probe(Upload upload, Path part) throws SQLException, IOException, InterruptedException {
        if (!tools.hasFfprobe()) {
            throw unavailable();
        }
        MediaProbe probe;
        try {
            probe = MediaProbe.probe(tools, part, null);
        } catch (ToolException e) {
            if (e.kind() == ToolException.Kind.NOT_AVAILABLE || e.kind() == ToolException.Kind.START_FAILED) {
                throw unavailable();
            }
            discard(upload);
            throw new HttpError(422, "unreadable_media", "The file can't be read as audio or video");
        } catch (IngestFailure e) {
            discard(upload);
            throw new HttpError(422, "unreadable_media", e.getMessage());
        }
        if (probe.audio() == null) {
            discard(upload);
            throw new HttpError(422, "no_audio", "The file has no audio");
        }
        try {
            IngestPipeline.checkSource(probe, maxDurationUs);
        } catch (IngestFailure e) {
            discard(upload);
            throw new HttpError(422, "too_long", e.getMessage());
        }
        return probe;
    }

    private static HttpError unavailable() {
        return new HttpError(503, "ingest_unavailable", "Uploads can't be processed: ffprobe isn't available on this server");
    }

    /**
     * Moves the upload into the item's staging directory and writes the item (new, or the
     * purged/failed one with this hash), its {@code upload} job, the library entry and the
     * upload's result in one transaction. If the transaction fails, the file goes back.
     */
    private Completed ingest(Upload upload, Path part, MediaProbe probe, String sha256, String reuseItemId,
                             String title, String artist, boolean library) throws SQLException, IOException {
        String itemId = reuseItemId != null ? reuseItemId : UuidV7.newId();
        Path staging = IngestPipeline.stagingDir(dataDir, itemId);
        deleteTree(staging); // leftovers of a failed job
        Files.createDirectories(staging);
        String ext = UploadMetadata.extension(upload.filename());
        Path source = staging.resolve(ext == null ? IngestPipeline.SOURCE_NAME : IngestPipeline.SOURCE_NAME + "." + ext);
        move(part, source);
        UploadMetadata meta = UploadMetadata.of(probe, upload.filename(), title, artist);
        long now = EpochMs.now(clock);
        try {
            db.write(c -> {
                if (reuseItemId == null) {
                    insertItem(c, itemId, probe.isVideo() ? "video" : "audio", meta, sha256, upload, now);
                } else {
                    References.touch(c, itemId, now);
                }
                jobs.enqueue(itemId, IngestJob.Kind.UPLOAD);
                if (library) {
                    addToLibrary(c, upload.userId(), itemId, now);
                }
                finish(c, upload.id(), itemId, reuseItemId != null, now);
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            try {
                move(source, part);
                deleteTree(staging);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        wake.run();
        return new Completed(itemId, reuseItemId != null);
    }

    private void insertItem(Connection c, String itemId, String kind, UploadMetadata meta, String sha256,
                            Upload upload, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, album, source,"
                + " content_sha256, added_by_user_id, added_by_guest_id, added_at, status, output_profile,"
                + " last_referenced_at) VALUES (?, ?, ?, ?, ?, 'upload', ?, ?, ?, ?, 'pending', ?, ?)")) {
            ps.setString(1, itemId);
            ps.setString(2, kind);
            ps.setString(3, meta.title());
            ps.setString(4, meta.artist());
            ps.setString(5, meta.album());
            ps.setString(6, sha256);
            ps.setString(7, upload.userId());
            ps.setString(8, upload.guestId());
            ps.setLong(9, now);
            ps.setString(10, profile.id());
            ps.setLong(11, now);
            ps.executeUpdate();
        }
    }


    private void addToLibrary(Connection c, String userId, String itemId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                List.of("user_id", "item_id", "added_at", "via"), List.of("user_id", "item_id")))) {
            ps.setString(1, userId);
            ps.setString(2, itemId);
            ps.setLong(3, now);
            ps.setString(4, "upload");
            ps.executeUpdate();
        }
    }

    private static void finish(Connection c, String uploadId, String itemId, boolean deduplicated, long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE uploads SET item_id = ?, deduplicated = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, itemId);
            ps.setInt(2, deduplicated ? 1 : 0);
            ps.setLong(3, now);
            ps.setString(4, uploadId);
            ps.executeUpdate();
        }
    }

    /** Deletes a refused upload (row and file). */
    private void discard(Upload upload) throws SQLException, IOException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM uploads WHERE id = ?")) {
                ps.setString(1, upload.id());
                ps.executeUpdate();
            }
            return null;
        });
        Files.deleteIfExists(partFile(upload.id()));
    }

    // ------------------------------------------------------------------ cleanup

    private void maybeSweep() {
        long now = EpochMs.now(clock);
        if (now < nextSweepAt) {
            return;
        }
        nextSweepAt = now + SWEEP_EVERY.toMillis();
        try {
            sweep();
        } catch (SQLException | IOException | RuntimeException e) {
            System.getLogger(Uploads.class.getName()).log(System.Logger.Level.WARNING, "Upload sweep failed", e);
        }
    }

    /**
     * Deletes uploads not touched for {@link #STALE_AFTER} (with their part files) and part files
     * that have no row, and guest quota rows that no longer count ({@link GuestUploadQuota#sweep}). An upload a request is working on right now is skipped.
     *
     * @return the number of uploads and stray files deleted
     */
    public int sweep() throws SQLException, IOException {
        guestQuota.sweep();
        long cutoff = EpochMs.now(clock) - STALE_AFTER.toMillis();
        List<String> stale = db.read(c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM uploads WHERE updated_at < ?")) {
                ps.setLong(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            return ids;
        });
        int deleted = 0;
        for (String id : stale) {
            Session session = sessions.computeIfAbsent(id, k -> new Session());
            if (!session.lock.tryLock()) {
                continue;
            }
            try {
                int rows = db.write(c -> {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM uploads WHERE id = ? AND updated_at < ?")) {
                        ps.setString(1, id);
                        ps.setLong(2, cutoff);
                        return ps.executeUpdate();
                    }
                });
                if (rows > 0) {
                    Files.deleteIfExists(partFile(id));
                    deleted++;
                }
            } finally {
                session.lock.unlock();
                sessions.remove(id, session);
            }
        }
        if (Files.isDirectory(dir())) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir())) {
                stream.forEach(files::add);
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                String id = name.endsWith(PART_SUFFIX) ? name.substring(0, name.length() - PART_SUFFIX.length()) : null;
                boolean known = id != null && UuidV7.isValid(id) && db.read(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM uploads WHERE id = ?")) {
                        ps.setString(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next();
                        }
                    }
                });
                if (!known && Files.isRegularFile(f)) {
                    Files.deleteIfExists(f);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------ helpers

    private static String userId(Actor owner) {
        return owner instanceof Actor.User u ? u.userId() : null;
    }

    private static String guestId(Actor owner) {
        if (owner instanceof Actor.Guest g) {
            return g.guestId();
        }
        if (owner instanceof Actor.User) {
            return null;
        }
        throw new IllegalArgumentException("Uploads belong to a user or a guest");
    }

    private static HttpError notFound() {
        return new HttpError(404, "upload_not_found", "No such upload");
    }

    private static HttpError busy() {
        return new HttpError(409, "upload_busy", "Another request is working on this upload; try again");
    }

    private static HttpError completedError() {
        return new HttpError(409, "upload_completed", "This upload is already complete");
    }

    private static MessageDigest sha256() {
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

    /** SHA-256 of a whole stream, lower-case hex (as stored in {@code content_sha256}). */
    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
