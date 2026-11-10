package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.SourcesTest.AVCC;
import static io.siggi.togethertunes.output.SourcesTest.frame;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.media.Adts;
import io.siggi.togethertunes.media.AnnexB;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The MPEG-TS muxer ({@code docs/program-output.md#mpeg-ts-muxer}), on synthetic units. */
class TsMuxerTest {
    static final long S0 = OutputClock.START;

    /** A unit like the builder makes: {@code frames} video frames from {@code start}, audio over the same span. */
    static Unit unit(long start, int frames, int audioFrames, int audioLen) {
        List<Unit.Video> v = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            v.add(new Unit.Video(frame(i == 0, i), start + i * 3000L, i == 0));
        }
        List<Unit.Audio> a = new ArrayList<>();
        for (int k = 0; k < audioFrames; k++) {
            byte[] f = new byte[audioLen];
            Arrays.fill(f, (byte) k);
            a.add(new Unit.Audio(f, start + k * 1920L));
        }
        long boundary = start + frames * 3000L;
        return new Unit(start, boundary, start + audioFrames * 1920L, v, a, null, false);
    }

    // --- a small TS parser ---

    record Packet(int pid, boolean pusi, int cc, boolean hasAf, int afLen, int afFlags, long pcr, byte[] payload) {
        boolean rai() {
            return (afFlags & 0x40) != 0;
        }
    }

    record Pes(int pid, long pts, int declaredLength, byte[] data, int packetCount) {
    }

    static List<Packet> parse(byte[] ts) {
        assertEquals(0, ts.length % 188, "whole packets");
        List<Packet> out = new ArrayList<>();
        for (int o = 0; o < ts.length; o += 188) {
            assertEquals(0x47, ts[o] & 0xFF, "sync byte");
            int pid = ((ts[o + 1] & 0x1F) << 8) | (ts[o + 2] & 0xFF);
            boolean pusi = (ts[o + 1] & 0x40) != 0;
            assertEquals(0, ts[o + 1] & 0x80, "no TEI");
            int afc = (ts[o + 3] >> 4) & 3;
            assertTrue(afc == 1 || afc == 3, "every packet carries payload");
            int cc = ts[o + 3] & 0x0F;
            int i = o + 4;
            int afLen = -1;
            int flags = 0;
            long pcr = -1;
            if (afc == 3) {
                afLen = ts[i] & 0xFF;
                assertTrue(afLen <= 182);
                if (afLen > 0) {
                    flags = ts[i + 1] & 0xFF;
                    assertEquals(0, flags & 0x80, "never a discontinuity");
                    if ((flags & 0x10) != 0) {
                        long b = ((ts[i + 2] & 0xFFL) << 25) | ((ts[i + 3] & 0xFFL) << 17) | ((ts[i + 4] & 0xFFL) << 9)
                                | ((ts[i + 5] & 0xFFL) << 1) | ((ts[i + 6] & 0x80L) >> 7);
                        int ext = ((ts[i + 6] & 1) << 8) | (ts[i + 7] & 0xFF);
                        pcr = b * 300 + ext;
                    }
                    int stuffFrom = i + 2 + ((flags & 0x10) != 0 ? 6 : 0);
                    for (int k = stuffFrom; k < i + 1 + afLen; k++) {
                        assertEquals(0xFF, ts[k] & 0xFF, "stuffing");
                    }
                }
                i += 1 + afLen;
            }
            out.add(new Packet(pid, pusi, cc, afc == 3, afLen, flags, pcr, Arrays.copyOfRange(ts, i, o + 188)));
        }
        return out;
    }

    /** Reassembles the PES of one PID (each must be complete within {@code packets}). */
    static List<Pes> pes(List<Packet> packets, int pid) {
        List<Pes> out = new ArrayList<>();
        ByteArrayOutputStream cur = null;
        int count = 0;
        for (Packet p : packets) {
            if (p.pid != pid) {
                continue;
            }
            if (p.pusi) {
                if (cur != null) {
                    out.add(finish(pid, cur.toByteArray(), count));
                }
                cur = new ByteArrayOutputStream();
                count = 0;
            }
            cur.write(p.payload, 0, p.payload.length);
            count++;
        }
        if (cur != null) {
            out.add(finish(pid, cur.toByteArray(), count));
        }
        return out;
    }

    static Pes finish(int pid, byte[] b, int count) {
        assertEquals(0, b[0]);
        assertEquals(0, b[1]);
        assertEquals(1, b[2]);
        int len = ((b[4] & 0xFF) << 8) | (b[5] & 0xFF);
        assertEquals(0x80, b[7] & 0xFF, "PTS only");
        assertEquals(5, b[8]);
        assertEquals(0x21, b[9] & 0xF1, "PTS prefix 0010 and marker");
        long pts = ((b[9] & 0x0EL) << 29) | ((b[10] & 0xFFL) << 22) | ((b[11] & 0xFEL) << 14)
                | ((b[12] & 0xFFL) << 7) | ((b[13] & 0xFEL) >> 1);
        if (len != 0) {
            assertEquals(len, b.length - 6, "PES_packet_length");
        }
        return new Pes(pid, pts, len, Arrays.copyOfRange(b, 14, b.length), count);
    }

    /** The section in a PSI packet, CRC checked. */
    static byte[] section(Packet p) {
        assertTrue(p.pusi);
        assertEquals(0, p.payload[0], "pointer field");
        int len = ((p.payload[2] & 0x0F) << 8) | (p.payload[3] & 0xFF);
        byte[] s = Arrays.copyOfRange(p.payload, 1, 4 + len);
        assertEquals(0, TsMuxer.crc32(s, 0, s.length), "CRC over the section incl. its CRC is 0");
        for (int i = 4 + len; i < p.payload.length; i++) {
            assertEquals(0xFF, p.payload[i] & 0xFF, "PSI padding");
        }
        return s;
    }

    // --- tests ---

    @Test
    void crcKnownValue() {
        // CRC-32/MPEG-2 check value of "123456789"
        assertEquals(0x0376E6E7, TsMuxer.crc32("123456789".getBytes(), 0, 9));
    }

    @Test
    void unitStartsWithTablesThenTheIdr() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC, "TogetherTunes", "Lounge");
        List<Packet> ps = parse(mux.mux(unit(S0, 60, 94, 300)));
        assertEquals(TsMuxer.PID_SDT, ps.get(0).pid);
        assertEquals(TsMuxer.PID_PAT, ps.get(1).pid);
        assertEquals(TsMuxer.PID_PMT, ps.get(2).pid);
        Packet idr = ps.get(3);
        assertEquals(TsMuxer.PID_VIDEO, idr.pid);
        assertTrue(idr.pusi && idr.rai(), "IDR with random_access_indicator");
        assertEquals((S0 - 63000) * 300, idr.pcr, "PCR = DTS − 700 ms");

        byte[] pat = section(ps.get(1));
        assertEquals(0x00, pat[0]);
        assertEquals(1, ((pat[3] & 0xFF) << 8) | (pat[4] & 0xFF), "transport_stream_id");
        assertEquals(1, ((pat[8] & 0xFF) << 8) | (pat[9] & 0xFF), "program 1");
        assertEquals(0x100, ((pat[10] & 0x1F) << 8) | (pat[11] & 0xFF), "PMT PID");

        byte[] pmt = section(ps.get(2));
        assertEquals(0x02, pmt[0]);
        assertEquals(0x101, ((pmt[8] & 0x1F) << 8) | (pmt[9] & 0xFF), "PCR PID");
        assertEquals(0x1B, pmt[12]);
        assertEquals(0x101, ((pmt[13] & 0x1F) << 8) | (pmt[14] & 0xFF));
        assertEquals(0x0F, pmt[17]);
        assertEquals(0x102, ((pmt[18] & 0x1F) << 8) | (pmt[19] & 0xFF));
        assertEquals(26, pmt.length, "two streams, no descriptors");

        byte[] sdt = section(ps.get(0));
        assertEquals(0x42, sdt[0]);
        String text = new String(sdt, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("TogetherTunes") && text.contains("Lounge"));
        int loop = ((sdt[14] & 0x0F) << 8) | (sdt[15] & 0xFF);
        assertEquals(0x48, sdt[16]);
        assertEquals(loop - 2, sdt[17], "service_descriptor length");
        assertEquals(sdt.length - 4, 16 + loop, "descriptor loop fills the section");
    }

    @Test
    void videoAndAudioPesContent() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        Unit u = unit(S0, 60, 94, 300);
        List<Packet> ps = parse(mux.mux(u));
        AnnexB annexB = new AnnexB(AVCC);

        List<Pes> video = pes(ps, TsMuxer.PID_VIDEO);
        assertEquals(60, video.size(), "one PES per access unit");
        for (int i = 0; i < 60; i++) {
            Pes p = video.get(i);
            assertEquals(0, p.declaredLength, "unbounded video PES");
            assertEquals(u.video().get(i).pts(), p.pts);
            assertArrayEquals(annexB.accessUnit(u.video().get(i).data()), p.data);
        }

        List<Pes> audio = pes(ps, TsMuxer.PID_AUDIO);
        assertEquals(19, audio.size(), "94 frames in PES of 5 (18 × 5 + 4)");
        int k = 0;
        for (Pes p : audio) {
            assertEquals(u.audio().get(k).pts(), p.pts, "PTS of the first frame");
            int off = 0;
            int n = 0;
            while (off < p.data.length) {
                byte[] h = Arrays.copyOfRange(p.data, off, off + 7);
                assertArrayEquals(Adts.header(300), h);
                assertArrayEquals(u.audio().get(k).data(), Arrays.copyOfRange(p.data, off + 7, off + 307));
                off += 307;
                n++;
                k++;
            }
            assertTrue(n <= 5);
        }
        assertEquals(94, k);
    }

    @Test
    void pcrOnEveryVideoPesAndRaiOnlyOnIdr() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        Unit u = unit(S0, 60, 94, 300);
        List<Packet> ps = parse(mux.mux(u));
        int v = 0;
        for (Packet p : ps) {
            if (p.pid == TsMuxer.PID_VIDEO && p.pusi) {
                assertEquals((u.video().get(v).pts() - 63000) * 300, p.pcr, "PCR " + v);
                assertEquals(v == 0, p.rai(), "RAI " + v);
                v++;
            } else {
                assertEquals(-1, p.pcr, "PCR only at video PES starts");
                assertFalse(p.rai());
            }
        }
        assertEquals(60, v);
    }

    @Test
    void interleavedByDtsAndTablesEvery400ms() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        Unit u = unit(S0, 60, 94, 300);
        List<Packet> ps = parse(mux.mux(u));
        long last = Long.MIN_VALUE;
        long lastTables = -1;
        List<Long> tableTimes = new ArrayList<>();
        int sdtCount = 0;
        for (int i = 0; i < ps.size(); i++) {
            Packet p = ps.get(i);
            if (p.pid == TsMuxer.PID_SDT) {
                sdtCount++;
            }
            if (p.pid == TsMuxer.PID_PAT) {
                assertEquals(TsMuxer.PID_PMT, ps.get(i + 1).pid, "PMT right after PAT");
                lastTables = -2; // the next PES start gives its time
            }
            if (p.pusi && (p.pid == TsMuxer.PID_VIDEO || p.pid == TsMuxer.PID_AUDIO)) {
                long dts = pesPts(p);
                assertTrue(dts >= last, "DTS order");
                last = dts;
                if (lastTables == -2) {
                    tableTimes.add(dts);
                    lastTables = dts;
                }
            }
        }
        assertEquals(1, sdtCount, "SDT once, at the unit start");
        assertEquals(S0, tableTimes.get(0));
        for (int i = 1; i < tableTimes.size(); i++) {
            long gap = tableTimes.get(i) - tableTimes.get(i - 1);
            assertTrue(gap >= 36000 && gap < 36000 + 3000, "PAT/PMT about every 400 ms: " + gap);
        }
        assertEquals(5, tableTimes.size(), "2 s unit: at 0, 0.4, 0.8, 1.2, 1.6 s");
    }

    @Test
    void continuityCountersRunAcrossUnits() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        Map<Integer, Integer> cc = new HashMap<>();
        long start = S0;
        for (int n = 0; n < 12; n++) {
            Unit u = unit(start, 60, n % 4 == 3 ? 93 : 94, 200 + n * 37);
            for (Packet p : parse(mux.mux(u))) {
                Integer prev = cc.get(p.pid);
                if (prev == null) {
                    assertEquals(0, p.cc, "counters start at 0");
                } else {
                    assertEquals((prev + 1) & 15, p.cc, "continuity on PID " + p.pid);
                }
                cc.put(p.pid, p.cc);
            }
            start = u.boundaryPts();
        }
        assertEquals(5, cc.size());
    }

    @Test
    void stuffingOnlyInTheLastPacketOfAPes() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        // frames of every size class: tiny, exactly filling packets, odd sizes
        for (int len = 1; len < 600; len += 7) {
            List<Packet> ps = parse(mux.mux(unit(S0, 3, 6, len)));
            for (int i = 0; i < ps.size(); i++) {
                Packet p = ps.get(i);
                if (p.pid != TsMuxer.PID_VIDEO && p.pid != TsMuxer.PID_AUDIO) {
                    continue;
                }
                boolean lastOfPes = true;
                for (int j = i + 1; j < ps.size(); j++) {
                    if (ps.get(j).pid == p.pid) {
                        lastOfPes = ps.get(j).pusi;
                        break;
                    }
                }
                int stuffing = !p.hasAf ? 0 : p.afLen == 0 ? 1 : p.afLen - 1 - (p.pcr >= 0 ? 6 : 0);
                if (!lastOfPes) {
                    assertEquals(0, stuffing, "stuffing only at a PES end (len " + len + ")");
                }
            }
            // every PES still reassembles to the right size
            for (Pes p : pes(ps, TsMuxer.PID_AUDIO)) {
                assertTrue(p.data.length % (7 + len) == 0);
            }
        }
    }

    @Test
    void wrapsAt33Bits() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        long start = OutputClock.WRAP - 90000; // 1 s before the wrap
        Unit u = unit(start, 60, 94, 100);
        List<Packet> ps = parse(mux.mux(u));
        List<Pes> video = pes(ps, TsMuxer.PID_VIDEO);
        for (int i = 0; i < 60; i++) {
            assertEquals(OutputClock.wrap(u.video().get(i).pts()), video.get(i).pts);
        }
        assertEquals(0, video.get(30).pts, "wrapped to 0 after 1 s");
        int v = 0;
        for (Packet p : ps) {
            if (p.pid == TsMuxer.PID_VIDEO && p.pusi) {
                assertEquals(OutputClock.wrap(u.video().get(v++).pts() - 63000) * 300, p.pcr);
            }
        }
        List<Pes> audio = pes(ps, TsMuxer.PID_AUDIO);
        assertEquals(OutputClock.wrap(u.audio().get(50).pts()), audio.get(10).pts);
    }

    @Test
    void eachCallMakesAFreshArray() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        Unit u = unit(S0, 60, 94, 300);
        byte[] a = mux.mux(u);
        byte[] b = mux.mux(u);
        assertNotSame(a, b);
        assertEquals(a.length, b.length);
        assertFalse(Arrays.equals(a, b), "continuity counters moved on");
    }

    @Test
    void largeAudioFramesSplitPes() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        List<Pes> audio = pes(parse(mux.mux(unit(S0, 60, 94, Adts.MAX_PAYLOAD))), TsMuxer.PID_AUDIO);
        int frames = 0;
        for (Pes p : audio) {
            assertTrue(p.declaredLength > 0 && p.declaredLength <= 0xFFFF);
            frames += p.data.length / (7 + Adts.MAX_PAYLOAD);
        }
        assertEquals(94, frames);
        assertThrows(IOException.class, () -> mux.mux(unit(S0, 60, 94, Adts.MAX_PAYLOAD + 1)));
    }

    @Test
    void badVideoSampleThrows() throws IOException {
        TsMuxer mux = new TsMuxer(AVCC);
        Unit bad = new Unit(S0, S0 + 3000, S0, List.of(new Unit.Video(new byte[] {0, 0, 0, 9, 0x65}, S0, true)),
                List.of(), null, false);
        assertThrows(IOException.class, () -> mux.mux(bad));
    }

    static long pesPts(Packet p) {
        byte[] b = p.payload;
        return ((b[9] & 0x0EL) << 29) | ((b[10] & 0xFFL) << 22) | ((b[11] & 0xFEL) << 14)
                | ((b[12] & 0xFFL) << 7) | ((b[13] & 0xFEL) >> 1);
    }
}
