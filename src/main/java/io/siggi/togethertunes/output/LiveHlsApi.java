package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.settings.Settings;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The HLS output ({@code docs/program-output.md#hls}): {@code GET /live/{key}/index.m3u8} and
 * {@code GET /live/{key}/seg/{sequence}.ts} (key: the room's slug, or a private room's token,
 * {@link StreamOutputs#KEY_MATCH}; each request counts as listening, {@link OutputListeners}) ({@code HEAD} answers like {@code GET}, without the
 * body).
 *
 * <ul>
 *   <li><b>Playlist:</b> {@code 200}, {@code Content-Type: application/vnd.apple.mpegurl},
 *       {@code Cache-Control: no-cache}; built by the room's {@link HlsSegmenter} (6 segments listed,
 *       8 kept, real {@code EXTINF}). Each fetch is activity ({@link ProgramOutputs#touch}),
 *       starting the room's output if needed. A fresh output has no segment yet: the request waits
 *       up to {@link #FIRST_SEGMENT_WAIT_MS} for the first one, else {@code 503} with
 *       {@code Retry-After: 1}.</li>
 *   <li><b>Segments:</b> {@code 200}, {@code Content-Type: video/mp2t}, {@code Content-Length},
 *       {@code Cache-Control: no-cache} (sequence numbers restart with the server, so a segment URL
 *       is not forever); {@code Range} is ignored. A segment no longer (or not yet) in the ring:
 *       {@code 404}. A segment fetch is activity too.</li>
 *   <li>The segmenter follows the room's packager from the first HLS request on, and after a
 *       restart of the packager ({@link #started}) while HLS was fetched in the last
 *       {@link ProgramOutputs#IDLE_STOP_MS}. Rooms nobody fetches HLS from keep no segment ring.</li>
 *   <li><b>Limits</b> ({@link LiveLimits}): every request counts against the client IP's rate
 *       limit ({@code 429 rate_limited}) and, while it is served, against the token's connection
 *       cap ({@code 429 too_many_connections}).</li>
 *   <li>Unknown, disabled or malformed tokens, a room without the HLS output, or the server switch
 *       {@code outputs.hls.enabled} off: {@code 404}. Tokens are never logged in full (invariant 6).</li>
 * </ul>
 */
public final class LiveHlsApi implements ProgramOutputs.StartListener {
    private static final System.Logger LOG = System.getLogger(LiveHlsApi.class.getName());

    public static final String PLAYLIST_TYPE = "application/vnd.apple.mpegurl";
    public static final String SEGMENT_TYPE = "video/mp2t";
    /** How long a playlist request waits for a fresh output's first segment. */
    public static final long FIRST_SEGMENT_WAIT_MS = 5000;

    private static final Pattern TOKEN_SHAPE = StreamOutputs.KEY_SHAPE;
    private static final Pattern SEGMENT_FILE = Pattern.compile("(0|[1-9][0-9]{0,17})\\.ts");

    /** Resolves a token to the room of an enabled HLS output, or null (unknown, disabled, server switch off). */
    @FunctionalInterface
    public interface Targets {
        String lookup(String token) throws SQLException;
    }

    private final Targets targets;
    private final ProgramOutputs outputs;
    private final LongSupplier nowMs;
    private final LiveLimits limits;
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private volatile OutputListeners listeners = OutputListeners.NONE;

    /** Where requests are counted as listening ({@link OutputAutoOff}). */
    public void listeners(OutputListeners listeners) {
        this.listeners = Objects.requireNonNull(listeners);
    }

    private static final class Room {
        final HlsSegmenter segmenter = new HlsSegmenter();
        volatile long lastFetchMs;
    }

    /** Without a per-IP limit (tests); the per-token cap still applies. */
    public LiveHlsApi(Targets targets, ProgramOutputs outputs, LongSupplier nowMs) {
        this(targets, outputs, nowMs, LiveLimits.noIpLimit());
    }

    public LiveHlsApi(Targets targets, ProgramOutputs outputs, LongSupplier nowMs, LiveLimits limits) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Checks the client IP's rate limit and counts the request against its token while it is served. */
    private LiveLimits.Slot admit(Request request) {
        limits.checkIp(request.clientIp());
        String token = request.pathParam("token");
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw HttpError.notFound();
        }
        return limits.open(token);
    }

    /** Targets from {@code stream_outputs} (kind {@code hls}, enabled), behind the server switch {@code outputs.hls.enabled}. */
    public static Targets targetsFrom(Database db, Settings settings) {
        return token -> {
            if (!settings.outputsHlsEnabled()) {
                return null;
            }
            return db.read(c -> {
                try (var ps = c.prepareStatement("SELECT so.room_id, so.enabled FROM stream_outputs so"
                        + " JOIN rooms r ON r.id = so.room_id WHERE so.kind = 'hls' AND " + StreamOutputs.KEY_MATCH)) {
                    StreamOutputs.bindKey(ps, 1, token);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(2) == 0) {
                            return null;
                        }
                        return rs.getString(1);
                    }
                }
            });
        };
    }

    /** Registers the routes and follows packager restarts. */
    public void register(Router router) {
        router.get("/live/{token}/index.m3u8", this::playlist);
        router.get("/live/{token}/seg/{file}", this::segment);
        outputs.addStartListener(this);
    }

    /** The room's segmenter, or null if HLS was never fetched from it (tests). */
    HlsSegmenter segmenter(String roomId) {
        Room r = rooms.get(roomId);
        return r == null ? null : r.segmenter;
    }

    @Override
    public void started(String roomId, Packager packager) {
        Room r = rooms.get(roomId);
        if (r != null && nowMs.getAsLong() - r.lastFetchMs <= ProgramOutputs.IDLE_STOP_MS) {
            r.segmenter.attach(packager);
        }
    }

    /** Resolves the token and makes sure the room's output runs with the segmenter attached; null if the response was sent. */
    private Room open(Request request, Response response) throws Exception {
        String token = request.pathParam("token");
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw HttpError.notFound();
        }
        String roomId;
        try {
            roomId = targets.lookup(token);
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Live HLS: could not look up token " + LiveTsApi.masked(token), e);
            response.error(500, "internal_error", "Internal server error");
            return null;
        }
        if (roomId == null) {
            throw HttpError.notFound();
        }
        Room room = rooms.computeIfAbsent(roomId, k -> new Room());
        room.lastFetchMs = nowMs.getAsLong();
        listeners.touched(roomId, StreamOutputs.Kind.HLS);
        Packager packager;
        try {
            packager = outputs.touch(roomId);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Live HLS " + LiveTsApi.masked(token)
                    + ": the program output of room " + roomId + " could not start", e);
            response.error(503, "output_unavailable", "The program output is not available right now");
            return null;
        }
        room.segmenter.attach(packager); // no-op if it already follows this packager
        return room;
    }

    void playlist(Request request, Response response) throws Exception {
        try (LiveLimits.Slot slot = admit(request)) {
            sendPlaylist(request, response);
        }
    }

    private void sendPlaylist(Request request, Response response) throws Exception {
        Room room = open(request, response);
        if (room == null) {
            return;
        }
        if (!room.segmenter.awaitSegments(1, FIRST_SEGMENT_WAIT_MS)) {
            response.header("Retry-After", "1");
            response.header("Cache-Control", "no-cache");
            response.error(503, "output_starting", "The program output is starting");
            return;
        }
        response.status(200);
        response.header("Cache-Control", "no-cache");
        response.raw().setContentType(PLAYLIST_TYPE);
        response.raw().send(room.segmenter.playlist().getBytes(StandardCharsets.UTF_8));
    }

    void segment(Request request, Response response) throws Exception {
        try (LiveLimits.Slot slot = admit(request)) {
            sendSegment(request, response);
        }
    }

    private void sendSegment(Request request, Response response) throws Exception {
        String file = request.pathParam("file");
        if (file == null || !SEGMENT_FILE.matcher(file).matches()) {
            throw HttpError.notFound();
        }
        long sequence = Long.parseLong(file.substring(0, file.length() - 3));
        Room room = open(request, response);
        if (room == null) {
            return;
        }
        HlsSegmenter.Segment seg = room.segmenter.segment(sequence);
        if (seg == null) {
            throw HttpError.notFound();
        }
        response.status(200);
        response.header("Cache-Control", "no-cache");
        response.raw().setContentType(SEGMENT_TYPE);
        response.raw().send(seg.ts());
    }
}
