// The admin's audit log viewer (GET /api/admin/audit, docs/api.md#admin): the query and how a row's fields
// read. No DOM here, so Node tests can import it.
import type { ActorRef } from "./actor.js";

/** One audit row. `ip` only for ip.view holders. */
export interface AuditEntry {
  id: string;
  at: number;
  actor?: ActorRef;
  action: string;
  target?: string;
  details?: unknown;
  ip?: string;
}

/** `GET /api/admin/audit`. */
export interface AuditPage { items: AuditEntry[]; nextCursor?: string }

/** The viewer's filters: an exact action, and a user or guest id. */
export interface AuditFilter { action: string; actor: string }

/** Actions the server writes, offered as suggestions for the action filter (any text is accepted). */
export const AUDIT_ACTIONS: readonly string[] = [
  "account.register", "account.setup",
  "admin.font_download", "admin.font_removed", "admin.font_uploaded",
  "admin.provider_created", "admin.provider_deleted", "admin.provider_updated",
  "admin.role_created", "admin.role_deleted", "admin.role_permissions_set", "admin.role_updated",
  "admin.settings_changed",
  "admin.user_created", "admin.user_password_set", "admin.user_roles_set", "admin.user_updated",
  "admin.ytdlp_update",
  "auth.identity_linked", "auth.identity_unlinked", "auth.login", "auth.login_failed", "auth.login_throttled",
  "auth.logout", "auth.password_change_failed", "auth.password_changed", "auth.session_revoked",
  "auth.sessions_revoked", "auth.token",
  "guest.created", "guest.merged", "guest.renamed",
  "item.ingest_retried", "item.media_undone", "item.pinned", "item.purged", "item.trimmed", "item.unpinned",
  "item.updated",
  "job.cancelled", "job.retried",
  "room.created", "room.deleted", "room.guest_banned", "room.guest_unbanned", "room.member_removed",
  "room.member_role", "room.output_created", "room.output_deleted", "room.output_token_rotated",
  "room.output_updated", "room.owner_transferred", "room.updated",
];

/** The request path for one page: empty filters are left out, `cursor` continues a listing. */
export function auditPath(filter: AuditFilter, cursor?: string, limit = 50): string {
  const params: string[] = [];
  const action = filter.action.trim();
  const actor = filter.actor.trim();
  if (action !== "") params.push("action=" + encodeURIComponent(action));
  if (actor !== "") params.push("actor=" + encodeURIComponent(actor));
  if (cursor !== undefined) params.push("cursor=" + encodeURIComponent(cursor));
  params.push("limit=" + limit);
  return "/api/admin/audit?" + params.join("&");
}

/** A row's details as one line: `key: value` pairs for an object, JSON otherwise; "" for none. */
export function detailsText(details: unknown): string {
  if (details === undefined || details === null) return "";
  if (typeof details !== "object" || Array.isArray(details)) return JSON.stringify(details);
  const parts: string[] = [];
  for (const [key, value] of Object.entries(details as Record<string, unknown>)) {
    if (value === undefined || value === null) continue;
    parts.push(key + ": " + (typeof value === "string" ? value : JSON.stringify(value)));
  }
  return parts.join(", ");
}

/** A timestamp as local date and time, `YYYY-MM-DD HH:MM:SS`. */
export function formatTime(at: number): string {
  const d = new Date(at);
  const two = (n: number): string => (n < 10 ? "0" : "") + n;
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())} `
    + `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
}
