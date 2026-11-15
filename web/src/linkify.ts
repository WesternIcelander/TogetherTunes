// Plain text with its web addresses as links (DOM-free; linkify.test.mjs). Used for the admin's
// login and register page messages (docs/auth.md#login-and-registration-messages): the text is
// split into parts, the page renders every part as text (textContent) and only the `http://` /
// `https://` addresses as links, so nothing in it is ever parsed as HTML.

/** A part of the text: plain text, or a web address with the URL its link goes to. */
export interface TextPart {
  text: string;
  href?: string;
}

// An address starts at "http://" or "https://" not glued to a preceding letter or digit (no
// lookbehind: Safari 15) and runs until whitespace or a character that can't be part of one.
const ADDRESS = /(^|[^A-Za-z0-9])(https?:\/\/[^\s<>"'`]+)/gi;

/** Punctuation that ends a sentence rather than the address ("see https://x.example."). */
const TRAILING = ".,;:!?";

/** Drops trailing punctuation and unbalanced closing brackets from a found address. */
function trimAddress(s: string): string {
  for (;;) {
    const last = s.charAt(s.length - 1);
    if (TRAILING.includes(last)) {
      s = s.slice(0, -1);
      continue;
    }
    const open = last === ")" ? "(" : last === "]" ? "[" : last === "}" ? "{" : "";
    if (open !== "" && s.split(open).length < s.split(last).length) {
      s = s.slice(0, -1);
      continue;
    }
    return s;
  }
}

/** The address as a link target, or undefined if it isn't a valid http(s) URL. */
function linkTarget(address: string): string | undefined {
  try {
    const url = new URL(address);
    if (url.protocol !== "http:" && url.protocol !== "https:") return undefined;
    if (url.hostname === "") return undefined;
    return url.href;
  } catch {
    return undefined;
  }
}

/**
 * Splits `text` into plain parts and link parts. Joining every part's `text` gives back `text`
 * exactly. Only `http://` and `https://` addresses become links.
 */
export function linkParts(text: string): TextPart[] {
  const parts: TextPart[] = [];
  let plain = "";
  let pos = 0;
  ADDRESS.lastIndex = 0;
  for (let m = ADDRESS.exec(text); m !== null; m = ADDRESS.exec(text)) {
    const start = m.index + (m[1] ?? "").length;
    const address = trimAddress(m[2] ?? "");
    const href = linkTarget(address);
    if (href === undefined) continue;
    plain += text.slice(pos, start);
    if (plain !== "") parts.push({ text: plain });
    plain = "";
    parts.push({ text: address, href });
    pos = start + address.length;
    ADDRESS.lastIndex = pos;
  }
  plain += text.slice(pos);
  if (plain !== "") parts.push({ text: plain });
  return parts;
}

/** The attributes every message link gets: a new tab, no opener, no referrer. */
export const LINK_ATTRIBUTES: Readonly<Record<string, string>> = {
  target: "_blank",
  rel: "noopener noreferrer",
};
