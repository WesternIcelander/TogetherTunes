// The encode jobs lists (docs/library-and-ingest.md#encode-jobs-page), shared by the admin page's
// Jobs tab (every job, with logs, Retry and Cancel: /api/admin/jobs) and the Encodes page (a user's
// own library's items, read only: /api/jobs). Three lists: running and queued (in the order the
// workers take them), failed, and finished in the last 24 hours (folded away). There is no push
// channel for jobs (the only WebSocket is a room's), so the lists poll every few seconds while the
// page is visible and the lists are shown.
import { api } from "./api.js";
import { button, formatTime, h } from "./dom.js";
import {
  elapsedText, emptyText, kindText, PAUSED_TEXT, stateText, tierText, type JobDetail, type JobListState, type JobPage,
  type JobRow,
} from "./jobtext.js";
import { itemPath } from "./media.js";
import { pageSignal } from "./nav.js";

/** How often the shown lists are fetched again. */
const POLL_MS = 3000;
/** Rows per page ("Load more" fetches the next). */
const PAGE = 50;
/** The most rows one poll fetches again. */
const MAX_REFRESH = 200;

export interface JobsOptions {
  /** `/api/admin/jobs` or `/api/jobs`. */
  base: string;
  /** Show logs and the Retry / Cancel actions. */
  admin: boolean;
  /** Where errors of actions and loads go. */
  onError: (e: unknown) => void;
  /** Called after Retry or Cancel, with what happened. */
  onDone?: (text: string) => void;
}

interface Section {
  state: JobListState;
  rows: JobRow[];
  cursor?: string;
  body: HTMLTableSectionElement;
  empty: HTMLElement;
  more: HTMLButtonElement;
  /** Fetched only while shown (the folded "done" list). */
  shown: () => boolean;
}

export interface JobsView {
  /** Fetches every shown list again now. */
  refresh(): Promise<void>;
  /** Stops polling. */
  stop(): void;
}

/** Renders the three lists into `root` and starts polling. */
export function mountJobs(root: HTMLElement, opts: JobsOptions): JobsView {
  const openLogs = new Map<string, string>();
  const paused = h("p", { class: "notice error", role: "status", text: PAUSED_TEXT });
  paused.hidden = true;

  function table(): { table: HTMLTableElement; body: HTMLTableSectionElement } {
    const body = h("tbody");
    const head = h("tr", {}, h("th", { text: "Item" }), h("th", { text: "Job" }), h("th", { text: "State" }),
      h("th", { text: "Times" }), opts.admin ? h("th", { text: "" }) : null);
    return { table: h("table", { class: "list jobs" }, h("thead", {}, head), body), body };
  }

  function section(state: JobListState, shown: () => boolean): { section: Section; nodes: Node[] } {
    const t = table();
    const empty = h("p", { class: "muted", text: emptyText(state, opts.admin) });
    empty.hidden = true;
    const more = h("button", { type: "button", text: "Load more" });
    more.hidden = true;
    const s: Section = { state, rows: [], body: t.body, empty, more, shown };
    more.addEventListener("click", () => {
      more.disabled = true;
      load(s, false).catch(opts.onError).finally(() => { more.disabled = false; });
    });
    return { section: s, nodes: [t.table, empty, more] };
  }

  const active = section("active", () => true);
  const failed = section("failed", () => true);
  const doneDetails = h("details", { class: "jobs-done" });
  const done = section("done", () => doneDetails.open);
  doneDetails.append(h("summary", { text: "Finished in the last 24 hours" }), ...done.nodes);
  doneDetails.addEventListener("toggle", () => {
    if (doneDetails.open) load(done.section, true).catch(opts.onError);
  });
  root.replaceChildren(
    h("h3", { text: "Encoding now and queued" }), paused, ...active.nodes,
    h("h3", { text: "Failed" }), ...failed.nodes,
    doneDetails);
  const sections = [active.section, failed.section, done.section];

  function logCell(row: JobRow): HTMLElement | null {
    const text = openLogs.get(row.id);
    if (text === undefined) return null;
    return h("pre", { class: "job-log small", text: text === "" ? "(no log)" : text });
  }

  function actions(row: JobRow, s: Section): HTMLElement {
    const cell = h("td", { class: "actions" });
    if (row.state === "failed") {
      cell.append(button("Retry", async () => {
        await api<JobRow>("POST", `${opts.base}/${encodeURIComponent(row.id)}/retry`);
        opts.onDone?.(`Queued "${row.item.title}" again.`);
        await refresh();
      }, opts.onError));
    }
    if (row.state === "queued") {
      cell.append(button("Cancel", async () => {
        const what = row.kind === "reencode" || row.kind === "quality"
          ? "Only this job is dropped; the item keeps playing its current files."
          : "The item becomes failed; you can retry it from the Failed list.";
        if (!confirm(`Cancel the ${kindText(row.kind).toLowerCase()} of "${row.item.title}"? ${what}`)) return;
        await api<JobRow>("POST", `${opts.base}/${encodeURIComponent(row.id)}/cancel`);
        opts.onDone?.(`Cancelled the job of "${row.item.title}".`);
        await refresh();
      }, opts.onError));
    }
    const open = openLogs.has(row.id);
    cell.append(button(open ? "Hide log" : "Log", async () => {
      if (openLogs.has(row.id)) {
        openLogs.delete(row.id);
      } else {
        const detail = await api<JobDetail>("GET", `${opts.base}/${encodeURIComponent(row.id)}`);
        openLogs.set(row.id, detail.log ?? "");
      }
      render(s);
    }, opts.onError, "link"));
    return cell;
  }

  function times(row: JobRow): HTMLElement {
    const lines: string[] = ["Queued " + formatTime(row.createdAt)];
    if (row.startedAt !== undefined && row.state !== "queued") lines.push("Started " + formatTime(row.startedAt));
    if (row.finishedAt !== undefined) lines.push("Finished " + formatTime(row.finishedAt));
    const took = elapsedText(row, Date.now());
    if (took !== "") lines.push((row.state === "running" ? "Running for " : "Took ") + took);
    const cell = h("td", { class: "muted small" });
    lines.forEach((l, i) => {
      if (i > 0) cell.append(h("br"));
      cell.append(l);
    });
    return cell;
  }

  function rowNodes(row: JobRow, s: Section): HTMLTableRowElement[] {
    const item = h("td", {}, h("div", { class: "item-title" },
      h("a", { href: itemPath(row.item.id), text: row.item.title }),
      row.item.artist !== undefined ? h("div", { class: "muted small", text: row.item.artist }) : null));
    const tier = tierText(row.tier);
    const job = h("td", {}, kindText(row.kind), tier !== "" ? h("div", { class: "muted small", text: tier }) : null);
    const state = h("td", {}, stateText(row));
    if (row.state === "running") {
      state.append(h("progress", { max: "1", value: String(Math.max(0, Math.min(1, row.progress))) }));
    }
    if (row.item.error !== undefined && row.state === "failed") {
      state.append(h("div", { class: "error small", text: row.item.error }));
    }
    const tr = h("tr", { "data-job": row.id }, item, job, state, times(row), opts.admin ? actions(row, s) : null);
    const out = [tr];
    const log = opts.admin ? logCell(row) : null;
    if (log !== null) {
      const td = h("td", {}, log);
      td.colSpan = 5;
      out.push(h("tr", { class: "job-log-row" }, td));
    }
    return out;
  }

  function render(s: Section): void {
    const nodes: HTMLTableRowElement[] = [];
    for (const row of s.rows) nodes.push(...rowNodes(row, s));
    s.body.replaceChildren(...nodes);
    s.empty.hidden = s.rows.length > 0;
    s.more.hidden = s.cursor === undefined;
  }

  /** Loads the first page again (`reset`), or the next page. */
  async function load(s: Section, reset: boolean): Promise<void> {
    const limit = reset ? Math.min(MAX_REFRESH, Math.max(PAGE, s.rows.length)) : PAGE;
    const params = new URLSearchParams({ state: s.state, limit: String(limit) });
    if (!reset && s.cursor !== undefined) params.set("cursor", s.cursor);
    const page = await api<JobPage>("GET", `${opts.base}?${params.toString()}`);
    s.rows = reset ? page.items : s.rows.concat(page.items);
    s.cursor = page.nextCursor;
    if (s.state === "active") paused.hidden = page.paused !== true;
    render(s);
  }

  async function refresh(): Promise<void> {
    await Promise.all(sections.filter((s) => s.shown()).map((s) => load(s, true)));
  }

  let timer: number | undefined;
  let busy = false;
  const visible = (): boolean => document.visibilityState === "visible" && root.isConnected
    && root.closest("[hidden]") === null;
  const tick = (): void => {
    if (busy || !visible()) return;
    busy = true;
    refresh().catch(opts.onError).finally(() => { busy = false; });
  };
  timer = window.setInterval(tick, POLL_MS);
  document.addEventListener("visibilitychange", tick, { signal: pageSignal() });
  const stop = (): void => {
    if (timer !== undefined) window.clearInterval(timer);
    timer = undefined;
  };
  pageSignal().addEventListener("abort", stop);
  return { refresh, stop };
}
