package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.ToolCall;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.AccessUnitGrouper;
import io.siggi.togethertunes.media.AccessUnitGrouper.AccessUnit;
import io.siggi.togethertunes.media.AnnexB;
import io.siggi.togethertunes.media.AnnexBReader;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/**
 * The music-video overlay re-encode ({@code docs/overlay.md#music-videos-re-encode-path}): decodes
 * the item's mezzanine from GOP {@code g0} on, composites the room's {@code overlay.png}, encodes it
 * with the shared profile arguments ({@link EncodeProfile#videoArgs}, the admin's quick preset) as
 * a raw H.264 stream on ffmpeg's stdout, and turns that into {@link OverlayGops} files:
 *
 * <ol>
 *   <li>the Annex B stream is split into NAL units ({@link AnnexBReader}) and grouped into access
 *       units ({@link AccessUnitGrouper}, {@code first_mb_in_slice});</li>
 *   <li>every SPS and PPS must be byte-identical to the reference avcC's (invariant 1), else the
 *       job is aborted ({@link MismatchException}) and the item plays without the overlay;</li>
 *   <li>SPS, PPS, AUD and SEI are dropped (the packager writes its own AUD, SPS and PPS); the rest
 *       of each frame is stored as an AVCC sample in {@value OverlayGops#H264_FILE} with an entry
 *       in {@value OverlayGops#INDEX_FILE};</li>
 *   <li>frame <i>i</i> of the output must be frame {@code start + i} of the mezzanine: an IDR
 *       exactly where the mezzanine has a keyframe, and as many frames as it has from there. A
 *       difference aborts the job ({@link MismatchException}); nothing already published is
 *       wrong, since a GOP is only marked complete after all its frames matched;</li>
 *   <li>once a GOP's last frame is written (and its index entries flushed), the GOP is marked
 *       complete in the {@link OverlayGops} (an in-memory {@code BitSet} the packager reads).</li>
 * </ol>
 *
 * <p>Input seeking lands exactly on GOP {@code g0}'s keyframe: {@code -seek_timestamp 1 -ss T} with
 * {@code T} the keyframe's time from the index, rounded down to the microsecond (ffmpeg drops the
 * decoded frames before {@code T}, and the previous frame is a whole frame earlier). The output's
 * keyframes then land on the mezzanine's: at a GOP start other than 0 the normal 2 s expression
 * (the output's time starts at 0), and from frame 0 the mezzanine's own first-GOP length (a smart
 * trim's short first GOP, {@link EncodeProfile#videoArgs(OutputEncoding, X264Preset, int)}).
 */
public final class OverlayVideoEncoder {
    /** How long to wait for the stdout reader to stop after ffmpeg ended or was stopped. */
    static final Duration READER_STOP_WAIT = ToolCall.KILL_GRACE.plusSeconds(2);

    private OverlayVideoEncoder() {
    }

    /**
     * One re-encode.
     *
     * @param mezz         the item's {@code mezz.mp4}
     * @param video        its video track (from {@code index.bin}): frame count, keyframes, timing
     * @param overlayPng   the room's {@code overlay.png}
     * @param x            the panel's left edge in the frame ({@code overlay.json})
     * @param y            the panel's top edge in the frame
     * @param encoding     the output encoding (profile and bitrate cap)
     * @param preset       the x264 preset ({@code ingest.quickPreset})
     * @param referenceAvcC the encoding's reference avcC ({@code ReferenceCard#avcC()})
     * @param g0           the GOP to start from (0 before the item plays, the GOP being played mid-item)
     */
    public record Request(Path mezz, Track video, Path overlayPng, int x, int y, OutputEncoding encoding,
                          X264Preset preset, byte[] referenceAvcC, int g0) {
        public Request {
            Objects.requireNonNull(mezz);
            Objects.requireNonNull(video);
            Objects.requireNonNull(overlayPng);
            Objects.requireNonNull(encoding);
            Objects.requireNonNull(preset);
            referenceAvcC = referenceAvcC.clone();
            if (x < 0 || y < 0) {
                throw new IllegalArgumentException("overlay position " + x + "," + y);
            }
            if (g0 < 0) {
                throw new IllegalArgumentException("g0 " + g0);
            }
        }

        @Override
        public byte[] referenceAvcC() {
            return referenceAvcC.clone();
        }
    }

    /** The encode's output doesn't match the reference or the mezzanine; play the plain video. */
    public static final class MismatchException extends IOException {
        public MismatchException(String message) {
            super(message);
        }
    }

    /**
     * The first GOP's length in frames for a video whose keyframes are {@code gopStarts}: the
     * second keyframe's frame number if it is below 60 (a smart trim's short first GOP), else 60.
     */
    static int firstGopFrames(int[] gopStarts) {
        if (gopStarts.length > 1 && gopStarts[1] < EncodeProfile.GOP_FRAMES) {
            return gopStarts[1];
        }
        return EncodeProfile.GOP_FRAMES;
    }

    /** The {@code -ss} value for frame {@code frame}: its time from the index, rounded down to the microsecond. */
    static String seekTime(Track video, int frame) {
        long us = Math.floorDiv(Math.multiplyExact(video.pts(frame), 1_000_000L), video.timescale());
        return us + "us";
    }

    /** The ffmpeg arguments (after {@code -hide_banner -nostdin}) for {@code request}. */
    public static List<String> command(Request request) {
        int[] gopStarts = OverlayGops.gopStarts(request.video());
        if (request.g0() >= gopStarts.length) {
            throw new IllegalArgumentException("g0 " + request.g0() + " of " + gopStarts.length + " GOPs");
        }
        int start = gopStarts[request.g0()];
        List<String> args = new ArrayList<>(List.of("-nostats"));
        if (start > 0) {
            args.addAll(List.of("-seek_timestamp", "1", "-ss", seekTime(request.video(), start)));
        }
        args.addAll(List.of(
                "-i", request.mezz().toString(),
                "-i", request.overlayPng().toString(),
                "-filter_complex", "[0:v][1:v]overlay=" + request.x() + ":" + request.y() + ","
                        + EncodeProfile.COLOUR_FILTER + "[v]",
                "-map", "[v]",
                "-fps_mode", "passthrough"));
        int firstGop = start == 0 ? firstGopFrames(gopStarts) : EncodeProfile.GOP_FRAMES;
        args.addAll(EncodeProfile.videoArgs(request.encoding(), request.preset(), firstGop));
        args.addAll(List.of("-an", "-f", "h264", "pipe:1"));
        return args;
    }

    /**
     * Runs the re-encode into {@code gops} (a fresh {@link OverlayGops} for the request's video; its
     * directory is created, and its files are replaced). GOPs are marked complete as they are
     * written, so the packager can use them while the encode goes on.
     *
     * @param progress gets the fraction of the frames from {@code g0} on that are written (may be null)
     * @param log      job log for ffmpeg's output, or null
     * @throws MismatchException    if the SPS/PPS differ from the reference, or the frames don't
     *                              match the mezzanine's (count, IDR positions)
     * @throws ToolException        if ffmpeg fails
     * @throws IOException          if the files can't be written, or the stream is malformed
     * @throws InterruptedException if the thread is interrupted (the job was cancelled); ffmpeg is stopped
     */
    public static void encode(Tools tools, Request request, OverlayGops gops, DoubleConsumer progress, JobLog log)
            throws IOException, ToolException, InterruptedException {
        int[] gopStarts = OverlayGops.gopStarts(request.video());
        if (gops.frameCount() != request.video().sampleCount() || gops.gopCount() != gopStarts.length) {
            throw new IllegalArgumentException("the OverlayGops are for another video");
        }
        List<String> args = command(request);
        Files.createDirectories(gops.dir());
        Writer writer = new Writer(request, gops, progress);
        AtomicReference<IOException> handlerFailure = new AtomicReference<>();
        CountDownLatch readerDone = new CountDownLatch(1);
        try {
            tools.ffmpeg(args).log(log).stdoutStream(in -> {
                try {
                    writer.read(in);
                } catch (IOException e) {
                    handlerFailure.compareAndSet(null, e);
                    throw e;
                } finally {
                    readerDone.countDown();
                }
            }).run();
        } catch (ToolException e) {
            IOException cause = handlerFailure.get();
            if (cause != null) {
                throw cause;
            }
            throw e;
        } finally {
            // the reader may still be writing when the run was cut short (interrupt, time limit)
            awaitQuietly(readerDone);
            writer.close();
        }
        IOException failure = handlerFailure.get();
        if (failure != null) {
            throw failure;
        }
        writer.checkEnd();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + READER_STOP_WAIT.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                if (latch.await(left, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Consumes ffmpeg's stdout and writes the files. Runs on the stdout reader thread. */
    private static final class Writer {
        private final Track video;
        private final OverlayGops gops;
        private final DoubleConsumer progress;
        private final List<byte[]> sps;
        private final List<byte[]> pps;
        private final int lengthSize;
        private final int startFrame;
        private final FileChannel data;
        private final DataOutputStream index;
        private int frame;
        private long dataSize;
        private boolean seenSps;
        private boolean seenPps;
        private boolean closed;

        Writer(Request request, OverlayGops gops, DoubleConsumer progress) throws IOException {
            this.video = request.video();
            this.gops = gops;
            this.progress = progress;
            AnnexB reference = new AnnexB(request.referenceAvcC());
            this.sps = reference.sps();
            this.pps = reference.pps();
            this.lengthSize = reference.nalLengthSize();
            this.startFrame = gops.gopStart(request.g0());
            this.frame = startFrame;
            FileChannel ch = FileChannel.open(gops.h264File(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            DataOutputStream idx;
            try {
                idx = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(gops.indexFile()), 64 * 1024));
                idx.writeInt(OverlayGops.MAGIC);
                idx.writeInt(OverlayGops.VERSION);
                idx.writeInt(gops.frameCount());
                idx.flush();
            } catch (IOException | RuntimeException e) {
                ch.close();
                throw e;
            }
            this.data = ch;
            this.index = idx;
        }

        void read(InputStream in) throws IOException {
            AnnexBReader reader = new AnnexBReader(in);
            AccessUnitGrouper grouper = new AccessUnitGrouper();
            byte[] nal;
            while ((nal = reader.next()) != null) {
                AccessUnit au = grouper.push(nal);
                if (au != null) {
                    accessUnit(au);
                }
            }
            AccessUnit last = grouper.finish();
            if (last != null) {
                accessUnit(last);
            }
        }

        private void accessUnit(AccessUnit au) throws IOException {
            int size = 0;
            for (byte[] nal : au.nals()) {
                int type = AccessUnitGrouper.type(nal);
                if (type == AnnexB.NAL_SPS) {
                    checkParameterSet(nal, sps, "SPS");
                    seenSps = true;
                } else if (type == AnnexB.NAL_PPS) {
                    checkParameterSet(nal, pps, "PPS");
                    seenPps = true;
                }
                if (!dropped(type)) {
                    if (lengthSize < 4 && nal.length >= 1 << (8 * lengthSize)) {
                        throw new IOException("a NAL unit of " + nal.length + " bytes doesn't fit a "
                                + lengthSize + "-byte length prefix");
                    }
                    size += lengthSize + nal.length;
                }
            }
            if (!au.hasSlice()) {
                return; // parameter sets or SEI after the last frame: checked, nothing to store
            }
            if (!seenSps || !seenPps) {
                throw new MismatchException("the overlay encode has no SPS/PPS before its first frame");
            }
            if (frame >= gops.frameCount()) {
                throw new MismatchException("the overlay encode has more frames than the video ("
                        + gops.frameCount() + ")");
            }
            boolean idr = au.isIdr();
            if (idr != video.isKeyframe(frame)) {
                throw new MismatchException("the overlay encode's frame " + (frame - startFrame) + " (video frame "
                        + frame + ") is " + (idr ? "an IDR" : "not an IDR") + ", the video's "
                        + (idr ? "isn't a keyframe" : "is a keyframe"));
            }
            ByteBuffer buf = ByteBuffer.allocate(size);
            for (byte[] nal : au.nals()) {
                if (dropped(AccessUnitGrouper.type(nal))) {
                    continue;
                }
                switch (lengthSize) {
                    case 1 -> buf.put((byte) nal.length);
                    case 2 -> buf.putShort((short) nal.length);
                    default -> buf.putInt(nal.length);
                }
                buf.put(nal);
            }
            buf.flip();
            long offset = dataSize;
            while (buf.hasRemaining()) {
                data.write(buf, offset + buf.position());
            }
            dataSize += size;
            index.writeInt(frame);
            index.writeLong(offset);
            index.writeInt(size);
            index.writeByte(idr ? OverlayGops.FLAG_IDR : 0);
            gops.frameWritten(frame, offset, size);
            frame++;
            if (frame == gops.frameCount() || video.isKeyframe(frame)) {
                index.flush();
                gops.markComplete(gops.gopOf(frame - 1));
                if (progress != null) {
                    progress.accept((double) (frame - startFrame) / (gops.frameCount() - startFrame));
                }
            }
        }

        private void checkParameterSet(byte[] nal, List<byte[]> reference, String what) throws MismatchException {
            for (byte[] r : reference) {
                if (Arrays.equals(r, nal)) {
                    return;
                }
            }
            throw new MismatchException("the overlay encode's " + what + " differs from the reference card's (expected "
                    + HexFormat.of().formatHex(reference.get(0)) + ", got " + HexFormat.of().formatHex(nal) + ")");
        }

        private static boolean dropped(int type) {
            return type == AnnexB.NAL_SPS || type == AnnexB.NAL_PPS || type == AnnexB.NAL_AUD
                    || type == AnnexB.NAL_SEI;
        }

        /** After a successful run: every frame from the start GOP to the end was written. */
        void checkEnd() throws MismatchException {
            if (frame != gops.frameCount()) {
                throw new MismatchException("the overlay encode ended after " + (frame - startFrame)
                        + " frames, the video has " + (gops.frameCount() - startFrame) + " from frame " + startFrame);
            }
        }

        synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                index.close();
            } finally {
                data.close();
            }
        }
    }
}
