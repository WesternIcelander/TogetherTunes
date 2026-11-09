package io.siggi.togethertunes;

import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.IdentityTickets;
import io.siggi.togethertunes.auth.AdminActivityApi;
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
import io.siggi.togethertunes.ingest.FillerCards;
import io.siggi.togethertunes.ingest.IngestItems;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.IngestQueue;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.SilenceFrame;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.library.AddMediaPolicy;
import io.siggi.togethertunes.library.GuestUploadQuota;
import io.siggi.togethertunes.library.Uploads;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.library.Items;
import io.siggi.togethertunes.library.ItemsApi;
import io.siggi.togethertunes.library.LibraryApi;
import io.siggi.togethertunes.library.MediaApi;
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.library.PlaylistsApi;
import io.siggi.togethertunes.library.Shares;
import io.siggi.togethertunes.library.SharesApi;
import io.siggi.togethertunes.library.UserLibraries;
import io.siggi.togethertunes.library.UserLibrariesApi;
import io.siggi.togethertunes.library.UploadsApi;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.library.YoutubeImportsApi;
import io.siggi.togethertunes.rooms.PlayingItems;
import io.siggi.togethertunes.rooms.RoomGuestRules;
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
import java.util.function.Supplier;

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
        // Cards are rendered with Java2D; never open a window or a Dock icon.
        System.setProperty("java.awt.headless", "true");
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
        // TODO(overlay, M5): the overlay encoder verifies its encodes against this too.
        ReferenceCard referenceCard = loadReferenceCard(config, dataDir, System.out, System.err);
        // TODO(packager, M4): the packager plays these when a room is paused, idle or loading.
        FillerCards fillerCards = loadFillerCards(config, dataDir, referenceCard, System.out, System.err);
        // TODO(packager, M4): repeated next to the filler cards.
        SilenceFrame silence = loadSilenceFrame(config, System.err);
        Settings settings = new Settings(database, config);
        Supplier<String> cookiesFile = cookiesFile(settings);
        IngestQueue ingest = startIngest(config, dataDir, database, referenceCard, cookiesFile, System.err);
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
        // Shared with the WebSocket handler (M3), which redeems the tickets.
        IdentityTickets identityTickets = new IdentityTickets();
        Guests guests = new Guests(database, settings, config.secureCookies());
        MeApi me = new MeApi(accounts, users, permissions, guests, settings, identityTickets);
        AdminActivityApi activity = AdminActivityApi.create(database, guests, permissions, Clock.systemUTC());
        Uploads uploads = uploads(config, dataDir, database, ingest, settings);
        try {
            int swept = uploads.sweep();
            if (swept > 0) {
                System.out.println("Deleted " + swept + " abandoned uploads");
            }
        } catch (SQLException | IOException e) {
            System.err.println("WARNING: could not clean up abandoned uploads: " + e.getMessage());
        }
        // Rooms come in M3: until then no room lets guests add media.
        AddMediaPolicy addMedia = new AddMediaPolicy(permissions, settings, RoomGuestRules.NO_ROOMS);
        UploadsApi uploadsApi = new UploadsApi(uploads, addMedia, guests);
        YoutubeImports youtubeImports = youtubeImports(config, database, ingest, cookiesFile);
        YoutubeImportsApi youtubeApi = new YoutubeImportsApi(youtubeImports, addMedia);
        Library library = new Library(database, youtubeImports, Clock.systemUTC());
        LibraryApi libraryApi = new LibraryApi(library, addMedia);
        ItemsApi itemsApi = new ItemsApi(new Items(database, dataDir, Clock.systemUTC()), permissions);
        Playlists playlists = new Playlists(database, youtubeImports, Clock.systemUTC());
        PlaylistsApi playlistsApi = new PlaylistsApi(playlists, addMedia);
        UserLibrariesApi userLibrariesApi = new UserLibrariesApi(new UserLibraries(database, library, playlists),
                permissions);
        SharesApi sharesApi = new SharesApi(new Shares(database, youtubeImports, Clock.systemUTC()), addMedia);
        // Rooms come in M3: until then no item is current or next anywhere.
        MediaApi mediaApi = new MediaApi(database, dataDir, settings, PlayingItems.NO_ROOMS);
        WebServer web;
        try {
            web = WebServer.start(config.httpBind(), config.httpPort(), router(config, staticFiles, actors, setup, auth,
                            sessionsApi, me, admin, activity, uploadsApi, youtubeApi, libraryApi, itemsApi,
                            playlistsApi, sharesApi, userLibrariesApi, mediaApi),
                    tmpDir.toFile(), config.trustedProxies());
        } catch (IOException e) {
            System.err.println("Could not listen on " + config.httpBind() + ":" + config.httpPort() + ": " + e);
            ingest.close();
            staticFiles.close();
            database.close();
            System.exit(EXIT_STARTUP);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.close();
            // Before the database: running jobs are put back in the queue on the way out.
            ingest.close();
            staticFiles.close();
            database.close();
        }, "shutdown"));
        System.out.println("Listening on " + config.httpBind() + ":" + web.port());
        web.join();
    }

    /**
     * Starts the ingest worker pool ({@code ingest.workers}) on the DB-backed queue; jobs left
     * {@code running} by a crash are queued again first, after cleaning up the directories such jobs
     * left ({@link IngestPipeline#recoverDirectories}). Items encoded for another output profile
     * get a {@code reencode} job ({@link IngestJobs#enqueueReencodes}). A database error here is reported and
     * ingest stays off until the next start (the server still runs).
     */
    static IngestQueue startIngest(Config config, DataDir dataDir, Database database, ReferenceCard referenceCard,
                                   Supplier<String> cookiesFile, PrintStream err) {
        try {
            int cleaned = IngestPipeline.recoverDirectories(dataDir);
            if (cleaned > 0) {
                err.println("Cleaned up " + cleaned + " directories left by interrupted ingest jobs");
            }
        } catch (IOException e) {
            err.println("WARNING: could not clean up after interrupted ingest jobs: " + e.getMessage());
        }
        IngestQueue.Handler handler = new IngestPipeline(Tools.from(config), dataDir, new IngestItems(database),
                referenceCard, config.ingestMaxDurationSeconds(), cookiesFile);
        IngestJobs jobs = new IngestJobs(database);
        IngestQueue queue = new IngestQueue(jobs, config.ingestWorkers(), handler);
        if (referenceCard != null) {
            try {
                int reencodes = jobs.enqueueReencodes(referenceCard.profile());
                if (reencodes > 0) {
                    err.println("Queued " + reencodes + " re-encode jobs for output profile "
                            + referenceCard.profile().id() + " (items encoded for another profile)");
                }
            } catch (SQLException e) {
                err.println("WARNING: could not queue re-encode jobs for the output profile: " + e.getMessage());
            }
        }
        try {
            queue.start();
        } catch (SQLException | RuntimeException e) {
            err.println("WARNING: could not start the ingest queue: " + e.getMessage());
        }
        return queue;
    }

    /**
     * The upload service ({@link Uploads}): limits from the config, new items recorded for the
     * configured output profile, each queued job wakes the ingest workers, and the guest upload
     * quota comes from the settings.
     */
    static Uploads uploads(Config config, DataDir dataDir, Database database, IngestQueue ingest, Settings settings) {
        return new Uploads(database, dataDir, Tools.from(config), ingest.jobs(), ingest::wake, config.outputProfile(),
                config.ingestMaxUploadBytes(), config.ingestMaxDurationSeconds(),
                new GuestUploadQuota(database, settings, Clock.systemUTC()), Clock.systemUTC());
    }

    /**
     * The YouTube import service ({@link YoutubeImports}): the duration limit from the config, new
     * items recorded for the configured output profile, each queued job wakes the ingest workers.
     */
    static YoutubeImports youtubeImports(Config config, Database database, IngestQueue ingest,
                                         Supplier<String> cookiesFile) {
        return new YoutubeImports(database, Tools.from(config), ingest.jobs(), ingest::wake, config.outputProfile(),
                config.ingestMaxDurationSeconds(), cookiesFile, Clock.systemUTC());
    }

    /**
     * The {@code ytdlp.cookiesFile} setting, read each time yt-dlp runs (so an admin's change
     * applies to the next import); null if it can't be read.
     */
    static Supplier<String> cookiesFile(Settings settings) {
        return () -> {
            try {
                return settings.ytdlpCookiesFile();
            } catch (SQLException e) {
                System.getLogger(Main.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not read ytdlp.cookiesFile: " + e.getMessage());
                return null;
            }
        };
    }

    /**
     * Creates or checks the reference card for the configured output profile
     * ({@link ReferenceCard#ensure}). Without a working ffmpeg/ffprobe the server still starts
     * (nothing can be ingested then), so this reports problems and returns null instead of failing.
     */
    static ReferenceCard loadReferenceCard(Config config, DataDir dataDir, PrintStream out, PrintStream err)
            throws InterruptedException {
        Tools tools = Tools.from(config);
        try {
            ReferenceCard card = ReferenceCard.ensure(tools, dataDir.cards(), config.outputProfile(), null);
            if (card.created()) {
                out.println("Created the " + config.outputProfile() + " reference card in " + card.card());
            }
            if (card.encoderChanged()) {
                err.println("WARNING: ffmpeg/x264 no longer reproduces the " + config.outputProfile()
                        + " reference card's avcC (" + ReferenceCard.profileDir(dataDir.cards(), config.outputProfile())
                        + "). New encodes will fail the avcC check until the library is re-encoded against a new reference.");
            }
            return card;
        } catch (ToolException | IOException e) {
            err.println("WARNING: could not create the " + config.outputProfile()
                    + " reference card; ingest won't work: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates or refreshes the server filler cards for the configured output profile
     * ({@link FillerCards#ensure}). Like the reference card, a failure is reported and the server
     * keeps starting; returns null then (and when there is no reference card).
     */
    static FillerCards loadFillerCards(Config config, DataDir dataDir, ReferenceCard reference, PrintStream out,
            PrintStream err) throws InterruptedException {
        if (reference == null) {
            return null;
        }
        try {
            FillerCards cards = FillerCards.ensure(Tools.from(config), dataDir.cards(), reference, null);
            if (cards.encoded() > 0) {
                out.println("Encoded " + cards.encoded() + " " + config.outputProfile() + " filler card(s) in "
                        + ReferenceCard.profileDir(dataDir.cards(), config.outputProfile()));
            }
            return cards;
        } catch (ToolException | IOException | ReferenceCard.AvcCMismatchException e) {
            err.println("WARNING: could not create the " + config.outputProfile() + " filler cards: " + e.getMessage());
            return null;
        }
    }

    /**
     * Encodes the AAC silence frame ({@link SilenceFrame#generate}). Like the cards, a failure is
     * reported and the server keeps starting; returns null then.
     */
    static SilenceFrame loadSilenceFrame(Config config, PrintStream err) throws InterruptedException {
        try {
            return SilenceFrame.generate(Tools.from(config), null);
        } catch (ToolException | IllegalStateException e) {
            err.println("WARNING: could not create the AAC silence frame: " + e.getMessage());
            return null;
        }
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
     * ({@code /api/admin/...}), {@link AdminActivityApi} the admin guest list, audit log and session
     * IPs ({@code /api/admin/guests}, {@code /api/admin/audit}, {@code /api/admin/ip/...}),
     * {@link UploadsApi} chunked uploads ({@code /api/uploads...}), {@link YoutubeImportsApi} YouTube
     * imports ({@code /api/imports/youtube}), {@link LibraryApi} the caller's library
     * ({@code /api/library...}), {@link ItemsApi} items by id ({@code /api/items/...}),
     * {@link PlaylistsApi} the caller's playlists ({@code /api/playlists...}), {@link SharesApi}
     * sharing items ({@code /api/shares...}), {@link UserLibrariesApi} other users' libraries and
     * playlists for {@code library.viewAll} ({@code /api/users...}), {@link MediaApi} the items'
     * media files ({@code /media/{id}/{file}}).
     */
    static Router router(Config config, StaticFiles staticFiles, ActorResolver actors, SetupApi setup, AuthApi auth,
                         SessionsApi sessions, MeApi me, AdminApi admin, AdminActivityApi activity,
                         UploadsApi uploads, YoutubeImportsApi youtube, LibraryApi library,
                         ItemsApi items, PlaylistsApi playlists, SharesApi shares,
                         UserLibrariesApi userLibraries, MediaApi media) {
        Csrf csrf = new Csrf(config.publicBaseUrl(),
                List.of(ActorResolver.SESSION_COOKIE, ActorResolver.GUEST_COOKIE));
        Router router = new Router(actors.hook(), csrf.hook());
        setup.register(router);
        auth.register(router);
        sessions.register(router);
        me.register(router);
        admin.register(router);
        activity.register(router);
        uploads.register(router);
        youtube.register(router);
        library.register(router);
        items.register(router);
        playlists.register(router);
        shares.register(router);
        userLibraries.register(router);
        media.register(router);
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
