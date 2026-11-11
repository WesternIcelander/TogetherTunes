package io.siggi.togethertunes.overlay;

import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.rooms.RoomSettings;
import io.siggi.togethertunes.rooms.Rooms;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * The overlay preview of the room management view ({@code docs/overlay.md#room-ui}).
 *
 * <ul>
 *   <li>{@code GET /api/rooms/{room}/overlay/preview.png?corner=&heightPct=&margin=} → a PNG at the
 *       output size: the room's idle card with the QR overlay drawn where the stream would show it
 *       ({@link Overlays#preview}). Each parameter is optional and replaces the room's saved
 *       {@code overlay.*} setting of that name, so the form can show unsaved values; the QR code
 *       always uses the saved link name. Values are validated like the room settings:
 *       {@code 400 invalid_setting}. Users only ({@code 401 not_authenticated}); the owner and
 *       moderators ({@code rooms.moderateAny} too), else {@code 403 forbidden}; {@code 404
 *       room_not_found}. The card is the room's custom queue-empty image when it has one
 *       ({@code docs/media-types.md#custom-card-images}). It renders whatever the room's {@code overlay.mode} (nothing is stored), so
 *       the owner can place the overlay before turning it on; the queue-empty card it shows is the
 *       one card that carries the overlay in both {@code always} and {@code idleOnly}
 *       ({@code docs/media-types.md#qr-overlay-modes}). {@code no-store}.</li>
 * </ul>
 */
public final class OverlayPreviewApi {
    private final Rooms rooms;
    private final Overlays overlays;

    private final CustomCards customCards;

    public OverlayPreviewApi(Rooms rooms, Overlays overlays) {
        this(rooms, overlays, null);
    }

    /** @param customCards the custom card images (the preview shows the room's queue-empty image); null: none */
    public OverlayPreviewApi(Rooms rooms, Overlays overlays, CustomCards customCards) {
        this.rooms = Objects.requireNonNull(rooms);
        this.overlays = Objects.requireNonNull(overlays);
        this.customCards = customCards;
    }

    public void register(Router router) {
        router.get("/api/rooms/{room}/overlay/preview.png", this::preview);
    }

    private void preview(Request request, Response response) throws Exception {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        Rooms.Access access = rooms.access(user, request.pathParam("room"));
        if (access.role() == null || !access.role().moderates()) {
            throw HttpError.forbidden("Only the owner and moderators manage a room");
        }
        JsonObject overlay = new JsonObject();
        String corner = request.query("corner");
        if (corner != null && !corner.isEmpty()) {
            overlay.addProperty("corner", corner);
        }
        for (String name : new String[] {"heightPct", "margin"}) {
            String raw = request.query(name);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            try {
                overlay.addProperty(name, Integer.parseInt(raw));
            } catch (NumberFormatException e) {
                throw new HttpError(400, "invalid_setting", "overlay." + name + " must be a whole number");
            }
        }
        JsonObject change = new JsonObject();
        change.add("overlay", overlay);
        RoomSettings settings = access.room().settings().patch(change);
        CustomCards.Image idle = customCards == null ? null
                : customCards.effective(access.room().id()).get(io.siggi.togethertunes.output.FillerSource.Filler.IDLE);
        BufferedImage image = overlays.preview(access.room().slug(), access.room().name(), settings, idle);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", png)) {
            throw new IllegalStateException("no PNG writer");
        }
        response.header("Cache-Control", "no-store");
        response.status(200);
        response.raw().setContentType("image/png");
        response.raw().send(png.toByteArray());
    }
}
