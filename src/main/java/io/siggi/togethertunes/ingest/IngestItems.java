package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
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
     * @param outputProfile the encoding (profile and bitrate id) its files were encoded for ({@code output_profile}), or null
     * @param originalFile  the kept original in the item directory ({@code original_file}), or null
     * @param loudnessLufs  the source's integrated loudness as measured at ingest, or null
     */
    public record Item(String id, String kind, String title, String artist, String album, String source,
                       String youtubeId, String status, String outputProfile, String originalFile,
                       Double loudnessLufs) {
        public boolean fromYoutube() {
            return "youtube".equals(source);
        }
    }

    public Optional<Item> get(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, title, artist, album, source, youtube_id,"
                    + " status, output_profile, original_file, loudness_lufs FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    double lufs = rs.getDouble(11);
                    Double loudness = rs.wasNull() ? null : lufs;
                    return Optional.of(new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), loudness));
                }
            }
        });
    }

    /**
     * Records what the publish step produced: the item's kind, the duration of its
     * {@code audio.m4a}, the encoding it was made for, the source's integrated loudness
     * (null if it couldn't be measured, e.g. digital silence) and the kept original (null if none,
     * i.e. re-encoded from the previous encode; the {@code original_*} columns are then cleared). A purged
     * item that was fetched again is no longer purged. The status itself becomes {@code ready}
     * when the queue marks the job done.
     */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, Double loudnessLufs,
                          Originals.Original original) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = ?, duration_ms = ?,"
                    + " output_profile = ?, loudness_lufs = ?, original_file = ?, original_codec = ?,"
                    + " original_web = ?, original_gain_db = ?, purged_at = NULL WHERE id = ?")) {
                ps.setString(1, video ? "video" : "audio");
                ps.setLong(2, durationMs);
                ps.setString(3, encoding.id());
                setDouble(ps, 4, loudnessLufs);
                ps.setString(5, original == null ? null : original.file());
                ps.setString(6, original == null ? null : original.codec());
                ps.setInt(7, original != null && original.web() ? 1 : 0);
                setDouble(ps, 8, original == null ? null : original.gainDb());
                ps.setString(9, id);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("item " + id + " no longer exists");
                }
            }
            return null;
        });
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }
}
