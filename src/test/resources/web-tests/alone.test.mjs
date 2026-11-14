// Unit tests for web/src/alone.ts (compiled), run with Node's test runner by AloneJsTest.
// The module path comes in ALONE_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const A = await import(pathToFileURL(process.env.ALONE_JS).href);

const user = { kind: "user", isGuest: false };
const guest = { kind: "guest", isGuest: true };
const anonymous = { kind: "anonymous" };

test("only users can be alone", () => {
  assert.equal(A.canBeAlone(user), true);
  assert.equal(A.canBeAlone(guest), false);
  assert.equal(A.canBeAlone(anonymous), false);
  assert.equal(A.canBeAlone(undefined), false);
  assert.equal(A.canBeAlone({ kind: "user", isGuest: true }), false);
});

test("the session's room: the room the user is in, otherwise the alone room", () => {
  assert.equal(A.sessionRoom({ aloneRoomId: "a" }), "a");
  assert.equal(A.sessionRoom({ aloneRoomId: "a", roomId: "r" }), "r");
  assert.equal(A.sessionRoom({ aloneRoomId: "a", roomId: "" }), "a");
  assert.equal(A.roomPath("a b"), "/rooms/a%20b");
  assert.equal(A.roomPath("~0190"), "/rooms/~0190");
});

test("a deleted room waits for fork or restore", () => {
  assert.equal(A.needsLeaveChoice({ aloneRoomId: "a" }), false);
  assert.equal(A.needsLeaveChoice({ aloneRoomId: "a", deletedRoom: { name: "Lounge" } }), true);
});

test("opening a room joins it: users, not their alone room, not the room they are in", () => {
  assert.equal(A.shouldJoin(user, { id: "r" }), true);
  assert.equal(A.shouldJoin(user, { id: "r" }, { aloneRoomId: "a" }), true);
  assert.equal(A.shouldJoin(user, { id: "r" }, { aloneRoomId: "a", roomId: "r" }), false);
  assert.equal(A.shouldJoin(user, { id: "r" }, { aloneRoomId: "a", roomId: "x" }), true);
  assert.equal(A.shouldJoin(user, { id: "a", alone: true }), false);
  assert.equal(A.shouldJoin(guest, { id: "r" }), false);
  assert.equal(A.shouldJoin(anonymous, { id: "r" }), false);
});

test("Leave: users choose fork or restore, the alone room has nothing to leave, others just stop", () => {
  assert.equal(A.leaveKind(user, false), "ask");
  assert.equal(A.leaveKind(user, true), "none");
  assert.equal(A.leaveKind(guest, false), "stop");
  assert.equal(A.leaveKind(anonymous, false), "stop");
  assert.equal(A.leaveKind(undefined, false), "stop");
});

test("the fork / restore question", () => {
  const leaving = A.leaveOffer("Lounge", false);
  assert.match(leaving.question, /Leave .Lounge./);
  assert.equal(leaving.cancellable, true);
  assert.notEqual(leaving.fork, leaving.restore);
  const deleted = A.leaveOffer("Lounge", true);
  assert.match(deleted.question, /was deleted/);
  assert.equal(deleted.cancellable, false);
  assert.match(A.leaveOffer("  ", false).question, /the room/);
});

test("Recents alone: the play history, songs from rooms say which room", () => {
  const alone = { id: "1", playedAt: 1, item: { id: "i" } };
  const inRoom = { id: "2", playedAt: 2, item: { id: "i" }, room: { id: "r1", name: "Lounge" } };
  const gone = { id: "3", playedAt: 3, item: { id: "i" }, room: { name: "Old" } };
  assert.equal(A.historyPlace(alone), "");
  assert.equal(A.historyPlace(inRoom), "in Lounge");
  assert.equal(A.historyPlace(gone), "in Old (deleted)");
  assert.equal(A.historyPlace({ ...inRoom, room: { id: "r", name: "" } }), "in a room");
  assert.equal(A.historyRoomPath(alone), undefined);
  assert.equal(A.historyRoomPath(inRoom), "/rooms/r1");
  assert.equal(A.historyRoomPath(gone), undefined);
  assert.equal(A.recentsFromHistory({ room: { alone: true } }), true);
  assert.equal(A.recentsFromHistory({ room: {} }), false);
  assert.equal(A.recentsFromHistory(undefined), false);
});

test("New Room moves a user's alone state into the new room", () => {
  assert.deepEqual(A.createRoomBody({ name: "N" }, user), { name: "N", moveAloneSession: true });
  assert.deepEqual(A.createRoomBody({ name: "N" }, guest), { name: "N" });
  assert.deepEqual(A.createRoomBody({ name: "N" }, undefined), { name: "N" });
});
