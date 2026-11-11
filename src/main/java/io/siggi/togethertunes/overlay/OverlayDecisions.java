package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.library.MediaType;
import io.siggi.togethertunes.rooms.RoomSettings;
import io.siggi.togethertunes.settings.Settings;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Whether the QR overlay may be used for a queue entry ({@code docs/overlay.md#permissions}), decided
 * once per entry and recorded in {@code queue_entries.overlay}:
 *
 * <ul>
 *   <li>the server's {@code overlay.enabled} is on AND the room's {@code overlay.mode} is
 *       {@code always} ({@code docs/media-types.md#qr-overlay-modes}) AND the item isn't a
 *       {@code movie} or {@code show}: the overlay is on the entry's cards ({@link Decision#CARDS});
 *       {@code idleOnly} puts it on the queue-empty card only, which is no entry's
 *       ({@link OverlayCards}), so its entries get {@link Decision#OFF};</li>
 *   <li>and also the room owner holds {@link Permission#OUTPUT_OVERLAY_VIDEO} through one of their
 *       roles: music and uncategorized videos are re-encoded with it too ({@link Decision#VIDEO}).</li>
 * </ul>
 *
 * <p>Movies and shows never get the overlay, in any mode: their entries are {@link Decision#OFF}
 * whatever was recorded (the type is read at every call, so an item reclassified as a movie after
 * its entry was decided loses it too; the other way round the recorded decision stays).
 *
 * <p>The first {@link #decide} for an entry (when the program output plans its overlay, as it becomes
 * next or current) records the decision; later calls return the recorded one, also after a restart.
 * So a change of the owner's roles or of either switch takes effect from the next entry, and an entry
 * never gains or loses the overlay halfway through. The decision says what is <i>allowed</i>: whether
 * an entry plays as video at all (a video item that isn't presented as audio and doesn't play
 * audio-only) is up to the caller, and only then does {@link Decision#video()} matter.
 *
 * <p>Thread-safe; every call reads the database (the permissions too, so nothing is cached).
 */
public final class OverlayDecisions {
    /** {@code queue_entries.overlay}. */
    public enum Decision {
        /** No overlay (the server's switch is off, the room's mode isn't {@code always}, or a movie or show). */
        OFF("off"),
        /** On cards only; music videos play from the plain mezzanine. */
        CARDS("cards"),
        /** On cards and on music and uncategorized videos (re-encoded). */
        VIDEO("video");

        private final String wire;

        Decision(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** Whether cards (filler and item cards) carry the overlay. */
        public boolean cards() {
            return this != OFF;
        }

        /** Whether a music video may be re-encoded with the overlay. */
        public boolean video() {
            return this == VIDEO;
        }

        /** The decision with this wire name, or {@code null} for {@code null} or an unknown name. */
        public static Decision parse(String wire) {
            for (Decision d : values()) {
                if (d.wire.equals(wire)) {
                    return d;
                }
            }
            return null;
        }
    }

    private final Database db;
    private final Settings settings;
    private final Permissions permissions;

    public OverlayDecisions(Database db, Settings settings, Permissions permissions) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.permissions = Objects.requireNonNull(permissions);
    }

    /** Whether items of this effective media type never get the overlay ({@code movie}, {@code show}). */
    public static boolean neverOverlaid(String mediaType) {
        return MediaType.MOVIE.wire().equals(mediaType) || MediaType.SHOW.wire().equals(mediaType);
    }

    /**
     * What an entry's overlay decision would be right now, without recording anything:
     * {@link Decision#OFF} unless the server's switch is on, the room's mode is {@code always} and
     * the item's effective media type isn't {@code movie} or {@code show}; {@link Decision#VIDEO}
     * if the owner also holds {@code output.overlayVideo}, else {@link Decision#CARDS}.
     *
     * @param mediaType the item's effective media type ({@link MediaType#effective})
     */
    public Decision evaluate(String ownerId, RoomSettings roomSettings, String mediaType) throws SQLException {
        Objects.requireNonNull(ownerId);
        if (neverOverlaid(mediaType) || roomSettings.overlayMode() != RoomSettings.OverlayMode.ALWAYS
                || !settings.overlayEnabled()) {
            return Decision.OFF;
        }
        // Feature-gated by overlay.enabled, which is on here; a disabled owner holds nothing.
        return permissions.held(ownerId).contains(Permission.OUTPUT_OVERLAY_VIDEO) ? Decision.VIDEO : Decision.CARDS;
    }

    /** The decision recorded for the entry, or {@code null} if none was made yet (or there is no such entry). */
    public Decision recorded(String entryId) throws SQLException {
        Objects.requireNonNull(entryId);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT overlay FROM queue_entries WHERE id = ?")) {
                ps.setString(1, entryId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Decision.parse(rs.getString(1)) : null;
                }
            }
        });
    }

    /**
     * The entry's overlay decision: the recorded one, or, the first time, {@link #evaluate} for its
     * room's owner and settings and its item's type as they are now, which is then recorded. When
     * two callers race, the first write wins and both get it. A movie or show is always
     * {@link Decision#OFF}, whatever was recorded.
     *
     * @return the decision, or {@code null} if there is no such entry
     */
    public Decision decide(String entryId) throws SQLException {
        Objects.requireNonNull(entryId);
        record Row(String overlay, String ownerId, String settingsJson, String mediaType) {
        }
        Row row = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT q.overlay, r.owner_id, r.settings_json, i.kind,"
                    + " i.media_type FROM queue_entries q JOIN rooms r ON r.id = q.room_id"
                    + " JOIN items i ON i.id = q.item_id WHERE q.id = ?")) {
                ps.setString(1, entryId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                            MediaType.effective(rs.getString(4), rs.getString(5))) : null;
                }
            }
        });
        if (row == null) {
            return null;
        }
        Decision known = Decision.parse(row.overlay());
        if (known != null) {
            return neverOverlaid(row.mediaType()) ? Decision.OFF : known;
        }
        Decision now = evaluate(row.ownerId(), RoomSettings.parse(row.settingsJson()), row.mediaType());
        int updated = db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE queue_entries SET overlay = ? WHERE id = ? AND overlay IS NULL")) {
                ps.setString(1, now.wire());
                ps.setString(2, entryId);
                return ps.executeUpdate();
            }
        });
        if (updated == 1) {
            return now;
        }
        Decision raced = recorded(entryId);
        return raced != null ? raced : now;
    }
}
