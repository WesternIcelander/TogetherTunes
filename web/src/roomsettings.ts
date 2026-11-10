// The room settings as the management view edits them (docs/rooms.md#room-settings-settings_json).
// No DOM: the definitions, reading and writing nested values by dotted name, turning form input
// into values, the merge patch for `PATCH /api/rooms/{id} {settings}`, and which settings the
// server has turned off (`GET /api/rooms/{id}/server-switches`). Node-tested (roomsettings.test.mjs).

export type SettingKind = "bool" | "int" | "select" | "text" | "threshold";

export interface SettingDef {
  /** The dotted name (`guests.canVote`); nested objects on the wire. */
  key: string;
  label: string;
  kind: SettingKind;
  group: string;
  default: boolean | number | string;
  min?: number;
  max?: number;
  /** For `select`: [value, label]. */
  options?: Array<[string, string]>;
  help?: string;
}

export const GROUPS: Array<[string, string]> = [
  ["requests", "Requests and voting"],
  ["guests", "Guests"],
  ["playback", "Playback"],
  ["overlay", "QR overlay"],
  ["outputs", "Outputs"],
];

/** Every room setting, in the order the view shows them. */
export const SETTINGS: SettingDef[] = [
  { key: "libraryRequests", label: "Allow library requests", kind: "bool", group: "requests", default: true,
    help: "Off: only you and moderators can search libraries and request items from them. YouTube links still work; uploads need library requests, so they turn off too." },
  { key: "uploadRequests", label: "Allow uploads as requests", kind: "bool", group: "requests", default: true,
    help: "Off: only you and moderators can request by uploading a file. Needs library requests on." },
  { key: "requestsPerActor", label: "Waiting requests per person", kind: "int", group: "requests", default: 3,
    min: 1, max: 1000 },
  { key: "noRepeatWindow", label: "No repeat within the last N items (0 = off)", kind: "int", group: "requests",
    default: 10, min: 0, max: 1000 },
  { key: "allowDownvotes", label: "Allow downvotes", kind: "bool", group: "requests", default: false },
  { key: "voteSkipThreshold", label: "Vote to skip: share of listeners needed (%)", kind: "threshold",
    group: "requests", default: 0.5, help: "Vote mode only. Empty or Off turns vote-skip off." },
  { key: "guests.canVote", label: "Guests can vote", kind: "bool", group: "guests", default: true },
  { key: "guests.canRequestLibrary", label: "Guests can request from the library", kind: "bool", group: "guests",
    default: true },
  { key: "guests.canUpload", label: "Guests can upload files", kind: "bool", group: "guests", default: false },
  { key: "guests.canYoutube", label: "Guests can add YouTube links", kind: "bool", group: "guests", default: false },
  { key: "transitionCard", label: "Show a card between items", kind: "bool", group: "playback", default: false },
  { key: "gapMs", label: "Gap between items (ms)", kind: "int", group: "playback", default: 0, min: 0, max: 10000 },
  { key: "audioOnly", label: "Audio-only room", kind: "bool", group: "playback", default: false,
    help: "Only audio plays, for listeners and in the stream outputs (which show each item's card at a lower"
      + " constant rate). A change applies from the next item." },
  { key: "overlay.enabled", label: "Show the QR code overlay", kind: "bool", group: "overlay", default: false },
  { key: "overlay.corner", label: "Corner", kind: "select", group: "overlay", default: "br",
    options: [["br", "bottom right"], ["bl", "bottom left"], ["tr", "top right"], ["tl", "top left"]] },
  { key: "overlay.heightPct", label: "Height (% of the frame)", kind: "int", group: "overlay", default: 22,
    min: 5, max: 50 },
  { key: "overlay.margin", label: "Margin (% of the frame height)", kind: "int", group: "overlay", default: 3,
    min: 0, max: 20 },
  { key: "outputs.rtmp.playbackUrl", label: "RTMP playback URL for viewers", kind: "text", group: "outputs",
    default: "", max: 2048, help: "The URL your RTMP service gives viewers. Empty for none." },
  { key: "outputs.rtmp.idleStopMinutes", label: "Stop the RTMP push after the queue was empty for (minutes)",
    kind: "int", group: "outputs", default: 10, min: 1, max: 1440,
    help: "The push turns off and stays off until you turn it on again on the room page." },
  { key: "outputs.ts.join", label: ".ts viewers join at", kind: "select", group: "outputs", default: "next",
    options: [["next", "the next unit (clean start)"], ["latest", "the latest unit (less delay)"]] },
  { key: "estimatedDelayMs.rtmp", label: "Estimated RTMP delay (ms)", kind: "int", group: "outputs", default: 5000,
    min: 0, max: 120000 },
  { key: "estimatedDelayMs.ts", label: "Estimated .ts delay (ms)", kind: "int", group: "outputs", default: 2000,
    min: 0, max: 120000 },
  { key: "estimatedDelayMs.hls", label: "Estimated HLS delay (ms)", kind: "int", group: "outputs", default: 8000,
    min: 0, max: 120000 },
];

/**
 * Uploads need library requests (docs/rooms.md#uploads-need-library-requests): [upload switch,
 * the library-request switch it needs]. The same pairs hold for the server's guest settings.
 */
export const COUPLED: Array<[string, string]> = [
  ["uploadRequests", "libraryRequests"],
  ["guests.canUpload", "guests.canRequestLibrary"],
];

/** A change made on top of the user's to keep uploads coupled, with the notice to show. */
export interface CoupledChange { key: string; value: boolean; notice: string }

/**
 * After a switch `key` was set to `value` in a form (labels from `labelOf`), the companion change
 * that keeps uploads coupled to library requests, or null: turning an upload switch on while its
 * library switch is off turns that on; turning a library switch off while its upload switch is
 * on turns that off. `current` reads the form's value of a switch.
 */
export function coupledChange(key: string, value: boolean, current: (key: string) => unknown,
  labelOf: (key: string) => string = settingLabel): CoupledChange | null {
  for (const [upload, library] of COUPLED) {
    if (key === upload && value && current(library) !== true) {
      return { key: library, value: true, notice: couplingNotice(library, true, labelOf) };
    }
    if (key === library && !value && current(upload) === true) {
      return { key: upload, value: false, notice: couplingNotice(upload, false, labelOf) };
    }
  }
  return null;
}

/** The notice for the settings the server changed on top of a patch (`adjusted` of the response). */
export function adjustedNotice(adjusted: unknown, after: (key: string) => unknown,
  labelOf: (key: string) => string = settingLabel): string | null {
  if (!Array.isArray(adjusted) || adjusted.length === 0) return null;
  return adjusted.filter((k): k is string => typeof k === "string")
    .map((k) => couplingNotice(k, after(k) === true, labelOf)).join(" ");
}

function couplingNotice(key: string, on: boolean, labelOf: (key: string) => string): string {
  return "Uploads need library requests: turned \"" + labelOf(key) + "\" " + (on ? "on" : "off") + " too.";
}

function settingLabel(key: string): string {
  const def = SETTINGS.find((d) => d.key === key);
  return def === undefined ? key : def.label;
}

/** `GET /api/rooms/{id}/server-switches`. */
export interface ServerSwitches {
  guests: { enabled: boolean; canVote: boolean; canRequestLibrary: boolean; canUpload: boolean; canYoutube: boolean };
  overlay: { enabled: boolean };
  outputs: { rtmp: { enabled: boolean; maxIdleStopMinutes?: number }; ts: { enabled: boolean }; hls: { enabled: boolean } };
}

/**
 * A note on a setting the server caps: `outputs.rtmp.idleStopMinutes` applies up to the server's
 * `outputs.rtmp.maxIdleStopMinutes`; a higher value is saved but acts as the maximum (clamped).
 */
export function serverCapNote(key: string, value: unknown, sw: ServerSwitches | undefined): string | null {
  if (key !== "outputs.rtmp.idleStopMinutes" || sw === undefined) return null;
  const max = sw.outputs.rtmp.maxIdleStopMinutes;
  if (typeof max !== "number") return null;
  const note = "This server stops idle pushes after at most " + max + (max === 1 ? " minute" : " minutes");
  return typeof value === "number" && value > max ? note + ", so " + max + " applies." : note + ".";
}

export const SERVER_DISABLED = "disabled by the server admin";

/**
 * Why the server keeps this room setting from having any effect, or null if it doesn't: the
 * effective value is server AND room, so such a setting is shown greyed out.
 */
export function serverDisabledReason(key: string, sw: ServerSwitches | undefined): string | null {
  if (sw === undefined) return null;
  if (key.startsWith("guests.")) {
    if (!sw.guests.enabled) return "Guests are " + SERVER_DISABLED;
    const cap = key.substring("guests.".length) as keyof ServerSwitches["guests"];
    return sw.guests[cap] === false ? capitalise(SERVER_DISABLED) : null;
  }
  if (key.startsWith("overlay.")) {
    return sw.overlay.enabled ? null : "The overlay is " + SERVER_DISABLED;
  }
  for (const kind of ["rtmp", "ts", "hls"] as const) {
    if (key === "estimatedDelayMs." + kind || key.startsWith("outputs." + kind + ".")) {
      return sw.outputs[kind].enabled ? null : OUTPUT_NAMES[kind] + " output is " + SERVER_DISABLED;
    }
  }
  return null;
}

const OUTPUT_NAMES = { rtmp: "The RTMP", ts: "The .ts", hls: "The HLS" };

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.substring(1);
}

/** The value at a dotted name in a nested object, or undefined. */
export function getPath(obj: unknown, key: string): unknown {
  let cur: unknown = obj;
  for (const part of key.split(".")) {
    if (cur === null || typeof cur !== "object") return undefined;
    cur = (cur as Record<string, unknown>)[part];
  }
  return cur;
}

/** Sets the value at a dotted name, creating nested objects on the way. */
export function setPath(obj: Record<string, unknown>, key: string, value: unknown): void {
  const parts = key.split(".");
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const part = parts[i] as string;
    const next = cur[part];
    if (next === null || typeof next !== "object") {
      const created: Record<string, unknown> = {};
      cur[part] = created;
      cur = created;
    } else {
      cur = next as Record<string, unknown>;
    }
  }
  cur[parts[parts.length - 1] as string] = value;
}

/** A threshold (0, 1] as the percentage shown in the form ("" for off). */
export function thresholdToPercent(value: unknown): string {
  if (typeof value !== "number") return "";
  return String(Math.round(value * 10000) / 100);
}

/**
 * What the form's input means for this setting. `raw` is the checkbox state for `bool`, the
 * field's text otherwise. Throws an Error naming the setting when the input is invalid.
 */
export function parseInput(def: SettingDef, raw: string | boolean): boolean | number | string {
  switch (def.kind) {
    case "bool":
      return raw === true;
    case "select": {
      const v = String(raw);
      if (def.options !== undefined && !def.options.some((o) => o[0] === v)) {
        throw new Error(def.label + ": choose one of the options");
      }
      return v;
    }
    case "text": {
      const v = String(raw).trim();
      if (def.max !== undefined && v.length > def.max) {
        throw new Error(def.label + ": at most " + def.max + " characters");
      }
      return v;
    }
    case "int": {
      const v = String(raw).trim();
      if (!/^-?\d+$/.test(v)) throw new Error(def.label + ": enter a whole number");
      const n = Number(v);
      if ((def.min !== undefined && n < def.min) || (def.max !== undefined && n > def.max)) {
        throw new Error(def.label + ": " + def.min + " to " + def.max);
      }
      return n;
    }
    case "threshold": {
      const v = String(raw).trim().replace(",", ".");
      if (v === "" || v.toLowerCase() === "off") return "off";
      if (!/^\d+(\.\d+)?$/.test(v)) throw new Error(def.label + ": a percentage above 0, at most 100, or empty");
      const pct = Number(v);
      if (!(pct > 0) || pct > 100) throw new Error(def.label + ": a percentage above 0, at most 100, or empty");
      return Math.round(pct * 100) / 10000;
    }
  }
}

/** Whether two setting values are the same (numbers compared with a little slack for percentages). */
function same(a: unknown, b: unknown): boolean {
  if (typeof a === "number" && typeof b === "number") return Math.abs(a - b) < 1e-9;
  return a === b;
}

/**
 * The merge patch for `PATCH /api/rooms/{id} {settings}`: only the settings whose value differs
 * from `current`, nested by name. Null when nothing changed.
 */
export function buildPatch(current: unknown, values: Record<string, unknown>): Record<string, unknown> | null {
  const patch: Record<string, unknown> = {};
  let changed = false;
  for (const def of SETTINGS) {
    if (!(def.key in values)) continue;
    const v = values[def.key];
    if (same(getPath(current, def.key), v)) continue;
    setPath(patch, def.key, v);
    changed = true;
  }
  return changed ? patch : null;
}

/** The default as the form shows it ("off" / percentages for the threshold). */
export function describeDefault(def: SettingDef): string {
  if (def.kind === "bool") return def.default ? "on" : "off";
  if (def.kind === "threshold") return thresholdToPercent(def.default) + "%";
  if (def.kind === "select" && def.options !== undefined) {
    const o = def.options.find((x) => x[0] === def.default);
    if (o !== undefined) return o[1];
  }
  if (def.kind === "text" && def.default === "") return "none";
  return String(def.default);
}

/**
 * The roles a manager may give in the member list: the owner any role but owner (ownership is
 * transferred separately), moderators only member or banned.
 */
export function assignableRoles(viewerRole: string | undefined): string[] {
  if (viewerRole === "owner") return ["moderator", "member", "banned"];
  if (viewerRole === "moderator") return ["member", "banned"];
  return [];
}

/**
 * Whether the viewer may change this member's role or remove them: never their own row (except
 * leaving, handled apart) or the owner's; moderators only members and banned users.
 */
export function canManageMember(viewerRole: string | undefined, viewerId: string, member: { id: string; role: string })
  : boolean {
  if (member.id === viewerId || member.role === "owner") return false;
  if (viewerRole === "owner") return true;
  if (viewerRole === "moderator") return member.role === "member" || member.role === "banned";
  return false;
}
