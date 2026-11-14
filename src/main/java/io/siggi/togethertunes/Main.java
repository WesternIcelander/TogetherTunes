package io.siggi.togethertunes;

import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.IdentityTickets;
import io.siggi.togethertunes.auth.AdminActivityApi;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.Providers;
import io.siggi.togethertunes.auth.OidcDiscovery;
import io.siggi.togethertunes.auth.Identities;
import io.siggi.togethertunes.auth.IdentitiesApi;
import io.siggi.togethertunes.auth.IdentityResolution;
import io.siggi.togethertunes.auth.OidcLogin;
import io.siggi.togethertunes.auth.ProvidersApi;
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
import io.siggi.togethertunes.auth.UserSettings;
import io.siggi.togethertunes.auth.UserSettingsApi;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigException;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.EchoEndpoint;
import io.siggi.togethertunes.http.Csrf;
import io.siggi.togethertunes.http.PlainHttpRedirect;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.http.WebSockets;
import io.siggi.togethertunes.ingest.EncodePresets;
import io.siggi.togethertunes.fonts.CardFonts;
import io.siggi.togethertunes.fonts.FontDownloader;
import io.siggi.togethertunes.fonts.FontLibrary;
import io.siggi.togethertunes.fonts.FontManifest;
import io.siggi.togethertunes.fonts.FontsApi;
import io.siggi.togethertunes.fonts.SystemFonts;
import io.siggi.togethertunes.fonts.TextRenderingCheck;
import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.ingest.FillerCards;
import io.siggi.togethertunes.settings.CjkVariant;
import io.siggi.togethertunes.overlay.OverlayCache;
import io.siggi.togethertunes.overlay.OverlayCards;
import io.siggi.togethertunes.overlay.OverlayDecisions;
import io.siggi.togethertunes.overlay.OverlayPool;
import io.siggi.togethertunes.overlay.OverlayVideoJobs;
import io.siggi.togethertunes.overlay.CustomCards;
import io.siggi.togethertunes.overlay.CustomCardsApi;
import io.siggi.togethertunes.overlay.OverlayPreviewApi;
import io.siggi.togethertunes.overlay.Overlays;
import io.siggi.togethertunes.ingest.IngestItems;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestProgress;
import io.siggi.togethertunes.ingest.JobList;
import io.siggi.togethertunes.ingest.JobsApi;
import io.siggi.togethertunes.ingest.MediaVersions;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.IngestQueue;
import io.siggi.togethertunes.ingest.OutputAssets;
import io.siggi.togethertunes.ingest.PlayableFiles;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.SilenceFrame;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.ingest.YtdlpApi;
import io.siggi.togethertunes.ingest.YtdlpUpdater;
import io.siggi.togethertunes.output.CardGop;
import io.siggi.togethertunes.output.CleartextTokens;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.LiveHlsApi;
import io.siggi.togethertunes.output.OutputStatusFeed;
import io.siggi.togethertunes.output.LiveLimits;
import io.siggi.togethertunes.output.LegacyRtmpKeys;
import io.siggi.togethertunes.output.LiveTsApi;
import io.siggi.togethertunes.output.LiveMp3Api;
import io.siggi.togethertunes.output.Mp3Encoders;
import io.siggi.togethertunes.output.ProgramMedia;
import io.siggi.togethertunes.output.ProgramOutputs;
import io.siggi.togethertunes.output.OutputAutoOff;
import io.siggi.togethertunes.output.RtmpPushers;
import io.siggi.togethertunes.output.StreamOutputControl;
import io.siggi.togethertunes.output.StreamOutputs;
import io.siggi.togethertunes.output.StreamOutputsApi;
import io.siggi.togethertunes.output.PlainHttpStreams;
import io.siggi.togethertunes.output.ProgramPipeline;
import io.siggi.togethertunes.output.TimeSource;
import io.siggi.togethertunes.library.AddMediaPolicy;
import io.siggi.togethertunes.library.GuestUploadQuota;
import io.siggi.togethertunes.library.ContentIdBackfill;
import io.siggi.togethertunes.library.Uploads;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.library.LibraryAddListener;
import io.siggi.togethertunes.library.Items;
import io.siggi.togethertunes.library.ItemsApi;
import io.siggi.togethertunes.library.LibraryApi;
import io.siggi.togethertunes.library.MediaApi;
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.library.PlaylistsApi;
import io.siggi.togethertunes.library.UserLibraries;
import io.siggi.togethertunes.library.UserLibrariesApi;
import io.siggi.togethertunes.library.UploadsApi;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.library.YoutubeImportsApi;
import io.siggi.togethertunes.retention.RetentionSweeper;
import io.siggi.togethertunes.storage.DiskSpace;
import io.siggi.togethertunes.rooms.DiagnosticsApi;
import io.siggi.togethertunes.rooms.RequestSearch;
import io.siggi.togethertunes.rooms.RoomHistory;
import io.siggi.togethertunes.rooms.RoomGuests;
import io.siggi.togethertunes.rooms.RoomQueue;
import io.siggi.togethertunes.rooms.RoomRegistry;
import io.siggi.togethertunes.rooms.RoomLinks;
import io.siggi.togethertunes.rooms.RoomSettings;
import io.siggi.togethertunes.rooms.RoomSocket;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.rooms.RoomsApi;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.secrets.SecretKeyException;
import io.siggi.togethertunes.secrets.ServerSecret;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
        // Before anything renders: Java2D needs FreeType, fontconfig and a font (a clear message, not a crash).
        int text = checkTextRendering(TextRenderingCheck.JAVA2D, System.err);
        if (text != 0) {
            System.exit(text);
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
            // Stream keys encrypted by older versions become plain text (migration 0007).
            LegacyRtmpKeys.Result keys = LegacyRtmpKeys.convert(database, secrets, System.err);
            if (keys.converted() > 0) {
                System.out.println("Converted " + keys.converted() + " encrypted RTMP stream keys to plain text");
            }
            BuiltinRoles.ensure(database);
        } catch (SQLException | RuntimeException e) {
            System.err.println("Could not migrate the database: " + e.getMessage());
            database.close();
            System.exit(EXIT_STARTUP);
            return;
        }
        // Config keys that are also admin settings: the config gives the default, the admin setting wins.
        Settings settings = new Settings(database, config);
        // The card fonts (bundled, downloaded, uploaded, installed), before the first card renders.
        FontLibrary fonts = startFonts(dataDir, settings, System.err);
        // The output encoding (output.profile, output.videoBitrateKbps) with its reference and filler
        // cards; an admin can change it at runtime (OutputAssets.follow, from the settings listener below).
        OutputAssets outputAssets = outputAssets(config, dataDir,
                setting(settings::outputEncoding, config.outputEncoding()), System.out, System.err);
        ReferenceCard referenceCard = outputAssets.reference();
        // Repeated next to the filler cards.
        SilenceFrame silence = loadSilenceFrame(config, System.err);
        Supplier<String> cookiesFile = cookiesFile(settings);
        // The quick and quality x264 presets, each checked against the reference avcC before use.
        EncodePresets presets = encodePresets(config, dataDir, outputAssets, settings, System.err);
        IngestPipeline pipeline = new IngestPipeline(Tools.from(config), dataDir, new IngestItems(database),
                referenceCard, config.ingestMaxDurationSeconds(), cookiesFile, presets);
        pipeline.reference(outputAssets::reference);
        pipeline.maxDurationSeconds(() -> setting(settings::ingestMaxDurationSeconds, config.ingestMaxDurationSeconds()));
        // Old media versions are deleted once nobody can still be playing them (rooms set inUse below).
        // The previous version is also kept for media.undoHours (undo; docs/library-and-ingest.md#undo).
        MediaVersions mediaVersions = new MediaVersions(dataDir, database);
        mediaVersions.undoWindow(() -> setting(settings::mediaUndoHours, Settings.MEDIA_UNDO_HOURS.defaultValue())
                * 3_600_000L);
        pipeline.mediaVersions(mediaVersions);
        // Adding media pauses (and queued ingest jobs wait) while the data volume is below storage.minFreeBytes.
        DiskSpace diskSpace = DiskSpace.of(dataDir.root(), settings::storageMinFreeBytes);
        // Kept undo versions are deleted early while the data volume is below the minimum.
        mediaVersions.diskSpace(diskSpace);
        IngestQueue ingest = startIngest(setting(settings::ingestWorkers, config.ingestWorkers()), dataDir, database,
                outputAssets, pipeline, mediaVersions, diskSpace, System.err);
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
        // Invites (docs/auth.md#invites): registration in 'invite' mode, /api/invites.
        io.siggi.togethertunes.auth.Invites invites = new io.siggi.togethertunes.auth.Invites(database,
                Clock.systemUTC());
        auth.invites(invites, new io.siggi.togethertunes.auth.InvitesApi(invites, permissions,
                config.publicBaseUrl()));
        AdminApi admin = new AdminApi(database, accounts, users, new Roles(database), permissions, settings);
        admin.diskSpace(diskSpace);
        // Login providers (OpenID Connect and OAuth); client secrets are stored encrypted with the server key.
        Providers loginProviders = new Providers(database, secrets);
        ProvidersApi providersApi = new ProvidersApi(loginProviders, permissions, accounts, config.publicBaseUrl());
        // The provider login flow (authorization code + PKCE, ID-token validation), ending in
        // identity resolution (log in, sign up or link by provider and subject, never by email).
        OidcLogin oidcLogin = new OidcLogin(loginProviders, new OidcDiscovery(), config.publicBaseUrl(),
                config.secureCookies());
        Identities identities = new Identities(database);
        oidcLogin.completion(new IdentityResolution(identities, sessions, config.secureCookies()));
        providersApi.loginFlow(oidcLogin);
        providersApi.identities(new IdentitiesApi(identities));
        // An admin can't pick an x264 preset that doesn't give the reference avcC.
        admin.settingCheck((key, value) -> {
            if (value instanceof X264Preset preset
                    && (key == Settings.INGEST_QUICK_PRESET || key == Settings.INGEST_QUALITY_PRESET)) {
                presets.validate(key.name(), preset);
            }
            // cards.font names an installed family or an uploaded font.
            if (key == Settings.CARDS_FONT) {
                fonts.validateCardFont((String) value);
            }
        });
        // Shared with the room WebSocket (RoomSocket), which redeems the tickets.
        IdentityTickets identityTickets = new IdentityTickets();
        Guests guests = new Guests(database, settings, config.secureCookies());
        MeApi me = new MeApi(accounts, users, permissions, guests, settings, identityTickets);
        // The user's own preferences (GET/PATCH /api/me/settings).
        me.userSettings(new UserSettingsApi(new UserSettings(database)));
        AdminActivityApi activity = AdminActivityApi.create(database, guests, permissions, Clock.systemUTC());
        Uploads uploads = uploads(config, dataDir, database, ingest, settings, outputAssets);
        // Adding a quick-encoded item to a library queues its quality re-encode.
        // Adding an item to a library may also classify it as a movie or show (or the other way):
        // its audio is then re-encoded with or without the loudness normalisation, unless the
        // quality re-encode does that anyway.
        LibraryAddListener qualityOnLibraryAdd = itemId -> {
            if (!ingest.jobs().enqueueQualityIfNeeded(itemId)) {
                ingest.jobs().enqueueLoudnessIfNeeded(itemId);
            }
        };
        uploads.libraryAddListener(qualityOnLibraryAdd);
        uploads.diskSpace(diskSpace);
        uploads.precheck(uploadPrecheck(config));
        new ContentIdBackfill(database, dataDir).start();
        try {
            int swept = uploads.sweep();
            if (swept > 0) {
                System.out.println("Deleted " + swept + " abandoned uploads");
            }
        } catch (SQLException | IOException e) {
            System.err.println("WARNING: could not clean up abandoned uploads: " + e.getMessage());
        }
        // The rooms' program outputs follow the room timelines (the registry's packager hook).
        // The rooms' QR overlay images (docs/overlay.md#the-overlay-image): rendered on demand for the
        // current slug, overlay settings, public URL and profile, and right away when an edit changes them.
        Overlays overlays = new Overlays(dataDir.rooms(), config.publicBaseUrl(),
                () -> outputAssets.encoding().profile());
        // Overlaid filler and item cards for the program outputs (docs/overlay.md#cards-cheap-path),
        // with the overlay decided once per queue entry (docs/overlay.md#permissions).
        // Custom card images (docs/media-types.md#custom-card-images): the server's, and a room's own
        // while its owner holds rooms.customCards; they replace the filler cards of the room's output.
        CustomCards customCards = new CustomCards(dataDir.customCards(), dataDir.rooms(),
                CustomCards.ownerHolds(database, permissions));
        OverlayCards.Lookups overlayLookups = OverlayCards.lookupsFrom(database, dataDir, settings,
                new OverlayDecisions(database, settings, permissions), customCards);
        OverlayCards overlayCards = new OverlayCards(dataDir.rooms(), Tools.from(config));
        // Music-video overlay re-encodes run in their own pool (overlay.workers), never behind ingest;
        // its jobs live in memory only (docs/architecture.md#threading).
        OverlayPool overlayPool = new OverlayPool(setting(settings::overlayWorkers, config.overlayWorkers()));
        overlayPool.start();
        // The next entry's re-encode starts ahead (normal priority), the playing one's catches up (high);
        // a busy pool means the item plays without the overlay (docs/overlay.md#scheduling-and-caching).
        OverlayVideoJobs overlayVideoJobs = new OverlayVideoJobs(dataDir.rooms(), overlayPool, Tools.from(config),
                overlayPresets(settings, presets, System.err), r -> Thread.ofVirtual().start(r));
        // The overlay cache (docs/overlay.md#scheduling-and-caching): re-encodes and room cards, least
        // recently used evicted beyond overlay.cacheMaxBytes, stale ones (another overlayHash) dropped;
        // partial encodes and orphaned room directories are discarded now.
        OverlayCache overlayCache = new OverlayCache(dataDir.rooms(),
                () -> setting(settings::overlayCacheMaxBytes, config.overlayCacheMaxBytes()),
                r -> Thread.ofVirtual().start(r));
        overlayCache.evictors(overlayVideoJobs::evict, overlayCards::evict);
        overlayCards.cache(overlayCache);
        overlayVideoJobs.cache(overlayCache);
        overlays.hashListener(overlayCache::roomOverlay);
        try {
            overlayCache.start(roomIds(database));
        } catch (IOException e) {
            System.err.println("WARNING: could not scan the overlay cache: " + e.getMessage());
        }
        ProgramOutputs programOutputs = programOutputs(config, dataDir, database, outputAssets, silence, settings,
                (roomId, reference) -> overlayCards.forRoom(roomId, reference, overlays, overlayLookups,
                        overlayVideoJobs));
        // A new output encoding: presets are checked against its reference card, every item re-encodes
        // and running program outputs restart on it (docs/library-and-ingest.md#changing-the-encoding-at-runtime).
        outputAssets.addListener(encodingChanged(presets, ingest, programOutputs, System.out, System.err));
        RoomRegistry roomRegistry = new RoomRegistry(database, Clock.systemUTC(), programOutputs);
        // One notion of "playable" for rooms and packager: a ready item whose files the program output
        // can't use (older encoding, unreadable index) is skipped like one still processing
        // (docs/rooms.md#room-state). An encoding switch makes every room look again.
        roomRegistry.playableFiles(playableFiles(dataDir, outputAssets)::usable);
        outputAssets.addListener((previous, now) -> roomRegistry.allItemsChanged());
        programOutputs.snapshotsFrom(roomRegistry);
        // RTMP pushes (supervised ffmpeg); started by the outputs API. Null without ffmpeg.
        RtmpPushers rtmpPushers = config.ffmpeg() == null ? null : new RtmpPushers(programOutputs, config.ffmpeg());
        Rooms rooms = new Rooms(database, permissions, Clock.systemUTC());
        rooms.liveIps(roomRegistry.mediaAccess()); // a room ban covers the address a person connects from now
        RoomGuests roomGuests = new RoomGuests(database, settings, permissions, rooms, roomRegistry);
        // Guests add media only as a request in a room that allows it (server AND room setting).
        AddMediaPolicy addMedia = new AddMediaPolicy(permissions, settings, roomGuests);
        UploadsApi uploadsApi = new UploadsApi(uploads, addMedia, guests);
        YoutubeImports youtubeImports = youtubeImports(config, database, ingest, cookiesFile);
        youtubeImports.outputEncoding(outputAssets::encoding);
        youtubeImports.maxDurationSeconds(() -> setting(settings::ingestMaxDurationSeconds,
                config.ingestMaxDurationSeconds()));
        youtubeImports.libraryAddListener(qualityOnLibraryAdd);
        youtubeImports.diskSpace(diskSpace);
        Library library = new Library(database, youtubeImports, Clock.systemUTC());
        library.libraryAddListener(qualityOnLibraryAdd);
        LibraryApi libraryApi = new LibraryApi(library, addMedia);
        Items items = new Items(database, dataDir, Clock.systemUTC());
        items.jobSteps(ingest::liveStep);
        items.mediaVersions(mediaVersions);
        // A purge also deletes the item's overlay re-encodes (stopping running ones) and cards.
        items.purgeListener(itemId -> {
            overlayVideoJobs.stopItem(itemId);
            overlayCache.itemPurged(itemId);
        });
        items.trimJobs(ingest.jobs());
        // retention.purgedEntryDays = 0: a purge also removes the item's library and playlist entries.
        items.purgedEntryDays(settings::retentionPurgedEntryDays);
        ItemsApi itemsApi = new ItemsApi(items, permissions, youtubeImports);
        Playlists playlists = new Playlists(database, library, youtubeImports, Clock.systemUTC());
        PlaylistsApi playlistsApi = new PlaylistsApi(playlists, addMedia);
        // "Add from link" imports straight into a playlist (and the library).
        YoutubeImportsApi youtubeApi = new YoutubeImportsApi(youtubeImports, addMedia, playlists);
        UserLibrariesApi userLibrariesApi = new UserLibrariesApi(new UserLibraries(database, library, playlists),
                permissions);
        // Guests and anonymous visitors may fetch the current and next items of the rooms they are in.
        MediaApi mediaApi = new MediaApi(database, dataDir, roomRegistry.mediaAccess());
        // Ingest and purges tell the rooms when an item's status changes (an idle room starts it).
        ingest.jobs().statusListener(roomRegistry);
        // An old media version is kept while its item is current or next in a room (and for its duration + 5 min).
        mediaVersions.inUse(roomRegistry.playingItems()::isCurrentOrNext);
        mediaVersions.start();
        // Jobs of items a room is waiting on go first; a change in the rooms re-checks the give-way rule.
        ingest.jobs().wantedItems(roomRegistry.wantedItems(ingest::wake));
        items.statusListener(roomRegistry);
        mediaVersions.statusListener(roomRegistry);
        try {
            int resumed = roomRegistry.resumePlaying();
            if (resumed > 0) {
                System.out.println("Resumed " + resumed + " playing rooms");
            }
        } catch (SQLException e) {
            System.err.println("WARNING: could not resume playing rooms: " + e.getMessage());
        }
        try {
            roomRegistry.startIdleWithReadyEntries();
        } catch (SQLException e) {
            System.err.println("WARNING: could not start idle rooms with ready entries: " + e.getMessage());
        }
        RoomHistory roomHistory = new RoomHistory(database, rooms, settings::roomsRequestHistoryDays,
                Clock.systemUTC());
        RequestSearch requestSearch = new RequestSearch(database, rooms, roomGuests);
        // GET /api/search: guests and anonymous visitors search what they may request in a room.
        itemsApi.roomSearch(requestSearch::requireMayRequest);
        RoomsApi roomsApi = new RoomsApi(rooms, roomRegistry, permissions, roomGuests, requestSearch, roomHistory);
        roomsApi.libraryAddListener(qualityOnLibraryAdd);
        // The alone state (docs/rooms.md#alone): each user's own pseudo-room, and their play history.
        io.siggi.togethertunes.rooms.PlayHistory playHistory = new io.siggi.togethertunes.rooms.PlayHistory(database,
                settings::roomsRequestHistoryDays, Clock.systemUTC());
        roomsApi.aloneSessions(new io.siggi.togethertunes.rooms.AloneSessions(database, rooms, roomRegistry,
                Clock.systemUTC()), playHistory);
        // Autoplay selections (docs/ui.md#autoplay-from-playlists): who selected which playlists per room.
        // Each selection change, and each change to a selected person's playlists, refills the room's
        // autoplay section (docs/rooms.md#autoplay).
        roomsApi.autoplay(new io.siggi.togethertunes.rooms.AutoplaySelections(rooms, playlists, roomRegistry));
        library.playlistsChangeListener(roomRegistry::playlistsChanged);
        RoomQueue roomQueue = new RoomQueue(roomRegistry, database, uploads, youtubeImports, playlists, addMedia,
                new SecureRandom());
        RoomSocket roomSocket = new RoomSocket(actors, identityTickets, guests, settings, rooms, roomGuests,
                roomRegistry, roomQueue, database, Clock.systemUTC());
        roomSocket.covers(new io.siggi.togethertunes.library.Covers(dataDir));
        Router appRouter = router(config, staticFiles, actors, setup, auth, sessionsApi, me, admin, providersApi, activity,
                uploadsApi, youtubeApi, libraryApi, itemsApi, playlistsApi, userLibrariesApi, mediaApi,
                roomsApi, roomSocket);
        // http.insecureBaseUrl: plain HTTP serves only stream URLs (by slug, or a private room's
        // token); the rest gets a 301 to https (docs/architecture.md#plain-http).
        if (config.redirectPlainHttp()) {
            appRouter.filter(new PlainHttpRedirect(config.publicBaseUrl(),
                    PlainHttpStreams.exemption(PlainHttpStreams.from(database))));
        }
        // The rooms' outputs (/api/rooms/{room}/outputs...).
        StreamOutputs streamOutputs = new StreamOutputs(database, new SecureRandom(), Clock.systemUTC());
        // Outputs turn off after the queue was empty, or (.ts/HLS/MP3) nobody listened, for the
        // room's limit (docs/program-output.md#auto-turn-off).
        OutputAutoOff autoOff = new OutputAutoOff(streamOutputs, roomId -> {
            Rooms.Room room = rooms.record(roomId);
            RoomSettings rs = room == null ? RoomSettings.DEFAULTS : room.settings();
            return OutputAutoOff.policy(rs.autoOff(), rs.autoOffMinutes(), settings.outputsForceAutoOff(),
                    settings.outputsMaxAutoOffMinutes());
        }, System::currentTimeMillis);
        programOutputs.idleListener(autoOff::idle);
        // /live/...: per-IP rate limit and per-URL connection cap (docs/auth.md#stream-urls).
        LiveLimits liveLimits = LiveLimits.defaults(Clock.systemUTC());
        // The direct MPEG-TS output: GET/HEAD /live/{key}.ts (docs/program-output.md#direct-mpeg-ts-ts).
        LiveTsApi liveTs = new LiveTsApi(LiveTsApi.targetsFrom(database, settings), programOutputs, liveLimits);
        liveTs.listeners(autoOff);
        // A private room's token used over plain HTTP is recorded; the outputs panel warns about it.
        CleartextTokens cleartextTokens = CleartextTokens.from(streamOutputs);
        liveTs.cleartext(cleartextTokens);
        liveTs.register(appRouter);
        // The MP3 output: GET/HEAD /live/{key}.mp3 (docs/program-output.md#mp3-output), one
        // supervised ffmpeg encode per room with listeners. Null encoders without ffmpeg.
        Mp3Encoders mp3Encoders = config.ffmpeg() == null ? null : new Mp3Encoders(programOutputs, config.ffmpeg(),
                () -> mp3Bitrate(settings), Mp3Encoders.titlesFrom(database));
        LiveMp3Api liveMp3 = new LiveMp3Api(LiveMp3Api.targetsFrom(database, settings), mp3Encoders,
                () -> mp3Bitrate(settings), liveLimits);
        liveMp3.listeners(autoOff);
        liveMp3.cleartext(cleartextTokens);
        liveMp3.register(liveTs);
        // HLS: GET /live/{key}/index.m3u8 and /live/{key}/seg/{seq}.ts (docs/program-output.md#hls).
        LiveHlsApi liveHls = new LiveHlsApi(LiveHlsApi.targetsFrom(database, settings), programOutputs,
                System::currentTimeMillis, liveLimits);
        liveHls.listeners(autoOff);
        liveHls.cleartext(cleartextTokens);
        liveHls.register(appRouter);
        // What keeps pushes and .ts/MP3 listeners in line with the outputs.
        StreamOutputControl outputControl = streamOutputControl(streamOutputs, settings, programOutputs, rtmpPushers,
                () -> {
                    liveTs.revalidate();
                    liveMp3.revalidate();
                });
        outputControl.autoOff(autoOff);
        // A new slug or visibility changes the stream URLs at once (docs/program-output.md#stream-urls):
        // a room that becomes private gets fresh tokens; connections on URLs that stopped working end.
        // A room edit re-renders its overlay image at once (the cards follow on their next use).
        // A deleted room's overlay files go (its re-encodes are stopped first).
        roomsApi.deleteListener(roomId -> Thread.ofVirtual().start(() -> {
            overlayVideoJobs.stopRoom(roomId);
            overlays.forget(roomId);
            overlayCache.roomDeleted(roomId);
        }));
        roomsApi.editListener((before, after) -> {
            try {
                overlays.roomEdited(after.id(), after.slug(), after.settings(), settings.overlayEnabled());
            } catch (IOException | SQLException e) {
                System.err.println("WARNING: could not render the overlay of room " + after.id() + ": " + e);
            }
            if (before.visibility() == after.visibility() && before.slug().equals(after.slug())) {
                return;
            }
            boolean becamePrivate = after.visibility() == Rooms.Visibility.PRIVATE
                    && before.visibility() != Rooms.Visibility.PRIVATE;
            outputControl.roomEdited(after.id(), becamePrivate);
        });
        new StreamOutputsApi(rooms, streamOutputs, outputControl, config.publicBaseUrl(),
                config.redirectPlainHttp() ? config.insecureBaseUrl() : null).register(appRouter);
        // The encode jobs page: /api/admin/jobs... (admin.jobs) and /api/jobs (a user's own library's items).
        JobList jobList = new JobList(database, ingest.jobs(), Clock.systemUTC());
        jobList.steps(ingest::liveStep);
        new JobsApi(ingest.jobs(), jobList, permissions, ingest::paused, Clock.systemUTC()).register(appRouter);
        // The admin's "Update yt-dlp" button (yt-dlp -U) and the installed version.
        new YtdlpApi(new YtdlpUpdater(Tools.from(config), Clock.systemUTC()), database, permissions, Clock.systemUTC())
                .register(appRouter);
        // The management view's overlay preview (docs/overlay.md#room-ui).
        new OverlayPreviewApi(rooms, overlays, customCards).register(appRouter);
        // Custom card images: /api/admin/cards... (admin.settings) and /api/rooms/{room}/cards...
        new CustomCardsApi(customCards, rooms, permissions, database, () -> outputAssets.encoding().profile(),
                dataDir.tmp(), Clock.systemUTC()).register(appRouter);
        // Queue entries whose item isn't ready show what its ingest is doing (the rooms' ingest messages).
        roomSocket.ingestProgress(IngestProgress.from(jobList), 1000);
        // How many items a new output encoding would re-encode (the admin page asks before saving).
        admin.encodingInfo(new AdminApi.EncodingInfo() {
            @Override
            public OutputEncoding current() {
                return outputAssets.encoding();
            }

            @Override
            public long reencodeCount(OutputEncoding target) throws SQLException {
                return ingest.jobs().countStale(target);
            }
        });
        // New card fonts (a setting, a download that landed, a removed font): the filler cards are made
        // again and items whose cards were drawn with other fonts get a card-only re-encode.
        fonts.changeListener(cardFontsChanged(outputAssets, ingest, System.out, System.err));
        new FontsApi(fonts, settings, permissions, database, itemTexts(database), dataDir.tmp(), Clock.systemUTC())
                .register(appRouter);
        admin.settingsListener(() -> {
            Thread.ofVirtual().start(outputControl::reconcile);
            // cards.font and fonts.cjkVariant: rebuilt (and the cards follow) when the chain changed.
            Thread.ofVirtual().start(fonts::rebuild);
            // ingest.workers, overlay.workers, output.profile and output.videoBitrateKbps take effect
            // without a restart.
            Thread.ofVirtual().start(() -> applyRuntimeSettings(settings, ingest, overlayPool, outputAssets,
                    System.err));
            // overlay.cacheMaxBytes: a smaller cache evicts at once
            overlayCache.trimSoon();
        });
        // The room pages' view of the outputs: `outputs` in `state`, `output.status` on every change.
        OutputStatusFeed outputStatus = new OutputStatusFeed(streamOutputs, outputControl, roomSocket::outputStatus);
        roomSocket.outputs(outputStatus);
        outputControl.reconcileListener(outputStatus::allChanged);
        if (rtmpPushers != null) {
            rtmpPushers.statusListener((roomId, status) -> outputStatus.roomChanged(roomId));
        }
        WebServer web;
        try {
            web = WebServer.start(config.httpBind(), config.httpPort(), appRouter,
                    tmpDir.toFile(), config.trustedProxies());
        } catch (IOException e) {
            System.err.println("Could not listen on " + config.httpBind() + ":" + config.httpPort() + ": " + e);
            ingest.close();
            staticFiles.close();
            database.close();
            System.exit(EXIT_STARTUP);
            return;
        }
        RetentionSweeper retention = new RetentionSweeper(database, items,
                () -> new RetentionSweeper.Limits(settings.retentionUnreferencedDays(),
                        settings.retentionVideoUnviewedDays(), settings.retentionVideoAfterViewHours(),
                        settings.retentionPurgedEntryDays()),
                Clock.systemUTC());
        retention.alsoRun("trimmed room history entries", roomHistory::trim);
        retention.alsoRun("trimmed play history entries", playHistory::trim);
        retention.start();
        // Starts the enabled RTMP pushes, then re-checks every 10 s.
        outputControl.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.close();
            outputControl.close();
            outputStatus.close();
            if (rtmpPushers != null) {
                rtmpPushers.close();
            }
            if (mp3Encoders != null) {
                mp3Encoders.close();
            }
            programOutputs.close();
            overlayPool.close();
            // After the web server (no new requests), before the database (rooms persist on the way out).
            roomRegistry.close();
            retention.close();
            mediaVersions.close();
            // Before the database: running jobs are put back in the queue on the way out.
            ingest.close();
            staticFiles.close();
            fonts.close();
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
    /** The item's {@code media_version} for the program output (0 if unknown); a database error is an I/O error. */
    private static int currentVersion(Database database, String itemId) throws IOException {
        try {
            return MediaVersions.current(database, itemId).orElse(0);
        } catch (SQLException e) {
            throw new IOException("can't read the media version of item " + itemId + ": " + e.getMessage(), e);
        }
    }

    static IngestQueue startIngest(int workers, DataDir dataDir, Database database, OutputAssets outputAssets,
                                   IngestPipeline handler, MediaVersions mediaVersions, DiskSpace diskSpace,
                                   PrintStream err) {
        ReferenceCard referenceCard = outputAssets.reference();
        try {
            int cleaned = IngestPipeline.recoverDirectories(dataDir);
            if (cleaned > 0) {
                err.println("Cleaned up " + cleaned + " directories left by interrupted ingest jobs");
            }
        } catch (IOException e) {
            err.println("WARNING: could not clean up after interrupted ingest jobs: " + e.getMessage());
        }
        try {
            // No player survives a restart: only the current version of each item's files is kept,
            // and the previous one while it is within the undo window.
            int versions = mediaVersions.cleanUpAtStartup();
            if (versions > 0) {
                err.println("Deleted " + versions + " old media versions");
            }
        } catch (IOException | SQLException e) {
            err.println("WARNING: could not delete old media versions: " + e.getMessage());
        }
        IngestJobs jobs = new IngestJobs(database);
        jobs.qualityPreset(handler.presets()::quality);
        // A job that finishes for an encoding that changed while it ran queues a re-encode.
        jobs.currentEncoding(outputAssets::encoding);
        IngestQueue queue = new IngestQueue(jobs, workers, handler);
        queue.pauseWhile(diskSpace::low);
        if (referenceCard != null) {
            try {
                int reencodes = jobs.enqueueReencodes(referenceCard.encoding());
                if (reencodes > 0) {
                    err.println("Queued " + reencodes + " re-encode jobs for output encoding "
                            + referenceCard.encoding().id() + " (items encoded for another profile or video bitrate)");
                }
            } catch (SQLException e) {
                err.println("WARNING: could not queue re-encode jobs for the output profile: " + e.getMessage());
            }
            try {
                int cards = jobs.enqueueCardRemakes(referenceCard.encoding(), cardFontKey());
                if (cards > 0) {
                    err.println("Queued " + cards + " card-only re-encode jobs (items with cards in an older card"
                            + " format or drawn with other fonts)");
                }
            } catch (SQLException e) {
                err.println("WARNING: could not queue card-only re-encode jobs: " + e.getMessage());
            }
            try {
                int quality = jobs.enqueueQualityReencodes();
                if (quality > 0) {
                    err.println("Queued " + quality + " quality re-encode jobs (library items made with the quick preset)");
                }
            } catch (SQLException e) {
                err.println("WARNING: could not queue quality re-encode jobs: " + e.getMessage());
            }
            try {
                int loudness = jobs.enqueueLoudnessReencodes();
                if (loudness > 0) {
                    err.println("Queued " + loudness + " audio re-encode jobs (loudness normalisation doesn't match"
                            + " the media type)");
                }
            } catch (SQLException e) {
                err.println("WARNING: could not queue audio re-encode jobs: " + e.getMessage());
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
     * The startup text check ({@link TextRenderingCheck}): 0 if Java2D can draw text, else the
     * message on {@code err} and {@link #EXIT_STARTUP}.
     */
    static int checkTextRendering(TextRenderingCheck.Probe probe, PrintStream err) {
        String problem = TextRenderingCheck.check(probe);
        if (problem == null) {
            return 0;
        }
        err.println(problem);
        return EXIT_STARTUP;
    }

    /**
     * The card fonts ({@code docs/library-and-ingest.md#card-fonts}) from {@code <data>/fonts} and the
     * settings, installed for every card renderer. If the directory can't be used, cards use the
     * bundled and installed fonts (reported).
     */
    static FontLibrary startFonts(DataDir dataDir, Settings settings, PrintStream err) {
        FontLibrary fonts = new FontLibrary(dataDir.fonts(), FontManifest.load(), new FontDownloader(),
                SystemFonts.installed(), new FontLibrary.Prefs() {
                    @Override
                    public String cardFont() throws SQLException {
                        return settings.cardsFont();
                    }

                    @Override
                    public boolean autoDownload() throws SQLException {
                        return settings.fontsAutoDownload();
                    }

                    @Override
                    public CjkVariant cjkVariant() throws SQLException {
                        return settings.fontsCjkVariant();
                    }
                }, FontLibrary.DEFAULT_BACKOFF, Clock.systemUTC());
        try {
            fonts.start();
        } catch (IOException | RuntimeException e) {
            err.println("WARNING: could not read the card fonts in " + dataDir.fonts() + ": " + e.getMessage());
            fonts.rebuild();
        }
        return fonts;
    }

    /** The fonts key of an item card's text with the card fonts in use ({@link CardRenderer#fontKey}). */
    static IngestJobs.CardFontKey cardFontKey() {
        return (title, artist, album) -> CardRenderer.fontKey(CardFonts.installed(), title, artist, album);
    }

    /**
     * What happens when the card fonts changed: the filler cards are made again for the encoding in
     * use ({@link OutputAssets#refreshCards}) and items whose card was drawn with other fonts get a
     * card-only re-encode ({@link IngestJobs#enqueueCardRemakes(OutputEncoding, IngestJobs.CardFontKey)}).
     * Runs on its own thread, one change at a time.
     */
    static Runnable cardFontsChanged(OutputAssets assets, IngestQueue ingest, PrintStream out, PrintStream err) {
        ReentrantLock lock = new ReentrantLock();
        return () -> Thread.ofVirtual().name("card-fonts-changed").start(() -> {
            lock.lock();
            try {
                if (assets.refreshCards()) {
                    out.println("Card fonts changed: the filler cards are up to date");
                }
                ReferenceCard reference = assets.reference();
                if (reference != null) {
                    int queued = ingest.jobs().enqueueCardRemakes(reference.encoding(), cardFontKey());
                    if (queued > 0) {
                        out.println("Card fonts changed: queued " + queued + " card-only re-encode jobs");
                        ingest.wake();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                err.println("WARNING: could not apply the new card fonts: " + e.getMessage());
            } finally {
                lock.unlock();
            }
        });
    }

    /** Every item's title, artist and album, for the fonts panel's uncovered characters. */
    static FontLibrary.Texts itemTexts(Database database) {
        return consumer -> database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT title, artist, album FROM items");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= 3; i++) {
                        consumer.accept(rs.getString(i));
                    }
                }
            }
            return null;
        });
    }

    /**
     * The x264 presets ({@code ingest.quickPreset}, {@code ingest.qualityPreset}), checked against
     * the reference avcC; a configured preset that fails the check is reported and not used. Without
     * a reference card (no ffmpeg) both are {@link X264Preset#REFERENCE}.
     */
    static EncodePresets encodePresets(Config config, DataDir dataDir, OutputAssets assets, Settings settings,
                                       PrintStream err) {
        if (assets.reference() == null) {
            return EncodePresets.fixed(X264Preset.REFERENCE, X264Preset.REFERENCE);
        }
        EncodePresets presets = EncodePresets.checkedAgainst(assets::reference, Tools.from(config), dataDir.cards(),
                settings::ingestQuickPreset, settings::ingestQualityPreset);
        for (String problem : presets.checkConfigured()) {
            err.println("WARNING: " + problem);
        }
        return presets;
    }

    /**
     * Whether the upload pre-check by content ID is on: only when {@code http.publicBaseUrl} is
     * {@code https}, since browsers compute the ID with {@code crypto.subtle}, which needs a secure
     * context (plain http isn't supported for it: no fallback hasher).
     */
    static boolean uploadPrecheck(Config config) {
        return "https".equalsIgnoreCase(config.publicBaseUri().getScheme());
    }

    /**
     * The upload service ({@link Uploads}): limits from the config, new items recorded for the
     * configured output profile, each queued job wakes the ingest workers, and the guest upload
     * quota comes from the settings.
     */
    static Uploads uploads(Config config, DataDir dataDir, Database database, IngestQueue ingest, Settings settings,
                           OutputAssets outputAssets) {
        Uploads uploads = new Uploads(database, dataDir, Tools.from(config), ingest.jobs(), ingest::wake,
                outputAssets.encoding(), config.ingestMaxUploadBytes(), config.ingestMaxDurationSeconds(),
                new GuestUploadQuota(database, settings, Clock.systemUTC()), Clock.systemUTC());
        uploads.outputEncoding(outputAssets::encoding);
        // ingest.maxUploadBytes and ingest.maxDurationSeconds are admin settings (config = default).
        uploads.limits(() -> setting(settings::ingestMaxUploadBytes, config.ingestMaxUploadBytes()),
                () -> setting(settings::ingestMaxDurationSeconds, config.ingestMaxDurationSeconds()));
        return uploads;
    }

    /**
     * What happens when an admin changed the output encoding ({@link OutputAssets.Listener}): the
     * x264 presets are checked again against the new reference card, every {@code ready} item of
     * another encoding gets a {@code reencode} job ({@link IngestJobs#enqueueReencodes}) and the
     * running program outputs restart on the new encoding ({@link ProgramOutputs#restartRunning}).
     */
    static OutputAssets.Listener encodingChanged(EncodePresets presets, IngestQueue ingest, ProgramOutputs outputs,
                                                 PrintStream out, PrintStream err) {
        return (previous, now) -> {
            presets.forget();
            try {
                int queued = ingest.jobs().enqueueReencodes(now.encoding());
                out.println("Output encoding changed from " + previous.encoding().id() + " to "
                        + now.encoding().id() + ": queued " + queued + " re-encode jobs");
            } catch (SQLException e) {
                err.println("WARNING: could not queue re-encode jobs for output encoding "
                        + now.encoding().id() + ": " + e.getMessage());
            }
            ingest.wake();
            int restarted = outputs.restartRunning("the output encoding changed to " + now.encoding().id());
            if (restarted > 0) {
                out.println("Restarted " + restarted + " program outputs on output encoding " + now.encoding().id());
            }
        };
    }

    /** The rooms' {@link PlayableFiles} check, for the output encoding in use and its reference card. */
    static PlayableFiles playableFiles(DataDir dataDir, OutputAssets assets) {
        return new PlayableFiles((id, version) -> MediaVersions.versionDir(dataDir, id, version), assets::encoding,
                () -> {
                    OutputAssets.Assets current = assets.current();
                    return current.reference() == null ? null : current.reference().avcC();
                });
    }

    /**
     * Reads a setting; if the database can't be read, logs it and returns {@code fallback} (the
     * config file's value).
     */
    /** The ids of all rooms, or null if they can't be read (the overlay cache then keeps every room's files). */
    static java.util.Set<String> roomIds(Database database) {
        try {
            return database.read(c -> {
                java.util.Set<String> ids = new java.util.HashSet<>();
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT id FROM rooms");
                     java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
                return ids;
            });
        } catch (SQLException e) {
            System.err.println("WARNING: could not read the room ids: " + e.getMessage());
            return null;
        }
    }

    static <T> T setting(Callable<T> read, T fallback) {
        try {
            return read.call();
        } catch (Exception e) {
            System.getLogger(Main.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not read a setting; using the config file's value: " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Applies the admin settings that change running parts of the server after an admin saved
     * settings: {@code ingest.workers} resizes the ingest pool, {@code overlay.workers} the overlay
     * encoder pool, {@code output.profile} and
     * {@code output.videoBitrateKbps} switch the output encoding ({@link OutputAssets#follow}:
     * new cards, re-encodes queued, program outputs restarted). Problems are logged.
     */
    static void applyRuntimeSettings(Settings settings, IngestQueue ingest, OverlayPool overlayPool,
                                     OutputAssets outputAssets, PrintStream err) {
        try {
            int workers = settings.ingestWorkers();
            if (workers != ingest.workers()) {
                ingest.resize(workers);
            }
        } catch (SQLException | RuntimeException e) {
            err.println("WARNING: could not apply ingest.workers: " + e.getMessage());
        }
        try {
            int workers = settings.overlayWorkers();
            if (workers != overlayPool.workers()) {
                overlayPool.resize(workers);
            }
        } catch (SQLException | RuntimeException e) {
            err.println("WARNING: could not apply overlay.workers: " + e.getMessage());
        }
        try {
            outputAssets.follow(settings::outputEncoding);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            err.println("WARNING: could not change the output encoding: " + e.getMessage());
        }
    }

    /**
     * The output encoding in use, starting with {@code initial} (the admin setting, else the config
     * file's): its reference card and filler cards are made or checked now
     * ({@link #loadReferenceCard}, {@link #loadFillerCards}), and again for each new encoding an
     * admin picks.
     */
    static OutputAssets outputAssets(Config config, DataDir dataDir, OutputEncoding initial, PrintStream out,
                                     PrintStream err) throws InterruptedException {
        OutputAssets.Maker maker = encoding -> {
            ReferenceCard reference = loadReferenceCard(config, dataDir, encoding, out, err);
            // The packager plays these when a room is paused, idle or loading.
            FillerCards fillers = loadFillerCards(config, dataDir, encoding, reference, out, err);
            return new OutputAssets.Assets(encoding, reference, fillers);
        };
        if (!initial.equals(config.outputEncoding())) {
            out.println("Output encoding " + initial + " (admin setting; the config file says "
                    + config.outputEncoding() + ")");
        }
        return new OutputAssets(maker, maker.make(initial));
    }

    /**
     * The YouTube import service ({@link YoutubeImports}): the duration limit from the config, new
     * items recorded for the configured output profile, each queued job wakes the ingest workers.
     */
    static YoutubeImports youtubeImports(Config config, Database database, IngestQueue ingest,
                                         Supplier<String> cookiesFile) {
        return new YoutubeImports(database, Tools.from(config), ingest.jobs(), ingest::wake, config.outputEncoding(),
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
    static ReferenceCard loadReferenceCard(Config config, DataDir dataDir, OutputEncoding encoding, PrintStream out,
                                           PrintStream err) throws InterruptedException {
        Tools tools = Tools.from(config);
        try {
            ReferenceCard card = ReferenceCard.ensure(tools, dataDir.cards(), encoding, null);
            if (card.created()) {
                out.println("Created the " + encoding + " reference card in " + card.card());
            }
            if (card.encoderChanged()) {
                err.println("WARNING: ffmpeg/x264 no longer reproduces the " + encoding
                        + " reference card's avcC (" + ReferenceCard.encodingDir(dataDir.cards(), encoding)
                        + "). New encodes will fail the avcC check until the library is re-encoded against a new reference.");
            }
            return card;
        } catch (ToolException | IOException e) {
            err.println("WARNING: could not create the " + encoding
                    + " reference card; ingest won't work: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates or refreshes the server filler cards for the configured output profile
     * ({@link FillerCards#ensure}). Like the reference card, a failure is reported and the server
     * keeps starting; returns null then (and when there is no reference card).
     */
    static FillerCards loadFillerCards(Config config, DataDir dataDir, OutputEncoding encoding, ReferenceCard reference,
                                       PrintStream out, PrintStream err) throws InterruptedException {
        if (reference == null) {
            return null;
        }
        try {
            FillerCards cards = FillerCards.ensure(Tools.from(config), dataDir.cards(), reference, null);
            if (cards.encoded() > 0) {
                out.println("Encoded " + cards.encoded() + " " + encoding + " filler card(s) in "
                        + ReferenceCard.encodingDir(dataDir.cards(), encoding));
            }
            return cards;
        } catch (ToolException | IOException | ReferenceCard.AvcCMismatchException e) {
            err.println("WARNING: could not create the " + encoding + " filler cards: " + e.getMessage());
            return null;
        }
    }

    /**
     * The program outputs ({@code docs/program-output.md#lifecycle}). Each start opens a
     * {@link ProgramMedia} on {@code <data>/media} for the output encoding in use then
     * ({@link OutputAssets}); its filler cards are indexed (ffprobe) on the first start with that
     * encoding and kept. Without the reference card, the filler cards or the silence frame every
     * start fails (the warning about them was printed when they were made).
     */
    static ProgramOutputs programOutputs(Config config, DataDir dataDir, Database database, OutputAssets assets,
            SilenceFrame silence, Settings settings) {
        return programOutputs(config, dataDir, database, assets, silence, settings, null);
    }

    /**
     * The x264 preset of an overlay re-encode ({@code docs/overlay.md}): the quick preset, except
     * that the next entry's (normal priority, made ahead) uses the quality preset when
     * {@code overlay.allowQualityPreset} is on. Read when the job starts.
     */
    static OverlayVideoJobs.Presets overlayPresets(Settings settings, EncodePresets presets, PrintStream err) {
        return priority -> {
            if (priority == OverlayPool.Priority.NORMAL) {
                try {
                    if (settings.overlayAllowQualityPreset()) {
                        return presets.quality();
                    }
                } catch (SQLException e) {
                    err.println("WARNING: could not read overlay.allowQualityPreset, using the quick preset: "
                            + e.getMessage());
                }
            }
            return presets.quick();
        };
    }

    /** A room's overlaid cards for an output on the encoding of {@code reference}. */
    @FunctionalInterface
    interface RoomOverlayCards {
        ProgramMedia.OverlayCards forRoom(String roomId, ReferenceCard reference);
    }

    /**
     * {@link #programOutputs(Config, DataDir, Database, OutputAssets, SilenceFrame, Settings)} with
     * the rooms' overlaid cards ({@code docs/overlay.md#cards-cheap-path}); null: no overlay.
     */
    static ProgramOutputs programOutputs(Config config, DataDir dataDir, Database database, OutputAssets assets,
            SilenceFrame silence, Settings settings, RoomOverlayCards overlayCards) {
        ProgramOutputs.ItemKinds kinds = ProgramOutputs.itemKindsFrom(database);
        if (silence == null) {
            return new ProgramOutputs((roomId, cue) -> {
                throw new IOException("program output unavailable: the silence frame could not be created at startup");
            }, kinds, System::currentTimeMillis);
        }
        // The filler cards of the encoding in use, indexed once per encoding.
        record LoadedFillers(OutputAssets.Assets assets, Map<Filler, CardGop> cards) {
        }
        AtomicReference<LoadedFillers> fillers = new AtomicReference<>();
        ReentrantLock fillersLock = new ReentrantLock();
        ProgramPipeline.EncodedOpener opener = roomId -> {
            // Each start uses the encoding in use then, for its sources, cards and muxer alike; the
            // stream is padded to the encoding's cap (normal rooms: stream rate = cap).
            OutputAssets.Assets current = assets.current();
            if (current.reference() == null || current.fillerCards() == null) {
                throw new IOException("program output unavailable: no reference or filler cards for "
                        + current.encoding().id());
            }
            ProgramMedia.CardLoader cardLoader = ProgramMedia.ffprobeCardLoader(Tools.from(config), current.encoding());
            Map<Filler, CardGop> cards;
            fillersLock.lock();
            try {
                LoadedFillers loaded = fillers.get();
                if (loaded == null || loaded.assets() != current) {
                    loaded = new LoadedFillers(current, ProgramMedia.loadFillerCards(current.fillerCards(), cardLoader));
                    fillers.set(loaded);
                }
                cards = loaded.cards();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while loading the filler cards", e);
            } finally {
                fillersLock.unlock();
            }
            byte[] avcC = current.reference().avcC();
            // Each load reads the item's current media version, so a new play uses the newest files.
            ProgramMedia.ItemDirs dirs = id -> MediaVersions.versionDir(dataDir, id, currentVersion(database, id));
            ProgramMedia.OverlayCards overlay = overlayCards == null ? null
                    : overlayCards.forRoom(roomId, current.reference());
            return new ProgramPipeline.Opened(new ProgramMedia(dirs, current.encoding(), avcC, cardLoader,
                    cards, silence.frame(), r -> Thread.ofVirtual().start(r), ProgramMedia.DEFAULT_WAIT_MS,
                    System::currentTimeMillis, overlay), avcC, current.encoding().videoBitrateBps(),
                    audioOnlyBitrateBps(settings));
        };
        return new ProgramOutputs(new ProgramPipeline(opener, TimeSource.system(Clock.systemUTC())),
                kinds, System::currentTimeMillis);
    }

    /**
     * The stream rate of audio-only output ({@code output.audioOnlyBitrateKbps}) in b/s, read when
     * an output starts (a change applies to outputs started afterwards).
     */
    static long audioOnlyBitrateBps(Settings settings) throws IOException {
        try {
            return settings.outputAudioOnlyBitrateKbps() * 1000L;
        } catch (SQLException e) {
            throw new IOException("can't read output.audioOnlyBitrateKbps", e);
        }
    }

    /**
     * The stream outputs' control ({@link StreamOutputControl}): the server switches from the
     * settings, the program outputs, the RTMP pushes (none without ffmpeg) and the {@code .ts}
     * and MP3 connections' revalidation.
     */
    static StreamOutputControl streamOutputControl(StreamOutputs rows, Settings settings, ProgramOutputs outputs,
                                                   RtmpPushers pushers, Runnable revalidate) {
        StreamOutputControl.Switches switches = new StreamOutputControl.Switches() {
            @Override
            public boolean rtmp() throws SQLException {
                return settings.outputsRtmpEnabled();
            }

            @Override
            public boolean ts() throws SQLException {
                return settings.outputsTsEnabled();
            }

            @Override
            public boolean hls() throws SQLException {
                return settings.outputsHlsEnabled();
            }

            @Override
            public boolean mp3() throws SQLException {
                return settings.outputsMp3Enabled();
            }
        };
        StreamOutputControl.Outputs programs = new StreamOutputControl.Outputs() {
            @Override
            public void start(String roomId) throws Exception {
                outputs.touch(roomId);
            }

            @Override
            public void stop(String roomId) {
                outputs.stop(roomId);
            }
        };
        return new StreamOutputControl(rows, switches, programs, pushers, revalidate);
    }

    /** {@code outputs.mp3.bitrateKbps}, or its default if the setting can't be read. */
    static int mp3Bitrate(Settings settings) {
        try {
            return settings.outputsMp3BitrateKbps();
        } catch (SQLException e) {
            return Settings.OUTPUTS_MP3_BITRATE_KBPS.defaultValue();
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
     * ({@code /api/admin/...}), {@link ProvidersApi} the login providers ({@code /api/admin/providers},
     * {@code /api/auth/providers}), {@link AdminActivityApi} the admin guest list, audit log and session
     * IPs ({@code /api/admin/guests}, {@code /api/admin/audit}, {@code /api/admin/ip/...}),
     * {@link UploadsApi} chunked uploads ({@code /api/uploads...}), {@link YoutubeImportsApi} YouTube
     * imports ({@code /api/imports/youtube}), {@link LibraryApi} the caller's library
     * ({@code /api/library...}), {@link ItemsApi} items by id ({@code /api/items/...}),
     * {@link PlaylistsApi} the caller's playlists ({@code /api/playlists...}), {@link UserLibrariesApi} other users' libraries and
     * playlists for {@code library.viewAll} ({@code /api/users...}), {@link MediaApi} the items'
     * media files ({@code /media/{id}/{file}}), {@link RoomsApi} rooms and room roles
     * ({@code /api/rooms...}), {@link DiagnosticsApi} the room page's listen-mode diagnostics report
     * ({@code /api/diagnostics}), {@link RoomSocket} the room WebSocket ({@code /ws}), {@link RoomLinks} the room short link
     * {@code /r/{slug}}. The web UI is a single-page app: every UI path ({@code /}, {@code /rooms/{ref}},
     * {@code /items/{id}}, …; {@link StaticFiles#isAppPath}) without a file of its own gets its
     * {@code index.html} from the catch-all route.
     */
    static Router router(Config config, StaticFiles staticFiles, ActorResolver actors, SetupApi setup, AuthApi auth,
                         SessionsApi sessions, MeApi me, AdminApi admin, ProvidersApi providers,
                         AdminActivityApi activity,
                         UploadsApi uploads, YoutubeImportsApi youtube, LibraryApi library,
                         ItemsApi items, PlaylistsApi playlists,
                         UserLibrariesApi userLibraries, MediaApi media, RoomsApi rooms, RoomSocket roomSocket) {
        Csrf csrf = new Csrf(config.publicBaseUrl(),
                List.of(ActorResolver.SESSION_COOKIE, ActorResolver.GUEST_COOKIE));
        Router router = new Router(actors.hook(), csrf.hook());
        setup.register(router);
        auth.register(router);
        sessions.register(router);
        me.register(router);
        admin.register(router);
        providers.register(router);
        activity.register(router);
        uploads.register(router);
        youtube.register(router);
        library.register(router);
        items.register(router);
        playlists.register(router);
        userLibraries.register(router);
        media.register(router);
        rooms.register(router);
        DiagnosticsApi.create(Clock.systemUTC()).register(router);
        WebSockets webSockets = new WebSockets(csrf);
        EchoEndpoint.register(router, webSockets);
        roomSocket.register(router, webSockets);
        // The QR short link /r/{slug} → the room view in remote mode.
        RoomLinks.register(router);
        // The web UI: static files, and index.html for every other UI path (the single-page app).
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
        out.println("Public URL " + config.publicBaseUrl()
                + (config.insecureBaseUrl() == null ? "" : " (plain-HTTP streams at " + config.insecureBaseUrl() + ")")
                + ", listening on " + config.httpBind() + ":" + config.httpPort()
                + ", data in " + config.dataDir() + ", output encoding " + config.outputEncoding());
        return new Startup(0, config);
    }

    private static String usage() {
        return "Usage: java -jar TogetherTunes.jar [--config <file>]\n"
                + "  The config file is JSON. Without --config, " + ConfigLoader.CONFIG_FILE_ENV
                + " names it, else ./" + ConfigLoader.DEFAULT_CONFIG_FILE + " is used if present.\n"
                + "  Every key can be overridden by an environment variable, e.g. "
                + ConfigLoader.envName(ConfigLoader.HTTP_PUBLIC_BASE_URL) + ".\n"
                + "  " + ConfigLoader.HTTP_INSECURE_BASE_URL + " (optional, http://): the plain-HTTP address of this server."
                + " When set, plain-HTTP\n"
                + "  requests are redirected to " + ConfigLoader.HTTP_PUBLIC_BASE_URL + ", except public and unlisted"
                + " rooms' stream URLs\n"
                + "  (for players that can't use HTTPS).";
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
