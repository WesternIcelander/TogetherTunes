package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.retention.References;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Sharing items between accounts ({@code docs/libraries.md#sharing}). A user shares an item with
 * another (enabled) account, picked by username, with an optional message. The share adds the
 * item to the recipient's library right away ({@code via='share'}, {@code shared_by_user_id} =
 * the sender); if it is already there, that entry is left as it is. Every share is a row in
 * {@code shares}, which is also the recipient's notification: {@code seen = 0} until the
 * recipient marks it seen.
 *
 * <p>Privacy: the recipient's library is never shown to the sender. The sender's outgoing list
 * shows the item, the recipient and the message, but not whether the recipient has seen it or
 * still has the item ({@link Share#seen} is {@code null} there).
 *
 * <p>Item rules mirror {@link Library#add}: a purged or failed upload can't be shared
 * ({@code 409 media_expired} / {@code item_failed}); a purged or failed YouTube item is fetched
 * again ({@link YoutubeImports#useExisting}, after the sender's {@code library.youtube} check).
 * Sharing references the item ({@code last_referenced_at} = now). A sender may create at most
 * {@link #MAX_PER_HOUR} shares per rolling hour ({@code 429 too_many_shares}), so notifications
 * can't be used to flood someone.
 */
public final class Shares {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Longest message, in code points (after cleaning). */
    public static final int MESSAGE_MAX = 500;
    /** Shares one user may create per rolling hour. */
    public static final int MAX_PER_HOUR = 100;
    /** Most ids one {@link #markSeen} call takes. */
    public static final int SEEN_IDS_MAX = 200;
    /** {@code library_entries.via} of an item added by a share. */
    public static final String VIA_SHARE = "share";

    private static final long HOUR_MS = 3_600_000L;

    /** Which shares {@link #list} returns. */
    public enum Direction {
        /** Shares the user received. */
        IN,
        /** Shares the user sent. */
        OUT;

        /** {@code in} or {@code out}; {@code 400 invalid_direction} otherwise. */
        public static Direction parse(String s) {
            if ("in".equals(s)) {
                return IN;
            }
            if ("out".equals(s)) {
                return OUT;
            }
            throw new HttpError(400, "invalid_direction", "direction must be in or out");
        }
    }

    private final Database db;
    private final YoutubeImports youtube;
    private final Clock clock;
    private volatile LibraryAddListener libraryAddListener = LibraryAddListener.NONE;

    /**
     * One share.
     *
     * @param message {@code null} when there is none
     * @param seen    whether the recipient marked it seen; {@code null} in the sender's view
     */
    public record Share(String id, Library.ItemView item, ActorRef from, ActorRef to, String message,
                       long createdAt, Boolean seen) {
    }

    /**
     * One page of shares; {@code nextCursor} is {@code null} on the last page. {@code unseenCount}
     * is the number of the caller's received shares not yet seen (in either direction's list).
     */
    public record Page(List<Share> items, String nextCursor, int unseenCount) {
    }

    public Shares(Database db, YoutubeImports youtube, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.youtube = Objects.requireNonNull(youtube);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * A cleaned share message (line breaks kept, like a playlist description).
     *
     * @return {@code null} when nothing is left
     * @throws HttpError {@code 400 message_too_long}
     */
    public static String message(String raw) {
        return UploadMetadata.cleanMultiline(raw, MESSAGE_MAX, "message_too_long");
    }

    private record ItemRow(String youtubeId, String status) {
    }

    /**
     * Shares {@code itemId} with the user called {@code toUsername} (case-insensitive) and adds it
     * to their library.
     *
     * @param message         the raw message, or {@code null}
     * @param mayFetchYoutube checked before a purged or failed YouTube item is fetched again
     *                        ({@code library.youtube}); throws to refuse
     * @return the new share, as the sender sees it
     * @throws HttpError {@code 404 user_not_found} (no such user, or disabled),
     *                   {@code 400 cannot_share_with_self}, {@code 400 message_too_long},
     *                   {@code 404 item_not_found}, {@code 409 media_expired} / {@code item_failed}
     *                   (a purged / failed upload), {@code 422 youtube_failed} (see
     *                   {@link YoutubeImports#useExisting}), {@code 429 too_many_shares}
     */
    public Share share(Actor.User from, String itemId, String toUsername, String message,
                       Library.YoutubeCheck mayFetchYoutube) throws SQLException, InterruptedException {
        String fromId = from.userId();
        String cleanMessage = message(message);
        String toId = db.read(c -> recipient(c, toUsername));
        if (toId == null) {
            throw new HttpError(404, "user_not_found", "No such user");
        }
        if (toId.equals(fromId)) {
            throw new HttpError(400, "cannot_share_with_self", "You can't share an item with yourself");
        }
        ItemRow item = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT youtube_id, status FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new ItemRow(rs.getString(1), rs.getString(2)) : null;
                }
            }
        });
        if (item == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        boolean gone = item.status().equals("purged") || item.status().equals("failed");
        if (gone && item.youtubeId() == null) {
            if (item.status().equals("purged")) {
                throw new HttpError(409, "media_expired",
                        "This item's media has expired; upload the file again to restore it");
            }
            throw new HttpError(409, "item_failed", "This item failed to process; upload the file again");
        }
        // The rate limit is checked again in the transaction; this early check keeps an
        // over-limit sender from starting a refetch.
        long early = EpochMs.now(clock);
        db.read(c -> {
            checkRate(c, fromId, early);
            return null;
        });
        if (gone) {
            mayFetchYoutube.check();
            youtube.useExisting(item.youtubeId());
        }
        String shareId = UuidV7.newId();
        long now = EpochMs.now(clock);
        return db.write(c -> {
            checkRate(c, fromId, now);
            if (recipient(c, toUsername) == null) {
                throw new HttpError(404, "user_not_found", "No such user"); // disabled or deleted meanwhile
            }
            try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                    List.of("user_id", "item_id", "added_at", "via", "shared_by_user_id"),
                    List.of("user_id", "item_id")))) {
                ps.setString(1, toId);
                ps.setString(2, itemId);
                ps.setLong(3, now);
                ps.setString(4, VIA_SHARE);
                ps.setString(5, fromId);
                ps.executeUpdate();
            }
            libraryAddListener.addedToLibrary(itemId);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO shares (id, item_id, from_user_id,"
                    + " to_user_id, message, created_at, seen) VALUES (?, ?, ?, ?, ?, ?, 0)")) {
                ps.setString(1, shareId);
                ps.setString(2, itemId);
                ps.setString(3, fromId);
                ps.setString(4, toId);
                ps.setString(5, cleanMessage);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            References.touch(c, itemId, now);
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE s.id = ?")) {
                ps.setString(1, shareId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return share(rs, Direction.OUT);
                }
            }
        });
    }

    /** The id of the enabled user called {@code username} (case-insensitive), or {@code null}. */
    private String recipient(Connection c, String username) throws SQLException {
        if (username == null) {
            return null;
        }
        String name = username.strip();
        if (name.isEmpty() || name.length() > 100) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE "
                + db.dialect().equalsIgnoreCase("username") + " AND disabled = 0")) {
            ps.setString(1, Dialect.foldCase(name));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** {@code 429 too_many_shares} if the sender made {@link #MAX_PER_HOUR} shares in the last hour. */
    private static void checkRate(Connection c, String fromId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), MIN(created_at) FROM shares"
                + " WHERE from_user_id = ? AND created_at > ?")) {
            ps.setString(1, fromId);
            ps.setLong(2, now - HOUR_MS);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) >= MAX_PER_HOUR) {
                    long oldest = rs.getLong(2);
                    long waitSec = Math.max(1, (oldest + HOUR_MS - now + 999) / 1000);
                    throw new HttpError(429, "too_many_shares", "You can share at most " + MAX_PER_HOUR
                            + " items per hour; try again later", waitSec);
                }
            }
        }
    }

    private static final String SELECT = "SELECT s.id, s.created_at, s.message, s.seen,"
            + " s.from_user_id, f.display_name, s.to_user_id, t.display_name,"
            + " i.id, i.kind, i.title, i.artist, i.album, i.duration_ms, i.source, i.youtube_id, i.status,"
            + " i.present_as_audio"
            + " FROM shares s JOIN items i ON i.id = s.item_id"
            + " JOIN users f ON f.id = s.from_user_id JOIN users t ON t.id = s.to_user_id";

    private static Share share(ResultSet rs, Direction direction) throws SQLException {
        long duration = rs.getLong(14);
        Long durationMs = rs.wasNull() ? null : duration;
        Library.ItemView item = new Library.ItemView(rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getString(13), durationMs, rs.getString(15), rs.getString(16), rs.getString(17),
                rs.getInt(18) != 0);
        Boolean seen = direction == Direction.IN ? rs.getInt(4) != 0 : null;
        return new Share(rs.getString(1), item, ActorRef.user(rs.getString(5), rs.getString(6)),
                ActorRef.user(rs.getString(7), rs.getString(8)), rs.getString(3), rs.getLong(2), seen);
    }

    /**
     * The shares the user received ({@link Direction#IN}) or sent ({@link Direction#OUT}), newest
     * first (then by id, descending).
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *               invalid_cursor} if malformed
     */
    public Page list(String userId, Direction direction, String cursor, int limit) throws SQLException {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(direction);
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        long cursorAt = 0;
        String cursorId = null;
        if (cursor != null) {
            int sep = cursor.indexOf('_');
            try {
                cursorAt = Long.parseLong(cursor.substring(0, Math.max(sep, 0)));
            } catch (NumberFormatException e) {
                sep = -1;
            }
            if (sep <= 0 || sep == cursor.length() - 1) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorId = cursor.substring(sep + 1);
        }
        String sql = SELECT + (direction == Direction.IN ? " WHERE s.to_user_id = ?" : " WHERE s.from_user_id = ?")
                + (cursorId != null ? " AND (s.created_at < ? OR (s.created_at = ? AND s.id < ?))" : "")
                + " ORDER BY s.created_at DESC, s.id DESC LIMIT " + (limit + 1);
        long at = cursorAt;
        String afterId = cursorId;
        return db.read(c -> {
            List<Share> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, userId);
                if (afterId != null) {
                    ps.setLong(2, at);
                    ps.setLong(3, at);
                    ps.setString(4, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(share(rs, direction));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                Share last = items.get(limit - 1);
                next = last.createdAt() + "_" + last.id();
            }
            return new Page(List.copyOf(items), next, unseenCount(c, userId));
        });
    }

    /** The number of shares the user received and hasn't marked seen. */
    public int unseenCount(String userId) throws SQLException {
        return db.read(c -> unseenCount(c, userId));
    }

    private static int unseenCount(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM shares WHERE to_user_id = ? AND seen = 0")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Marks shares the user received as seen. Ids of other users' shares, and unknown ids, are
     * ignored.
     *
     * @param ids the share ids, or {@code null} for every share the user received
     * @return the remaining unseen count
     */
    public int markSeen(String userId, Collection<String> ids) throws SQLException {
        Objects.requireNonNull(userId);
        if (ids != null && ids.size() > SEEN_IDS_MAX) {
            throw new IllegalArgumentException("at most " + SEEN_IDS_MAX + " ids");
        }
        List<String> distinct = ids == null ? null : List.copyOf(new LinkedHashSet<>(ids));
        return db.write(c -> {
            if (distinct == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE shares SET seen = 1 WHERE to_user_id = ? AND seen = 0")) {
                    ps.setString(1, userId);
                    ps.executeUpdate();
                }
            } else if (!distinct.isEmpty()) {
                StringBuilder sql = new StringBuilder("UPDATE shares SET seen = 1 WHERE to_user_id = ? AND seen = 0"
                        + " AND id IN (");
                for (int i = 0; i < distinct.size(); i++) {
                    sql.append(i == 0 ? "?" : ", ?");
                }
                sql.append(")");
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    ps.setString(1, userId);
                    for (int i = 0; i < distinct.size(); i++) {
                        ps.setString(i + 2, distinct.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            return unseenCount(c, userId);
        });
    }

    /** Sets who is told when this class adds an item to a library ({@link LibraryAddListener}). */
    public void libraryAddListener(LibraryAddListener listener) {
        this.libraryAddListener = listener == null ? LibraryAddListener.NONE : listener;
    }
}
