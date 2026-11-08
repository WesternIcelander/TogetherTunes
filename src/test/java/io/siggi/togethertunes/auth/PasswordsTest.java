package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PasswordsTest {
    private static final Passwords CHEAP = new Passwords(new Passwords.Params(64, 1, 1));

    @Test
    void defaultParametersAreTheDocumentedOnes() {
        String hash = Passwords.DEFAULT.hash("correct horse battery");
        assertTrue(Pattern.matches("\\$argon2id\\$v=19\\$m=65536,t=3,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}", hash), hash);
        assertTrue(Passwords.DEFAULT.verify("correct horse battery", hash));
        assertFalse(Passwords.DEFAULT.verify("correct horse batterY", hash));
        assertFalse(Passwords.DEFAULT.needsRehash(hash));
    }

    @Test
    void roundTripWithFreshSalt() {
        String a = CHEAP.hash("pässwörd 🎵 long enough");
        String b = CHEAP.hash("pässwörd 🎵 long enough");
        assertNotEquals(a, b);
        assertTrue(CHEAP.verify("pässwörd 🎵 long enough", a));
        assertTrue(CHEAP.verify("pässwörd 🎵 long enough", b));
        assertFalse(CHEAP.verify("passwörd 🎵 long enough", a));
        assertFalse(CHEAP.verify("", a));
        assertFalse(CHEAP.verify(null, a));
    }

    @Test
    void verifiesWithTheParametersInTheHash() {
        String old = new Passwords(new Passwords.Params(32, 2, 1)).hash("0123456789");
        assertTrue(CHEAP.verify("0123456789", old));
        assertTrue(CHEAP.needsRehash(old));
        assertFalse(CHEAP.needsRehash(CHEAP.hash("0123456789")));
    }

    /** Hashes made by the reference implementation's CLI ({@code argon2 <salt> -id ... -e}) verify. */
    @Test
    void verifiesReferenceImplementationHashes() {
        // echo -n password | argon2 somesalt -id -t 2 -m 16 -p 4 -l 24 -e
        String a = "$argon2id$v=19$m=65536,t=2,p=4$c29tZXNhbHQ$F1jG2CV3/Nr+yRuIsPKw0J9r4s7cJHBU";
        assertTrue(CHEAP.verify("password", a));
        assertFalse(CHEAP.verify("Password", a));
        assertTrue(Passwords.DEFAULT.needsRehash(a));
        // echo -n "pässwörd 🎵 long enough" | argon2 saltsaltsalt1234 -id -t 3 -m 16 -p 1 -l 32 -e
        String b = "$argon2id$v=19$m=65536,t=3,p=1$c2FsdHNhbHRzYWx0MTIzNA$BoDPxpQRwGyRsAIqUA9WBKgOsFb8f0BJp0k1ffe375s";
        assertTrue(Passwords.DEFAULT.verify("pässwörd 🎵 long enough", b));
        assertFalse(Passwords.DEFAULT.needsRehash(b));
    }

    @Test
    void malformedHashesNeverMatch() {
        String good = CHEAP.hash("0123456789");
        for (String bad : new String[] {null, "", "plain", "$argon2i$v=19$m=64,t=1,p=1$c29tZXNhbHQ$" + good.substring(good.lastIndexOf('$') + 1),
                "$argon2id$v=19$m=64,t=1$c29tZXNhbHQ$AAAAAAAAAAAAAAAAAAAAAA",
                "$argon2id$v=19$m=64,t=1,p=1$c29tZXNhbHQ",
                "$argon2id$v=19$m=x,t=1,p=1$c29tZXNhbHQ$AAAAAAAAAAAAAAAAAAAAAA",
                "$argon2id$v=19$m=99999999,t=1,p=1$c29tZXNhbHQ$AAAAAAAAAAAAAAAAAAAAAA",
                "$argon2id$v=19$m=64,t=1,p=1$!!!$AAAAAAAAAAAAAAAAAAAAAA"}) {
            assertFalse(CHEAP.verify("0123456789", bad), bad);
            assertTrue(CHEAP.needsRehash(bad), bad);
        }
    }

    @Test
    void lengthRules() {
        assertNotNull(Passwords.problem(null));
        assertNotNull(Passwords.problem("123456789"));
        assertNull(Passwords.problem("1234567890"));
        assertNotNull(Passwords.problem("🎵".repeat(9)), "counts code points, not UTF-16 units");
        assertNull(Passwords.problem("🎵".repeat(10)));
        assertNull(Passwords.problem("x".repeat(1024)));
        assertNotNull(Passwords.problem("x".repeat(1025)));
    }

    @Test
    void badParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Passwords.Params(64, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Passwords.Params(4, 1, 1));
    }
}
