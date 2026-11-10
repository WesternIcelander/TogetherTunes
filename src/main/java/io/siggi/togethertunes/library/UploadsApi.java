package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The upload endpoints ({@code docs/api.md#adding-media}); the work is in {@link Uploads}.
 *
 * <ul>
 *   <li>{@code POST /api/uploads} {@code {filename, size, roomId?, contentId?, addToLibrary?}} →
 *       {@code 201} {@code {uploadId, chunkSize, size}}; or, when the <b>pre-check</b> finds a
 *       ready, pending or processing upload item with that {@code contentId} ({@link ContentId};
 *       only on an {@code https} server, {@link Uploads#start}), {@code 200 {uploadId, chunkSize,
 *       size, deduplicated: true, itemId, status}}: the upload is complete without any bytes
 *       ({@code queue.add {uploadId}} takes it as usual). Errors: {@code 400 invalid_filename},
 *       {@code 400 invalid_size}, {@code 413 payload_too_large} (over {@code ingest.maxUploadBytes},
 *       or a guest's quota size), {@code 429 upload_quota_exceeded} (guests), and the
 *       {@link AddMediaPolicy} errors. A user's {@code roomId} gets {@code 404 room_not_found} until
 *       rooms exist (M3); a guest's goes through the guest rules, whose room half is a stub that
 *       also answers {@code 404 room_not_found} until then. An anonymous visitor who passes every
 *       check becomes a guest here ({@code gid} cookie in cookie mode).</li>
 *   <li>{@code PUT /api/uploads/{id}} with {@code Content-Range: bytes start-end/size} and the
 *       chunk as the body (any content type) → {@code 200 {receivedBytes, size}}. Idempotent: bytes
 *       already received are skipped. A chunk must start at or before {@code receivedBytes}
 *       ({@code 409 upload_offset_mismatch} otherwise).</li>
 *   <li>{@code GET /api/uploads/{id}} → {@code {receivedBytes, size, itemId?}} ({@code itemId}
 *       once completed), to resume after an interruption.</li>
 *   <li>{@code POST /api/uploads/{id}/complete} {@code {title?, artist?, addToLibrary?}} (body
 *       optional) → {@code 200 {itemId, deduplicated}}; repeating it returns the same.</li>
 * </ul>
 *
 * <p>Uploads belong to whoever started them; anyone else gets {@code 404 upload_not_found}. Users
 * need {@code library.upload} on every route; guests need the server's guest upload rules
 * ({@link AddMediaPolicy#requireUploadOwner}) on the routes after {@code POST}.
 */
public final class UploadsApi {
    private final Uploads uploads;
    private final AddMediaPolicy policy;
    private final Guests guests;

    record CreateRequest(String filename, Long size, String roomId, String contentId, Boolean addToLibrary) {
    }

    record CompleteRequest(String title, String artist, Boolean addToLibrary) {
    }

    public UploadsApi(Uploads uploads, AddMediaPolicy policy, Guests guests) {
        this.uploads = Objects.requireNonNull(uploads);
        this.policy = Objects.requireNonNull(policy);
        this.guests = Objects.requireNonNull(guests);
    }

    public void register(Router router) {
        router.post("/api/uploads", this::create);
        router.put("/api/uploads/{id}", this::chunk, policy.uploadOwnerHook());
        router.get("/api/uploads/{id}", this::status, policy.uploadOwnerHook());
        router.post("/api/uploads/{id}/complete", this::complete, policy.uploadOwnerHook());
    }

    private void create(Request request, Response response) throws Exception {
        response.header("Cache-Control", "no-store");
        Actor actor = ActorResolver.actor(request);
        if (actor instanceof Actor.User) {
            policy.requireUpload(actor, null); // before the body, like a permission hook
        }
        CreateRequest body = request.readJson(CreateRequest.class);
        String roomId = body.roomId();
        if (!(actor instanceof Actor.User)) {
            policy.requireUpload(actor, roomId);
        }
        if (roomId != null && actor instanceof Actor.User) {
            // TODO(rooms, M3): a user's upload as a request in a room.
            throw new HttpError(404, "room_not_found", "No such room");
        }
        if (body.size() == null) {
            throw new HttpError(400, "invalid_size", "size is required");
        }
        if (!(actor instanceof Actor.User)) {
            String guestId = actor instanceof Actor.Guest g ? g.guestId() : null;
            // Checked before an anonymous visitor becomes a guest; the quota again (and recorded) on insert.
            uploads.validate(body.filename(), body.size());
            uploads.guestQuota().check(guestId, request.clientIp(), body.size());
            actor = guests.requireIdentity(request, response);
        }
        Uploads.Started started = uploads.start(actor, body.filename(), body.size(), roomId, request.clientIp(),
                body.contentId(), body.addToLibrary());
        Uploads.Upload upload = started.upload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId", upload.id());
        out.put("chunkSize", Uploads.CHUNK_SIZE);
        out.put("size", upload.size());
        if (started.precheckHit()) {
            // The pre-check found the item: no bytes needed, the upload is already complete.
            out.put("deduplicated", true);
            out.put("itemId", upload.itemId());
            out.put("status", started.itemStatus());
            response.json(200, out);
            return;
        }
        response.json(201, out);
    }

    private void chunk(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        long received = uploads.writeChunk(actor, request.pathParam("id"), request);
        response.header("Cache-Control", "no-store");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receivedBytes", received);
        out.put("size", uploads.get(actor, request.pathParam("id")).size());
        response.json(out);
    }

    private void status(Request request, Response response) throws Exception {
        Uploads.Upload upload = uploads.get(ActorResolver.actor(request), request.pathParam("id"));
        response.header("Cache-Control", "no-store");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receivedBytes", uploads.received(upload));
        out.put("size", upload.size());
        if (upload.completed()) {
            out.put("itemId", upload.itemId());
        }
        response.json(out);
    }

    private void complete(Request request, Response response) throws Exception {
        CompleteRequest body = noBody(request) ? new CompleteRequest(null, null, null)
                : request.readJson(CompleteRequest.class);
        Uploads.Completed done = uploads.complete(ActorResolver.actor(request), request.pathParam("id"),
                body.title(), body.artist(), body.addToLibrary());
        response.header("Cache-Control", "no-store");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", done.itemId());
        out.put("deduplicated", done.deduplicated());
        response.json(out);
    }

    private static boolean noBody(Request request) {
        return request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
    }
}
