package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import org.junit.jupiter.api.Test;

/** {@link IngestProgress}: what a room is told about an item's ingest, from its latest job. */
class IngestProgressTest {
    private static JobList.Row row(String itemStatus, State state, Integer position, IngestQueue.LiveStep step,
                                   String error) {
        IngestJob job = new IngestJob("j", "i", Kind.YOUTUBE, state, 1, 0.5, 1, null, null, null, null);
        return new JobList.Row(job, position, new JobList.ItemRef("i", "video", "T", null, "youtube", itemStatus, error),
                step);
    }

    @Test
    void statusFromTheLatestJob() {
        assertEquals("{\"state\":\"queued\",\"kind\":\"youtube\",\"position\":4}",
                IngestProgress.status(row("pending", State.QUEUED, 4, null, null)).toJson().toString());
        assertEquals("{\"state\":\"running\",\"kind\":\"youtube\"}",
                IngestProgress.status(row("processing", State.RUNNING, null, null, null)).toJson().toString());
        assertEquals("{\"state\":\"running\",\"kind\":\"youtube\",\"step\":\"download\",\"progress\":0.45}",
                IngestProgress.status(row("processing", State.RUNNING, null,
                        new IngestQueue.LiveStep("download", 0.4567), null)).toJson().toString());
        assertEquals("{\"state\":\"failed\",\"kind\":\"youtube\",\"error\":\"Video unavailable\"}",
                IngestProgress.status(row("failed", State.FAILED, null, null, "Video unavailable\nstack")).toJson().toString());
        // a job that just finished while the item hasn't changed yet reads as waiting
        assertEquals("queued", IngestProgress.status(row("processing", State.DONE, null, null, null)).state());
        assertNull(IngestProgress.status(row("ready", State.RUNNING, null, null, null)));
        assertNull(IngestProgress.status(row("purged", State.FAILED, null, null, null)));
    }

    @Test
    void shortReasons() {
        assertEquals("Unknown error", IngestProgress.shortReason(null));
        assertEquals("Unknown error", IngestProgress.shortReason("  "));
        assertEquals("first line", IngestProgress.shortReason("  first line \nsecond"));
        String longText = "x".repeat(500);
        String cut = IngestProgress.shortReason(longText);
        assertEquals(IngestProgress.ERROR_MAX, cut.length());
        assertEquals('…', cut.charAt(cut.length() - 1));
    }
}
