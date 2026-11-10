package io.siggi.togethertunes.media;

/**
 * ADTS header writer for the program output's audio PES (see
 * {@code docs/program-output.md#mpeg-ts-muxer}).
 *
 * <p>The header is 7 bytes (no CRC): sync word {@code 0xFFF}, {@code ID} 0 (MPEG-4),
 * {@code layer} 0, {@code protection_absent} 1, {@code profile} 1 (AAC-LC),
 * {@code sampling_frequency_index} 3 (48 kHz), {@code channel_configuration} 2,
 * {@code frame_length} = 7 + payload, {@code adts_buffer_fullness} 0x7FF (variable bit rate) and
 * one raw data block. These are the same bytes ffmpeg's ADTS muxer writes for the profile's AAC.
 */
public final class Adts {
    /** Header length without CRC. */
    public static final int HEADER_SIZE = 7;
    /** Largest {@code frame_length} (13 bits), header included. */
    public static final int MAX_FRAME_LENGTH = 0x1FFF;
    /** Largest raw AAC frame that fits behind a header. */
    public static final int MAX_PAYLOAD = MAX_FRAME_LENGTH - HEADER_SIZE;

    /** Index = ADTS {@code sampling_frequency_index}; declared before its first use. */
    private static final int[] SAMPLE_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000,
            12000, 11025, 8000, 7350};

    /** ADTS {@code profile}: the MPEG-4 audio object type minus 1 (AAC-LC = 2). */
    static final int PROFILE_LC = 1;
    /** {@code sampling_frequency_index} of {@link EncodeProfile#AUDIO_SAMPLE_RATE}. */
    static final int SF_INDEX = samplingFrequencyIndex(EncodeProfile.AUDIO_SAMPLE_RATE);
    /** {@code channel_configuration} of {@link EncodeProfile#AUDIO_CHANNELS}. */
    static final int CHANNEL_CONFIG = EncodeProfile.AUDIO_CHANNELS;
    private static final int BUFFER_FULLNESS_VBR = 0x7FF;

    private Adts() {
    }

    static int samplingFrequencyIndex(int sampleRate) {
        for (int i = 0; i < SAMPLE_RATES.length; i++) {
            if (SAMPLE_RATES[i] == sampleRate) {
                return i;
            }
        }
        throw new IllegalArgumentException("no ADTS sampling_frequency_index for " + sampleRate + " Hz");
    }

    /**
     * Writes the 7-byte header for a raw AAC frame of {@code payloadLength} bytes at
     * {@code dst[off]}.
     *
     * @throws IllegalArgumentException if the payload is empty or longer than {@link #MAX_PAYLOAD}
     */
    public static void writeHeader(byte[] dst, int off, int payloadLength) {
        if (payloadLength <= 0 || payloadLength > MAX_PAYLOAD) {
            throw new IllegalArgumentException("AAC frame of " + payloadLength + " bytes doesn't fit in ADTS");
        }
        int frameLength = HEADER_SIZE + payloadLength;
        dst[off] = (byte) 0xFF;
        // sync word (low 4 bits), ID 0, layer 00, protection_absent 1
        dst[off + 1] = (byte) 0xF1;
        // profile (2), sampling_frequency_index (4), private_bit 0, channel_configuration bit 2
        dst[off + 2] = (byte) ((PROFILE_LC << 6) | (SF_INDEX << 2) | ((CHANNEL_CONFIG >> 2) & 0x01));
        // channel_configuration bits 1-0, original/copy, home, two copyright bits (all 0),
        // frame_length bits 12-11
        dst[off + 3] = (byte) (((CHANNEL_CONFIG & 0x03) << 6) | ((frameLength >> 11) & 0x03));
        dst[off + 4] = (byte) ((frameLength >> 3) & 0xFF);
        // frame_length bits 2-0, buffer fullness bits 10-6
        dst[off + 5] = (byte) (((frameLength & 0x07) << 5) | ((BUFFER_FULLNESS_VBR >> 6) & 0x1F));
        // buffer fullness bits 5-0, number_of_raw_data_blocks_in_frame - 1 = 0
        dst[off + 6] = (byte) ((BUFFER_FULLNESS_VBR & 0x3F) << 2);
    }

    /** The header for a raw AAC frame of {@code payloadLength} bytes. */
    public static byte[] header(int payloadLength) {
        byte[] h = new byte[HEADER_SIZE];
        writeHeader(h, 0, payloadLength);
        return h;
    }

    /** {@code frame[off, off + len)} behind its ADTS header. */
    public static byte[] wrap(byte[] frame, int off, int len) {
        byte[] out = new byte[HEADER_SIZE + len];
        writeHeader(out, 0, len);
        System.arraycopy(frame, off, out, HEADER_SIZE, len);
        return out;
    }

    /** A raw AAC frame behind its ADTS header. */
    public static byte[] wrap(byte[] frame) {
        return wrap(frame, 0, frame.length);
    }
}
