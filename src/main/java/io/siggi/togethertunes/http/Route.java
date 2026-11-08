package io.siggi.togethertunes.http;

import java.util.List;

/**
 * A registered route: method, path pattern, the route's own hooks and the handler. {@code chain}
 * is the router-wide hooks, then {@code hooks}, then {@code handler}, composed once at
 * registration.
 */
record Route(String method, PathPattern pattern, List<Hook> hooks, Handler handler, Handler chain) {
}
