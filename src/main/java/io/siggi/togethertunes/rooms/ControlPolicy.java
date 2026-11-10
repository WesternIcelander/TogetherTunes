package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.http.HttpError;

/**
 * Who may do what to a room's queue and timeline in each control mode
 * ({@code docs/rooms.md#control-modes}, {@code #queue-operations}). Pure checks: each
 * {@code require…} throws an {@link HttpError} or returns; nothing is changed.
 *
 * <table>
 * <caption>Permissions per operation</caption>
 * <tr><th>Operation</th><th>{@code owner}</th><th>{@code vote}</th><th>{@code open}</th></tr>
 * <tr><td>add</td><td>mods add; others with request rights suggest</td><td>request rights</td>
 *     <td>request rights</td></tr>
 * <tr><td>addPlaylist</td><td colspan="3">owner and moderators</td></tr>
 * <tr><td>remove</td><td colspan="2">mods; the requester while the entry is queued or suggested</td>
 *     <td>mods; the requester; anyone with request rights</td></tr>
 * <tr><td>move</td><td>mods</td><td>(not in this mode)</td><td>request rights</td></tr>
 * <tr><td>pin</td><td>(not in this mode)</td><td>mods</td><td>(not in this mode)</td></tr>
 * <tr><td>vote</td><td>(not in this mode)</td><td>vote rights</td><td>(not in this mode)</td></tr>
 * <tr><td>accept/reject suggestion</td><td colspan="3">owner and moderators</td></tr>
 * <tr><td>play, pause, seek, next, previous</td><td>mods</td><td>mods</td><td>mods; request rights</td></tr>
 * <tr><td>voteSkip</td><td>(not in this mode)</td><td>vote rights, unless the threshold is off</td>
 *     <td>(not in this mode)</td></tr>
 * </table>
 *
 * <p>Error codes: {@code 401 identity_required} (anonymous), {@code 403 cannot_request} /
 * {@code cannot_vote} / {@code forbidden}, {@code 409 wrong_control_mode},
 * {@code 409 vote_skip_off}, {@code 400 invalid_vote}, {@code 403 downvotes_disabled}.
 */
public final class ControlPolicy {
    private ControlPolicy() {
    }

    /** Where an {@code add} lands. */
    public enum AddAs {
        /** In the queue ({@code queued}). */
        QUEUE,
        /** As a suggestion for the owner to accept or reject ({@code suggested}, {@code owner} mode). */
        SUGGESTION;

        public QueueEntry.State entryState() {
            return this == QUEUE ? QueueEntry.State.QUEUED : QueueEntry.State.SUGGESTED;
        }
    }

    /** What the actor may do, for the {@code you} object of the room state. */
    public record Capabilities(String role, boolean canVote, boolean canRequest, boolean canUpload,
                               boolean canYoutube, boolean canSkip) {
    }

    public static Capabilities capabilities(RoomState s, RoomActor a) {
        return capabilities(s.controlMode(), a);
    }

    /** {@link #capabilities(RoomState, RoomActor)} in a room with this control mode (all it depends on). */
    public static Capabilities capabilities(Rooms.ControlMode mode, RoomActor a) {
        return new Capabilities(a.role() == null ? null : a.role().wire(),
                a.identified() && a.canVote() && mode == Rooms.ControlMode.VOTE,
                a.identified() && (a.moderates() || a.canRequest()),
                a.identified() && a.canUpload(),
                a.identified() && a.canYoutube(),
                controlsPlayback(mode, a));
    }

    /**
     * The request path of an {@code add}: {@code via} is {@code library} (an item id),
     * {@code upload}, {@code youtube} or {@code history} (an item of the room's history, which
     * counts as a library request) ({@link RoomQueue.Source#via}). A guest needs the matching
     * effective capability ({@code 403 guest_library_requests_disabled} /
     * {@code guest_uploads_disabled} / {@code guest_youtube_disabled}); a user the matching
     * permission ({@code 403 forbidden}). The other checks of an add ({@link #add}) come on top.
     */
    public static void requireSource(RoomActor a, String via) {
        a.requireIdentity();
        boolean allowed = switch (via) {
            case "library", "history" -> a.canRequestLibrary();
            case "upload" -> a.canUpload();
            case "youtube" -> a.canYoutube();
            default -> throw new IllegalArgumentException("unknown request path: " + via);
        };
        if (allowed || !a.canRequest() && !a.moderates()) {
            return; // nothing to add here; add() refuses with cannot_request
        }
        if (!a.isGuest()) {
            throw HttpError.forbidden(switch (via) {
                case "upload" -> "You can't upload files";
                case "youtube" -> "You can't add YouTube links";
                default -> "You can't request from the library";
            });
        }
        throw switch (via) {
            case "upload" -> new HttpError(403, "guest_uploads_disabled", "Guests can't upload files in this room");
            case "youtube" -> new HttpError(403, "guest_youtube_disabled", "Guests can't add YouTube links in this room");
            default -> new HttpError(403, "guest_library_requests_disabled",
                    "Guests can't request from the library in this room");
        };
    }

    /**
     * {@code add}: owner and moderators always add to the queue. Others need request rights; in
     * {@code owner} mode their request becomes a suggestion.
     */
    public static AddAs add(RoomState s, RoomActor a) {
        a.requireIdentity();
        if (a.moderates()) {
            return AddAs.QUEUE;
        }
        requireRequestRights(a);
        return switch (s.controlMode()) {
            case OWNER -> AddAs.SUGGESTION;
            case VOTE, OPEN -> AddAs.QUEUE;
        };
    }

    /** {@code addPlaylist}: owner and moderators only, in every mode. */
    public static void requireAddPlaylist(RoomState s, RoomActor a) {
        requireModerator(a, "Only the owner and moderators can add a playlist");
    }

    /**
     * {@code remove(entry)} of a queued or suggested entry: owner and moderators; the requester
     * while the entry is still queued (or suggested); in {@code open} mode anyone with request
     * rights.
     */
    public static void requireRemove(RoomState s, RoomActor a, QueueEntry entry) {
        a.requireIdentity();
        if (a.moderates()) {
            return;
        }
        boolean waiting = entry.state() == QueueEntry.State.QUEUED || entry.state() == QueueEntry.State.SUGGESTED;
        if (waiting && a.added(entry)) {
            return;
        }
        switch (s.controlMode()) {
            case OPEN -> {
                if (waiting && a.canRequest()) {
                    return;
                }
            }
            case OWNER, VOTE -> {
            }
        }
        throw HttpError.forbidden("You can't remove this entry");
    }

    /**
     * {@code queue.retry} (a failed YouTube entry fetched again): the owner and moderators, and
     * whoever requested the entry while they may still add YouTube links here (for a guest the
     * room's and the server's guest rules, for a user {@code library.youtube}).
     */
    public static void requireRetry(RoomActor a, QueueEntry entry) {
        a.requireIdentity();
        if (a.moderates() || a.added(entry) && a.canYoutube()) {
            return;
        }
        throw HttpError.forbidden("Only the room's owner and moderators, or whoever requested it, can retry this");
    }

    /** {@code move}: {@code owner} mode: owner and moderators; {@code open}: request rights; not in {@code vote}. */
    public static void requireMove(RoomState s, RoomActor a) {
        a.requireIdentity();
        switch (s.controlMode()) {
            case OWNER -> requireModerator(a, "Only the owner and moderators can reorder the queue");
            case OPEN -> {
                if (!a.moderates()) {
                    requireRequestRights(a);
                }
            }
            case VOTE -> throw wrongMode("In vote mode the queue is ordered by votes; pin an entry instead");
        }
    }

    /** {@code pin}: {@code vote} mode only, owner and moderators. */
    public static void requirePin(RoomState s, RoomActor a) {
        a.requireIdentity();
        switch (s.controlMode()) {
            case VOTE -> requireModerator(a, "Only the owner and moderators can pin entries");
            case OWNER, OPEN -> throw wrongMode("Pinning is for vote mode; move the entry instead");
        }
    }

    /** {@code vote(value)}: {@code vote} mode, vote rights; {@code -1} only with {@code allowDownvotes}. */
    public static void requireVote(RoomState s, RoomActor a, int value) {
        a.requireIdentity();
        switch (s.controlMode()) {
            case VOTE -> {
            }
            case OWNER, OPEN -> throw wrongMode("Votes are for vote mode");
        }
        if (value < -1 || value > 1) {
            throw new HttpError(400, "invalid_vote", "A vote is 1, -1 or 0");
        }
        requireVoteRights(a);
        if (value == -1 && !s.controlSettings().allowDownvotes()) {
            throw new HttpError(403, "downvotes_disabled", "This room doesn't allow downvotes");
        }
    }

    /** {@code suggest.accept} / {@code suggest.reject}: owner and moderators, in every mode. */
    public static void requireDecideSuggestion(RoomState s, RoomActor a) {
        requireModerator(a, "Only the owner and moderators can accept or reject suggestions");
    }

    /**
     * {@code play}, {@code pause}, {@code seek}, {@code next} (skip) and {@code previous}: owner
     * and moderators; in {@code open} mode also anyone with request rights.
     */
    public static void requireControlPlayback(RoomState s, RoomActor a) {
        a.requireIdentity();
        if (controlsPlayback(s.controlMode(), a)) {
            return;
        }
        throw HttpError.forbidden(switch (s.controlMode()) {
            case OWNER -> "Only the owner and moderators control playback";
            case VOTE -> "Only the owner and moderators control playback; vote to skip instead";
            case OPEN -> "You need request rights to control playback";
        });
    }

    /** {@code voteSkip}: {@code vote} mode, vote rights, and a threshold that isn't {@code off}. */
    public static void requireVoteSkip(RoomState s, RoomActor a) {
        a.requireIdentity();
        switch (s.controlMode()) {
            case VOTE -> {
            }
            case OWNER, OPEN -> throw wrongMode("Vote-skip is for vote mode");
        }
        requireVoteRights(a);
        if (s.controlSettings().voteSkipThreshold() == null) {
            throw new HttpError(409, "vote_skip_off", "This room has vote-skip switched off");
        }
    }

    private static boolean controlsPlayback(Rooms.ControlMode mode, RoomActor a) {
        if (!a.identified()) {
            return false;
        }
        if (a.moderates()) {
            return true;
        }
        return switch (mode) {
            case OWNER, VOTE -> false;
            case OPEN -> a.canRequest();
        };
    }

    private static void requireModerator(RoomActor a, String message) {
        a.requireIdentity();
        if (!a.moderates()) {
            throw HttpError.forbidden(message);
        }
    }

    private static void requireRequestRights(RoomActor a) {
        if (!a.canRequest()) {
            throw new HttpError(403, "cannot_request", "You can't request in this room");
        }
    }

    private static void requireVoteRights(RoomActor a) {
        if (!a.canVote()) {
            throw new HttpError(403, "cannot_vote", "You can't vote in this room");
        }
    }

    private static HttpError wrongMode(String message) {
        return new HttpError(409, "wrong_control_mode", message);
    }
}
