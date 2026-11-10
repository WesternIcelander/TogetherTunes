package io.siggi.togethertunes.output;

import io.siggi.togethertunes.media.Adts;
import io.siggi.togethertunes.media.AnnexB;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-house MPEG-TS muxer ({@code docs/program-output.md#mpeg-ts-muxer}): turns each {@link Unit}
 * into 188-byte TS packets, once, as one {@code byte[]} that every consumer shares.
 *
 * <ul>
 *   <li>PIDs as VRCDN: PAT 0x0000, SDT 0x0011, PMT 0x0100, video 0x0101 (H.264, also the PCR PID),
 *       audio 0x0102 (AAC ADTS).</li>
 *   <li>Each unit starts with SDT, PAT and PMT, then the IDR access unit with
 *       {@code random_access_indicator=1}; PAT and PMT are repeated about every 400 ms
 *       ({@link #TABLE_INTERVAL}) within the unit.</li>
 *   <li>One PES per video access unit ({@code PES_packet_length = 0}, Annex B with AUD, SPS/PPS
 *       before IDRs, via {@link AnnexB}); audio in PES of up to {@link #AUDIO_FRAMES_PER_PES} ADTS
 *       frames. PES headers carry PTS only (DTS = PTS). PES are interleaved by DTS, video first on a
 *       tie, so each unit starts with its IDR.</li>
 *   <li>PCR ({@code DTS − 63000}) in the adaptation field of every video PES start.</li>
 *   <li>One continuity counter per PID, kept for the muxer's (= the packager's) lifetime, never a
 *       discontinuity flag. The last packet of each PES or section is filled with adaptation-field
 *       stuffing (PSI with 0xFF after the section).</li>
 * </ul>
 *
 * <p>Not thread-safe: one muxer per packager, used on the packager thread only.
 */
public final class TsMuxer {
    public static final int PACKET_SIZE = 188;
    public static final int PID_PAT = 0x0000;
    public static final int PID_SDT = 0x0011;
    public static final int PID_PMT = 0x0100;
    public static final int PID_VIDEO = 0x0101;
    public static final int PID_AUDIO = 0x0102;
    public static final int STREAM_TYPE_H264 = 0x1B;
    public static final int STREAM_TYPE_AAC_ADTS = 0x0F;
    public static final int TRANSPORT_STREAM_ID = 1;
    public static final int PROGRAM_NUMBER = 1;
    /** As ffmpeg's mpegts muxer. */
    public static final int ORIGINAL_NETWORK_ID = 0xFF01;
    /** PAT/PMT repeat interval within a unit: 400 ms. */
    public static final long TABLE_INTERVAL = 36_000;
    /** PCR = DTS − 700 ms. */
    public static final long MUX_DELAY = 63_000;
    /** About 107 ms of audio per PES. */
    public static final int AUDIO_FRAMES_PER_PES = 5;

    private static final int STREAM_ID_VIDEO = 0xE0;
    private static final int STREAM_ID_AUDIO = 0xC0;
    private static final int MAX_PES_PACKET_LENGTH = 0xFFFF;

    private final AnnexB annexB;
    private final byte[] pat;
    private final byte[] pmt;
    private final byte[] sdt;
    private final int[] cc = new int[0x2000];

    /**
     * @param avcC         the profile's avcC (identical for every item and card, invariant 1)
     * @param provider     SDT service provider name
     * @param serviceName  SDT service name
     */
    public TsMuxer(byte[] avcC, String provider, String serviceName) throws IOException {
        this.annexB = new AnnexB(avcC);
        this.pat = patSection();
        this.pmt = pmtSection();
        this.sdt = sdtSection(provider, serviceName);
    }

    public TsMuxer(byte[] avcC) throws IOException {
        this(avcC, "TogetherTunes", "TogetherTunes");
    }

    /**
     * Muxes one unit. The returned array is new, never touched again by the muxer, and meant to be
     * shared by every consumer as is: don't modify it.
     *
     * @throws IOException if a video sample isn't valid AVCC for the profile's avcC, or an audio frame
     *                     is too long for ADTS
     */
    public byte[] mux(Unit unit) throws IOException {
        List<Pes> pes = new ArrayList<>(unit.video().size() + unit.audio().size() / AUDIO_FRAMES_PER_PES + 2);
        for (Unit.Video v : unit.video()) {
            pes.add(new Pes(true, v.dts(), videoPes(v), v.idr()));
        }
        List<Pes> audio = audioPes(unit.audio());
        // merge by DTS, video first on a tie (both lists are already in DTS order)
        List<Pes> merged = new ArrayList<>(pes.size() + audio.size());
        int vi = 0;
        int ai = 0;
        while (vi < pes.size() || ai < audio.size()) {
            if (ai >= audio.size() || (vi < pes.size() && pes.get(vi).dts <= audio.get(ai).dts)) {
                merged.add(pes.get(vi++));
            } else {
                merged.add(audio.get(ai++));
            }
        }

        int estimate = 0;
        for (Pes p : merged) {
            estimate += p.bytes.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((estimate / 184 + merged.size() + 32) * PACKET_SIZE);
        byte[] packet = new byte[PACKET_SIZE];
        writeSection(out, packet, PID_SDT, sdt);
        writeSection(out, packet, PID_PAT, pat);
        writeSection(out, packet, PID_PMT, pmt);
        long lastTables = unit.startPts();
        for (Pes p : merged) {
            if (p.dts - lastTables >= TABLE_INTERVAL) {
                writeSection(out, packet, PID_PAT, pat);
                writeSection(out, packet, PID_PMT, pmt);
                lastTables = p.dts;
            }
            writePes(out, packet, p.video ? PID_VIDEO : PID_AUDIO, p.bytes,
                    p.video ? OutputClock.wrap(p.dts - MUX_DELAY) : -1, p.randomAccess);
        }
        return out.toByteArray();
    }

    private record Pes(boolean video, long dts, byte[] bytes, boolean randomAccess) {
    }

    private byte[] videoPes(Unit.Video v) throws IOException {
        byte[] au = annexB.accessUnit(v.data());
        byte[] pes = new byte[14 + au.length];
        pesHeader(pes, STREAM_ID_VIDEO, 0, v.pts33(), true);
        System.arraycopy(au, 0, pes, 14, au.length);
        return pes;
    }

    private static List<Pes> audioPes(List<Unit.Audio> frames) throws IOException {
        List<Pes> out = new ArrayList<>(frames.size() / AUDIO_FRAMES_PER_PES + 1);
        int i = 0;
        while (i < frames.size()) {
            int payload = 0;
            int n = 0;
            while (n < AUDIO_FRAMES_PER_PES && i + n < frames.size()) {
                int len = frames.get(i + n).data().length;
                if (len > Adts.MAX_PAYLOAD) {
                    throw new IOException("AAC frame too long for ADTS: " + len + " bytes");
                }
                if (n > 0 && 8 + payload + Adts.HEADER_SIZE + len > MAX_PES_PACKET_LENGTH) {
                    break;
                }
                payload += Adts.HEADER_SIZE + len;
                n++;
            }
            byte[] pes = new byte[14 + payload];
            Unit.Audio first = frames.get(i);
            pesHeader(pes, STREAM_ID_AUDIO, 8 + payload, first.pts33(), false);
            int off = 14;
            for (int k = 0; k < n; k++) {
                byte[] f = frames.get(i + k).data();
                Adts.writeHeader(pes, off, f.length);
                off += Adts.HEADER_SIZE;
                System.arraycopy(f, 0, pes, off, f.length);
                off += f.length;
            }
            out.add(new Pes(false, first.dts(), pes, false));
            i += n;
        }
        return out;
    }

    /** A 14-byte PES header with PTS only. */
    private static void pesHeader(byte[] b, int streamId, int packetLength, long pts33, boolean dataAlignment) {
        b[0] = 0;
        b[1] = 0;
        b[2] = 1;
        b[3] = (byte) streamId;
        b[4] = (byte) (packetLength >> 8);
        b[5] = (byte) packetLength;
        b[6] = (byte) (dataAlignment ? 0x84 : 0x80);
        b[7] = (byte) 0x80; // PTS only
        b[8] = 5;
        b[9] = (byte) (0x21 | ((pts33 >> 29) & 0x0E));
        b[10] = (byte) (pts33 >> 22);
        b[11] = (byte) (((pts33 >> 14) & 0xFE) | 1);
        b[12] = (byte) (pts33 >> 7);
        b[13] = (byte) (((pts33 << 1) & 0xFE) | 1);
    }

    private int nextCc(int pid) {
        int c = cc[pid];
        cc[pid] = (c + 1) & 0x0F;
        return c;
    }

    /** One PSI section (pointer field 0), padded with 0xFF; always fits one packet here. */
    private void writeSection(ByteArrayOutputStream out, byte[] p, int pid, byte[] section) {
        p[0] = 0x47;
        p[1] = (byte) (0x40 | (pid >> 8));
        p[2] = (byte) pid;
        p[3] = (byte) (0x10 | nextCc(pid));
        p[4] = 0;
        System.arraycopy(section, 0, p, 5, section.length);
        for (int i = 5 + section.length; i < PACKET_SIZE; i++) {
            p[i] = (byte) 0xFF;
        }
        out.write(p, 0, PACKET_SIZE);
    }

    /**
     * Splits a PES into packets. The first packet gets an adaptation field when there is a PCR
     * ({@code pcrBase ≥ 0}) or a random access point; the last one is padded with adaptation-field
     * stuffing.
     */
    private void writePes(ByteArrayOutputStream out, byte[] p, int pid, byte[] pes, long pcrBase,
            boolean randomAccess) {
        int off = 0;
        boolean first = true;
        while (off < pes.length) {
            int flags = 0;
            int afMin = 0; // adaptation field bytes incl. its length byte, before stuffing
            if (first) {
                if (randomAccess) {
                    flags |= 0x40;
                }
                if (pcrBase >= 0) {
                    flags |= 0x10;
                }
                if (flags != 0) {
                    afMin = 2 + (pcrBase >= 0 ? 6 : 0);
                }
            }
            int remaining = pes.length - off;
            int payload = Math.min(remaining, 184 - afMin);
            int af = 184 - payload; // adaptation field total size (0 = none)
            p[0] = 0x47;
            p[1] = (byte) ((first ? 0x40 : 0) | (pid >> 8));
            p[2] = (byte) pid;
            p[3] = (byte) ((af > 0 ? 0x30 : 0x10) | nextCc(pid));
            int i = 4;
            if (af > 0) {
                p[i++] = (byte) (af - 1);
                if (af > 1) {
                    p[i++] = (byte) flags;
                    if ((flags & 0x10) != 0) {
                        p[i++] = (byte) (pcrBase >> 25);
                        p[i++] = (byte) (pcrBase >> 17);
                        p[i++] = (byte) (pcrBase >> 9);
                        p[i++] = (byte) (pcrBase >> 1);
                        p[i++] = (byte) (((pcrBase & 1) << 7) | 0x7E); // reserved, extension 0
                        p[i++] = 0;
                    }
                    while (i < 4 + af) {
                        p[i++] = (byte) 0xFF;
                    }
                }
            }
            System.arraycopy(pes, off, p, i, payload);
            out.write(p, 0, PACKET_SIZE);
            off += payload;
            first = false;
        }
    }

    // --- PSI ---

    private static byte[] patSection() {
        byte[] body = {
                (byte) (PROGRAM_NUMBER >> 8), (byte) PROGRAM_NUMBER,
                (byte) (0xE0 | (PID_PMT >> 8)), (byte) PID_PMT,
        };
        return section(0x00, TRANSPORT_STREAM_ID, body);
    }

    private static byte[] pmtSection() {
        byte[] body = {
                (byte) (0xE0 | (PID_VIDEO >> 8)), (byte) PID_VIDEO, // PCR PID
                (byte) 0xF0, 0, // program_info_length 0
                (byte) STREAM_TYPE_H264, (byte) (0xE0 | (PID_VIDEO >> 8)), (byte) PID_VIDEO, (byte) 0xF0, 0,
                (byte) STREAM_TYPE_AAC_ADTS, (byte) (0xE0 | (PID_AUDIO >> 8)), (byte) PID_AUDIO, (byte) 0xF0, 0,
        };
        return section(0x02, PROGRAM_NUMBER, body);
    }

    private static byte[] sdtSection(String provider, String serviceName) {
        byte[] prov = latin(provider);
        byte[] name = latin(serviceName);
        int descBody = 3 + prov.length + name.length; // service_type, two length-prefixed names
        int descLen = 2 + descBody; // descriptors_loop_length: the one service_descriptor
        int loopLen = descLen;
        byte[] body = new byte[3 + 5 + loopLen];
        int i = 0;
        body[i++] = (byte) (ORIGINAL_NETWORK_ID >> 8);
        body[i++] = (byte) ORIGINAL_NETWORK_ID;
        body[i++] = (byte) 0xFF; // reserved_future_use
        body[i++] = (byte) (PROGRAM_NUMBER >> 8); // service_id
        body[i++] = (byte) PROGRAM_NUMBER;
        body[i++] = (byte) 0xFC; // reserved, no EIT
        body[i++] = (byte) (0x80 | (descLen >> 8)); // running (4), not scrambled
        body[i++] = (byte) descLen;
        body[i++] = 0x48; // service_descriptor
        body[i++] = (byte) descBody;
        body[i++] = 0x01; // digital television service
        body[i++] = (byte) prov.length;
        System.arraycopy(prov, 0, body, i, prov.length);
        i += prov.length;
        body[i++] = (byte) name.length;
        System.arraycopy(name, 0, body, i, name.length);
        return section(0x42, TRANSPORT_STREAM_ID, body);
    }

    /** At most 64 printable ASCII characters (DVB default table is close enough for those). */
    private static byte[] latin(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 64; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return b.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** A long-form section: header, the body, CRC32. */
    private static byte[] section(int tableId, int idExtension, byte[] body) {
        int sectionLength = 5 + body.length + 4;
        byte[] s = new byte[3 + sectionLength];
        s[0] = (byte) tableId;
        s[1] = (byte) (0xB0 | (sectionLength >> 8)); // syntax 1, '0' (PAT/PMT) / reserved, reserved
        if (tableId == 0x42) {
            s[1] = (byte) (0xF0 | (sectionLength >> 8)); // SDT: reserved_future_use = 1
        }
        s[2] = (byte) sectionLength;
        s[3] = (byte) (idExtension >> 8);
        s[4] = (byte) idExtension;
        s[5] = (byte) 0xC1; // reserved, version 0, current_next 1
        s[6] = 0; // section_number
        s[7] = 0; // last_section_number
        System.arraycopy(body, 0, s, 8, body.length);
        int crc = crc32(s, 0, s.length - 4);
        int o = s.length - 4;
        s[o] = (byte) (crc >>> 24);
        s[o + 1] = (byte) (crc >>> 16);
        s[o + 2] = (byte) (crc >>> 8);
        s[o + 3] = (byte) crc;
        return s;
    }

    /** CRC-32/MPEG-2: poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final xor. */
    static int crc32(byte[] b, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 24;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }
}
