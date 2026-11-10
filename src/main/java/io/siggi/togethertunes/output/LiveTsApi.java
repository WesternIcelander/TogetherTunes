package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StreamBody;
import io.siggi.togethertunes.rooms.RoomSettings;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The direct MPEG-TS output, {@code GET}/{@code HEAD /live/{token}.ts}
 * ({@code docs/program-output.md#direct-mpeg-ts-ts}), copied from VRCDN's {@code .ts} output.
 *
 * <ul>
 *   <li>{@code GET}: {@code 200}, {@code Content-Type: video/mp2t}, {@code Cache-Control: no-store},
 *       a close-delimited body (no {@code Content-Length}, no {@code Transfer-Encoding},
 *       {@code Connection: close}) sent from the live edge; {@code Range} is ignored. The headers go
 *       out at once; the body starts at the next unit boundary ({@code join=next}) or, with the
 *       room setting {@code outputs.ts.join = latest}, with the most recent unit. Each connection
 *       holds a {@link ProgramOutputs.Lease} (starting the room's output if needed) and a
 *       {@link ConsumerQueue} of {@link ConsumerQueue#DEFAULT_CAPACITY} units; a client too slow to
 *       keep up is disconnected when its queue overflows.</li>
 *   <li>{@code HEAD}: {@code 200} with {@code Content-Length: 0} and no body, like VRCDN; it only
 *       checks the token, never starts the output and is never a viewer.</li>
 *   <li><b>Viewers:</b> a connection counts as a viewer ({@link #viewers}) only once it has been
 *       sent {@link #VIEWER_AFTER_UNITS} units (about 2 s of real-time output), so a probe (yt-dlp's
 *       generic extractor reading the first bytes, a player checking the type) never counts.</li>
 *   <li>Unknown, disabled or malformed tokens, a room without the {@code .ts} output, or the server
 *       switch {@code outputs.ts.enabled} off: {@code 404}. The token is a capability: it is never
 *       logged in full (invariant 6), so errors are handled here, not by the router (which logs
 *       the path).</li>
 * </ul>
 */
public final class LiveTsApi {
    private static final System.Logger LOG = System.getLogger(LiveTsApi.class.getName());

    public static final String CONTENT_TYPE = "video/mp2t";
    /** Units a connection must have been sent before it counts as a viewer. */
    public static final int VIEWER_AFTER_UNITS = 2;
    /** Stream tokens: 128 bits in base64url without padding (22 characters); a little slack. */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    /** What a token opens: a room's {@code .ts} output and its join point. */
    public record Target(String roomId, boolean joinLatest) {
        public Target {
            Objects.requireNonNull(roomId, "roomId");
        }
    }

    /** Resolves a token to an enabled {@code .ts} output, or null (unknown, disabled, server switch off). */
    @FunctionalInterface
    public interface Targets {
        Target lookup(String token) throws SQLException;
    }

    /** Ends a connection at once, also while another thread is blocked writing to it. */
    @FunctionalInterface
    interface Abort {
        void abort();
    }

    private final Targets targets;
    private final ProgramOutputs outputs;
    private final ConcurrentHashMap<String, AtomicInteger> viewers = new ConcurrentHashMap<>();

    public LiveTsApi(Targets targets, ProgramOutputs outputs) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
    }

    /**
     * Targets from {@code stream_outputs} (kind {@code ts}, enabled) and the room's
     * {@code outputs.ts.join} setting, behind the server switch {@code outputs.ts.enabled}.
     */
    public static Targets targetsFrom(Database db, Settings settings) {
        return token -> {
            if (!settings.outputsTsEnabled()) {
                return null;
            }
            return db.read(c -> {
                try (var ps = c.prepareStatement("SELECT so.room_id, so.enabled, r.settings_json"
                        + " FROM stream_outputs so JOIN rooms r ON r.id = so.room_id"
                        + " WHERE so.token = ? AND so.kind = 'ts'")) {
                    ps.setString(1, token);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(2) == 0) {
                            return null;
                        }
                        return new Target(rs.getString(1), RoomSettings.parse(rs.getString(3)).tsJoinLatest());
                    }
                }
            });
        };
    }

    public void register(Router router) {
        // GET routes answer HEAD too; /live/{token}/… (HLS) has more segments and doesn't match.
        router.get("/live/{file}", this::handle);
    }

    /** Connections of the room that count as viewers now. */
    public int viewers(String roomId) {
        AtomicInteger n = viewers.get(roomId);
        return n == null ? 0 : n.get();
    }

    /** The token for logs: its first 4 characters (invariant 6). */
    static String masked(String token) {
        return token.length() <= 4 ? "…" : token.substring(0, 4) + "…";
    }

    void handle(Request request, Response response) throws Exception {
        String file = request.pathParam("file");
        if (file == null || !file.endsWith(".ts")) {
            throw HttpError.notFound();
        }
        String token = file.substring(0, file.length() - 3);
        if (!TOKEN_SHAPE.matcher(token).matches()) {
            throw HttpError.notFound();
        }
        Target target;
        try {
            target = targets.lookup(token);
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Live .ts: could not look up token " + masked(token), e);
            response.error(500, "internal_error", "Internal server error");
            return;
        }
        if (target == null) {
            throw HttpError.notFound();
        }
        if (request.method().equals("HEAD")) {
            // like VRCDN: 200, Content-Length: 0; never starts the output, never a viewer
            response.header("Content-Type", CONTENT_TYPE);
            response.header("Cache-Control", "no-store");
            response.empty(200);
            return;
        }
        ProgramOutputs.Lease lease;
        try {
            lease = outputs.acquire(target.roomId());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Live .ts " + masked(token) + ": the program output of room "
                    + target.roomId() + " could not start", e);
            response.error(503, "output_unavailable", "The program output is not available right now");
            return;
        }
        try {
            stream(response, lease.packager(), target, abortFor(response));
        } finally {
            lease.close();
        }
    }

    /**
     * How the packager ends a connection whose queue overflowed while the handler thread may be
     * blocked writing to a stalled client.
     */
    private static Abort abortFor(Response response) {
        // TODO(SiggiHTTP): needs an abort that works while another thread is blocked in write
        // (reported to the SiggiHTTP session 2026-11-10). Until then the writer notices the closed
        // queue after its current write returns.
        return () -> {
        };
    }

    /** Sends units until the client leaves or its queue closes (overflow, output stopped). */
    void stream(Response response, Packager packager, Target target, Abort abort) throws IOException {
        ConsumerQueue queue = packager.subscribe(target.joinLatest());
        queue.onClose(abort::abort);
        boolean viewer = false;
        try {
            StreamBody body = response.stream(CONTENT_TYPE, StreamBody.Framing.CLOSE_DELIMITED);
            response.raw().sendHeaders(); // at once, as VRCDN; the first unit may be up to ~2 s away
            int sent = 0;
            while (true) {
                TsUnit unit;
                try {
                    unit = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (unit == null) {
                    return; // overflow or output stopped: the body ends with the connection
                }
                body.write(unit.ts());
                if (++sent == VIEWER_AFTER_UNITS) {
                    viewers.computeIfAbsent(target.roomId(), k -> new AtomicInteger()).incrementAndGet();
                    viewer = true;
                }
            }
        } finally {
            queue.leave();
            if (viewer) {
                viewers.computeIfPresent(target.roomId(), (k, n) -> n.decrementAndGet() <= 0 ? null : n);
            }
        }
    }
}
