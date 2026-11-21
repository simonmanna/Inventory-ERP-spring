/**
 * ADR-S083 C5 — snapshot page verification.
 *
 * The server's `pageChecksum` is sha256 of the page's `rows` exactly as it serialized them. Re-serializing parsed JSON
 * in the browser does not reproduce those bytes (numbers like `1.50`, integer-like keys reordered), so the client
 * hashes the raw text of the top-level `rows` array taken from the response body instead.
 */

/**
 * The raw JSON text of the value of top-level key `key` in the object `raw`, or `null` when absent. A small scanner:
 * it tracks strings (with escapes) and nesting depth, so a `"rows"` inside a nested value is never matched.
 */
export function rawTopLevelValue(raw: string, key: string): string | null {
  let depth = 0;
  let i = 0;
  const n = raw.length;
  const needle = JSON.stringify(key);
  while (i < n) {
    const c = raw[i];
    if (c === '"') {
      const start = i;
      i++;
      while (i < n && raw[i] !== '"') i += raw[i] === '\\' ? 2 : 1;
      i++;
      if (depth === 1 && raw.slice(start, i) === needle) {
        let j = i;
        while (j < n && /\s/.test(raw[j])) j++;
        if (raw[j] === ':') {
          j++;
          while (j < n && /\s/.test(raw[j])) j++;
          return raw.slice(j, valueEnd(raw, j));
        }
      }
      continue;
    }
    if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') depth--;
    i++;
  }
  return null;
}

/** Index just past the JSON value starting at `start`. */
function valueEnd(raw: string, start: number): number {
  const n = raw.length;
  const first = raw[start];
  if (first === '"') {
    let i = start + 1;
    while (i < n && raw[i] !== '"') i += raw[i] === '\\' ? 2 : 1;
    return i + 1;
  }
  if (first === '{' || first === '[') {
    let depth = 0;
    let i = start;
    while (i < n) {
      const c = raw[i];
      if (c === '"') {
        i++;
        while (i < n && raw[i] !== '"') i += raw[i] === '\\' ? 2 : 1;
      } else if (c === '{' || c === '[') {
        depth++;
      } else if (c === '}' || c === ']') {
        depth--;
        if (depth === 0) return i + 1;
      }
      i++;
    }
    return n;
  }
  let i = start;
  while (i < n && !/[,}\]\s]/.test(raw[i])) i++;
  return i;
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Scope checksum: sha256 of the page checksums joined by commas, in page order (`SyncSnapshotService.scopeChecksum`). */
export function scopeChecksum(pageChecksums: string[]): Promise<string> {
  return sha256Hex(pageChecksums.join(','));
}

/** True when the raw page body's `rows` hash to its `pageChecksum`. */
export async function verifyPage(rawBody: string, pageChecksum: string): Promise<boolean> {
  const rows = rawTopLevelValue(rawBody, 'rows');
  if (rows === null) return false;
  return (await sha256Hex(rows)) === pageChecksum;
}
