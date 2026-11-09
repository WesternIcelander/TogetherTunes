package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.HttpError;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@link ControlPolicy}: the permission of every queue operation in every control mode, for
 * owner, moderator, member, a user without a role, guests with and without rights, banned users
 * and anonymous visitors. Also {@link ControlSettings} and the per-mode play order.
 */
class ControlPolicyTest {
    private static final RoomActor OWNER = RoomActor.user("u-owner", Rooms.Role.OWNER);
    private static final RoomActor MOD = RoomActor.user("u-mod", Rooms.Role.MODERATOR);
    private static final RoomActor MEMBER = RoomActor.user("u-member", Rooms.Role.MEMBER);
    private static final RoomActor USER = RoomActor.user("u-none", null);
    private static final RoomActor BANNED = RoomActor.user("u-banned", Rooms.Role.BANNED);
    private static final RoomActor GUEST = RoomActor.guest("g-1", true, true);
    private static final RoomActor GUEST_NO_RIGHTS = RoomActor.guest("g-2", false, false);
    private static final RoomActor ANON = RoomActor.ANONYMOUS;

    private static RoomState room(Rooms.ControlMode mode, String settings) {
        return new RoomState("r", "party", "Party", "u-owner", Rooms.Visibility.UNLISTED, mode, settings,
                Timeline.IDLE, 0);
    }

    private static RoomState room(Rooms.ControlMode mode) {
        return room(mode, "{}");
    }

    private static QueueEntry entry(String id, String byUser, String byGuest, QueueEntry.State state) {
        return new QueueEntry(id, "item-" + id, byUser, byGuest, 1, state, 1, false, null, "ready", 1000L);
    }

    private static void allowed(Executable e) {
        try {
            e.execute();
        } catch (Throwable t) {
            throw new AssertionError("expected allowed, got " + t, t);
        }
    }

    private static void refused(int status, String code, Executable e) {
        HttpError err = assertThrows(HttpError.class, e);
        assertEquals(status, err.status(), err.code());
        assertEquals(code, err.code());
    }

    @Test
    void add() {
        RoomState owner = room(Rooms.ControlMode.OWNER);
        assertEquals(ControlPolicy.AddAs.QUEUE, ControlPolicy.add(owner, OWNER));
        assertEquals(ControlPolicy.AddAs.QUEUE, ControlPolicy.add(owner, MOD));
        assertEquals(ControlPolicy.AddAs.SUGGESTION, ControlPolicy.add(owner, MEMBER));
        assertEquals(ControlPolicy.AddAs.SUGGESTION, ControlPolicy.add(owner, USER));
        assertEquals(ControlPolicy.AddAs.SUGGESTION, ControlPolicy.add(owner, GUEST));
        for (Rooms.ControlMode mode : List.of(Rooms.ControlMode.VOTE, Rooms.ControlMode.OPEN)) {
            RoomState s = room(mode);
            for (RoomActor a : List.of(OWNER, MOD, MEMBER, USER, GUEST)) {
                assertEquals(ControlPolicy.AddAs.QUEUE, ControlPolicy.add(s, a), mode + " " + a);
            }
        }
        for (Rooms.ControlMode mode : Rooms.ControlMode.values()) {
            RoomState s = room(mode);
            refused(403, "cannot_request", () -> ControlPolicy.add(s, GUEST_NO_RIGHTS));
            refused(403, "cannot_request", () -> ControlPolicy.add(s, BANNED));
            refused(401, "identity_required", () -> ControlPolicy.add(s, ANON));
        }
        assertEquals(QueueEntry.State.SUGGESTED, ControlPolicy.AddAs.SUGGESTION.entryState());
    }

    @Test
    void addPlaylistAndSuggestionsAreForOwnerAndModerators() {
        for (Rooms.ControlMode mode : Rooms.ControlMode.values()) {
            RoomState s = room(mode);
            for (RoomActor a : List.of(OWNER, MOD)) {
                allowed(() -> ControlPolicy.requireAddPlaylist(s, a));
                allowed(() -> ControlPolicy.requireDecideSuggestion(s, a));
            }
            for (RoomActor a : List.of(MEMBER, USER, GUEST, BANNED)) {
                refused(403, "forbidden", () -> ControlPolicy.requireAddPlaylist(s, a));
                refused(403, "forbidden", () -> ControlPolicy.requireDecideSuggestion(s, a));
            }
            refused(401, "identity_required", () -> ControlPolicy.requireAddPlaylist(s, ANON));
            refused(401, "identity_required", () -> ControlPolicy.requireDecideSuggestion(s, ANON));
        }
    }

    @Test
    void remove() {
        QueueEntry byMember = entry("e1", "u-member", null, QueueEntry.State.QUEUED);
        QueueEntry byGuest = entry("e2", null, "g-1", QueueEntry.State.SUGGESTED);
        QueueEntry byOther = entry("e3", "u-x", null, QueueEntry.State.QUEUED);
        for (Rooms.ControlMode mode : Rooms.ControlMode.values()) {
            RoomState s = room(mode);
            for (QueueEntry e : List.of(byMember, byGuest, byOther)) {
                allowed(() -> ControlPolicy.requireRemove(s, OWNER, e));
                allowed(() -> ControlPolicy.requireRemove(s, MOD, e));
                refused(401, "identity_required", () -> ControlPolicy.requireRemove(s, ANON, e));
                refused(403, "forbidden", () -> ControlPolicy.requireRemove(s, GUEST_NO_RIGHTS, e));
            }
            // The requester, while the entry is still waiting.
            allowed(() -> ControlPolicy.requireRemove(s, MEMBER, byMember));
            allowed(() -> ControlPolicy.requireRemove(s, GUEST, byGuest));
            refused(403, "forbidden", () -> ControlPolicy.requireRemove(s, MEMBER,
                    byMember.withState(QueueEntry.State.PLAYING, 5L)));
        }
        // Someone else's entry: only in open mode, with request rights.
        refused(403, "forbidden", () -> ControlPolicy.requireRemove(room(Rooms.ControlMode.OWNER), MEMBER, byOther));
        refused(403, "forbidden", () -> ControlPolicy.requireRemove(room(Rooms.ControlMode.VOTE), GUEST, byOther));
        allowed(() -> ControlPolicy.requireRemove(room(Rooms.ControlMode.OPEN), MEMBER, byOther));
        allowed(() -> ControlPolicy.requireRemove(room(Rooms.ControlMode.OPEN), GUEST, byOther));
        refused(403, "forbidden", () -> ControlPolicy.requireRemove(room(Rooms.ControlMode.OPEN), BANNED, byOther));
    }

    @Test
    void moveAndPin() {
        RoomState owner = room(Rooms.ControlMode.OWNER);
        RoomState vote = room(Rooms.ControlMode.VOTE);
        RoomState open = room(Rooms.ControlMode.OPEN);
        allowed(() -> ControlPolicy.requireMove(owner, OWNER));
        allowed(() -> ControlPolicy.requireMove(owner, MOD));
        refused(403, "forbidden", () -> ControlPolicy.requireMove(owner, MEMBER));
        refused(403, "forbidden", () -> ControlPolicy.requireMove(owner, GUEST));
        for (RoomActor a : List.of(OWNER, MOD, MEMBER, USER, GUEST)) {
            allowed(() -> ControlPolicy.requireMove(open, a));
            refused(409, "wrong_control_mode", () -> ControlPolicy.requireMove(vote, a));
            refused(409, "wrong_control_mode", () -> ControlPolicy.requirePin(owner, a));
            refused(409, "wrong_control_mode", () -> ControlPolicy.requirePin(open, a));
        }
        refused(403, "cannot_request", () -> ControlPolicy.requireMove(open, GUEST_NO_RIGHTS));
        refused(401, "identity_required", () -> ControlPolicy.requireMove(open, ANON));
        allowed(() -> ControlPolicy.requirePin(vote, OWNER));
        allowed(() -> ControlPolicy.requirePin(vote, MOD));
        for (RoomActor a : List.of(MEMBER, USER, GUEST, BANNED)) {
            refused(403, "forbidden", () -> ControlPolicy.requirePin(vote, a));
        }
        refused(401, "identity_required", () -> ControlPolicy.requirePin(vote, ANON));
    }

    @Test
    void votes() {
        RoomState vote = room(Rooms.ControlMode.VOTE);
        RoomState downvotes = room(Rooms.ControlMode.VOTE, "{\"allowDownvotes\":true}");
        for (RoomActor a : List.of(OWNER, MOD, MEMBER, USER, GUEST)) {
            allowed(() -> ControlPolicy.requireVote(vote, a, 1));
            allowed(() -> ControlPolicy.requireVote(vote, a, 0));
            refused(403, "downvotes_disabled", () -> ControlPolicy.requireVote(vote, a, -1));
            allowed(() -> ControlPolicy.requireVote(downvotes, a, -1));
            refused(400, "invalid_vote", () -> ControlPolicy.requireVote(vote, a, 2));
            refused(409, "wrong_control_mode", () -> ControlPolicy.requireVote(room(Rooms.ControlMode.OWNER), a, 1));
            refused(409, "wrong_control_mode", () -> ControlPolicy.requireVote(room(Rooms.ControlMode.OPEN), a, 1));
        }
        refused(403, "cannot_vote", () -> ControlPolicy.requireVote(vote, GUEST_NO_RIGHTS, 1));
        refused(403, "cannot_vote", () -> ControlPolicy.requireVote(vote, BANNED, 1));
        refused(401, "identity_required", () -> ControlPolicy.requireVote(vote, ANON, 1));
    }

    @Test
    void playbackControl() {
        for (Rooms.ControlMode mode : Rooms.ControlMode.values()) {
            RoomState s = room(mode);
            allowed(() -> ControlPolicy.requireControlPlayback(s, OWNER));
            allowed(() -> ControlPolicy.requireControlPlayback(s, MOD));
            refused(401, "identity_required", () -> ControlPolicy.requireControlPlayback(s, ANON));
            refused(403, "forbidden", () -> ControlPolicy.requireControlPlayback(s, GUEST_NO_RIGHTS));
            refused(403, "forbidden", () -> ControlPolicy.requireControlPlayback(s, BANNED));
        }
        for (RoomActor a : List.of(MEMBER, USER, GUEST)) {
            refused(403, "forbidden", () -> ControlPolicy.requireControlPlayback(room(Rooms.ControlMode.OWNER), a));
            refused(403, "forbidden", () -> ControlPolicy.requireControlPlayback(room(Rooms.ControlMode.VOTE), a));
            allowed(() -> ControlPolicy.requireControlPlayback(room(Rooms.ControlMode.OPEN), a));
        }
    }

    @Test
    void voteSkip() {
        RoomState vote = room(Rooms.ControlMode.VOTE);
        for (RoomActor a : List.of(OWNER, MOD, MEMBER, USER, GUEST)) {
            allowed(() -> ControlPolicy.requireVoteSkip(vote, a));
            refused(409, "wrong_control_mode", () -> ControlPolicy.requireVoteSkip(room(Rooms.ControlMode.OWNER), a));
            refused(409, "wrong_control_mode", () -> ControlPolicy.requireVoteSkip(room(Rooms.ControlMode.OPEN), a));
            refused(409, "vote_skip_off", () -> ControlPolicy.requireVoteSkip(
                    room(Rooms.ControlMode.VOTE, "{\"voteSkipThreshold\":\"off\"}"), a));
        }
        refused(403, "cannot_vote", () -> ControlPolicy.requireVoteSkip(vote, GUEST_NO_RIGHTS));
        refused(401, "identity_required", () -> ControlPolicy.requireVoteSkip(vote, ANON));
    }

    @Test
    void capabilities() {
        ControlPolicy.Capabilities c = ControlPolicy.capabilities(room(Rooms.ControlMode.VOTE), GUEST);
        assertNull(c.role());
        assertTrue(c.canVote());
        assertTrue(c.canRequest());
        assertFalse(c.canSkip());
        c = ControlPolicy.capabilities(room(Rooms.ControlMode.OPEN), MEMBER);
        assertEquals("member", c.role());
        assertFalse(c.canVote());
        assertTrue(c.canSkip());
        c = ControlPolicy.capabilities(room(Rooms.ControlMode.OWNER), MOD);
        assertTrue(c.canSkip());
        assertTrue(c.canRequest());
        c = ControlPolicy.capabilities(room(Rooms.ControlMode.VOTE), ANON);
        assertFalse(c.canVote() || c.canRequest() || c.canUpload() || c.canYoutube() || c.canSkip());
        // A guest that may only upload: request rights, upload, no links.
        RoomActor uploader = new RoomActor(null, "g-3", null, false, true, false, true);
        c = ControlPolicy.capabilities(room(Rooms.ControlMode.VOTE), uploader);
        assertTrue(c.canRequest() && c.canUpload() && c.canVote());
        assertFalse(c.canYoutube());
        c = ControlPolicy.capabilities(room(Rooms.ControlMode.OPEN), RoomActor.user("u-x", null, false, true));
        assertTrue(c.canRequest() && c.canYoutube());
        assertFalse(c.canUpload());
    }

    @Test
    void requestPaths() {
        RoomActor libraryOnly = new RoomActor(null, "g-3", null, true, false, false, true);
        allowed(() -> ControlPolicy.requireSource(libraryOnly, "library"));
        refused(403, "guest_uploads_disabled", () -> ControlPolicy.requireSource(libraryOnly, "upload"));
        refused(403, "guest_youtube_disabled", () -> ControlPolicy.requireSource(libraryOnly, "youtube"));
        RoomActor linksOnly = new RoomActor(null, "g-4", null, false, false, true, true);
        refused(403, "guest_library_requests_disabled", () -> ControlPolicy.requireSource(linksOnly, "library"));
        allowed(() -> ControlPolicy.requireSource(linksOnly, "youtube"));
        for (String via : List.of("library", "upload", "youtube")) {
            allowed(() -> ControlPolicy.requireSource(GUEST, via));
            allowed(() -> ControlPolicy.requireSource(OWNER, via));
            // No request rights at all: left to add(), which says cannot_request.
            allowed(() -> ControlPolicy.requireSource(GUEST_NO_RIGHTS, via));
            allowed(() -> ControlPolicy.requireSource(BANNED, via));
            refused(401, "identity_required", () -> ControlPolicy.requireSource(ANON, via));
        }
        // A user (even a moderator) without the upload permission.
        RoomActor modNoUpload = RoomActor.user("u-m", Rooms.Role.MODERATOR, false, false);
        allowed(() -> ControlPolicy.requireSource(modNoUpload, "library"));
        refused(403, "forbidden", () -> ControlPolicy.requireSource(modNoUpload, "upload"));
        refused(403, "forbidden", () -> ControlPolicy.requireSource(modNoUpload, "youtube"));
        assertThrows(IllegalArgumentException.class, () -> ControlPolicy.requireSource(GUEST, "carrier-pigeon"));
    }

    @Test
    void controlSettings() {
        assertEquals(ControlSettings.DEFAULTS, ControlSettings.parse("{}"));
        assertEquals(ControlSettings.DEFAULTS, ControlSettings.parse("not json"));
        assertEquals(ControlSettings.DEFAULTS, ControlSettings.parse("{\"allowDownvotes\":\"yes\","
                + "\"voteSkipThreshold\":1.5}"));
        assertEquals(new ControlSettings(true, null), ControlSettings.parse("{\"allowDownvotes\":true,"
                + "\"voteSkipThreshold\":\"off\"}"));
        assertEquals(new ControlSettings(false, 0.3), ControlSettings.parse("{\"voteSkipThreshold\":0.3}"));
        assertEquals(3, ControlSettings.DEFAULTS.requestsPerActor());
        assertEquals(10, ControlSettings.DEFAULTS.noRepeatWindow());
        assertEquals(new ControlSettings(false, 0.5, 7, 0), ControlSettings.parse("{\"requestsPerActor\":7,"
                + "\"noRepeatWindow\":0}"));
        assertEquals(ControlSettings.DEFAULTS, ControlSettings.parse("{\"requestsPerActor\":0,"
                + "\"noRepeatWindow\":-1}"), "out of range");
        assertEquals(ControlSettings.DEFAULTS, ControlSettings.parse("{\"requestsPerActor\":2.5,"
                + "\"noRepeatWindow\":\"5\"}"), "not a whole number");
        assertEquals(ControlSettings.DEFAULTS, ControlSettings.parse("{\"requestsPerActor\":1001,"
                + "\"noRepeatWindow\":1001}"), "too large");
        ControlSettings half = ControlSettings.DEFAULTS;
        assertEquals(1, half.skipVotesNeeded(0));
        assertEquals(1, half.skipVotesNeeded(1));
        assertEquals(1, half.skipVotesNeeded(2));
        assertEquals(2, half.skipVotesNeeded(3));
        assertEquals(2, half.skipVotesNeeded(4));
        assertEquals(3, new ControlSettings(false, 0.3).skipVotesNeeded(10));
        assertEquals(10, new ControlSettings(false, 1.0).skipVotesNeeded(10));
        assertThrows(IllegalStateException.class, () -> new ControlSettings(false, null).skipVotesNeeded(3));
    }

    @Test
    void playOrderPerMode() {
        // a: position 1, added 1; b: position 2, added 2; c: position 3, added 3, pinned.
        QueueEntry a = new QueueEntry("a", "i", "u", null, 1, QueueEntry.State.QUEUED, 1, false, null, "ready", 1L);
        QueueEntry b = new QueueEntry("b", "i", "u", null, 2, QueueEntry.State.QUEUED, 2, false, null, "ready", 1L);
        QueueEntry c = new QueueEntry("c", "i", "u", null, 3, QueueEntry.State.QUEUED, 3, true, null, "ready", 1L);
        QueueEntry d = new QueueEntry("d", "i", "u", null, 4, QueueEntry.State.QUEUED, 0.5, false, null, "ready", 1L);
        Map<String, Map<String, Integer>> votes = Map.of("b", Map.of("u:1", 1, "u:2", 1), "d", Map.of("u:1", -1),
                "a", Map.of("u:3", 1));
        for (Rooms.ControlMode mode : Rooms.ControlMode.values()) {
            RoomState s = new RoomState("r", "party", "Party", "o", Rooms.Visibility.UNLISTED, mode, "{}",
                    Timeline.IDLE, 0, null, List.of(a, b, c, d), List.of(), votes, Set.of());
            List<String> order = Playback.playOrder(s).stream().map(QueueEntry::id).toList();
            switch (mode) {
                // Manual: positions only, pins don't count.
                case OWNER, OPEN -> assertEquals(List.of("d", "a", "b", "c"), order, mode.wire());
                // Pinned first, then votes (b 2, a 1, d -1).
                case VOTE -> assertEquals(List.of("c", "b", "a", "d"), order, mode.wire());
            }
        }
    }
}
