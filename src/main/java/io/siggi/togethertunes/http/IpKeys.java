package io.siggi.togethertunes.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Normalised per-client IP keys: IPv4 uses the full address, IPv6 its /64 prefix (privacy
 * extensions rotate the interface ID, so one client has a whole /64). IPv4-mapped IPv6 addresses
 * ({@code ::ffff:a.b.c.d}) count as IPv4. Used for IP-mode guests ({@code docs/auth.md}) and as
 * the key of per-IP rate limits ({@link RateLimiter}).
 */
public final class IpKeys {
    private IpKeys() {
    }

    /**
     * The normalised key, e.g. {@code 203.0.113.7} or {@code 2001:db8:1:2::/64}, or {@code null}
     * if {@code ip} is not an IP address literal. Never does a DNS lookup.
     */
    public static String ipKey(String ip) {
        InetAddress address = parseLiteral(ip);
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
            }
            return sb.append(":/64").toString();
        }
        return null;
    }

    private static InetAddress parseLiteral(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.strip();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean hasColon = s.indexOf(':') >= 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.' || c == ':'
                    || (hasColon && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
            if (!ok) {
                return null; // not a literal: never let getByName resolve a host name
            }
        }
        if (!hasColon && !s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return null; // getByName accepts shorthand like "127.1"; we don't
        }
        if (!hasColon) {
            for (String octet : s.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
