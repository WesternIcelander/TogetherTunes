package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads {@code audit_log} for the admin pages, newest first. {@link AuditEntryView} has no IP
 * field: the {@code ip} column is read only by {@link IpAccess} ({@code docs/auth.md#ip-address-policy}).
 * Writing stays with {@link AuditLog}.
 */
public final class AuditEntries {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    private final Database db;

    /**
     * One audit row, without its IP.
     *
     * @param actor       the acting user or guest, {@code null} if none (or it no longer exists)
     * @param target      what was acted on (usually an id), or {@code null}
     * @param detailsJson extra fields as JSON text, or {@code null}
     */
    public record AuditEntryView(String id, long at, ActorRef actor, String action, String target,
                                 String detailsJson) {
    }

    /** One page; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<AuditEntryView> items, String nextCursor) {
    }

    public AuditEntries(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    /**
     * Entries newest first (by {@code at}, then id).
     *
     * @param action only entries with exactly this action, or {@code null} for all
     * @param actor  only entries by this user or guest id, or {@code null} for all
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *               invalid_cursor} if malformed
     */
    public Page list(String action, String actor, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        long cursorAt = 0;
        String cursorId = null;
        if (cursor != null) {
            int sep = cursor.indexOf('_');
            try {
                cursorAt = Long.parseLong(cursor.substring(0, Math.max(sep, 0)));
            } catch (NumberFormatException e) {
                sep = -1;
            }
            if (sep <= 0 || sep == cursor.length() - 1) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorId = cursor.substring(sep + 1);
        }
        StringBuilder sql = new StringBuilder(
                "SELECT a.id, a.at, a.actor_user_id, u.display_name, a.actor_guest_id, g.display_name,"
                        + " a.action, a.target, a.details_json FROM audit_log a"
                        + " LEFT JOIN users u ON u.id = a.actor_user_id"
                        + " LEFT JOIN guests g ON g.id = a.actor_guest_id WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (action != null) {
            sql.append(" AND a.action = ?");
            params.add(action);
        }
        if (actor != null) {
            sql.append(" AND (a.actor_user_id = ? OR a.actor_guest_id = ?)");
            params.add(actor);
            params.add(actor);
        }
        if (cursorId != null) {
            sql.append(" AND (a.at < ? OR (a.at = ? AND a.id < ?))");
            params.add(cursorAt);
            params.add(cursorAt);
            params.add(cursorId);
        }
        sql.append(" ORDER BY a.at DESC, a.id DESC LIMIT ").append(limit + 1);
        return db.read(c -> {
            List<AuditEntryView> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i) instanceof Long l) {
                        ps.setLong(i + 1, l);
                    } else {
                        ps.setString(i + 1, (String) params.get(i));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String userId = rs.getString(3);
                        String userName = rs.getString(4);
                        String guestId = rs.getString(5);
                        String guestName = rs.getString(6);
                        // A deleted actor (no joined name) is shown as none.
                        ActorRef ref = userId != null && userName != null ? ActorRef.user(userId, userName)
                                : guestId != null && guestName != null ? ActorRef.guest(guestId, guestName) : null;
                        items.add(new AuditEntryView(rs.getString(1), rs.getLong(2), ref, rs.getString(7),
                                rs.getString(8), rs.getString(9)));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                AuditEntryView last = items.get(limit - 1);
                next = last.at() + "_" + last.id();
            }
            return new Page(List.copyOf(items), next);
        });
    }
}
