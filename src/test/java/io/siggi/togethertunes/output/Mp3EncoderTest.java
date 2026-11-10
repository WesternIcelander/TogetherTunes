package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.PackagerTest.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.Tools;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Mp3Encoder} with a real ffmpeg: valid MP3 from the first byte, the stream title following
 * the units' items, restarts without dropping listeners, and slow listeners dropped.
 */
class Mp3EncoderTest {
    @TempDir
    static Path dir;
    static Tools tools;
    static byte[] ts12;

    @BeforeAll
    static void media() throws Exception {
        tools = Mp3TestMedia.tools();
        ts12 = Mp3TestMedia.ts(tools, dir, 12);
    }

    /** Takes chunks until {@code bytes} bytes arrived or nothing came for 5 s. */
    static List<Mp3Encoder.Chunk> collect(Mp3Encoder.Listener l, int bytes) throws InterruptedException {
        List<Mp3Encoder.Chunk> out = new ArrayList<>();
        int got = 0;
        while (got < bytes) {
            Mp3Encoder.Chunk c = l.poll(5000);
            if (c == null) {
                break;
            }
            out.add(c);
            got += c.data().length;
        }
        return out;
    }

    static byte[] join(List<Mp3Encoder.Chunk> chunks) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (Mp3Encoder.Chunk c : chunks) {
            b.writeBytes(c.data());
        }
        return b.toByteArray();
    }

    @Test
    void encodesValidMp3WithTitlesFollowingTheItems() throws Exception {
        Mp3TestMedia.FakeFeed feed = new Mp3TestMedia.FakeFeed(true, List.of(
                Mp3TestMedia.units(ts12, "item-a", "item-a", "item-b", "item-b", null, null)));
        try (Mp3Encoder e = Mp3TestMedia.encoder(tools, feed, null)) {
            Mp3Encoder.Listener l = e.join();
            e.start();
            // 12 s at 128 kb/s = 192000 bytes; the queue holds 10 s, so read as it comes
            List<Mp3Encoder.Chunk> chunks = collect(l, 185_000);
            byte[] mp3 = join(chunks);
            assertTrue(mp3.length >= 185_000, "got " + mp3.length + " bytes");
            int frames = Mp3TestMedia.assertWholeFrames(mp3, mp3.length);
            assertEquals(mp3.length, frames * 417 + countPadded(mp3), "whole frames only");
            Mp3Frames.Header first = Mp3Frames.header(mp3, 0);
            assertEquals(128, first.bitrateKbps());
            assertEquals(128, e.bitrateKbps());

            // ffprobe agrees
            Path f = dir.resolve("out.mp3");
            Files.write(f, mp3);
            String probe = ffprobe(f);
            assertTrue(probe.contains("codec_name=mp3"), probe);
            assertTrue(probe.contains("sample_rate=44100"), probe);
            assertTrue(probe.contains("channels=2"), probe);

            // titles: A for 0..4 s, B for 4..8 s, empty (fillers) from 8 s
            assertEquals("Artist A - Song A", chunks.get(0).title());
            double bChange = changeAt(chunks, "Artist B - Song B");
            double fillerChange = changeAt(chunks, "");
            assertTrue(bChange >= 3.4 && bChange <= 4.7, "B from " + bChange + " s");
            assertTrue(fillerChange >= 7.4 && fillerChange <= 8.7, "filler from " + fillerChange + " s");
        }
    }

    /** Padded frames (418 bytes) in a 128 kb/s stream. */
    static int countPadded(byte[] mp3) {
        int pos = 0;
        int n = 0;
        while (pos + 4 <= mp3.length) {
            Mp3Frames.Header h = Mp3Frames.header(mp3, pos);
            n += h.length() == 418 ? 1 : 0;
            pos += h.length();
        }
        return n;
    }

    /** Seconds of audio (at 128 kb/s) before the first chunk with this title. */
    static double changeAt(List<Mp3Encoder.Chunk> chunks, String title) {
        long bytes = 0;
        for (Mp3Encoder.Chunk c : chunks) {
            if (title.equals(c.title())) {
                return bytes / 16_000.0;
            }
            bytes += c.data().length;
        }
        throw new AssertionError("no chunk with title " + title);
    }

    static String ffprobe(Path f) throws Exception {
        String ffprobe = Path.of(tools.ffmpegExecutable()).resolveSibling("ffprobe").toString();
        Process p = new ProcessBuilder(ffprobe, "-v", "error", "-show_streams", f.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(30, TimeUnit.SECONDS);
        return out;
    }

    @Test
    void restartsAfterTheProgramOutputRestartedWithoutDroppingListeners() throws Exception {
        List<TsUnit> units = Mp3TestMedia.units(ts12, "item-a", "item-a", "item-a", "item-b", "item-b", "item-b");
        Mp3TestMedia.FakeFeed feed = new Mp3TestMedia.FakeFeed(true, List.of(units.subList(0, 3),
                Mp3TestMedia.units(ts12, "item-c", "item-c", "item-c", "item-c", "item-c", "item-c")));
        try (Mp3Encoder e = Mp3TestMedia.encoder(tools, feed, null)) {
            Mp3Encoder.Listener l = e.join();
            e.start();
            List<Mp3Encoder.Chunk> chunks = collect(l, 80_000 + 185_000);
            assertTrue(feed.opens.get() >= 2, "a second attempt");
            assertTrue(e.attempts() >= 2);
            assertNull(l.closeReason(), "the listener stays connected");
            assertTrue(join(chunks).length > 80_000 + 150_000, "audio from both attempts");
            assertEquals("Artist C - Song C", chunks.get(chunks.size() - 1).title());
            assertTrue(feed.closes.get() >= 1, "the first subscription was released");
        }
        await(() -> feed.closes.get() == feed.opens.get());
    }

    @Test
    void aListenerThatDoesNotReadIsDroppedAndOthersKeepGoing() throws Exception {
        Mp3TestMedia.FakeFeed feed = new Mp3TestMedia.FakeFeed(false, List.of(
                Mp3TestMedia.units(ts12, "item-a", "item-a", "item-a", "item-a", "item-a", "item-a")));
        AtomicInteger idle = new AtomicInteger();
        try (Mp3Encoder e = Mp3TestMedia.encoder(tools, feed, idle::incrementAndGet)) {
            Mp3Encoder.Listener stalled = e.join();
            AtomicBoolean closed = new AtomicBoolean();
            stalled.onClose(() -> closed.set(true));
            Mp3Encoder.Listener reader = e.join();
            assertEquals(2, e.listenerCount());
            e.start();
            List<Mp3Encoder.Chunk> chunks = collect(reader, 170_000);
            assertTrue(join(chunks).length >= 170_000);
            await(() -> stalled.closeReason() == Mp3Encoder.CloseReason.OVERFLOW);
            assertTrue(closed.get(), "its connection is told to end");
            assertNull(stalled.poll(10));
            assertEquals(1, e.listenerCount());
            assertNull(reader.closeReason());
            // a late listener gets the recent burst (about 2 s) at once, from a frame boundary
            Mp3Encoder.Listener late = e.join();
            Mp3Encoder.Chunk c = late.poll(1000);
            assertNotNull(c);
            assertNotNull(Mp3Frames.header(c.data(), 0));
            reader.close();
            late.close();
            assertEquals(1, idle.get(), "told once when the last listener left");
        }
        await(() -> feed.closes.get() == feed.opens.get());
    }

    @Test
    @org.junit.jupiter.api.Timeout(300)
    void encodesThePackagersRealOutput(@TempDir Path work) throws Exception {
        // a real session through the packager's harness: a video, an audio item, a pause, idle; CBR padded
        HarnessFixture.Media m = HarnessFixture.media(tools, work);
        ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS)
                .at(0).add(new ProgramCue.Entry("v", "video1", true, false, HarnessFixture.VIDEO_MS),
                        new ProgramCue.Entry("a", "audio1", false, false, HarnessFixture.AUDIO_MS))
                .at(12_000).pause().at(14_000).play();
        Path tsFile = work.resolve("session.ts");
        long rate = io.siggi.togethertunes.config.OutputEncoding.defaultFor(
                io.siggi.togethertunes.config.OutputProfile.P720_30).videoBitrateBps();
        OfflineHarness.Result r = HarnessFixture.run(tools, m, script, 24_000, rate, tsFile);
        int n = (int) r.units();
        String[] items = new String[n];
        java.util.Arrays.fill(items, "item-v");
        List<TsUnit> units = Mp3TestMedia.units(Files.readAllBytes(tsFile), items);
        Mp3TestMedia.FakeFeed feed = new Mp3TestMedia.FakeFeed(true, List.of(units));
        try (Mp3Encoder e = Mp3TestMedia.encoder(tools, feed, null)) {
            Mp3Encoder.Listener l = e.join();
            e.start();
            long expected = r.durationMs() * 16; // bytes at 128 kb/s
            byte[] mp3 = join(collect(l, (int) (expected * 0.97)));
            assertTrue(mp3.length >= expected * 0.97, mp3.length + " of about " + expected + " bytes");
            Mp3TestMedia.assertWholeFrames(mp3, mp3.length);
            Path f = work.resolve("session.mp3");
            Files.write(f, mp3);
            Process p = new ProcessBuilder(tools.ffmpegExecutable(), "-v", "error", "-i", f.toString(), "-f", "null",
                    "-").redirectErrorStream(true).start();
            String errors = new String(p.getInputStream().readAllBytes());
            p.waitFor(60, TimeUnit.SECONDS);
            assertEquals("", errors.strip(), "the MP3 decodes cleanly");
        }
    }

    @Test
    void closeEndsEveryListener() throws Exception {
        Mp3TestMedia.FakeFeed feed = new Mp3TestMedia.FakeFeed(false, List.of());
        Mp3Encoder e = Mp3TestMedia.encoder(tools, feed, null);
        Mp3Encoder.Listener l = e.join();
        e.start();
        await(() -> feed.opens.get() == 1);
        e.close();
        assertEquals(Mp3Encoder.CloseReason.STOPPED, l.closeReason());
        assertNull(l.take());
        assertEquals(Mp3Encoder.CloseReason.STOPPED, e.join().closeReason(), "no joining a stopped encoder");
        await(() -> feed.closes.get() == 1);
    }

    @Test
    void commandHasNoTagsAndTheFixedRate() {
        List<String> cmd = Mp3Encoder.command("ffmpeg", 192);
        String s = String.join(" ", cmd);
        assertTrue(s.contains("-c:a libmp3lame -b:a 192k -ar 44100 -ac 2"), s);
        assertTrue(s.contains("-id3v2_version 0 -write_xing 0"), s);
        assertTrue(s.endsWith("-f mp3 pipe:1"), s);
        assertEquals(320, Mp3Encoder.clampKbps(999));
    }
}
