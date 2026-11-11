// Custom card images (docs/media-types.md#custom-card-images): the rules and texts the admin page
// and the room management view show. DOM-free; the server enforces every rule.

export interface CardImage { width: number; height: number; format: string; size: number; uploadedAt: number; url: string }
export interface CardWarning { code: string; message: string }
export interface CardScreen {
  screen: string;
  label: string;
  image?: CardImage;
  /** Room view only: the server's image for this screen (used where the room has none). */
  server?: { width: number; height: number };
  warnings: CardWarning[];
}
export interface CardsInfo {
  output: { profile: string; width: number; height: number };
  screens: CardScreen[];
  /** Room view only. */
  canEdit?: boolean;
  /** Room view only: whether the room's own images replace the server's (the owner's role allows it). */
  applies?: boolean;
}

/** What the file picker offers. */
export const CARD_ACCEPT = "image/png,image/jpeg,.png,.jpg,.jpeg";
/** The largest file the server accepts (CustomCards.MAX_BYTES). */
export const MAX_CARD_BYTES = 20 * 1024 * 1024;

export function serverCardsPath(): string {
  return "/api/admin/cards";
}

export function roomCardsPath(roomId: string): string {
  return "/api/rooms/" + encodeURIComponent(roomId) + "/cards";
}

/** `PUT`/`DELETE` path of one screen's image under `base` (one of the two above). */
export function screenPath(base: string, screen: string): string {
  return base + "/" + encodeURIComponent(screen);
}

/** The spec line shown above the screens. */
export function specText(output: CardsInfo["output"]): string {
  return `Images are shown exactly as given, with no text drawn on them. Best: 16:9, at least ${output.width}×${output.height}`
    + ` (the ${output.profile} output). Other sizes are scaled to fit, with black bars. PNG or JPEG, at most 20 MB.`;
}

/** What a screen shows now. `room`: the room's view (falls back to the server's image). */
export function screenStatus(s: CardScreen, room: boolean): string {
  if (s.image !== undefined) {
    return `${room ? "Room's image" : "Custom image"}, ${s.image.width}×${s.image.height} ${s.image.format.toUpperCase()}`;
  }
  if (room && s.server !== undefined) {
    return `Server's image, ${s.server.width}×${s.server.height}`;
  }
  return "Built-in card";
}

/** A note for the room view when the room's images don't apply (the owner's role doesn't allow them). */
export function roomNote(info: CardsInfo): string | undefined {
  if (info.applies === false && info.screens.some((s) => s.image !== undefined)) {
    return "The owner's role doesn't allow custom card images now: the stream shows the server's images or the built-in cards instead.";
  }
  return undefined;
}

/** A client-side check of a picked file before uploading (null: fine). The server checks again. */
export function checkFile(file: { size: number; type: string; name: string }): string | null {
  const name = file.name.toLowerCase();
  const typeOk = file.type === "image/png" || file.type === "image/jpeg"
    || (file.type === "" && /\.(png|jpe?g)$/.test(name));
  if (!typeOk) return "Pick a PNG or JPEG image.";
  if (file.size > MAX_CARD_BYTES) return "The image is larger than 20 MB.";
  if (file.size === 0) return "The file is empty.";
  return null;
}
