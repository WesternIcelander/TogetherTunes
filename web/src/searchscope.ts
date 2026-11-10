// The "Search the whole server" tickbox shared by the Library page and a room's request search
// (`GET /api/items/search`, `GET /api/rooms/{id}/search?all=1`): unchecked by default, remembered
// per browser. Storage can be missing or throw (private windows, blocked site data), so this is
// best effort. No DOM here, so Node tests can import it.

const KEY = "tt.searchWholeServer";

/** Whether this browser last left the tickbox checked. */
export function rememberedWholeServer(): boolean {
  try {
    return localStorage.getItem(KEY) === "1";
  } catch {
    return false;
  }
}

/** Remembers the tickbox. */
export function rememberWholeServer(on: boolean): void {
  try {
    if (on) localStorage.setItem(KEY, "1");
    else localStorage.removeItem(KEY);
  } catch {
    // not remembered
  }
}
