package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Json;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;

/**
 * Writes {@code audit_log} rows. Only writes: {@link AuditEntries} reads the log without its
 * {@code ip} column, which only {@link IpAccess} reads (invariant 5).
 *
 * <p>Action names are dotted lower case, e.g. {@code account.setup}.
 */
public final class AuditLog {
    private AuditLog() {
    }

    /**
     * Inserts one row in the caller's transaction.
     *
     * @param actorUserId  the acting user, or {@code null}
     * @param actorGuestId the acting guest, or {@code null} (at most one of the two)
     * @param ip           the client IP, or {@code null}
     * @param target       what was acted on (usually an id), or {@code null}
     * @param details      extra fields, serialised as JSON, or {@code null}; never secrets
     */
    public static void write(Connection c, long at, String actorUserId, String actorGuestId, String ip,
                             String action, String target, Map<String, ?> details) throws SQLException {
        if (actorUserId != null && actorGuestId != null) {
            throw new IllegalArgumentException("An audit row has a user or a guest actor, not both");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log (id, at, actor_user_id, actor_guest_id, ip, action, target, details_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, UuidV7.newId());
            ps.setLong(2, at);
            setNullable(ps, 3, actorUserId);
            setNullable(ps, 4, actorGuestId);
            setNullable(ps, 5, ip);
            ps.setString(6, action);
            setNullable(ps, 7, target);
            setNullable(ps, 8, details == null ? null : Json.GSON.toJson(details));
            ps.executeUpdate();
        }
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
