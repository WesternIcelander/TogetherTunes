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
import io.siggi.togethertunes.library.Shares;
import io.siggi.togethertunes.library.SharesApi;
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

    public static Uploads uploads(Database db, Path dir, Config config, Tools tools, Clock clock) {
        try {
            return new Uploads(db, DataDir.create(dir.resolve("data")), tools, new IngestJobs(db, clock), () -> {
            }, config.outputProfile(), config.ingestMaxUploadBytes(), config.ingestMaxDurationSeconds(),
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
        }, config.outputProfile(), config.ingestMaxDurationSeconds(), () -> null, clock);
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

    /** The shares API over YouTube imports with the configured tools. */
    public static SharesApi sharesApi(Database db, Config config, Permissions permissions, Clock clock) {
        return new SharesApi(new Shares(db, youtube(db, config, Tools.from(config), clock), clock),
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
            return new ItemsApi(new Items(db, DataDir.create(dir.resolve("data")), clock), permissions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The media files API with data in {@code <dir>/data}, rooms stubbed by {@code playing}. */
    public static MediaApi mediaApi(Database db, Path dir, Settings settings,
                                    io.siggi.togethertunes.rooms.PlayingItems playing) {
        try {
            return new MediaApi(db, DataDir.create(dir.resolve("data")), settings, playing);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The rooms API as {@code Main} builds it. */
    public static io.siggi.togethertunes.rooms.RoomsApi roomsApi(Database db, Settings settings, Permissions permissions,
                                                                 Clock clock) {
        io.siggi.togethertunes.rooms.Rooms rooms = new io.siggi.togethertunes.rooms.Rooms(db, permissions, clock);
        io.siggi.togethertunes.rooms.RoomRegistry registry = new io.siggi.togethertunes.rooms.RoomRegistry(db, clock,
                io.siggi.togethertunes.rooms.PackagerHook.NONE);
        io.siggi.togethertunes.rooms.RoomGuests guests = new io.siggi.togethertunes.rooms.RoomGuests(db, settings,
                permissions, rooms, registry);
        return new io.siggi.togethertunes.rooms.RoomsApi(rooms, registry, permissions, guests,
                new io.siggi.togethertunes.rooms.RequestSearch(db, rooms, guests));
    }

    /** An add-media policy without rooms (every room {@code 404 room_not_found}). */
    public static AddMediaPolicy policy(Permissions permissions, Settings settings) {
        return new AddMediaPolicy(permissions, settings, RoomGuestRules.NO_ROOMS);
    }
}
