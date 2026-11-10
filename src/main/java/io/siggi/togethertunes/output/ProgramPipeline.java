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

    private final SourcesOpener sources;
    private final byte[] avcC;
    private final TimeSource time;

    /**
     * @param sources opens each output's sources
     * @param avcC    the profile's reference avcC (what the muxer writes SPS/PPS from)
     * @param time    real time in production
     */
    public ProgramPipeline(SourcesOpener sources, byte[] avcC, TimeSource time) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.avcC = Objects.requireNonNull(avcC, "avcC").clone();
        this.time = Objects.requireNonNull(time, "time");
    }

    @Override
    public ProgramOutputs.Output start(String roomId, ProgramCue initial) throws IOException {
        TimelineFollower.SourceFactory src = sources.open();
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
