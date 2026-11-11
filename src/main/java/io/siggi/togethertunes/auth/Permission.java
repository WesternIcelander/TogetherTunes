package io.siggi.togethertunes.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every permission a role can hold ({@code docs/auth.md#roles-and-permissions}). Stored in
 * {@code role_permissions.permission} by {@link #wireName()}; the built-in {@code admin} role
 * holds all of them without rows (so a permission added later is the admin's at once).
 *
 * <p>Some permissions belong to a server-wide {@link Feature}: while that feature is switched off,
 * the permission grants nothing, to anyone ({@link Permissions#require}).
 */
public enum Permission {
    ADMIN_SETTINGS("admin.settings", null, "Change server settings"),
    ADMIN_USERS("admin.users", null, "Manage user accounts"),
    ADMIN_ROLES("admin.roles", null, "Manage roles and their permissions"),
    ADMIN_PROVIDERS("admin.providers", null, "Manage OIDC login providers"),
    ADMIN_JOBS("admin.jobs", null, "See every encode job with its log; retry failed jobs and cancel queued ones"),
    IP_VIEW("ip.view", null, "See IP addresses anywhere: guests, sessions, audit log, room member lists"),
    LIBRARY_UPLOAD("library.upload", null, "Upload files"),
    LIBRARY_YOUTUBE("library.youtube", Feature.YOUTUBE, "Import from YouTube"),
    LIBRARY_VIEW_ALL("library.viewAll", null, "Browse and search other users' libraries and playlists"),
    LIBRARY_EDIT_ANY("library.editAny", null, "Edit the metadata of any item"),
    LIBRARY_PURGE("library.purge", null, "Delete any item from the server immediately"),
    LIBRARY_PIN("library.pin", null, "Pin items so their media is never deleted automatically"),
    ROOMS_CREATE("rooms.create", null, "Create rooms"),
    ROOMS_MODERATE_ANY("rooms.moderateAny", null, "Act as moderator in any room"),
    ROOMS_CUSTOM_CARDS("rooms.customCards", null, "Upload custom card images for rooms this user owns"),
    OUTPUT_OVERLAY_VIDEO("output.overlayVideo", Feature.OVERLAY,
            "Rooms owned by this user may re-encode music videos to add the QR overlay");

    private static final Map<String, Permission> BY_WIRE_NAME;

    static {
        Map<String, Permission> m = new LinkedHashMap<>();
        for (Permission p : values()) {
            m.put(p.wireName, p);
        }
        BY_WIRE_NAME = Collections.unmodifiableMap(m);
    }

    private final String wireName;
    private final Feature feature;
    private final String description;

    Permission(String wireName, Feature feature, String description) {
        this.wireName = wireName;
        this.feature = feature;
        this.description = description;
    }

    /** The name used in the database and the API, e.g. {@code library.upload}. */
    public String wireName() {
        return wireName;
    }

    /** The feature switch this permission depends on, or {@code null} if none. */
    public Feature feature() {
        return feature;
    }

    /** A short English description for the admin UI. */
    public String description() {
        return description;
    }

    /** The permission with this wire name (exact, case-sensitive). */
    public static Optional<Permission> fromWireName(String name) {
        return Optional.ofNullable(BY_WIRE_NAME.get(name));
    }

    /** Every permission (a fresh, mutable set). */
    public static Set<Permission> all() {
        return EnumSet.allOf(Permission.class);
    }

    @Override
    public String toString() {
        return wireName;
    }
}
