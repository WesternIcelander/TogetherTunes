// Entry point of the web UI. Loaded by index.html as an ES module.
// Placeholder until the real UI arrives; it proves the tsc build and module loading work.
import { describeVersion } from "./version.js";

function start(): void {
  const status = document.getElementById("status");
  if (status !== null) {
    status.textContent = describeVersion();
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", start);
} else {
  start();
}
