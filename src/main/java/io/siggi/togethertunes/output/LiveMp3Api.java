package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.StreamBody;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * The MP3 output, {@code GET}/{@code HEAD /live/{token}.mp3}
 * ({@code docs/program-output.md#mp3-output}): a Shoutcast/Icecast-style MP3 stream of the room's
 * program audio for internet-radio players.
 *
 * <ul>
 *   <li>{@code GET}: {@code 200}, {@code Content-Type: audio/mpeg}, a close-delimited body (no
 *       {@code Content-Length}, no {@code Transfer-Encoding}) of whole MP3 frames from the room's
 *       {@link Mp3Encoder} (one per room, started by the first listener), {@code icy-name} (the
 *       room's name), {@code icy-br}, {@code icy-pub: 0}. With {@code Icy-MetaData: 1} the
 *       response has {@code icy-metaint: 16000} and ICY metadata blocks ({@link IcyWriter}) with
 *       {@code StreamTitle='Artist - Title';}; without it, plain MP3. A listener too slow for its
 *       queue is disconnected.</li>
 *   <li>{@code HEAD}: the same headers, no body; never starts the encoder.</li>
 *   <li>Limits, revocation and errors as for {@code .ts} ({@link LiveTsApi}): per-IP rate limit,
 *       per-token connection cap, {@link #revalidate} ends connections whose token no longer opens
 *       the room's MP3 output, {@code 404} for anything that doesn't resolve (also with the server
 *       switch {@code outputs.mp3.enabled} off); {@code 503 output_unavailable} when the server has
 *       no ffmpeg. Tokens are never logged in full (invariant 6).</li>
 * </ul>
 *
 * <p>Served through {@link LiveTsApi}'s {@code /live/{file}} route ({@link LiveTsApi#mp3}).
 */
public final class LiveMp3Api {
    private static final System.Logger LOG = System.getLogger(LiveMp3Api.class.getName());

    public static final String CONTENT_TYPE = "audio/mpeg";
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    /** What a token opens: a room's MP3 output. */
    public record Target(String roomId, String roomName) {
        public Target {
            Objects.requireNonNull(roomId, "roomId");
        }
    }

    /** Resolves a token to an enabled MP3 output, or null. */
    @FunctionalInterface
    public interface Targets {
        Target lookup(String token) throws SQLException;
    }

    private record Connection(String token, String roomId, Mp3Encoder.Listener listener, LiveTsApi.Abort abort) {
        void end() {
            listener.close();
            abort.abort();
        }
    }

    private final Targets targets;
    private final Mp3Encoders encoders; // null: no ffmpeg
    private final IntSupplier bitrateKbps;
    private final LiveLimits limits;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

    /**
     * @param encoders    the rooms' encoders, or null if the server has no ffmpeg
     * @param bitrateKbps {@code outputs.mp3.bitrateKbps}, for {@code icy-br} before an encoder runs
     */
    public LiveMp3Api(Targets targets, Mp3Encoders encoders, IntSupplier bitrateKbps, LiveLimits limits) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.encoders = encoders;
        this.bitrateKbps = Objects.requireNonNull(bitrateKbps, "bitrateKbps");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Targets from {@code stream_outputs} (kind {@code mp3}, enabled), behind {@code outputs.mp3.enabled}. */
    public static Targets targetsFrom(Database db, Settings settings) {
        return token -> {
            if (!settings.outputsMp3Enabled()) {
                return null;
            }
            return db.read(c -> {
                try (var ps = c.prepareStatement("SELECT so.room_id, so.enabled, r.name"
                        + " FROM stream_outputs so JOIN rooms r ON r.id = so.room_id"
                        + " WHERE so.token = ? AND so.kind = 'mp3'")) {
                    ps.setString(1, token);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(2) == 0) {
                            return null;
                        }
                        return new Target(rs.getString(1), rs.getString(3));
                    }
                }
            });
        };
    }

    /** Open {@code GET} connections. */
    public int connections() {
        return connections.size();
    }

    /** Ends every open connection whose token no longer opens the same room's MP3 output. */
    public int revalidate() {
        Map<String, List<Connection>> byToken = new HashMap<>();
        for (Connection c : connections) {
            byToken.computeIfAbsent(c.token(), k -> new ArrayList<>()).add(c);
        }
        int ended = 0;
        for (Map.Entry<String, List<Connection>> e : byToken.entrySet()) {
            Target t;
            try {
                t = targets.lookup(e.getKey());
            } catch (SQLException | RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Live .mp3: could not re-check token "
                        + LiveTsApi.masked(e.getKey()), ex);
                continue;
            }
            for (Connection c : e.getValue()) {
                if (t == null || !t.roomId().equals(c.roomId())) {
                    connections.remove(c);
                    c.end();
                    ended++;
                }
            }
        }
        if (ended > 0) {
            LOG.log(System.Logger.Level.INFO, "Live .mp3: ended " + ended + " connection(s) whose token was revoked");
        }
        return ended;
    }

    /** Registers with the {@code .ts} endpoint, which owns the {@code /live/{file}} route. */
    public void register(LiveTsApi ts) {
        ts.mp3(this::handle);
    }

    /** {@code icy-name}: the room name as printable ASCII (accents dropped), at most 200 characters. */
    static String icyName(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    void handle(Request request, Response response) throws Exception {
        limits.checkIp(request.clientIp());
        String file = request.pathParam("file");
        if (file == null || !file.endsWith(".mp3")) {
            throw HttpError.notFound();
        }
        String token = file.substring(0, file.length() - 4);
        if (!TOKEN_SHAPE.matcher(token).matches()) {
            throw HttpError.notFound();
        }
        Target target;
        try {
            target = targets.lookup(token);
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Live .mp3: could not look up token " + LiveTsApi.masked(token), e);
            response.error(500, "internal_error", "Internal server error");
            return;
        }
        if (target == null) {
            throw HttpError.notFound();
        }
        boolean metadata = "1".equals(strip(request.header("Icy-MetaData")));
        if (request.method().equals("HEAD")) {
            headers(response, target, bitrateKbps.getAsInt(), metadata);
            response.stream(CONTENT_TYPE, StreamBody.Framing.CLOSE_DELIMITED);
            return;
        }
        if (encoders == null) {
            response.error(503, "output_unavailable", "This server can't encode MP3: ffmpeg isn't configured");
            return;
        }
        try (LiveLimits.Slot slot = limits.open(token)) {
            Mp3Encoder.Listener listener;
            try {
                listener = encoders.join(target.roomId());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Live .mp3 " + LiveTsApi.masked(token)
                        + ": the MP3 encoder of room " + target.roomId() + " could not start", e);
                response.error(503, "output_unavailable", "The program output is not available right now");
                return;
            }
            stream(response, listener, token, target, metadata, response.raw()::abort);
        }
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }

    private static void headers(Response response, Target target, int kbps, boolean metadata) {
        response.header("icy-name", icyName(target.roomName()));
        response.header("icy-br", Integer.toString(kbps));
        response.header("icy-pub", "0");
        response.header("icy-sr", Integer.toString(Mp3Encoder.SAMPLE_RATE));
        if (metadata) {
            response.header("icy-metaint", Integer.toString(IcyWriter.METAINT));
        }
    }

    /** Sends MP3 until the client leaves or its queue closes (too slow, encoder stopped, revoked). */
    void stream(Response response, Mp3Encoder.Listener listener, String token, Target target, boolean metadata,
                LiveTsApi.Abort abort) throws IOException {
        Connection connection = new Connection(token, target.roomId(), listener, abort);
        connections.add(connection);
        AtomicBoolean writing = new AtomicBoolean();
        listener.onClose(() -> {
            if (listener.closeReason() == Mp3Encoder.CloseReason.OVERFLOW || writing.get()) {
                abort.abort();
            }
        });
        try {
            headers(response, target, listener.encoder().bitrateKbps(), metadata);
            StreamBody body = response.stream(CONTENT_TYPE, StreamBody.Framing.CLOSE_DELIMITED);
            response.raw().sendHeaders();
            IcyWriter out = new IcyWriter(body, metadata ? IcyWriter.METAINT : 0);
            while (true) {
                Mp3Encoder.Chunk c;
                try {
                    c = listener.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (c == null) {
                    return;
                }
                writing.set(true);
                try {
                    out.write(c.data(), c.title());
                } finally {
                    writing.set(false);
                }
            }
        } finally {
            connections.remove(connection);
            listener.close();
        }
    }
}
