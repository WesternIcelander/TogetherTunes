package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.EncodePresets;
import io.siggi.togethertunes.ingest.FillerCards;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestQueue;
import io.siggi.togethertunes.ingest.OutputAssets;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.CardRenderer.Filler;
import io.siggi.togethertunes.output.ProgramOutputs;
import io.siggi.togethertunes.settings.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Changing {@code output.profile} / {@code output.videoBitrateKbps} and {@code ingest.workers} in
 * the admin settings takes effect without a restart, wired as {@link Main} does it: the new
 * encoding's reference and filler cards are made and swapped in, every item re-encodes, and the
 * ingest pool resizes. Needs ffmpeg and ffprobe on the PATH (skipped otherwise).
 */
class MainEncodingTest {
    @TempDir
    Path dir;

    private Database db;
    private IngestQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static String onPath(String name) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String candidate : List.of(entry, "/opt/homebrew/bin", "/usr/local/bin")) {
                Path p = Path.of(candidate, name);
                if (Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    private String readyItem(String outputProfile) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            String user = UuidV7.newId();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, ?, ?, ?, 0)")) {
                ps.setString(1, user);
                ps.setString(2, "u" + user.substring(user.length() - 8));
                ps.setString(3, "uploader");
                ps.setLong(4, 1_700_000_000_000L);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', 'Song', 'upload', ?, ?, 'ready', ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, user);
                ps.setLong(3, 1_700_000_000_000L);
                ps.setString(4, outputProfile);
                ps.setLong(5, 1_700_000_000_000L);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Test
    @Timeout(300)
    void encodingAndWorkersFollowTheAdminSettingsWithoutARestart() throws Exception {
        String ffmpeg = onPath("ffmpeg");
        String ffprobe = onPath("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null, "ffmpeg/ffprobe not on PATH");
        Config config = new Config("127.0.0.1", 0, "http://localhost", List.of(), dir.resolve("data"),
                "jdbc:sqlite:" + dir.resolve("test.db"), ffmpeg, ffprobe, null, OutputProfile.P720_30, 3000, 1,
                Settings.GIB, 1800, X264Preset.VERYFAST, X264Preset.VERYFAST, 1, Settings.GIB);
        DataDir dataDir = DataDir.create(config.dataDir());
        db = Database.open(config.dbUrl());
        MigrationRunner.migrate(db);
        Settings settings = new Settings(db, config);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(log, true, StandardCharsets.UTF_8);

        OutputAssets assets = Main.outputAssets(config, dataDir, settings.outputEncoding(), out, out);
        OutputEncoding initial = OutputEncoding.DEFAULT;
        assertEquals(initial, assets.encoding());
        assertNotNull(assets.reference());
        assertNotNull(assets.current().fillerCards());

        IngestJobs jobs = new IngestJobs(db);
        jobs.currentEncoding(assets::encoding);
        queue = new IngestQueue(jobs, 1, job -> { });
        EncodePresets presets = EncodePresets.checkedAgainst(assets::reference, io.siggi.togethertunes.ingest.Tools.from(config),
                dataDir.cards(), settings::ingestQuickPreset, settings::ingestQualityPreset);
        assertTrue(presets.matches(X264Preset.VERYFAST));
        ProgramOutputs outputs = new ProgramOutputs((roomId, cue) -> {
            throw new IOException("no outputs in this test");
        }, itemId -> null, System::currentTimeMillis);
        assets.addListener(Main.encodingChanged(presets, queue, outputs, out, out));
        String current = readyItem(initial.id());
        String older = readyItem("720p30");

        // Nothing changed: nothing happens.
        Main.applyRuntimeSettings(settings, queue, assets, out);
        assertEquals(initial, assets.encoding());
        assertTrue(jobs.list(IngestJob.State.QUEUED, 10).isEmpty());

        // An admin lowers the bitrate and raises the worker count.
        settings.setJson("output.videoBitrateKbps", "2000");
        settings.setJson("ingest.workers", "3");
        Main.applyRuntimeSettings(settings, queue, assets, out);
        OutputEncoding lower = new OutputEncoding(OutputProfile.P720_30, 2000);
        assertEquals(lower, assets.encoding());
        assertEquals(3, queue.workers());
        ReferenceCard reference = assets.reference();
        assertEquals(lower, reference.encoding());
        assertTrue(Files.isRegularFile(reference.card()), reference.card().toString());
        assertTrue(reference.card().startsWith(dataDir.cards().resolve(lower.id())));
        FillerCards fillers = assets.current().fillerCards();
        assertEquals(lower, fillers.encoding());
        for (Filler f : Filler.values()) {
            assertTrue(Files.isRegularFile(fillers.card(f)), fillers.card(f).toString());
        }
        // the old encoding's cards stay where they were (a switch back reuses them)
        assertTrue(Files.isDirectory(ReferenceCard.encodingDir(dataDir.cards(), initial)));
        // Both items now re-encode; the presets are checked against the new reference.
        List<IngestJob> queued = jobs.list(IngestJob.State.QUEUED, 10);
        assertEquals(2, queued.size());
        for (IngestJob j : queued) {
            assertEquals(IngestJob.Kind.REENCODE, j.kind());
            assertTrue(List.of(current, older).contains(j.itemId()));
        }
        assertTrue(presets.matches(X264Preset.VERYFAST));
        String text = log.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Output encoding changed from " + initial.id() + " to " + lower.id()
                + ": queued 2 re-encode jobs"), text);
        assertEquals(2, jobs.countStale(lower));

        // A reset goes back to the config's encoding (cards already there).
        settings.reset(Settings.OUTPUT_VIDEO_BITRATE_KBPS);
        Main.applyRuntimeSettings(settings, queue, assets, out);
        assertEquals(initial, assets.encoding());
        assertFalse(text.contains("WARNING"), text);
        outputs.close();
    }
}
