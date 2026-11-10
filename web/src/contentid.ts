// The content ID of a file for the upload pre-check (docs/library-and-ingest.md#content-id): a
// chunked SHA-256, "sha256c8m:<size>:<hex>". The file is read in consecutive 8 MiB pieces
// (Blob.slice, so memory stays bounded for multi-GB files); each piece is hashed with
// crypto.subtle.digest, then the concatenation of those digests is hashed again. No hand-written
// SHA-256: without crypto.subtle (plain http, which isn't a secure context) there is no pre-check.
// The server computes the same ID (library/ContentId.java). No DOM; Node-tested (contentid.test.mjs).

export const PIECE_BYTES = 8 * 1024 * 1024;
export const PREFIX = "sha256c8m:";

/** The part of the Web Crypto API this needs (crypto.subtle in browsers and Node). */
export type Digester = Pick<SubtleCrypto, "digest">;

/** Something sliceable like a File or Blob. */
export interface Sliceable {
  readonly size: number;
  slice(start: number, end: number): { arrayBuffer(): Promise<ArrayBuffer> };
}

/**
 * Whether this page can use the pre-check: an https page (the server only offers it on an https
 * `http.publicBaseUrl`, and pages are served from that origin) in a secure context with
 * `crypto.subtle`. `env` is for tests; it defaults to the page's globals.
 */
export function precheckAvailable(env?: { protocol: string; isSecureContext: boolean; subtle: unknown }): boolean {
  const e = env ?? {
    protocol: typeof location === "undefined" ? "" : location.protocol,
    isSecureContext: typeof isSecureContext === "boolean" ? isSecureContext : false,
    subtle: typeof crypto === "undefined" ? undefined : crypto.subtle,
  };
  return e.protocol === "https:" && e.isSecureContext && e.subtle !== undefined && e.subtle !== null;
}

function hex(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i++) {
    s += (bytes[i] as number).toString(16).padStart(2, "0");
  }
  return s;
}

/**
 * The content ID of `file`. `onProgress` gets the share done (0..1) after each piece; `stopped`
 * is asked between pieces and aborts with an Error named "AbortError" when it returns true.
 */
export async function contentId(file: Sliceable, subtle: Digester, onProgress?: (done: number) => void,
  stopped?: () => boolean): Promise<string> {
  const size = file.size;
  if (!(size > 0)) throw new Error("An empty file has no content ID");
  const pieces = Math.ceil(size / PIECE_BYTES);
  const digests = new Uint8Array(pieces * 32);
  for (let i = 0; i < pieces; i++) {
    if (stopped !== undefined && stopped()) {
      const e = new Error("Stopped");
      e.name = "AbortError";
      throw e;
    }
    const start = i * PIECE_BYTES;
    const data = await file.slice(start, Math.min(size, start + PIECE_BYTES)).arrayBuffer();
    digests.set(new Uint8Array(await subtle.digest("SHA-256", data)), i * 32);
    if (onProgress !== undefined) onProgress((i + 1) / pieces);
  }
  return PREFIX + size + ":" + hex(new Uint8Array(await subtle.digest("SHA-256", digests)));
}
