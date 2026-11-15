// The web player's copy of the stream's QR overlay (docs/ui.md "Show QR code", docs/overlay.md#the-web-players-copy):
// the layout from GET /api/rooms/{room}/overlay/layout (output-frame coordinates, from the server's own
// OverlayRenderer layout), when the stream shows the overlay right now, where the output frame lies over the
// displayed video or card, and the SVG markup. No DOM here, so Node tests can import it.

/** A rectangle in output-frame pixels (or page pixels for `frameRect`). */
export interface Rect { x: number; y: number; width: number; height: number }

/** `GET /api/rooms/{room}/overlay/layout`. */
export interface OverlayLayout {
  version: number;
  /** The room's `overlay.mode`: `off`, `always` or `idleOnly`. */
  mode: "off" | "always" | "idleOnly" | string;
  /** The server's `overlay.enabled`. */
  enabled: boolean;
  /** Whether the web player offers Show QR code (server switch on, mode not off, not an alone room). */
  available: boolean;
  frame: { width: number; height: number };
  corner: string;
  url: string;
  bounds: Rect;
  qr: Rect & { radius: number; modules: number; modulePx: number; quietZone: number; rows: string[] };
  text: {
    value: string;
    font: string;
    bold: boolean;
    size: number;
    x: number;
    baseline: number;
    advance: number;
    backdrop: Rect & { radius: number };
  };
  colors: { backdrop: string; modules: string; text: string };
}

/** The parts of the room state the rule reads (roomclient.ts RoomState). */
export interface OverlayStateLike {
  timeline: { state: string };
  current?: {
    status: string;
    audioOnly?: boolean;
    /** The entry's overlay decision as the stream uses it: `off`, `cards` or `video` (absent: unknown). */
    overlay?: string;
    item: { kind?: string; presentAsAudio?: boolean };
  };
}

/** Where the "Show QR code" choice is remembered (this browser only). */
export const SHOW_QR_KEY = "togethertunes.showQr";

/**
 * Whether the stream shows the QR overlay at this moment (the rule of OverlayCards / OverlayDecisions):
 * - the server's switch off, or mode `off`: never;
 * - nothing playing (the queue-empty card): in `always` and `idleOnly`;
 * - otherwise only in `always`: the paused card and the loading card (an entry not ready yet) always
 *   have it; a playing entry per its recorded decision: shown as a card (an audio item, a video played
 *   as audio or audio-only) with `cards` or `video`, played as video only with `video` (movies and shows
 *   are always `off`).
 */
export function overlayShown(state: OverlayStateLike | undefined, layout: OverlayLayout | undefined): boolean {
  if (state === undefined || layout === undefined || !layout.enabled) return false;
  const mode = layout.mode;
  if (mode !== "always" && mode !== "idleOnly") return false;
  const c = state.current;
  if (c === undefined || state.timeline.state === "idle") return true;
  if (mode !== "always") return false;
  if (state.timeline.state !== "playing") return true; // the paused card
  if (c.status !== "ready") return true; // the loading card
  const d = c.overlay;
  const asCard = c.audioOnly === true || c.item.kind !== "video" || c.item.presentAsAudio === true;
  return asCard ? d === "cards" || d === "video" : d === "video";
}

/** What the layout depends on in the room state: refetch it when this changes (slug, overlay settings). */
export function layoutKey(roomId: string, slug: string, settings: Record<string, unknown> | undefined): string {
  let overlay = "";
  try {
    overlay = JSON.stringify(settings?.["overlay"] ?? null);
  } catch {
    overlay = "";
  }
  return roomId + "|" + slug + "|" + overlay;
}

/**
 * Where the output frame lies on the page. `box` is the element's box; `media` the displayed media's
 * own size (a video's videoWidth/Height), or undefined for the card. The stream fits the video into
 * the frame (letterboxed or pillarboxed), so the frame is the output-aspect rectangle around the video
 * as it is displayed (object-fit: contain) in the box; a card fills a frame-aspect rectangle fitted
 * into the box. When the frame around the video wouldn't fit the box, the frame-aspect rectangle
 * fitted into the box is used.
 */
export function frameRect(box: Rect, frame: { width: number; height: number },
  media?: { width: number; height: number }): Rect {
  const aspect = frame.width / frame.height;
  const fitted = contain(box, aspect);
  if (media === undefined || media.width <= 0 || media.height <= 0) return fitted;
  const shown = contain(box, media.width / media.height);
  let w: number;
  let hgt: number;
  if (shown.width / shown.height < aspect) {
    hgt = shown.height;
    w = hgt * aspect;
  } else {
    w = shown.width;
    hgt = w / aspect;
  }
  if (w > box.width + 0.5 || hgt > box.height + 0.5) return fitted;
  return { x: shown.x + (shown.width - w) / 2, y: shown.y + (shown.height - hgt) / 2, width: w, height: hgt };
}

function contain(box: Rect, aspect: number): Rect {
  if (box.width <= 0 || box.height <= 0) return { x: box.x, y: box.y, width: 0, height: 0 };
  let w = box.width;
  let hgt = w / aspect;
  if (hgt > box.height) {
    hgt = box.height;
    w = hgt * aspect;
  }
  return { x: box.x + (box.width - w) / 2, y: box.y + (box.height - hgt) / 2, width: w, height: hgt };
}

/** An SVG path of the matrix's `1` modules in module units (one subpath per horizontal run). */
export function modulePath(rows: string[]): string {
  const parts: string[] = [];
  for (let y = 0; y < rows.length; y++) {
    const row = rows[y] ?? "";
    let x = 0;
    while (x < row.length) {
      if (row.charAt(x) !== "1") {
        x++;
        continue;
      }
      const start = x;
      while (x < row.length && row.charAt(x) === "1") x++;
      parts.push("M" + start + " " + y + "h" + (x - start) + "v1h" + (start - x) + "z");
    }
  }
  return parts.join("");
}

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** A CSS font-family list: the stream's font, then the closest common ones. */
export function fontFamily(font: string): string {
  const first = "'" + font.replace(/['\\]/g, "") + "'";
  return [first, "'Bitstream Vera Sans'", "Verdana", "'DejaVu Sans'", "'Segoe UI'", "Arial", "sans-serif"].join(", ");
}

/**
 * The overlay as SVG markup with a viewBox of the output frame, so it scales with the element it is put
 * in (placed at `frameRect`): the QR square and the text backdrop (rounded, translucent), the white
 * modules as crisp rects, and the text stretched to the stream's advance width.
 */
export function overlaySvg(l: OverlayLayout): string {
  const q = l.qr;
  const t = l.text;
  const b = t.backdrop;
  const m = q.modulePx;
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + l.frame.width + " " + l.frame.height
    + '" preserveAspectRatio="none" width="100%" height="100%" aria-hidden="true" focusable="false">'
    + '<rect x="' + q.x + '" y="' + q.y + '" width="' + q.width + '" height="' + q.height + '" rx="' + q.radius
    + '" ry="' + q.radius + '" fill="' + esc(l.colors.backdrop) + '"/>'
    + '<path class="qr-modules" shape-rendering="crispEdges" transform="translate(' + q.x + " " + q.y + ") scale(" + m
    + ')" fill="' + esc(l.colors.modules) + '" d="' + modulePath(q.rows) + '"/>'
    + '<rect x="' + b.x + '" y="' + b.y + '" width="' + b.width + '" height="' + b.height + '" rx="' + b.radius
    + '" ry="' + b.radius + '" fill="' + esc(l.colors.backdrop) + '"/>'
    + '<text x="' + t.x + '" y="' + t.baseline + '" font-size="' + t.size + '" font-weight="'
    + (t.bold ? "bold" : "normal") + '" font-family="' + esc(fontFamily(t.font)) + '" textLength="' + t.advance
    + '" lengthAdjust="spacingAndGlyphs" fill="' + esc(l.colors.text) + '">' + esc(t.value) + "</text></svg>";
}
