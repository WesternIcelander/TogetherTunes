package io.siggi.togethertunes.settings;

/**
 * The guest upload quota ({@code guests.uploadQuota}): at most {@code perHour} uploads per
 * rolling hour and {@code maxItemBytes} per uploaded file, counted per guest <em>and</em> per
 * IP, whichever is hit first. {@code perHour = 0} allows no uploads.
 */
public record UploadQuota(int perHour, long maxItemBytes) {
    public UploadQuota {
        if (perHour < 0) {
            throw new IllegalArgumentException("perHour must not be negative");
        }
        if (maxItemBytes < 1) {
            throw new IllegalArgumentException("maxItemBytes must be at least 1");
        }
    }
}
