package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Linked media ({@code docs/library-and-ingest.md#linked-media}): an item's music videos, lyric
 * videos and external links, table {@code item_links}.
 *
 * <p>A video link points to another item, which every viewer may see (items are visible by direct
 * link), so its summary is the same for everyone: id, title, artist, media type, status, and its
 * cover URL only while it is ready and its current version has a cover (never more than its own
 * item page shows). External links carry their URL and optional label.
 *
 * <p>Saving ({@code PUT /api/items/{id}/links}) replaces the whole list; the caller checks who may
 * edit. Validation ({@code 400 invalid_link} with {@code reason} and {@code index}):
 * {@code bad_type}, {@code not_an_item} (a video link without an item ID, or with a URL),
 * {@code bad_url} (an external link without an {@code http(s)} URL, or with an item ID),
 * {@code bad_label}, {@code self_link}, {@code duplicate} (the same target or URL twice),
 * {@code too_many} (over {@value #MAX_LINKS}). An unknown target is {@code 404 item_not_found}.
 */
public final class ItemLinks {
    /** The link types, as stored and in the API. */
    public static final List<String> TYPES = List.of("music_video", "lyric_video", "external");
    /** Most links one item may have. */
    public static final int MAX_LINKS = 20;
    /** Longest external URL. */
    public static final int MAX_URL = 2000;
    /** Longest label (characters). */
    public static final int MAX_LABEL = 100;

    private ItemLinks() {
    }

    /**
     * A linked item as every viewer may see it.
     *
     * @param coverUrl only while it is ready and its current version has a cover, else null
     */
    public record Target(String id, String title, String artist, String coverUrl, String status, String mediaType) {
    }

    /**
     * One link of an item: {@code item} for a video type, {@code url} (and {@code label}, may be
     * null) for {@code external}.
     */
    public record Link(String id, String type, Target item, String url, String label) {
    }

    /** One link as saved ({@code PUT}): {@code itemId} for a video type, {@code url}/{@code label} for external. */
    public record NewLink(String type, String itemId, String url, String label) {
    }

    /** {@code 400 invalid_link}, with {@code reason} and the link's {@code index} (when it is about one). */
    public static final class InvalidLink extends HttpError {
        private static final long serialVersionUID = 1L;
        private final String reason;
        private final Integer index;

        InvalidLink(String reason, Integer index, String message) {
            super(400, "invalid_link", message);
            this.reason = reason;
            this.index = index;
        }

        public String reason() {
            return reason;
        }

        @Override
        public Map<String, Object> extra() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("reason", reason);
            if (index != null) {
                m.put("index", index);
            }
            return m;
        }
    }

    /** The item's links in order. */
    public static List<Link> list(Connection c, String itemId, Covers covers) throws SQLException {
        List<Link> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT l.id, l.kind, l.url, l.label, t.id, t.title, t.artist,"
                + " t.status, t.media_version, t.kind, t.media_type FROM item_links l"
                + " LEFT JOIN items t ON t.id = l.target_item_id WHERE l.item_id = ? ORDER BY l.position, l.id")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(2);
                    if ("external".equals(kind)) {
                        out.add(new Link(rs.getString(1), kind, null, rs.getString(3), rs.getString(4)));
                        continue;
                    }
                    String targetId = rs.getString(5);
                    if (targetId == null) {
                        continue; // can't happen (deletes remove both sides)
                    }
                    String status = rs.getString(8);
                    int version = rs.getInt(9);
                    String coverUrl = "ready".equals(status) && covers.exists(targetId, version)
                            ? MediaApi.url(targetId, version, "cover.jpg") : null;
                    out.add(new Link(rs.getString(1), kind, new Target(targetId, rs.getString(6), rs.getString(7),
                            coverUrl, status, MediaType.effective(rs.getString(10), rs.getString(11))), null, null));
                }
            }
        }
        return out;
    }

    /**
     * Parses the body of {@code PUT /api/items/{id}/links}: {@code {"links": [{"type", "itemId"?,
     * "url"?, "label"?}]}}. Checks the shape, types, URLs, labels, duplicates and the count; the
     * targets' existence and self links are {@link #replace}'s.
     */
    public static List<NewLink> parse(JsonObject body) {
        if (body == null || !(body.get("links") instanceof JsonArray array)) {
            throw new InvalidLink("bad_body", null, "Expected {\"links\": [...]}");
        }
        for (String key : body.keySet()) {
            if (!key.equals("links")) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
        if (array.size() > MAX_LINKS) {
            throw new InvalidLink("too_many", null, "At most " + MAX_LINKS + " linked media per item");
        }
        List<NewLink> out = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            if (!(array.get(i) instanceof JsonObject o)) {
                throw new InvalidLink("bad_type", i, "Each link is an object with a type");
            }
            for (String key : o.keySet()) {
                if (!Set.of("type", "itemId", "url", "label").contains(key)) {
                    throw new HttpError(400, "unknown_field", "Unknown field: " + key);
                }
            }
            String type = string(o.get("type"));
            if (type == null || !TYPES.contains(type)) {
                throw new InvalidLink("bad_type", i, "The type must be Music Video, Lyric Video or External link");
            }
            String itemId = string(o.get("itemId"));
            String url = string(o.get("url"));
            JsonElement labelEl = o.get("label");
            if (type.equals("external")) {
                if (has(o, "itemId")) {
                    throw new InvalidLink("bad_url", i, "An external link has a URL, not an item");
                }
                url = url(url);
                if (url == null) {
                    throw new InvalidLink("bad_url", i, "An external link needs an http:// or https:// URL");
                }
                String label = null;
                if (labelEl != null && !labelEl.isJsonNull()) {
                    if (string(labelEl) == null) {
                        throw new InvalidLink("bad_label", i, "The label must be text");
                    }
                    label = UploadMetadata.clean(string(labelEl));
                    if (label != null && label.isEmpty()) {
                        label = null;
                    }
                    if (label != null && label.codePointCount(0, label.length()) > MAX_LABEL) {
                        throw new InvalidLink("bad_label", i, "The label can be at most " + MAX_LABEL + " characters");
                    }
                }
                if (!urls.add(url)) {
                    throw new InvalidLink("duplicate", i, "This link is already in the list");
                }
                out.add(new NewLink(type, null, url, label));
            } else {
                if (has(o, "url") || has(o, "label")) {
                    throw new InvalidLink("not_an_item", i, "A video link points to an item, not a URL");
                }
                if (itemId == null || itemId.isEmpty() || itemId.length() > 64) {
                    throw new InvalidLink("not_an_item", i, "A video link needs the item it points to");
                }
                if (!targets.add(itemId)) {
                    throw new InvalidLink("duplicate", i, "This item is already linked");
                }
                out.add(new NewLink(type, itemId, null, null));
            }
        }
        return out;
    }

    private static boolean has(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }

    private static String string(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** {@code s} as a stored external URL (trimmed; http or https with a host), or null if it isn't one. */
    static String url(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty() || s.length() > MAX_URL) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch <= 0x20 || ch == 0x7f) {
                return null;
            }
        }
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty() || uri.getRawUserInfo() != null) {
                return null;
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return s;
    }

    /**
     * Replaces the item's links with {@code links} (already {@link #parse parsed}), in their order.
     *
     * @throws HttpError {@code 400 invalid_link} ({@code self_link}), {@code 404 item_not_found}
     *                   (a target that doesn't exist)
     */
    public static void replace(Connection c, String itemId, List<NewLink> links, String userId, long now)
            throws SQLException {
        for (int i = 0; i < links.size(); i++) {
            NewLink l = links.get(i);
            if (l.itemId() == null) {
                continue;
            }
            if (l.itemId().equals(itemId)) {
                throw new InvalidLink("self_link", i, "An item can't link to itself");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM items WHERE id = ?")) {
                ps.setString(1, l.itemId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new HttpError(404, "item_not_found", "The linked item doesn't exist");
                    }
                }
            }
        }
        // a link that stays (same type and target or URL) keeps its id, adder and time
        Map<String, Object[]> kept = new java.util.HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT kind, target_item_id, url, id, added_by, added_at"
                + " FROM item_links WHERE item_id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    kept.put(key(rs.getString(1), rs.getString(2), rs.getString(3)),
                            new Object[] {rs.getString(4), rs.getString(5), rs.getLong(6)});
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM item_links WHERE item_id = ?")) {
            ps.setString(1, itemId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO item_links (id, item_id, position, kind,"
                + " target_item_id, url, label, added_by, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < links.size(); i++) {
                NewLink l = links.get(i);
                Object[] old = kept.get(key(l.type(), l.itemId(), l.url()));
                ps.setString(2, itemId);
                ps.setInt(3, i);
                ps.setString(4, l.type());
                ps.setString(5, l.itemId());
                ps.setString(6, l.url());
                ps.setString(7, l.label());
                ps.setString(1, old != null ? (String) old[0] : UuidV7.newId());
                ps.setString(8, old != null ? (String) old[1] : userId);
                ps.setLong(9, old != null ? (Long) old[2] : now);
                ps.executeUpdate();
            }
        }
    }

    private static String key(String kind, String target, String url) {
        return kind + "\u0000" + (target != null ? "i:" + target : "u:" + url);
    }

    /** Removes every link from or to the item (its delete record). */
    static void removeForItem(Connection c, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM item_links WHERE item_id = ? OR target_item_id = ?")) {
            ps.setString(1, itemId);
            ps.setString(2, itemId);
            ps.executeUpdate();
        }
    }
}
