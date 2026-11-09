package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The room's management view ({@code /rooms/{room}/manage}): runs the unit tests of the settings
 * logic ({@code src/test/resources/web-tests/roomsettings.test.mjs}) against the compiled
 * {@code web/js/roomsettings.js}, and checks that the page uses every API it needs (settings and
 * server switches, members, guest bans, request history and add-to-library) and is linked from the
 * room page.
 */
class RoomManageJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void roomSettingsUnitTestsPass() throws Exception {
        NodeTests.run("roomsettings.test.mjs", Map.of("ROOMSETTINGS_JS", "web/js/roomsettings.js"));
    }

    @Test
    void managementPageUsesTheRoomApis() throws Exception {
        String settings = read("web/js/roomsettings.js");
        for (String s : List.of("document.", "fetch(", "createElement")) {
            assertFalse(settings.contains(s), s);
        }
        String page = read("web/js/roommanage.js");
        for (String s : List.of("from \"./session.js\"", "from \"./roomsettings.js\"", "\"/server-switches\"",
                "\"PATCH\"", "\"/members\"", "\"/members?\"", "\"/guest-bans/\"", "\"/guest-bans?\"",
                "\"/requests?\"", "\"/requests/add-to-library\"", "serverDisabledReason(", "requester", "source")) {
            assertTrue(page.contains(s), s);
        }
        String html = read("web/room-manage.html");
        for (String s : List.of("src=\"/js/roommanage.js\"", "id=\"site-nav\"", "id=\"tab-settings\"",
                "id=\"tab-members\"", "id=\"tab-bans\"", "id=\"tab-requests\"", "id=\"request-source\"",
                "id=\"request-add-selected\"", "id=\"member-add\"")) {
            assertTrue(html.contains(s), s);
        }
        assertTrue(read("web/room.html").contains("id=\"room-manage-link\""));
        assertTrue(read("web/js/room.js").contains("/manage"));
        assertTrue(read("web/css/main.css").contains(".server-disabled"));
    }
}
