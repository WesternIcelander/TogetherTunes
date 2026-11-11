package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.overlay.OverlayCards;
import io.siggi.togethertunes.overlay.OverlayDecisions;
import io.siggi.togethertunes.overlay.OverlayPool;
import io.siggi.togethertunes.overlay.OverlayRenderer;
import io.siggi.togethertunes.overlay.OverlayVideoJobs;
import io.siggi.togethertunes.overlay.Overlays;
import io.siggi.togethertunes.rooms.RoomSettings;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A music video started from "next" shows the overlay from its first frame
 * ({@code docs/overlay.md#packager-substitution}: "Job started when the entry became next: usually
 * the whole item is ready before it plays"). The real chain: {@link OverlayCards#forRoom} with
 * {@link OverlayVideoJobs} on an {@link OverlayPool} running real {@code OverlayVideoEncoder}
 * re-encodes of the room's rendered QR panel, fed by {@link ProgramMedia}, through the
 * {@link OfflineHarness} into a {@code .ts} file; then every output frame's panel region is
 * extracted with ffmpeg and searched for the room's QR code.
 *
 * <p>Script: an audio item {@code a0} (no overlay) with the music video {@code v0} next, which
 * starts at the audio item's natural end; while {@code v0} plays, {@code w0} (another item) is next
 * and is started by a skip 6 s later. {@code v0} starts at its media time 0 (the output moves on
 * when the audio item's source ends); {@code w0} joins the timeline where it is when the skip
 * reaches the output (about one unit plus {@link Packager#LEAD_MS} later, so at its GOP 1), which
 * the encode from GOP 0 covers too. Either way the first frame sent must show the overlay. The harness writes without real-time pacing, so before a
 * video's first unit is built it waits (wall clock) until the encode that its "next" turn started
 * is finished, and records how long that took against the time the live output would have had
 * (the previous item's remaining play time).
 *
 * <p>Needs ffmpeg and ffprobe; skipped without them, except in CI (see {@link HarnessFixture#tools()}).
 */
class NextVideoOverlayTest {
    private static final String ROOM = "room1";
    private static final String BASE = "https://tunes.example.com";
    private static final long SKIP_AT_MS = HarnessFixture.AUDIO_MS + 6_000;
    private static final long SESSION_MS = SKIP_AT_MS + HarnessFixture.VIDEO_MS + 3_000;

    @TempDir
    Path dir;

    @Test
    @Timeout(600)
    void musicVideoStartedFromNextShowsTheOverlayFromItsFirstFrame() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        // a second music video item with its own re-encode (same files, another item id)
        Path video2 = Files.createDirectories(m.media().resolve("video2"));
        try (Stream<Path> files = Files.list(m.media().resolve("video1"))) {
            for (Path f : files.toList()) {
                Files.copy(f, video2.resolve(f.getFileName()));
            }
        }
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

        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Map<String, Long> nextAtNanos = new ConcurrentHashMap<>();
        Map<String, Long> waitedMs = new ConcurrentHashMap<>();
        try (OverlayPool pool = new OverlayPool(1)) {
            pool.start();
            OverlayVideoJobs jobs = new OverlayVideoJobs(roomsDir, pool, tools, p -> X264Preset.DEFAULT_QUICK,
                    r -> Thread.ofVirtual().start(r));
            ProgramMedia.OverlayCards real = new OverlayCards(roomsDir, tools).forRoom(ROOM, reference, overlays,
                    lookups, jobs);
            ProgramMedia.OverlayCards recorded = new ProgramMedia.OverlayCards() {
                @Override
                public Map<Filler, ProgramMedia.CardFile> fillers() throws IOException, InterruptedException {
                    return real.fillers();
                }

                @Override
                public ProgramMedia.CardFile item(Entry entry) throws IOException, InterruptedException {
                    return real.item(entry);
                }

                @Override
                public Supplier<VideoOverlay> video(Entry entry, Path itemDir, SampleIndex index) {
                    events.add("play " + entry.entryId());
                    return real.video(entry, itemDir, index);
                }

                @Override
                public void videoNext(Entry entry, Path itemDir, SampleIndex index) {
                    if (nextAtNanos.putIfAbsent(entry.entryId(), System.nanoTime()) == null) {
                        events.add("next " + entry.entryId());
                    }
                    real.videoNext(entry, itemDir, index);
                }

                @Override
                public void videoWanted(Set<String> entryIds) {
                    real.videoWanted(entryIds);
                }
            };

            ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
            script.at(0).add(
                            new Entry("a0", "audio1", false, false, HarnessFixture.AUDIO_MS),
                            new Entry("v0", "video1", true, false, HarnessFixture.VIDEO_MS),
                            new Entry("w0", "video2", true, false, HarnessFixture.VIDEO_MS))
                    .at(SKIP_AT_MS).skip();
            // the time the live output has between handing an entry over as next and building its
            // first unit: v0 is next from 0 and starts at a0's end; w0 is next from v0's start
            Map<String, Long> budgetMs = Map.of("v0", HarnessFixture.AUDIO_MS - Packager.LEAD_MS,
                    "w0", SKIP_AT_MS - HarnessFixture.AUDIO_MS);

            Path mediaDir = m.media();
            Path out = dir.resolve("next.ts");
            OfflineHarness.Result r;
            try (ProgramMedia media = new ProgramMedia(itemId -> mediaDir.resolve(itemId), enc, m.avcC(),
                    ProgramMedia.ffprobeCardLoader(tools, enc), m.fillers(), m.silence(), Runnable::run, 0,
                    script::startServerMs, recorded);
                    OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                TimelineFollower.SourceFactory paced = new TimelineFollower.SourceFactory() {
                    @Override
                    public ProgramSource item(Entry entry, long startMs) throws IOException {
                        if (entry.videoItem() && !waitedMs.containsKey(entry.entryId())) {
                            waitedMs.put(entry.entryId(), awaitEncode(roomsDir, entry, nextAtNanos, events));
                        }
                        return media.item(entry, startMs);
                    }

                    @Override
                    public ProgramSource filler(Filler filler) {
                        return media.filler(filler);
                    }

                    @Override
                    public void prepare(ProgramCue cue) {
                        media.prepare(cue);
                    }
                };
                r = OfflineHarness.run(paced, m.avcC(), 0, script, SESSION_MS, os);
            }

            HarnessFixture.assertDecodesClean(tools, out);
            HarnessFixture.assertPackets(tools, out, r);
            assertEquals(List.of("next v0", "play v0", "next w0", "play w0"), List.copyOf(events),
                    "each video was handed over as next before it played");
            for (String e : List.of("v0", "w0")) {
                System.out.printf("NextVideoOverlayTest: %s's re-encode (from next) finished %d ms after it became"
                        + " next; the live output would have had %d ms%n", e, waitedMs.get(e), budgetMs.get(e));
            }
            // one encode per item, started at GOP 0 by its "next" turn (not a catch-up job of the play)
            for (String item : List.of("video1", "video2")) {
                try (Stream<Path> d = Files.list(roomsDir.resolve(ROOM).resolve("ov").resolve(item))) {
                    List<String> names = d.map(p -> p.getFileName().toString()).toList();
                    assertEquals(1, names.size(), item + ": " + names);
                    assertTrue(names.get(0).endsWith("-g0"), names.get(0));
                }
            }

            // every output frame: which unit's entry it belongs to, and whether the panel's QR code shows
            List<Long> pts = videoPts(tools, out);
            boolean[] qr = qrFrames(tools, out, panel, url);
            assertEquals(r.videoFrames(), pts.size());
            assertEquals(pts.size(), qr.length, "every frame decodes");
            List<long[]> unitStarts = new ArrayList<>();
            List<String> unitWhat = new ArrayList<>();
            for (String line : r.log()) {
                if (line.startsWith("unit ")) {
                    String[] f = line.split(" ");
                    unitStarts.add(new long[] {Long.parseLong(f[3].substring(0, f[3].indexOf('.')))});
                    unitWhat.add(line.contains("video item video1") ? "v0" : line.contains("video item video2") ? "w0"
                            : line.contains("audio item") ? "a0" : "filler");
                }
            }
            Map<String, int[]> firstAndCount = new java.util.LinkedHashMap<>();
            int u = 0;
            List<String> wrong = new ArrayList<>();
            for (int i = 0; i < pts.size(); i++) {
                while (u + 1 < unitStarts.size() && pts.get(i) >= unitStarts.get(u + 1)[0]) {
                    u++;
                }
                String what = unitWhat.get(u);
                boolean video = what.equals("v0") || what.equals("w0");
                firstAndCount.computeIfAbsent(what, k -> new int[] {-1, 0});
                int[] fc = firstAndCount.get(what);
                if (fc[0] < 0) {
                    fc[0] = i;
                }
                fc[1]++;
                if (qr[i] != video) {
                    wrong.add("frame " + i + " (" + what + ", pts " + pts.get(i) + "): "
                            + (qr[i] ? "QR code where none belongs" : "no QR code"));
                }
            }
            System.out.println("NextVideoOverlayTest: frames per source (first, count): "
                    + firstAndCount.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/"
                    + e.getValue()[1]).toList());
            assertTrue(firstAndCount.containsKey("v0") && firstAndCount.containsKey("w0"), "both videos played");
            assertTrue(r.log().stream().filter(l -> l.contains("video item video1")).findFirst().orElseThrow()
                    .contains("video1@0ms"), "v0 starts at its media time 0");
            assertTrue(qr[firstAndCount.get("v0")[0]], "v0's first frame shows the overlay");
            assertTrue(qr[firstAndCount.get("w0")[0]], "w0's first frame shows the overlay");
            assertTrue(firstAndCount.get("a0")[1] > 0, "the audio item played first");
            assertTrue(wrong.isEmpty(), wrong.size() + " frames wrong, first: "
                    + wrong.subList(0, Math.min(10, wrong.size())));
        }
    }

    /**
     * Waits (wall clock, up to 2 minutes) for the finished re-encode of {@code entry}'s item from
     * GOP 0, which must already have been started by the entry's "next" turn; returns the ms since
     * that turn.
     */
    private static long awaitEncode(Path roomsDir, Entry entry, Map<String, Long> nextAtNanos, List<String> events)
            throws IOException {
        Long nextAt = nextAtNanos.get(entry.entryId());
        if (nextAt == null) {
            fail(entry.entryId() + " starts without having been next: " + events);
        }
        Path itemOv = roomsDir.resolve(ROOM).resolve("ov").resolve(entry.itemId());
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(itemOv)) {
                try (Stream<Path> d = Files.list(itemOv)) {
                    if (d.anyMatch(p -> p.getFileName().toString().endsWith("-g0")
                            && Files.exists(p.resolve("done")))) {
                        return (System.nanoTime() - nextAt) / 1_000_000;
                    }
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        fail(entry.entryId() + ": the re-encode started as next didn't finish in 2 minutes");
        return -1;
    }

    /** Video packet PTS of {@code ts} in decode order (= presentation order: no B-frames). */
    static List<Long> videoPts(Tools tools, Path ts) throws Exception {
        String packets = tools.ffprobe("-v", "error", "-select_streams", "v", "-show_entries", "packet=pts",
                "-of", "csv=p=0", ts.toString()).stdoutToString(1 << 26).run().stdoutString();
        List<Long> pts = new ArrayList<>();
        for (String line : packets.split("\n")) {
            if (!line.isBlank()) {
                pts.add(Long.parseLong(line.trim().replace(",", "")));
            }
        }
        return pts;
    }

    /**
     * Extracts every decoded frame's panel region (grey) and returns, per frame, whether the room's
     * QR code decodes from it to {@code url}.
     */
    static boolean[] qrFrames(Tools tools, Path ts, OverlayRenderer.Rendered panel, String url)
            throws Exception {
        int w = panel.width();
        int h = panel.height();
        ToolResult res = tools.ffmpeg("-v", "error", "-i", ts.toString(), "-map", "0:v",
                "-vf", "crop=" + w + ":" + h + ":" + panel.x() + ":" + panel.y() + ",format=gray",
                "-fps_mode", "passthrough", "-f", "rawvideo", "pipe:1")
                .stdoutToString(1 << 30).timeout(Duration.ofMinutes(10)).run();
        byte[] px = res.stdout();
        int size = w * h;
        assertEquals(0, px.length % size);
        boolean[] out = new boolean[px.length / size];
        QRCodeReader reader = new QRCodeReader();
        for (int i = 0; i < out.length; i++) {
            byte[] frame = new byte[size];
            System.arraycopy(px, i * size, frame, 0, size);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                    new PlanarYUVLuminanceSource(frame, w, h, 0, 0, w, h, false)));
            try {
                out[i] = url.equals(reader.decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, true)).getText());
            } catch (NotFoundException | ChecksumException | FormatException e) {
                out[i] = false;
            }
            reader.reset();
        }
        return out;
    }
}
