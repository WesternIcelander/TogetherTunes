package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.siggi.togethertunes.ingest.Tools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Program-output-like input for the MP3 tests: a real MPEG-TS (H.264 + stereo AAC at 48 kHz, as the
 * packager writes) cut into "units" of about 2 s with item ids, and a fake {@link RtmpPusher.Feed}
 * that hands them to an {@link Mp3Encoder}.
 */
final class Mp3TestMedia {
    static final long UNIT_TICKS = 180_000;

    private Mp3TestMedia() {
    }

    /** ffmpeg (the test is skipped without it, except in CI: {@link HarnessFixture#tools}). */
    static Tools tools() {
        return HarnessFixture.tools();
    }

    /** A {@code seconds} s TS of a 440 Hz tone and a black picture. */
    static byte[] ts(Tools tools, Path dir, int seconds) throws Exception {
        Path out = dir.resolve("program-" + seconds + ".ts");
        if (Files.exists(out)) {
            return Files.readAllBytes(out);
        }
        Process p = new ProcessBuilder(tools.ffmpegExecutable(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + seconds,
                "-f", "lavfi", "-i", "color=c=black:s=64x64:r=30:d=" + seconds,
                "-map", "1:v", "-map", "0:a", "-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-bf", "0",
                "-c:a", "aac", "-ac", "2", "-b:a", "128k", "-f", "mpegts", out.toString())
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("ffmpeg failed: " + log);
        }
        return Files.readAllBytes(out);
    }

    /**
     * {@code ts} cut into {@code itemIds.length} units of equal size (whole TS packets), unit i
     * starting at {@code i · 2 s} and playing {@code itemIds[i]} (null: a filler).
     */
    static List<TsUnit> units(byte[] ts, String... itemIds) {
        int n = itemIds.length;
        int packets = ts.length / 188;
        List<TsUnit> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int from = packets * i / n * 188;
            int to = i == n - 1 ? ts.length : packets * (i + 1) / n * 188;
            long start = OutputClock.START + i * UNIT_TICKS;
            out.add(new TsUnit(i, start, start + UNIT_TICKS, start + UNIT_TICKS, Arrays.copyOfRange(ts, from, to),
                    itemIds[i]));
        }
        return out;
    }

    /**
     * Hands the n-th subscription the n-th batch of units. With {@code endEachBatch}, a batch's
     * queue closes ({@link ConsumerQueue.CloseReason#STOPPED}, as when the packager restarts) once
     * the encoder took every unit; otherwise it stays open (a live output with nothing new yet).
     * Subscriptions beyond the batches stay open and empty.
     */
    static final class FakeFeed implements RtmpPusher.Feed {
        final List<List<TsUnit>> batches;
        final boolean endEachBatch;
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();

        FakeFeed(boolean endEachBatch, List<List<TsUnit>> batches) {
            this.batches = batches;
            this.endEachBatch = endEachBatch;
        }

        @Override
        public RtmpPusher.Subscription open() {
            int n = opens.getAndIncrement();
            ConsumerQueue q = new ConsumerQueue(1024);
            if (n < batches.size()) {
                List<TsUnit> batch = batches.get(n);
                Thread.ofVirtual().start(() -> {
                    for (TsUnit u : batch) {
                        q.deliver(u);
                    }
                    if (endEachBatch) {
                        try {
                            while (q.size() > 0 && !q.isClosed()) {
                                Thread.sleep(2);
                            }
                        } catch (InterruptedException e) {
                            return;
                        }
                        q.close();
                    }
                });
            }
            return new RtmpPusher.Subscription() {
                @Override
                public ConsumerQueue queue() {
                    return q;
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                    q.leave();
                }
            };
        }
    }

    /** Titles as {@code Artist X - Song X} for item {@code item-x}. */
    static String title(String itemId) {
        String x = itemId.substring(itemId.indexOf('-') + 1).toUpperCase(java.util.Locale.ROOT);
        return "Artist " + x + " - Song " + x;
    }

    static Mp3Encoder encoder(Tools tools, RtmpPusher.Feed feed, Runnable onIdle) {
        return new Mp3Encoder("room-1", tools.ffmpegExecutable(), () -> 128, feed, Mp3TestMedia::title,
                RtmpPusher.processLauncher(), ms -> Thread.sleep(Math.min(ms, 20)), System::currentTimeMillis, onIdle);
    }

    /** Checks that {@code mp3} is a sequence of whole Layer III frames from byte 0; returns how many. */
    static int assertWholeFrames(byte[] mp3, int upTo) {
        int pos = 0;
        int frames = 0;
        while (pos + 4 <= upTo) {
            Mp3Frames.Header h = Mp3Frames.header(mp3, pos);
            if (h == null) {
                throw new AssertionError("no frame header at byte " + pos + " of " + upTo);
            }
            assertEquals(44_100, h.sampleRate());
            pos += h.length();
            frames++;
        }
        return frames;
    }
}
