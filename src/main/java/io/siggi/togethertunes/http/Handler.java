package io.siggi.togethertunes.http;

/**
 * Handles a request that the {@link Router} matched to a route, after the route's hook chain
 * let it through.
 *
 * <p>Runs on a virtual thread, so blocking I/O is fine. Throw {@link HttpError} for an error
 * response; any other exception becomes a {@code 500} (if nothing has been written yet). A
 * handler that returns without writing anything gets a {@code 404}, matching SiggiHTTP's own
 * "nothing written means not found" rule (e.g. {@code returnFile} on a missing file).
 */
@FunctionalInterface
public interface Handler {
    void handle(Request request, Response response) throws Exception;
}
