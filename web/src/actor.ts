// How actors are shown (docs/api.md, docs/auth.md#guests): every guest gets a guest badge.

/** An actor as responses and messages serialise it: `{ kind, id, name, isGuest }`. */
export interface ActorRef {
  kind: "user" | "guest";
  id: string;
  name: string;
  isGuest: boolean;
}

/**
 * The actor's name, followed by a "Guest" badge for guests. The name is set as text, never
 * parsed as HTML. Style hooks: `.actor`, `.badge.badge-guest`.
 */
export function actorLabel(actor: ActorRef): HTMLElement {
  const span = document.createElement("span");
  span.className = "actor";
  span.append(actor.name);
  if (actor.isGuest) {
    const badge = document.createElement("span");
    badge.className = "badge badge-guest";
    badge.textContent = "Guest";
    badge.title = "Guest (no account)";
    span.append(" ", badge);
  }
  return span;
}
