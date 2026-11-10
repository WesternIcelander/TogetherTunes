package io.siggi.togethertunes.output;

import io.siggi.togethertunes.http.Request;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notices private rooms' stream tokens arriving over plain HTTP
 * ({@code docs/program-output.md#plain-http}): the stream endpoints ({@link LiveTsApi},
 * {@link LiveMp3Api}, {@link LiveHlsApi}) call {@link #presented} once a URL key has resolved to an
 * output, and the first time a private room's token comes in over plain HTTP
 * ({@link Request#isSecure()} false) {@link StreamOutputs#markTokenCleartext} records it, so the
 * outputs panel can warn the owner. Slug URLs (public and unlisted rooms) have no token and record
 * nothing (the update matches only a private room's current token). The event is logged with the
 * room and output, never the token (invariant 6).
 *
 * <p>Keys already handled are remembered in memory, so HLS (a request every couple of seconds)
 * costs one database write per token and server run, not one per request. A new token (Rotate, a
 * room becoming private) is a new key, so its first plain-HTTP use is recorded again.
 */
public final class CleartextTokens {
    private static final System.Logger LOG = System.getLogger(CleartextTokens.class.getName());

    /** Records nothing (tests, or no database). */
    public static final CleartextTokens NONE = new CleartextTokens(null);

    /** Where the time is stored: {@link StreamOutputs#markTokenCleartext}. */
    @FunctionalInterface
    public interface Store {
        /** @return the output if this call recorded it, else null */
        StreamOutputs.Output mark(StreamOutputs.Kind kind, String token) throws SQLException;
    }

    private final Store store;
    private final Set<String> handled = ConcurrentHashMap.newKeySet();

    public CleartextTokens(Store store) {
        this.store = store;
    }

    /** From the {@code stream_outputs} rows. */
    public static CleartextTokens from(StreamOutputs outputs) {
        Objects.requireNonNull(outputs, "outputs");
        return new CleartextTokens(outputs::markTokenCleartext);
    }

    /**
     * A request with URL key {@code key} reached the {@code kind} endpoint and the key opened an
     * output. Records the token's first plain-HTTP use if it is a private room's token and the
     * request came over plain HTTP; never fails the request.
     */
    public void presented(Request request, StreamOutputs.Kind kind, String key) {
        if (store == null || request.isSecure()) {
            return;
        }
        presented(kind, key);
    }

    /** {@link #presented(Request, StreamOutputs.Kind, String)} for a request known to be plain HTTP. */
    void presented(StreamOutputs.Kind kind, String key) {
        if (store == null || key == null) {
            return;
        }
        String id = kind.wire() + "/" + key;
        if (!handled.add(id)) {
            return;
        }
        try {
            StreamOutputs.Output o = store.mark(kind, key);
            if (o != null) {
                LOG.log(System.Logger.Level.INFO, "Stream token of the " + kind.wire() + " output " + o.id()
                        + " of room " + o.roomId() + " was used over plain HTTP; the outputs panel now warns about it");
            }
        } catch (SQLException | RuntimeException e) {
            handled.remove(id); // try again on the next request
            LOG.log(System.Logger.Level.WARNING, "Could not record a stream token's plain-HTTP use (" + kind.wire()
                    + " output): " + e);
        }
    }
}
