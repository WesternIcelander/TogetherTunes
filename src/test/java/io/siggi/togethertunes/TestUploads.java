package io.siggi.togethertunes;

import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.library.Uploads;
import io.siggi.togethertunes.library.UploadsApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;

/** The upload service and API for router tests: data in {@code <dir>/data}, jobs queued without workers. */
public final class TestUploads {
    private TestUploads() {
    }

    public static Uploads uploads(Database db, Path dir, Config config, Tools tools, Clock clock) {
        try {
            return new Uploads(db, DataDir.create(dir.resolve("data")), tools, new IngestJobs(db, clock), () -> {
            }, config.outputProfile(), config.ingestMaxUploadBytes(), config.ingestMaxDurationSeconds(), clock);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static UploadsApi api(Database db, Path dir, Config config, Permissions permissions, Clock clock) {
        return new UploadsApi(uploads(db, dir, config, Tools.from(config), clock), permissions);
    }
}
