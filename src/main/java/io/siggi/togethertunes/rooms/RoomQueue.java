package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.AddMediaPolicy;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.library.Uploads;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.library.YoutubeLookupStage;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * The queue operations of a room as its callers (the WebSocket layer) use them: each one runs on
 * the room's executor through the {@link RoomRegistry}. {@code add} and {@code addPlaylist} do
 * their slow part first, outside the executor: an {@code uploadId} becomes the item its
 * {@code complete} produced, a {@code youtubeUrl} is imported ({@link YoutubeImports}, which may
 * run yt-dlp), and a purged or failed YouTube item is fetched again. The cheap checks
 * ({@link QueueOps#checkAdd}) run on the executor before that, so a refused request starts nothing;
 * everything is checked again when the entry is made.
 *
 * <p>The caller passes both the {@link Actor} (whose uploads and imports these are) and the
 * {@link RoomActor} (its room role and effective capabilities, built by the caller).
 */
public final class RoomQueue {
    private final RoomRegistry registry;
    private final Database db;
    private final Uploads uploads;
    private final YoutubeImports youtube;
    private final Playlists playlists;
    private final AddMediaPolicy addMedia;
    private final Random random;

    /** Where an added item comes from: exactly one of the three is set. */
    public record Source(String itemId, String uploadId, String youtubeUrl) {
        public static Source item(String itemId) {
            return new Source(itemId, null, null);
        }

        public static Source upload(String uploadId) {
            return new Source(null, uploadId, null);
        }

        public static Source youtube(String url) {
            return new Source(null, null, url);
        }

        /** {@code library}, {@code upload} or {@code youtube} (as {@code room_requests.via}). */
        public String via() {
            return itemId != null ? "library" : uploadId != null ? "upload" : "youtube";
        }
    }

    /** The room's request scope and owner, read on the executor with the cheap checks. */
    private record Scope(RoomSettings.RequestScope requestScope, String ownerId) {
    }

    public RoomQueue(RoomRegistry registry, Database db, Uploads uploads, YoutubeImports youtube, Playlists playlists,
                     AddMediaPolicy addMedia, Random random) {
        this.registry = Objects.requireNonNull(registry);
        this.db = Objects.requireNonNull(db);
        this.uploads = Objects.requireNonNull(uploads);
        this.youtube = Objects.requireNonNull(youtube);
        this.playlists = Objects.requireNonNull(playlists);
        this.addMedia = Objects.requireNonNull(addMedia);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * {@code queue.add}: see {@link QueueOps#add}. A request (not by the owner, nor by a moderator
     * in {@code owner} mode) is written to the room's request history with its entry
     * ({@link RoomRequests}).
     *
     * @throws HttpError {@code 400 invalid_source} (not exactly one source), as
     *                   {@link ControlPolicy#requireSource} (the actor's right to this request path:
     *                   for a guest the effective guest capability) and {@link QueueOps#add};
     *                   by item id, outside the owner and moderators, the room's request scope
     *                   ({@link RequestSearch#requireInScope}: {@code 403 library_requests_disabled}
     *                   / {@code not_in_request_scope});
     *                   for an upload {@code 404 upload_not_found} (not the actor's) and
     *                   {@code 409 upload_incomplete}; for a YouTube link (or a purged/failed
     *                   YouTube item) the import's errors and {@link AddMediaPolicy#requireYoutube}
     */
    public QueueOps.Added add(String roomId, Actor actor, RoomActor a, Source source) throws Exception {
        return add(roomId, actor, a, source, YoutubeLookupStage.Listener.NONE);
    }

    /**
     * Like {@link #add(String, Actor, RoomActor, Source)}, telling {@code lookup} the stages of a
     * YouTube link's {@code yt-dlp -J} lookup while it runs (only when there is one).
     */
    public QueueOps.Added add(String roomId, Actor actor, RoomActor a, Source source,
                              YoutubeLookupStage.Listener lookup) throws Exception {
        int given = (source.itemId() != null ? 1 : 0) + (source.uploadId() != null ? 1 : 0)
                + (source.youtubeUrl() != null ? 1 : 0);
        if (given != 1) {
            throw new HttpError(400, "invalid_source", "Give exactly one of itemId, uploadId and youtubeUrl");
        }
        ControlPolicy.requireSource(a, source.via());
        Scope scope = registry.call(roomId, tx -> {
            QueueOps.checkAdd(tx.state(), a);
            return new Scope(tx.state().settings().requestScope(), tx.state().ownerId());
        });
        String itemId;
        if (source.itemId() != null) {
            itemId = source.itemId();
            RequestSearch.requireInScope(db, scope.requestScope(), scope.ownerId(), a, itemId);
            refetchIfNeeded(roomId, actor, itemId);
        } else if (source.uploadId() != null) {
            Uploads.Upload upload = uploads.get(actor, source.uploadId());
            if (!upload.completed()) {
                throw new HttpError(409, "upload_incomplete", "Complete the upload first");
            }
            itemId = upload.itemId();
        } else {
            addMedia.requireYoutube(actor, roomId);
            itemId = youtube.importUrl(actor, source.youtubeUrl(), null, false, lookup).itemId();
        }
        return registry.call(roomId, tx -> QueueOps.add(tx, a, itemId, source.via()));
    }

    /**
     * {@code queue.addPlaylist}: the owner and moderators add one of their own playlists, in its
     * order or shuffled. Purged or failed YouTube items in it are fetched again when allowed (and
     * left out when not); see {@link QueueOps#addPlaylist}.
     *
     * @throws HttpError as {@link ControlPolicy#requireAddPlaylist}, {@code 404 playlist_not_found}
     */
    public QueueOps.PlaylistAdded addPlaylist(String roomId, Actor actor, RoomActor a, String playlistId,
                                              boolean shuffle) throws Exception {
        registry.call(roomId, tx -> {
            ControlPolicy.requireAddPlaylist(tx.state(), a);
            return null;
        });
        if (!(actor instanceof Actor.User user)) {
            throw HttpError.forbidden("Only the owner and moderators can add a playlist");
        }
        Playlists.PlaylistPage page = playlists.get(user.userId(), playlistId);
        List<String> itemIds = new ArrayList<>();
        for (Playlists.Entry e : page.entries()) {
            Library.ItemView item = e.item();
            if (item.youtubeId() != null && ("purged".equals(item.status()) || "failed".equals(item.status()))) {
                try {
                    addMedia.requireYoutube(actor, roomId);
                    youtube.useExisting(item.youtubeId());
                } catch (HttpError refused) {
                    // Left out below (still purged or failed).
                }
            }
            itemIds.add(item.id());
        }
        if (shuffle) {
            synchronized (random) {
                Collections.shuffle(itemIds, random);
            }
        }
        return registry.call(roomId, tx -> QueueOps.addPlaylist(tx, a, itemIds));
    }

    /** {@code queue.remove}: see {@link QueueOps#remove}. */
    public void remove(String roomId, RoomActor a, String entryId) throws Exception {
        registry.call(roomId, tx -> {
            QueueOps.remove(tx, a, entryId);
            return null;
        });
    }

    /**
     * {@code queue.retry}: fetches the failed YouTube item of a queued entry or suggestion again
     * ({@link YoutubeImports#retry}). The entry keeps its place; its item turns {@code pending} and
     * plays once ready like any entry that isn't ready yet.
     *
     * @throws HttpError {@code 404 entry_not_found}, as {@link ControlPolicy#requireRetry},
     *                   {@code 403 feature_disabled} ({@code ytdlp.enabled} off), and
     *                   {@link YoutubeImports#retry}'s errors
     */
    public YoutubeImports.Retried retry(String roomId, Actor actor, RoomActor a, String entryId, String clientIp)
            throws Exception {
        String itemId = registry.call(roomId, tx -> {
            QueueEntry e = entryId == null ? null : tx.state().waitingEntry(entryId);
            if (e == null) {
                throw new HttpError(404, "entry_not_found", "No such entry in the queue");
            }
            ControlPolicy.requireRetry(a, e);
            return e.itemId();
        });
        addMedia.requireYoutubeFeature();
        return youtube.retry(itemId, actor, clientIp, roomId);
    }

    /** {@code queue.move}: see {@link QueueOps#move}. */
    public boolean move(String roomId, RoomActor a, String entryId, String beforeEntryId) throws Exception {
        return registry.call(roomId, tx -> QueueOps.move(tx, a, entryId, beforeEntryId));
    }

    /** {@code queue.pin}: see {@link QueueControl#pin}. */
    public boolean pin(String roomId, RoomActor a, String entryId, boolean pinned) throws Exception {
        return registry.call(roomId, tx -> QueueControl.pin(tx, a, entryId, pinned));
    }

    /** {@code vote}: see {@link QueueControl#vote}. */
    public int vote(String roomId, RoomActor a, String entryId, int value) throws Exception {
        return registry.call(roomId, tx -> QueueControl.vote(tx, a, entryId, value));
    }

    /** {@code voteSkip}: see {@link QueueControl#voteSkip}. */
    public QueueControl.VoteSkip voteSkip(String roomId, RoomActor a) throws Exception {
        return registry.call(roomId, tx -> QueueControl.voteSkip(tx, a));
    }

    /** {@code suggest.accept}: see {@link QueueControl#accept}. */
    public void accept(String roomId, RoomActor a, String entryId) throws Exception {
        registry.call(roomId, tx -> {
            QueueControl.accept(tx, a, entryId);
            return null;
        });
    }

    /** {@code suggest.reject}: see {@link QueueControl#reject}. */
    public void reject(String roomId, RoomActor a, String entryId) throws Exception {
        registry.call(roomId, tx -> {
            QueueControl.reject(tx, a, entryId);
            return null;
        });
    }

    /** {@code play}: see {@link QueueOps#play}. */
    public boolean play(String roomId, RoomActor a) throws Exception {
        return registry.call(roomId, tx -> QueueOps.play(tx, a));
    }

    /** {@code pause}: see {@link QueueOps#pause}. */
    public boolean pause(String roomId, RoomActor a) throws Exception {
        return registry.call(roomId, tx -> QueueOps.pause(tx, a));
    }

    /** {@code seek}: see {@link QueueOps#seek}. */
    public void seek(String roomId, RoomActor a, long positionMs) throws Exception {
        registry.call(roomId, tx -> {
            QueueOps.seek(tx, a, positionMs);
            return null;
        });
    }

    /** {@code next}: see {@link QueueOps#next}. */
    public void next(String roomId, RoomActor a) throws Exception {
        registry.call(roomId, tx -> QueueOps.next(tx, a));
    }

    /** {@code previous}: see {@link QueueOps#previous}. */
    public boolean previous(String roomId, RoomActor a) throws Exception {
        return registry.call(roomId, tx -> QueueOps.previous(tx, a));
    }

    /**
     * A purged or failed YouTube item is fetched again (as an import of its link would be, so it
     * needs the YouTube rights); other items are left to {@link QueueOps#add}'s checks.
     */
    private void refetchIfNeeded(String roomId, Actor actor, String itemId) throws Exception {
        if (!UuidV7.isValid(itemId)) {
            return;
        }
        String[] row = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, youtube_id FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null;
                }
            }
        });
        if (row == null || row[1] == null || !("purged".equals(row[0]) || "failed".equals(row[0]))) {
            return;
        }
        addMedia.requireYoutube(actor, roomId);
        youtube.useExisting(row[1]);
    }
}
