// The Encodes page (/encodes/): the encode jobs of the items in the caller's library
// (GET /api/jobs; docs/library-and-ingest.md#encode-jobs-page). Read only: no logs, no actions.
// Users only; guests and anonymous visitors go to the login page.
import { mountJobs } from "./jobs.js";
import { element, message, onReady, renderNav, requireUser } from "./session.js";

async function start(): Promise<void> {
  const status = element("encodes-status");
  try {
    const me = await requireUser();
    if (me === null) return;
    renderNav(me);
    const view = mountJobs(element("encodes-root"), {
      base: "/api/jobs",
      admin: false,
      onError: (e) => { status.textContent = message(e); },
    });
    await view.refresh();
    status.textContent = "";
  } catch (e) {
    status.textContent = message(e);
  }
}

onReady(start);
