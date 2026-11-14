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
 * server switches, members, guest bans) and is linked from the room page. Also the room's history
 * ({@code history.test.mjs} against {@code web/js/history.js}) and its wiring on the room page (the
 * request history is gone).
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
    void historyUnitTestsPass() throws Exception {
        NodeTests.run("history.test.mjs", Map.of("HISTORY_JS", "web/js/history.js"));
    }

    @Test
    void roomPageShowsTheHistory() throws Exception {
        String history = read("web/js/history.js");
        for (String s : List.of("document.", "fetch(", "createElement")) {
            assertFalse(history.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("from \"./history.js\"", "\"/history?\"", "historyEntryId: r.entryId",
                "\"/api/library/\"", "libraryAction(", "queueAction(", "banGuestConfirmed(", "\"Show more\"")) {
            assertTrue(room.contains(s), s);
        }
    }

    @Test
    void managementPageUsesTheRoomApis() throws Exception {
        String settings = read("web/js/roomsettings.js");
        for (String s : List.of("document.", "fetch(", "createElement")) {
            assertFalse(settings.contains(s), s);
        }
        String page = read("web/js/roommanage.js");
        for (String s : List.of("from \"./session.js\"", "from \"./roomsettings.js\"", "\"/server-switches\"",
                "\"PATCH\"", "\"/members\"", "\"/members?\"", "\"/bans?\"", "\"/bans/\"",
                "\"guests/\"", "\"users/\"", "\"Unban\"", "new OutputsPanel(", "\"Leave the room\"",
                "serverDisabledReason(")) {
            assertTrue(page.contains(s), s);
        }
        assertFalse(page.contains("/requests"), "the request history is gone");
        assertTrue(read("web/js/guestban.js").contains("/guest-bans/"));
        String html = NodeTests.view("view-room-manage");
        for (String s : List.of("id=\"tab-settings\"",
                "id=\"tab-members\"", "id=\"tab-bans\"", "id=\"tab-outputs\"", "id=\"outputs-root\"",
                "id=\"member-add\"")) {
            assertTrue(html.contains(s), s);
        }
        assertFalse(html.contains("tab-requests"), html);
        assertTrue(NodeTests.view("view-room").contains("id=\"room-manage-link\""));
        assertTrue(read("web/js/room.js").contains("/manage"));
        assertTrue(read("web/css/main.css").contains(".server-disabled"));
    }
}
