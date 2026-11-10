package io.siggi.togethertunes.output;

import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code stream_outputs} rows ({@code docs/data-model.md}, {@code docs/rooms.md#outputs}): a
 * room's RTMP push, {@code .ts}, HLS and MP3 outputs, at most one of each kind per room.
 *
 * <ul>
 *   <li><b>Stream URLs</b> ({@code .ts}/HLS/MP3, {@code docs/program-output.md#stream-urls}): a
 *       public or unlisted room's outputs are reached by the room's slug ({@code /live/{slug}.ts}),
 *       a private room's by a random token. The URL's key is resolved by {@link #KEY_MATCH}: a
 *       token opens only a private room's output, a slug only a public or unlisted room's.</li>
 *   <li><b>Tokens</b>: 128 random bits, base64url without padding (22 characters), stored in
 *       plaintext (they are shown to the owner again); {@link #rotate} replaces one, and
 *       {@link #reissueTokens} all of a room's (when it becomes private). A token never has the
 *       shape of a slug ({@link #newToken}), so the two can't collide. Tokens are capabilities:
 *       never logged, never in an audit entry (invariant 6). Slugs aren't secrets. With
 *       {@code http.insecureBaseUrl} a token may travel over plain HTTP; its first such use is
 *       recorded in {@code token_cleartext_at} ({@link #markTokenCleartext}), and a new token
 *       clears it.</li>
 *   <li><b>RTMP stream keys</b> are stored in plain text ({@code rtmp_key}; RTMP sends them in
 *       plain text anyway). The API shows the key (and the ingest URL) to the room's owner only;
 *       moderators get nothing about either. Keys are never logged or audited (invariant 6).
 *       Keys encrypted by older versions are converted by {@link LegacyRtmpKeys}. The RTMP ingest
 *       URL is not secret, but is left out of audit entries too (some services put the key in it).</li>
 * </ul>
 *
 * <p>Access checks (owner, moderators) and the server switches are the caller's job
 * ({@link StreamOutputsApi}). Changes are audited ({@code room.output_*}, target = the room).
 */
public final class StreamOutputs {
    public static final String AUDIT_CREATED = "room.output_created";
    public static final String AUDIT_UPDATED = "room.output_updated";
    public static final String AUDIT_DELETED = "room.output_deleted";
    public static final String AUDIT_ROTATED = "room.output_token_rotated";

    public static final int RTMP_URL_MAX = 2048;
    public static final int RTMP_KEY_MAX = 512;
    /** Token entropy in bytes (128 bits). */
    public static final int TOKEN_BYTES = 16;

    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** What a slug looks like ({@code Rooms#slug}): a token never does. */
    private static final java.util.regex.Pattern SLUG_LIKE = java.util.regex.Pattern.compile("[a-z0-9-]+");

    /**
     * The shape of a stream URL's key (a slug or a token) on {@code /live/...}; anything else is
     * {@code 404} without a lookup.
     */
    public static final java.util.regex.Pattern KEY_SHAPE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{3,64}");

    /**
     * The {@code WHERE} condition (with {@code so} = {@code stream_outputs}, {@code r} = {@code
     * rooms}) that resolves a stream URL's key: the token of a private room's output, or the slug
     * of a public or unlisted room. Bind the key to both parameters ({@link #bindKey}).
     */
    public static final String KEY_MATCH =
            "((so.token = ? AND r.visibility = 'private') OR (r.slug = ? AND r.visibility <> 'private'))";

    /** Binds {@code key} to {@link #KEY_MATCH}'s two parameters, starting at {@code index}. */
    public static void bindKey(PreparedStatement ps, int index, String key) throws SQLException {
        ps.setString(index, key);
        ps.setString(index + 1, key);
    }

    /** Whether a room of this visibility ({@code rooms.visibility}) is reached by token URLs. */
    public static boolean usesToken(String visibility) {
        return "private".equals(visibility);
    }

    /** An output kind, as on the wire and in {@code stream_outputs.kind}. */
    public enum Kind {
        RTMP, TS, HLS, MP3;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The kind, or null if {@code s} isn't one. */
        public static Kind parse(String s) {
            if (s == null) {
                return null;
            }
            for (Kind k : values()) {
                if (k.wire().equals(s)) {
                    return k;
                }
            }
            return null;
        }

        /** Whether this kind is reached by a token URL ({@code .ts}, HLS, MP3). */
        public boolean hasToken() {
            return this != RTMP;
        }
    }

    /** A {@code stream_outputs} row. Internal: holds the token and the stream key. */
    public record Output(String id, String roomId, Kind kind, String token, String rtmpUrl, String rtmpKey,
                         boolean enabled, long createdAt, Stopped stopped, Long tokenCleartextAt) {
        public Output {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(roomId, "roomId");
            Objects.requireNonNull(kind, "kind");
        }

        public Output(String id, String roomId, Kind kind, String token, String rtmpUrl, String rtmpKey,
                      boolean enabled, long createdAt, Stopped stopped) {
            this(id, roomId, kind, token, rtmpUrl, rtmpKey, enabled, createdAt, stopped, null);
        }

        public Output(String id, String roomId, Kind kind, String token, String rtmpUrl, String rtmpKey,
                      boolean enabled, long createdAt) {
            this(id, roomId, kind, token, rtmpUrl, rtmpKey, enabled, createdAt, null);
        }

        public boolean hasRtmpKey() {
            return rtmpKey != null;
        }

        /** Without the token and the key (invariant 6). */
        @Override
        public String toString() {
            return "Output[id=" + id + ", roomId=" + roomId + ", kind=" + kind.wire() + ", enabled=" + enabled + "]";
        }
    }

    /**
     * Why the server turned an output off ({@link #stopAuto}), kept until {@code enabled} changes
     * again.
     *
     * @param reason       {@value #STOPPED_IDLE} or {@value #STOPPED_NO_LISTENERS}
     * @param at           when, epoch ms
     * @param idleMinutes  the auto turn-off limit that applied
     */
    public record Stopped(String reason, long at, int idleMinutes) {
    }

    /** {@link Stopped#reason()} of an output turned off after the room was idle (queue empty) too long. */
    public static final String STOPPED_IDLE = "idle";
    /** {@link Stopped#reason()} of a {@code .ts}/HLS/MP3 output turned off after nobody listened too long. */
    public static final String STOPPED_NO_LISTENERS = "no_listeners";

    /**
     * A change: {@code null} fields stay as they are. {@code rtmpKey} is only applied when
     * {@code setRtmpKey} is true (then null or empty removes the key).
     */
    public record Patch(Boolean enabled, String rtmpUrl, boolean setRtmpKey, String rtmpKey) {
        public static final Patch NONE = new Patch(null, null, false, null);
    }

    private final Database db;
    private final SecureRandom random;
    private final Clock clock;

    public StreamOutputs(Database db, SecureRandom random, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A new stream token: 128 random bits, base64url without padding, never one that looks like a
     * slug (only {@code a-z}, {@code 0-9}, {@code -}), so a slug URL and a token URL can't collide.
     */
    public String newToken() {
        byte[] b = new byte[TOKEN_BYTES];
        while (true) {
            random.nextBytes(b);
            String t = TOKEN_ENCODER.encodeToString(b);
            if (!SLUG_LIKE.matcher(t).matches()) {
                return t;
            }
        }
    }

    /**
     * Checks an RTMP ingest URL: {@code rtmp://} or {@code rtmps://} with a host, at most
     * {@value #RTMP_URL_MAX} characters, no spaces or control characters.
     *
     * @return the URL, trimmed
     * @throws HttpError {@code 400 invalid_rtmp_url}
     */
    public static String rtmpUrl(String raw) {
        String s = raw == null ? "" : raw.strip();
        HttpError bad = new HttpError(400, "invalid_rtmp_url",
                "rtmpUrl must be an rtmp:// or rtmps:// URL with a host, at most " + RTMP_URL_MAX + " characters");
        if (s.isEmpty() || s.length() > RTMP_URL_MAX) {
            throw bad;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                throw bad;
            }
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw bad;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("rtmp") || scheme.equals("rtmps")) || uri.getHost() == null || uri.getHost().isEmpty()) {
            throw bad;
        }
        return s;
    }

    /**
     * Checks an RTMP stream key: printable ASCII without spaces, at most {@value #RTMP_KEY_MAX}
     * characters; null or empty means no key.
     *
     * @return the key, or null for none
     * @throws HttpError {@code 400 invalid_rtmp_key}
     */
    public static String rtmpKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.length() > RTMP_KEY_MAX) {
            throw new HttpError(400, "invalid_rtmp_key", "rtmpKey is longer than " + RTMP_KEY_MAX + " characters");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new HttpError(400, "invalid_rtmp_key",
                        "rtmpKey may only hold printable ASCII characters without spaces");
            }
        }
        return raw;
    }

    /** The room's outputs, in the order rtmp, ts, hls, mp3. */
    public List<Output> list(String roomId) throws SQLException {
        return db.read(c -> {
            List<Output> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE room_id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Output o = row(rs);
                        if (o != null) {
                            out.add(o);
                        }
                    }
                }
            }
            out.sort((a, b) -> a.kind().compareTo(b.kind()));
            return out;
        });
    }

    /** The output with this id in this room, or null. */
    public Output get(String roomId, String outputId) throws SQLException {
        return db.read(c -> find(c, roomId, outputId));
    }

    /** The ids of the rooms that have at least one output (on or off). */
    public java.util.Set<String> roomIds() throws SQLException {
        return db.read(c -> {
            java.util.Set<String> out = new java.util.HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT room_id FROM stream_outputs");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    /** Every enabled RTMP output (the pushes the server should run, server switch permitting). */
    public List<Output> enabledRtmp() throws SQLException {
        return db.read(c -> {
            List<Output> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE kind = 'rtmp' AND enabled = 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Output o = row(rs);
                        if (o != null) {
                            out.add(o);
                        }
                    }
                }
            }
            return out;
        });
    }

    /** Every enabled output of every room (server switches not applied). */
    public List<Output> enabled() throws SQLException {
        return db.read(c -> {
            List<Output> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE enabled = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Output o = row(rs);
                    if (o != null) {
                        out.add(o);
                    }
                }
            }
            return out;
        });
    }

    /**
     * Creates the room's output of this kind. {@code .ts}/HLS/MP3 get a new token; RTMP needs
     * {@code rtmpUrl} (already checked with {@link #rtmpUrl}) and may have a key.
     *
     * @throws HttpError {@code 409 output_exists} if the room already has one of this kind
     */
    public Output create(String roomId, Kind kind, String rtmpUrl, String rtmpKey, boolean enabled,
                         String actorUserId, String ip) throws SQLException {
        String id = UuidV7.newId();
        long now = EpochMs.now(clock);
        String token = kind.hasToken() ? newToken() : null;
        try {
            return db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM stream_outputs WHERE room_id = ? AND kind = ?")) {
                    ps.setString(1, roomId);
                    ps.setString(2, kind.wire());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw exists(kind);
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO stream_outputs (id, room_id, kind, token,"
                        + " rtmp_url, rtmp_key, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, roomId);
                    ps.setString(3, kind.wire());
                    ps.setString(4, token);
                    ps.setString(5, kind == Kind.RTMP ? rtmpUrl : null);
                    ps.setString(6, kind == Kind.RTMP ? rtmpKey : null);
                    ps.setInt(7, enabled ? 1 : 0);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                }
                Map<String, Object> details = details(id, kind);
                details.put("enabled", enabled);
                AuditLog.write(c, now, actorUserId, null, ip, AUDIT_CREATED, roomId, details);
                return new Output(id, roomId, kind, token, kind == Kind.RTMP ? rtmpUrl : null,
                        kind == Kind.RTMP ? rtmpKey : null, enabled, now);
            });
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw exists(kind); // a concurrent create won
            }
            throw e;
        }
    }

    /**
     * Applies a change. {@code rtmpUrl}/{@code rtmpKey} are only for RTMP outputs (the caller
     * checks). The audit entry names what changed, never the values of the URL or key.
     *
     * @return the output after the change, or null if there is no such output in the room
     */
    public Output update(String roomId, String outputId, Patch patch, String actorUserId, String ip)
            throws SQLException {
        return db.write(c -> {
            Output o = find(c, roomId, outputId);
            if (o == null) {
                return null;
            }
            List<String> changed = new ArrayList<>();
            boolean enabled = o.enabled();
            if (patch.enabled() != null && patch.enabled() != enabled) {
                enabled = patch.enabled();
                changed.add("enabled");
            }
            String url = o.rtmpUrl();
            if (patch.rtmpUrl() != null && !patch.rtmpUrl().equals(url)) {
                url = patch.rtmpUrl();
                changed.add("rtmpUrl");
            }
            String key = o.rtmpKey();
            if (patch.setRtmpKey() && !Objects.equals(patch.rtmpKey(), key)) {
                key = patch.rtmpKey();
                changed.add("rtmpKey");
            }
            if (changed.isEmpty()) {
                return o;
            }
            boolean keepStopped = !changed.contains("enabled");
            try (PreparedStatement ps = c.prepareStatement("UPDATE stream_outputs SET enabled = ?, rtmp_url = ?,"
                    + " rtmp_key = ?" + (keepStopped ? "" : ", stopped_reason = NULL, stopped_at = NULL,"
                    + " stopped_idle_minutes = NULL") + " WHERE id = ?")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, url);
                ps.setString(3, key);
                ps.setString(4, o.id());
                ps.executeUpdate();
            }
            Map<String, Object> details = details(o.id(), o.kind());
            details.put("changed", changed);
            if (changed.contains("enabled")) {
                details.put("enabled", enabled);
            }
            AuditLog.write(c, EpochMs.now(clock), actorUserId, null, ip, AUDIT_UPDATED, roomId, details);
            return new Output(o.id(), roomId, o.kind(), o.token(), url, key, enabled, o.createdAt(),
                    keepStopped ? o.stopped() : null, o.tokenCleartextAt());
        });
    }

    /**
     * The server turns the room's output of this kind off automatically
     * ({@code docs/program-output.md#auto-turn-off}): {@code enabled = false} as if the owner had
     * turned it off (URL, key and token stay), with the reason recorded and an audit entry by the
     * system (no actor).
     *
     * @param reason  {@link #STOPPED_IDLE} or {@link #STOPPED_NO_LISTENERS}
     * @param minutes the limit that applied
     * @return the output after the change, or null if the room has no output of this kind that is on
     */
    public Output stopAuto(String roomId, Kind kind, String reason, int minutes) throws SQLException {
        Objects.requireNonNull(reason, "reason");
        return db.write(c -> {
            Output o = null;
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE room_id = ? AND kind = ?")) {
                ps.setString(1, roomId);
                ps.setString(2, kind.wire());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        o = row(rs);
                    }
                }
            }
            if (o == null || !o.enabled()) {
                return null;
            }
            long now = EpochMs.now(clock);
            try (PreparedStatement ps = c.prepareStatement("UPDATE stream_outputs SET enabled = 0,"
                    + " stopped_reason = ?, stopped_at = ?, stopped_idle_minutes = ? WHERE id = ? AND enabled = 1")) {
                ps.setString(1, reason);
                ps.setLong(2, now);
                ps.setInt(3, minutes);
                ps.setString(4, o.id());
                if (ps.executeUpdate() == 0) {
                    return null;
                }
            }
            Map<String, Object> details = details(o.id(), o.kind());
            details.put("changed", List.of("enabled"));
            details.put("enabled", false);
            details.put("reason", reason);
            details.put("idleMinutes", minutes);
            AuditLog.write(c, now, null, null, null, AUDIT_UPDATED, roomId, details);
            return new Output(o.id(), roomId, o.kind(), o.token(), o.rtmpUrl(), o.rtmpKey(), false, o.createdAt(),
                    new Stopped(reason, now, minutes), o.tokenCleartextAt());
        });
    }

    /**
     * Gives every {@code .ts}/HLS/MP3 output of the room a new token, e.g. because the room just
     * became private: tokens from an earlier private phase never come back. Clears
     * {@code token_cleartext_at} (the new tokens have never travelled unencrypted). Not audited (the
     * visibility change is).
     *
     * @return how many outputs got a new token
     */
    public int reissueTokens(String roomId) throws SQLException {
        List<Output> outs = list(roomId);
        return db.write(c -> {
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE stream_outputs SET token = ?, token_cleartext_at = NULL WHERE id = ?")) {
                for (Output o : outs) {
                    if (o.kind().hasToken()) {
                        ps.setString(1, newToken());
                        ps.setString(2, o.id());
                        n += ps.executeUpdate();
                    }
                }
            }
            return n;
        });
    }

    /** Deletes the output. @return the deleted row, or null if there was none */
    public Output delete(String roomId, String outputId, String actorUserId, String ip) throws SQLException {
        return db.write(c -> {
            Output o = find(c, roomId, outputId);
            if (o == null) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM stream_outputs WHERE id = ?")) {
                ps.setString(1, o.id());
                ps.executeUpdate();
            }
            AuditLog.write(c, EpochMs.now(clock), actorUserId, null, ip, AUDIT_DELETED, roomId,
                    details(o.id(), o.kind()));
            return o;
        });
    }

    /**
     * Gives a {@code .ts}/HLS/MP3 output a new token; the old one stops working at once. Clears
     * {@code token_cleartext_at}: the new token has never travelled unencrypted.
     *
     * @return the output with its new token, or null if there is no such output in the room
     * @throws HttpError {@code 409 no_token} for an RTMP output
     */
    public Output rotate(String roomId, String outputId, String actorUserId, String ip) throws SQLException {
        String token = newToken();
        return db.write(c -> {
            Output o = find(c, roomId, outputId);
            if (o == null) {
                return null;
            }
            if (!o.kind().hasToken()) {
                throw new HttpError(409, "no_token", "An RTMP output has no token to rotate");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE stream_outputs SET token = ?, token_cleartext_at = NULL WHERE id = ?")) {
                ps.setString(1, token);
                ps.setString(2, o.id());
                ps.executeUpdate();
            }
            AuditLog.write(c, EpochMs.now(clock), actorUserId, null, ip, AUDIT_ROTATED, roomId,
                    details(o.id(), o.kind()));
            return new Output(o.id(), roomId, o.kind(), token, o.rtmpUrl(), o.rtmpKey(), o.enabled(),
                    o.createdAt(), o.stopped(), null);
        });
    }

    /**
     * Records that a private room's output token was presented over plain HTTP
     * ({@code docs/program-output.md#plain-http}): sets {@code token_cleartext_at} to now if
     * {@code token} is the current token of the room's output of this kind, the room is private and
     * the time isn't set yet. Not audited (the token must not appear anywhere; the caller logs the
     * event without it).
     *
     * @return the output (without its token) if this call set the time, else null (not a private
     *         room's current token, or already recorded)
     */
    public Output markTokenCleartext(Kind kind, String token) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        if (token == null || !kind.hasToken()) {
            return null;
        }
        long now = EpochMs.now(clock);
        return db.write(c -> {
            String id;
            String roomId;
            try (PreparedStatement ps = c.prepareStatement("SELECT so.id, so.room_id FROM stream_outputs so"
                    + " JOIN rooms r ON r.id = so.room_id WHERE so.kind = ? AND so.token = ?"
                    + " AND r.visibility = 'private' AND so.token_cleartext_at IS NULL")) {
                ps.setString(1, kind.wire());
                ps.setString(2, token);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    id = rs.getString(1);
                    roomId = rs.getString(2);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE stream_outputs SET token_cleartext_at = ?"
                    + " WHERE id = ? AND token = ? AND token_cleartext_at IS NULL")) {
                ps.setLong(1, now);
                ps.setString(2, id);
                ps.setString(3, token);
                if (ps.executeUpdate() == 0) {
                    return null;
                }
            }
            Output o = find(c, roomId, id);
            return o == null ? null : new Output(o.id(), o.roomId(), o.kind(), null, null, null, o.enabled(),
                    o.createdAt(), o.stopped(), o.tokenCleartextAt());
        });
    }

    private static final String SELECT =
            "SELECT id, room_id, kind, token, rtmp_url, rtmp_key, enabled, created_at, stopped_reason, stopped_at,"
                    + " stopped_idle_minutes, token_cleartext_at FROM stream_outputs";

    private static Output find(Connection c, String roomId, String outputId) throws SQLException {
        if (outputId == null || roomId == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ? AND room_id = ?")) {
            ps.setString(1, outputId);
            ps.setString(2, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    /** The row, or null for an unknown kind (written by a newer version). */
    private static Output row(ResultSet rs) throws SQLException {
        Kind kind = Kind.parse(rs.getString(3));
        if (kind == null) {
            return null;
        }
        String reason = rs.getString(9);
        Stopped stopped = reason == null ? null : new Stopped(reason, rs.getLong(10), rs.getInt(11));
        long cleartext = rs.getLong(12);
        Long cleartextAt = rs.wasNull() ? null : cleartext;
        return new Output(rs.getString(1), rs.getString(2), kind, rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getInt(7) != 0, rs.getLong(8), stopped, cleartextAt);
    }

    private static Map<String, Object> details(String outputId, Kind kind) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("outputId", outputId);
        d.put("kind", kind.wire());
        return d;
    }

    private static HttpError exists(Kind kind) {
        return new HttpError(409, "output_exists", "This room already has " + switch (kind) {
            case RTMP -> "an RTMP output";
            case TS -> "a .ts output";
            case HLS -> "an HLS output";
            case MP3 -> "an MP3 output";
        });
    }

    private static boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                if ("23505".equals(sql.getSQLState())) {
                    return true;
                }
                String m = sql.getMessage();
                if (m != null && m.contains("UNIQUE constraint failed")) {
                    return true;
                }
            }
        }
        return false;
    }
}
