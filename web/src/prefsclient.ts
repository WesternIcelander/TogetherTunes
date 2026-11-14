// The page's one preferences store (prefs.ts) on `/api/me/settings`. Every view module imports the
// same instance (only view modules are imported under `?mount=`), so the frame, the room's player and
// the settings view share it.
import { api } from "./api.js";
import { PrefsStore, type Prefs } from "./prefs.js";

export const prefs = new PrefsStore(
  () => api<unknown>("GET", "/api/me/settings"),
  (patch: Partial<Prefs>) => api<unknown>("PATCH", "/api/me/settings", patch));
