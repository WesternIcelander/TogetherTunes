// Guest -> account merge (`/api/me/merge-guest`, docs/auth.md "Guest upgrade"): after logging in
// or registering in a browser that was a guest, the guest's queue entries and votes can move to
// the account. Only the browser holding the guest cookie can do it (the server checks). DOM-free.
import { api } from "./api.js";
import type { ActorRef } from "./actor.js";

/** `POST /api/me/merge-guest`'s answer. `votes` counts votes and skip votes. */
export interface MergeResult {
  guest: ActorRef;
  moved: { entries: number; votes: number };
}

/** The guest this browser holds that could be merged into the logged-in account, if any. */
export async function mergeableGuest(): Promise<ActorRef | undefined> {
  return (await api<{ guest?: ActorRef }>("GET", "/api/me/merge-guest")).guest;
}

/** Merges the guest this browser holds into the logged-in account. */
export function mergeGuest(): Promise<MergeResult> {
  return api<MergeResult>("POST", "/api/me/merge-guest");
}

/** The question offering the merge. */
export function mergeQuestion(guest: ActorRef): string {
  return `This browser was the guest "${guest.name}". Move that guest's songs and votes in rooms to your account?`;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** A short summary of a finished merge. */
export function mergedText(result: MergeResult): string {
  return `Moved ${plural(result.moved.entries, "song", "songs")} and ${plural(result.moved.votes, "vote", "votes")}`
    + ` from the guest "${result.guest.name}" to your account.`;
}
