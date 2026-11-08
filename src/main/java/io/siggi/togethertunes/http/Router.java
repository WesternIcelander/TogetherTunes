package io.siggi.togethertunes.http;

import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPResponder;
import io.siggi.http.exception.EndResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Routes SiggiHTTP requests to {@link Handler}s by method and {@link PathPattern}, running each
 * route's hook chain. Registered as SiggiHTTP's responder for {@code /} (all paths), so it answers
 * every request itself:
 *
 * <ul>
 *   <li>Among the routes whose pattern matches the path and whose method matches, the most specific
 *       pattern wins ({@link PathPattern#compareTo}). {@code HEAD} runs the {@code GET} route
 *       (SiggiHTTP drops the body).</li>
 *   <li>No pattern matches: {@code 404}. Patterns match but no method does: {@code 405} with
 *       {@code Allow}, or for {@code OPTIONS} a {@code 204} with {@code Allow}.</li>
 *   <li>{@link HttpError} becomes the JSON error body; any other exception is logged and becomes a
 *       {@code 500}. If the response was already committed, the exception is passed on to
 *       SiggiHTTP, which closes the connection.</li>
 *   <li>A chain that writes nothing (and doesn't {@link Response#detach}) gets a {@code 404}.</li>
 * </ul>
 */
public final class Router implements HTTPResponder {
    private static final System.Logger LOG = System.getLogger(Router.class.getName());
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final List<Hook> globalHooks;
    private final List<Route> routes = new CopyOnWriteArrayList<>();

    /**
     * @param globalHooks hooks run for every route, in this order, before the route's own hooks
     *                    (normally {@code ActorResolver.hook()} then {@link Hooks#csrf()})
     */
    public Router(Hook... globalHooks) {
        this.globalHooks = List.of(globalHooks);
    }

    public Router get(String pattern, Handler handler, Hook... hooks) {
        return add("GET", pattern, handler, hooks);
    }

    public Router post(String pattern, Handler handler, Hook... hooks) {
        return add("POST", pattern, handler, hooks);
    }

    public Router put(String pattern, Handler handler, Hook... hooks) {
        return add("PUT", pattern, handler, hooks);
    }

    public Router patch(String pattern, Handler handler, Hook... hooks) {
        return add("PATCH", pattern, handler, hooks);
    }

    public Router delete(String pattern, Handler handler, Hook... hooks) {
        return add("DELETE", pattern, handler, hooks);
    }

    /**
     * Registers a route. {@code hooks} run after the router-wide hooks, in order.
     *
     * @throws IllegalArgumentException for an unknown method, a malformed pattern, or a route that
     *                                  duplicates an existing one
     */
    public synchronized Router add(String method, String pattern, Handler handler, Hook... hooks) {
        String m = method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) {
            throw new IllegalArgumentException("Unsupported method " + method + " (HEAD uses the GET route)");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        PathPattern parsed = PathPattern.parse(pattern);
        for (Route r : routes) {
            if (r.method().equals(m) && r.pattern().sameShape(parsed)) {
                throw new IllegalArgumentException(m + " " + pattern + " clashes with " + r.method() + " " + r.pattern());
            }
        }
        List<Hook> routeHooks = List.of(hooks);
        List<Hook> all = new ArrayList<>(globalHooks);
        all.addAll(routeHooks);
        routes.add(new Route(m, parsed, routeHooks, handler, compose(all, handler)));
        return this;
    }

    private static Handler compose(List<Hook> hooks, Handler handler) {
        Handler next = handler;
        for (int i = hooks.size() - 1; i >= 0; i--) {
            Hook hook = hooks.get(i);
            Handler after = next;
            next = (request, response) -> hook.handle(request, response, after);
        }
        return next;
    }

    @Override
    public void respond(HTTPRequest raw) throws Exception {
        String path = raw.path();
        String method = raw.method();
        String lookup = method.equals("HEAD") ? "GET" : method;

        Route best = null;
        Map<String, String> bestParams = null;
        Set<String> allowed = new TreeSet<>();
        for (Route route : routes) {
            Map<String, String> params = route.pattern().match(path);
            if (params == null) {
                continue;
            }
            allowed.add(route.method());
            if (route.method().equals(lookup) && (best == null || route.pattern().compareTo(best.pattern()) < 0)) {
                best = route;
                bestParams = params;
            }
        }

        Request request = new Request(raw, best == null ? Map.of() : bestParams);
        Response response = new Response(raw);
        try {
            if (best == null) {
                noRoute(method, allowed, response);
                return;
            }
            best.chain().handle(request, response);
            if (!response.committed() && !response.detached()) {
                response.error(HttpError.notFound());
            }
        } catch (EndResponse e) {
            throw e;
        } catch (HttpError e) {
            if (response.committed() || response.detached()) {
                throw new IOException("HTTP " + e.status() + " " + e.code() + " after the response was committed", e);
            }
            response.error(e);
        } catch (Exception e) {
            if (response.committed() || response.detached()) {
                throw e; // SiggiHTTP logs it and closes the connection
            }
            LOG.log(System.Logger.Level.ERROR, "Error handling " + method + " " + path, e);
            response.error(500, "internal_error", "Internal server error");
        }
    }

    private static void noRoute(String method, Set<String> allowed, Response response) throws IOException {
        if (allowed.isEmpty()) {
            response.error(HttpError.notFound());
            return;
        }
        if (allowed.contains("GET")) {
            allowed.add("HEAD");
        }
        allowed.add("OPTIONS");
        String allow = String.join(", ", allowed);
        if (method.equals("OPTIONS")) {
            response.header("Allow", allow);
            response.empty(204);
        } else {
            response.header("Allow", allow);
            response.error(405, "method_not_allowed", "Method " + method + " is not allowed here");
        }
    }
}
