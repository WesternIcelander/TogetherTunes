package io.siggi.togethertunes.config;

import java.util.List;

/**
 * The configuration is invalid. Carries every problem found, so the operator can fix them
 * all in one go.
 */
public final class ConfigException extends Exception {
    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super(format(problems));
        this.problems = List.copyOf(problems);
    }

    public ConfigException(String problem) {
        this(List.of(problem));
    }

    public List<String> problems() {
        return problems;
    }

    private static String format(List<String> problems) {
        StringBuilder sb = new StringBuilder("Invalid configuration:");
        for (String problem : problems) {
            sb.append("\n  - ").append(problem);
        }
        return sb.toString();
    }
}
