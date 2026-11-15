package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The listener's volume and the preview duck ({@code docs/ui.md} "Item page"): runs
 * {@code src/test/resources/web-tests/volume.test.mjs} against the compiled {@code web/js/volume.js},
 * and checks that the frame publishes the volume, the room player listens to the duck, and the item
 * page's player follows the one and sets the other.
 */
class VolumeJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void volumeUnitTestsPass() throws Exception {
        NodeTests.run("volume.test.mjs", Map.of("VOLUME_JS", "web/js/volume.js"));
    }

    @Test
    void volumeIsPureLogic() throws Exception {
        String volume = read("web/js/volume.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "localStorage", "fetch(")) {
            assertFalse(volume.contains(s), s);
        }
    }

    @Test
    void framePublishesAndPagesListen() throws Exception {
        assertTrue(read("web/js/frame.js").contains("listenerVolume.set("), "frame publishes the volume");
        String room = read("web/js/room.js");
        assertTrue(room.contains("previewDuck.subscribe(") && room.contains("setDucked("), "room player ducks");
        String item = read("web/js/item.js");
        for (String s : List.of("listenerVolume.subscribe(", "previewDuck.set(", "DUCK_ON_EVENTS", "DUCK_OFF_EVENTS")) {
            assertTrue(item.contains(s), s);
        }
        assertFalse(item.contains("localStorage"), "the item page doesn't read the stored volume");
    }
}
