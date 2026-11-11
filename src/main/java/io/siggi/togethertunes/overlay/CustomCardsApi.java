package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.rooms.Rooms;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Custom card images ({@code docs/media-types.md#custom-card-images}, {@code docs/api.md#custom-card-images}),
 * all {@code Cache-Control: no-store} except the images, changes audited:
 *
 * <ul>
 *   <li><b>Server-wide</b> ({@code admin.settings}):
 *     <ul>
 *       <li>{@code GET /api/admin/cards} → {@code {"output": {"profile", "width", "height"}, "screens":
 *           [{"screen", "label", "image"?: {"width", "height", "format", "size", "uploadedAt", "url"},
 *           "warnings": [{"code", "message"}]}]}}.</li>
 *       <li>{@code PUT /api/admin/cards/{screen}} (the PNG or JPEG file as the body, at most
 *           {@link CustomCards#MAX_BYTES}) → {@code 200} the screen as above (with its warnings);
 *           {@code 400 invalid_image}, {@code 413}.</li>
 *       <li>{@code DELETE /api/admin/cards/{screen}} → {@code 204}; {@code 404 no_image}.</li>
 *       <li>{@code GET /api/admin/cards/{screen}/image} → the file as uploaded; {@code 404 no_image}.</li>
 *     </ul></li>
 *   <li><b>Per room</b>: reading for the owner and moderators (and {@code rooms.moderateAny}),
 *       changing for the owner holding {@code rooms.customCards}, else {@code 403 forbidden}
 *       ({@code 401 not_authenticated} for guests, {@code 404 room_not_found}):
 *     <ul>
 *       <li>{@code GET /api/rooms/{room}/cards} → as the server's, plus {@code "canEdit"},
 *           {@code "applies"} (the owner holds {@code rooms.customCards}: the room's images replace
 *           the server's) and per screen {@code "server"?: {"width", "height"}} (the server's image,
 *           used where the room has none).</li>
 *       <li>{@code PUT}/{@code DELETE /api/rooms/{room}/cards/{screen}},
 *           {@code GET /api/rooms/{room}/cards/{screen}/image}: as the server's.</li>
 *     </ul></li>
 * </ul>
 *
 * <p>An unknown screen is {@code 404 unknown_screen}. The warnings are computed for the output
 * profile in use when asked, so they follow an encoding change.
 */
public final class CustomCardsApi {
    public static final String AUDIT_SERVER_UPLOADED = "admin.card_uploaded";
    public static final String AUDIT_SERVER_REMOVED = "admin.card_removed";
    public static final String AUDIT_ROOM_UPLOADED = "room.card_uploaded";
    public static final String AUDIT_ROOM_REMOVED = "room.card_removed";

    private final CustomCards cards;
    private final Rooms rooms;
    private final Permissions permissions;
    private final Database db;
    private final Supplier<OutputProfile> profile;
    private final Path tmpDir;
    private final Clock clock;

    /**
     * @param profile the output profile in use
     * @param tmpDir  where an upload is received before it is checked
     */
    public CustomCardsApi(CustomCards cards, Rooms rooms, Permissions permissions, Database db,
            Supplier<OutputProfile> profile, Path tmpDir, Clock clock) {
        this.cards = Objects.requireNonNull(cards);
        this.rooms = Objects.requireNonNull(rooms);
        this.permissions = Objects.requireNonNull(permissions);
        this.db = Objects.requireNonNull(db);
        this.profile = Objects.requireNonNull(profile);
        this.tmpDir = Objects.requireNonNull(tmpDir);
        this.clock = Objects.requireNonNull(clock);
    }

    public void register(Router router) {
        var admin = permissions.hook(Permission.ADMIN_SETTINGS);
        router.get("/api/admin/cards", this::serverList, admin);
        router.put("/api/admin/cards/{screen}", this::serverPut, admin);
        router.delete("/api/admin/cards/{screen}", this::serverDelete, admin);
        router.get("/api/admin/cards/{screen}/image", this::serverImage, admin);
        router.get("/api/rooms/{room}/cards", this::roomList);
        router.put("/api/rooms/{room}/cards/{screen}", this::roomPut);
        router.delete("/api/rooms/{room}/cards/{screen}", this::roomDelete);
        router.get("/api/rooms/{room}/cards/{screen}/image", this::roomImage);
    }

    // ------------------------------------------------------------------ server

    private void serverList(Request request, Response response) throws Exception {
        OutputProfile p = profile.get();
        List<Map<String, Object>> screens = new ArrayList<>();
        for (CustomCards.Screen s : CustomCards.Screen.values()) {
            screens.add(screenJson(s, cards.server(s), "/api/admin/cards/", p));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("output", output(p));
        out.put("screens", screens);
        send(response, 200, out);
    }

    private void serverPut(Request request, Response response) throws Exception {
        CustomCards.Screen screen = screen(request);
        CustomCards.Image img = receive(request, null, screen);
        audit(request, AUDIT_SERVER_UPLOADED, screen.id(), Map.of("screen", screen.id(), "width", img.width(),
                "height", img.height(), "size", img.size()));
        send(response, 200, screenJson(screen, img, "/api/admin/cards/", profile.get()));
    }

    private void serverDelete(Request request, Response response) throws Exception {
        CustomCards.Screen screen = screen(request);
        if (!cards.delete(null, screen)) {
            throw noImage();
        }
        audit(request, AUDIT_SERVER_REMOVED, screen.id(), Map.of("screen", screen.id()));
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void serverImage(Request request, Response response) throws Exception {
        sendImage(request, response, cards.server(screen(request)));
    }

    // ------------------------------------------------------------------ rooms

    /** The room for a reader: the owner and moderators. */
    private Rooms.Access reader(Request request) throws Exception {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        Rooms.Access access = rooms.access(user, request.pathParam("room"));
        if (access.role() == null || !access.role().moderates()) {
            throw HttpError.forbidden("Only the owner and moderators manage a room");
        }
        return access;
    }

    /** Whether the caller may change the room's images: its owner, holding {@code rooms.customCards}. */
    private boolean canEdit(Request request, Rooms.Access access) throws Exception {
        return access.role() == Rooms.Role.OWNER
                && permissions.allowed(ActorResolver.actor(request), Permission.ROOMS_CUSTOM_CARDS);
    }

    private Rooms.Access editor(Request request) throws Exception {
        Rooms.Access access = reader(request);
        if (access.role() != Rooms.Role.OWNER) {
            throw HttpError.forbidden("Only the room's owner changes its card images");
        }
        if (!canEdit(request, access)) {
            throw HttpError.forbidden("Missing permission " + Permission.ROOMS_CUSTOM_CARDS.wireName());
        }
        return access;
    }

    private void roomList(Request request, Response response) throws Exception {
        Rooms.Access access = reader(request);
        String roomId = access.room().id();
        OutputProfile p = profile.get();
        String base = "/api/rooms/" + roomId + "/cards/";
        List<Map<String, Object>> screens = new ArrayList<>();
        for (CustomCards.Screen s : CustomCards.Screen.values()) {
            Map<String, Object> m = screenJson(s, cards.room(roomId, s), base, p);
            CustomCards.Image server = cards.server(s);
            if (server != null) {
                m.put("server", Map.of("width", server.width(), "height", server.height()));
            }
            screens.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("output", output(p));
        out.put("canEdit", canEdit(request, access));
        out.put("applies", cards.roomImagesApply(roomId));
        out.put("screens", screens);
        send(response, 200, out);
    }

    private void roomPut(Request request, Response response) throws Exception {
        Rooms.Access access = editor(request);
        CustomCards.Screen screen = screen(request);
        String roomId = access.room().id();
        CustomCards.Image img = receive(request, roomId, screen);
        audit(request, AUDIT_ROOM_UPLOADED, roomId, Map.of("screen", screen.id(), "width", img.width(),
                "height", img.height(), "size", img.size()));
        send(response, 200, screenJson(screen, img, "/api/rooms/" + roomId + "/cards/", profile.get()));
    }

    private void roomDelete(Request request, Response response) throws Exception {
        Rooms.Access access = editor(request);
        CustomCards.Screen screen = screen(request);
        String roomId = access.room().id();
        if (!cards.delete(roomId, screen)) {
            throw noImage();
        }
        audit(request, AUDIT_ROOM_REMOVED, roomId, Map.of("screen", screen.id()));
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void roomImage(Request request, Response response) throws Exception {
        Rooms.Access access = reader(request);
        sendImage(request, response, cards.room(access.room().id(), screen(request)));
    }

    // ------------------------------------------------------------------ shared

    private static CustomCards.Screen screen(Request request) {
        CustomCards.Screen s = CustomCards.Screen.byId(request.pathParam("screen"));
        if (s == null) {
            throw new HttpError(404, "unknown_screen", "No such screen (paused, idle, loading or gap)");
        }
        return s;
    }

    private static HttpError noImage() {
        return new HttpError(404, "no_image", "There is no custom image for this screen");
    }

    private CustomCards.Image receive(Request request, String roomId, CustomCards.Screen screen) throws Exception {
        Files.createDirectories(tmpDir);
        Path tmp = Files.createTempFile(tmpDir, "card-upload-", ".part");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                request.copyBody(out, CustomCards.MAX_BYTES);
            }
            if (Files.size(tmp) == 0) {
                throw new HttpError(400, "invalid_image", "Send the PNG or JPEG image as the request body");
            }
            try {
                return cards.put(roomId, screen, tmp);
            } catch (CustomCards.InvalidImageException e) {
                throw new HttpError(400, "invalid_image", e.getMessage());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Map<String, Object> output(OutputProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", p.id());
        m.put("width", p.width());
        m.put("height", p.height());
        return m;
    }

    private static Map<String, Object> screenJson(CustomCards.Screen s, CustomCards.Image img, String base,
            OutputProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("screen", s.id());
        m.put("label", s.label());
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (img != null) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("width", img.width());
            i.put("height", img.height());
            i.put("format", img.format());
            i.put("size", img.size());
            i.put("uploadedAt", img.uploadedAt());
            i.put("url", base + s.id() + "/image?v=" + img.hash());
            m.put("image", i);
            for (CustomCards.Warning w : CustomCards.warnings(img.width(), img.height(), p)) {
                warnings.add(Map.of("code", w.code(), "message", w.message()));
            }
        }
        m.put("warnings", warnings);
        return m;
    }

    private static void sendImage(Request request, Response response, CustomCards.Image img) throws IOException {
        String method = request.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            throw new HttpError(405, "method_not_allowed", "Method " + method + " is not allowed here");
        }
        if (img == null || !Files.isRegularFile(img.file())) {
            throw noImage();
        }
        response.header("Cache-Control", "private, no-cache");
        response.header("X-Content-Type-Options", "nosniff");
        response.raw().returnFile(img.file().toFile(), img.contentType());
    }

    private void audit(Request request, String action, String target, Map<String, ?> details) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // only users get this far
        db.write(c -> {
            AuditLog.write(c, EpochMs.now(clock), user.userId(), null, request.clientIp(), action, target, details);
            return null;
        });
    }

    private static void send(Response response, int status, Object body) throws IOException {
        response.header("Cache-Control", "no-store");
        response.json(status, body);
    }
}
