package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.rooms.RoomSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlaysTest {
    private static final String ROOM = "01a1c2fe-121a-74ee-ae2c-143f98971e63";

    @TempDir
    Path rooms;

    private final AtomicReference<OutputProfile> profile = new AtomicReference<>(OutputProfile.P720_30);

    private Overlays overlays(String base) {
        return new Overlays(rooms, base, profile::get);
    }

    @Test
    void rendersOnceAndReusesTheStoredImageAcrossRestarts() throws Exception {
        RoomSettings s = OverlayRendererTest.settings("br", 22, 3);
        Overlays a = overlays("https://a.example.com");
        OverlayRenderer.Rendered first = a.current(ROOM, "chill", s);
        Path png = rooms.resolve(ROOM).resolve(Overlays.PNG);
        assertTrue(Files.isRegularFile(png));
        assertTrue(Files.isRegularFile(rooms.resolve(ROOM).resolve(Overlays.META)));
        assertSame(first, a.current(ROOM, "chill", s));
        assertEquals(png, a.png(ROOM));

        // A restart with the same spec reads the stored image (not rewritten) and gets the same hash.
        Files.setLastModifiedTime(png, FileTime.fromMillis(1_000_000));
        OverlayRenderer.Rendered again = overlays("https://a.example.com").current(ROOM, "chill", s);
        assertEquals(first.hash(), again.hash());
        assertEquals(first.x(), again.x());
        assertEquals(first.y(), again.y());
        assertEquals(1_000_000, Files.getLastModifiedTime(png).toMillis());
        int w = first.width(), h = first.height();
        assertArrayEquals(first.image().getRGB(0, 0, w, h, null, 0, w), again.image().getRGB(0, 0, w, h, null, 0, w));
    }

    @Test
    void regeneratesOnSlugSettingsBaseUrlAndProfileChanges() throws Exception {
        RoomSettings s = OverlayRendererTest.settings("br", 22, 3);
        Overlays o = overlays("https://a.example.com");
        String h0 = o.current(ROOM, "chill", s).hash();
        String h1 = o.current(ROOM, "chill-2", s).hash();
        assertNotEquals(h0, h1);
        String h2 = o.current(ROOM, "chill-2", OverlayRendererTest.settings("tl", 22, 3)).hash();
        assertNotEquals(h1, h2);
        assertEquals(h2, OverlayRenderer.hash(javax.imageio.ImageIO.read(o.png(ROOM).toFile()),
                o.current(ROOM, "chill-2", OverlayRendererTest.settings("tl", 22, 3)).x(),
                o.current(ROOM, "chill-2", OverlayRendererTest.settings("tl", 22, 3)).y(), 1280, 720),
                "the stored file is the current render");
        profile.set(OutputProfile.P1080_30);
        OverlayRenderer.Rendered hd = o.current(ROOM, "chill-2", OverlayRendererTest.settings("tl", 22, 3));
        assertNotEquals(h2, hd.hash());
        assertEquals(1920, hd.spec().frameWidth());

        // A new public URL after a restart: the stored image doesn't match and is redone.
        Overlays moved = overlays("https://b.example.com");
        OverlayRenderer.Rendered b = moved.current(ROOM, "chill-2", OverlayRendererTest.settings("tl", 22, 3));
        assertNotEquals(hd.hash(), b.hash());
        assertEquals("https://b.example.com/r/chill-2", b.spec().url());
        String meta = Files.readString(rooms.resolve(ROOM).resolve(Overlays.META));
        assertTrue(meta.contains("b.example.com") && meta.contains(b.hash()), meta);
    }

    @Test
    void roomEditedRendersOnlyWhileTheOverlayIsOn() throws Exception {
        Overlays o = overlays("https://a.example.com");
        RoomSettings off = RoomSettings.DEFAULTS;
        RoomSettings on = OverlayRendererTest.settings("bl", 22, 3);
        o.roomEdited(ROOM, "chill", off, true);
        assertFalse(Files.exists(o.png(ROOM)), "room switch off");
        o.roomEdited(ROOM, "chill", on, false);
        assertFalse(Files.exists(o.png(ROOM)), "server switch off");
        o.roomEdited(ROOM, "chill", on, true);
        assertTrue(Files.exists(o.png(ROOM)));
        String meta = Files.readString(rooms.resolve(ROOM).resolve(Overlays.META));
        o.roomEdited(ROOM, "renamed", on, true);
        assertNotEquals(meta, Files.readString(rooms.resolve(ROOM).resolve(Overlays.META)));
        assertTrue(Files.readString(rooms.resolve(ROOM).resolve(Overlays.META)).contains("/r/renamed"));
    }

    @Test
    void unreadableStoredFilesAreReRendered() throws Exception {
        RoomSettings s = OverlayRendererTest.settings("br", 22, 3);
        String hash = overlays("https://a.example.com").current(ROOM, "chill", s).hash();
        Files.writeString(rooms.resolve(ROOM).resolve(Overlays.PNG), "not a png");
        assertEquals(hash, overlays("https://a.example.com").current(ROOM, "chill", s).hash());
        Files.writeString(rooms.resolve(ROOM).resolve(Overlays.META), "{");
        assertEquals(hash, overlays("https://a.example.com").current(ROOM, "chill", s).hash());
        assertThrows(IllegalArgumentException.class, () -> overlays("https://a.example.com").png("../x"));
    }
}
