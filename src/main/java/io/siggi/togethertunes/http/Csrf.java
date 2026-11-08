package io.siggi.togethertunes.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * The CSRF check from {@code docs/auth.md}: the {@code Origin} header must be the origin of
 * {@code http.publicBaseUrl} for state-changing cookie-authenticated requests and for WebSocket
 * upgrades.
 *
 * <p>Rules for a state-changing request (any method but {@code GET}, {@code HEAD} and
 * {@code OPTIONS}), applied by {@link #hook()} before any handler runs:
 * <ul>
 *   <li>With an {@code Authorization: Bearer} header the request is never cookie-authenticated
 *       (the actor resolver ignores cookies then), and a page on another site can't add that
 *       header without a CORS preflight, which we never grant. Not checked.</li>
 *   <li>Otherwise, an {@code Origin} header that is present must match. Browsers send
 *       {@code Origin} on every cross-origin request that isn't a GET or HEAD, so this refuses all
 *       cross-site writes, including ones that ride on ambient credentials other than cookies
 *       (the client IP in guest IP mode) and login CSRF (no cookie yet). {@code Origin: null}
 *       never matches.</li>
 *   <li>A request without {@code Origin} is refused if it carries one of the credential cookies
 *       ({@code sid}, {@code gid}): it may be cookie-authenticated, and current browsers always
 *       send {@code Origin} on such requests, so only a non-browser client can omit it (and
 *       such a client can send it, or use a bearer token). A request without {@code Origin} and
 *       without those cookies can't come from a browser page riding on cookies, so it passes.</li>
 * </ul>
 *
 * <p>Checking cookie <em>presence</em> rather than the resolved actor is deliberately stricter:
 * it needs no database lookup, and it also covers a stale {@code sid} next to a valid
 * {@code gid}. WebSocket upgrades are checked the same way by {@link #checkUpgrade} (a GET, but
 * the socket carries the cookies' identity).
 *
 * <p>Refusals are {@code 403 origin_not_allowed}.
 */
public final class Csrf {
    private static final System.Logger LOG = System.getLogger(Csrf.class.getName());

    private final String origin;
    private final Set<String> credentialCookies;

    /**
     * @param publicBaseUrl     {@code http.publicBaseUrl}; its scheme, host and port form the only
     *                          allowed {@code Origin}
     * @param credentialCookies cookies that authenticate a request ({@code sid}, {@code gid})
     */
    public Csrf(String publicBaseUrl, Collection<String> credentialCookies) {
        this.origin = originOf(publicBaseUrl);
        this.credentialCookies = Set.copyOf(credentialCookies);
        if (this.credentialCookies.isEmpty()) {
            throw new IllegalArgumentException("credentialCookies");
        }
    }

    /**
     * The origin ({@code scheme://host[:port]}, lower case, default port left out) of a URL such
     * as {@code https://Tunes.example.com/prefix}.
     *
     * @throws IllegalArgumentException if it isn't an absolute http(s) URL with a host
     */
    public static String originOf(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an absolute http(s) URL with a host: " + url);
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || scheme.equals("http") && port == 80
                || scheme.equals("https") && port == 443;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    /** The only accepted {@code Origin}. */
    public String origin() {
        return origin;
    }

    /** Whether {@code method} may change state (everything but GET, HEAD and OPTIONS). */
    public static boolean stateChanging(String method) {
        String m = method.toUpperCase(Locale.ROOT);
        return !m.equals("GET") && !m.equals("HEAD") && !m.equals("OPTIONS");
    }

    /** The router-wide hook: {@link #check} for every request. */
    public Hook hook() {
        return (request, response, next) -> {
            check(request);
            next.handle(request, response);
        };
    }

    /**
     * Checks a plain HTTP request (see the class comment).
     *
     * @throws HttpError 403 {@code origin_not_allowed}
     */
    public void check(Request request) {
        if (!stateChanging(request.method()) || hasBearer(request)) {
            return;
        }
        verify(request);
    }

    /**
     * Checks a WebSocket upgrade: {@code Origin} must match if present, and must be present if
     * the request carries a credential cookie.
     *
     * @throws HttpError 403 {@code origin_not_allowed}
     */
    public void checkUpgrade(Request request) {
        verify(request);
    }

    private void verify(Request request) {
        String sent = request.header("Origin");
        if (sent == null) {
            if (hasCredentialCookie(request)) {
                refuse(request, "no Origin, but a credential cookie");
            }
            return;
        }
        if (!matches(sent)) {
            refuse(request, "Origin " + quote(sent) + " is not " + origin);
        }
    }

    /**
     * Whether {@code sent} (an {@code Origin} header value) is the allowed origin. Browsers send
     * it serialised (lower-case scheme and host, no default port, no path), so this is an exact
     * comparison, ignoring case only; {@code null} and anything else never match.
     */
    boolean matches(String sent) {
        return sent.strip().equalsIgnoreCase(origin);
    }

    private boolean hasCredentialCookie(Request request) {
        for (String name : credentialCookies) {
            if (request.cookie(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBearer(Request request) {
        String authorization = request.header("Authorization");
        if (authorization == null) {
            return false;
        }
        String value = authorization.strip();
        int space = value.indexOf(' ');
        String scheme = space < 0 ? value : value.substring(0, space);
        return scheme.equalsIgnoreCase("bearer");
    }

    private static void refuse(Request request, String why) {
        LOG.log(System.Logger.Level.INFO, request.method() + " " + request.path() + " refused (CSRF): " + why);
        throw new HttpError(403, "origin_not_allowed", "Origin not allowed");
    }

    private static String quote(String value) {
        String v = value.length() > 200 ? value.substring(0, 200) + "..." : value;
        return '"' + v.replaceAll("\\p{Cntrl}", "?") + '"';
    }
}
