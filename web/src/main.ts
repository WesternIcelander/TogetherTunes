// Entry point of the web UI. Loaded by index.html as an ES module.
// Until the first account exists, every visit goes to the setup page.
import { setupNeeded } from "./api.js";
import { describeVersion } from "./version.js";

async function start(): Promise<void> {
  try {
    if (await setupNeeded()) {
      location.replace("/setup.html");
      return;
    }
  } catch {
    // Show the page anyway; the API calls on it will report the problem.
  }
  const status = document.getElementById("status");
  if (status !== null) {
    status.textContent = describeVersion();
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => void start());
} else {
  void start();
}
