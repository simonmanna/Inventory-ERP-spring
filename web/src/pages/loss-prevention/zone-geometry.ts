import {
  FRACTION_PARAMS, ZONE_DEFAULT_PARAMS, ZONE_LINKS, type LinkedType, type Point, type Side, type ZoneType,
} from '@/features/loss-prevention/vision-api';

/**
 * Pure helpers of the zone editor (no React, no DOM): drawing geometry and the zone parameter text fields. Points are
 * fractions of the picture (x to the right, y down).
 */

/** Points as an SVG `points` attribute on a 1000 × 1000 view box. */
export const svgPoints = (ps: Point[]) => ps.map(([x, y]) => `${x * 1000},${y * 1000}`).join(' ');

/**
 * The "in" arrow of a counting line: from its middle towards `side`, drawn square on screen whatever the picture's
 * aspect. Left and right are as seen on the picture when walking from A to B.
 */
export function inArrow(a: Point, b: Point, side: Side, aspect: number): Point[] {
  const ax = a[0] * aspect;
  const bx = b[0] * aspect;
  const len = Math.hypot(bx - ax, b[1] - a[1]) || 1;
  const ux = (bx - ax) / len;
  const uy = (b[1] - a[1]) / len;
  const s = side === 'left' ? 1 : -1;
  const nx = s * uy;
  const ny = s * -ux;
  const mx = (ax + bx) / 2;
  const my = (a[1] + b[1]) / 2;
  const at = (along: number, across: number): Point => [(mx + nx * along + ux * across) / aspect, my + ny * along + uy * across];
  return [at(-0.05, 0.006), at(0.04, 0.006), at(0.04, 0.025), at(0.08, 0), at(0.04, -0.025), at(0.04, -0.006), at(-0.05, -0.006)];
}

/** The link a new zone of this type starts with: the first allowed link when one is required, else none. */
export const defaultLink = (t: ZoneType): LinkedType | '' => (ZONE_LINKS[t].required ? ZONE_LINKS[t].types[0] : '');

/** The parameters of a zone as text fields; keys the zone type does not define are kept untouched. */
export function splitParams(type: ZoneType, stored: Record<string, unknown> | null | undefined) {
  const defaults = ZONE_DEFAULT_PARAMS[type];
  const text: Record<string, string> = {};
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(stored ?? {})) {
    if (!(k in defaults)) extra[k] = v;
    else text[k] = Array.isArray(v) ? v.join(', ') : v === null || v === undefined ? '' : String(v);
  }
  return { text, extra };
}

/** True when a filled-in parameter is invalid: a negative or non-numeric number, a fraction above 1, an empty list. */
export function paramProblem(type: ZoneType, text: Record<string, string>): boolean {
  return Object.entries(ZONE_DEFAULT_PARAMS[type]).some(([k, d]) => {
    const v = (text[k] ?? '').trim();
    if (v === '') return false;
    if (typeof d === 'number') {
      const n = Number(v);
      return !Number.isFinite(n) || n < 0 || (FRACTION_PARAMS.has(k) && n > 1);
    }
    if (Array.isArray(d)) return v.split(',').map((s) => s.trim()).filter(Boolean).length === 0;
    return false;
  });
}

/** The text fields back as zone parameters; an empty field is left out so the agent's default applies. */
export function joinParams(type: ZoneType, text: Record<string, string>, extra: Record<string, unknown>) {
  const out: Record<string, unknown> = { ...extra };
  for (const [k, d] of Object.entries(ZONE_DEFAULT_PARAMS[type])) {
    const v = (text[k] ?? '').trim();
    if (v === '') continue;
    if (typeof d === 'number') out[k] = Number(v);
    else if (Array.isArray(d)) out[k] = v.split(',').map((s) => s.trim()).filter(Boolean);
    else out[k] = v;
  }
  return out;
}
