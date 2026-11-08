package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.IpKeys;

/**
 * The key of an IP-mode guest ({@code docs/auth.md#identity-assignment}): IPv4 uses the full
 * address, IPv6 its /64 prefix (privacy extensions rotate the interface ID). IPv4-mapped IPv6
 * addresses ({@code ::ffff:a.b.c.d}) count as IPv4. {@code guests.key_hash} is
 * {@link Tokens#hash} of the normalised key.
 */
public final class GuestKeys {
    private GuestKeys() {
    }

    /**
     * The normalised key, e.g. {@code 203.0.113.7} or {@code 2001:db8:1:2::/64}, or {@code null}
     * if {@code ip} is not an IP address literal ({@link IpKeys#ipKey}).
     */
    public static String ipKey(String ip) {
        return IpKeys.ipKey(ip);
    }

    /** {@link Tokens#hash} of {@link #ipKey}, or {@code null} if {@code ip} is not an IP address. */
    public static String ipKeyHash(String ip) {
        String key = ipKey(ip);
        return key == null ? null : Tokens.hash(key);
    }
}
