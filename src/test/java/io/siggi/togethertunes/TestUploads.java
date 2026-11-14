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
                config.publicBaseUrl());
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

    /** YouTube imports with the configured tools (no cookies file). */
    public static YoutubeImports youtube(Database db, Config config, Tools tools, Clock clock) {
        return new YoutubeImports(db, tools, new IngestJobs(db, clock), () -> {
        }, config.outputEncoding(), config.ingestMaxDurationSeconds(), () -> null, clock);
    }

    public static YoutubeImportsApi youtubeApi(Database db, Config config, Permissions permissions, Clock clock) {
        return new YoutubeImportsApi(youtube(db, config, Tools.from(config), clock),
                policy(permissions, new Settings(db, config)));
    }

    /** The library API over YouTube imports with the configured tools. */
    public static LibraryApi libraryApi(Database db, Config config, Permissions permissions, Clock clock) {
        return new LibraryApi(new Library(db, youtube(db, config, Tools.from(config), clock), clock),
                policy(permissions, new Settings(db, config)));
    }

    /** The playlists API over YouTube imports with the configured tools. */
    public static PlaylistsApi playlistsApi(Database db, Config config, Permissions permissions, Clock clock) {
        return new PlaylistsApi(new Playlists(db, youtube(db, config, Tools.from(config), clock), clock),
                policy(permissions, new Settings(db, config)));
    }

    /** The {@code library.viewAll} API over YouTube imports with the configured tools. */
    public static UserLibrariesApi userLibrariesApi(Database db, Config config, Permissions permissions, Clock clock) {
        YoutubeImports youtube = youtube(db, config, Tools.from(config), clock);
        return new UserLibrariesApi(new UserLibraries(db, new Library(db, youtube, clock),
                new Playlists(db, youtube, clock)), permissions);
    }

    /** The items API with data in {@code <dir>/data}. */
    public static ItemsApi itemsApi(Database db, Path dir, Permissions permissions, Clock clock) {
        try {
            DataDir data = DataDir.create(dir.resolve("data"));
            Items items = new Items(db, data, clock);
            // undo with the default window (media.undoHours 48)
            io.siggi.togethertunes.ingest.MediaVersions versions = new io.siggi.togethertunes.ingest.MediaVersions(
                    data, db, clock::millis, io.siggi.togethertunes.ingest.MediaVersions.GRACE);
            versions.undoWindow(() -> 48 * 3_600_000L);
            items.mediaVersions(versions);
            items.trimJobs(new io.siggi.togethertunes.ingest.IngestJobs(db, clock));
            return new ItemsApi(items, permissions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
                              io.siggi.togethertunes.rooms.RoomRegistry registry) {
    }

    /** {@link RoomsWiring}: data in {@code <dir>/data}, {@code tickets} shared with the {@code MeApi}. */
    public static RoomsWiring rooms(Database db, Path dir, Config config, Settings settings, Permissions permissions,
                                    io.siggi.togethertunes.auth.ActorResolver actors,
                                    io.siggi.togethertunes.auth.IdentityTickets tickets, Clock clock) {
        io.siggi.togethertunes.rooms.Rooms rooms = new io.siggi.togethertunes.rooms.Rooms(db, permissions, clock);
        io.siggi.togethertunes.rooms.RoomRegistry registry = new io.siggi.togethertunes.rooms.RoomRegistry(db, clock,
                io.siggi.togethertunes.rooms.PackagerHook.NONE);
        io.siggi.togethertunes.rooms.RoomGuests guests = new io.siggi.togethertunes.rooms.RoomGuests(db, settings,
                permissions, rooms, registry);
        // the rooms hear about status changes of the jobs imports and retries queue, as in Main
        IngestJobs youtubeJobs = new IngestJobs(db, clock);
        youtubeJobs.statusListener(registry);
        YoutubeImports youtube = new YoutubeImports(db, Tools.from(config), youtubeJobs, () -> {
        }, config.outputEncoding(), config.ingestMaxDurationSeconds(), () -> null, clock);
        AddMediaPolicy addMedia = new AddMediaPolicy(permissions, settings, guests);
        io.siggi.togethertunes.rooms.RoomsApi api = new io.siggi.togethertunes.rooms.RoomsApi(rooms, registry,
                permissions, guests, new io.siggi.togethertunes.rooms.RequestSearch(db, rooms, guests),
                new io.siggi.togethertunes.rooms.RoomHistory(db, rooms, settings::roomsRequestHistoryDays, clock));
        alone(api, db, rooms, registry, settings, clock);
        io.siggi.togethertunes.library.Playlists playlists = new io.siggi.togethertunes.library.Playlists(db, youtube,
                clock);
        api.autoplay(new io.siggi.togethertunes.rooms.AutoplaySelections(rooms, playlists));
        io.siggi.togethertunes.rooms.RoomQueue queue = new io.siggi.togethertunes.rooms.RoomQueue(registry, db,
                uploads(db, dir, config, Tools.from(config), clock), youtube,
                playlists, addMedia, new java.util.Random(1));
        io.siggi.togethertunes.rooms.RoomSocket socket = new io.siggi.togethertunes.rooms.RoomSocket(actors, tickets,
                new Guests(db, settings, clock, config.secureCookies(), GuestNames.bundled()), settings, rooms, guests,
                registry, queue, db, clock);
        return new RoomsWiring(api, socket, registry);
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
                new io.siggi.togethertunes.library.Playlists(db, youtube(db, config, Tools.from(config), clock), clock)));
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
