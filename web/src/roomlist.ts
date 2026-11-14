// The rooms page's logic without the DOM (Node-tested by RoomsPageJsTest): labels, the room page's
// path, the create request's body, client-side slug hints, and which form field a create error
// belongs to.

/** A room as `GET /api/rooms` and `POST /api/rooms` return it (docs/api.md#rooms). */
export interface RoomListEntry {
  id: string;
  slug: string;
  name: string;
  owner: { kind: "user" | "guest"; id: string; name: string; isGuest: boolean };
  visibility: string;
  controlMode: string;
  createdAt: number;
  you: { role?: string; canEdit: boolean; canDelete: boolean; canModerate: boolean };
  /** People in the room now (only in the list). */
  listeners?: number;
}

export const VISIBILITY_LABELS: Record<string, string> = {
  public: "Public",
  unlisted: "Unlisted",
  private: "Private",
};

export const CONTROL_MODE_LABELS: Record<string, string> = {
  owner: "Owner picks",
  vote: "Vote",
  open: "Open",
};

export const ROLE_LABELS: Record<string, string> = {
  owner: "Owner",
  moderator: "Moderator",
  member: "Member",
};

export function visibilityLabel(v: string): string {
  return VISIBILITY_LABELS[v] ?? v;
}

export function controlModeLabel(m: string): string {
  return CONTROL_MODE_LABELS[m] ?? m;
}

/** The caller's role in the room, or "" when they have none (e.g. a public room they just visit). */
export function roleLabel(role: string | undefined): string {
  if (role === undefined) return "";
  return ROLE_LABELS[role] ?? role;
}

/** The room page's URL: `/rooms/{slug}`. */
export function roomPagePath(room: { slug: string }): string {
  return "/rooms/" + encodeURIComponent(room.slug);
}

export const SLUG_MIN = 3;
export const SLUG_MAX = 40;
export const NAME_MAX = 100;

const SLUG = /^[a-z0-9][a-z0-9-]*[a-z0-9]$/;
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The slug as the server reads it: trimmed and lower-cased. */
export function normalizeSlug(raw: string): string {
  return raw.trim().toLowerCase();
}

/**
 * Why the server would refuse this slug (`invalid_slug`), or null if it looks fine (it may still be
 * taken). An empty slug is fine: the server derives one from the name.
 */
export function slugProblem(raw: string): string | null {
  const s = normalizeSlug(raw);
  if (s === "") return null;
  if (s.length < SLUG_MIN || s.length > SLUG_MAX) {
    return SLUG_MIN + "–" + SLUG_MAX + " characters";
  }
  if (!SLUG.test(s)) {
    return "Only a–z, 0–9 and -, starting and ending with a letter or digit";
  }
  if (UUID_SHAPE.test(s)) {
    return "Can't look like a room id";
  }
  return null;
}

export interface CreateForm {
  name: string;
  slug: string;
  visibility: string;
  controlMode: string;
}

/** The `POST /api/rooms` body: the name trimmed, the slug left out when blank (derived by the server). */
export function createBody(form: CreateForm): { name: string; slug?: string; visibility: string; controlMode: string } {
  const body: { name: string; slug?: string; visibility: string; controlMode: string } = {
    name: form.name.trim(),
    visibility: form.visibility,
    controlMode: form.controlMode,
  };
  const slug = normalizeSlug(form.slug);
  if (slug !== "") body.slug = slug;
  return body;
}

export type CreateField = "name" | "slug" | "visibility" | "controlMode";

/** The form field an error code from `POST /api/rooms` is about, or null for a form-wide error. */
export function fieldForError(code: string): CreateField | null {
  switch (code) {
    case "invalid_name":
    case "name_too_long":
      return "name";
    case "invalid_slug":
    case "slug_taken":
      return "slug";
    case "invalid_visibility":
      return "visibility";
    case "invalid_control_mode":
      return "controlMode";
    default:
      return null;
  }
}

/** Whether the caller may create rooms (`rooms.create` in `GET /api/me`); guests and anonymous never. */
export function canCreateRooms(me: { kind: string; permissions?: string[] }): boolean {
  return me.kind === "user" && Array.isArray(me.permissions) && me.permissions.includes("rooms.create");
}
