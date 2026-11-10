// Banning a guest from a room (`PUT /api/rooms/{room}/guest-bans/{guestId}`), after a confirm that
// shows the IP-mode warning (and the address, which only ip.view holders get). Used by the room
// page's history and the management view.
import { api } from "./api.js";
import type { ActorRef } from "./actor.js";

export interface GuestBan {
  guest: ActorRef;
  identifiedBy: "cookie" | "ip" | string;
  banned: boolean;
  bannedAt?: number;
  warning?: string;
  ip?: string;
}

/** Bans the guest after confirming; `false` if it was already banned or the user said no. */
export async function banGuestConfirmed(roomPath: string, guest: ActorRef): Promise<boolean> {
  const path = roomPath + "/guest-bans/" + encodeURIComponent(guest.id);
  const current = await api<GuestBan>("GET", path);
  if (current.banned) {
    alert(guest.name + " is already banned from this room.");
    return false;
  }
  let text = "Ban the guest " + guest.name + " from this room?";
  if (current.warning !== undefined) text += "\n\n" + current.warning;
  if (current.ip !== undefined) text += "\n\nIP address: " + current.ip;
  if (!confirm(text)) return false;
  await api<GuestBan>("PUT", path);
  return true;
}
