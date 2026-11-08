// Small fetch helpers for the JSON API (docs/api.md).

/** An error response: `{ "error": { "code", "message" } }`, or a network failure (status 0). */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

/** Sends a request with an optional JSON body and returns the parsed JSON response. */
export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, headers: { Accept: "application/json" }, credentials: "same-origin" };
  if (body !== undefined) {
    init.headers = { ...init.headers, "Content-Type": "application/json" };
    init.body = JSON.stringify(body);
  }
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch {
    throw new ApiError(0, "network_error", "Could not reach the server");
  }
  const text = await response.text();
  let json: unknown = undefined;
  try {
    json = text === "" ? undefined : JSON.parse(text);
  } catch {
    // not JSON
  }
  if (!response.ok) {
    const error = (json as { error?: { code?: string; message?: string } } | undefined)?.error;
    throw new ApiError(response.status, error?.code ?? "http_" + response.status,
      error?.message ?? response.statusText);
  }
  return json as T;
}

/** `GET /api/setup`. */
export async function setupNeeded(): Promise<boolean> {
  return (await api<{ needed: boolean }>("GET", "/api/setup")).needed;
}
