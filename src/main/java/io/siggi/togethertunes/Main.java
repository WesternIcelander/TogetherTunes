package io.siggi.togethertunes;

import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.MeApi;
import io.siggi.togethertunes.auth.AuthRateLimits;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.SessionsApi;
import io.siggi.togethertunes.auth.SetupApi;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigException;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.EchoEndpoint;
import io.siggi.togethertunes.http.Csrf;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.http.WebSockets;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.secrets.SecretKeyException;
import io.siggi.togethertunes.secrets.ServerSecret;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Entry point of the runnable jar.
 *
 * <p>Usage: {@code java -jar TogetherTunes.jar [--config <file>]}. Loads and validates the
 * configuration (see {@link ConfigLoader}) and exits with status 1 if it is invalid, then creates
 * the data directory layout ({@link DataDir}), loads the secret key ({@link ServerSecret}), opens
 * the database ({@link Database}) and applies pending migrations ({@link MigrationRunner}) and creates the built-in roles
 * ({@link BuiltinRoles}), starts the HTTP server ({@link WebServer}) and runs until the
 * process is stopped.
 */
public final class Main {
    /** Exit status for an invalid configuration. */
    static final int EXIT_CONFIG = 1;
    /** Exit status for bad command-line usage. */
    static final int EXIT_USAGE = 2;
    /** Exit status when the database or the HTTP server can't start (e.g. the port is taken). */
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
        DataDir dataDir;
        try {
            dataDir = DataDir.create(config.dataDir());
        } catch (IOException e) {
            System.err.println("Could not set up the data directory: " + e.getMessage());
            System.exit(EXIT_STARTUP);
            return;
        }
        SecretCipher secrets;
        try {
            secrets = loadSecret(config, System.getenv(), System.out, System.err);
        } catch (SecretKeyException e) {
            System.err.println("Could not load the secret key: " + e.getMessage());
            System.exit(EXIT_STARTUP);
            return;
        }
        // TODO(OIDC provider admin / stream outputs): pass `secrets` to the repositories with *_enc columns.
        Database database;
        try {
            database = Database.open(config.dbUrl());
        } catch (SQLException | RuntimeException e) {
            System.err.println("Could not open the database: " + e.getMessage());
            System.exit(EXIT_STARTUP);
            return;
        }
        try {
            MigrationRunner.migrate(database);
            BuiltinRoles.ensure(database);
        } catch (SQLException | RuntimeException e) {
            System.err.println("Could not migrate the database: " + e.getMessage());
            database.close();
            System.exit(EXIT_STARTUP);
            return;
        }
        Path tmpDir = dataDir.tmp();
        StaticFiles staticFiles;
        try {
            staticFiles = StaticFiles.fromClasspath(Main.class.getClassLoader(), tmpDir);
        } catch (IOException e) {
            System.err.println("Could not set up the web UI: " + e.getMessage());
            database.close();
            System.exit(EXIT_STARTUP);
            return;
        }
        Settings settings = new Settings(database, config);
        ActorResolver actors = new ActorResolver(database, settings, config.secureCookies());
        Accounts accounts = new Accounts(database, Passwords.DEFAULT);
        AuthRateLimits limits = AuthRateLimits.defaults(Clock.systemUTC());
        Sessions sessions = new Sessions(database);
        try {
            sessions.deleteExpired();
        } catch (SQLException e) {
            System.err.println("Could not delete expired sessions: " + e);
        }
        SetupApi setup = new SetupApi(accounts, sessions, config.secureCookies(), limits);
        AuthApi auth = new AuthApi(accounts, sessions, settings, config.secureCookies(), limits);
        SessionsApi sessionsApi = new SessionsApi(sessions, accounts, config.secureCookies());
        Users users = new Users(database, Passwords.DEFAULT, Clock.systemUTC());
        Permissions permissions = new Permissions(database, settings);
        AdminApi admin = new AdminApi(database, accounts, users, new Roles(database), permissions, settings);
        MeApi me = new MeApi(accounts, users, permissions, new Guests(database, settings, config.secureCookies()), settings);
        WebServer web;
        try {
            web = WebServer.start(config.httpBind(), config.httpPort(), router(config, staticFiles, actors, setup, auth, sessionsApi, me, admin),
                    tmpDir.toFile(), config.trustedProxies());
        } catch (IOException e) {
            System.err.println("Could not listen on " + config.httpBind() + ":" + config.httpPort() + ": " + e);
            staticFiles.close();
            database.close();
            System.exit(EXIT_STARTUP);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.close();
            staticFiles.close();
            database.close();
        }, "shutdown"));
        System.out.println("Listening on " + config.httpBind() + ":" + web.port());
        web.join();
    }

    /**
     * Loads the secret key ({@link ServerSecret}), generating {@code <data>/secret.key} on first
     * start, and reports where it came from.
     */
    static SecretCipher loadSecret(Config config, Map<String, String> env, PrintStream out, PrintStream err)
            throws SecretKeyException {
        ServerSecret.Loaded loaded = ServerSecret.load(config.dataDir(), env);
        for (String warning : loaded.warnings()) {
            err.println("WARNING: " + warning);
        }
        switch (loaded.source()) {
            case ENVIRONMENT -> out.println("Secret key from " + ServerSecret.ENV_VAR);
            case FILE -> out.println("Secret key from " + loaded.file());
            case GENERATED -> out.println("Generated a new secret key in " + loaded.file()
                    + " (back it up: encrypted secrets can't be read without it)");
        }
        return loaded.cipher();
    }

    /**
     * The application's routes. Router-wide hooks: actor resolution ({@link ActorResolver#hook()};
     * handlers read the actor with {@link ActorResolver#actor}), then the CSRF check ({@link Csrf}:
     * {@code Origin} must match {@code http.publicBaseUrl} for state-changing requests that may be
     * cookie-authenticated, and for WebSocket upgrades). The web UI is the catch-all {@code GET /{path...}} route, so every
     * more specific route (API, media, live, WebSocket) wins over it. {@link EchoEndpoint} is the
     * test socket used by the test page {@code /test/ws-echo.html}. {@link SetupApi} is the
     * first-run setup ({@code /api/setup}), {@link AuthApi} local registration, login, logout and
     * bearer tokens ({@code /api/auth/...}), {@link SessionsApi} the user's own sessions
     * ({@code /api/me/sessions}), {@link MeApi} who-am-I and the guest identity ({@code /api/me},
     * {@code /api/me/guest}), {@link AdminApi} the admin API for users, roles and settings
     * ({@code /api/admin/...}).
     */
    static Router router(Config config, StaticFiles staticFiles, ActorResolver actors, SetupApi setup, AuthApi auth,
                         SessionsApi sessions, MeApi me, AdminApi admin) {
        Csrf csrf = new Csrf(config.publicBaseUrl(),
                List.of(ActorResolver.SESSION_COOKIE, ActorResolver.GUEST_COOKIE));
        Router router = new Router(actors.hook(), csrf.hook());
        setup.register(router);
        auth.register(router);
        sessions.register(router);
        me.register(router);
        admin.register(router);
        WebSockets webSockets = new WebSockets(csrf);
        EchoEndpoint.register(router, webSockets);
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
