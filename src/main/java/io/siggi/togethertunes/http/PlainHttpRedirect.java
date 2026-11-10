package io.siggi.togethertunes.http;

import java.util.Objects;

/**
 * The plain-HTTP gate ({@code http.insecureBaseUrl}, {@code docs/architecture.md#plain-http}): every
 * request that arrived over plain HTTP ({@link Request#isSecure()} false: the connection, or
 * {@code X-Forwarded-Proto} from a trusted proxy) gets a {@code 301} to the same path and query on
 * {@code http.publicBaseUrl} with an empty body, unless {@link Exemption} says it may be served over
 * HTTP (public and unlisted rooms' stream URLs, for players without TLS). Pages, {@code /api/*},
 * {@code /media/*}, WebSocket upgrades, unknown paths and private rooms' token stream URLs all
 * redirect. Requests over HTTPS pass through untouched.
 *
 * <p>Installed only when {@code http.insecureBaseUrl} is set and {@code http.publicBaseUrl} is
 * {@code https://} ({@code Config.redirectPlainHttp()}); with an {@code http://} public URL (local
 * testing) nothing is ever redirected. The {@code Location} may carry a private room's stream token
 * (the client sent it already); it is never logged (invariant 6).
 */
public final class PlainHttpRedirect implements Router.Filter {
    private static final System.Logger LOG = System.getLogger(PlainHttpRedirect.class.getName());

    /** Whether a plain-HTTP request may be served as normal instead of redirected. */
    @FunctionalInterface
    public interface Exemption {
        boolean servedOverHttp(Request request) throws Exception;
    }

    private final String publicBaseUrl;
    private final Exemption exemption;

    /**
     * @param publicBaseUrl {@code http.publicBaseUrl} ({@code https://}), the redirect target
     * @param exemption     the requests served over plain HTTP; a failure counts as "not exempt"
     */
    public PlainHttpRedirect(String publicBaseUrl, Exemption exemption) {
        String base = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        if (!base.startsWith("https://")) {
            throw new IllegalArgumentException("The redirect target must be https://");
        }
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.exemption = Objects.requireNonNull(exemption, "exemption");
    }

    @Override
    public boolean handled(Request request, Response response) throws Exception {
        if (request.isSecure()) {
            return false;
        }
        boolean exempt;
        try {
            exempt = exemption.servedOverHttp(request);
        } catch (Exception e) {
            // No path in the message: it may hold a stream token.
            LOG.log(System.Logger.Level.WARNING, "Plain-HTTP check failed, redirecting: " + e);
            exempt = false;
        }
        if (exempt) {
            return false;
        }
        response.header("Location", location(request.rawTarget()));
        response.empty(301);
        return true;
    }

    /** The HTTPS URL for a request target ({@code /path?query}, as sent). */
    String location(String rawTarget) {
        String target = rawTarget == null || !rawTarget.startsWith("/") ? "/" : rawTarget;
        // The request line can't hold CR, LF or spaces, but be strict about what goes in a header.
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= ' ' || c >= 0x7f) {
                return publicBaseUrl + "/";
            }
        }
        return publicBaseUrl + target;
    }
}
