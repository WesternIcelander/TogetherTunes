package io.siggi.togethertunes.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Local password hashing with Argon2id ({@code docs/auth.md#local-passwords}), using Bouncy
 * Castle's {@link Argon2BytesGenerator}. Hashes are stored as the standard PHC string, e.g.
 * {@code $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>} (salt and hash in base64 without padding),
 * so the parameters travel with the hash and can be raised later ({@link #needsRehash}).
 *
 * <p>Thread-safe. Hashing at the default parameters takes 64 MiB and a noticeable fraction of a
 * second, so callers hash outside database transactions.
 */
public final class Passwords {
    /** Minimum password length in characters (code points). No composition rules. */
    public static final int MIN_LENGTH = 10;
    /** Maximum password length in characters, so a request can't make us hash megabytes. */
    public static final int MAX_LENGTH = 1024;

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final String PREFIX = "$argon2id$v=19$";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    /**
     * Argon2id cost parameters.
     *
     * @param memoryKiB   memory in KiB
     * @param iterations  passes over the memory
     * @param parallelism lanes
     */
    public record Params(int memoryKiB, int iterations, int parallelism) {
        public Params {
            if (parallelism < 1 || iterations < 1 || memoryKiB < 8 * parallelism) {
                throw new IllegalArgumentException("Bad Argon2 parameters m=" + memoryKiB + " t=" + iterations
                        + " p=" + parallelism);
            }
        }
    }

    /** The parameters from {@code docs/auth.md}: m=64 MiB, t=3, p=1. */
    public static final Params DEFAULT_PARAMS = new Params(64 * 1024, 3, 1);

    /** Hashing with {@link #DEFAULT_PARAMS}. */
    public static final Passwords DEFAULT = new Passwords(DEFAULT_PARAMS);

    private final Params params;

    public Passwords(Params params) {
        this.params = Objects.requireNonNull(params);
    }

    public Params params() {
        return params;
    }

    /**
     * Why {@code password} is not acceptable as a new password, or {@code null} if it is. Only the
     * length is checked.
     */
    public static String problem(String password) {
        if (password == null) {
            return "A password is required";
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            return "The password must be at least " + MIN_LENGTH + " characters long";
        }
        if (length > MAX_LENGTH) {
            return "The password must be at most " + MAX_LENGTH + " characters long";
        }
        return null;
    }

    /** Hashes {@code password} with a fresh random salt and returns the PHC string. */
    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, params, HASH_BYTES);
        return PREFIX + "m=" + params.memoryKiB() + ",t=" + params.iterations() + ",p=" + params.parallelism()
                + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(hash);
    }

    /**
     * Whether {@code password} matches {@code encoded}. A malformed or non-Argon2id
     * {@code encoded} never matches. The comparison runs in constant time.
     */
    public boolean verify(String password, String encoded) {
        Parsed parsed = Parsed.of(encoded);
        if (parsed == null || password == null) {
            return false;
        }
        byte[] actual = derive(password, parsed.salt(), parsed.params(), parsed.hash().length);
        return MessageDigest.isEqual(actual, parsed.hash());
    }

    /**
     * Whether {@code encoded} should be replaced by a fresh {@link #hash} after a successful
     * login: its parameters differ from this instance's, or it can't be parsed.
     */
    public boolean needsRehash(String encoded) {
        Parsed parsed = Parsed.of(encoded);
        return parsed == null || !parsed.params().equals(params) || parsed.hash().length != HASH_BYTES;
    }

    private static byte[] derive(String password, byte[] salt, Params params, int length) {
        Argon2Parameters argon = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB())
                .withIterations(params.iterations())
                .withParallelism(params.parallelism())
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(argon);
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[length];
        try {
            generator.generateBytes(secret, out);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        return out;
    }

    /** A parsed PHC string. */
    private record Parsed(Params params, byte[] salt, byte[] hash) {
        /** Upper bounds, so a tampered hash in the database can't make verification run forever. */
        private static final int MAX_MEMORY_KIB = 1024 * 1024;
        private static final int MAX_ITERATIONS = 64;
        private static final int MAX_PARALLELISM = 16;

        static Parsed of(String encoded) {
            if (encoded == null || !encoded.startsWith(PREFIX)) {
                return null;
            }
            String[] parts = encoded.substring(PREFIX.length()).split("\\$", -1);
            if (parts.length != 3) {
                return null;
            }
            int m = -1;
            int t = -1;
            int p = -1;
            String[] fields = parts[0].split(",", -1);
            if (fields.length != 3) {
                return null;
            }
            try {
                for (String field : fields) {
                    if (field.startsWith("m=")) {
                        m = Integer.parseInt(field.substring(2));
                    } else if (field.startsWith("t=")) {
                        t = Integer.parseInt(field.substring(2));
                    } else if (field.startsWith("p=")) {
                        p = Integer.parseInt(field.substring(2));
                    } else {
                        return null;
                    }
                }
                if (m > MAX_MEMORY_KIB || t > MAX_ITERATIONS || p > MAX_PARALLELISM) {
                    return null;
                }
                Params params = new Params(m, t, p);
                byte[] salt = B64_DECODER.decode(parts[1]);
                byte[] hash = B64_DECODER.decode(parts[2]);
                if (salt.length < 8 || hash.length < 16 || hash.length > 64) {
                    return null;
                }
                return new Parsed(params, salt, hash);
            } catch (IllegalArgumentException e) { // NumberFormatException included
                return null;
            }
        }
    }
}
