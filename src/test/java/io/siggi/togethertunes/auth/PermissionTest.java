package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.settings.Settings;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionTest {
    @Test
    void theFullListFromTheDocs() {
        // docs/auth.md#roles-and-permissions
        assertEquals(List.of("admin.settings", "admin.users", "admin.roles", "admin.providers", "admin.jobs",
                        "ip.view",
                        "library.upload", "library.youtube", "library.viewAll", "library.editAny", "library.purge",
                        "rooms.create", "rooms.moderateAny", "output.overlayVideo"),
                Arrays.stream(Permission.values()).map(Permission::wireName).toList());
    }

    @Test
    void wireNamesRoundTripExactly() {
        for (Permission p : Permission.values()) {
            assertEquals(Optional.of(p), Permission.fromWireName(p.wireName()));
            assertEquals(p.wireName(), p.toString());
            assertTrue(!p.description().isBlank());
        }
        assertEquals(Optional.empty(), Permission.fromWireName("IP.VIEW"));
        assertEquals(Optional.empty(), Permission.fromWireName("ADMIN_SETTINGS"));
    }

    @Test
    void featuresOfPermissions() {
        assertEquals(Feature.YOUTUBE, Permission.LIBRARY_YOUTUBE.feature());
        assertEquals(Feature.OVERLAY, Permission.OUTPUT_OVERLAY_VIDEO.feature());
        assertNull(Permission.LIBRARY_UPLOAD.feature());
        assertNull(Permission.ADMIN_SETTINGS.feature());
    }

    @Test
    void featureSwitchesAreServerSettings() {
        assertEquals(Settings.YTDLP_ENABLED, Feature.YOUTUBE.setting());
        assertEquals(Settings.OUTPUTS_RTMP_ENABLED, Feature.OUTPUT_RTMP.setting());
        assertEquals(Settings.OUTPUTS_TS_ENABLED, Feature.OUTPUT_TS.setting());
        assertEquals(Settings.OUTPUTS_HLS_ENABLED, Feature.OUTPUT_HLS.setting());
        assertEquals(Settings.OUTPUTS_MP3_ENABLED, Feature.OUTPUT_MP3.setting());
        assertEquals(Settings.OVERLAY_ENABLED, Feature.OVERLAY.setting());
        for (Feature f : Feature.values()) {
            assertEquals(f.setting(), Settings.KEYS.get(f.setting().name()));
        }
    }
}
