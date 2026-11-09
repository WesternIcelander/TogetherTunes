package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.GuestKeys;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.Settings;
import io.siggi.togethertunes.settings.UploadQuota;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The guest upload quota ({@code guests.uploadQuota}, {@code docs/auth.md#guest-policy-server-settings}):
 * at most {@link UploadQuota#perHour} uploads started in any rolling hour, counted per guest
 * <b>and</b> per IP (whichever is hit first), and at most {@link UploadQuota#maxItemBytes} per
 * file. Users have no quota.
 *
 * <p>Every upload a guest starts is a {@code guest_upload_starts} row, written in the same
 * transaction as the upload; the row stays when the upload is refused, abandoned or deduplicated,
 * so those count too. The IP is counted by the SHA-256 of its normalised key (the IPv4 address
 * or the IPv6 /64, {@link GuestKeys#ipKeyHash}), so no IP address is stored and an IPv6 client
 * can't escape by rotating its interface ID. Several guests behind one NAT share the IP's quota.
 *
 * <p>Over the quota: {@code 429 upload_quota_exceeded} with {@code Retry-After} (until the oldest
 * counted upload is an hour old); over the size: {@code 413 payload_too_large}. Checks and the
 * insert run under {@link #lock()}, so concurrent requests can't both take the last slot.
 */
public final class GuestUploadQuota {
    /** The rolling window. */
    public static final Duration WINDOW = Duration.ofHours(1);

    private final Database db;
    private final Settings settings;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public GuestUploadQuota(Database db, Settings settings, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The current setting. */
    public UploadQuota quota() throws SQLException {
        return settings.guestsUploadQuota();
    }

    /** Held around {@link #reserve} and the upload's insert. */
    ReentrantLock lock() {
        return lock;
    }

    /**
     * Checks without recording, e.g. before an anonymous visitor becomes a guest, so a refused
     * request doesn't create one.
     *
     * @param guestId  the guest, or null for an anonymous visitor (only the IP counts)
     * @param clientIp the request's IP, or null if unknown (only the guest counts)
     * @throws HttpError {@code 413 payload_too_large} / {@code 429 upload_quota_exceeded}
     */
    public void check(String guestId, String clientIp, long size) throws SQLException {
        UploadQuota quota = quota();
        checkSize(quota, size);
        long now = EpochMs.now(clock);
        db.read(c -> {
            checkCount(c, quota, guestId, GuestKeys.ipKeyHash(clientIp), now);
            return null;
        });
    }

    /**
     * Checks and records one upload start for {@code guestId} in the caller's transaction. Call
     * it while holding {@link #lock()}.
     *
     * @throws HttpError as {@link #check}
     */
    void reserve(Connection c, String guestId, String clientIp, long size, long now) throws SQLException {
        Objects.requireNonNull(guestId);
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("reserve needs the quota lock");
        }
        UploadQuota quota = quota();
        checkSize(quota, size);
        String ipHash = GuestKeys.ipKeyHash(clientIp);
        checkCount(c, quota, guestId, ipHash, now);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO guest_upload_starts (id, guest_id, ip_key_hash, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, UuidV7.newId());
            ps.setString(2, guestId);
            ps.setString(3, ipHash);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    private static void checkSize(UploadQuota quota, long size) {
        if (size > quota.maxItemBytes()) {
            throw HttpError.payloadTooLarge("The file is larger than the guest upload limit ("
                    + quota.maxItemBytes() + " bytes)");
        }
    }

    private static void checkCount(Connection c, UploadQuota quota, String guestId, String ipHash, long now)
            throws SQLException {
        if (quota.perHour() == 0) {
            throw new HttpError(403, "guest_uploads_disabled", "Guests can't upload files on this server");
        }
        long since = now - WINDOW.toMillis();
        long retryAt = 0;
        if (guestId != null) {
            retryAt = Math.max(retryAt, full(c, "guest_id", guestId, since, quota.perHour()));
        }
        if (ipHash != null) {
            retryAt = Math.max(retryAt, full(c, "ip_key_hash", ipHash, since, quota.perHour()));
        }
        if (retryAt > 0) {
            long waitMs = Math.max(1, retryAt - now);
            throw new HttpError(429, "upload_quota_exceeded", "Guests may start " + quota.perHour()
                    + " uploads per hour; try again later", Math.max(1, (waitMs + 999) / 1000));
        }
    }

    /**
     * When the window has room again if {@code column = value} already has {@code limit} uploads
     * after {@code since}, else 0.
     */
    private static long full(Connection c, String column, String value, long since, int limit) throws SQLException {
        // The limit-th newest start: once it leaves the window, there is room for one more.
        try (PreparedStatement ps = c.prepareStatement("SELECT created_at FROM guest_upload_starts WHERE "
                + column + " = ? AND created_at > ? ORDER BY created_at DESC LIMIT 1 OFFSET ?")) {
            ps.setString(1, value);
            ps.setLong(2, since);
            ps.setInt(3, limit - 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) + WINDOW.toMillis() : 0;
            }
        }
    }

    /** Deletes rows that no longer count. @return how many */
    public int sweep() throws SQLException {
        long cutoff = EpochMs.now(clock) - WINDOW.toMillis();
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guest_upload_starts WHERE created_at <= ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }
}
