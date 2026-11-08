// Entry point of the web UI (the front page). Until the first account exists, every visit goes
// to the setup page; a user with a temporary password goes to the change-password page.
import { setupNeeded } from "./api.js";
import { actorLabel } from "./actor.js";
import {
  ACCOUNT_PAGE, ADMIN_PAGE, checkPasswordChange, isAdmin, loadMe, LOGIN_PAGE, message, onReady, REGISTER_PAGE,
  renderNav,
} from "./session.js";
import { describeVersion } from "./version.js";

function link(href: string, text: string): HTMLAnchorElement {
  const a = document.createElement("a");
  a.href = href;
  a.textContent = text;
  return a;
}

async function start(): Promise<void> {
  const status = document.getElementById("status");
  const links = document.getElementById("links");
  try {
    if (await setupNeeded()) {
      location.replace("/setup.html");
      return;
    }
    const me = await loadMe();
    if (!checkPasswordChange(me)) return;
    renderNav(me);
    if (status !== null) {
      status.replaceChildren();
      if (me.kind === "anonymous") {
        status.textContent = "You are not logged in.";
      } else {
        status.append("Hello, ", actorLabel({ kind: me.kind, id: me.id, name: me.name, isGuest: me.isGuest }), ".");
      }
    }
    if (links !== null) {
      const items: (Node | string)[] = [];
      if (me.kind === "user") {
        items.push(link(ACCOUNT_PAGE, "Your account"));
        if (isAdmin(me)) items.push(" · ", link(ADMIN_PAGE, "Administration"));
      } else {
        items.push(link(LOGIN_PAGE, "Log in"), " · ", link(REGISTER_PAGE, "Register"));
      }
      links.replaceChildren(...items);
      links.hidden = false;
    }
  } catch (e) {
    if (status !== null) status.textContent = describeVersion() + " " + message(e);
  }
}

onReady(start);
