package io.siggi.togethertunes.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Still-image detection at ingest ({@code docs/media-types.md#still-image-detection}): is a video
 * source's picture still for (nearly) its whole duration, like a song uploaded with its album
 * cover? Such an item publishes as an audio item, with the video kept only for undo.
 *
 * <p>How: one extra decode of the source's video, {@value #FPS} frames a second, scaled (area
 * average) to {@value #WIDTH}×{@value #HEIGHT} grey ({@link #args}), read raw from ffmpeg's stdout.
 * Each frame is compared with a <b>reference frame</b>, the first frame of the current still
 * stretch (as ffmpeg's {@code freezedetect} does, so a slow zoom or pan adds up until it counts):
 * a frame <b>differs</b> when at least {@value #MIN_CHANGED_PIXELS} of its pixels differ from the
 * reference by more than {@value #PIXEL_THRESHOLD} (of 255); it then becomes the new reference.
 * The area scaling averages compression noise and film grain away (a near-still with heavy grain
 * stays within ±8), while a changed line of text still moves a few dozen pixels by 70 or more.
 * {@code freezedetect} itself was tried and rejected: it compares the <i>mean</i> difference of
 * the whole frame, and a lyric video whose subtitle line changes is as close to its reference by
 * that measure as a still with grain (both around −60 dB).
 *
 * <p>Still means one still stretch covers the whole video except at most
 * {@link #allowanceUs} in total at its start and end (a fade-in or -out): 3 % of the duration,
 * at least 2 s (but no more than a tenth of a short video) and at most 10 s. A source without a known duration is never still. The decode
 * stops as soon as the answer is no (a changing picture after the allowance), so a normal video
 * costs about the allowance's decode.
 */
public final class StillImage {
    public static final int WIDTH = 160;
    public static final int HEIGHT = 90;
    public static final int FPS = 2;
    /** A pixel differs from the reference by more than this (0..255 grey). */
    public static final int PIXEL_THRESHOLD = 32;
    /** A frame differs from the reference when at least this many of its pixels do (0.05 %). */
    public static final int MIN_CHANGED_PIXELS = 7;

    private static final int FRAME_BYTES = WIDTH * HEIGHT;

    private StillImage() {
    }

    /**
     * The result.
     *
     * @param still   whether the picture is still for (nearly) the whole duration
     * @param startUs where the still stretch starts (the longest one seen), on the source's timeline
     * @param endUs   where it ends
     * @param reason  a line for the job log
     */
    public record Result(boolean still, long startUs, long endUs, String reason) {
        /** A good moment for the cover: the middle of the still stretch. */
        public long coverAtUs() {
            return startUs + (endUs - startUs) / 2;
        }
    }

    /**
     * How much of the video may be other than the still stretch, in total: 3 % of it, but at
     * least 2 s (at most a tenth of a short video) and at most 10 s.
     */
    public static long allowanceUs(long durationUs) {
        long atLeast = Math.min(2_000_000L, durationUs / 10);
        return Math.min(10_000_000L, Math.max(atLeast, durationUs * 3 / 100));
    }

    /** The ffmpeg arguments of the detection decode. */
    static List<String> args(Path source, String videoMap) {
        return List.of("-i", source.toString(), "-map", videoMap, "-an", "-sn", "-dn",
                "-vf", "fps=" + FPS + ":eof_action=pass,scale=" + WIDTH + ":" + HEIGHT + ":flags=area,format=gray",
                "-f", "rawvideo", "pipe:1");
    }

    /**
     * Decodes the video of {@code source} and decides. A failing decode answers "not still"
     * (logged): detection never fails a job.
     */
    public static Result detect(Tools tools, Path source, MediaProbe probe, JobLog log) throws InterruptedException {
        long durationUs = probe.durationUs();
        if (durationUs <= 0 || probe.video() == null) {
            return new Result(false, 0, 0, "the length of the video is unknown");
        }
        Detector detector = new Detector(durationUs);
        try {
            tools.ffmpeg(args(source, probe.video().map())).log(log).stdoutStream(in -> {
                byte[] frame = new byte[FRAME_BYTES];
                while (readFrame(in, frame)) {
                    if (!detector.frame(frame)) {
                        throw new Decided();
                    }
                }
            }).run();
        } catch (ToolException e) {
            if (!(e.getCause() instanceof Decided)) {
                log.append("[ingest] the still-image check failed (" + e.reason() + "); treating it as a video");
                return new Result(false, 0, 0, "the check failed");
            }
            // stopped early: the answer is known
        }
        return detector.result();
    }

    /** Thrown by the frame reader to stop the decode once the answer is no. */
    private static final class Decided extends IOException {
        Decided() {
            super("the picture changes; no need to decode further", null);
        }
    }

    private static boolean readFrame(InputStream in, byte[] frame) throws IOException {
        int off = 0;
        while (off < frame.length) {
            int n = in.read(frame, off, frame.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    /** Whether {@code frame} differs from {@code reference} (see the class comment). */
    static boolean differs(byte[] reference, byte[] frame) {
        int changed = 0;
        for (int i = 0; i < frame.length; i++) {
            if (Math.abs((reference[i] & 0xff) - (frame[i] & 0xff)) > PIXEL_THRESHOLD && ++changed >= MIN_CHANGED_PIXELS) {
                return true;
            }
        }
        return false;
    }

    /** The decision over a stream of frames, {@value #FPS} a second from 0. */
    static final class Detector {
        private final long durationUs;
        private final long allowanceUs;
        private byte[] reference;
        private long stretchStartUs;
        private long bestStartUs;
        private long bestEndUs = -1;
        private int frames;
        private int stretches;
        private boolean no;

        Detector(long durationUs) {
            this.durationUs = durationUs;
            this.allowanceUs = allowanceUs(durationUs);
        }

        private long timeUs(int frame) {
            return frame * 1_000_000L / FPS;
        }

        /** Takes the next frame; false once the picture can't be still any more (stop decoding). */
        boolean frame(byte[] frame) {
            long t = timeUs(frames++);
            if (reference == null) {
                reference = frame.clone();
                stretchStartUs = t;
                stretches = 1;
                return true;
            }
            if (!differs(reference, frame)) {
                return true;
            }
            // the stretch [stretchStartUs, t) ends here
            keep(stretchStartUs, t);
            reference = frame.clone();
            stretchStartUs = t;
            stretches++;
            if (t > allowanceUs && !qualifies(bestStartUs, bestEndUs)) {
                // a stretch starting now or later is too short, and none before was long enough
                no = true;
                return false;
            }
            return true;
        }

        private void keep(long start, long end) {
            if (end - start > bestEndUs - bestStartUs) {
                bestStartUs = start;
                bestEndUs = end;
            }
        }

        private boolean qualifies(long start, long end) {
            return end >= 0 && start + Math.max(0, durationUs - end) <= allowanceUs;
        }

        Result result() {
            if (!no && reference != null) {
                keep(stretchStartUs, Math.max(durationUs, timeUs(frames)));
            }
            long end = Math.min(bestEndUs, durationUs);
            if (reference == null) {
                return new Result(false, 0, 0, "no video frames were decoded");
            }
            boolean still = !no && qualifies(bestStartUs, end);
            String where = String.format(Locale.ROOT, "%.1f s to %.1f s of %.1f s", bestStartUs / 1e6,
                    Math.max(0, end) / 1e6, durationUs / 1e6);
            return new Result(still, bestStartUs, Math.max(bestStartUs, end), still
                    ? "the picture is still from " + where
                    : "the picture changes (" + (no ? "stopped after " + frames + " frames; " : "")
                    + stretches + " still stretch" + (stretches == 1 ? "" : "es") + ", the longest "
                    + where + ")");
        }
    }
}
