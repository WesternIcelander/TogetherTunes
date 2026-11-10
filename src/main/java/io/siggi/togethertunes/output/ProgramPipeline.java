package io.siggi.togethertunes.output;

import java.io.IOException;
import java.util.Objects;

/**
 * Builds a room's running program output ({@link ProgramOutputs.OutputFactory}): a fresh
 * {@link PacingClock} (the output clock starts over at {@link OutputClock#START}), {@link UnitBuilder},
 * {@link TimelineFollower} on the room's sources, {@link TsMuxer} and a started {@link Packager}.
 */
public final class ProgramPipeline implements ProgramOutputs.OutputFactory {
    private static final System.Logger LOG = System.getLogger(ProgramPipeline.class.getName());

    /** Opens the sources for one output ({@link ProgramMedia} in production); closed with the output if closeable. */
    @FunctionalInterface
    public interface SourcesOpener {
        TimelineFollower.SourceFactory open() throws IOException;
    }

    /**
     * Opens the sources for one output together with the avcC they were encoded for (so an output
     * started after the output encoding changed uses the new encoding throughout).
     */
    @FunctionalInterface
    public interface EncodedOpener {
        Opened open() throws IOException;
    }

    /** One output's sources and the reference avcC its muxer writes SPS/PPS from. */
    public record Opened(TimelineFollower.SourceFactory sources, byte[] avcC) {
        public Opened {
            Objects.requireNonNull(sources, "sources");
            Objects.requireNonNull(avcC, "avcC");
        }
    }

    private final EncodedOpener opener;
    private final TimeSource time;

    /**
     * @param sources opens each output's sources
     * @param avcC    the profile's reference avcC (what the muxer writes SPS/PPS from)
     * @param time    real time in production
     */
    public ProgramPipeline(SourcesOpener sources, byte[] avcC, TimeSource time) {
        Objects.requireNonNull(sources, "sources");
        byte[] fixed = Objects.requireNonNull(avcC, "avcC").clone();
        this.opener = () -> new Opened(sources.open(), fixed);
        this.time = Objects.requireNonNull(time, "time");
    }

    /** @param opener opens each output's sources with their encoding's avcC */
    public ProgramPipeline(EncodedOpener opener, TimeSource time) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.time = Objects.requireNonNull(time, "time");
    }

    @Override
    public ProgramOutputs.Output start(String roomId, ProgramCue initial) throws IOException {
        Opened opened = opener.open();
        TimelineFollower.SourceFactory src = opened.sources();
        byte[] avcC = opened.avcC();
        try {
            PacingClock clock = new PacingClock(time);
            TimelineFollower follower = new TimelineFollower(new UnitBuilder(), src, clock, initial);
            TsMuxer muxer = new TsMuxer(avcC);
            Packager packager = new Packager(roomId, follower, muxer::mux, clock, time);
            packager.start();
            return new Running(packager, follower, src);
        } catch (RuntimeException | IOException e) {
            closeQuietly(src);
            throw e;
        }
    }

    private static void closeQuietly(Object o) {
        if (o instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Closing program output sources failed", e);
            }
        }
    }

    private record Running(Packager packager, TimelineFollower follower, TimelineFollower.SourceFactory sources)
            implements ProgramOutputs.Output {
        @Override
        public void update(ProgramCue cue) {
            follower.update(cue);
        }

        @Override
        public void close() {
            packager.close(); // also closes the follower (on the packager thread)
            closeQuietly(sources);
        }
    }
}
