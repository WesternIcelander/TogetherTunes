package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The encode jobs page's API ({@code docs/api.md}, {@code docs/library-and-ingest.md#encode-jobs-page}).
 * Responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/admin/jobs?state=active|failed|done&cursor=&limit=} ({@code admin.jobs}) and
 *       {@code GET /api/jobs?…} (any user, {@code 401} for guests and anonymous callers; only jobs of
 *       items in the caller's library) → {@code {"items": [job], "nextCursor"?, "paused"?}}, the
 *       {@link JobList} views ({@code active} is the default). A job is {@code {"id", "kind",
 *       "tier"?, "state", "progress", "attempts", "createdAt", "startedAt"?, "finishedAt"?,
 *       "position"?, "step"?, "stepProgress"?, "item": {"id", "kind", "title", "artist"?, "source", "status", "error"?}}}.
 *       {@code paused} (active view only): workers hold back queued jobs because disk space is low.
 *       {@code limit} 1–200, default 50. {@code 400 invalid_state}, {@code invalid_cursor},
 *       {@code invalid_limit}.</li>
 *   <li>{@code GET /api/admin/jobs/{id}} ({@code admin.jobs}) → the job plus {@code "log"} (its
 *       tail). {@code 404 job_not_found}.</li>
 *   <li>{@code POST /api/admin/jobs/{id}/retry} ({@code admin.jobs}) → {@code 201} the new job
 *       ({@link IngestJobs#retry}). {@code 404 job_not_found}, {@code 409 job_not_failed},
 *       {@code 409 job_superseded}, {@code 409 item_purged}. Audited ({@value #AUDIT_RETRIED}).</li>
 *   <li>{@code POST /api/admin/jobs/{id}/cancel} ({@code admin.jobs}) → the job, now
 *       {@code failed} ({@link IngestJobs#cancel}). {@code 404 job_not_found},
 *       {@code 409 job_not_queued}. Audited ({@value #AUDIT_CANCELLED}).</li>
 * </ul>
 * Nothing here names who added an item or whose library it is in, and nothing carries an IP.
 */
public final class JobsApi {
    /** {@code audit_log} action of an admin's retry. */
    public static final String AUDIT_RETRIED = "job.retried";
    /** {@code audit_log} action of an admin's cancel. */
    public static final String AUDIT_CANCELLED = "job.cancelled";

    static final int LIST_DEFAULT = 50;
    static final int LIST_MAX = 200;
    /** Highest offset a cursor may hold. */
    static final int MAX_OFFSET = 1_000_000;

    private final IngestJobs jobs;
    private final JobList list;
    private final Permissions permissions;
    private final BooleanSupplier paused;
    private final Clock clock;

    /**
     * @param paused whether the workers hold back queued jobs now ({@link IngestQueue#paused})
     */
    public JobsApi(IngestJobs jobs, JobList list, Permissions permissions, BooleanSupplier paused, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs);
        this.list = Objects.requireNonNull(list);
        this.permissions = Objects.requireNonNull(permissions);
        this.paused = Objects.requireNonNull(paused);
        this.clock = Objects.requireNonNull(clock);
    }

    public void register(Router router) {
        var perm = permissions.hook(Permission.ADMIN_JOBS);
        router.get("/api/admin/jobs", this::adminList, perm);
        router.get("/api/admin/jobs/{id}", this::adminGet, perm);
        router.post("/api/admin/jobs/{id}/retry", this::retry, perm);
        router.post("/api/admin/jobs/{id}/cancel", this::cancel, perm);
        router.get("/api/jobs", this::userList);
    }

    private void adminList(Request request, Response response) throws Exception {
        send(request, response, null);
    }

    private void userList(Request request, Response response) throws Exception {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        send(request, response, user.userId());
    }

    private void send(Request request, Response response, String userId) throws Exception {
        String state = request.query("state");
        JobList.View view = state == null || state.isEmpty() ? JobList.View.ACTIVE
                : JobList.View.fromWire(state).orElseThrow(() ->
                        new HttpError(400, "invalid_state", "state must be active, failed or done"));
        int offset = offset(request.query("cursor"));
        int limit = limit(request.query("limit"));
        JobList.Page page = list.list(view, userId, offset, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JobList.Row row : page.rows()) {
            items.add(json(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", page.nextOffset() == null ? null : String.valueOf(page.nextOffset()));
        if (view == JobList.View.ACTIVE) {
            out.put("paused", paused.getAsBoolean());
        }
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private void adminGet(Request request, Response response) throws Exception {
        String id = request.pathParam("id");
        JobList.Row row = list.get(id).orElseThrow(JobsApi::notFound);
        Map<String, Object> out = json(row);
        out.put("log", jobs.get(id).map(IngestJob::log).orElse(null));
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private void retry(Request request, Response response) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the permission hook let only users through
        String ip = request.clientIp();
        IngestJobs.Retry result = jobs.retry(request.pathParam("id"), (c, job) -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("itemId", job.itemId());
            details.put("kind", job.kind().wire());
            AuditLog.write(c, EpochMs.now(clock), user.userId(), null, ip, AUDIT_RETRIED, job.id(), details);
        });
        switch (result.outcome()) {
            case NOT_FOUND -> throw notFound();
            case NOT_FAILED -> throw new HttpError(409, "job_not_failed", "Only a failed job can be retried");
            case SUPERSEDED -> throw new HttpError(409, "job_superseded",
                    "The item has a newer job; retry that one if it fails");
            case ITEM_PURGED -> throw new HttpError(409, "item_purged", "The item was purged");
            case QUEUED -> {
                JobList.Row row = list.get(result.job().id()).orElseThrow(JobsApi::notFound);
                response.header("Cache-Control", "no-store");
                response.json(201, json(row));
            }
        }
    }

    private void cancel(Request request, Response response) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request);
        String ip = request.clientIp();
        String id = request.pathParam("id");
        IngestJobs.CancelOutcome outcome = jobs.cancel(id, (c, job) -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("itemId", job.itemId());
            details.put("kind", job.kind().wire());
            AuditLog.write(c, EpochMs.now(clock), user.userId(), null, ip, AUDIT_CANCELLED, job.id(), details);
        });
        switch (outcome) {
            case NOT_FOUND -> throw notFound();
            case NOT_QUEUED -> throw new HttpError(409, "job_not_queued", "Only a queued job can be cancelled");
            case CANCELLED -> {
                JobList.Row row = list.get(id).orElseThrow(JobsApi::notFound);
                response.header("Cache-Control", "no-store");
                response.json(json(row));
            }
        }
    }

    private static HttpError notFound() {
        return new HttpError(404, "job_not_found", "No such job");
    }

    static Map<String, Object> json(JobList.Row row) {
        IngestJob job = row.job();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.id());
        m.put("kind", job.kind().wire());
        m.put("tier", job.tier() == null ? null : job.tier().wire());
        m.put("state", job.state().wire());
        m.put("progress", job.progress());
        m.put("attempts", job.attempts());
        m.put("createdAt", job.createdAt());
        m.put("startedAt", job.startedAt());
        m.put("finishedAt", job.finishedAt());
        m.put("position", row.position());
        if (row.step() != null) {
            m.put("step", row.step().step());
            m.put("stepProgress", row.step().progress());
        }
        JobList.ItemRef item = row.item();
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("id", item.id());
        i.put("kind", item.kind());
        i.put("title", item.title());
        i.put("artist", item.artist());
        i.put("source", item.source());
        i.put("status", item.status());
        i.put("error", item.error());
        m.put("item", i);
        return m;
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        int offset;
        try {
            offset = Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            offset = -1;
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new HttpError(400, "invalid_cursor", "Bad cursor");
        }
        return offset;
    }

    private static int limit(String param) {
        if (param == null) {
            return LIST_DEFAULT;
        }
        int limit;
        try {
            limit = Integer.parseInt(param);
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > LIST_MAX) {
            throw new HttpError(400, "invalid_limit", "limit must be 1.." + LIST_MAX);
        }
        return limit;
    }
}
