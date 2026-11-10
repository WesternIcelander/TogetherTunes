package io.siggi.togethertunes.output;

/**
 * Told when somebody listens to a pull output ({@code .ts}, HLS, MP3), for the auto turn-off's
 * "no listeners" clock ({@link OutputAutoOff}). Called from request threads; must not block.
 */
public interface OutputListeners {
    /** Nobody is told anything (tests). */
    OutputListeners NONE = new OutputListeners() {
        @Override
        public void opened(String roomId, StreamOutputs.Kind kind) {
        }

        @Override
        public void closed(String roomId, StreamOutputs.Kind kind) {
        }

        @Override
        public void touched(String roomId, StreamOutputs.Kind kind) {
        }
    };

    /** A {@code .ts} or MP3 connection opened (it counts as listening until {@link #closed}). */
    void opened(String roomId, StreamOutputs.Kind kind);

    /** That connection ended. */
    void closed(String roomId, StreamOutputs.Kind kind);

    /** An HLS playlist or segment request: listening at that moment. */
    void touched(String roomId, StreamOutputs.Kind kind);
}
