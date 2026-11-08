package io.siggi.togethertunes;

import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigException;
import io.siggi.togethertunes.config.ConfigLoader;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Entry point of the runnable jar.
 *
 * <p>Usage: {@code java -jar TogetherTunes.jar [--config <file>]}. Loads and validates the
 * configuration (see {@link ConfigLoader}) and exits with status 1 if it is invalid. The HTTP
 * server is wired in by a later M0 step.
 */
public final class Main {
    /** Exit status for an invalid configuration. */
    static final int EXIT_CONFIG = 1;
    /** Exit status for bad command-line usage. */
    static final int EXIT_USAGE = 2;

    private Main() {
    }

    public static void main(String[] args) {
        int status = run(args, ConfigLoader.forProcess(), System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Parses the command line and loads the configuration.
     *
     * @return 0 to keep running, otherwise the exit status
     */
    static int run(String[] args, ConfigLoader loader, PrintStream out, PrintStream err) {
        Path configFile = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value;
            if (arg.equals("--config") || arg.equals("-c")) {
                if (i + 1 >= args.length) {
                    err.println(arg + " needs a file name");
                    return EXIT_USAGE;
                }
                value = args[++i];
            } else if (arg.startsWith("--config=")) {
                value = arg.substring("--config=".length());
            } else if (arg.equals("--help") || arg.equals("-h")) {
                out.println(usage());
                return EXIT_USAGE;
            } else {
                err.println("Unknown argument: " + arg);
                err.println(usage());
                return EXIT_USAGE;
            }
            try {
                configFile = Path.of(value);
            } catch (InvalidPathException e) {
                err.println("Invalid config file path: " + value);
                return EXIT_USAGE;
            }
        }

        out.println("TogetherTunes " + version());
        ConfigLoader.Loaded loaded;
        try {
            loaded = loader.load(configFile);
        } catch (ConfigException e) {
            err.println(e.getMessage());
            return EXIT_CONFIG;
        }
        for (String warning : loaded.warnings()) {
            err.println("WARNING: " + warning);
        }
        Config config = loaded.config();
        out.println("Config: " + (loaded.configFile() == null ? "(no file, environment and defaults only)" : loaded.configFile()));
        out.println("Public URL " + config.publicBaseUrl() + ", listening on " + config.httpBind() + ":" + config.httpPort()
                + ", data in " + config.dataDir() + ", output profile " + config.outputProfile());
        return 0;
    }

    private static String usage() {
        return "Usage: java -jar TogetherTunes.jar [--config <file>]\n"
                + "  The config file is JSON. Without --config, " + ConfigLoader.CONFIG_FILE_ENV
                + " names it, else ./" + ConfigLoader.DEFAULT_CONFIG_FILE + " is used if present.\n"
                + "  Every key can be overridden by an environment variable, e.g. "
                + ConfigLoader.envName(ConfigLoader.HTTP_PUBLIC_BASE_URL) + ".";
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
