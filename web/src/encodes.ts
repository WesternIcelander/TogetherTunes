// The Encodes page (/encodes/): the encode jobs of the items in the caller's library
// (GET /api/jobs; docs/library-and-ingest.md#encode-jobs-page). Read only: no logs, no actions.
// Users only; guests and anonymous visitors go to the login page.
import { mountJobs } from "./jobs.js";
import { viewScope } from "./nav.js";
import { message, onReady, renderNav, requireUser } from "./session.js";

/** This view's start (before any await): its signal stops the polling, and its elements only. */
const VIEW = viewScope(import.meta.url);
const element = VIEW.element;

async function start(): Promise<void> {
  const status = element("encodes-status");
  try {
    const me = await requireUser(VIEW);
    if (me === null || VIEW.signal.aborted) return;
    renderNav(me);
    const view = mountJobs(element("encodes-root"), {
      base: "/api/jobs",
      admin: false,
      onError: (e) => { status.textContent = message(e); },
      signal: VIEW.signal,
    });
    await view.refresh();
    status.textContent = "";
  } catch (e) {
    if (!VIEW.signal.aborted) status.textContent = message(e);
  }
}

onReady(start);
