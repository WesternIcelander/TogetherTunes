package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The rules for custom guest names ({@code docs/auth.md#names}).
 *
 * <ol>
 *   <li><b>Form</b> ({@link #normalise}): NFKC-normalised (so full-width or styled letters become
 *       plain ones), whitespace trimmed and collapsed to single spaces, {@value #MIN_LENGTH} to
 *       {@value #MAX_LENGTH} code points. Allowed: letters of any script (with combining marks,
 *       at most {@value #MAX_MARKS} after one base character), decimal digits, spaces and the basic
 *       punctuation {@value #PUNCTUATION}; at least one letter or digit. Otherwise 400
 *       {@code invalid_name}.</li>
 *   <li><b>Accounts</b>: rejected with 409 {@code name_taken} if its {@link #key} equals the key of
 *       any registered username or display name (disabled accounts included), so guests can't
 *       impersonate accounts.</li>
 *   <li><b>Blocklist</b> ({@code guests.nameBlocklist}): rejected with 400 {@code name_blocked}
 *       if its {@link #compact} form contains the compact form of any entry. Substring matching
 *       also catches "x y z" or "x.y.z" for an entry "xyz", so entries should be specific
 *       enough not to hit innocent names.</li>
 * </ol>
 * Generated names are not checked: they come from the bundled word lists.
 */
public final class GuestNameRules {
    /** Shortest custom guest name, in code points (after normalisation). */
    public static final int MIN_LENGTH = 2;
    /** Longest custom guest name, in code points (after normalisation). */
    public static final int MAX_LENGTH = 32;
    /** The punctuation allowed besides letters, digits and spaces. */
    public static final String PUNCTUATION = ".,'\u2019-_!?&():;#+~";
    /** Most combining marks allowed on one base character (stops "Zalgo" text). */
    public static final int MAX_MARKS = 3;

    private GuestNameRules() {
    }

    /**
     * The stored form of a custom name (see the class comment), or 400 {@code invalid_name}.
     */
    public static String normalise(String name) {
        String n = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").strip();
        int length = n.codePointCount(0, n.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw invalid();
        }
        boolean letterOrDigit = false;
        int marks = 0;
        int previous = -1;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                letterOrDigit = true;
                marks = 0;
            } else if (isMark(cp)) {
                // Only on a letter, digit or another mark, and not too many.
                boolean onBase = previous != -1 && (isMark(previous) || Character.isLetter(previous)
                        || Character.getType(previous) == Character.DECIMAL_DIGIT_NUMBER);
                if (!onBase || ++marks > MAX_MARKS) {
                    throw invalid();
                }
            } else if (cp == ' ' || PUNCTUATION.indexOf(cp) >= 0) {
                marks = 0;
            } else {
                throw invalid();
            }
            previous = cp;
        }
        if (!letterOrDigit) {
            throw invalid();
        }
        return n;
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static HttpError invalid() {
        return new HttpError(400, "invalid_name", "Guest names are " + MIN_LENGTH + " to " + MAX_LENGTH
                + " characters: letters, digits, spaces and " + PUNCTUATION);
    }

    /**
     * The comparison key of a name: NFKC, typographic apostrophes as {@code '}, whitespace trimmed
     * and collapsed, case folded (upper- then lower-cased in {@link Locale#ROOT}, so e.g. "\u00df"
     * matches "ss"), and NFKC again.
     */
    public static String key(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replaceAll("(?U)\\s+", " ").strip();
        n = n.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(n, Normalizer.Form.NFKC);
    }

    /** {@link #key} with only letters, digits and combining marks kept (for the blocklist). */
    public static String compact(String name) {
        StringBuilder out = new StringBuilder();
        key(name).codePoints()
                .filter(cp -> Character.isLetterOrDigit(cp) || isMark(cp))
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Whether {@code name} contains a blocklist entry (see the class comment). */
    public static boolean blocked(String name, List<String> blocklist) {
        String compactName = compact(name);
        for (String entry : blocklist) {
            String compactEntry = compact(entry);
            if (!compactEntry.isEmpty() && compactName.contains(compactEntry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code name} matches (by {@link #key}) a registered username or display name. Scans
     * the {@code users} table: SQL can't apply NFKC, and a self-hosted server has few accounts.
     */
    public static boolean clashesWithAccount(Database db, String name) throws SQLException {
        String key = key(name);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT username, display_name FROM users");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (key.equals(key(rs.getString(1))) || key.equals(key(rs.getString(2)))) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /**
     * {@link #normalise}, then the account and blocklist checks.
     *
     * @return the name to store
     * @throws HttpError 400 {@code invalid_name}, 409 {@code name_taken}, 400 {@code name_blocked}
     */
    public static String check(Database db, List<String> blocklist, String name) throws SQLException {
        Objects.requireNonNull(blocklist);
        String n = normalise(name);
        if (clashesWithAccount(db, n)) {
            throw new HttpError(409, "name_taken", "That name belongs to an account; choose another");
        }
        if (blocked(n, blocklist)) {
            throw new HttpError(400, "name_blocked", "That name isn't allowed here; choose another");
        }
        return n;
    }
}
