// The front page's view (/). Until the first account exists, every visit goes to the setup view;
// a user with a temporary password goes to the change-password view; a signed-in user goes on to
// All My Songs (/songs).
import { setupNeeded } from "./api.js";
import { actorLabel } from "./actor.js";
import {
  checkPasswordChange, loadMe, LOGIN_PAGE, message, onReady, REGISTER_PAGE, renderNav, ROOMS_PAGE, SETUP_PAGE,
} from "./session.js";
import { navigate } from "./nav.js";
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
      navigate(SETUP_PAGE, true);
      return;
    }
    const me = await loadMe();
    if (!checkPasswordChange(me)) return;
    // Signed-in users start in their library (the desktop layout's All My Songs).
    if (me.kind === "user") {
      navigate("/songs", true);
      return;
    }
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
      const items: (Node | string)[] = [link(ROOMS_PAGE, "Rooms"), " · ", link(LOGIN_PAGE, "Log in"), " · ",
        link(REGISTER_PAGE, "Register")];
      links.replaceChildren(...items);
      links.hidden = false;
    }
  } catch (e) {
    if (status !== null) status.textContent = describeVersion() + " " + message(e);
  }
}

onReady(start);
