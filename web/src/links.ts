// Linked media (docs/library-and-ingest.md#linked-media): an item's music videos, lyric videos and
// external links. The labels ("Music Video 2"), the editor's list operations and checks (the same
// rules the server enforces on `PUT /api/items/{id}/links`), the save body, and which song-menu
// entries the links give. Pure logic (no DOM), run under Node in the tests
// (src/test/resources/web-tests/links.test.mjs); item.ts draws it.

export type LinkType = "music_video" | "lyric_video" | "external";

export const LINK_TYPES: readonly LinkType[] = ["music_video", "lyric_video", "external"];

/** Most links one item may have. */
export const MAX_LINKS = 20;
/** Longest external URL. */
export const MAX_URL = 2000;
/** Longest label (characters). */
export const MAX_LABEL = 100;

/** A linked item as the item page gives it. */
export interface LinkTarget {
  id: string;
  title: string;
  artist?: string;
  coverUrl?: string;
  status: string;
  mediaType: string;
}

/** One link of `GET /api/items/{id}` (`links`). */
export interface ItemLink {
  id?: string;
  type: LinkType;
  item?: LinkTarget;
  url?: string;
  label?: string;
}

/** "Music Video", "Lyric Video", "External link". */
export function linkTypeLabel(type: LinkType): string {
  switch (type) {
    case "music_video": return "Music Video";
    case "lyric_video": return "Lyric Video";
    default: return "External link";
  }
}

/**
 * Each link's name: its type, numbered from the second one when the list has several of that type
 * ("Music Video", "Music Video 2", "Lyric Video").
 */
export function linkNames(links: readonly ItemLink[]): string[] {
  const total = new Map<LinkType, number>();
  for (const l of links) total.set(l.type, (total.get(l.type) ?? 0) + 1);
  const seen = new Map<LinkType, number>();
  return links.map((l) => {
    const n = (seen.get(l.type) ?? 0) + 1;
    seen.set(l.type, n);
    const base = linkTypeLabel(l.type);
    return (total.get(l.type) ?? 0) > 1 && n > 1 ? base + " " + n : base;
  });
}

/** The URL's host ("www." dropped), or the URL itself if it can't be read. */
export function hostOf(url: string): string {
  const m = /^[a-z][a-z0-9+.-]*:\/\/(?:[^@/?#]*@)?([^/?#:]+)/i.exec(url.trim());
  if (m === null || m[1] === undefined) return url;
  return m[1].toLowerCase().replace(/^www\./, "");
}

/** What an external link shows: its label, else its host. */
export function externalText(link: Pick<ItemLink, "url" | "label">): string {
  const label = (link.label ?? "").trim();
  return label !== "" ? label : hostOf(link.url ?? "");
}

/** The cleaned label: control characters and runs of whitespace become one space, trimmed. */
export function cleanLabel(label: string): string {
  // eslint-disable-next-line no-control-regex
  return label.replace(/[\u0000-\u001f\u007f\s]+/g, " ").trim();
}

/** `text` as an external link's URL (trimmed), or undefined if it isn't an http(s) URL with a host. */
export function externalUrl(text: string): string | undefined {
  const s = text.trim();
  if (s === "" || s.length > MAX_URL) return undefined;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000- \u007f]/.test(s)) return undefined;
  const m = /^(https?):\/\/([^/?#]*)/i.exec(s);
  if (m === null || m[2] === undefined) return undefined;
  const authority = m[2];
  if (authority.indexOf("@") >= 0) return undefined;
  const host = authority.replace(/:\d*$/, "");
  if (host === "" || host === "[]") return undefined;
  return s;
}

/** The list with link `i` moved by `delta` (unchanged when it can't move). */
export function moveLink<T>(list: readonly T[], i: number, delta: number): T[] {
  const j = i + delta;
  const out = list.slice();
  if (i < 0 || i >= out.length || j < 0 || j >= out.length) return out;
  const moved = out[i] as T;
  out[i] = out[j] as T;
  out[j] = moved;
  return out;
}

/** The list without link `i`. */
export function removeLink<T>(list: readonly T[], i: number): T[] {
  return list.filter((_, k) => k !== i);
}

/**
 * Why `add` can't join `list` on item `itemId`, or undefined when it can: the count, a link to the
 * item itself, the same item or URL twice, a bad URL or label.
 */
export function addProblem(itemId: string, list: readonly ItemLink[], add: ItemLink): string | undefined {
  if (list.length >= MAX_LINKS) return "An item can have at most " + MAX_LINKS + " linked media.";
  if (add.type === "external") {
    const url = externalUrl(add.url ?? "");
    if (url === undefined) return "Enter a link starting with http:// or https://.";
    if (cleanLabel(add.label ?? "").length > MAX_LABEL) return "The label can be at most " + MAX_LABEL + " characters.";
    if (list.some((l) => l.type === "external" && l.url === url)) return "This link is already in the list.";
    return undefined;
  }
  if (add.item === undefined) return "Choose an item.";
  if (add.item.id === itemId) return "A song can't link to itself.";
  if (list.some((l) => l.item !== undefined && l.item.id === add.item?.id)) return "This item is already linked.";
  return undefined;
}

/** `PUT /api/items/{id}/links`'s body for the list. */
export function linksBody(list: readonly ItemLink[]): { links: { type: LinkType; itemId?: string; url?: string; label?: string }[] } {
  return {
    links: list.map((l) => {
      if (l.type === "external") {
        const label = cleanLabel(l.label ?? "");
        return label === "" ? { type: l.type, url: (l.url ?? "").trim() } : { type: l.type, url: (l.url ?? "").trim(), label };
      }
      return { type: l.type, itemId: l.item?.id ?? "" };
    }),
  };
}

/** Whether two lists save the same (type, target or URL, label, order). */
export function sameLinks(a: readonly ItemLink[], b: readonly ItemLink[]): boolean {
  return JSON.stringify(linksBody(a)) === JSON.stringify(linksBody(b));
}

/** What a `400 invalid_link` reason means, for the editor. */
export function invalidLinkText(reason: string | undefined): string {
  switch (reason) {
    case "bad_type": return "That isn't a type of linked media.";
    case "self_link": return "A song can't link to itself.";
    case "duplicate": return "The same item or link is in the list twice.";
    case "bad_url": return "An external link needs a link starting with http:// or https://.";
    case "bad_label": return "The label can be at most " + MAX_LABEL + " characters.";
    case "not_an_item": return "A video link needs an item from the library.";
    case "too_many": return "An item can have at most " + MAX_LINKS + " linked media.";
    default: return "These linked media can't be saved.";
  }
}

// ------------------------------------------------------------------ song menu entries

/** Where the song menu is open: in a room (requests) or alone (play now / queue). */
export interface LinkMenuContext {
  /** Alone (the user's alone room): Play now and Add to Queue; else a request in the room. */
  alone: boolean;
  /** A signed-in user (not a guest or anonymous visitor). */
  signedIn: boolean;
  /** May import YouTube links (`library.youtube`, `ytdlp.enabled`). */
  youtube: boolean;
}

/** One song-menu entry from the links. */
export type LinkMenuEntry =
  | { kind: "request" | "playNow" | "queue"; itemId: string; label: string }
  | { kind: "open"; url: string; label: string }
  | { kind: "findVideo"; label: string };

/**
 * The song menu's entries for a song's links (docs/library-and-ingest.md#linked-media): for each
 * music or lyric video, the same action the menu offers for the song itself, with that item (in a
 * room "Add Music Video to Queue" as a request; alone "Play Music Video now" and "Add Music Video to
 * Queue"), numbered when there are several of a type; each external link "Open <label or host>".
 * Without any music or lyric video, signed-in users who may import YouTube links get "Find a music
 * video…".
 */
export function linkMenuEntries(links: readonly ItemLink[], ctx: LinkMenuContext): LinkMenuEntry[] {
  const out: LinkMenuEntry[] = [];
  const names = linkNames(links);
  let videos = 0;
  links.forEach((l, i) => {
    const name = names[i] ?? linkTypeLabel(l.type);
    if (l.type === "external") {
      if (l.url !== undefined) out.push({ kind: "open", url: l.url, label: "Open " + externalText(l) });
      return;
    }
    if (l.item === undefined) return;
    videos++;
    if (ctx.alone) {
      out.push({ kind: "playNow", itemId: l.item.id, label: "Play " + name + " now" });
      out.push({ kind: "queue", itemId: l.item.id, label: "Add " + name + " to Queue" });
    } else {
      out.push({ kind: "request", itemId: l.item.id, label: "Add " + name + " to Queue" });
    }
  });
  if (videos === 0 && ctx.signedIn && ctx.youtube) out.push({ kind: "findVideo", label: "Find a music video…" });
  return out;
}
