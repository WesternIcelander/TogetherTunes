package io.siggi.togethertunes.http;

/**
 * A step in a route's hook chain (authentication, CSRF check, rate limiting, …).
 *
 * <p>SiggiHTTP has no per-route hooks, so the {@link Router} runs them: first the router-wide
 * hooks, then the route's own, then the {@link Handler}. A hook either calls {@code next} to
 * continue, throws {@link HttpError} to reject the request, or writes a response itself and does
 * not call {@code next}. It may also do work after {@code next} returns.
 */
@FunctionalInterface
public interface Hook {
    void handle(Request request, Response response, Handler next) throws Exception;
}
