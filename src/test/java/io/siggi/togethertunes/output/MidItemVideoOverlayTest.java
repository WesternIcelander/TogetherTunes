package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.overlay.OverlayCards;
import io.siggi.togethertunes.overlay.OverlayDecisions;
import io.siggi.togethertunes.overlay.OverlayPool;
import io.siggi.togethertunes.overlay.OverlayRenderer;
import io.siggi.togethertunes.overlay.OverlayVideoJobs;
import io.siggi.togethertunes.overlay.Overlays;
import io.siggi.togethertunes.rooms.RoomSettings;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A music video skipped to mid-item gains the overlay within 4 s, with no glitch in the stream
 * ({@code docs/milestones.md} M5; {@code docs/overlay.md#scheduling-and-caching}: a job started
 * mid-item is a HIGH job from the GOP after the one playing, and "the first GOP or two play without
 * the overlay, then the overlay appears and stays").
 *
 * <p>The real chain, as in {@link NextVideoOverlayTest} ({@link OverlayCards#forRoom} with
 * {@link OverlayVideoJobs} on a 1-worker {@link OverlayPool} running real re-encodes of the room's
 * QR panel, {@link ProgramMedia}, the {@link OfflineHarness}), but the harness runs in <b>real
 * time</b> (each unit built when the live packager would build it), so the encoder races playback
 * as it does live; nothing waits for it. Script: the music video {@code v0} is the first entry and
 * starts at 5 s (it was never next, so no encode exists: a pure catch-up job; the output joins at
 * about 4 s, GOP 2, and the job starts at GOP 3), then at 4 s it is seeked back to 0 (the output
 * joins about 2 s later, at GOP 1: before the first job's start, so a second catch-up job from GOP
 * 2); then an audio item and fillers. A new play is a change of the unit's source description
 * (the two plays start at different media times). Per play: the overlay must appear within 4 s of output time from
 * the play's first frame, on an IDR, and stay on every frame to the play's end; no QR code on
 * anything else; the file decodes without errors and its packets are continuous.
 *
 * <p>Needs ffmpeg and ffprobe; skipped without them, except in CI (see {@link HarnessFixture#tools()}).
 */
class MidItemVideoOverlayTest {
    private static final String ROOM = "room1";
    private static final String BASE = "https://tunes.example.com";
    private static final long SEEK_BACK_AT_MS = 4_000;
    private static final long SESSION_MS = SEEK_BACK_AT_MS + HarnessFixture.VIDEO_MS + HarnessFixture.AUDIO_MS
            + 2_000;
    /**
     * The longest the overlay may take to appear, in output time from a play's first frame. A play
     * starts on a keyframe (GOP g); the unit of GOP g, built {@link Packager#LEAD_MS} ahead, already
     * reads GOP g + 1's keyframe (where the unit ends), so GOP g + 1 is decided before the catch-up
     * job (planned at GOP g's request) can have it: the earliest overlay GOP is g + 2, 4 s after
     * the start (less after a short first GOP). The job has 2 s of wall time for that.
     */
    private static final long MAX_OVERLAY_DELAY_MS = 4_000;

    @TempDir
    Path dir;

    @Test
    @Timeout(600)
    void musicVideoSkippedToMidItemGainsTheOverlayWithinFourSeconds() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        OutputEncoding enc = OutputEncoding.defaultFor(OutputProfile.P720_30);
        Path data = dir.resolve("data");
        Path roomsDir = data.resolve("rooms");
        ReferenceCard reference = ReferenceCard.ensure(tools, Files.createDirectories(data.resolve("cards")), enc, null);
        assertArrayEquals(reference.avcC(), m.avcC(), "the mezzanines match the reference (invariant 1)");
        Overlays overlays = new Overlays(roomsDir, BASE, enc::profile);
        OverlayCards.RoomInfo room = new OverlayCards.RoomInfo("chill", RoomSettings.DEFAULTS);
        OverlayRenderer.Rendered panel = overlays.current(ROOM, room.slug(), room.settings());
        String url = OverlayRenderer.shortUrl(BASE, room.slug());

        OverlayCards.Lookups lookups = new OverlayCards.Lookups() {
            @Override
            public boolean serverEnabled() {
                return false; // plain fillers: the panel only shows where a re-encode plays
            }

            @Override
            public OverlayCards.RoomInfo room(String roomId) {
                return room;
            }

            @Override
            public OverlayDecisions.Decision decide(String entryId) {
                return entryId.startsWith("a") ? OverlayDecisions.Decision.OFF : OverlayDecisions.Decision.VIDEO;
            }

            @Override
            public OverlayCards.ItemInfo item(String itemId) {
                return null;
            }
        };

        try (OverlayPool pool = new OverlayPool(1)) {
            pool.start();
            OverlayVideoJobs jobs = new OverlayVideoJobs(roomsDir, pool, tools, p -> X264Preset.DEFAULT_QUICK,
                    r -> Thread.ofVirtual().start(r));
            ProgramMedia.OverlayCards cards = new OverlayCards(roomsDir, tools).forRoom(ROOM, reference, overlays,
                    lookups, jobs);

            ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
            script.at(0).add(
                            new Entry("v0", "video1", true, false, HarnessFixture.VIDEO_MS),
                            new Entry("a0", "audio1", false, false, HarnessFixture.AUDIO_MS))
                    .seek(5_000)
                    .at(SEEK_BACK_AT_MS).seek(0);

            Path mediaDir = m.media();
            Path out = dir.resolve("mid.ts");
            OfflineHarness.Result r;
            long wallStart = System.nanoTime();
            try (ProgramMedia media = new ProgramMedia(itemId -> mediaDir.resolve(itemId), enc, m.avcC(),
                    ProgramMedia.ffprobeCardLoader(tools, enc), m.fillers(), m.silence(), Runnable::run, 0,
                    script::startServerMs, cards);
                    OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                r = OfflineHarness.run(media, m.avcC(), 0, 0, script, SESSION_MS, os, true);
            }
            long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
            assertTrue(wallMs >= SESSION_MS - Packager.LEAD_MS - 2_500, "ran in real time: " + wallMs + " ms");
            r.log().forEach(l -> System.out.println("MidItemVideoOverlayTest: " + l));

            HarnessFixture.assertDecodesClean(tools, out);
            HarnessFixture.assertPackets(tools, out, r);

            // both plays got a catch-up encode of their own (none was started as next)
            try (Stream<Path> d = Files.list(roomsDir.resolve(ROOM).resolve("ov").resolve("video1"))) {
                List<String> names = d.map(p -> p.getFileName().toString()).sorted().toList();
                System.out.println("MidItemVideoOverlayTest: encodes " + names);
                assertEquals(2, names.size(), names.toString());
                assertTrue(names.get(0).endsWith("-g2") && names.get(1).endsWith("-g3"), names.toString());
            }

            // the plays: runs of units from the same source (a new source per play)
            List<Long> unitStarts = new ArrayList<>();
            List<String> unitWhat = new ArrayList<>();
            for (String line : r.log()) {
                if (line.startsWith("unit ")) {
                    String[] f = line.split(" ");
                    unitStarts.add(Long.parseLong(f[3].substring(0, f[3].indexOf('.'))));
                    int s = line.indexOf(' ', line.indexOf(" pts ") + 5) + 1;
                    String what = line.substring(s).replace(" (ended)", "");
                    unitWhat.add(what);
                }
            }
            List<Long> pts = new ArrayList<>();
            List<Boolean> key = new ArrayList<>();
            videoPackets(tools, out, pts, key);
            boolean[] qr = NextVideoOverlayTest.qrFrames(tools, out, panel, url);
            assertEquals(r.videoFrames(), pts.size());
            assertEquals(pts.size(), qr.length, "every frame decodes");

            List<String> wrong = new ArrayList<>();
            List<String> plays = new ArrayList<>();
            int u = 0;
            String playing = null;
            int playFirst = -1;
            int firstQr = -1;
            for (int i = 0; i <= pts.size(); i++) {
                String what = null;
                if (i < pts.size()) {
                    while (u + 1 < unitStarts.size() && pts.get(i) >= unitStarts.get(u + 1)) {
                        u++;
                    }
                    what = unitWhat.get(u);
                }
                if (playing != null && !playing.equals(what)) {
                    // the end of a video play: check it
                    long delayMs = firstQr < 0 ? -1 : (pts.get(firstQr) - pts.get(playFirst)) / 90;
                    plays.add(playing + ": " + (i - playFirst) + " frames, overlay from frame " + (firstQr - playFirst)
                            + " (" + delayMs + " ms)");
                    if (firstQr < 0) {
                        wrong.add(playing + ": no overlay at all");
                    } else {
                        if (delayMs > MAX_OVERLAY_DELAY_MS) {
                            wrong.add(playing + ": overlay after " + delayMs + " ms");
                        }
                        if (!key.get(firstQr)) {
                            wrong.add(playing + ": overlay starts on a non-IDR frame " + firstQr);
                        }
                        for (int j = firstQr; j < i; j++) {
                            if (!qr[j]) {
                                wrong.add(playing + ": overlay missing again at frame " + j);
                                break;
                            }
                        }
                    }
                    playing = null;
                }
                if (what == null) {
                    break;
                }
                if (what.startsWith("video item video1")) {
                    if (playing == null) {
                        playing = what;
                        playFirst = i;
                        firstQr = -1;
                    }
                    if (qr[i] && firstQr < 0) {
                        firstQr = i;
                    }
                } else if (qr[i]) {
                    wrong.add("frame " + i + " (" + what + "): QR code where none belongs");
                }
            }
            System.out.println("MidItemVideoOverlayTest: plays " + plays);
            assertEquals(2, plays.size(), "two plays of v0: " + plays);
            // the output joins where the timeline is, at a keyframe: about 4 s (GOP 2), then about 2 s (GOP 1)
            assertTrue(plays.get(0).matches("video item video1@(39|40)\\d\\dms.*")
                    && plays.get(1).matches("video item video1@(19|20)\\d\\dms.*"), "both plays start mid-item: " + plays);
            assertTrue(unitWhat.stream().anyMatch(w -> w.startsWith("audio item")), "the audio item played");
            assertTrue(wrong.isEmpty(), wrong.size() + " problems, first: "
                    + wrong.subList(0, Math.min(10, wrong.size())));
        }
    }

    /** Video packet PTS and keyframe flags of {@code ts}, in decode order (= presentation order). */
    private static void videoPackets(Tools tools, Path ts, List<Long> pts, List<Boolean> key) throws Exception {
        String packets = tools.ffprobe("-v", "error", "-select_streams", "v", "-show_entries", "packet=pts,flags",
                "-of", "csv=p=0", ts.toString()).stdoutToString(1 << 26).timeout(Duration.ofMinutes(5)).run()
                .stdoutString();
        for (String line : packets.split("\n")) {
            if (!line.isBlank()) {
                String[] f = line.trim().split(",");
                pts.add(Long.parseLong(f[0]));
                key.add(f[1].startsWith("K"));
            }
        }
    }
}
