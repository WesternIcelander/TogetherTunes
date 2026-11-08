package io.siggi.togethertunes.settings;

import java.util.Locale;

/** How guests are identified ({@code guests.identifyBy}, {@code docs/auth.md#guests}). */
public enum GuestIdentity {
    /** A random token in the {@code gid} cookie. */
    COOKIE,
    /** The client IP (IPv6: its /64 prefix). */
    IP;

    /** The stored form: {@code cookie} or {@code ip}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
