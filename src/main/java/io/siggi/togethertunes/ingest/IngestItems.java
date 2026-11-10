package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
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
     * @param encodePreset  the x264 preset its files were made with ({@code encode_preset}), or null
     * @param encodeQuality whether its files are the quality encode ({@code encode_quality})
     * @param mediaVersion  the current version of its files ({@code media_version}, {@link MediaVersions})
     */
    public record Item(String id, String kind, String title, String artist, String album, String source,
                       String youtubeId, String status, String outputProfile, String originalFile,
                       Double loudnessLufs, String encodePreset, boolean encodeQuality, int mediaVersion) {
        /** Without a media version (version 0, the flat layout). */
        public Item(String id, String kind, String title, String artist, String album, String source,
                    String youtubeId, String status, String outputProfile, String originalFile,
                    Double loudnessLufs, String encodePreset, boolean encodeQuality) {
            this(id, kind, title, artist, album, source, youtubeId, status, outputProfile, originalFile,
                    loudnessLufs, encodePreset, encodeQuality, 0);
        }

        public boolean fromYoutube() {
            return "youtube".equals(source);
        }
    }

    public Optional<Item> get(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, title, artist, album, source, youtube_id,"
                    + " status, output_profile, original_file, loudness_lufs, encode_preset, encode_quality,"
                    + " media_version FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    double lufs = rs.getDouble(11);
                    Double loudness = rs.wasNull() ? null : lufs;
                    return Optional.of(new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), loudness, rs.getString(12), rs.getInt(13) != 0, rs.getInt(14)));
                }
            }
        });
    }

    /**
     * Whether a room wants the item now: it is queued, suggested or playing in some room
     * ({@code queue_entries}). Such an item is encoded with the quick preset.
     */
    public boolean wantedInRoom(String id) throws SQLException {
        return exists("SELECT 1 FROM queue_entries WHERE item_id = ? AND state IN ('queued', 'suggested', 'playing')", id);
    }

    /** Whether the item is in at least one user's library. */
    public boolean inLibrary(String id) throws SQLException {
        return exists("SELECT 1 FROM library_entries WHERE item_id = ?", id);
    }

    private boolean exists(String sql, String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Marks the item's files as its quality encode without re-encoding: a {@code quality} job
     * found them made with the quality preset already.
     */
    public void markQuality(String id) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET encode_quality = 1 WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Records what the publish step produced: the item's kind, the duration of its
     * {@code audio.m4a}, the encoding and x264 preset it was made with (and whether that is its
     * quality encode), the source's integrated loudness
     * (null if it couldn't be measured, e.g. digital silence) and the kept original (null if none,
     * i.e. re-encoded from the previous encode; the {@code original_*} columns are then cleared). A purged
     * item that was fetched again is no longer purged. The status itself becomes {@code ready}
     * when the queue marks the job done. These overloads leave {@code media_version} as it is.
     */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, Double loudnessLufs,
                          Originals.Original original) throws SQLException {
        published(id, video, durationMs, encoding, X264Preset.REFERENCE, false, loudnessLufs, original);
    }

    /** See {@link #published(String, boolean, long, OutputEncoding, Double, Originals.Original)}. */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, X264Preset preset,
                          boolean quality, Double loudnessLufs, Originals.Original original) throws SQLException {
        published(id, video, durationMs, encoding, preset, quality, loudnessLufs, original, -1);
    }

    /**
     * See {@link #published(String, boolean, long, OutputEncoding, Double, Originals.Original)};
     * also switches the item to {@code mediaVersion} (its files' new {@code v<N>} directory) in the
     * same update, so the new files become current in one step. A negative version leaves
     * {@code media_version} as it is.
     */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, X264Preset preset,
                          boolean quality, Double loudnessLufs, Originals.Original original, int mediaVersion)
            throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = ?, duration_ms = ?,"
                    + " output_profile = ?, loudness_lufs = ?, original_file = ?, original_codec = ?,"
                    + " original_web = ?, original_gain_db = ?, encode_preset = ?, encode_quality = ?,"
                    + (mediaVersion >= 0 ? " media_version = " + mediaVersion + "," : "")
                    + " purged_at = NULL WHERE id = ?")) {
                ps.setString(1, video ? "video" : "audio");
                ps.setLong(2, durationMs);
                ps.setString(3, encoding.id());
                setDouble(ps, 4, loudnessLufs);
                ps.setString(5, original == null ? null : original.file());
                ps.setString(6, original == null ? null : original.codec());
                ps.setInt(7, original != null && original.web() ? 1 : 0);
                setDouble(ps, 8, original == null ? null : original.gainDb());
                ps.setString(9, preset.id());
                ps.setInt(10, quality ? 1 : 0);
                ps.setString(11, id);
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
