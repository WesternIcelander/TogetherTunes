package io.siggi.togethertunes.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.storage.DiskSpace;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Uploaded library export zips ({@code docs/library-and-ingest.md#library-import}): a chunked,
 * resumable upload, a sibling of {@link Uploads} for imports. Everything lives in
 * {@code <data>/tmp/imports/} (not counted as media), named by the server:
 * <ul>
 *   <li>{@code <id>.json}: the upload's size and file name, while it is in progress;</li>
 *   <li>{@code <id>.part}: the bytes received so far (its size is the received count, so an upload
 *       resumes after a restart);</li>
 *   <li>{@code <id>.zip}: the completed zip, which {@link LibraryImports} reads in place as the
 *       source {@code {"uploadId"}}.</li>
 * </ul>
 * <b>Chunks</b> are numbered: chunk {@code n} is the bytes {@code [n * CHUNK_SIZE, min(size, (n + 1)
 * * CHUNK_SIZE))}. They are appended in order: a chunk may start anywhere up to the bytes received
 * so far (bytes already there are read and dropped, so repeating a chunk is harmless); one that
 * starts past them is refused with {@code 409 upload_offset_mismatch}. One request per upload at a
 * time ({@code 409 upload_busy}).
 *
 * <p><b>Space</b>: a new upload is refused up front ({@code 507}) when the free space minus its size
 * would fall below {@code storage.minFreeBytes}, and so is a chunk when the rest of the upload no
 * longer fits. The size is capped by {@code libraryImport.maxZipBytes} ({@code 413}).
 *
 * <p><b>Cleanup</b> ({@link #sweep}): an upload's files are deleted after {@link #UNUSED_AFTER}
 * without being touched (unless an import is checking or importing from it); at startup also every
 * completed zip that no import (checking, ready, importing or paused) uses. {@link LibraryImports}
 * deletes the zip when its import is done, failed or cancelled.
 */
public final class LibraryImportUploads {
    /** The chunk size (the same as media uploads'). */
    public static final long CHUNK_SIZE = Uploads.CHUNK_SIZE;
    /** Upload files not touched for this long are deleted. */
    public static final Duration UNUSED_AFTER = Duration.ofDays(7);
    static final String PART = ".part";
    static final String META = ".json";
    static final String ZIP = ".zip";

    private final LibraryImports imports;
    private final Path dir;
    private final LongSupplier maxZipBytes;
    private final Clock clock;
    private volatile Supplier<DiskSpace> diskSpace = DiskSpace::unlimited;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** An upload's state. */
    public record Status(String uploadId, long size, long receivedBytes, boolean complete) {
    }

    /**
     * @param imports     the imports that read the zips (and say which are in use)
     * @param maxZipBytes {@code libraryImport.maxZipBytes}
     */
    public LibraryImportUploads(LibraryImports imports, long maxZipBytes, Clock clock) {
        this.imports = Objects.requireNonNull(imports);
        this.dir = imports.importsDir();
        this.maxZipBytes = () -> maxZipBytes;
        this.clock = Objects.requireNonNull(clock);
    }

    /** The free-space check ({@code storage.minFreeBytes}). Unlimited until set. */
    public void diskSpace(DiskSpace space) {
        Objects.requireNonNull(space);
        this.diskSpace = () -> space;
    }

    /** {@code <data>/tmp/imports}. */
    public Path dir() {
        return dir;
    }

    private Path file(String id, String suffix) {
        return dir.resolve(id + suffix);
    }

    private static String requireId(String id) {
        if (id == null || !UuidV7.isValid(id)) {
            throw notFound();
        }
        return id;
    }

    private static HttpError notFound() {
        return new HttpError(404, "upload_not_found", "No such upload");
    }

    private ReentrantLock lock(String id) {
        ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new HttpError(409, "upload_busy", "Another request is working on this upload; try again");
        }
        return lock;
    }

    // ------------------------------------------------------------------ create / status

    /**
     * Starts an upload of a zip of {@code size} bytes.
     *
     * @throws HttpError {@code 400 invalid_filename} (not a {@code .zip}), {@code 400 invalid_size},
     *                   {@code 413 payload_too_large} over {@code libraryImport.maxZipBytes},
     *                   {@code 507 insufficient_storage}
     */
    public Status create(String filename, Long size) throws IOException {
        String name = filename == null ? null : UploadMetadata.clean(filename);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(ZIP)) {
            throw new HttpError(400, "invalid_filename", "Choose a .zip file");
        }
        if (name.length() > Uploads.MAX_FILENAME_CHARS) {
            name = name.substring(name.length() - Uploads.MAX_FILENAME_CHARS);
        }
        if (size == null || size < 1) {
            throw new HttpError(400, "invalid_size", "size must be at least 1 byte");
        }
        long limit = maxZipBytes.getAsLong();
        if (size > limit) {
            throw HttpError.payloadTooLarge("The zip file is larger than this server accepts (" + limit + " bytes)");
        }
        diskSpace.get().requireRoomFor(size);
        sweepQuietly();
        String id = UuidV7.newId();
        Files.createDirectories(dir);
        JsonObject meta = new JsonObject();
        meta.addProperty("size", size);
        meta.addProperty("filename", name);
        Files.createFile(file(id, PART));
        Files.writeString(file(id, META), meta.toString());
        FileTime now = FileTime.fromMillis(clock.millis());
        Files.setLastModifiedTime(file(id, PART), now);
        Files.setLastModifiedTime(file(id, META), now);
        return new Status(id, size, 0, false);
    }

    /** @throws HttpError {@code 404 upload_not_found} */
    public Status status(String uploadId) throws IOException {
        String id = requireId(uploadId);
        Path zip = file(id, ZIP);
        if (Files.isRegularFile(zip)) {
            long size = Files.size(zip);
            return new Status(id, size, size, true);
        }
        long size = size(id);
        long received;
        try {
            received = Files.size(file(id, PART));
        } catch (NoSuchFileException e) {
            throw notFound();
        }
        return new Status(id, size, received, false);
    }

    /** The declared size of an upload in progress. */
    private long size(String id) throws IOException {
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(file(id, META))).getAsJsonObject();
            return meta.get("size").getAsLong();
        } catch (NoSuchFileException e) {
            throw notFound();
        } catch (RuntimeException e) {
            throw new IOException("Unreadable upload record " + id, e);
        }
    }

    // ------------------------------------------------------------------ chunks

    /**
     * Writes chunk {@code n} from the request body.
     *
     * @return the upload's state afterwards
     * @throws HttpError {@code 404 upload_not_found}, {@code 409 upload_complete},
     *                   {@code 400 invalid_chunk}, {@code 409 upload_offset_mismatch},
     *                   {@code 400 chunk_length_mismatch}, {@code 409 upload_busy}, {@code 507}
     */
    public Status writeChunk(String uploadId, String n, Request request) throws IOException {
        String id = requireId(uploadId);
        long index;
        try {
            index = Long.parseLong(n);
        } catch (NumberFormatException e) {
            index = -1;
        }
        ReentrantLock lock = lock(id);
        try {
            if (Files.isRegularFile(file(id, ZIP))) {
                throw new HttpError(409, "upload_complete", "The upload is already complete");
            }
            long size = size(id);
            long chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;
            if (index < 0 || index >= chunks) {
                throw new HttpError(400, "invalid_chunk", "The chunk number must be 0 to " + (chunks - 1));
            }
            long start = index * CHUNK_SIZE;
            long length = Math.min(CHUNK_SIZE, size - start);
            Path part = file(id, PART);
            if (!Files.exists(part)) {
                throw notFound();
            }
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
                long received = channel.size();
                if (start > received) {
                    throw new HttpError(409, "upload_offset_mismatch", "The server has " + received
                            + " bytes; send chunk " + received / CHUNK_SIZE + " next");
                }
                if (start + length > received) {
                    diskSpace.get().requireRoomFor(size - received);
                }
                long total = request.copyBody(new Sink(channel, received, received - start), length);
                if (total != length) {
                    throw new HttpError(400, "chunk_length_mismatch", "Chunk " + index + " must be " + length
                            + " bytes, not " + total);
                }
                long now = clock.millis();
                Files.setLastModifiedTime(part, FileTime.fromMillis(now));
                return new Status(id, size, channel.size(), false);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Drops the first {@code skip} bytes (already received), then appends at {@code position}. */
    private static final class Sink extends OutputStream {
        private final FileChannel channel;
        private long position;
        private long skip;

        Sink(FileChannel channel, long position, long skip) {
            this.channel = channel;
            this.position = position;
            this.skip = skip;
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
            ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
        }
    }

    // ------------------------------------------------------------------ complete / delete

    /**
     * Completes an upload whose bytes have all arrived: it becomes {@code <id>.zip}, after its zip
     * directory has been read (a file that isn't a zip is deleted). Repeating it answers the same.
     *
     * @throws HttpError {@code 404 upload_not_found}, {@code 409 upload_incomplete},
     *                   {@code 400 invalid_zip}, {@code 409 upload_busy}
     */
    public Status complete(String uploadId) throws IOException {
        String id = requireId(uploadId);
        ReentrantLock lock = lock(id);
        try {
            Path zip = file(id, ZIP);
            if (Files.isRegularFile(zip)) {
                return status(id);
            }
            long size = size(id);
            Path part = file(id, PART);
            long received;
            try {
                received = Files.size(part);
            } catch (NoSuchFileException e) {
                throw notFound();
            }
            if (received != size) {
                throw new HttpError(409, "upload_incomplete", "The server has " + received + " of " + size + " bytes");
            }
            try (ZipFile z = new ZipFile(part.toFile())) {
                z.size(); // the central directory was read
            } catch (ZipException e) {
                Files.deleteIfExists(part);
                Files.deleteIfExists(file(id, META));
                throw new HttpError(400, "invalid_zip", "The file isn't a readable zip file");
            }
            try {
                Files.move(part, zip, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, zip);
            }
            Files.setLastModifiedTime(zip, FileTime.fromMillis(clock.millis()));
            Files.deleteIfExists(file(id, META));
            return new Status(id, size, size, true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes an upload (in progress or complete).
     *
     * @throws HttpError {@code 404 upload_not_found}, {@code 409 import_running} while an import
     *                   is checking or importing from it, {@code 409 upload_busy}
     */
    public void delete(String uploadId) throws IOException, SQLException {
        String id = requireId(uploadId);
        ReentrantLock lock = lock(id);
        try {
            if (!Files.exists(file(id, ZIP)) && !Files.exists(file(id, PART)) && !Files.exists(file(id, META))) {
                throw notFound();
            }
            String state = imports.uploadsInUse().get(id);
            if (state != null && !state.equals("ready")) {
                throw new HttpError(409, "import_running", "An import is reading this upload; cancel it first");
            }
            if (state != null) {
                imports.uploadGone(id, "The uploaded zip was deleted");
            }
            deleteFiles(id);
        } finally {
            lock.unlock();
        }
    }

    private void deleteFiles(String id) throws IOException {
        Files.deleteIfExists(file(id, PART));
        Files.deleteIfExists(file(id, META));
        Files.deleteIfExists(file(id, ZIP));
    }

    // ------------------------------------------------------------------ cleanup

    private volatile long nextSweepAt;

    private void sweepQuietly() {
        long now = clock.millis();
        if (now < nextSweepAt) {
            return;
        }
        nextSweepAt = now + Duration.ofMinutes(10).toMillis();
        try {
            sweep(false);
        } catch (IOException | SQLException e) {
            System.getLogger(LibraryImportUploads.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not clean up library import uploads", e);
        }
    }

    /**
     * Deletes upload files not touched for {@link #UNUSED_AFTER} (a zip an import is checking,
     * importing or paused on is kept; a {@code ready} import of a deleted zip fails), files with names
     * the server didn't make, and, at {@code startup}, every completed zip no import uses. An upload a
     * request is working on right now is skipped.
     *
     * @return the number of uploads (and stray files) deleted
     */
    public int sweep(boolean startup) throws IOException, SQLException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(files::add);
        }
        Map<String, String> inUse = imports.uploadsInUse();
        long cutoff = clock.millis() - UNUSED_AFTER.toMillis();
        int deleted = 0;
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                continue;
            }
            String name = f.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String id = dot < 0 ? null : name.substring(0, dot);
            String suffix = dot < 0 ? "" : name.substring(dot);
            if (id == null || !UuidV7.isValid(id) || !(suffix.equals(PART) || suffix.equals(META) || suffix.equals(ZIP))) {
                Files.deleteIfExists(f);
                deleted++;
                continue;
            }
            if (!done.add(id)) {
                continue;
            }
            ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
            if (!lock.tryLock()) {
                continue;
            }
            try {
                String state = inUse.get(id);
                boolean active = state != null && !state.equals("ready");
                if (active) {
                    continue;
                }
                long touched = lastTouched(id);
                boolean zip = Files.isRegularFile(file(id, ZIP));
                boolean stale = touched < cutoff;
                boolean orphan = startup && zip && state == null;
                if (stale || orphan) {
                    if (state != null) {
                        imports.uploadGone(id, "The uploaded zip was deleted after " + UNUSED_AFTER.toDays()
                                + " days unused");
                    }
                    deleteFiles(id);
                    deleted++;
                }
            } finally {
                lock.unlock();
                locks.remove(id, lock);
            }
        }
        return deleted;
    }

    /** The newest modification time of an upload's files. */
    private long lastTouched(String id) throws IOException {
        long t = Long.MIN_VALUE;
        for (String s : List.of(PART, META, ZIP)) {
            Path p = file(id, s);
            try {
                t = Math.max(t, Files.getLastModifiedTime(p).toMillis());
            } catch (NoSuchFileException e) {
                // not there
            }
        }
        return t;
    }
}
