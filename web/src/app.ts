// Entry point of the single-page app (docs/ui.md#single-page-app): index.html is the one document
// for every UI path; the shell (shell.ts) shows the URL's view and handles navigation in place.
import { captureErrors, type ErrorTarget } from "./diagnostics.js";
import { Shell } from "./shell.js";

// Errors from now on go into the diagnostics ring (sent with "Send diagnostics").
captureErrors(window as unknown as ErrorTarget);

function start(): void {
  const app = document.getElementById("app");
  if (app === null) return;
  new Shell(app).start();
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", start);
} else {
  start();
}
