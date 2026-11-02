/**
 * Shared look of the weekly report deck (colours + geometry), measured from presentation-real.pptx.
 * Geometry is in pixels on a 1280×720 canvas; the PPTX exporter divides by 96 to get inches.
 */

export const FONT = 'Calibri';
export const CSS_FONT = "Calibri, Carlito, 'Segoe UI', Arial, sans-serif";

export const C = {
  navy: '143056', navyBand: '17375E', navySummary: '0C3E5B', navyTable: '1B304F', navyText: '16365C',
  blue: '2563AA', total: '2563A0', link: '2470AD', cyan: '00B0F0', orange: 'D97B2B',
  pill: 'DCEBFA', panel: 'F2F6FA', panelLine: 'DCE4EC', bar: 'D2DCE6', cardLine: 'D7DDE5',
  zebra: 'EBEFF4', grey: 'F5F5F5', gridLine: 'D6D6D6', ink: '26303C', muted: '5E6B7B', black: '000000', white: 'FFFFFF',
  brown: '875330', tan: 'B8845E', keyLabel: '234860',
  greenBg: 'E5F0EA', greenLine: 'BED8C9', green: '2B7352',
  chartBar: 'C0504D', bars3d: ['4F81BD', 'C0504D', '9BBB59'], gold: 'E8C25A',
} as const;

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
} as const;
