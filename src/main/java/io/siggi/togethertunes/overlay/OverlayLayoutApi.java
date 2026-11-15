package io.siggi.togethertunes.overlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.rooms.Rooms;
import java.awt.Color;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/**
 * The web player's copy of the room's QR overlay ({@code docs/overlay.md#the-web-players-copy},
 * {@code docs/ui.md} "Show QR code").
 *
 * <ul>
 *   <li>{@code GET /api/rooms/{room}/overlay/layout} → what the stream's overlay draws, in
 *       output-frame coordinates, from {@link OverlayRenderer#layout} (the code the stream's overlay
 *       image is drawn from): the frame size, the QR code's module matrix (rows of {@code 0}/{@code 1},
 *       quiet zone included, {@code 1} = drawn white), its square and corner radius, the text with its
 *       font size, baseline and backdrop, the colours, and the room's {@code overlay.mode} and the
 *       server's {@code overlay.enabled}. {@code available} says whether the web player offers it at
 *       all (the server's switch on, the mode not {@code off}, not an alone room). The QR code's URL is
 *       the room's short link {@code {publicBaseUrl}/r/{slug}}, the same the stream shows (never a
 *       stream token). Anyone who may see the room (as {@code GET /api/rooms/{room}}); banned
 *       people see it read-only there too; {@code 404 room_not_found}. {@code version} is
 *       {@link OverlayRenderer#RENDER_VERSION}. {@code no-store}; the page fetches it again when the
 *       room's slug or overlay settings change (they arrive in the room state).</li>
 * </ul>
 */
public final class OverlayLayoutApi {
    /** The stream's overlay font: the bundled DejaVu Sans ({@code CardFonts}), bold, unless the admin set a card font. */
    static final String FONT = "DejaVu Sans";

    /** Whether the server's {@code overlay.enabled} is on. */
    @FunctionalInterface
    public interface ServerSwitch {
        boolean enabled() throws SQLException;
    }

    private final Rooms rooms;
    private final Overlays overlays;
    private final ServerSwitch serverSwitch;

    public OverlayLayoutApi(Rooms rooms, Overlays overlays, ServerSwitch serverSwitch) {
        this.rooms = Objects.requireNonNull(rooms);
        this.overlays = Objects.requireNonNull(overlays);
        this.serverSwitch = Objects.requireNonNull(serverSwitch);
    }

    public void register(Router router) {
        router.get("/api/rooms/{room}/overlay/layout", this::layout);
    }

    private void layout(Request request, Response response) throws Exception {
        Rooms.Access access = rooms.access(ActorResolver.actor(request), request.pathParam("room"),
                request.clientIp());
        Rooms.Room room = access.room();
        OverlayRenderer.Layout l = OverlayRenderer.layout(overlays.spec(room.slug(), room.settings()));
        JsonObject body = json(l, room.settings().overlayMode().wire(), serverSwitch.enabled(), room.alone());
        response.header("Cache-Control", "no-store");
        response.json(body);
    }

    /** The response body for a layout. */
    static JsonObject json(OverlayRenderer.Layout l, String mode, boolean serverEnabled, boolean alone) {
        OverlayRenderer.Spec spec = l.spec();
        JsonObject o = new JsonObject();
        o.addProperty("version", OverlayRenderer.RENDER_VERSION);
        o.addProperty("mode", mode);
        o.addProperty("enabled", serverEnabled);
        o.addProperty("available", serverEnabled && !"off".equals(mode) && !alone);
        JsonObject frame = new JsonObject();
        frame.addProperty("width", spec.frameWidth());
        frame.addProperty("height", spec.frameHeight());
        o.add("frame", frame);
        o.addProperty("corner", spec.corner());
        o.addProperty("url", spec.url());
        o.add("bounds", rect(l.x(), l.y(), l.width(), l.height()));

        JsonObject qr = rect(l.x() + l.qrX(), l.y() + l.qrY(), l.qrSide(), l.qrSide());
        qr.addProperty("radius", l.qrRadius());
        qr.addProperty("modules", l.modules());
        qr.addProperty("modulePx", l.modulePx());
        qr.addProperty("quietZone", OverlayRenderer.QUIET_ZONE);
        JsonArray rows = new JsonArray();
        StringBuilder row = new StringBuilder(l.modules());
        for (int y = 0; y < l.modules(); y++) {
            row.setLength(0);
            for (int x = 0; x < l.modules(); x++) {
                row.append(l.matrix().get(x, y) ? '1' : '0');
            }
            rows.add(row.toString());
        }
        qr.add("rows", rows);
        o.add("qr", qr);

        JsonObject text = new JsonObject();
        text.addProperty("value", spec.text());
        text.addProperty("font", FONT);
        text.addProperty("bold", true);
        text.addProperty("size", l.fontSize());
        text.addProperty("x", l.x() + l.textX());
        text.addProperty("baseline", l.y() + l.baseline());
        text.addProperty("advance", l.advance());
        JsonObject backdrop = rect(l.x() + l.backdropX(), l.y() + l.backdropY(), l.backdropW(), l.backdropH());
        backdrop.addProperty("radius", l.backdropRadius());
        text.add("backdrop", backdrop);
        o.add("text", text);

        JsonObject colors = new JsonObject();
        colors.addProperty("backdrop", css(OverlayRenderer.BACKDROP));
        colors.addProperty("modules", css(OverlayRenderer.TEXT));
        colors.addProperty("text", css(OverlayRenderer.TEXT));
        o.add("colors", colors);
        return o;
    }

    private static JsonObject rect(double x, double y, double w, double h) {
        JsonObject r = new JsonObject();
        r.addProperty("x", x);
        r.addProperty("y", y);
        r.addProperty("width", w);
        r.addProperty("height", h);
        return r;
    }

    /** {@code rgba(r, g, b, a)} with the alpha as a fraction (4 decimals). */
    static String css(Color c) {
        return String.format(Locale.ROOT, "rgba(%d, %d, %d, %s)", c.getRed(), c.getGreen(), c.getBlue(),
                stripZeros(c.getAlpha() / 255.0));
    }

    private static String stripZeros(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
