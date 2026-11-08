package io.siggi.togethertunes.http;

import io.siggi.http.HTTPResponse;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An endless (or open-ended) response body, from {@link Response#stream}. Every write goes to the
 * client straight away (the response is unbuffered and each write is flushed), so a client that has
 * left surfaces as an {@link IOException} from {@code write} within a few writes; on loopback that
 * takes tens of milliseconds. Letting that exception propagate out of the handler is fine:
 * SiggiHTTP recognises the failed write and ends the connection without logging.
 *
 * <p>For a {@code HEAD} request the headers have already been sent when this is returned,
 * {@link #headOnly()} is true and writes are discarded, so the producer should stop rather than
 * loop forever.
 *
 * <p>Writes come from one thread at a time. {@link #close()} may be called from another thread
 * only when the response was {@linkplain Response#detach() detached}.
 */
public final class StreamBody extends OutputStream {
    /** How the end of the body is marked. */
    public enum Framing {
        /** {@code Transfer-Encoding: chunked}; the connection can be reused afterwards. */
        CHUNKED,
        /**
         * Neither {@code Content-Length} nor {@code Transfer-Encoding}: the body ends when the
         * connection closes ({@code Connection: close}). For raw {@code .ts} streams, like VRCDN.
         */
        CLOSE_DELIMITED
    }

    private final HTTPResponse raw;
    private final boolean headOnly;

    StreamBody(HTTPResponse raw, boolean headOnly) {
        this.raw = raw;
        this.headOnly = headOnly;
    }

    /** True for a {@code HEAD} request: the headers are sent and nothing more will be. */
    public boolean headOnly() {
        return headOnly;
    }

    @Override
    public void write(int b) throws IOException {
        raw.write(b);
        raw.flush();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        raw.write(b, off, len);
        raw.flush();
    }

    @Override
    public void flush() throws IOException {
        raw.flush();
    }

    /**
     * Ends the body. Not needed when the handler simply returns; needed (from any thread) for a
     * detached response. For {@link Framing#CLOSE_DELIMITED} this closes the connection.
     */
    @Override
    public void close() throws IOException {
        raw.close();
    }
}
