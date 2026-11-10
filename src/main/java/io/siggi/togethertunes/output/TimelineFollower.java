package io.siggi.togethertunes.output;

import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.rooms.PlaybackState;
import java.io.IOException;

/**
 * Makes the program output follow the room's timeline
 * ({@code docs/program-output.md#applying-timeline-changes}). It decides, at every unit boundary,
 * which source the {@link UnitBuilder} plays, and never feeds anything back into the timeline
 * (invariant 4).
 *
 * <ul>
 * <li><b>Changes apply at the next unit boundary.</b> Before each unit it asks: is the source being
 * played still what the timeline says should be playing at this point (where a new source would
 * start, {@link UnitBuilder#switchPoint()}, mapped to server time by the {@link ServerClock})? If not, it switches: a new entry or a seek → the item
 * at the timeline's position there; {@code PAUSED} → {@link Filler#PAUSED}; {@code IDLE} →
 * {@link Filler#IDLE}; a state it doesn't recognise ({@code null}) → {@link Filler#PAUSED}. A
 * re-anchor that doesn't move the position (by {@link #SAME_POSITION_MS} or more) isn't a seek.</li>
 * <li><b>Natural end mid-unit.</b> An item ends where its audio ends ({@code E_n}, usually mid-unit;
 * the builder ends the unit there). The next unit starts at {@code E_n} with the next item from
 * position 0 (the room's next entry, or the one it already advanced to) or a filler. If the room
 * advances first (the output is a little behind), the output still plays its item to the end: the
 * room says so with {@link ProgramCue#playedOut()}.</li>
 * <li><b>Drift</b> is not corrected mid-item. At an item boundary, if the output is more than
 * {@link #RESYNC_MS} behind the timeline, the next item starts at the timeline's position for it
 * instead of 0; if it is more than that ahead (the item ended in the output while the timeline
 * still has more than 3 s of it), a {@link Filler#LOADING} filler plays until the timeline moves on.</li>
 * <li>An item that can't be played yet ({@link SourceFactory#item} gives null or fails) → the
 * {@link Filler#LOADING} filler, retried at every boundary.</li>
 * </ul>
 *
 * <p>Threads: {@link #update} may be called from any thread (the room's executor); everything else
 * runs on the one packager thread.
 */
public final class TimelineFollower implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TimelineFollower.class.getName());

    /** Drift between output and timeline at an item boundary beyond which the output re-syncs. */
    public static final long RESYNC_MS = 3000;
    /** A re-anchor of the playing entry that moves its position by less than this isn't a seek. */
    public static final long SAME_POSITION_MS = 100;

    /** Maps the (unwrapped) output clock to server time (epoch ms); set up by the pacing. */
    @FunctionalInterface
    public interface ServerClock {
        long serverMsAt(long outputPts);
    }

    /** Makes the sources the follower plays. */
    public interface SourceFactory {
        /**
         * A source for {@code entry}'s item starting at {@code startMs} of media time, or null if it
         * can't be played yet (its index isn't loaded): the follower then plays {@link Filler#LOADING}
         * and asks again at the next boundary.
         */
        ProgramSource item(ProgramCue.Entry entry, long startMs) throws IOException;

        /** A filler source (never ends). */
        ProgramSource filler(Filler filler);
    }

    private final UnitBuilder builder;
    private final SourceFactory factory;
    private final ServerClock clock;
    private volatile ProgramCue cue;

    /** The entry whose item is playing, or null while a filler plays. */
    private ProgramCue.Entry entry;
    /** The filler playing, or null while an item plays. */
    private Filler filler;
    /** Where the playing item started on the output clock, and from which media time. */
    private long entryStartPts;
    private long entryMediaStartMs;
    /** The cue the playing source was chosen for (or adopted, when the room caught up). */
    private ProgramCue followed;
    /**
     * The entry whose item already ended in the output while the timeline still plays it (output
     * ahead), and the cue it ended under: the follower doesn't play it again unless that cue changes.
     */
    private String endedEntryId;
    private ProgramCue endedCue;

    /**
     * @param initial the timeline to start with (null: nothing known yet, played as paused)
     */
    public TimelineFollower(UnitBuilder builder, SourceFactory factory, ServerClock clock, ProgramCue initial) {
        if (builder == null || factory == null || clock == null) {
            throw new IllegalArgumentException("builder, factory and clock are required");
        }
        this.builder = builder;
        this.factory = factory;
        this.clock = clock;
        this.cue = initial;
    }

    /** The room's timeline changed: applied at the next unit boundary. Any thread. */
    public void update(ProgramCue newCue) {
        this.cue = newCue;
    }

    /** The latest cue handed over. */
    public ProgramCue cue() {
        return cue;
    }

    /** The entry whose item is playing, or null while a filler plays. */
    public ProgramCue.Entry playingEntry() {
        return entry;
    }

    /** The filler playing, or null while an item plays. */
    public Filler playingFiller() {
        return filler;
    }

    /**
     * Applies the latest cue at this unit boundary and builds the next unit. Never null: when a
     * source gives nothing, the follower decides again (in the end, a filler always gives a unit).
     */
    public Unit nextUnit() throws IOException {
        for (int attempt = 0; attempt < 4; attempt++) {
            decide(cue);
            Unit u = builder.next();
            if (u != null) {
                return u;
            }
        }
        // sources that keep ending at once (e.g. a seek past the end of the media): rest on a filler
        playFiller(Filler.PAUSED, cue);
        return builder.next();
    }

    private void decide(ProgramCue c) {
        if (builder.source() == null) {
            follow(c, false);
        } else if (builder.sourceEnded()) {
            ended(c);
        } else {
            boundary(c);
        }
    }

    /** The playing source runs on into the next unit unless the timeline says otherwise. */
    private void boundary(ProgramCue c) {
        if (entry != null && c != null && c.state() == PlaybackState.PLAYING) {
            String x = c.current().entryId();
            // where a new source would start (up to one audio frame past the unit boundary)
            long at = builder.switchPoint();
            long now = clock.serverMsAt(at);
            if (entry.entryId().equals(x)) {
                if (c == followed || sameMotion(followed, c, now)) {
                    followed = c;
                    return;
                }
                if (followed == null || !followed.playing(x)) {
                    // started at its natural point before the room advanced to it: adopt the room's
                    // cue unless the two are far apart
                    if (Math.abs(outputPositionMs(at) - c.positionAt(now)) <= RESYNC_MS) {
                        followed = c;
                        return;
                    }
                }
                startItem(c, c.current(), c.positionAt(now)); // a seek
                return;
            }
            if (entry.entryId().equals(c.playedOut())) {
                return; // the room moved on at the item's end; the output plays its copy to the end
            }
        }
        follow(c, false);
    }

    /** The playing source ended at {@code E_n}: start what comes next there. */
    private void ended(ProgramCue c) {
        ProgramCue.Entry endedEntry = entry;
        entry = null;
        if (endedEntry != null && c != null && c.playing(endedEntry.entryId())) {
            // the room hasn't advanced yet
            long now = clock.serverMsAt(builder.switchPoint());
            long remaining = endedEntry.durationMs() - c.positionAt(now);
            endedEntryId = endedEntry.entryId();
            endedCue = c;
            if (remaining > RESYNC_MS) {
                playFiller(Filler.LOADING, c); // the output is ahead: wait for the timeline
            } else if (c.next() == null) {
                playFiller(Filler.IDLE, c); // the room goes idle next
            } else {
                long behind = Math.max(0, -remaining);
                startItem(c, c.next(), behind > RESYNC_MS ? behind : 0);
            }
            return;
        }
        follow(c, true);
    }

    /**
     * Plays what the timeline says, unless it's the entry that already ended in the output under
     * the same cue. {@code itemBoundary}: an item just ended, so a drift of up to
     * {@link #RESYNC_MS} is let go (the next item starts at 0).
     */
    private void follow(ProgramCue c, boolean itemBoundary) {
        if (c == null || c.state() == null) {
            playFiller(Filler.PAUSED, c);
            return;
        }
        switch (c.state()) {
            case IDLE -> playFiller(Filler.IDLE, c);
            case PAUSED -> playFiller(Filler.PAUSED, c);
            case PLAYING -> {
                ProgramCue.Entry x = c.current();
                long now = clock.serverMsAt(builder.switchPoint());
                if (x.entryId().equals(endedEntryId)) {
                    if (c == endedCue || sameMotion(endedCue, c, now)) {
                        endedCue = c;
                        return; // keep what plays after it (the next item or a filler)
                    }
                }
                long pos = Math.max(0, c.positionAt(now));
                if (itemBoundary && pos <= RESYNC_MS) {
                    pos = 0;
                }
                if (pos >= x.durationMs()) {
                    // the timeline is at the end, the room is about to advance
                    endedEntryId = x.entryId();
                    endedCue = c;
                    if (c.next() == null) {
                        playFiller(Filler.IDLE, c);
                    } else {
                        long into = pos - x.durationMs();
                        startItem(c, c.next(), into > RESYNC_MS ? into : 0);
                    }
                    return;
                }
                startItem(c, x, pos);
            }
        }
    }

    private void startItem(ProgramCue c, ProgramCue.Entry x, long startMs) {
        ProgramSource src;
        try {
            src = factory.item(x, startMs);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Program output: can't play item " + x.itemId() + ": " + e, e);
            src = null;
        }
        if (src == null) {
            playFiller(Filler.LOADING, c);
            return;
        }
        builder.play(src);
        entry = x;
        filler = null;
        followed = c;
        entryStartPts = builder.switchPoint();
        entryMediaStartMs = src instanceof ItemSource is ? is.mediaStartMs() : startMs;
        if (c == null || !c.playing(endedEntryId) || x.entryId().equals(endedEntryId)) {
            // the guard lasts only while the cue still plays the entry that ended
            endedEntryId = null;
            endedCue = null;
        }
    }

    private void playFiller(Filler f, ProgramCue c) {
        followed = c;
        if (entry == null && filler == f && builder.source() != null && !builder.sourceEnded()) {
            return;
        }
        builder.play(factory.filler(f));
        entry = null;
        filler = f;
    }

    /** The playing item's media position at {@code pts} on the output clock. */
    private long outputPositionMs(long pts) {
        return entryMediaStartMs + Math.floorDiv(pts - entryStartPts, OutputClock.HZ / 1000);
    }

    /** Whether two cues play the same entry at the same position (a re-anchor that isn't a seek). */
    private static boolean sameMotion(ProgramCue a, ProgramCue b, long serverMs) {
        return a != null && b != null && a.state() == PlaybackState.PLAYING && b.state() == PlaybackState.PLAYING
                && a.current().entryId().equals(b.current().entryId())
                && Math.abs(a.positionAt(serverMs) - b.positionAt(serverMs)) < SAME_POSITION_MS;
    }

    @Override
    public void close() {
        builder.close();
    }
}
