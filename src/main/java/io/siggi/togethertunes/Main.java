package io.siggi.togethertunes;

import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigException;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.http.Hooks;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Entry point of the runnable jar.
 *
 * <p>Usage: {@code java -jar TogetherTunes.jar [--config <file>]}. Loads and validates the
 * configuration (see {@link ConfigLoader}) and exits with status 1 if it is invalid, then starts
 * the HTTP server ({@link WebServer}) and runs until the process is stopped.
 */
public final class Main {
    /** Exit status for an invalid configuration. */
    static final int EXIT_CONFIG = 1;
    /** Exit status for bad command-line usage. */
    static final int EXIT_USAGE = 2;
    /** Exit status when the HTTP server can't start (e.g. the port is taken). */
    static final int EXIT_STARTUP = 3;

    /** Outcome of {@link #load}: an exit status, and the config when the status is 0. */
    record Startup(int status, Config config) {
    }

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        Startup startup = load(args, ConfigLoader.forProcess(), System.out, System.err);
        if (startup.status() != 0) {
            System.exit(startup.status());
        }
        Config config = startup.config();
        // TODO(data dir step): use <data>/tmp once the data dir layout is created on startup.
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        StaticFiles staticFiles;
        try {
            staticFiles = StaticFiles.fromClasspath(Main.class.getClassLoader(), tmpDir);
        } catch (IOException e) {
            System.err.println("Could not set up the web UI: " + e.getMessage());
            System.exit(EXIT_STARTUP);
            return;
        }
        WebServer web;
        try {
            web = WebServer.start(config.httpBind(), config.httpPort(), router(config, staticFiles),
                    tmpDir.toFile(), config.trustedProxies());
        } catch (IOException e) {
            System.err.println("Could not listen on " + config.httpBind() + ":" + config.httpPort() + ": " + e);
            staticFiles.close();
            System.exit(EXIT_STARTUP);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.close();
            staticFiles.close();
        }, "shutdown"));
        System.out.println("Listening on " + config.httpBind() + ":" + web.port());
        web.join();
    }

    /**
     * The application's routes. Router-wide hooks: actor resolution, then the CSRF check
     * (placeholders until M1). The web UI is the catch-all {@code GET /{path...}} route, so every
     * more specific route (API, media, live) wins over it.
     */
    static Router router(Config config, StaticFiles staticFiles) {
        Router router = new Router(Hooks.authenticate(), Hooks.csrf());
        staticFiles.register(router);
        return router;
    }

    /**
     * Parses the command line and loads the configuration.
     *
     * @return 0 to keep running, otherwise the exit status
     */
    static int run(String[] args, ConfigLoader loader, PrintStream out, PrintStream err) {
        return load(args, loader, out, err).status();
    }

    /** Parses the command line and loads the configuration. */
    static Startup load(String[] args, ConfigLoader loader, PrintStream out, PrintStream err) {
        Path configFile = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value;
            if (arg.equals("--config") || arg.equals("-c")) {
                if (i + 1 >= args.length) {
                    err.println(arg + " needs a file name");
                    return new Startup(EXIT_USAGE, null);
                }
                value = args[++i];
            } else if (arg.startsWith("--config=")) {
                value = arg.substring("--config=".length());
            } else if (arg.equals("--help") || arg.equals("-h")) {
                out.println(usage());
                return new Startup(EXIT_USAGE, null);
            } else {
                err.println("Unknown argument: " + arg);
                err.println(usage());
                return new Startup(EXIT_USAGE, null);
            }
            try {
                configFile = Path.of(value);
            } catch (InvalidPathException e) {
                err.println("Invalid config file path: " + value);
                return new Startup(EXIT_USAGE, null);
            }
        }

        out.println("TogetherTunes " + version());
        ConfigLoader.Loaded loaded;
        try {
            loaded = loader.load(configFile);
        } catch (ConfigException e) {
            err.println(e.getMessage());
            return new Startup(EXIT_CONFIG, null);
        }
        for (String warning : loaded.warnings()) {
            err.println("WARNING: " + warning);
        }
        Config config = loaded.config();
        out.println("Config: " + (loaded.configFile() == null ? "(no file, environment and defaults only)" : loaded.configFile()));
        out.println("Public URL " + config.publicBaseUrl() + ", listening on " + config.httpBind() + ":" + config.httpPort()
                + ", data in " + config.dataDir() + ", output profile " + config.outputProfile());
        return new Startup(0, config);
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
