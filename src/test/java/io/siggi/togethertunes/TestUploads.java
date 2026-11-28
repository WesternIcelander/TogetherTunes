package io.siggi.togethertunes;

import io.siggi.togethertunes.auth.GuestNames;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.library.AddMediaPolicy;
import io.siggi.togethertunes.library.GuestUploadQuota;
import io.siggi.togethertunes.library.Items;
import io.siggi.togethertunes.library.ItemsApi;
import io.siggi.togethertunes.library.MediaApi;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.library.LibraryApi;
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.library.PlaylistsApi;
import io.siggi.togethertunes.library.UserLibraries;
import io.siggi.togethertunes.library.UserLibrariesApi;
import io.siggi.togethertunes.library.Uploads;
import io.siggi.togethertunes.library.UploadsApi;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.library.YoutubeImportsApi;
import io.siggi.togethertunes.rooms.RoomGuestRules;
import io.siggi.togethertunes.settings.Settings;
import io.siggi.togethertunes.youtube.FakeYtSucks;
import io.siggi.togethertunes.youtube.YoutubeService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;

/**
 * The upload and YouTube import services and APIs for router tests: data in {@code <dir>/data},
 * jobs queued without workers.
 */
public final class TestUploads {
    private TestUploads() {
    }

    /** The login provider API (with the login flow routes) with a fixed test key (all zero bytes). */
    public static io.siggi.togethertunes.auth.ProvidersApi providersApi(Database db, Config config, Settings settings,
            io.siggi.togethertunes.auth.Accounts accounts) {
        var providers = new io.siggi.togethertunes.auth.Providers(db,
                new io.siggi.togethertunes.secrets.SecretCipher(new byte[32]));
        var api = new io.siggi.togethertunes.auth.ProvidersApi(providers, new Permissions(db, settings), accounts,
                settings, config.publicBaseUrl());
        // with the login routes, as Main wires them
        var login = new io.siggi.togethertunes.auth.OidcLogin(providers,
                new io.siggi.togethertunes.auth.OidcDiscovery(), config.publicBaseUrl(), config.secureCookies());
        var identities = new io.siggi.togethertunes.auth.Identities(db);
        login.completion(new io.siggi.togethertunes.auth.IdentityResolution(identities,
                new io.siggi.togethertunes.auth.Sessions(db), config.secureCookies()));
        api.loginFlow(login);
        api.identities(new io.siggi.togethertunes.auth.IdentitiesApi(identities));
        return api;
    }

    /**
     * {@code admin} with the library import routes ({@code /api/admin/users/{id}/library-import...}),
     * importing from {@code allowedPaths}; returns it.
     */
    public static io.siggi.togethertunes.auth.AdminApi withLibraryImport(io.siggi.togethertunes.auth.AdminApi admin,
            Database db, Path dir, Config config, Permissions permissions, java.util.List<Path> allowedPaths,
            Clock clock) {
        Tools tools = Tools.from(config);
        var imports = new io.siggi.togethertunes.library.LibraryImports(db, uploads(db, dir, config, tools, clock),
                new Playlists(db, youtube(db, config, YoutubeService.none(), clock), clock), dir.resolve("data/tmp/imports"),
                allowedPaths, clock);
        var zips = new io.siggi.togethertunes.library.LibraryImportUploads(imports, config.libraryImportMaxZipBytes(),
                clock);
        admin.libraryImportRoutes(new io.siggi.togethertunes.library.LibraryImportApi(imports, zips, permissions)::register);
        return admin;
    }

    public static Uploads uploads(Database db, Path dir, Config config, Tools tools, Clock clock) {
        try {
            return new Uploads(db, DataDir.create(dir.resolve("data")), tools, new IngestJobs(db, clock), () -> {
            }, config.outputEncoding(), config.ingestMaxUploadBytes(), config.ingestMaxDurationSeconds(),
                    new GuestUploadQuota(db, new Settings(db, config), clock), clock);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static UploadsApi api(Database db, Path dir, Config config, Permissions permissions, Clock clock) {
        Settings settings = new Settings(db, config);
        return new UploadsApi(uploads(db, dir, config, Tools.from(config), clock), policy(permissions, settings),
                new Guests(db, settings, clock, config.secureCookies(), GuestNames.bundled()));
    }

    /** YouTube imports through {@code youtube} (e.g. a {@code FakeYtSucks}, or none). */
    public static YoutubeImports youtube(Database db, Config config, YoutubeService youtube, Clock clock) {
        return new YoutubeImports(db, youtube, new IngestJobs(db, clock), () -> {
        }, config.outputEncoding(), config.ingestMaxDurationSeconds(), clock);
    }

    public static YoutubeImportsApi youtubeApi(Database db, Config config, Permissions permissions, Clock clock) {
        return new YoutubeImportsApi(youtube(db, config, YoutubeService.none(), clock),
                policy(permissions, new Settings(db, config)));
    }

    /** The library API over YouTube imports with the configured tools. */
    public static LibraryApi libraryApi(Database db, Config config, Permissions permissions, Clock clock) {
        return new LibraryApi(new Library(db, youtube(db, config, YoutubeService.none(), clock), clock),
                policy(permissions, new Settings(db, config)));
    }

    /** The playlists API over YouTube imports with the configured tools. */
    public static PlaylistsApi playlistsApi(Database db, Config config, Permissions permissions, Clock clock) {
        return new PlaylistsApi(new Playlists(db, youtube(db, config, YoutubeService.none(), clock), clock),
                policy(permissions, new Settings(db, config)));
    }

    /** The {@code library.viewAll} API over YouTube imports with the configured tools. */
    public static UserLibrariesApi userLibrariesApi(Database db, Config config, Permissions permissions, Clock clock) {
        YoutubeImports youtube = youtube(db, config, YoutubeService.none(), clock);
        return new UserLibrariesApi(new UserLibraries(db, new Library(db, youtube, clock),
                new Playlists(db, youtube, clock)), permissions);
    }

    /** The items API with data in {@code <dir>/data}. */
    public static ItemsApi itemsApi(Database db, Path dir, Permissions permissions, Clock clock) {
        return itemsApi(db, dir, permissions, clock, null);
    }

    /**
     * The items API with data in {@code <dir>/data}; with {@code roomGuests}, the room form of the
     * YouTube search ({@code GET /api/rooms/{room}/youtube-search}) by those rooms' rules.
     */
    public static ItemsApi itemsApi(Database db, Path dir, Permissions permissions, Clock clock,
                                    io.siggi.togethertunes.rooms.RoomGuests roomGuests) {
        try {
            DataDir data = DataDir.create(dir.resolve("data"));
            Items items = new Items(db, data, clock);
            // undo with the default window (media.undoHours 48)
            io.siggi.togethertunes.ingest.MediaVersions versions = new io.siggi.togethertunes.ingest.MediaVersions(
                    data, db, clock::millis, io.siggi.togethertunes.ingest.MediaVersions.GRACE);
            versions.undoWindow(() -> 48 * 3_600_000L);
            items.mediaVersions(versions);
            io.siggi.togethertunes.ingest.IngestJobs jobs = new io.siggi.togethertunes.ingest.IngestJobs(db, clock);
            items.trimJobs(jobs);
            ItemsApi api = new ItemsApi(items, permissions);
            // looking details up online, with a fake TrackSplitter that finds one track
            io.siggi.togethertunes.library.FakeTrackSplitter fake =
                    new io.siggi.togethertunes.library.FakeTrackSplitter(dir.resolve("tracksplitter"));
            fake.respond(io.siggi.togethertunes.library.FakeTrackSplitter.singleTrack(
                    fake.artwork("catalogue-1.jpg", 32, 32), "name"), 0);
            api.lookup(new io.siggi.togethertunes.library.MetadataLookupApi(items, permissions,
                    new io.siggi.togethertunes.settings.Settings(db),
                    new io.siggi.togethertunes.library.MetadataLookup(fake.script.toString(), fake.cacheDir,
                            "TogetherTunes/test", null, clock),
                    new io.siggi.togethertunes.ingest.Tools(onPath("ffmpeg"), null, null), data, jobs, clock));
            // Replace artwork: an image body on the same cover route
            api.coverUpload(new io.siggi.togethertunes.library.CoverUpload(items,
                    new io.siggi.togethertunes.ingest.Tools(onPath("ffmpeg"), null, null), data, jobs));
            // "Find a music video": a fake YouTube service whose search finds one video (no MVFinder)
            YoutubeService ytService = searchService().service();
            YoutubeImports youtube = new YoutubeImports(db, ytService, jobs, () -> {
            }, io.siggi.togethertunes.config.OutputEncoding.DEFAULT, 3600, clock);
            api.videoSearch(new io.siggi.togethertunes.library.VideoSearchApi(
                    new io.siggi.togethertunes.library.VideoSearch(db, ytService, youtube, null, () -> 1000,
                            clock), permissions));
            // The YouTube search: the same fake service (no MVFinder)
            io.siggi.togethertunes.library.YoutubeSearchApi youtubeSearch =
                    new io.siggi.togethertunes.library.YoutubeSearchApi(new io.siggi.togethertunes.library.YoutubeSearch(
                            ytService, null, () -> 1000, clock), permissions);
            if (roomGuests != null) {
                youtubeSearch.roomRules(roomGuests::requireYoutubeRequests);
            }
            api.youtubeSearch(youtubeSearch);
            return api;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The video the test items API's "Find a music video" search finds. */
    public static final String FOUND_VIDEO_ID = "fOuNdViDe01";

    private static FakeYtSucks searchService;

    /** One fake YouTube service for every test items API: its search finds {@link #FOUND_VIDEO_ID}, any video looks up. */
    static synchronized FakeYtSucks searchService() throws IOException {
        if (searchService == null) {
            searchService = new FakeYtSucks();
            searchService.anyVideo = true;
            searchService.searchBody = FakeYtSucks.results(new String[] {FOUND_VIDEO_ID, "Found Video", "Channel", "200"});
        }
        return searchService;
    }

    /** A tool's path when it is on PATH (setting a found cover scales it with ffmpeg), else null. */
    static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String d : path.split(java.io.File.pathSeparator)) {
            Path p = Path.of(d).resolve(name);
            if (java.nio.file.Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    /** The media files API with data in {@code <dir>/data}, the rooms' guest access stubbed by {@code access}. */
    public static MediaApi mediaApi(Database db, Path dir, io.siggi.togethertunes.rooms.GuestMediaAccess access) {
        return mediaApi(db, dir, access, java.time.Clock.systemUTC());
    }

    /** {@link #mediaApi(Database, Path, io.siggi.togethertunes.rooms.GuestMediaAccess)} on {@code clock} (views). */
    public static MediaApi mediaApi(Database db, Path dir, io.siggi.togethertunes.rooms.GuestMediaAccess access,
                                    java.time.Clock clock) {
        try {
            return new MediaApi(db, DataDir.create(dir.resolve("data")), access, clock);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The rooms API and the room WebSocket on one room registry, as {@code Main} builds them. */
    public record RoomsWiring(io.siggi.togethertunes.rooms.RoomsApi api, io.siggi.togethertunes.rooms.RoomSocket socket,
                              io.siggi.togethertunes.rooms.RoomRegistry registry,
                              io.siggi.togethertunes.rooms.RoomGuests guests) {
    }

    /** {@link RoomsWiring}: data in {@code <dir>/data}, {@code tickets} shared with the {@code MeApi}. */
    public static RoomsWiring rooms(Database db, Path dir, Config config, Settings settings, Permissions permissions,
                                    io.siggi.togethertunes.auth.ActorResolver actors,
                                    io.siggi.togethertunes.auth.IdentityTickets tickets, Clock clock) {
        return rooms(db, dir, config, settings, permissions, actors, tickets, clock, YoutubeService.none());
    }

    /** {@link RoomsWiring} whose YouTube imports go to {@code ytService}. */
    public static RoomsWiring rooms(Database db, Path dir, Config config, Settings settings, Permissions permissions,
                                    io.siggi.togethertunes.auth.ActorResolver actors,
                                    io.siggi.togethertunes.auth.IdentityTickets tickets, Clock clock,
                                    YoutubeService ytService) {
        io.siggi.togethertunes.rooms.Rooms rooms = new io.siggi.togethertunes.rooms.Rooms(db, permissions, clock);
        io.siggi.togethertunes.rooms.RoomRegistry registry = new io.siggi.togethertunes.rooms.RoomRegistry(db, clock,
                io.siggi.togethertunes.rooms.PackagerHook.NONE);
        io.siggi.togethertunes.rooms.RoomGuests guests = new io.siggi.togethertunes.rooms.RoomGuests(db, settings,
                permissions, rooms, registry);
        // the rooms hear about status changes of the jobs imports and retries queue, as in Main
        IngestJobs youtubeJobs = new IngestJobs(db, clock);
        youtubeJobs.statusListener(registry);
        YoutubeImports youtube = new YoutubeImports(db, ytService, youtubeJobs, () -> {
        }, config.outputEncoding(), config.ingestMaxDurationSeconds(), clock);
        AddMediaPolicy addMedia = new AddMediaPolicy(permissions, settings, guests);
        io.siggi.togethertunes.rooms.RoomsApi api = new io.siggi.togethertunes.rooms.RoomsApi(rooms, registry,
                permissions, guests, new io.siggi.togethertunes.rooms.RequestSearch(db, rooms, guests),
                new io.siggi.togethertunes.rooms.RoomHistory(db, rooms, settings::roomsRequestHistoryDays, clock));
        alone(api, db, rooms, registry, settings, clock);
        io.siggi.togethertunes.library.Playlists playlists = new io.siggi.togethertunes.library.Playlists(db, youtube,
                clock);
        io.siggi.togethertunes.rooms.AutoplaySelections selections =
                new io.siggi.togethertunes.rooms.AutoplaySelections(rooms, playlists, registry);
        api.autoplay(selections);
        io.siggi.togethertunes.rooms.RoomQueue queue = new io.siggi.togethertunes.rooms.RoomQueue(registry, db,
                uploads(db, dir, config, Tools.from(config), clock), youtube,
                playlists, addMedia, new java.util.Random(1));
        io.siggi.togethertunes.rooms.RoomSocket socket = new io.siggi.togethertunes.rooms.RoomSocket(actors, tickets,
                new Guests(db, settings, clock, config.secureCookies(), GuestNames.bundled()), settings, rooms, guests,
                registry, queue, db, clock);
        socket.autoplay(selections); // autoplay.resume
        return new RoomsWiring(api, socket, registry, guests);
    }

    /** The rooms API as {@code Main} builds it. */
    public static io.siggi.togethertunes.rooms.RoomsApi roomsApi(Database db, Config config, Settings settings,
                                                                 Permissions permissions, Clock clock) {
        io.siggi.togethertunes.rooms.Rooms rooms = new io.siggi.togethertunes.rooms.Rooms(db, permissions, clock);
        io.siggi.togethertunes.rooms.RoomRegistry registry = new io.siggi.togethertunes.rooms.RoomRegistry(db, clock,
                io.siggi.togethertunes.rooms.PackagerHook.NONE);
        io.siggi.togethertunes.rooms.RoomGuests guests = new io.siggi.togethertunes.rooms.RoomGuests(db, settings,
                permissions, rooms, registry);
        io.siggi.togethertunes.rooms.RoomsApi api = new io.siggi.togethertunes.rooms.RoomsApi(rooms, registry,
                permissions, guests, new io.siggi.togethertunes.rooms.RequestSearch(db, rooms, guests),
                new io.siggi.togethertunes.rooms.RoomHistory(db, rooms, settings::roomsRequestHistoryDays, clock));
        alone(api, db, rooms, registry, settings, clock);
        api.autoplay(new io.siggi.togethertunes.rooms.AutoplaySelections(rooms,
                new io.siggi.togethertunes.library.Playlists(db, youtube(db, config, YoutubeService.none(), clock), clock)));
        return api;
    }

    /** The alone state on the rooms API, as in {@code Main}. */
    private static void alone(io.siggi.togethertunes.rooms.RoomsApi api, Database db,
                              io.siggi.togethertunes.rooms.Rooms rooms, io.siggi.togethertunes.rooms.RoomRegistry registry,
                              Settings settings, Clock clock) {
        api.aloneSessions(new io.siggi.togethertunes.rooms.AloneSessions(db, rooms, registry, clock),
                new io.siggi.togethertunes.rooms.PlayHistory(db, settings::roomsRequestHistoryDays, clock));
    }

    /** An add-media policy without rooms (every room {@code 404 room_not_found}). */
    public static AddMediaPolicy policy(Permissions permissions, Settings settings) {
        return new AddMediaPolicy(permissions, settings, RoomGuestRules.NO_ROOMS);
    }
}
