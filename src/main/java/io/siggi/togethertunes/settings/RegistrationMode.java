package io.siggi.togethertunes.settings;

import java.util.Locale;

/** Who may create a local account ({@code registration.mode}, {@code docs/auth.md#accounts}). */
public enum RegistrationMode {
    /** Anyone can register. */
    OPEN,
    /** Only with an invite. */
    INVITE,
    /** Nobody can register; admins create accounts. The first-user setup page works regardless. */
    CLOSED;

    /** The stored form: {@code open}, {@code invite} or {@code closed}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
