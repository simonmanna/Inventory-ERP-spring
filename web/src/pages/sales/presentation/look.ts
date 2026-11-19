/**
 * Shared look of the weekly report deck (colours + geometry), measured from presentation-real.pptx.
 * Geometry is in pixels on a 1280×720 canvas; the PPTX exporter divides by 96 to get inches.
 */

export const FONT = 'Calibri';
export const CSS_FONT = "Calibri, Carlito, 'Segoe UI', Arial, sans-serif";

/** Classic palette (the owner's reference deck). Other themes derive the same keys. */
export const C = {
  navy: '143056', navyBand: '17375E', navySummary: '0C3E5B', navyTable: '1B304F', navyText: '16365C',
  blue: '2563AA', total: '2563A0', link: '2470AD', cyan: '00B0F0', orange: 'D97B2B',
  pill: 'DCEBFA', panel: 'F2F6FA', panelLine: 'DCE4EC', bar: 'D2DCE6', cardLine: 'D7DDE5',
  zebra: 'EBEFF4', grey: 'F5F5F5', gridLine: 'D6D6D6', ink: '26303C', muted: '5E6B7B', black: '000000', white: 'FFFFFF',
  brown: '875330', tan: 'B8845E', keyLabel: '234860',
  greenBg: 'E5F0EA', greenLine: 'BED8C9', green: '2B7352',
  chartBar: 'C0504D', bars3d: ['4F81BD', 'C0504D', '9BBB59'], gold: 'E8C25A', logoBg: '000000',
} as const;

export type Palette = { [K in keyof typeof C]: K extends 'bars3d' ? readonly string[] : string };

// ---------------------------------------------------------------- themes

const rgb = (h: string) => [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
const toHex = (v: number[]) => v.map((x) => Math.round(Math.max(0, Math.min(255, x))).toString(16).padStart(2, '0')).join('').toUpperCase();
/** Mixes `a` towards `b` by `t` (0 → a, 1 → b). Colours are 6-digit hex without '#'. */
export const mix = (a: string, b: string, t: number) => { const x = rgb(a); const y = rgb(b); return toHex(x.map((v, i) => v + (y[i] - v) * t)); };
export const cleanHex = (v: string, fallback: string) => { const h = v.replace('#', '').trim(); return /^[0-9a-fA-F]{6}$/.test(h) ? h.toUpperCase() : fallback; };

/** Builds a full palette from a brand primary, a secondary (charts / totals) and an accent (strips, highlights). */
export function derive(primary: string, secondary: string, accent: string): Palette {
  const W = 'FFFFFF';
  return {
    ...C,
    navy: primary, navyBand: mix(primary, W, 0.08), navySummary: mix(primary, '000000', 0.1), navyTable: mix(primary, W, 0.05), navyText: primary,
    blue: secondary, total: secondary, link: mix(secondary, '000000', 0.1), cyan: accent, orange: mix(accent, '000000', 0.25),
    pill: mix(primary, W, 0.86), panel: mix(primary, W, 0.94), panelLine: mix(primary, W, 0.8), bar: mix(primary, W, 0.78),
    cardLine: mix(primary, W, 0.8), zebra: mix(primary, W, 0.9), keyLabel: primary,
    brown: mix(primary, secondary, 0.35), tan: mix(secondary, W, 0.2),
    chartBar: secondary, bars3d: [mix(secondary, W, 0.35), secondary, accent], gold: mix(accent, W, 0.25), logoBg: primary,
  };
}

export type ThemeKey = 'classic' | 'corporate' | 'emerald' | 'sunset' | 'royal' | 'ocean' | 'brand';
export type StyleKey = 'classic' | 'minimal' | 'bold';

export const THEMES: Array<{ key: ThemeKey; label: string; colors: [string, string, string] }> = [
  { key: 'classic', label: 'Navy classic', colors: ['143056', '2563AA', '00B0F0'] },
  { key: 'corporate', label: 'Corporate slate', colors: ['2F3B4C', '3A7CA5', '81B29A'] },
  { key: 'emerald', label: 'Emerald', colors: ['0F5132', '198754', 'E0A800'] },
  { key: 'sunset', label: 'Sunset', colors: ['5B1A2E', 'C8553D', 'F4A259'] },
  { key: 'royal', label: 'Royal', colors: ['2E1A5B', '6A4C93', 'F2B134'] },
  { key: 'ocean', label: 'Ocean', colors: ['0B3C49', '0F7C8C', '5CC8D7'] },
  { key: 'brand', label: 'My brand colours', colors: ['143056', '2563AA', 'D97B2B'] },
];

export const STYLES: Array<{ key: StyleKey; label: string; hint: string }> = [
  { key: 'classic', label: 'Classic', hint: 'Colour bands, pills and soft shadows' },
  { key: 'minimal', label: 'Minimal', hint: 'White headers, thin accent lines, no shadows' },
  { key: 'bold', label: 'Bold', hint: 'Solid colour blocks with accent strips' },
];

export const FONTS = ['Calibri', 'Arial', 'Georgia', 'Verdana', 'Trebuchet MS', 'Segoe UI'] as const;

/** Presenter's choice of theme, layout style and font (persisted per browser). */
export interface DeckLook { theme: ThemeKey; style: StyleKey; font: string; brand: [string, string, string] }
export const DEFAULT_LOOK: DeckLook = { theme: 'classic', style: 'classic', font: FONT, brand: ['143056', '2563AA', 'D97B2B'] };

/** Resolved look the renderers use. */
export interface Look { c: Palette; style: StyleKey; font: string; cssFont: string }

export function resolveLook(l: DeckLook = DEFAULT_LOOK): Look {
  const t = THEMES.find((x) => x.key === l.theme) ?? THEMES[0];
  const [p, s, a] = t.key === 'brand' ? l.brand.map((v, i) => cleanHex(v, t.colors[i])) : t.colors;
  const font = FONTS.includes(l.font as (typeof FONTS)[number]) ? l.font : FONT;
  return {
    c: t.key === 'classic' ? C : derive(p, s, a),
    style: (['classic', 'minimal', 'bold'] as const).includes(l.style) ? l.style : 'classic',
    font,
    cssFont: font === FONT ? CSS_FONT : `'${font}', Arial, sans-serif`,
  };
}

export interface Box { x: number; y: number; w: number; h: number }

export const G = {
  topBar: { x: 0, y: 0, w: 1280, h: 43 },
  band: { x: 0, y: 0, w: 1280, h: 105 },
  bandTitle: { x: 55, y: 22, w: 1100, h: 62 },
  bandSub: { x: 72, y: 125, w: 1000, h: 36 },
  cover: {
    photo: { x: 0, y: 0, w: 1280, h: 645 }, frame: { x: 57, y: -6, w: 1128, h: 585 },
    logo: { x: 356, y: 27, w: 506, h: 198 }, title: { x: 100, y: 270, w: 1080, h: 70 },
  },
  summary: {
    band: { x: 0, y: 0, w: 1280, h: 88 }, title: { x: 148, y: 18, w: 1060, h: 60 }, strip: { x: 0, y: 93, w: 1280, h: 12 },
    kv: { x: 78, y: 110, w: 818 }, kvCols: [280, 538], kvRow: 39,
    heading: { x: 78, y: 233, w: 818, h: 40 },
    table: { x: 78, y: 273, w: 818 }, cols: [248, 162, 167, 241],
  },
  catTable: {
    title: { x: 88, y: 76, w: 1100, h: 56 }, sub: { x: 100, y: 140, w: 900, h: 28 },
    table: { x: 92, y: 185, w: 1058 }, cols: [223, 228, 264, 343],
  },
  catChart: {
    band: { x: 0, y: 0, w: 1280, h: 118 }, strip: { x: 0, y: 118, w: 1280, h: 10 },
    title: { x: 80, y: 18, w: 1150, h: 50 }, sub: { x: 10, y: 80, w: 1260, h: 26 },
    chartWithText: { x: 30, y: 186, w: 560, h: 390 }, chartWide: { x: 249, y: 165, w: 812, h: 378 },
    divider: { x: 634, y: 168, w: 16, h: 437 }, card: { x: 710, y: 262, w: 465, h: 215 },
    bottom: { x: 32, y: 634, w: 1176, h: 20 },
  },
  catCompare: {
    titlePill: { x: 26, y: 64, w: 520, h: 72 }, headPill: { x: 580, y: 66, w: 600, h: 72 },
    chart: { x: 60, y: 160, w: 470, h: 410 }, divider: { x: 556, y: 150, w: 22, h: 430 },
    card: { x: 640, y: 210, w: 505, h: 235 }, bottom: { x: 35, y: 598, w: 1174, h: 20 }, note: { x: 66, y: 620, w: 400, h: 22 },
  },
  lost: { sub: { x: 10, y: 113, w: 900, h: 30 }, table: { x: 107, y: 190, w: 905 }, cols: [226, 514, 165] },
  visits: {
    titlePill: { x: 58, y: 66, w: 932, h: 72 }, headPill: { x: 56, y: 175, w: 555, h: 72 },
    item: { x: 55, y: 278, w: 332, h: 44 }, itemStep: 66,
    images: [{ x: 531, y: 258, w: 348, h: 249 }, { x: 928, y: 258, w: 257, h: 134 }, { x: 491, y: 518, w: 277, h: 174 }, { x: 885, y: 462, w: 290, h: 206 }],
  },
  market: { table: { x: 63, y: 183, w: 1077 }, cols: [180, 532, 365] },
  progress: {
    table: { x: 63, y: 192, w: 1151 }, cols: [215, 533, 403],
    key: { x: 63, y: 494, w: 1151, h: 102 }, note: { x: 75, y: 638, w: 1100, h: 24 },
  },
  digital: {
    cards: [{ x: 63, y: 188, w: 365, h: 148 }, { x: 489, y: 188, w: 331, h: 148 }, { x: 63, y: 361, w: 365, h: 148 }, { x: 498, y: 361, w: 322, h: 148 }],
    image: { x: 832, y: 173, w: 300, h: 478 }, note: { x: 77, y: 560, w: 760, h: 34 },
  },
  results: {
    card: { x: 67, y: 193, w: 538, h: 350 }, heading: { x: 129, y: 258, w: 460, h: 34 }, text: { x: 129, y: 305, w: 460, h: 200 },
    actHead: { x: 667, y: 196, w: 560, h: 34 }, act: { x: 667, y: 244, w: 580, h: 44 }, note: { x: 79, y: 612, w: 1100, h: 24 },
  },
  objectives: {
    cards: [{ x: 63, y: 188, w: 566, h: 148 }, { x: 654, y: 188, w: 566, h: 148 }, { x: 63, y: 361, w: 566, h: 148 }, { x: 654, y: 361, w: 566, h: 148 }],
    note: { x: 79, y: 562, w: 1100, h: 30 },
  },
  plan: { table: { x: 86, y: 193, w: 1025 }, cols: [192, 466, 367], note: { x: 77, y: 592, w: 1100, h: 28 } },
  /** User-added slides (kind 'custom'); content area sits under the 105px band. */
  custom: {
    full: { x: 70, y: 150, w: 1140, h: 480 },
    left: { x: 70, y: 150, w: 560, h: 480 }, right: { x: 670, y: 150, w: 540, h: 480 },
    chart: { x: 40, y: 150, w: 640, h: 470 }, side: { x: 720, y: 170, w: 500, h: 430 },
    caption: { x: 0, y: 590, w: 1280, h: 130 }, note: { x: 70, y: 650, w: 1140, h: 30 },
    cards: [{ x: 63, y: 160, w: 566, h: 220 }, { x: 654, y: 160, w: 566, h: 220 }, { x: 63, y: 405, w: 566, h: 220 }, { x: 654, y: 405, w: 566, h: 220 }],
  },
} as const;

/** Column widths that share `w` evenly, first column a little wider. */
export const evenCols = (n: number, w: number): number[] => {
  if (n <= 1) return [w];
  const first = Math.round((w / n) * 1.3);
  const rest = (w - first) / (n - 1);
  return [first, ...Array.from({ length: n - 1 }, () => rest)];
};
/** Row height that fits `rows` body rows (+ header) in `h` pixels. */
export const fitRowH = (rows: number, h: number, max = 48) => Math.max(22, Math.min(max, Math.floor(h / Math.max(1, rows + 1))));
