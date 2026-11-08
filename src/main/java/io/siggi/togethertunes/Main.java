package io.siggi.togethertunes;

/**
 * Entry point of the runnable jar.
 *
 * <p>Only a skeleton for now: configuration loading and the HTTP server are wired in by
 * later M0 steps.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("TogetherTunes " + version());
    }

    /**
     * The version from the jar manifest, or {@code "dev"} when running from classes
     * (IDE, tests).
     */
    public static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
