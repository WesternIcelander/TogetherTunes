package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.media.EncodeProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * One pre-encoded AAC-LC frame of digital silence (48 kHz stereo, {@link EncodeProfile#AUDIO_ARGS}),
 * which the packager repeats next to the filler cards whenever a room is paused, idle or loading
 * ({@code docs/library-and-ingest.md#silence}). Each repeat is 1024 samples, i.e. exactly 1920
 * ticks at 90 kHz (invariant 3).
 *
 * <p>{@link #generate} runs at startup: ffmpeg encodes {@value #ENCODED_FRAMES} frames of
 * {@code anullsrc} to ADTS on stdout. The first packet is the encoder priming (pts −1024, with
 * ffmpeg's "Lavc" fill element) and is dropped, as is the first real frame (its MDCT window still
 * overlaps the priming) and the last one (encoder flush). The frames in between must all be
 * byte-identical: that steady-state frame decodes to silence on its own and when repeated, so it is
 * the one kept. The raw frame (no ADTS header) is held in memory; the packager adds its own ADTS
 * header.
 */
public final class SilenceFrame {
    /** Frames of silence encoded to find the steady-state frame. */
    static final int ENCODED_FRAMES = 16;
    /** Time limit for the encode (normally a few tens of milliseconds). */
    public static final Duration ENCODE_TIMEOUT = Duration.ofMinutes(1);
    /** An AAC-LC frame is at most 6144 bits per channel. */
    static final int MAX_FRAME_BYTES = 6144 / 8 * EncodeProfile.AUDIO_CHANNELS;

    /** ADTS {@code sampling_frequency_index} for 48 kHz. */
    static final int ADTS_SF_INDEX_48000 = 3;
    /** ADTS {@code profile} for AAC-LC (object type 2, minus 1). */
    static final int ADTS_PROFILE_LC = 1;

    private final byte[] frame;

    private SilenceFrame(byte[] frame) {
        this.frame = frame;
    }

    /**
     * Encodes silence with ffmpeg and extracts the steady-state frame.
     *
     * @param log job log for the ffmpeg output, or null
     * @throws ToolException        if ffmpeg fails
     * @throws IllegalStateException if ffmpeg's output isn't what is expected (see {@link #extract})
     */
    public static SilenceFrame generate(Tools tools, JobLog log) throws ToolException, InterruptedException {
        ToolCall call = tools.ffmpeg(command()).timeout(ENCODE_TIMEOUT)
                .stdoutToString((ENCODED_FRAMES + 2) * (MAX_FRAME_BYTES + 9));
        if (log != null) {
            call.log(log);
        }
        return new SilenceFrame(extract(call.run().stdout()));
    }

    /** The ffmpeg arguments (without the {@code -hide_banner -nostdin} prefix). */
    static List<String> command() {
        List<String> args = new ArrayList<>(List.of("-v", "error",
                "-f", "lavfi",
                "-i", "anullsrc=r=" + EncodeProfile.AUDIO_SAMPLE_RATE + ":cl=stereo",
                "-frames:a", Integer.toString(ENCODED_FRAMES)));
        args.addAll(EncodeProfile.AUDIO_ARGS);
        args.addAll(List.of("-f", "adts", "pipe:1"));
        return args;
    }

    /**
     * Picks the steady-state silence frame from an ADTS stream of {@value #ENCODED_FRAMES} encoded
     * frames plus the priming packet: drops the priming packet, the first and the last frame, and
     * requires the rest to be byte-identical.
     *
     * @throws IllegalStateException if the stream isn't AAC-LC 48 kHz stereo, has fewer frames
     *                               than expected, or the middle frames differ
     */
    static byte[] extract(byte[] adts) {
        List<byte[]> frames = parseAdts(adts);
        if (frames.size() < ENCODED_FRAMES + 1) {
            throw new IllegalStateException("expected " + (ENCODED_FRAMES + 1) + " ADTS frames (priming included), got "
                    + frames.size());
        }
        // frames[0] is the priming packet, frames[1] the first real frame; the last may be the flush.
        byte[] silence = frames.get(2);
        for (int i = 3; i < frames.size() - 1; i++) {
            if (!Arrays.equals(silence, frames.get(i))) {
                throw new IllegalStateException("the encoder's silence frames differ: frame 2 = "
                        + HexFormat.of().formatHex(silence) + ", frame " + i + " = "
                        + HexFormat.of().formatHex(frames.get(i)));
            }
        }
        if (silence.length == 0) {
            throw new IllegalStateException("empty silence frame");
        }
        return silence;
    }

    /**
     * Splits an ADTS stream into raw AAC frames (headers, and CRCs when present, removed) and checks
     * that every header is MPEG-4 AAC-LC, 48 kHz, stereo, one raw data block per frame.
     */
    static List<byte[]> parseAdts(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            if (data.length - pos < 7) {
                throw new IllegalStateException("truncated ADTS header at byte " + pos);
            }
            int b1 = data[pos + 1] & 0xFF;
            if ((data[pos] & 0xFF) != 0xFF || (b1 & 0xF0) != 0xF0) {
                throw new IllegalStateException("no ADTS sync word at byte " + pos);
            }
            if ((b1 & 0x06) != 0) {
                throw new IllegalStateException("ADTS layer is not 0 at byte " + pos);
            }
            boolean protectionAbsent = (b1 & 0x01) != 0;
            int b2 = data[pos + 2] & 0xFF;
            int b3 = data[pos + 3] & 0xFF;
            int profile = b2 >>> 6;
            int sfIndex = (b2 >>> 2) & 0x0F;
            int channels = ((b2 & 0x01) << 2) | (b3 >>> 6);
            int frameLength = ((b3 & 0x03) << 11) | ((data[pos + 4] & 0xFF) << 3) | ((data[pos + 5] & 0xFF) >>> 5);
            int rawBlocks = data[pos + 6] & 0x03;
            if (profile != ADTS_PROFILE_LC || sfIndex != ADTS_SF_INDEX_48000
                    || channels != EncodeProfile.AUDIO_CHANNELS || rawBlocks != 0) {
                throw new IllegalStateException("ADTS frame at byte " + pos + " is not AAC-LC 48 kHz stereo with one"
                        + " raw block (profile " + profile + ", sf index " + sfIndex + ", channels " + channels
                        + ", raw blocks " + (rawBlocks + 1) + ")");
            }
            int headerLength = protectionAbsent ? 7 : 9;
            if (frameLength < headerLength || frameLength > data.length - pos) {
                throw new IllegalStateException("bad ADTS frame length " + frameLength + " at byte " + pos);
            }
            frames.add(Arrays.copyOfRange(data, pos + headerLength, pos + frameLength));
            pos += frameLength;
        }
        return frames;
    }

    /** The raw AAC frame, without an ADTS header (a copy). */
    public byte[] frame() {
        return frame.clone();
    }

    /** Length of the raw frame in bytes. */
    public int length() {
        return frame.length;
    }

    @Override
    public String toString() {
        return "SilenceFrame[" + HexFormat.of().formatHex(frame) + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SilenceFrame other && Arrays.equals(frame, other.frame);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(frame);
    }
}
