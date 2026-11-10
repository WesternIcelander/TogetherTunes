package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.ingest.IngestPipeline;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Backfills {@code items.content_id} ({@link ContentId}) for upload items from before content IDs:
 * each upload item that still has its kept original ({@code media/<itemId>/original.<ext>}, the
 * uploaded bytes) gets the ID of that file, so the pre-check finds it. The file's SHA-256 is
 * computed in the same pass and must match {@code content_sha256} (else the item is left alone).
 * Purged items (no file) keep only {@code content_sha256}.
 *
 * <p>Runs once at startup on a low-priority daemon thread ({@link #start}), one file at a time.
 */
public final class ContentIdBackfill {
    private static final System.Logger LOG = System.getLogger(ContentIdBackfill.class.getName());
    private static final Pattern ORIGINAL_FILE = Pattern.compile("original\\.[a-z0-9]{1,10}");
    private static final int BATCH = 50;

    private final Database db;
    private final DataDir dataDir;

    public ContentIdBackfill(Database db, DataDir dataDir) {
        this.db = Objects.requireNonNull(db);
        this.dataDir = Objects.requireNonNull(dataDir);
    }

    /** Starts {@link #run} on a daemon thread of minimum priority. */
    public Thread start() {
        Thread t = new Thread(() -> {
            try {
                int n = run();
                if (n > 0) {
                    LOG.log(System.Logger.Level.INFO, "Content IDs recorded for " + n + " uploaded items");
                }
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Content ID backfill stopped", e);
            }
        }, "content-id-backfill");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return t;
    }

    private record Row(String id, String originalFile, String sha256) {
    }

    /**
     * Records the content ID of every upload item that lacks one and has its original.
     *
     * @return the number of items updated
     */
    public int run() throws SQLException {
        int updated = 0;
        String after = "";
        for (;;) {
            String cursor = after;
            List<Row> rows = db.read(c -> {
                List<Row> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id, original_file, content_sha256 FROM items"
                        + " WHERE source = 'upload' AND content_id IS NULL AND original_file IS NOT NULL"
                        + " AND status <> 'purged' AND id > ? ORDER BY id LIMIT " + BATCH)) {
                    ps.setString(1, cursor);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3)));
                        }
                    }
                }
                return out;
            });
            if (rows.isEmpty()) {
                return updated;
            }
            for (Row row : rows) {
                after = row.id();
                if (Thread.currentThread().isInterrupted()) {
                    return updated;
                }
                if (backfill(row)) {
                    updated++;
                }
            }
        }
    }

    private boolean backfill(Row row) throws SQLException {
        if (!ORIGINAL_FILE.matcher(row.originalFile()).matches()) {
            return false;
        }
        Path file = IngestPipeline.itemDir(dataDir, row.id()).resolve(row.originalFile());
        String contentId;
        String sha256;
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = ContentId.sha256();
            ContentId id = new ContentId();
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
                id.update(buffer, 0, n);
            }
            contentId = id.value();
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the original of item " + row.id() + ": " + e);
            return false;
        }
        if (contentId == null || (row.sha256() != null && !row.sha256().equals(sha256))) {
            return false;
        }
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE items SET content_id = ? WHERE id = ? AND content_id IS NULL AND status <> 'purged'")) {
                ps.setString(1, contentId);
                ps.setString(2, row.id());
                return ps.executeUpdate() > 0;
            }
        });
    }
}
