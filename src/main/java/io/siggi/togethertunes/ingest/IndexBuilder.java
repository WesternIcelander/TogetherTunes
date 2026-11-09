package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an item's {@code index.bin} from ffprobe's packet list, so the packager never parses
 * MP4 at playback time ({@code docs/library-and-ingest.md#sample-index-indexbin}).
 *
 * <p>For a video item it indexes {@code mezz.mp4} (video) and {@code audio.m4a} (audio); for an
 * audio item {@code card.mp4} (video) and {@code audio.m4a}. Per file it runs
 * <pre>ffprobe -v error -select_streams v:0 -show_entries stream=time_base:packet=pts,dts,duration,size,pos,flags -of csv FILE</pre>
 * (the doc's packet command plus the stream time base, in one call; {@code -of csv} keeps the
 * section names so the two kinds of line can be told apart). Packets flagged {@code D} (discard)
 * or with a negative PTS are dropped: ffmpeg's AAC encoder reports its 1024-sample priming as a
 * first packet with {@code pts=-1024} and flag {@code D}. The video avcC comes from the MP4 box
 * walker. The builder does not check the avcC against the reference or the GOP structure; the
 * ingest verify step does.
 */
public final class IndexBuilder {
    /** One file and the track to index from it. */
    public static final class Source {
        private final Path file;
        private final Kind kind;

        public Source(Path file, Kind kind) {
            this.file = file;
            this.kind = kind;
        }

        public Path file() {
            return file;
        }

        public Kind kind() {
            return kind;
        }
    }

    private IndexBuilder() {
    }

    /**
     * Indexes {@code video} (a mezzanine or a card) and {@code audio} and writes the index to
     * {@code out} atomically. Both files must be in the same directory as {@code out}, since the
     * index refers to them by file name.
     */
    public static SampleIndex build(Tools tools, OutputProfile profile, Path video, Path audio, Path out, JobLog log)
            throws IOException, ToolException, InterruptedException {
        List<Source> sources = List.of(new Source(video, Kind.VIDEO), new Source(audio, Kind.AUDIO));
        Path dir = out.toAbsolutePath().getParent();
        for (Source s : sources) {
            if (!s.file().toAbsolutePath().getParent().equals(dir)) {
                throw new IllegalArgumentException(s.file() + " is not next to " + out);
            }
        }
        SampleIndex index = build(tools, profile, sources, log);
        index.write(out);
        return index;
    }

    /**
     * Indexes the given sources, one track each, in order. The avcC is read from the first video
     * source (empty if there is none). Each distinct file becomes one file-ref entry.
     */
    public static SampleIndex build(Tools tools, OutputProfile profile, List<Source> sources, JobLog log)
            throws IOException, ToolException, InterruptedException {
        List<String> files = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        byte[] avcC = new byte[0];
        boolean haveAvcC = false;
        for (Source s : sources) {
            String name = s.file().getFileName().toString();
            int ref = files.indexOf(name);
            if (ref < 0) {
                ref = files.size();
                files.add(name);
            }
            tracks.add(probeTrack(tools, s.file(), s.kind(), ref, log));
            if (s.kind() == Kind.VIDEO && !haveAvcC) {
                avcC = Mp4Boxes.readAvcC(s.file());
                haveAvcC = true;
            }
        }
        return new SampleIndex(profile, files, tracks, avcC);
    }

    /** The ffprobe arguments for one track of {@code file}. */
    static List<String> probeArgs(Path file, Kind kind) {
        return List.of("-v", "error",
                "-select_streams", (kind == Kind.VIDEO ? "v" : "a") + ":0",
                "-show_entries", "stream=time_base:packet=pts,dts,duration,size,pos,flags",
                "-of", "csv",
                file.toString());
    }

    static Track probeTrack(Tools tools, Path file, Kind kind, int fileRef, JobLog log)
            throws IOException, ToolException, InterruptedException {
        PacketCsv csv = new PacketCsv();
        tools.ffprobe(probeArgs(file, kind)).log(log).stdoutLines(csv::accept).run();
        try {
            return csv.toTrack(kind, fileRef);
        } catch (IllegalArgumentException e) {
            throw new IOException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses ffprobe's {@code -of csv} output for one stream: {@code stream,<num>/<den>} and
     * {@code packet,<pts>,<dts>,<duration>,<size>,<pos>,<flags>[,…]} lines (ffprobe appends an
     * empty field to packets with side data, e.g. the AAC priming packet). Other sections are
     * ignored. Not thread-safe; ffprobe's stdout is read by one thread.
     */
    static final class PacketCsv {
        private long timescale = -1;
        private final List<long[]> packets = new ArrayList<>();
        private int dropped;
        private int line;

        void accept(String text) {
            line++;
            if (text.isEmpty()) {
                return;
            }
            String[] f = text.split(",", -1);
            switch (f[0]) {
                case "stream" -> {
                    if (timescale != -1) {
                        throw new IllegalArgumentException("more than one stream selected");
                    }
                    timescale = parseTimeBase(f.length > 1 ? f[1] : "");
                }
                case "packet" -> packet(f);
                default -> {
                }
            }
        }

        private void packet(String[] f) {
            if (f.length < 7) {
                throw new IllegalArgumentException("line " + line + ": expected 6 packet fields");
            }
            String flags = f[6];
            Long pts = number(f[1], "pts");
            if (flags.indexOf('D') >= 0 || (pts != null && pts < 0)) {
                dropped++;
                return;
            }
            if (pts == null) {
                throw new IllegalArgumentException("line " + line + ": packet without pts");
            }
            Long duration = number(f[3], "duration");
            Long size = number(f[4], "size");
            Long pos = number(f[5], "pos");
            if (size == null || pos == null) {
                throw new IllegalArgumentException("line " + line + ": packet without size or pos");
            }
            int flagBits = flags.indexOf('K') >= 0 ? SampleIndex.FLAG_KEYFRAME : 0;
            packets.add(new long[] {pos, size, pts, duration == null ? -1 : duration, flagBits, line});
        }

        private Long number(String s, String what) {
            if (s.isEmpty() || s.equals("N/A")) {
                return null;
            }
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("line " + line + ": bad " + what + " '" + s + "'");
            }
        }

        static long parseTimeBase(String s) {
            int slash = s.indexOf('/');
            try {
                if (slash > 0 && Long.parseLong(s.substring(0, slash)) == 1) {
                    long den = Long.parseLong(s.substring(slash + 1));
                    if (den > 0 && den <= 0xFFFFFFFFL) {
                        return den;
                    }
                }
            } catch (NumberFormatException e) {
                // fall through
            }
            throw new IllegalArgumentException("unsupported time base '" + s + "' (need 1/N)");
        }

        /** Packets dropped for {@code D} or a negative PTS. */
        int dropped() {
            return dropped;
        }

        /**
         * The track. A missing duration is taken from the next packet's PTS (an error for the
         * last packet).
         *
         * @throws IllegalArgumentException if there was no stream line, no packets were kept, or
         *         the packets break the index's rules (e.g. PTS not strictly increasing)
         */
        Track toTrack(Kind kind, int fileRef) {
            if (timescale == -1) {
                throw new IllegalArgumentException("ffprobe reported no " + kind.name().toLowerCase() + " stream");
            }
            if (packets.isEmpty()) {
                throw new IllegalArgumentException("no " + kind.name().toLowerCase() + " packets");
            }
            Track.Builder b = new Track.Builder(kind, timescale, fileRef);
            for (int i = 0; i < packets.size(); i++) {
                long[] p = packets.get(i);
                long duration = p[3];
                if (duration < 0) {
                    if (i + 1 == packets.size()) {
                        throw new IllegalArgumentException("line " + p[5] + ": last packet without duration");
                    }
                    duration = packets.get(i + 1)[2] - p[2];
                }
                try {
                    b.add(p[0], p[1], p[2], duration, (int) p[4]);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("line " + p[5] + ": " + e.getMessage(), e);
                }
            }
            return b.build();
        }
    }
}
