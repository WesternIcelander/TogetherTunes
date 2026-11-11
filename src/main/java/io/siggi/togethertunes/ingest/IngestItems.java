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

    /** The edit settings (JSON) {@code version} of the item was made with, or null (none, or no row). */
    public String editJson(String id, int version) throws SQLException {
        return db.read(c -> MediaVersions.editJson(c, id, version));
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
        published(id, video, durationMs, encoding, preset, quality, loudnessLufs, original, mediaVersion, null);
    }

    /**
     * What a switch to a new media version keeps for undo ({@code docs/library-and-ingest.md#undo},
     * {@link MediaVersions}).
     *
     * @param keptVersion the version the item switches away from, kept for undo; null if there is
     *                    none (a first ingest, or its files are gone)
     * @param undoable    whether the switch is a person's change (an edit): only those show Undo;
     *                    a re-encode made by the system keeps the previous version without it
     * @param editJson    the edit settings the new version was made with (e.g. trims), as JSON, or null
     * @param atMs        when the switch happens (epoch ms)
     */
    public record VersionChange(Integer keptVersion, boolean undoable, String editJson, long atMs) {
    }

    /**
     * See {@link #published(String, boolean, long, OutputEncoding, X264Preset, boolean, Double, Originals.Original, int)};
     * with a non-negative {@code mediaVersion}, also records the new version's settings
     * ({@code media_versions}) and the kept previous one ({@code media_undo}, from {@code change};
     * null: nothing kept), in the same transaction.
     */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, X264Preset preset,
                          boolean quality, Double loudnessLufs, Originals.Original original, int mediaVersion,
                          VersionChange change) throws SQLException {
        published(id, video, durationMs, encoding, preset, quality, loudnessLufs, original, mediaVersion, change, null);
    }

    /**
     * See {@link #published(String, boolean, long, OutputEncoding, X264Preset, boolean, Double, Originals.Original, int, VersionChange)};
     * also records the length of a video item's audio-only file ({@code audio_only_ms},
     * {@code docs/library-and-ingest.md#audio-trim}; null: it has none, its audio-only file is
     * {@code audio.m4a}).
     */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, X264Preset preset,
                          boolean quality, Double loudnessLufs, Originals.Original original, int mediaVersion,
                          VersionChange change, Long audioOnlyMs) throws SQLException {
        published(id, video, durationMs, encoding, preset, quality, loudnessLufs, original, mediaVersion, change,
                audioOnlyMs, null);
    }

    /**
     * See {@link #published(String, boolean, long, OutputEncoding, X264Preset, boolean, Double, Originals.Original, int, VersionChange, Long)};
     * also records the card format of the new version's {@code card.mp4} ({@code card_version},
     * {@link CardEncoder#CARD_VERSION}) when it made a new card; null keeps the stored value (the
     * card was taken over from the previous version).
     */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, X264Preset preset,
                          boolean quality, Double loudnessLufs, Originals.Original original, int mediaVersion,
                          VersionChange change, Long audioOnlyMs, Integer cardVersion) throws SQLException {
        published(id, video, durationMs, encoding, preset, quality, loudnessLufs, original, mediaVersion, change,
                audioOnlyMs, cardVersion, null);
    }

    /**
     * See {@link #published(String, boolean, long, OutputEncoding, X264Preset, boolean, Double, Originals.Original, int, VersionChange, Long, Integer)};
     * with a new card ({@code cardVersion} not null) also records which fonts drew its text
     * ({@code card_fonts}, {@link io.siggi.togethertunes.fonts.CardFonts#key}; null: unknown).
     */
    public void published(String id, boolean video, long durationMs, OutputEncoding encoding, X264Preset preset,
                          boolean quality, Double loudnessLufs, Originals.Original original, int mediaVersion,
                          VersionChange change, Long audioOnlyMs, Integer cardVersion, String cardFonts)
            throws SQLException {
        db.write(c -> {
            if (mediaVersion >= 0 && change != null && change.keptVersion() != null) {
                MediaVersions.snapshotIfMissing(c, id, change.keptVersion(), change.atMs());
            }
            // An audio publish plays as audio (media type audio); a video publish keeps the type.
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = ?, duration_ms = ?,"
                    + (video ? "" : " media_type = 'audio', show_name = NULL, season_number = NULL, episode_number = NULL,")
                    + " output_profile = ?, loudness_lufs = ?, original_file = ?, original_codec = ?,"
                    + " original_web = ?, original_gain_db = ?, encode_preset = ?, encode_quality = ?, audio_only_ms = ?,"
                    + (mediaVersion >= 0 ? " media_version = " + mediaVersion + "," : "")
                    + (cardVersion != null ? " card_version = " + cardVersion.intValue() + ", card_fonts = ?," : "")
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
                if (audioOnlyMs == null) {
                    ps.setNull(11, Types.BIGINT);
                } else {
                    ps.setLong(11, audioOnlyMs);
                }
                int next = 12;
                if (cardVersion != null) {
                    ps.setString(next++, cardFonts);
                }
                ps.setString(next, id);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("item " + id + " no longer exists");
                }
            }
            if (mediaVersion >= 0) {
                long at = change != null ? change.atMs() : System.currentTimeMillis();
                MediaVersions.recordVersion(c, id, mediaVersion, at, change == null ? null : change.editJson());
                MediaVersions.recordKept(c, id, mediaVersion, change == null ? null : change.keptVersion(),
                        change != null && change.undoable(), at);
            }
            return null;
        });
    }

    /**
     * The card format of the item's current {@code card.mp4} ({@code card_version},
     * {@link CardEncoder#CARD_VERSION}), or 0 if the item doesn't exist.
     */
    public int cardVersion(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT card_version FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /**
     * Which fonts drew the item's current card ({@code card_fonts}, {@link io.siggi.togethertunes.fonts.CardFonts#key}),
     * or null if not recorded (or no such item).
     */
    public String cardFonts(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT card_fonts FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /**
     * Switches the item from version {@code from} to {@code mediaVersion}, which differs from it
     * only in its card (a card-only re-encode, {@code docs/library-and-ingest.md#cards}): records the
     * new card format and the new version like {@link #published} does, keeping {@code from} for the
     * undo window without Undo (a change made by the system).
     *
     * @throws SQLException if the item is gone or no longer at version {@code from}
     */
    public void cardRemade(String id, int from, int mediaVersion, int cardVersion, boolean keepFrom, long atMs)
            throws SQLException {
        cardRemade(id, from, mediaVersion, cardVersion, null, keepFrom, atMs);
    }

    /** {@link #cardRemade(String, int, int, int, boolean, long)}, recording the card's fonts ({@code card_fonts}). */
    public void cardRemade(String id, int from, int mediaVersion, int cardVersion, String cardFonts, boolean keepFrom,
                           long atMs) throws SQLException {
        db.write(c -> {
            if (keepFrom) {
                MediaVersions.snapshotIfMissing(c, id, from, atMs);
            }
            String editJson = MediaVersions.editJson(c, id, from);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE items SET media_version = ?, card_version = ?, card_fonts = ? WHERE id = ? AND media_version = ?")) {
                ps.setInt(1, mediaVersion);
                ps.setInt(2, cardVersion);
                ps.setString(3, cardFonts);
                ps.setString(4, id);
                ps.setInt(5, from);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("item " + id + " no longer exists or is no longer at version " + from);
                }
            }
            MediaVersions.recordVersion(c, id, mediaVersion, atMs, editJson);
            MediaVersions.recordKept(c, id, mediaVersion, keepFrom ? from : null, false, atMs);
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
