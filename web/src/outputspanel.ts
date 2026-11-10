// The room page's outputs panel (docs/rooms.md#outputs). Everyone sees the RTMP playback URL
// (the room setting `outputs.rtmp.playbackUrl`) while the RTMP output is active, with a copy
// button. The owner and moderators also see every output from `GET /api/rooms/{id}/outputs`:
// the `.ts`, HLS and MP3 URLs with copy buttons, the RTMP push status and, for the owner only, the RTMP
// target and key (in a password field with Reveal and Copy), live push status
// (from the room state, which `output.status` keeps current), the player allowlist note and, with
// HLS on, the HLS desync note. The owner turns outputs on and off, rotates the URLs' tokens, sets
// the RTMP target and key, and removes outputs. The decisions are in outputs.ts.
import { api } from "./api.js";
import { button, h } from "./dom.js";
import {
  activeKey, autoOffLine, canCreate, canRotate, urlKey, OUTPUT_DESCRIPTIONS, OUTPUT_KINDS, OUTPUT_LABELS, outputNotes, pushDetail, pushLine, rtmpPlaybackUrl,
  validRtmpUrl, type OutputKind, type OutputRow,
} from "./outputs.js";
import type { RoomState } from "./roomclient.js";
import type { ServerSwitches } from "./roomsettings.js";
import { message } from "./session.js";

/**
 * A Copy button for a read-only field: the clipboard API, else select + `execCommand("copy")`, else
 * the text stays selected for the user to copy. `beforeSelect` runs before the fallback selects the
 * field (a password field must show its text to be copied that way).
 */
function copyButton(input: HTMLInputElement, text: string, status: HTMLElement, beforeSelect?: () => void): HTMLElement {
  const copy = h("button", { type: "button", text: "Copy" });
  copy.addEventListener("click", () => {
    const done = (ok: boolean): void => {
      status.textContent = ok ? "Copied." : "Selected: copy it with Ctrl+C (or long-press).";
    };
    const fallback = (): void => {
      if (beforeSelect !== undefined) beforeSelect();
      input.focus();
      input.select();
      let ok = false;
      try {
        ok = document.execCommand("copy");
      } catch {
        ok = false;
      }
      done(ok);
    };
    const clip = navigator.clipboard;
    if (clip !== undefined && typeof clip.writeText === "function") {
      clip.writeText(text).then(() => done(true), fallback);
    } else {
      fallback();
    }
  });
  return copy;
}

/** A read-only URL field with a Copy button; where the clipboard API is missing, selects the text. */
export function copyField(url: string, label: string): HTMLElement {
  const input = h("input", { type: "text", readonly: true, value: url, class: "grow copy-url", "aria-label": label });
  input.addEventListener("focus", () => input.select());
  const status = h("span", { class: "muted small", role: "status" });
  return h("div", { class: "row copy-row" }, input, copyButton(input, url, status), status);
}

/**
 * A secret in a read-only password field with Reveal (toggles to text, then "Hide") and Copy (the
 * same fallback chain as {@link copyField}; the fallback reveals the field, since browsers don't
 * copy from password fields).
 */
export function secretField(secret: string, label: string): HTMLElement {
  const input = h("input", { type: "password", readonly: true, value: secret, autocomplete: "off",
    class: "grow copy-url", "aria-label": label });
  input.addEventListener("focus", () => input.select());
  const status = h("span", { class: "muted small", role: "status" });
  const reveal = h("button", { type: "button", text: "Reveal", "aria-pressed": "false" });
  const show = (on: boolean): void => {
    input.type = on ? "text" : "password";
    reveal.textContent = on ? "Hide" : "Reveal";
    reveal.setAttribute("aria-pressed", on ? "true" : "false");
  };
  reveal.addEventListener("click", () => show(input.type === "password"));
  return h("div", { class: "row copy-row" }, input, reveal, copyButton(input, secret, status, () => show(true)), status);
}

export class OutputsPanel {
  readonly #el: HTMLElement;
  #roomId = "";
  #owner = false;
  #moderator = false;
  #state: RoomState | undefined;
  /** The owner's/moderators' view; undefined until loaded. */
  #rows: OutputRow[] | undefined;
  #switches: ServerSwitches | undefined;
  #loadedKey: string | undefined;
  #loading = false;
  #error = "";
  /** Which RTMP target form is open: "create", "edit" or none. */
  #rtmpForm: "create" | "edit" | undefined;
  #playback: HTMLElement;
  #manage: HTMLElement;
  #pushStatus: HTMLElement | undefined;
  #pushDetail: HTMLElement | undefined;
  #drawnPlayback: string | undefined = "\u0000";
  /** The viewer URL setting the management part was drawn with. */
  #managePlayback: string | undefined;

  constructor(el: HTMLElement) {
    this.#el = el;
    this.#playback = h("div", { class: "outputs-playback" });
    this.#manage = h("div", { class: "outputs-manage" });
    el.replaceChildren(this.#playback, this.#manage);
  }

  /** The room state changed (also on `output.status`). */
  update(s: RoomState): void {
    this.#state = s;
    const roomChanged = this.#roomId !== s.room.id;
    this.#roomId = s.room.id;
    const owner = s.room.you.role === "owner";
    const moderator = s.room.you.canModerate;
    const rightsChanged = owner !== this.#owner || moderator !== this.#moderator;
    this.#owner = owner;
    this.#moderator = moderator;
    if (roomChanged || rightsChanged) {
      this.#rows = undefined;
      this.#loadedKey = undefined;
      this.#rtmpForm = undefined;
    }
    this.#renderPlayback(s);
    if (moderator) {
      // Another moderator (or the server) turned something on or off: load the URLs again.
      const key = activeKey(s.outputs) + "|" + urlKey(s.room);
      const playback = rtmpPlaybackUrl([{ kind: "rtmp" }], s.room.settings);
      if (this.#loadedKey !== key && !this.#loading) void this.reload();
      else if (rightsChanged || roomChanged || (playback !== this.#managePlayback && this.#rtmpForm === undefined)) {
        this.#renderManage();
      } else this.#renderLive();
    } else {
      this.#manage.replaceChildren();
      this.#pushStatus = undefined;
      this.#pushDetail = undefined;
    }
    this.#el.hidden = this.#playback.childElementCount === 0 && this.#manage.childElementCount === 0;
  }

  /** Loads the outputs and server switches (owner and moderators), then draws them. */
  async reload(): Promise<void> {
    const roomId = this.#roomId;
    const key = activeKey(this.#state?.outputs) + "|" + urlKey(this.#state?.room);
    this.#loading = true;
    try {
      const path = "/api/rooms/" + encodeURIComponent(roomId);
      const [list, switches] = await Promise.all([
        api<{ items: OutputRow[] }>("GET", path + "/outputs"),
        api<ServerSwitches>("GET", path + "/server-switches"),
      ]);
      if (roomId !== this.#roomId) return;
      this.#rows = list.items;
      this.#switches = switches;
      this.#loadedKey = key;
      this.#error = "";
    } catch (e) {
      this.#error = message(e);
      this.#loadedKey = key; // don't retry on every state message; actions and changes reload
    } finally {
      this.#loading = false;
    }
    this.#renderManage();
    if (this.#state !== undefined
      && activeKey(this.#state.outputs) + "|" + urlKey(this.#state.room) !== this.#loadedKey) void this.reload();
  }

  #renderPlayback(s: RoomState): void {
    const url = rtmpPlaybackUrl(s.outputs, s.room.settings);
    if (url === this.#drawnPlayback) return;
    this.#drawnPlayback = url;
    if (url === undefined) {
      this.#playback.replaceChildren();
      return;
    }
    this.#playback.replaceChildren(
      h("h3", { text: "Watch on the stream" }),
      h("p", { class: "muted small", text: "Paste this URL into your video player." }),
      copyField(url, "Stream URL"));
  }

  #fail(e: unknown): void {
    this.#error = message(e);
    this.#renderManage();
  }

  async #act(method: string, path: string, body?: unknown): Promise<void> {
    await api(method, "/api/rooms/" + encodeURIComponent(this.#roomId) + "/outputs" + path, body);
    this.#rtmpForm = undefined;
    await this.reload();
  }

  #renderManage(): void {
    this.#managePlayback = this.#state !== undefined
      ? rtmpPlaybackUrl([{ kind: "rtmp" }], this.#state.room.settings) : undefined;
    const parts: Node[] = [h("h3", { text: "Stream outputs" })];
    this.#pushStatus = undefined;
    this.#pushDetail = undefined;
    if (this.#error !== "") parts.push(h("p", { class: "error", role: "alert", text: this.#error }));
    const rows = this.#rows;
    if (rows === undefined) {
      parts.push(h("p", { class: "muted small", text: this.#loading ? "Loading…" : "" }));
      this.#manage.replaceChildren(...parts);
      this.#el.hidden = false;
      return;
    }
    if (!this.#owner) {
      parts.push(h("p", { class: "muted small", text: "Only the room's owner changes the outputs." }));
    }
    for (const kind of OUTPUT_KINDS) {
      const row = rows.find((r) => r.kind === kind);
      parts.push(kind === "rtmp" ? this.#rtmpBlock(row, rows) : this.#urlBlock(kind, row, rows));
    }
    for (const note of outputNotes(rows)) parts.push(h("p", { class: "muted small output-note", text: note }));
    this.#manage.replaceChildren(...parts);
    this.#el.hidden = false;
    this.#renderLive();
  }

  #serverOn(kind: OutputKind): boolean {
    return this.#switches?.outputs[kind]?.enabled !== false;
  }

  /** On/off and remove buttons for an existing output (owner only). */
  #rowButtons(row: OutputRow): Node[] {
    if (!this.#owner) return [];
    const fail = (e: unknown): void => this.#fail(e);
    const out: Node[] = [];
    if (row.enabled) {
      out.push(button("Turn off", () => this.#act("PATCH", "/" + encodeURIComponent(row.id), { enabled: false }), fail));
    } else if (row.serverEnabled) {
      out.push(button("Turn on", () => this.#act("PATCH", "/" + encodeURIComponent(row.id), { enabled: true }), fail));
    }
    out.push(button("Remove", async () => {
      if (!confirm("Remove the " + (OUTPUT_LABELS[row.kind] ?? row.kind) + " output?"
        + (row.kind === "rtmp" ? " Its target and stream key are deleted." : " Its URL stops working for good."))) return;
      await this.#act("DELETE", "/" + encodeURIComponent(row.id));
    }, fail, "danger"));
    return out;
  }

  #stateLine(row: OutputRow): string {
    if (row.active) return "On";
    return row.serverEnabled ? autoOffLine(row) ?? "Off" : "Off (disabled by the server admin)";
  }

  /** `.ts`, HLS or MP3: the stream URL with Copy, Rotate URL (private rooms), on/off, remove. */
  #urlBlock(kind: OutputKind, row: OutputRow | undefined, rows: OutputRow[]): HTMLElement {
    const fail = (e: unknown): void => this.#fail(e);
    const title = h("h4", { text: OUTPUT_LABELS[kind] ?? kind });
    const about = OUTPUT_DESCRIPTIONS[kind];
    const description = about !== undefined ? h("p", { class: "muted small", text: about }) : null;
    if (row === undefined) {
      const serverOn = this.#serverOn(kind);
      const parts: Node[] = [title, h("p", { class: "muted small", text: serverOn ? "Off" : "Disabled by the server admin" })];
      if (description !== null) parts.push(description);
      if (this.#owner && canCreate(kind, rows, serverOn)) {
        parts.push(button("Turn on", () => this.#act("POST", "", { kind }), fail));
      }
      return h("div", { class: "output-block" }, ...parts);
    }
    const parts: Node[] = [title, h("p", { class: "small", text: this.#stateLine(row) })];
    if (description !== null) parts.push(description);
    if (row.url !== undefined) parts.push(copyField(row.url, (OUTPUT_LABELS[kind] ?? kind) + " URL"));
    const buttons: Node[] = [];
    if (this.#owner && canRotate(row)) {
      buttons.push(button("Rotate URL", async () => {
        if (!confirm("Make a new URL? The current one stops working at once (open connections end); "
          + "viewers and players need the new one.")) return;
        await this.#act("POST", "/" + encodeURIComponent(row.id) + "/rotate");
      }, fail));
    }
    buttons.push(...this.#rowButtons(row));
    if (buttons.length > 0) parts.push(h("div", { class: "row" }, ...buttons));
    return h("div", { class: "output-block" }, ...parts);
  }

  /** RTMP: target and key (owner only; the key with Reveal/Copy), live status, playback URL, the target form, on/off, remove. */
  #rtmpBlock(row: OutputRow | undefined, rows: OutputRow[]): HTMLElement {
    const fail = (e: unknown): void => this.#fail(e);
    const title = h("h4", { text: OUTPUT_LABELS["rtmp"] ?? "RTMP" });
    const serverOn = this.#serverOn("rtmp");
    if (row === undefined) {
      const parts: Node[] = [title, h("p", { class: "muted small",
        text: serverOn ? "Off. Pushes the program to an RTMP service, whose viewer URL viewers play."
          : "Disabled by the server admin" })];
      if (this.#owner && canCreate("rtmp", rows, serverOn)) {
        if (this.#rtmpForm === "create") parts.push(this.#rtmpTargetForm(undefined));
        else parts.push(button("Set up RTMP push", () => { this.#rtmpForm = "create"; this.#renderManage(); }, fail));
      }
      return h("div", { class: "output-block" }, ...parts);
    }
    this.#pushStatus = h("span", { class: "output-status" });
    this.#pushDetail = h("p", { class: "muted small output-detail" });
    // The room setting as the state has it now (the API's copy is from the last load).
    const playback = this.#state !== undefined ? rtmpPlaybackUrl([{ kind: "rtmp" }], this.#state.room.settings)
      : row.playbackUrl;
    const manageHref = "/rooms/" + encodeURIComponent(this.#state?.room.slug ?? this.#roomId) + "/manage";
    const parts: Node[] = [
      title,
      h("p", { class: "small" }, "Status: ", this.#pushStatus),
      this.#pushDetail,
    ];
    // The target and key are the owner's (the API sends them to the owner only); moderators see neither.
    if (this.#owner && row.rtmpUrl !== undefined) {
      parts.push(h("p", { class: "small" }, "Target: ", h("code", { text: row.rtmpUrl })));
      if (row.rtmpKey !== undefined) {
        parts.push(h("p", { class: "small", text: "Stream key:" }), secretField(row.rtmpKey, "RTMP stream key"));
      } else {
        parts.push(h("p", { class: "small muted", text: "No stream key." }));
      }
    }
    if (playback !== undefined) {
      parts.push(h("p", { class: "small", text: "Viewer URL:" }), copyField(playback, "RTMP viewer URL"));
    } else {
      parts.push(h("p", { class: "muted small" }, "No viewer URL saved. Viewers need the URL the RTMP service gives "
        + "out: save it under ", h("a", { href: manageHref, text: "Manage room" }), " → Settings → Outputs."));
    }
    const buttons: Node[] = [];
    if (this.#owner && this.#rtmpForm !== "edit") {
      buttons.push(button("Change target or key", () => { this.#rtmpForm = "edit"; this.#renderManage(); }, fail));
    }
    buttons.push(...this.#rowButtons(row));
    if (buttons.length > 0) parts.push(h("div", { class: "row" }, ...buttons));
    if (this.#owner && this.#rtmpForm === "edit") parts.push(this.#rtmpTargetForm(row));
    return h("div", { class: "output-block" }, ...parts);
  }

  /** The RTMP target form: ingest URL and stream key (blank keeps the key when editing). */
  #rtmpTargetForm(row: OutputRow | undefined): HTMLElement {
    const url = h("input", { type: "url", placeholder: "rtmp://ingest.example/live",
      value: row?.rtmpUrl ?? "", autocomplete: "off", "aria-label": "RTMP ingest URL" });
    const key = h("input", { type: "password", autocomplete: "new-password",
      placeholder: row?.hasRtmpKey === true ? "Leave empty to keep the key" : "Stream key (if needed)",
      "aria-label": "Stream key" });
    const removeKey = h("input", { type: "checkbox" });
    const status = h("p", { class: "error", role: "alert" });
    const save = h("button", { type: "submit", text: row === undefined ? "Start pushing" : "Save" });
    const cancel = h("button", { type: "button", class: "link", text: "Cancel" });
    cancel.addEventListener("click", () => { this.#rtmpForm = undefined; this.#renderManage(); });
    const form = h("form", { class: "form rtmp-form" },
      h("label", {}, "Ingest URL", url),
      h("label", {}, "Stream key", key),
      row?.hasRtmpKey === true ? h("label", { class: "check" }, removeKey, " Remove the stream key") : null,
      h("small", { text: row === undefined ? "Only you (the owner) can see the key again, with Reveal or Copy."
        : "Leave the key empty to keep the current one. Only you (the owner) can see it, with Reveal or Copy." }),
      h("div", { class: "row" }, save, cancel), status);
    form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const target = url.value.trim();
      if (!validRtmpUrl(target)) {
        status.textContent = "Enter an rtmp:// or rtmps:// URL.";
        return;
      }
      const body: Record<string, unknown> = row === undefined ? { kind: "rtmp", rtmpUrl: target } : { rtmpUrl: target };
      if (key.value !== "") body["rtmpKey"] = key.value;
      else if (row !== undefined && removeKey.checked) body["rtmpKey"] = null;
      save.disabled = true;
      const done = row === undefined ? this.#act("POST", "", body)
        : this.#act("PATCH", "/" + encodeURIComponent(row.id), body);
      done.catch((e: unknown) => {
        status.textContent = message(e);
      }).finally(() => { save.disabled = false; });
    });
    return form;
  }

  /** Updates the RTMP status line in place (no redraw, so forms keep what was typed). */
  #renderLive(): void {
    const row = this.#rows?.find((r) => r.kind === "rtmp");
    if (row === undefined || this.#pushStatus === undefined || this.#pushDetail === undefined) return;
    const live = this.#state?.outputs?.find((o) => o.kind === "rtmp");
    const line = pushLine(live, row, Date.now());
    this.#pushStatus.textContent = line;
    this.#pushStatus.className = "output-status output-" + (row.active ? (live?.status ?? row.status?.state ?? "connecting") : "off");
    const detail = pushDetail(live, row);
    this.#pushDetail.textContent = detail ?? "";
    this.#pushDetail.hidden = detail === undefined || detail === "";
  }
}
