// Free disk space on the admin page (docs/library-and-ingest.md#free-disk-space): `bytes`
// settings such as storage.minFreeBytes are entered and shown in GiB, and the storage box shows
// GET /api/admin/storage. No DOM here, so Node tests can import it.

/** One GiB. */
const GIB = 1024 * 1024 * 1024;

/** `GET /api/admin/storage`. */
export interface StorageStatus { freeBytes?: number; totalBytes?: number; minFreeBytes: number; low: boolean }

/** Bytes as GiB for display and inputs: up to 3 decimals, no trailing zeros. */
export function formatGib(bytes: number): string {
  return String(Math.round(bytes / GIB * 1000) / 1000);
}

/** A GiB form value as whole bytes, or NaN if it isn't a number. */
export function parseGib(text: string): number {
  const trimmed = text.trim();
  if (trimmed === "") return NaN;
  return Math.round(Number(trimmed) * GIB);
}

/** The storage box's text: free space and the minimum, plus the warning while below it. */
export function storageText(s: StorageStatus): { line: string; warning: string | undefined } {
  const free = s.freeBytes === undefined ? "Free space on the data volume: unknown (it couldn't be measured)."
    : `Free space on the data volume: ${formatGib(s.freeBytes)} GiB`
      + (s.totalBytes === undefined ? "." : ` of ${formatGib(s.totalBytes)} GiB.`);
  const min = s.minFreeBytes === 0 ? " Minimum: off (storage.minFreeBytes is 0)."
    : ` Minimum: ${formatGib(s.minFreeBytes)} GiB.`;
  const warning = s.low ? "Free space is below the minimum: uploads and YouTube links are refused and queued"
    + " ingest jobs wait until space is freed (delete or purge items, or lower the minimum)." : undefined;
  return { line: free + min, warning };
}
