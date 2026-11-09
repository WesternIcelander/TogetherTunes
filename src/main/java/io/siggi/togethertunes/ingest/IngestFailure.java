package io.siggi.togethertunes.ingest;

/**
 * A job failure with a message meant for the person who added the item (e.g. "The file has no
 * audio stream"): {@link IngestQueue} stores the message as the item's error and logs it without
 * a stack trace.
 */
public class IngestFailure extends Exception {
    public IngestFailure(String message) {
        super(message);
    }

    public IngestFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
