// The room WebSocket (docs/api.md#websocket): one socket per page, joined to one room.
//
// RoomConnection keeps the socket open (reconnecting with a growing delay), joins the room after
// every (re)connect, sends requests with a `rid` and matches the `ack` / `error` replies, and
// keeps the room state up to date from `state` (full) and the `timeline` / `queue` / `presence`
// deltas. Deltas carry consecutive versions; a gap means a message was missed, and the client asks
// for the full state again (`sync`).
//
// Identity: actions that need one (votes, requests, vote-skip) are refused from an anonymous socket
// with `identity_required`. `request()` then creates the guest (`POST /api/me/guest`), redeems the
// returned ticket on the open socket and retries once (see `ensureIdentity`).
import { api, ApiError } from "./api.js";
import type { ActorRef } from "./actor.js";
import { clientNow } from "./clocksync.js";
import type { IngestStatus } from "./jobtext.js";
import { applyOutputStatus } from "./outputs.js";

export type { RoomMode } from "./remote.js";
import type { OutputView, RoomMode } from "./remote.js";

/** `PlaybackState` on the wire. Unknown states are treated like `paused` (docs/rooms.md#room-state). */
export type TimelineState = "idle" | "playing" | "paused" | string;

export interface Timeline {
  state: TimelineState;
  entryId?: string;
  anchorServerMs: number;
  anchorPositionMs: number;
}

/** An item as room messages carry it; the media URLs only once it is ready. */
export interface RoomItem {
  id: string;
  kind?: "audio" | "video";
  /** Derived from the media type: a video item of type `audio`. */
  presentAsAudio?: boolean;
  /** docs/media-types.md: audio, uncategorized, music_video, movie or show. */
  mediaType?: string;
  /** Type `show` only. */
  show?: { name: string; season?: number; episode: number };
  title?: string;
  artist?: string;
  durationMs?: number;
  status?: "pending" | "processing" | "ready" | "failed" | "purged" | string;
  /** `youtube` or `upload`: only a failed YouTube item can be retried. */
  source?: "youtube" | "upload" | string;
  coverUrl?: string;
  audioUrl?: string;
  videoUrl?: string;
  originalUrl?: string;
  originalGainDb?: number;
  /** The original's MIME type, for `canPlayType`. */
  originalType?: string;
}

export type EntryStatus = "ready" | "processing" | "failed" | "purged" | string;

export interface CurrentEntry {
  entryId: string;
  status: EntryStatus;
  item: RoomItem;
  addedBy?: ActorRef;
  addedAt: number;
  /** Plays audio-only (an audio-only room): `item` then has no `videoUrl`. */
  audioOnly?: boolean;
}

export interface WaitingEntry extends CurrentEntry {
  /** `manual` (Requests) or `autoplay` (From Playlist). */
  section?: "manual" | "autoplay" | string;
  votes: number;
  pinned: boolean;
  yourVote: number;
}

export interface HistoryEntry {
  entryId: string;
  state: "played" | "skipped" | string;
  item: RoomItem;
  addedBy?: ActorRef;
  via?: string;
  playedAt: number;
}

/** Someone identified in the room, with how they follow it (`listen`, or `remote`: requests-only mode). */
export interface PresentPerson { actor: ActorRef; mode: "listen" | "remote" | string }

export interface Presence {
  listening: number;
  remote: number;
  anonymous: number;
  /** The identified people present, in the order they came (anonymous visitors are only counted). */
  people?: PresentPerson[];
}

export interface SkipVotes { entryId: string; votes: number; needed: number }

export interface You {
  role?: "owner" | "moderator" | "member" | "banned" | string;
  identified: boolean;
  canVote: boolean;
  canRequest: boolean;
  canUpload: boolean;
  canYoutube: boolean;
  canSkip: boolean;
  canModerate: boolean;
  /** May request items by id (library search, the history's Add to Queue). */
  canRequestLibrary?: boolean;
  /** Banned or timed out here: read-only (may listen, nothing else); `until` ends a timeout. */
  restriction?: { kind: "ban" | "timeout" | string; until?: number };
}

export interface RoomSettingsView {
  libraryRequests?: boolean;
  allowDownvotes?: boolean;
  voteSkipThreshold?: number | "off";
  /** An audio-only room (docs/rooms.md#audio-only-rooms). */
  audioOnly?: boolean;
  [key: string]: unknown;
}

export interface RoomInfo {
  id: string;
  slug: string;
  name: string;
  visibility: "public" | "unlisted" | "private" | string;
  controlMode: "owner" | "vote" | "open" | string;
  ownerId: string;
  settings: RoomSettingsView;
  you: You;
  /** The user's alone room (docs/rooms.md#alone). */
  alone?: boolean;
}

/** The room as the page knows it (the `state` message, with deltas applied). */
export interface RoomState {
  version: number;
  room: RoomInfo;
  timeline: Timeline;
  current?: CurrentEntry;
  queue: WaitingEntry[];
  suggestions: WaitingEntry[];
  history: HistoryEntry[];
  skipVotes?: SkipVotes;
  presence: Presence;
  /** The active program outputs with their status and estimated delays (`output.status` updates them). */
  outputs?: OutputView[];
}

export interface NewRequest {
  entryId: string;
  item: RoomItem;
  by?: ActorRef;
  via: "library" | "upload" | "youtube" | "history" | string;
}

/** What the page is told about. */
export interface RoomEvents {
  /** The state changed (full state or a delta was applied). */
  state(state: RoomState): void;
  /** Connected and joined (`true`), or the connection was lost and is being retried (`false`). */
  connection(connected: boolean): void;
  /** Taken out of the room. `final`: the page shouldn't expect to get back in by itself. */
  kicked(reason: string, final: boolean): void;
  /** `request.new` (owner and moderators). */
  request(request: NewRequest): void;
  /** The socket's identity changed (the guest it became, or the user it was opened as). */
  identity(actor: ActorRef | undefined): void;
  /**
   * Asked before a new guest identity is used for the first time when custom names are allowed:
   * resolves with the name to use (the generated one, or one the user typed), or rejects to cancel.
   */
  chooseGuestName(generated: string): Promise<string>;
  /** A `time` reply (clock sync, docs/rooms.md#clock-sync-web-and-app). */
  time?(t0: number, ts: number, t1: number): void;
  /** An `ingest` message: what the ingest of the entries' items that aren't ready is doing. */
  ingest?(items: Record<string, IngestStatus>): void;
}

/** Where a timeline is at `serverNowMs` (docs/rooms.md#room-state). */
export function positionAt(t: Timeline, serverNowMs: number): number {
  if (t.state === "playing") return Math.max(0, t.anchorPositionMs + (serverNowMs - t.anchorServerMs));
  return t.anchorPositionMs; // paused, idle and unknown states freeze the position
}

const RECONNECT_DELAYS_MS = [500, 1000, 2000, 5000, 10000];

interface Pending {
  resolve(value: Record<string, unknown>): void;
  reject(e: unknown): void;
  /** `request.progress` for this request (a YouTube lookup's stages). */
  progress?(stage: string, text: string): void;
}

interface GuestCreated extends ActorRef { identityTicket: string; nameChosen: boolean; customNames: boolean }

export class RoomConnection {
  readonly room: string;
  mode: RoomMode;
  state: RoomState | undefined;
  /** The last `ingest` message's items, by item id (docs/api.md#websocket). */
  ingest: Record<string, IngestStatus> = {};
  /** The socket's actor: the user, or the guest once identified; undefined while anonymous. */
  actor: ActorRef | undefined;
  #events: RoomEvents;
  #ws: WebSocket | undefined;
  #nextRid = 1;
  #pending = new Map<number, Pending>();
  #attempt = 0;
  #stopped = false;
  #joined = false;
  #reconnectTimer: number | undefined;
  #identifying: Promise<void> | undefined;

  constructor(room: string, mode: RoomMode, events: RoomEvents) {
    this.room = room;
    this.mode = mode;
    this.#events = events;
  }

  get joined(): boolean {
    return this.#joined;
  }

  connect(): void {
    if (this.#stopped) return;
    const url = (location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws";
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      this.#scheduleReconnect();
      return;
    }
    this.#ws = ws;
    ws.onopen = () => {
      this.#request({ type: "join", room: this.room, mode: this.mode }).then(() => {
        this.#attempt = 0;
        this.#joined = true;
        this.#events.connection(true);
      }).catch((e: unknown) => {
        // The room is gone, private, or banned us: nothing to retry.
        const code = e instanceof ApiError ? e.code : "join_failed";
        if (e instanceof ApiError && (e.status === 403 || e.status === 404)) {
          this.stop();
          this.#events.kicked(code, true);
        } else {
          ws.close();
        }
      });
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data !== "string") return;
      let msg: Record<string, unknown>;
      try {
        msg = JSON.parse(ev.data) as Record<string, unknown>;
      } catch {
        return;
      }
      if (msg === null || typeof msg !== "object") return;
      this.#onMessage(msg);
    };
    ws.onclose = () => {
      if (this.#ws !== ws) return;
      this.#ws = undefined;
      this.#joined = false;
      for (const p of this.#pending.values()) p.reject(new ApiError(0, "network_error", "Connection lost"));
      this.#pending.clear();
      if (!this.#stopped) {
        this.#events.connection(false);
        this.#scheduleReconnect();
      }
    };
  }

  /** Closes the socket for good. */
  stop(): void {
    this.#stopped = true;
    if (this.#reconnectTimer !== undefined) window.clearTimeout(this.#reconnectTimer);
    const ws = this.#ws;
    this.#ws = undefined;
    this.#joined = false;
    ws?.close();
  }

  #scheduleReconnect(): void {
    if (this.#stopped || this.#reconnectTimer !== undefined) return;
    const delay = RECONNECT_DELAYS_MS[Math.min(this.#attempt, RECONNECT_DELAYS_MS.length - 1)] ?? 10000;
    this.#attempt++;
    this.#reconnectTimer = window.setTimeout(() => {
      this.#reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  /** Sends a message without waiting for a reply. */
  send(msg: Record<string, unknown>): boolean {
    const ws = this.#ws;
    if (ws === undefined || ws.readyState !== WebSocket.OPEN) return false;
    ws.send(JSON.stringify(msg));
    return true;
  }

  /** Sends `{type: "time", t0}` (clock sync; `t0` from `clientNow()`). */
  sendTime(t0: number = clientNow()): boolean {
    return this.send({ type: "time", t0 });
  }

  #request(msg: Record<string, unknown>, progress?: (stage: string, text: string) => void):
    Promise<Record<string, unknown>> {
    return new Promise((resolve, reject) => {
      const rid = this.#nextRid++;
      if (!this.send({ ...msg, rid })) {
        reject(new ApiError(0, "network_error", "Not connected to the room"));
        return;
      }
      this.#pending.set(rid, { resolve, reject, progress });
    });
  }

  /**
   * Sends a room action and resolves with its `ack` (or rejects with an ApiError). An
   * `identity_required` refusal creates the guest identity and retries once. `progress` gets the
   * request's `request.progress` messages (a `queue.add` YouTube lookup's stages).
   */
  async request(msg: Record<string, unknown>, progress?: (stage: string, text: string) => void):
    Promise<Record<string, unknown>> {
    try {
      return await this.#request(msg, progress);
    } catch (e) {
      if (!(e instanceof ApiError) || e.code !== "identity_required") throw e;
    }
    await this.ensureIdentity();
    return await this.#request(msg, progress);
  }

  /**
   * Makes sure the socket acts as a user or guest: creates the guest (or gets the existing one,
   * e.g. after an upload created it), lets the user pick a name when custom names are allowed,
   * and redeems the identity ticket on this socket.
   */
  ensureIdentity(): Promise<void> {
    if (this.actor !== undefined && this.state?.room.you.identified !== false) return Promise.resolve();
    if (this.#identifying === undefined) {
      this.#identifying = this.#identify().finally(() => { this.#identifying = undefined; });
    }
    return this.#identifying;
  }

  async #identify(): Promise<void> {
    let guest = await api<GuestCreated>("POST", "/api/me/guest");
    let ticket = guest.identityTicket;
    if (guest.customNames && !guest.nameChosen) {
      const name = (await this.#events.chooseGuestName(guest.name)).trim();
      if (name !== "" && name !== guest.name) {
        // Renaming returns a fresh ticket too.
        guest = await api<GuestCreated>("POST", "/api/me/guest", { name });
        ticket = guest.identityTicket;
      }
    }
    try {
      await this.#request({ type: "identity", ticket });
    } catch (e) {
      // Already this guest (e.g. a reconnect after the cookie was set): fine.
      if (!(e instanceof ApiError) || e.code !== "already_identified") throw e;
    }
    this.actor = { kind: "guest", id: guest.id, name: guest.name, isGuest: true };
    this.#events.identity(this.actor);
  }

  #onMessage(msg: Record<string, unknown>): void {
    switch (msg["type"]) {
      case "ack": case "error": {
        const rid = msg["rid"];
        const p = typeof rid === "number" ? this.#pending.get(rid) : undefined;
        if (p === undefined) return;
        this.#pending.delete(rid as number);
        if (msg["type"] === "ack") {
          p.resolve(msg);
        } else {
          p.reject(new ApiError(Number(msg["status"] ?? 0), String(msg["code"] ?? "error"),
            String(msg["message"] ?? "Request failed")));
        }
        return;
      }
      case "request.progress": {
        const rid = msg["rid"];
        const p = typeof rid === "number" ? this.#pending.get(rid) : undefined;
        if (p?.progress !== undefined) p.progress(String(msg["stage"] ?? ""), String(msg["text"] ?? ""));
        return;
      }
      case "ingest": {
        // Not a room change (no version): kept beside the state until the next one.
        const items = msg["items"];
        this.ingest = items !== null && typeof items === "object" ? items as Record<string, IngestStatus> : {};
        if (this.#events.ingest !== undefined) this.#events.ingest(this.ingest);
        return;
      }
      case "time": {
        const t0 = Number(msg["t0"]);
        const ts = Number(msg["ts"]);
        if (this.#events.time !== undefined && isFinite(t0) && isFinite(ts)) this.#events.time(t0, ts, clientNow());
        return;
      }
      case "identity": {
        this.actor = msg["actor"] as ActorRef | undefined;
        this.#events.identity(this.actor);
        return;
      }
      case "state": {
        this.state = msg as unknown as RoomState;
        this.#events.state(this.state);
        return;
      }
      case "timeline": case "queue": case "presence": {
        this.#applyDelta(msg);
        return;
      }
      case "output.status": {
        // Not a room change (no version): applied to the state as it is.
        const s = this.state;
        if (s === undefined) return;
        s.outputs = applyOutputStatus(s.outputs, msg);
        this.#events.state(s);
        return;
      }
      case "request.new": {
        this.#events.request(msg["request"] as NewRequest);
        return;
      }
      case "kicked": {
        const reason = String(msg["reason"] ?? "");
        this.#joined = false;
        this.state = undefined;
        if (reason === "room_closed") {
          // The room's executor restarted: join again.
          this.#events.connection(false);
          this.#request({ type: "join", room: this.room, mode: this.mode }).then(() => {
            this.#joined = true;
            this.#events.connection(true);
          }).catch(() => this.#ws?.close());
          return;
        }
        this.stop();
        this.#events.kicked(reason, true);
        return;
      }
      default:
        // Unknown types (newer servers) are no-ops.
        return;
    }
  }

  #applyDelta(msg: Record<string, unknown>): void {
    const s = this.state;
    const version = Number(msg["version"]);
    if (s === undefined) return; // a full state comes first after every join
    if (version <= s.version) return; // old news
    if (version !== s.version + 1) {
      // Missed one: ask for everything again.
      this.send({ type: "sync" });
      return;
    }
    s.version = version;
    switch (msg["type"]) {
      case "presence":
        s.presence = msg["presence"] as Presence;
        break;
      case "timeline":
        s.timeline = msg["timeline"] as Timeline;
        s.current = msg["current"] as CurrentEntry | undefined;
        s.skipVotes = msg["skipVotes"] as SkipVotes | undefined;
        break;
      case "queue": {
        const byId = new Map<string, WaitingEntry>();
        for (const e of s.queue) byId.set(e.entryId, e);
        for (const e of s.suggestions) byId.set(e.entryId, e);
        for (const id of (msg["removed"] as string[] | undefined) ?? []) byId.delete(id);
        for (const e of (msg["upsert"] as WaitingEntry[] | undefined) ?? []) byId.set(e.entryId, e);
        const pick = (ids: unknown): WaitingEntry[] => {
          const out: WaitingEntry[] = [];
          for (const id of (ids as string[] | undefined) ?? []) {
            const e = byId.get(id);
            if (e !== undefined) out.push(e);
          }
          return out;
        };
        s.queue = pick(msg["order"]);
        s.suggestions = pick(msg["suggestionOrder"]);
        s.timeline = msg["timeline"] as Timeline;
        s.current = msg["current"] as CurrentEntry | undefined;
        s.history = (msg["history"] as HistoryEntry[] | undefined) ?? s.history;
        s.skipVotes = msg["skipVotes"] as SkipVotes | undefined;
        if (msg["presence"] !== undefined) s.presence = msg["presence"] as Presence;
        break;
      }
    }
    this.#events.state(s);
  }
}
