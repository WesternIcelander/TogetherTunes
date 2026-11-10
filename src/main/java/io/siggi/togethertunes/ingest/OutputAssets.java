package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The output encoding the server encodes for now ({@code output.profile} at
 * {@code output.videoBitrateKbps}), with its reference card and filler cards
 * ({@code docs/library-and-ingest.md#changing-the-encoding-at-runtime}).
 *
 * <p>Ingest (new items and re-encodes), the upload and import services (the encoding new items
 * are recorded with) and the program outputs read {@link #current()} instead of a fixed encoding,
 * so an admin can change the encoding without a restart: {@link #follow} makes the new
 * encoding's reference card and filler cards first (the old ones stay in use meanwhile), then
 * swaps them in and tells the {@link Listener}s, which queue the re-encodes and restart the
 * running program outputs. Switches run one at a time.
 */
public final class OutputAssets {
    private static final System.Logger LOG = System.getLogger(OutputAssets.class.getName());

    /**
     * One encoding and its cards.
     *
     * @param reference   its reference card; null when it couldn't be made (no ffmpeg at startup)
     * @param fillerCards its filler cards; null when they couldn't be made
     */
    public record Assets(OutputEncoding encoding, ReferenceCard reference, FillerCards fillerCards) {
        public Assets {
            Objects.requireNonNull(encoding, "encoding");
        }
    }

    /** Makes (or finds) an encoding's reference card and filler cards. */
    @FunctionalInterface
    public interface Maker {
        /** @return the assets; {@code reference} is null if the reference card couldn't be made */
        Assets make(OutputEncoding encoding) throws InterruptedException;
    }

    /** Told after the encoding changed (on the switching thread). */
    @FunctionalInterface
    public interface Listener {
        void switched(Assets previous, Assets now);
    }

    private final Maker maker;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock switching = new ReentrantLock();
    private volatile Assets current;

    public OutputAssets(Maker maker, Assets initial) {
        this.maker = Objects.requireNonNull(maker);
        this.current = Objects.requireNonNull(initial);
    }

    /** Fixed assets that never change (tests, and servers that can't encode). */
    public static OutputAssets fixed(Assets assets) {
        return new OutputAssets(encoding -> {
            throw new IllegalStateException("the output encoding can't change here");
        }, assets);
    }

    /** The encoding and cards in use now. */
    public Assets current() {
        return current;
    }

    /** The encoding in use now. */
    public OutputEncoding encoding() {
        return current.encoding();
    }

    /** The reference card in use now, or null. */
    public ReferenceCard reference() {
        return current.reference();
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Switches to the encoding {@code target} gives, read once this switch has its turn (so of two
     * quick changes, the later one wins). Nothing happens if it's already the current one.
     *
     * @return whether the encoding changed
     * @throws IOException if the new encoding's reference card couldn't be made; the current
     *                     encoding stays
     */
    public boolean follow(Callable<OutputEncoding> target) throws Exception {
        switching.lock();
        try {
            OutputEncoding wanted = Objects.requireNonNull(target.call(), "target encoding");
            Assets previous = current;
            if (previous.encoding().equals(wanted)) {
                return false;
            }
            Assets made = maker.make(wanted);
            if (made == null || made.reference() == null || !made.encoding().equals(wanted)) {
                throw new IOException("could not create the " + wanted.id() + " reference card; still encoding for "
                        + previous.encoding().id());
            }
            current = made;
            LOG.log(System.Logger.Level.INFO, "Output encoding changed from " + previous.encoding().id() + " to "
                    + wanted.id());
            for (Listener l : listeners) {
                try {
                    l.switched(previous, made);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "An output encoding listener failed", e);
                }
            }
            return true;
        } finally {
            switching.unlock();
        }
    }
}
