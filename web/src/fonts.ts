// Card fonts (docs/library-and-ingest.md#card-fonts): the data of GET /api/admin/fonts and the
// texts the admin page shows for it. DOM-free, so a new admin UI can reuse it.

export type FontState = "not_downloaded" | "queued" | "downloading" | "installed" | "failed";

export interface ScriptFont {
  id: string;
  name: string;
  scripts: string[];
  cjkVariant?: string;
  size: number;
  status: FontState;
  error?: string;
  retryAt?: number;
}

export interface FontUpload { id: string; family: string; size: number; value: string }

export interface UncoveredChar { char: string; codePoint: string; script: string; count: number; font?: string }

export interface FontsInfo {
  cardFont: string;
  autoDownload: boolean;
  cjkVariant: string;
  fonts: ScriptFont[];
  totalBytes: number;
  missingBytes: number;
  choices: { bundled: { value: string; label: string }; system: string[]; uploads: FontUpload[] };
  uncovered: UncoveredChar[];
}

/** The CJK variants (`fonts.cjkVariant`) with their labels. */
export const CJK_VARIANTS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "jp", label: "Japanese" },
  { value: "kr", label: "Korean" },
  { value: "sc", label: "Simplified Chinese" },
  { value: "tc", label: "Traditional Chinese" },
];

/** `12.3 MB` (decimal megabytes, one decimal; below 0.1 MB in kB). */
export function formatSize(bytes: number): string {
  if (bytes < 100_000) return `${Math.max(1, Math.round(bytes / 1000))} kB`;
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

/** What a script font's status reads as, e.g. "Failed: HTTP 404 (retrying at 14:05)". */
export function statusText(font: ScriptFont, timeText: (ms: number) => string): string {
  switch (font.status) {
    case "installed": return "Installed";
    case "queued": return "Queued";
    case "downloading": return "Downloading…";
    case "failed": {
      const retry = font.retryAt !== undefined ? ` (retrying at ${timeText(font.retryAt)})` : "";
      return `Failed: ${font.error ?? "unknown error"}${retry}`;
    }
    default: return "Not downloaded";
  }
}

/** Whether a download is under way (the page refreshes until it isn't). */
export function busy(info: FontsInfo): boolean {
  return info.fonts.some((f) => f.status === "queued" || f.status === "downloading");
}

/** The card font choices: value (as stored in `cards.font`, "bundled" for the default) and label. */
export function cardFontChoices(info: FontsInfo): Array<{ value: string; label: string }> {
  const out = [{ value: info.choices.bundled.value, label: info.choices.bundled.label }];
  for (const u of info.choices.uploads) out.push({ value: u.value, label: `${u.family} (uploaded)` });
  for (const f of info.choices.system) out.push({ value: "system:" + f, label: `${f} (installed)` });
  if (!out.some((c) => c.value === info.cardFont)) {
    out.push({ value: info.cardFont, label: `${info.cardFont} (not available; the built-in font is used)` });
  }
  return out;
}

/** The `cards.font` patch value for a choice: null for the built-in font. */
export function cardFontSetting(value: string): string | null {
  return value === "bundled" ? null : value;
}

/** The confirmation text for Download all. */
export function downloadAllText(info: FontsInfo): string {
  const missing = info.fonts.filter((f) => f.status !== "installed").length;
  return `Download ${missing} font${missing === 1 ? "" : "s"} (${formatSize(info.missingBytes)})?`;
}
