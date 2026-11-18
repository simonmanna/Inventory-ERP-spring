/**
 * Decimal-safe quantity arithmetic for document lines.
 *
 * The server keeps quantities as Decimal(20, 6) and compares them exactly (a purchase order has no over-receipt
 * tolerance by default). Plain JavaScript subtraction adds binary floating-point noise — 10.3 − 10.1 is
 * 0.20000000000000107 — so a "receive the rest" button sent more than was open and the server refused a correct
 * receipt. These helpers work on whole millionths (the server's scale) instead.
 */
const DP = 6;
const SCALE = 1_000_000n;
const DECIMAL = /^([+-]?)(\d*)(?:\.(\d*))?$/;

/** A number or decimal string as whole millionths (half-up beyond 6 decimals); null when it is not a number. */
function toMicros(value: unknown): bigint | null {
  let text = '';
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return null;
    text = value.toFixed(DP);
  } else if (typeof value === 'string') {
    text = value.trim();
  } else {
    return null;
  }
  const m = DECIMAL.exec(text);
  if (!m || (!m[2] && !m[3])) {
    // Exponent notation ("1E+1") or junk: go through Number once, which normalises it.
    const n = typeof value === 'string' && text !== '' ? Number(text) : Number.NaN;
    return Number.isFinite(n) ? toMicros(n) : null;
  }
  const frac = (m[3] ?? '').padEnd(DP + 1, '0');
  let micros = BigInt(m[2] || '0') * SCALE + BigInt(frac.slice(0, DP));
  if (frac.charCodeAt(DP) >= 53 /* '5' */) micros += 1n;
  return m[1] === '-' ? -micros : micros;
}

function fromMicros(micros: bigint): number {
  const negative = micros < 0n;
  const abs = negative ? -micros : micros;
  const frac = (abs % SCALE).toString().padStart(DP, '0').replace(/0+$/, '');
  return Number(`${negative ? '-' : ''}${abs / SCALE}${frac ? `.${frac}` : ''}`);
}

/**
 * `from` minus every `less`, exact to 6 decimals: what is still open on a line (ordered − received). Values that are
 * not numbers count as 0.
 */
export function subtractQty(from: unknown, ...less: unknown[]): number {
  let micros = toMicros(from) ?? 0n;
  for (const v of less) micros -= toMicros(v) ?? 0n;
  return fromMicros(micros);
}

/** A quantity rounded to the 6 decimals the server stores, without float noise (0.20000000000000107 → 0.2). */
export function roundQty(value: unknown): number {
  const micros = toMicros(value);
  return micros === null ? 0 : fromMicros(micros);
}
