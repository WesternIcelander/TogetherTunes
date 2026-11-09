// The room page (/rooms/{idOrSlug}). The QR short link /r/{slug} redirects here with
// ?mode=remote (docs/rooms.md#web-and-app-playback), which opens the page in remote mode.
// For now this only shows the room and the mode it was opened in; the room page steps of M3
// (now playing, queue, listen and remote mode) build on it.
import { api, ApiError } from "./api.js";
import { h } from "./dom.js";
import { checkPasswordChange, element, loadMe, message, onReady, renderNav } from "./session.js";

/** A connection's mode (docs/rooms.md#web-and-app-playback). */
export type RoomMode = "listen" | "remote";

interface RoomInfo {
  id: string;
  slug: string;
  name: string;
}

/** The room from `/rooms/{idOrSlug}` (or `?room=` when the page is opened as a plain file). */
function roomFromLocation(): string {
  const m = /^\/rooms\/([^/]+)\/?$/.exec(location.pathname);
  if (m !== null && m[1] !== undefined) {
    try {
      return decodeURIComponent(m[1]);
    } catch {
      return "";
    }
  }
  return new URLSearchParams(location.search).get("room") ?? "";
}

/** The mode requested by the URL (`?mode=remote` from the short link), if any. */
export function modeFromLocation(): RoomMode | null {
  const mode = new URLSearchParams(location.search).get("mode");
  return mode === "remote" || mode === "listen" ? mode : null;
}

function notFound(): void {
  element("room-body").replaceChildren(h("h1", { text: "Not found" }),
    h("p", { text: "There is no room at this link." }));
}

async function start(): Promise<void> {
  let me;
  try {
    me = await loadMe();
  } catch (e) {
    element("room-status").textContent = message(e);
    return;
  }
  if (!checkPasswordChange(me)) return;
  renderNav(me);
  const ref = roomFromLocation();
  element("room-status").hidden = true;
  if (ref === "") {
    notFound();
    return;
  }
  try {
    const room = await api<RoomInfo>("GET", "/api/rooms/" + encodeURIComponent(ref));
    document.title = room.name + " · TogetherTunes";
    const mode = modeFromLocation() ?? "listen";
    const body = element("room-body");
    body.dataset["mode"] = mode;
    body.replaceChildren(h("h1", { text: room.name }),
      h("p", { id: "room-mode", text: mode === "remote" ? "Remote mode" : "Listen mode" }));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      notFound();
    } else {
      element("room-error").textContent = message(e);
    }
  }
}

onReady(start);
