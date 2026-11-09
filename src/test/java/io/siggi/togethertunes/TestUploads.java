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

    /** The add-media policy as {@code Main} builds it: no rooms yet. */
    public static AddMediaPolicy policy(Permissions permissions, Settings settings) {
        return new AddMediaPolicy(permissions, settings, RoomGuestRules.NO_ROOMS);
    }
}
