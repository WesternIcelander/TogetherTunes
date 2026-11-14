// The page a provider login started in a popup ends at (/auth/popup-done, served by the server's
// OidcLogin; docs/ui.md#single-page-app): tells the page that opened the popup how the login ended
// (same-origin postMessage, and a BroadcastChannel for when the provider's pages cut the opener link)
// and closes the window. Nothing secret is sent: only "logged in" or the failure's code.
import { LOGIN_CHANNEL, loginMessage, popupResult, providerErrorMessage } from "./providers.js";

function finish(): void {
  const result = popupResult(location.search);
  const msg = loginMessage(result, Math.random().toString(36).slice(2) + Date.now().toString(36));
  let told = false;
  try {
    const opener = window.opener as Window | null;
    if (opener !== null && opener !== window) {
      opener.postMessage(msg, location.origin);
      told = true;
    }
  } catch {
    // No opener (or another site's): the channel below.
  }
  try {
    if (typeof BroadcastChannel !== "undefined") {
      const channel = new BroadcastChannel(LOGIN_CHANNEL);
      channel.postMessage(msg);
      channel.close();
      told = true;
    }
  } catch {
    // Not available (Safari before 15.4): the opener above.
  }
  const status = document.getElementById("popup-done-status");
  if (status !== null) {
    const what = result.ok ? "You are logged in." : providerErrorMessage(result.error ?? "");
    status.textContent = what + (told ? " You can close this window." : " Go back to TogetherTunes and reload it.");
    if (!told) {
      const a = document.createElement("a");
      a.href = "/";
      a.textContent = "Open TogetherTunes";
      status.after(a);
    }
  }
  // Closing only works for a window a script opened (the popup); otherwise the text stays.
  if (told) window.setTimeout(() => window.close(), 100);
}

if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", finish);
else finish();
