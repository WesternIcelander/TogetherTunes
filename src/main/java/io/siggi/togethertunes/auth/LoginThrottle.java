package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.IpKeys;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Password-login backoff per client IP and per username ({@code docs/auth.md#local-passwords}),
 * in memory.
 *
 * <p>Each IP key ({@link IpKeys#ipKey}, IPv6 by /64) and each username (case-insensitive) counts
 * failed logins. After {@link Policy#freeFailures} failures, every further failure locks that key
 * for {@link Policy#baseDelayMs} × 2<sup>n−1</sup> (n = failures over the free ones), capped at
 * {@link Policy#maxDelayMs}, counted from the latest failure. A key with no failure for
 * {@link Policy#forgetAfterMs} starts again from zero. A successful login clears its username's
 * count and takes back its own attempt from the IP's count (so the IP's earlier failures still
 * count; logging in to one account doesn't reset an IP that is guessing others).
 *
 * <p>Every attempt counts as a failure from the moment it {@linkplain #begin begins}, before the
 * password is checked, and {@link Attempt#succeeded} takes it back. So parallel attempts can't get
 * past the limit while the slow Argon2 checks run.
 *
 * <p>A locked username also refuses the right password: that lets anyone delay a known
 * username's logins by up to {@code maxDelayMs} at a time, the usual price of per-username limits.
 * Memory is bounded by {@link Policy#maxKeys} per kind (least recently used dropped first).
 * Thread-safe.
 */
public final class LoginThrottle {
    /**
     * @param freeFailures  failures before the first lockout
     * @param baseDelayMs   the first lockout
     * @param maxDelayMs    the longest lockout
     * @param forgetAfterMs a key without failures for this long is forgotten
     * @param maxKeys       most keys tracked per kind
     */
    public record Policy(int freeFailures, long baseDelayMs, long maxDelayMs, long forgetAfterMs, int maxKeys) {
        public Policy {
            if (freeFailures < 0 || baseDelayMs < 1 || maxDelayMs < baseDelayMs || forgetAfterMs < maxDelayMs
                    || maxKeys < 1) {
                throw new IllegalArgumentException("Bad login throttle policy");
            }
        }

        /** The lockout after {@code failures} failures (0 while within the free ones). */
        long delayMs(int failures) {
            int over = failures - freeFailures;
            if (over <= 0) {
                return 0;
            }
            if (over > 62 || baseDelayMs << (over - 1) >= maxDelayMs || baseDelayMs << (over - 1) <= 0) {
                return maxDelayMs;
            }
            return baseDelayMs << (over - 1);
        }
    }

    /** Per username: 5 free failures, then 1 s, 2 s, 4 s … up to 15 min. */
    public static final Policy USERNAME_POLICY = new Policy(5, 1_000, 15 * 60_000, 60 * 60_000, 100_000);
    /** Per IP: 20 free failures (shared NATs), then 1 s, 2 s, 4 s … up to 15 min. */
    public static final Policy IP_POLICY = new Policy(20, 1_000, 15 * 60_000, 60 * 60_000, 100_000);

    /** Which limit refused an attempt. */
    public enum Scope {
        IP("ip"), USERNAME("username");

        private final String json;

        Scope(String json) {
            this.json = json;
        }

        /** The value used in audit details. */
        public String json() {
            return json;
        }
    }

    /**
     * An attempt refused because its IP or username is locked.
     *
     * @param retryAfterMs ms until the lock ends
     * @param firstRefusal whether this is the first refusal of this lock (audit only that one, so
     *                     a client hammering a locked key can't flood {@code audit_log})
     */
    public record Refusal(Scope scope, long retryAfterMs, boolean firstRefusal) {
    }

    /** Thrown by {@link #begin} when the attempt is refused. */
    public static final class Locked extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient Refusal refusal;

        Locked(Refusal refusal) {
            super(null, null, false, false);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    private final Clock clock;
    private final Keys ips;
    private final Keys usernames;

    public LoginThrottle(Clock clock) {
        this(clock, IP_POLICY, USERNAME_POLICY);
    }

    public LoginThrottle(Clock clock, Policy ipPolicy, Policy usernamePolicy) {
        this.clock = Objects.requireNonNull(clock);
        this.ips = new Keys(ipPolicy);
        this.usernames = new Keys(usernamePolicy);
    }

    /**
     * Starts a login attempt: refuses it if its IP or username is locked, otherwise counts it as a
     * failure until {@link Attempt#succeeded} is called.
     *
     * @param ip       the client IP ({@code null} or a non-literal is tracked as given)
     * @param username as typed; blank means no username limit
     * @throws Locked if the IP or username is locked (nothing is counted then)
     */
    public Attempt begin(String ip, String username) throws Locked {
        String ipKey = ipKey(ip);
        String nameKey = usernameKey(username);
        long now = clock.millis();
        synchronized (this) {
            Refusal refusal = ips.refusal(ipKey, now, Scope.IP);
            if (refusal == null && nameKey != null) {
                refusal = usernames.refusal(nameKey, now, Scope.USERNAME);
            }
            if (refusal != null) {
                throw new Locked(refusal);
            }
            long ipPrevious = ips.fail(ipKey, now);
            if (nameKey != null) {
                usernames.fail(nameKey, now);
            }
            return new Attempt(ipKey, nameKey, ipPrevious, now);
        }
    }

    /** A login attempt that counts as failed unless {@link #succeeded} is called. */
    public final class Attempt {
        private final String ipKey;
        private final String nameKey;
        private final long ipPreviousFailureAt;
        private final long at;
        private boolean done;

        private Attempt(String ipKey, String nameKey, long ipPreviousFailureAt, long at) {
            this.ipKey = ipKey;
            this.nameKey = nameKey;
            this.ipPreviousFailureAt = ipPreviousFailureAt;
            this.at = at;
        }

        /** The password was right: clears the username's count and takes this attempt back from the IP. */
        public void succeeded() {
            synchronized (LoginThrottle.this) {
                if (done) {
                    return;
                }
                done = true;
                ips.undo(ipKey, ipPreviousFailureAt, at);
                if (nameKey != null) {
                    usernames.clear(nameKey);
                }
            }
        }
    }

    /** The lockout ms left for this IP now (0 if none). For tests and diagnostics. */
    public synchronized long ipLockedForMs(String ip) {
        return ips.lockedForMs(ipKey(ip), clock.millis());
    }

    /** The lockout ms left for this username now (0 if none). For tests and diagnostics. */
    public synchronized long usernameLockedForMs(String username) {
        String key = usernameKey(username);
        return key == null ? 0 : usernames.lockedForMs(key, clock.millis());
    }

    private static String ipKey(String ip) {
        String s = ip == null ? "" : ip;
        String key = IpKeys.ipKey(s);
        return key == null ? s : key;
    }

    private static String usernameKey(String username) {
        String name = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        // Longer than any valid username: all such names share one key, which is fine.
        return name.length() > Accounts.USERNAME_MAX ? name.substring(0, Accounts.USERNAME_MAX + 1) : name;
    }

    /** The failure counts of one kind of key. Guarded by the enclosing {@code LoginThrottle}. */
    private static final class Keys {
        private final Policy policy;
        private final Map<String, State> states;

        private static final class State {
            int failures;
            long lastFailureAt;
            /** The lock end that was last refused (so only its first refusal is reported as first). */
            long refusedUntil;
        }

        Keys(Policy policy) {
            this.policy = Objects.requireNonNull(policy);
            int max = policy.maxKeys();
            this.states = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
                    return size() > max;
                }
            };
        }

        /** The state, or null if none or forgotten (then removed). */
        private State live(String key, long now) {
            State s = states.get(key);
            if (s != null && now - s.lastFailureAt >= policy.forgetAfterMs()) {
                states.remove(key);
                return null;
            }
            return s;
        }

        private long lockedUntil(State s) {
            long delay = policy.delayMs(s.failures);
            return delay == 0 ? 0 : s.lastFailureAt + delay;
        }

        long lockedForMs(String key, long now) {
            State s = live(key, now);
            return s == null ? 0 : Math.max(0, lockedUntil(s) - now);
        }

        Refusal refusal(String key, long now, Scope scope) {
            State s = live(key, now);
            if (s == null) {
                return null;
            }
            long until = lockedUntil(s);
            if (until <= now) {
                return null;
            }
            boolean first = s.refusedUntil != until;
            s.refusedUntil = until;
            return new Refusal(scope, until - now, first);
        }

        /** Counts a failure; returns the previous latest-failure time. */
        long fail(String key, long now) {
            State s = live(key, now);
            if (s == null) {
                s = new State();
                states.put(key, s);
            }
            long previous = s.lastFailureAt;
            if (s.failures < Integer.MAX_VALUE) {
                s.failures++;
            }
            s.lastFailureAt = Math.max(s.lastFailureAt, now);
            return previous;
        }

        /**
         * Takes back a failure counted at {@code at}; if no later failure has come since, the
         * latest-failure time goes back to {@code previous}, so the undone attempt doesn't restart
         * the lockout clock.
         */
        void undo(String key, long previous, long at) {
            State s = states.get(key);
            if (s == null) {
                return;
            }
            if (--s.failures <= 0) {
                states.remove(key);
            } else if (s.lastFailureAt == at) {
                s.lastFailureAt = previous;
            }
        }

        void clear(String key) {
            states.remove(key);
        }
    }
}
