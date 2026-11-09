package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

/**
 * The few {@code items} columns the ingest pipeline reads and writes. The library's item
 * repository (a later step) owns everything else; the status column is driven by
 * {@link IngestJobs}.
 */
public final class IngestItems {
    private final Database db;

    public IngestItems(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    /**
     * What a job needs to know about its item.
     *
     * @param kind      {@code audio} | {@code video} (as stored; the probe decides it anew)
     * @param source    {@code upload} | {@code youtube}
     * @param youtubeId the video ID for YouTube items, else null
     */
    public record Item(String id, String kind, String title, String artist, String album, String source,
                       String youtubeId, String status) {
    }

    public Optional<Item> get(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, title, artist, album, source, youtube_id,"
                    + " status FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
                }
            }
        });
    }

    /**
     * Records what the publish step produced: the item's kind, the duration of its
     * {@code audio.m4a}, the profile it was encoded for and the source's integrated loudness
     * (null if it couldn't be measured, e.g. digital silence). A purged item that was fetched
     * again is no longer purged. The status itself becomes {@code ready} when the queue marks the
     * job done.
     */
    public void published(String id, boolean video, long durationMs, OutputProfile profile, Double loudnessLufs)
            throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = ?, duration_ms = ?,"
                    + " output_profile = ?, loudness_lufs = ?, purged_at = NULL WHERE id = ?")) {
                ps.setString(1, video ? "video" : "audio");
                ps.setLong(2, durationMs);
                ps.setString(3, profile.id());
                if (loudnessLufs == null) {
                    ps.setNull(4, Types.REAL);
                } else {
                    ps.setDouble(4, loudnessLufs);
                }
                ps.setString(5, id);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("item " + id + " no longer exists");
                }
            }
            return null;
        });
    }
}
