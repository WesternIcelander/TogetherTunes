package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Invites ({@code docs/auth.md#invites}): single-use codes that let someone register while
 * {@code registration.mode} is {@code invite}. Created by holders of {@code users.invite}; only the
 * code's SHA-256 is stored ({@code invites.code_hash}), so the code is shown once, when it is
 * made. An invite is {@code pending} until it is used (by one registration,
 * {@link #consume}), revoked or expired. An invite whose creator's account is disabled can't be
 * used (the creator losing {@code users.invite} doesn't void the invites already handed out; an
 * admin or the creator revokes them).
 */
public final class Invites {
    /** Audit action: an invite created (actor: the creator; target: the invite id). */
    public static final String AUDIT_CREATED = "invite.created";
    /** Audit action: an invite revoked (actor: who revoked it; target: the invite id). */
    public static final String AUDIT_REVOKED = "invite.revoked";

    /** Default lifetime of an invite: 7 days. */
    public static final int DEFAULT_HOURS = 7 * 24;
    /** Longest lifetime of an invite: 30 days. */
    public static final int MAX_HOURS = 30 * 24;
    /** Longest note, in code points. */
    public static final int NOTE_MAX = 200;
    /** Pending invites a user may have at once (holders of {@code admin.users}: no limit). */
    public static final int PENDING_MAX = 50;
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    public static final String PENDING = "pending";
    public static final String USED = "used";
    public static final String REVOKED = "revoked";
    public static final String EXPIRED = "expired";

    private final Database db;
    private final Clock clock;

    /** One invite as listed. Never the code. {@code createdBy}/{@code usedBy} null: none, or gone. */
    public record InviteView(String id, String status, long createdAt, long expiresAt, String note,
                             ActorRef createdBy, ActorRef usedBy, Long usedAt, Long revokedAt) {
    }

    /** A new invite and its code (shown only now). */
    public record Created(InviteView invite, String code) {
    }

    public record Page(List<InviteView> items, String nextCursor) {
    }

    public Invites(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Creates an invite.
     *
     * @param expiresInHours 1..{@value #MAX_HOURS}, or {@code null} for {@value #DEFAULT_HOURS}
     * @param limited        whether the {@value #PENDING_MAX} pending invites limit applies
     * @throws HttpError {@code 400 invalid_expiry}, {@code 400 invalid_note},
     *                   {@code 409 too_many_invites}
     */
    public Created create(String userId, Integer expiresInHours, String note, boolean limited, String ip)
            throws SQLException {
        Objects.requireNonNull(userId);
        int hours = expiresInHours == null ? DEFAULT_HOURS : expiresInHours;
        if (hours < 1 || hours > MAX_HOURS) {
            throw new HttpError(400, "invalid_expiry", "expiresInHours must be 1.." + MAX_HOURS);
        }
        String cleanNote = note(note);
        String code = Tokens.newToken();
        String id = UuidV7.newId();
        long now = EpochMs.now(clock);
        long expiresAt = now + hours * 3_600_000L;
        db.write(c -> {
            if (limited) {
                // Serialise this user's creates (a row lock on PostgreSQL; SQLite has one writer).
                try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = disabled WHERE id = ?")) {
                    ps.setString(1, userId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM invites WHERE created_by_user_id = ?"
                        + " AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?")) {
                    ps.setString(1, userId);
                    ps.setLong(2, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) >= PENDING_MAX) {
                            throw new HttpError(409, "too_many_invites", "You have " + PENDING_MAX
                                    + " unused invites already; revoke some or wait until they expire");
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO invites (id, code_hash, created_by_user_id,"
                    + " created_at, expires_at, note) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, Tokens.hash(code));
                ps.setString(3, userId);
                ps.setLong(4, now);
                ps.setLong(5, expiresAt);
                if (cleanNote == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, cleanNote);
                }
                ps.executeUpdate();
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("expiresAt", expiresAt);
            AuditLog.write(c, now, userId, null, ip, AUDIT_CREATED, id, details);
            return null;
        });
        return new Created(get(id), code);
    }

    /** The invite, or null. */
    public InviteView get(String id) throws SQLException {
        long now = EpochMs.now(clock);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE i.id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? view(rs, now) : null;
                }
            }
        });
    }

    /**
     * Invites, newest first.
     *
     * @param createdBy only this user's invites; {@code null} for everyone's
     * @param cursor    the {@code nextCursor} of the page before, or null
     */
    public Page list(String createdBy, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit " + limit);
        }
        long now = EpochMs.now(clock);
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<String> args = new ArrayList<>();
        if (createdBy != null) {
            sql.append(" AND i.created_by_user_id = ?");
            args.add(createdBy);
        }
        if (cursor != null) {
            sql.append(" AND i.id < ?");
            args.add(cursor);
        }
        sql.append(" ORDER BY i.id DESC LIMIT ").append(limit + 1);
        List<InviteView> items = db.read(c -> {
            List<InviteView> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    ps.setString(i + 1, args.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(view(rs, now));
                    }
                }
            }
            return out;
        });
        String next = null;
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
            next = items.get(limit - 1).id();
        }
        return new Page(items, next);
    }

    /**
     * Revokes a pending invite.
     *
     * @param onlyOf the invite must be this user's ({@code 404} otherwise); {@code null}: any
     * @return the invite afterwards
     * @throws HttpError {@code 404 invite_not_found}, {@code 409 invite_used}; revoking a revoked or
     *                   expired invite changes nothing
     */
    public InviteView revoke(String id, String onlyOf, String actorUserId, String ip) throws SQLException {
        long now = EpochMs.now(clock);
        db.write(c -> {
            String createdBy;
            Long usedAt;
            Long revokedAt;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT created_by_user_id, used_at, revoked_at FROM invites WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw notFound();
                    }
                    createdBy = rs.getString(1);
                    usedAt = rs.getObject(2) == null ? null : rs.getLong(2);
                    revokedAt = rs.getObject(3) == null ? null : rs.getLong(3);
                }
            }
            if (onlyOf != null && !onlyOf.equals(createdBy)) {
                throw notFound();
            }
            if (usedAt != null) {
                throw new HttpError(409, "invite_used", "This invite has been used already");
            }
            if (revokedAt != null) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE invites SET revoked_at = ? WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL")) {
                ps.setLong(1, now);
                ps.setString(2, id);
                if (ps.executeUpdate() == 1) {
                    AuditLog.write(c, now, actorUserId, null, ip, AUDIT_REVOKED, id, null);
                }
            }
            return null;
        });
        return get(id);
    }

    /**
     * Whether {@code code} is a usable invite now (a cheap check before the slow password hash;
     * {@link #consume} decides).
     *
     * @throws HttpError {@code 403 invite_required} (no code), {@code 403 invalid_invite}
     */
    public void check(String code) throws SQLException {
        if (code == null || code.isBlank()) {
            throw inviteRequired();
        }
        if (!Tokens.isWellFormed(code.strip())) {
            throw invalid();
        }
        long now = EpochMs.now(clock);
        boolean ok = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM invites i JOIN users u"
                    + " ON u.id = i.created_by_user_id WHERE " + USABLE)) {
                ps.setString(1, Tokens.hash(code.strip()));
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
        if (!ok) {
            throw invalid();
        }
    }

    /**
     * In the transaction that creates the account {@code newUserId}: uses up the invite with this
     * code. Two registrations with one code: exactly one gets it.
     *
     * @return the invite's id
     * @throws HttpError {@code 403 invite_required}, {@code 403 invalid_invite} (unknown, used,
     *                   revoked, expired, or its creator is disabled)
     */
    public static String consume(Connection c, String code, String newUserId, long now) throws SQLException {
        if (code == null || code.isBlank()) {
            throw inviteRequired();
        }
        String clean = code.strip();
        if (!Tokens.isWellFormed(clean)) {
            throw invalid();
        }
        String hash = Tokens.hash(clean);
        String id;
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id FROM invites i JOIN users u"
                + " ON u.id = i.created_by_user_id WHERE " + USABLE)) {
            ps.setString(1, hash);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw invalid();
                }
                id = rs.getString(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE invites SET used_by_user_id = ?, used_at = ?"
                + " WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL")) {
            ps.setString(1, newUserId);
            ps.setLong(2, now);
            ps.setString(3, id);
            if (ps.executeUpdate() != 1) {
                throw invalid();
            }
        }
        return id;
    }

    /** {@code invites i JOIN users u}: the code (1) usable at (2). */
    private static final String USABLE = "i.code_hash = ? AND i.used_at IS NULL AND i.revoked_at IS NULL"
            + " AND i.expires_at > ? AND u.disabled = 0";

    private static final String SELECT = "SELECT i.id, i.created_at, i.expires_at, i.note, i.used_at, i.revoked_at,"
            + " i.created_by_user_id, cu.display_name, i.used_by_user_id, uu.display_name FROM invites i"
            + " LEFT JOIN users cu ON cu.id = i.created_by_user_id LEFT JOIN users uu ON uu.id = i.used_by_user_id";

    private static InviteView view(ResultSet rs, long now) throws SQLException {
        long expiresAt = rs.getLong(3);
        Long usedAt = rs.getObject(5) == null ? null : rs.getLong(5);
        Long revokedAt = rs.getObject(6) == null ? null : rs.getLong(6);
        String status = usedAt != null ? USED : revokedAt != null ? REVOKED : expiresAt <= now ? EXPIRED : PENDING;
        String createdBy = rs.getString(7);
        String usedBy = rs.getString(9);
        return new InviteView(rs.getString(1), status, rs.getLong(2), expiresAt, rs.getString(4),
                createdBy == null ? null : ActorRef.user(createdBy, rs.getString(8)),
                usedBy == null ? null : ActorRef.user(usedBy, rs.getString(10)), usedAt, revokedAt);
    }

    private static String note(String note) {
        if (note == null) {
            return null;
        }
        String s = note.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.codePointCount(0, s.length()) > NOTE_MAX || s.codePoints().anyMatch(Character::isISOControl)) {
            throw new HttpError(400, "invalid_note", "A note is at most " + NOTE_MAX
                    + " characters on one line");
        }
        return s;
    }

    private static HttpError notFound() {
        return new HttpError(404, "invite_not_found", "No such invite");
    }

    static HttpError inviteRequired() {
        return new HttpError(403, "invite_required", "Registration is by invitation only: an invite code is needed");
    }

    static HttpError invalid() {
        return new HttpError(403, "invalid_invite", "This invite can't be used: it is unknown, used, revoked or expired");
    }
}
