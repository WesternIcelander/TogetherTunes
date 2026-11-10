package io.siggi.togethertunes.output;

import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.secrets.SecretDecryptionException;
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
 * room's RTMP push, {@code .ts} and HLS outputs, at most one of each kind per room.
 *
 * <ul>
 *   <li><b>Tokens</b> ({@code .ts}/HLS): 128 random bits, base64url without padding (22
 *       characters), stored in plaintext (they are shown to the owner again); {@link #rotate}
 *       replaces one. They are capabilities: never logged, never in an audit entry (invariant 6).</li>
 *   <li><b>RTMP stream keys</b> are stored encrypted ({@link SecretCipher}, context
 *       {@code stream_outputs.rtmp_key_enc:<id>}) and only ever shown as their last 4 characters
 *       ({@link #keyLast4}). The RTMP ingest URL is not secret, but is left out of audit entries
 *       too (some services put the key in it).</li>
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
    /** Keys shorter than this show nothing, not even the last 4 (as {@link RtmpPusher#maskKey}). */
    static final int KEY_HINT_MIN_LENGTH = 8;

    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** An output kind, as on the wire and in {@code stream_outputs.kind}. */
    public enum Kind {
        RTMP, TS, HLS;

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

        /** Whether this kind is reached by a token URL ({@code .ts}, HLS). */
        public boolean hasToken() {
            return this != RTMP;
        }
    }

    /** A {@code stream_outputs} row. Internal: holds the token and the encrypted key. */
    public record Output(String id, String roomId, Kind kind, String token, String rtmpUrl, String rtmpKeyEnc,
                         boolean enabled, long createdAt) {
        public Output {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(roomId, "roomId");
            Objects.requireNonNull(kind, "kind");
        }

        public boolean hasRtmpKey() {
            return rtmpKeyEnc != null;
        }
    }

    /**
     * A change: {@code null} fields stay as they are. {@code rtmpKey} is only applied when
     * {@code setRtmpKey} is true (then null or empty removes the key).
     */
    public record Patch(Boolean enabled, String rtmpUrl, boolean setRtmpKey, String rtmpKey) {
        public static final Patch NONE = new Patch(null, null, false, null);
    }

    private final Database db;
    private final SecretCipher cipher;
    private final SecureRandom random;
    private final Clock clock;

    public StreamOutputs(Database db, SecretCipher cipher, SecureRandom random, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A new stream token: 128 random bits, base64url without padding. */
    public String newToken() {
        byte[] b = new byte[TOKEN_BYTES];
        random.nextBytes(b);
        return TOKEN_ENCODER.encodeToString(b);
    }

    static String keyContext(String outputId) {
        return "stream_outputs.rtmp_key_enc:" + outputId;
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

    /** The room's outputs, in the order rtmp, ts, hls. */
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

    /**
     * Creates the room's output of this kind. {@code .ts}/HLS get a new token; RTMP needs
     * {@code rtmpUrl} (already checked with {@link #rtmpUrl}) and may have a key.
     *
     * @throws HttpError {@code 409 output_exists} if the room already has one of this kind
     */
    public Output create(String roomId, Kind kind, String rtmpUrl, String rtmpKey, boolean enabled,
                         String actorUserId, String ip) throws SQLException {
        String id = UuidV7.newId();
        long now = EpochMs.now(clock);
        String token = kind.hasToken() ? newToken() : null;
        String keyEnc = rtmpKey == null ? null : cipher.encrypt(rtmpKey, keyContext(id));
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
                        + " rtmp_url, rtmp_key_enc, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, roomId);
                    ps.setString(3, kind.wire());
                    ps.setString(4, token);
                    ps.setString(5, kind == Kind.RTMP ? rtmpUrl : null);
                    ps.setString(6, kind == Kind.RTMP ? keyEnc : null);
                    ps.setInt(7, enabled ? 1 : 0);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                }
                Map<String, Object> details = details(id, kind);
                details.put("enabled", enabled);
                AuditLog.write(c, now, actorUserId, null, ip, AUDIT_CREATED, roomId, details);
                return new Output(id, roomId, kind, token, kind == Kind.RTMP ? rtmpUrl : null,
                        kind == Kind.RTMP ? keyEnc : null, enabled, now);
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
            String keyEnc = o.rtmpKeyEnc();
            if (patch.setRtmpKey()) {
                String newKey = patch.rtmpKey();
                String oldKey = decryptOrNull(o);
                if (newKey == null ? keyEnc != null : !newKey.equals(oldKey)) {
                    keyEnc = newKey == null ? null : cipher.encrypt(newKey, keyContext(o.id()));
                    changed.add("rtmpKey");
                }
            }
            if (changed.isEmpty()) {
                return o;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE stream_outputs SET enabled = ?, rtmp_url = ?, rtmp_key_enc = ? WHERE id = ?")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, url);
                ps.setString(3, keyEnc);
                ps.setString(4, o.id());
                ps.executeUpdate();
            }
            Map<String, Object> details = details(o.id(), o.kind());
            details.put("changed", changed);
            if (changed.contains("enabled")) {
                details.put("enabled", enabled);
            }
            AuditLog.write(c, EpochMs.now(clock), actorUserId, null, ip, AUDIT_UPDATED, roomId, details);
            return new Output(o.id(), roomId, o.kind(), o.token(), url, keyEnc, enabled, o.createdAt());
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
     * Gives a {@code .ts}/HLS output a new token; the old one stops working at once.
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
            try (PreparedStatement ps = c.prepareStatement("UPDATE stream_outputs SET token = ? WHERE id = ?")) {
                ps.setString(1, token);
                ps.setString(2, o.id());
                ps.executeUpdate();
            }
            AuditLog.write(c, EpochMs.now(clock), actorUserId, null, ip, AUDIT_ROTATED, roomId,
                    details(o.id(), o.kind()));
            return new Output(o.id(), roomId, o.kind(), token, o.rtmpUrl(), o.rtmpKeyEnc(), o.enabled(),
                    o.createdAt());
        });
    }

    /**
     * The output's RTMP stream key in plaintext (for ffmpeg only), or null if it has none.
     *
     * @throws SecretDecryptionException if it can't be decrypted (another server key)
     */
    public String rtmpKey(Output o) throws SecretDecryptionException {
        return o.rtmpKeyEnc() == null ? null : cipher.decrypt(o.rtmpKeyEnc(), keyContext(o.id()));
    }

    /**
     * The last 4 characters of the output's stream key, the only part ever shown; null without a
     * key, for keys shorter than {@value #KEY_HINT_MIN_LENGTH} characters, or if it can't be
     * decrypted.
     */
    public String keyLast4(Output o) {
        String key = decryptOrNull(o);
        if (key == null || key.length() < KEY_HINT_MIN_LENGTH) {
            return null;
        }
        return key.substring(key.length() - 4);
    }

    private String decryptOrNull(Output o) {
        try {
            return rtmpKey(o);
        } catch (SecretDecryptionException e) {
            return null;
        }
    }

    private static final String SELECT =
            "SELECT id, room_id, kind, token, rtmp_url, rtmp_key_enc, enabled, created_at FROM stream_outputs";

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
        return new Output(rs.getString(1), rs.getString(2), kind, rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getInt(7) != 0, rs.getLong(8));
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
