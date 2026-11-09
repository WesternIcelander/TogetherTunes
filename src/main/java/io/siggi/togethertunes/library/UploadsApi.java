package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
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
 *   <li>{@code POST /api/uploads} {@code {filename, size, roomId?}} → {@code 201}
 *       {@code {uploadId, chunkSize, size}}. Errors: {@code 400 invalid_filename},
 *       {@code 400 invalid_size}, {@code 413 payload_too_large} (over {@code ingest.maxUploadBytes}),
 *       {@code 404 room_not_found} for a {@code roomId} (rooms come in M3).</li>
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
 * <p>Uploads belong to whoever started them; anyone else gets {@code 404 upload_not_found}. Every
 * route needs {@code library.upload} ({@code 401} for guests and anonymous callers: guest uploads
 * are requests in a room, which come with rooms and the guest upload rules).
 */
public final class UploadsApi {
    private final Uploads uploads;
    private final Permissions permissions;

    record CreateRequest(String filename, Long size, String roomId) {
    }

    record CompleteRequest(String title, String artist, Boolean addToLibrary) {
    }

    public UploadsApi(Uploads uploads, Permissions permissions) {
        this.uploads = Objects.requireNonNull(uploads);
        this.permissions = Objects.requireNonNull(permissions);
    }

    public void register(Router router) {
        router.post("/api/uploads", this::create, permissions.hook(Permission.LIBRARY_UPLOAD));
        router.put("/api/uploads/{id}", this::chunk, permissions.hook(Permission.LIBRARY_UPLOAD));
        router.get("/api/uploads/{id}", this::status, permissions.hook(Permission.LIBRARY_UPLOAD));
        router.post("/api/uploads/{id}/complete", this::complete, permissions.hook(Permission.LIBRARY_UPLOAD));
    }

    private void create(Request request, Response response) throws Exception {
        CreateRequest body = request.readJson(CreateRequest.class);
        response.header("Cache-Control", "no-store");
        if (body.roomId() != null) {
            // TODO(rooms, M3): an upload as a request in a room, with the room's guest rules.
            throw new HttpError(404, "room_not_found", "No such room");
        }
        if (body.size() == null) {
            throw new HttpError(400, "invalid_size", "size is required");
        }
        Uploads.Upload upload = uploads.create(ActorResolver.actor(request), body.filename(), body.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId", upload.id());
        out.put("chunkSize", Uploads.CHUNK_SIZE);
        out.put("size", upload.size());
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
