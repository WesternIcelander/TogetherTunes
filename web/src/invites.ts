// Invites (docs/auth.md#invites, `GET`/`POST /api/invites`, `DELETE /api/invites/{id}`), without the
// DOM: the request body, its checks (the server checks them again) and how an invite is described.
// The user settings' Invites tab (account.ts) draws them.
import type { ActorRef } from "./actor.js";

/** An invite as the API lists it (never its code). */
export interface Invite {
  id: string;
  status: "pending" | "used" | "revoked" | "expired" | string;
  createdAt: number;
  expiresAt: number;
  note?: string;
  createdBy?: ActorRef;
  usedBy?: ActorRef;
  usedAt?: number;
  revokedAt?: number;
}

/** `POST /api/invites` → the invite with its code and registration link (shown only then). */
export interface CreatedInvite {
  invite: Invite;
  code: string;
  url: string;
}

/** How long a new invite stays usable: hours and label (the server takes 1 to 720 hours). */
export const EXPIRY_OPTIONS: readonly [number, string][] = [
  [1, "1 hour"],
  [24, "1 day"],
  [168, "7 days"],
  [720, "30 days"],
];

export const DEFAULT_EXPIRY_HOURS = 168;
export const MAX_NOTE = 200;

/** The body of `POST /api/invites`, or the problem with the input. */
export function inviteBody(expiresInHours: string | number, note: string):
  { body: { expiresInHours: number; note?: string } } | { error: string } {
  const hours = typeof expiresInHours === "number" ? expiresInHours : Number(expiresInHours);
  if (!Number.isInteger(hours) || hours < 1 || hours > 720) return { error: "Choose how long the invite lasts." };
  const n = note.trim();
  if (/[\r\n]/.test(n)) return { error: "The note must be one line." };
  if (n.length > MAX_NOTE) return { error: "The note is too long (at most " + MAX_NOTE + " characters)." };
  return { body: n === "" ? { expiresInHours: hours } : { expiresInHours: hours, note: n } };
}

const STATUS_TEXT: Record<string, string> = {
  pending: "Not used yet",
  used: "Used",
  revoked: "Revoked",
  expired: "Expired",
};

/** "Not used yet", "Used by Alice", "Revoked", "Expired" (unknown states as they are). */
export function inviteStatusText(invite: Invite): string {
  if (invite.status === "used" && invite.usedBy !== undefined) return "Used by " + invite.usedBy.name;
  return STATUS_TEXT[invite.status] ?? invite.status;
}

/** Only a pending invite can be revoked. */
export function canRevoke(invite: Invite): boolean {
  return invite.status === "pending";
}
