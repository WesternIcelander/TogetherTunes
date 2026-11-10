package io.siggi.togethertunes.output;

import io.siggi.togethertunes.rooms.PlaybackState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * A fake room timeline for the {@link OfflineHarness} ({@code docs/program-output.md#testing-the-packager}):
 * a script of queue actions at fixed times (ms after the session start) that it turns into the
 * {@link ProgramCue}s a room would hand the packager. Like a room, it advances by itself when the
 * current entry reaches its {@link ProgramCue.Entry#durationMs()} (setting
 * {@link ProgramCue#playedOut()}), goes idle when the queue runs empty, and starts playing again
 * when something is queued while idle.
 *
 * <p>Build the script with {@link #at(long)} followed by actions, in non-decreasing time order:
 * <pre>{@code
 * new ScriptedTimeline(t0).at(0).add(video, audio).at(12_000).pause().at(16_000).play()
 *         .at(20_000).seek(3_000).at(30_000).skip()
 * }</pre>
 * Not thread-safe; the harness drives it from one thread.
 */
public final class ScriptedTimeline {
    /** One scripted action. */
    private sealed interface Action {
    }

    private record Add(List<ProgramCue.Entry> entries) implements Action {
    }

    private record Pause() implements Action {
    }

    private record Play() implements Action {
    }

    private record Seek(long positionMs) implements Action {
    }

    private record Skip() implements Action {
    }

    private record Clear() implements Action {
    }

    private record Unknown() implements Action {
    }

    private record AudioOnly(boolean on) implements Action {
    }

    private record Scheduled(long atMs, Action action) {
    }

    /**
     * A change of the timeline.
     *
     * @param atMs     when it happened, ms after the session start
     * @param serverMs when it happened, server time (epoch ms)
     * @param cue      the timeline after it
     * @param what     a short description for logs
     */
    public record Change(long atMs, long serverMs, ProgramCue cue, String what) {
    }

    private final long startServerMs;
    private final Deque<Scheduled> script = new ArrayDeque<>();
    private long cursorMs;

    // the simulated room
    private final Deque<ProgramCue.Entry> queue = new ArrayDeque<>();
    private PlaybackState state = PlaybackState.IDLE;
    private boolean unknownState;
    private ProgramCue.Entry current;
    private long anchorServerMs;
    private long anchorPositionMs;
    private String playedOut;
    private boolean audioOnly;
    private long simulatedToMs = Long.MIN_VALUE;
    private ProgramCue cue = ProgramCue.IDLE;

    /** @param startServerMs server time (epoch ms) of the session start: script time 0 */
    public ScriptedTimeline(long startServerMs) {
        this.startServerMs = startServerMs;
    }

    /** Server time of script time 0. */
    public long startServerMs() {
        return startServerMs;
    }

    /** Following actions happen {@code ms} after the session start (not before earlier ones). */
    public ScriptedTimeline at(long ms) {
        if (ms < cursorMs) {
            throw new IllegalArgumentException("script time goes backwards: " + ms + " < " + cursorMs);
        }
        cursorMs = ms;
        return this;
    }

    /** Appends entries to the queue; if the room is idle, the first starts playing from 0. */
    public ScriptedTimeline add(ProgramCue.Entry... entries) {
        for (ProgramCue.Entry e : entries) {
            Objects.requireNonNull(e, "entry");
            if (e.durationMs() <= 0) {
                throw new IllegalArgumentException("entry " + e.entryId() + " has no duration");
            }
        }
        return schedule(new Add(List.of(entries)));
    }

    /** Pauses (if playing). */
    public ScriptedTimeline pause() {
        return schedule(new Pause());
    }

    /** Resumes (if paused). */
    public ScriptedTimeline play() {
        return schedule(new Play());
    }

    /** Seeks the current entry to {@code positionMs} (keeps playing or paused). */
    public ScriptedTimeline seek(long positionMs) {
        if (positionMs < 0) {
            throw new IllegalArgumentException("negative position");
        }
        return schedule(new Seek(positionMs));
    }

    /** Skips to the next queued entry (or idle). */
    public ScriptedTimeline skip() {
        return schedule(new Skip());
    }

    /** Empties the queue and goes idle. */
    public ScriptedTimeline clear() {
        return schedule(new Clear());
    }

    /**
     * Hands the packager a cue in a state it doesn't recognise ({@code state = null}) until the next
     * action; the output plays the Paused filler meanwhile.
     */
    public ScriptedTimeline unknownState() {
        return schedule(new Unknown());
    }

    /**
     * Sets the room's {@code audioOnly} setting ({@link ProgramCue#audioOnly()}: the fillers' rate).
     * The entries carry their own mode, as the room gives it to them: add entries made with
     * {@code audioOnly} for those that start after the switch.
     */
    public ScriptedTimeline audioOnly(boolean on) {
        return schedule(new AudioOnly(on));
    }

    private ScriptedTimeline schedule(Action a) {
        if (simulatedToMs != Long.MIN_VALUE) {
            throw new IllegalStateException("the script is already running");
        }
        script.addLast(new Scheduled(cursorMs, a));
        return this;
    }

    /** The latest cue (the room's timeline as of the last {@link #advanceTo}). */
    public ProgramCue cue() {
        return cue;
    }

    /**
     * Runs the simulated room up to and including {@code atMs} (ms after the session start):
     * scripted actions and natural ends, in time order (a natural end first when both fall on the
     * same ms). Returns the changes, in order; empty if nothing changed.
     */
    public List<Change> advanceTo(long atMs) {
        List<Change> changes = new ArrayList<>();
        if (simulatedToMs != Long.MIN_VALUE && atMs <= simulatedToMs) {
            return changes;
        }
        while (true) {
            long end = naturalEndMs();
            Scheduled next = script.peekFirst();
            long scripted = next == null ? Long.MAX_VALUE : next.atMs();
            if (end <= atMs && end <= scripted) {
                String finished = current.entryId();
                advance(end);
                playedOut = finished;
                changes.add(change(end, "natural end of " + finished));
            } else if (scripted <= atMs) {
                script.removeFirst();
                String what = apply(next);
                if (what != null) {
                    changes.add(change(next.atMs(), what));
                }
            } else {
                break;
            }
        }
        simulatedToMs = atMs;
        return changes;
    }

    /** Script time at which the playing entry reaches its end, or {@link Long#MAX_VALUE}. */
    private long naturalEndMs() {
        if (state != PlaybackState.PLAYING || unknownState) {
            return Long.MAX_VALUE;
        }
        long left = Math.max(0, current.durationMs() - anchorPositionMs);
        return anchorServerMs - startServerMs + left;
    }

    private long positionAt(long atMs) {
        long serverMs = startServerMs + atMs;
        return state == PlaybackState.PLAYING ? anchorPositionMs + (serverMs - anchorServerMs) : anchorPositionMs;
    }

    /** Applies a scripted action; returns a description, or null if it changed nothing. */
    private String apply(Scheduled s) {
        long t = s.atMs();
        boolean wasUnknown = unknownState;
        unknownState = false;
        String what = switch (s.action()) {
            case Add a -> {
                queue.addAll(a.entries());
                if (state == PlaybackState.IDLE) {
                    advance(t);
                }
                yield "add " + a.entries().stream().map(ProgramCue.Entry::entryId).toList();
            }
            case Pause p -> {
                if (state != PlaybackState.PLAYING) {
                    yield null;
                }
                anchorPositionMs = Math.min(positionAt(t), current.durationMs());
                anchorServerMs = startServerMs + t;
                state = PlaybackState.PAUSED;
                yield "pause at " + anchorPositionMs + " ms";
            }
            case Play p -> {
                if (state != PlaybackState.PAUSED) {
                    yield null;
                }
                anchorServerMs = startServerMs + t;
                state = PlaybackState.PLAYING;
                yield "play from " + anchorPositionMs + " ms";
            }
            case Seek k -> {
                if (state == PlaybackState.IDLE) {
                    yield null;
                }
                anchorServerMs = startServerMs + t;
                anchorPositionMs = Math.min(k.positionMs(), current.durationMs());
                yield "seek to " + anchorPositionMs + " ms";
            }
            case Skip k -> {
                if (state == PlaybackState.IDLE) {
                    yield null;
                }
                String from = current.entryId();
                advance(t);
                yield "skip " + from;
            }
            case Clear c -> {
                queue.clear();
                current = null;
                state = PlaybackState.IDLE;
                yield "clear";
            }
            case Unknown u -> {
                unknownState = true;
                yield "unknown state";
            }
            case AudioOnly o -> {
                if (audioOnly == o.on()) {
                    yield null;
                }
                audioOnly = o.on();
                yield "audio-only room " + (o.on() ? "on" : "off");
            }
        };
        if (what == null && wasUnknown) {
            what = "back from unknown state";
        }
        if (what != null) {
            playedOut = null;
        }
        return what;
    }

    /** Moves to the next queued entry (playing from 0 at {@code atMs}), or idle. */
    private void advance(long atMs) {
        current = queue.pollFirst();
        state = current == null ? PlaybackState.IDLE : PlaybackState.PLAYING;
        anchorServerMs = startServerMs + atMs;
        anchorPositionMs = 0;
        playedOut = null;
    }

    private Change change(long atMs, String what) {
        if (unknownState) {
            cue = new ProgramCue(null, current, anchorServerMs, anchorPositionMs, queue.peekFirst(), null, audioOnly);
        } else if (state == PlaybackState.IDLE) {
            cue = audioOnly ? new ProgramCue(PlaybackState.IDLE, null, 0, 0, null, null, true) : ProgramCue.IDLE;
        } else {
            cue = new ProgramCue(state, current, anchorServerMs, anchorPositionMs, queue.peekFirst(), playedOut,
                    audioOnly);
        }
        return new Change(atMs, startServerMs + atMs, cue, what);
    }
}
