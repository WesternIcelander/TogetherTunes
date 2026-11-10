package io.siggi.togethertunes.ingest;

import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the ingest of items that aren't ready is doing, for the rooms' queue entries and
 * suggestions ({@code docs/rooms.md#entries-that-arent-ready}, the {@code ingest} WebSocket
 * message): waiting (with the place in the queue), downloading, encoding or finishing (with the
 * step's own progress), or failed (with a short reason). No logs, nothing about other people.
 */
@FunctionalInterface
public interface IngestProgress {
    /** Knows nothing: every item reads as waiting. */
    IngestProgress NONE = itemIds -> Map.of();

    /** Longest {@link Status#error} in characters. */
    int ERROR_MAX = 120;

    /**
     * The status of each of {@code itemIds} that isn't ready (ready and purged items and unknown
     * ids are left out).
     */
    Map<String, Status> of(Collection<String> itemIds) throws SQLException;

    /**
     * One item's ingest, as sent to rooms.
     *
     * @param state    {@code queued}, {@code running} or {@code failed}
     * @param kind     the job's kind ({@code upload}, {@code youtube}, …), null if the item has no job
     * @param position 1-based place in the whole ingest queue (queued only), else null
     * @param step     {@code download}, {@code encode} or {@code finish} (running only, once known)
     * @param progress the step's own progress, 0..1, with {@code step}
     * @param error    a short reason (failed only)
     */
    record Status(String state, String kind, Integer position, String step, Double progress, String error) {
        /** {@code {state, kind?, position?, step?, progress?, error?}}. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("state", state);
            if (kind != null) {
                o.addProperty("kind", kind);
            }
            if (position != null) {
                o.addProperty("position", position);
            }
            if (step != null) {
                o.addProperty("step", step);
                // whole percent: finer steps would only make more messages
                o.addProperty("progress", Math.floor((progress == null ? 0 : progress) * 100) / 100.0);
            }
            if (error != null) {
                o.addProperty("error", error);
            }
            return o;
        }
    }

    /** From the jobs table (latest job per item, place in the claim order) and the queue's live steps. */
    static IngestProgress from(JobList list) {
        Objects.requireNonNull(list);
        return itemIds -> {
            Map<String, Status> out = new LinkedHashMap<>();
            for (JobList.Row row : list.latest(itemIds).values()) {
                Status s = status(row);
                if (s != null) {
                    out.put(row.item().id(), s);
                }
            }
            return out;
        };
    }

    /** The status of a row's item, or null if it is ready or purged. */
    static Status status(JobList.Row row) {
        String itemStatus = row.item().status();
        if ("ready".equals(itemStatus) || "purged".equals(itemStatus)) {
            return null;
        }
        IngestJob job = row.job();
        String kind = job.kind().wire();
        if ("failed".equals(itemStatus)) {
            return new Status("failed", kind, null, null, null, shortReason(row.item().error()));
        }
        return switch (job.state()) {
            case RUNNING -> {
                IngestQueue.LiveStep step = row.step();
                yield new Status("running", kind, null, step == null ? null : step.step(),
                        step == null ? null : step.progress(), null);
            }
            case QUEUED -> new Status("queued", kind, row.position(), null, null, null);
            // done or failed while the item isn't ready yet (a moment before the item changes): waiting
            default -> new Status("queued", kind, null, null, null, null);
        };
    }

    /** The first line of an item's error, at most {@link #ERROR_MAX} characters; "Unknown error" if none. */
    static String shortReason(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown error";
        }
        String line = error.strip();
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).strip();
        }
        if (line.length() > ERROR_MAX) {
            line = line.substring(0, ERROR_MAX - 1).strip() + "…";
        }
        return line;
    }
}
