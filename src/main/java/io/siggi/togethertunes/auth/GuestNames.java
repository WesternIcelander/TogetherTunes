package io.siggi.togethertunes.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Draws generated guest names, {@code Adjective Noun} (e.g. "Quiet Walrus"), from the bundled word
 * lists {@value #ADJECTIVES} and {@value #NOUNS} (about 300 entries each, see
 * {@code docs/auth.md#names}). Each list is one lower-case word of ASCII letters per line; blank
 * lines and lines starting with {@code #} are skipped. Words are title-cased.
 *
 * <p>This only draws; {@link Guests} makes the name unique among active guests (redrawing, then
 * appending a number).
 */
public final class GuestNames implements Supplier<String> {
    /** Classpath resource (next to this class) of the adjectives. */
    public static final String ADJECTIVES = "guest-name-adjectives.txt";
    /** Classpath resource (next to this class) of the nouns. */
    public static final String NOUNS = "guest-name-nouns.txt";

    private static final Pattern WORD = Pattern.compile("[a-z]+");

    private final List<String> adjectives;
    private final List<String> nouns;
    private final RandomGenerator random;

    GuestNames(List<String> adjectives, List<String> nouns, RandomGenerator random) {
        if (adjectives.isEmpty() || nouns.isEmpty()) {
            throw new IllegalArgumentException("empty word list");
        }
        this.adjectives = List.copyOf(adjectives);
        this.nouns = List.copyOf(nouns);
        this.random = Objects.requireNonNull(random);
    }

    /** The bundled lists, drawn with a {@link SecureRandom}. */
    public static GuestNames bundled() {
        return bundled(new SecureRandom());
    }

    /** The bundled lists, drawn with {@code random}. */
    public static GuestNames bundled(RandomGenerator random) {
        return new GuestNames(load(ADJECTIVES), load(NOUNS), random);
    }

    /** The adjectives, title-cased. */
    public List<String> adjectives() {
        return adjectives;
    }

    /** The nouns, title-cased. */
    public List<String> nouns() {
        return nouns;
    }

    /** A random {@code Adjective Noun}. */
    @Override
    public String get() {
        String adjective;
        String noun;
        synchronized (this) { // RandomGenerator implementations needn't be thread-safe
            adjective = adjectives.get(random.nextInt(adjectives.size()));
            noun = nouns.get(random.nextInt(nouns.size()));
        }
        return adjective + " " + noun;
    }

    /** Reads a bundled word list: title-cased, in file order; a malformed or repeated word fails. */
    static List<String> load(String resource) {
        try (InputStream in = GuestNames.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing word list " + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> words = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip();
                if (word.isEmpty() || word.startsWith("#")) {
                    continue;
                }
                if (!WORD.matcher(word).matches()) {
                    throw new IllegalStateException("bad word in " + resource + ": " + word);
                }
                String titled = word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
                if (words.contains(titled)) {
                    throw new IllegalStateException("repeated word in " + resource + ": " + word);
                }
                words.add(titled);
            }
            return List.copyOf(words);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
