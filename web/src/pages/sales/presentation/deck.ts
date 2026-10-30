import type PptxGenJS from 'pptxgenjs';
import type { DeckNotes, PresentationCategory, PresentationData, SectionKey, ThemeKey } from './types';

// ---------------------------------------------------------------- themes

export interface DeckTheme {
  label: string; dark: boolean;
  bg: string; card: string; line: string; ink: string; muted: string;
  primary: string; accent: string; positive: string; negative: string;
  cover: string; coverInk: string; palette: string[];
}

export const THEMES: Record<ThemeKey, DeckTheme> = {
  midnight: {
    label: 'Midnight', dark: true, bg: '0B1220', card: '141D31', line: '24314D', ink: 'F1F5F9', muted: '94A3B8',
    primary: '38BDF8', accent: 'A78BFA', positive: '34D399', negative: 'F87171', cover: '070C17', coverInk: 'FFFFFF',
    palette: ['38BDF8', 'A78BFA', '34D399', 'FBBF24', 'F472B6', '60A5FA', 'FB923C', '94A3B8'],
  },
  corporate: {
    label: 'Corporate', dark: false, bg: 'FFFFFF', card: 'F5F7FC', line: 'E2E8F0', ink: '0F172A', muted: '64748B',
    primary: '4F46E5', accent: '0EA5E9', positive: '059669', negative: 'DC2626', cover: '1E1B4B', coverInk: 'FFFFFF',
    palette: ['4F46E5', '0EA5E9', '10B981', 'F59E0B', 'EC4899', '8B5CF6', '14B8A6', '94A3B8'],
  },
  emerald: {
    label: 'Granite & Emerald', dark: false, bg: 'FAFAF7', card: 'FFFFFF', line: 'E7E5E4', ink: '1C1917', muted: '78716C',
    primary: '047857', accent: 'CA8A04', positive: '15803D', negative: 'B91C1C', cover: '052E26', coverInk: 'FFFFFF',
    palette: ['047857', 'CA8A04', '0D9488', '65A30D', '0369A1', 'B45309', '57534E', 'A8A29E'],
  },
  sunset: {
    label: 'Sunset', dark: true, bg: '1C1917', card: '292524', line: '44403C', ink: 'FAFAF9', muted: 'A8A29E',
    primary: 'F97316', accent: 'FACC15', positive: '4ADE80', negative: 'FB7185', cover: '0C0A09', coverInk: 'FFFFFF',
    palette: ['F97316', 'FACC15', 'FB7185', 'A3E635', '38BDF8', 'C084FC', '2DD4BF', 'D6D3D1'],
  },
};

// ---------------------------------------------------------------- sections

export const SECTIONS: Array<{ key: SectionKey; title: string; hint: string }> = [
  { key: 'agenda', title: 'Agenda', hint: 'What the deck covers' },
  { key: 'summary', title: 'Executive summary', hint: 'Headline KPIs vs previous period + insights' },
  { key: 'funnel', title: 'Commercial funnel', hint: 'Quotes → orders → invoices → cash' },
  { key: 'trend', title: 'Revenue trend', hint: 'Revenue and margin over time' },
  { key: 'mix', title: 'Revenue mix by category', hint: 'Share of revenue per product category' },
  { key: 'categories', title: 'Category deep-dives', hint: 'Product volume, revenue and share per category' },
  { key: 'customers', title: 'Top customers', hint: 'Who bought the most, and how that changed' },
  { key: 'team', title: 'Sales team performance', hint: 'Revenue and margin by salesperson' },
  { key: 'pipeline', title: 'Pipeline & opportunities', hint: 'Open, won and lost CRM opportunities' },
  { key: 'lost', title: 'Lost business', hint: 'Cancelled orders, rejected/expired quotes, lost reasons' },
  { key: 'receivables', title: 'Collections & receivables', hint: 'Cash collected and AR ageing' },
  { key: 'activity', title: 'Field & marketing activity', hint: 'Visits, calls, meetings and marketing notes' },
  { key: 'forecast', title: 'Outlook', hint: 'Three-month revenue forecast' },
  { key: 'risks', title: 'Key observations & risks', hint: 'Advisor findings that need attention' },
  { key: 'actions', title: 'Follow-up action plan', hint: 'Priorities for the coming period' },
  { key: 'closing', title: 'Closing slide', hint: 'Thank you / questions' },
];

// ---------------------------------------------------------------- formatting

const nf0 = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });
const nf2 = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const nfq = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });

/** 130553381 → "130.6M". */
export function compact(v: number): string {
  const a = Math.abs(v);
  if (a >= 1e9) return `${(v / 1e9).toFixed(a >= 1e10 ? 1 : 2)}B`;
  if (a >= 1e6) return `${(v / 1e6).toFixed(a >= 1e7 ? 1 : 2)}M`;
  if (a >= 1e4) return `${(v / 1e3).toFixed(1)}K`;
  return nf0.format(v);
}
const amount = (v: number) => (Math.abs(v) >= 1000 ? nf0.format(v) : nf2.format(v));
const qty = (v: number | null | undefined) => (v == null ? '—' : nfq.format(v));
const pctText = (v: number | null | undefined) => (v == null ? '—' : `${v.toFixed(1)}%`);
const signed = (v: number | null | undefined) => (v == null ? 'new' : `${v > 0 ? '▲' : v < 0 ? '▼' : '■'} ${Math.abs(v).toFixed(1)}%`);

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const d = (iso: string) => { const [y, m, day] = iso.split('-').map(Number); return { y, m, day }; };
export function shortDate(iso: string): string { const x = d(iso); return `${x.day} ${MONTHS[x.m - 1]}`; }
export function longDate(iso: string): string { const x = d(iso); return `${x.day} ${MONTHS[x.m - 1]} ${x.y}`; }
export function rangeLabel(from: string, to: string): string {
  const a = d(from); const b = d(to);
  if (from === to) return longDate(to);
  if (a.y === b.y && a.m === b.m) return `${a.day}–${b.day} ${MONTHS[b.m - 1]} ${b.y}`;
  if (a.y === b.y) return `${shortDate(from)} – ${longDate(to)}`;
  return `${longDate(from)} – ${longDate(to)}`;
}
const bucketLabel = (b: { from: string; to: string }, granularity: string) =>
  granularity === 'month' ? `${MONTHS[d(b.from).m - 1]} ${String(d(b.from).y).slice(2)}` : b.from === b.to ? shortDate(b.from) : `${shortDate(b.from)}–${shortDate(b.to)}`;

/** Excel number format that scales axis labels to K / M. */
const axisFormat = (max: number) => (max >= 1e6 ? '#,##0.0,,"M"' : max >= 1e4 ? '#,##0,"K"' : '#,##0');

/** "this week" / "this month" / "this period" wording for a period length. */
export function periodWord(days: number): { noun: string; prev: string } {
  if (days === 7) return { noun: 'this week', prev: 'last week' };
  if (days >= 28 && days <= 31) return { noun: 'this month', prev: 'last month' };
  if (days >= 89 && days <= 92) return { noun: 'this quarter', prev: 'last quarter' };
  return { noun: 'this period', prev: 'the previous period' };
}

// ---------------------------------------------------------------- insights

/** Plain-language observations derived from the numbers; used on the summary slide and as speaker notes. */
export function insights(data: PresentationData): string[] {
  const k = data.kpis;
  const cur = data.organization.currencyCode;
  const w = periodWord(data.period.days);
  const out: string[] = [];
  const rc = k.revenue.changePercent;
  out.push(rc == null
    ? `Net invoiced revenue ${w.noun} reached ${cur} ${compact(k.revenue.current)}.`
    : `Net invoiced revenue ${rc >= 0 ? 'grew' : 'fell'} ${Math.abs(rc).toFixed(1)}% to ${cur} ${compact(k.revenue.current)} versus ${w.prev} (${cur} ${compact(k.revenue.previous)}).`);
  const top = data.categories[0];
  if (top && top.revenue > 0) out.push(`${top.label} led the mix with ${pctText(top.sharePercent)} of revenue (${cur} ${compact(top.revenue)}).`);
  const mover = [...data.categories].filter((c) => c.changePercent != null && c.key !== '__other').sort((a, b) => Math.abs(b.changePercent!) - Math.abs(a.changePercent!))[0];
  if (mover && mover !== top && Math.abs(mover.changePercent!) >= 10) out.push(`${mover.label} ${mover.changePercent! > 0 ? 'rose' : 'dropped'} ${Math.abs(mover.changePercent!).toFixed(0)}% against ${w.prev}.`);
  if (k.marginPercent.current != null) {
    const delta = k.marginPercent.previous == null ? null : k.marginPercent.current - k.marginPercent.previous;
    out.push(`Gross margin ${delta == null ? 'stands at' : delta >= 0 ? 'improved to' : 'eased to'} ${pctText(k.marginPercent.current)}${delta == null ? '' : ` (${delta >= 0 ? '+' : ''}${delta.toFixed(1)} pts)`}.`);
  }
  const best = data.topCustomers[0];
  if (best) out.push(`Largest customer: ${best.name} with ${cur} ${compact(best.revenue)} (${pctText(best.sharePercent)} of revenue).`);
  if (k.quotationsIssued.count > 0) out.push(`${k.quotationsIssued.count} quotations issued worth ${cur} ${compact(k.quotationsIssued.value)}; ${k.ordersConfirmed.count} orders confirmed (${cur} ${compact(k.ordersConfirmed.value)}).`);
  if (k.ordersLost.count > 0) out.push(`${k.ordersLost.count} orders / quotations lost worth ${cur} ${compact(k.ordersLost.value)} — see lost-business analysis.`);
  if (k.newCustomers > 0) out.push(`${k.newCustomers} new customer${k.newCustomers === 1 ? '' : 's'} placed a first invoice ${w.noun}.`);
  if (data.receivables.overdue > 0) out.push(`Overdue receivables stand at ${cur} ${compact(data.receivables.overdue)} across ${data.receivables.overdueCustomers} customers.`);
  if (data.pipeline.open.count > 0) out.push(`Open pipeline: ${data.pipeline.open.count} opportunities worth ${cur} ${compact(data.pipeline.open.value)} (weighted ${cur} ${compact(data.pipeline.open.weighted)}).`);
  return out;
}

// ---------------------------------------------------------------- builder

const W = 13.333;
const MX = 0.6;
const FONT = 'Segoe UI';
const FONT_HEAD = 'Segoe UI Semibold';
const lines = (s: string) => s.split('\n').map((l) => l.replace(/^\s*[-•*]\s*/, '').trim()).filter(Boolean);

type Slide = PptxGenJS.Slide;
type Cell = PptxGenJS.TableCell;

interface Ctx {
  pptx: PptxGenJS; t: DeckTheme; data: PresentationData; notes: DeckNotes; cur: string; section: number;
}

/** Builds the deck and triggers the browser download. */
export async function generateDeck(data: PresentationData, notes: DeckNotes, themeKey: ThemeKey, sections: Set<SectionKey>): Promise<string> {
  const { default: Pptx } = await import('pptxgenjs');
  const pptx = new Pptx();
  const t = THEMES[themeKey];
  pptx.layout = 'LAYOUT_WIDE';
  pptx.author = notes.presenter || data.organization.name;
  pptx.company = data.organization.name;
  pptx.title = notes.title;
  pptx.subject = `Sales review ${data.period.from} – ${data.period.to}`;
  pptx.theme = { headFontFace: FONT_HEAD, bodyFontFace: FONT };

  const footer = `${data.organization.name}  ·  ${notes.title}  ·  ${rangeLabel(data.period.from, data.period.to)}`;
  pptx.defineSlideMaster({
    title: 'CONTENT',
    background: { color: t.bg },
    objects: [
      { rect: { x: 0, y: 0, w: 0.12, h: 7.5, fill: { color: t.primary } } },
      { line: { x: MX, y: 7.0, w: W - 2 * MX, h: 0, line: { color: t.line, width: 0.75 } } },
      { text: { text: footer, options: { x: MX, y: 7.05, w: 10, h: 0.3, fontFace: FONT, fontSize: 9, color: t.muted } } },
    ],
    slideNumber: { x: W - MX - 0.6, y: 7.05, w: 0.6, h: 0.3, fontFace: FONT, fontSize: 9, color: t.muted, align: 'right' },
  });

  const ctx: Ctx = { pptx, t, data, notes, cur: data.organization.currencyCode, section: 0 };
  cover(ctx);
  const order = SECTIONS.map((s) => s.key).filter((k) => sections.has(k));
  for (const key of order) BUILDERS[key](ctx, order);

  const fileName = `Sales-Review_${data.period.from}_${data.period.to}.pptx`;
  await pptx.writeFile({ fileName });
  return fileName;
}

// ---------------------------------------------------------------- primitives

function content(ctx: Ctx, title: string, subtitle?: string): Slide {
  const { pptx, t } = ctx;
  const s = pptx.addSlide({ masterName: 'CONTENT' });
  ctx.section += 1;
  s.addText(String(ctx.section).padStart(2, '0'), { x: MX, y: 0.38, w: 0.8, h: 0.5, fontFace: FONT_HEAD, fontSize: 22, bold: true, color: t.primary, margin: 0 });
  s.addText(title, { x: MX + 0.75, y: 0.36, w: W - 2 * MX - 0.75, h: 0.55, fontFace: FONT_HEAD, fontSize: 26, bold: true, color: t.ink, margin: 0 });
  if (subtitle) s.addText(subtitle, { x: MX + 0.75, y: 0.9, w: W - 2 * MX - 0.75, h: 0.35, fontFace: FONT, fontSize: 12.5, color: t.muted, margin: 0 });
  s.addShape(pptx.ShapeType.rect, { x: MX + 0.77, y: 1.32, w: 0.9, h: 0.05, fill: { color: t.accent }, line: { color: t.accent, width: 0 } });
  return s;
}

/** Text colour on a primary / palette fill: dark themes use light fills, so dark text. */
const onFill = (t: DeckTheme) => (t.dark ? t.bg : 'FFFFFF');

function card(ctx: Ctx, s: Slide, x: number, y: number, w: number, h: number, accent?: string) {
  const { pptx, t } = ctx;
  s.addShape(pptx.ShapeType.roundRect, {
    x, y, w, h, rectRadius: 0.08, fill: { color: t.card }, line: { color: t.line, width: 0.75 },
    shadow: t.dark ? undefined : { type: 'outer', blur: 8, offset: 2, angle: 90, color: '0F172A', opacity: 0.08 },
  });
  if (accent) s.addShape(pptx.ShapeType.rect, { x: x + 0.18, y: y + 0.2, w: 0.06, h: h - 0.4, fill: { color: accent }, line: { color: accent, width: 0 } });
}

function kpi(ctx: Ctx, s: Slide, x: number, y: number, w: number, h: number, label: string, value: string, change: number | null | undefined, sub?: string, accent?: string, invert = false) {
  const { t } = ctx;
  const color = accent ?? t.primary;
  card(ctx, s, x, y, w, h, color);
  s.addText(label.toUpperCase(), { x: x + 0.38, y: y + 0.16, w: w - 0.5, h: 0.3, fontFace: FONT, fontSize: 9.5, bold: true, color: t.muted, charSpacing: 1, margin: 0 });
  s.addText(value, { x: x + 0.38, y: y + 0.42, w: w - 0.5, h: Math.min(0.6, h - 0.84), fontFace: FONT_HEAD, fontSize: h < 1.3 ? 21 : 24, bold: true, color: t.ink, margin: 0, valign: 'middle', fit: 'shrink' });
  const runs: PptxGenJS.TextProps[] = [];
  if (change !== undefined) {
    const good = change == null ? true : invert ? change <= 0 : change >= 0;
    runs.push({ text: signed(change), options: { color: change == null ? t.muted : good ? t.positive : t.negative, bold: true } });
  }
  if (sub) runs.push({ text: `${runs.length ? '  ' : ''}${sub}`, options: { color: t.muted } });
  if (runs.length) s.addText(runs, { x: x + 0.38, y: y + h - 0.4, w: w - 0.5, h: 0.28, fontFace: FONT, fontSize: 10, margin: 0 });
}

function empty(ctx: Ctx, s: Slide, text: string, y = 3.3) {
  s.addText(text, { x: MX, y, w: W - 2 * MX, h: 0.6, fontFace: FONT, fontSize: 16, italic: true, color: ctx.t.muted, align: 'center' });
}

function bullets(ctx: Ctx, s: Slide, items: string[], x: number, y: number, w: number, h: number, size = 13, marker?: string) {
  if (!items.length) return;
  s.addText(items.map((text, i) => ({
    text, options: { bullet: marker ? { code: marker } : { indent: 18 }, breakLine: i < items.length - 1, paraSpaceAfter: 6, color: ctx.t.ink },
  })), { x, y, w, h, fontFace: FONT, fontSize: size, valign: 'top', margin: 2, fit: 'shrink' });
}

function header(ctx: Ctx, labels: string[], aligns: Array<'left' | 'right' | 'center'>): Cell[] {
  const { t } = ctx;
  return labels.map((text, i) => ({ text, options: { bold: true, color: onFill(t), fill: { color: t.primary }, align: aligns[i], fontSize: 10.5 } }));
}

function row(ctx: Ctx, cells: Array<string | Cell>, aligns: Array<'left' | 'right' | 'center'>, i: number, bold = false): Cell[] {
  const { t } = ctx;
  const fill = bold ? (t.dark ? t.line : 'E8ECF6') : i % 2 ? t.card : t.bg;
  return cells.map((c, j) => {
    const base = typeof c === 'string' ? { text: c, options: {} } : c;
    return { text: base.text, options: { color: t.ink, fill: { color: fill }, align: aligns[j], bold, ...base.options } };
  });
}

function table(ctx: Ctx, s: Slide, rows: Cell[][], x: number, y: number, w: number, colW: number[], fontSize = 10.5) {
  const k = w / colW.reduce((a, b) => a + b, 0);
  s.addTable(rows, { x, y, w, colW: colW.map((c) => c * k), fontFace: FONT, fontSize, rowH: 0.34, valign: 'middle', margin: [2, 6, 2, 6], border: { type: 'solid', pt: 0.5, color: ctx.t.line } });
}

function delta(ctx: Ctx, v: number | null | undefined, invert = false): Cell {
  const { t } = ctx;
  const good = v == null || (invert ? v <= 0 : v >= 0);
  return { text: signed(v), options: { color: v == null ? t.muted : good ? t.positive : t.negative, bold: true } };
}

/** Shared chart styling. */
function chartBase(ctx: Ctx, max: number): PptxGenJS.IChartOpts {
  const { t } = ctx;
  return {
    fontFace: FONT,
    catAxisLabelColor: t.muted, valAxisLabelColor: t.muted, catAxisLabelFontSize: 10, valAxisLabelFontSize: 9,
    catAxisLineShow: false, valAxisLineShow: false,
    valGridLine: { color: t.line, style: 'solid', size: 0.5 }, catGridLine: { style: 'none' },
    valAxisLabelFormatCode: axisFormat(max),
    showLegend: true, legendPos: 'b', legendFontSize: 10, legendColor: t.muted, legendFontFace: FONT,
    dataLabelColor: t.ink, dataLabelFontSize: 9, dataLabelFormatCode: axisFormat(max),
  };
}

/** Multi-type chart: pptxgenjs reads the options from the 2nd argument here (its typings say otherwise). */
function combo(s: Slide, types: PptxGenJS.IChartMulti[], opts: PptxGenJS.IChartOpts) {
  (s.addChart as unknown as (t: PptxGenJS.IChartMulti[], o: PptxGenJS.IChartOpts) => Slide)(types, opts);
}

function observation(ctx: Ctx, s: Slide, title: string, text: string, x: number, y: number, w: number, h: number) {
  const { t } = ctx;
  card(ctx, s, x, y, w, h, t.accent);
  s.addText([
    { text: title.toUpperCase(), options: { bold: true, color: t.accent, fontSize: 9.5, charSpacing: 1, breakLine: true } },
    { text, options: { color: t.ink, fontSize: 12 } },
  ], { x: x + 0.38, y: y + 0.12, w: w - 0.55, h: h - 0.24, fontFace: FONT, valign: 'top', margin: 0, paraSpaceAfter: 4, fit: 'shrink' });
}

// ---------------------------------------------------------------- slides

function cover(ctx: Ctx) {
  const { pptx, t, data, notes } = ctx;
  const s = pptx.addSlide();
  s.background = { color: t.cover };
  s.addShape(pptx.ShapeType.ellipse, { x: 8.4, y: -2.2, w: 7.2, h: 7.2, fill: { color: t.primary, transparency: 78 }, line: { color: t.primary, width: 0 } });
  s.addShape(pptx.ShapeType.ellipse, { x: 10.6, y: 2.6, w: 4.6, h: 4.6, fill: { color: t.accent, transparency: 80 }, line: { color: t.accent, width: 0 } });
  s.addShape(pptx.ShapeType.ellipse, { x: 7.6, y: 4.9, w: 1.6, h: 1.6, fill: { color: t.primary, transparency: 55 }, line: { color: t.primary, width: 0 } });
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: 0.18, h: 7.5, fill: { color: t.primary }, line: { color: t.primary, width: 0 } });
  s.addText(data.organization.name.toUpperCase(), { x: 0.9, y: 0.8, w: 8, h: 0.4, fontFace: FONT_HEAD, fontSize: 13, bold: true, color: t.primary, charSpacing: 3, margin: 0 });
  s.addText(notes.title, { x: 0.9, y: 2.0, w: 8.6, h: 1.7, fontFace: FONT_HEAD, fontSize: 44, bold: true, color: t.coverInk, valign: 'bottom', margin: 0, fit: 'shrink' });
  if (notes.subtitle) s.addText(notes.subtitle, { x: 0.9, y: 3.8, w: 8.4, h: 0.5, fontFace: FONT, fontSize: 18, color: 'CBD5E1', margin: 0 });
  s.addShape(pptx.ShapeType.roundRect, { x: 0.9, y: 4.55, w: 4.2, h: 0.55, rectRadius: 0.27, fill: { color: t.primary }, line: { color: t.primary, width: 0 } });
  s.addText(rangeLabel(data.period.from, data.period.to), { x: 0.9, y: 4.55, w: 4.2, h: 0.55, fontFace: FONT_HEAD, fontSize: 15, bold: true, color: onFill(t), align: 'center', valign: 'middle', margin: 0 });
  const who = [notes.presenter && { text: notes.presenter, options: { bold: true, color: t.coverInk, breakLine: true } },
    notes.role && { text: notes.role, options: { color: 'CBD5E1', breakLine: true } },
    { text: `Report date: ${longDate(new Date().toISOString().slice(0, 10))}`, options: { color: '94A3B8' } }].filter(Boolean) as PptxGenJS.TextProps[];
  s.addText(who, { x: 0.9, y: 5.75, w: 7, h: 1.1, fontFace: FONT, fontSize: 13, valign: 'top', margin: 0 });
  s.addNotes(insights(data).join('\n'));
}

function agenda(ctx: Ctx, order: SectionKey[]) {
  const { t } = ctx;
  const items = SECTIONS.filter((x) => order.includes(x.key) && !['agenda', 'closing'].includes(x.key));
  const s = content(ctx, 'Agenda', `Sales review for ${rangeLabel(ctx.data.period.from, ctx.data.period.to)}`);
  let next = ctx.section + 1;
  const numbers = items.map((it) => { const n = next; next += it.key === 'categories' ? deepDives(ctx.data).length || 1 : 1; return n; });
  const perCol = Math.ceil(items.length / 2);
  items.forEach((it, i) => {
    const col = i < perCol ? 0 : 1;
    const r = i % perCol;
    const x = MX + 0.75 + col * 6.1;
    const y = 1.75 + r * 0.66;
    s.addText(String(numbers[i]).padStart(2, '0'), { x, y, w: 0.7, h: 0.5, fontFace: FONT_HEAD, fontSize: 20, bold: true, color: ctx.t.palette[i % ctx.t.palette.length], margin: 0 });
    s.addText([{ text: it.title, options: { bold: true, color: t.ink, fontSize: 15, breakLine: true } }, { text: it.hint, options: { color: t.muted, fontSize: 10.5 } }],
      { x: x + 0.75, y: y - 0.04, w: 5.1, h: 0.6, fontFace: FONT, margin: 0, valign: 'top' });
  });
}

function summary(ctx: Ctx) {
  const { data, cur, t, notes } = ctx;
  const k = data.kpis;
  const w = periodWord(data.period.days);
  const s = content(ctx, 'Executive summary', `${rangeLabel(data.period.from, data.period.to)} compared with ${rangeLabel(data.previousPeriod.from, data.previousPeriod.to)}`);
  const cw = (W - 2 * MX - 0.75 - 3 * 0.25) / 4;
  const x0 = MX + 0.75;
  kpi(ctx, s, x0, 1.65, cw, 1.4, `Revenue (${cur})`, compact(k.revenue.current), k.revenue.changePercent, `vs ${w.prev}`, t.palette[0]);
  kpi(ctx, s, x0 + (cw + 0.25), 1.65, cw, 1.4, 'Gross margin', pctText(k.marginPercent.current), k.margin.changePercent, `${cur} ${compact(k.margin.current)}`, t.palette[1]);
  kpi(ctx, s, x0 + 2 * (cw + 0.25), 1.65, cw, 1.4, 'Orders confirmed', String(k.ordersConfirmed.count), k.ordersConfirmed.previousCount ? ((k.ordersConfirmed.count - k.ordersConfirmed.previousCount) / k.ordersConfirmed.previousCount) * 100 : null, `${cur} ${compact(k.ordersConfirmed.value)}`, t.palette[2]);
  kpi(ctx, s, x0 + 3 * (cw + 0.25), 1.65, cw, 1.4, `Cash collected (${cur})`, compact(k.collections.current), k.collections.changePercent, `vs ${w.prev}`, t.palette[3]);
  kpi(ctx, s, x0, 3.25, cw, 1.4, 'Invoices issued', String(k.invoices.current), k.invoices.previous ? ((k.invoices.current - k.invoices.previous) / k.invoices.previous) * 100 : null, `avg ${cur} ${compact(k.averageInvoice.current)}`, t.palette[4]);
  kpi(ctx, s, x0 + (cw + 0.25), 3.25, cw, 1.4, 'Active customers', String(k.activeCustomers.current), k.activeCustomers.previous ? ((k.activeCustomers.current - k.activeCustomers.previous) / k.activeCustomers.previous) * 100 : null, `${k.newCustomers} new`, t.palette[5]);
  kpi(ctx, s, x0 + 2 * (cw + 0.25), 3.25, cw, 1.4, 'Quotations issued', String(k.quotationsIssued.count), undefined, `${cur} ${compact(k.quotationsIssued.value)}`, t.palette[6]);
  kpi(ctx, s, x0 + 3 * (cw + 0.25), 3.25, cw, 1.4, 'Orders lost', String(k.ordersLost.count), undefined, `${cur} ${compact(k.ordersLost.value)}`, t.negative);
  const hl = [...lines(notes.highlights), ...insights(data)].slice(0, 5);
  observation(ctx, s, 'Highlights', '', x0, 4.85, W - MX - x0, 1.95);
  bullets(ctx, s, hl, x0 + 0.38, 5.15, W - MX - x0 - 0.6, 1.6, 12);
  s.addNotes(insights(data).join('\n'));
}

function funnel(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const k = data.kpis;
  const w = periodWord(data.period.days);
  const s = content(ctx, 'Commercial funnel', `From quotation to cash, ${w.noun}`);
  const stages = [
    { label: 'Quotations issued', count: k.quotationsIssued.count, value: k.quotationsIssued.value },
    { label: 'Orders confirmed', count: k.ordersConfirmed.count, value: k.ordersConfirmed.value },
    { label: 'Invoiced revenue', count: k.invoices.current, value: k.revenue.current },
    { label: 'Cash collected', count: null, value: k.collections.current },
  ];
  stages.forEach((st, i) => {
    const bw = 6.2 - i * 0.9;
    const x = MX + 0.75 + (6.2 - bw) / 2;
    const y = 1.75 + i * 1.18;
    s.addShape(pptx.ShapeType.roundRect, { x, y, w: bw, h: 1.0, rectRadius: 0.1, fill: { color: t.palette[i] }, line: { color: t.palette[i], width: 0 } });
    s.addText([{ text: st.label.toUpperCase(), options: { fontSize: 10, bold: true, charSpacing: 1, breakLine: true } },
      { text: `${cur} ${compact(st.value)}${st.count != null ? `  ·  ${st.count}` : ''}`, options: { fontSize: 18, bold: true } }],
    { x, y, w: bw, h: 1.0, fontFace: FONT_HEAD, color: onFill(t), align: 'center', valign: 'middle', margin: 0 });
  });
  const aligns: Array<'left' | 'right'> = ['left', 'right', 'right', 'right'];
  const trs = [
    header(ctx, ['Metric', 'This period', 'Previous', 'Change'], aligns),
    row(ctx, ['Revenue (net invoiced)', amount(k.revenue.current), amount(k.revenue.previous), delta(ctx, k.revenue.changePercent)], aligns, 0),
    row(ctx, ['Gross margin', amount(k.margin.current), amount(k.margin.previous), delta(ctx, k.margin.changePercent)], aligns, 1),
    row(ctx, ['Quotations issued', String(k.quotationsIssued.count), String(k.quotationsIssued.previousCount), '—'], aligns, 2),
    row(ctx, ['Quote conversion', pctText(k.quotationsIssued.conversionPercent), '—', '—'], aligns, 3),
    row(ctx, ['Orders confirmed', String(k.ordersConfirmed.count), String(k.ordersConfirmed.previousCount), '—'], aligns, 4),
    row(ctx, ['Orders confirmed value', amount(k.ordersConfirmed.value), amount(k.ordersConfirmed.previousValue), delta(ctx, k.ordersConfirmed.previousValue ? ((k.ordersConfirmed.value - k.ordersConfirmed.previousValue) / k.ordersConfirmed.previousValue) * 100 : null)], aligns, 5),
    row(ctx, ['Orders / quotes lost', String(k.ordersLost.count), '—', '—'], aligns, 6),
    row(ctx, ['Invoices issued', String(k.invoices.current), String(k.invoices.previous), '—'], aligns, 7),
    row(ctx, ['Cash collected', amount(k.collections.current), amount(k.collections.previous), delta(ctx, k.collections.changePercent)], aligns, 8),
    row(ctx, ['New customers', String(k.newCustomers), '—', '—'], aligns, 9),
  ];
  table(ctx, s, trs, 7.75, 1.75, W - MX - 7.75, [2.2, 1.35, 1.35, 0.98], 10);
}

function trend(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const tr = data.trend;
  const sub = tr.granularity === 'month' ? 'Monthly revenue and gross margin across the period' : `Last ${tr.buckets.length} periods of ${data.period.days} day${data.period.days === 1 ? '' : 's'}, ending ${longDate(data.period.to)}`;
  const s = content(ctx, 'Revenue trend', sub);
  const labels = tr.buckets.map((b) => bucketLabel(b, tr.granularity));
  const max = Math.max(1, ...tr.buckets.map((b) => b.revenue));
  if (!tr.buckets.some((b) => b.revenue !== 0)) { empty(ctx, s, 'No invoiced revenue in this window.'); return; }
  card(ctx, s, MX + 0.75, 1.6, 6.3, 5.2);
  s.addText(`Revenue & margin (${cur})`, { x: MX + 1.0, y: 1.7, w: 5.5, h: 0.35, fontFace: FONT_HEAD, fontSize: 12, bold: true, color: t.ink, margin: 0 });
  combo(s, [
    { type: pptx.ChartType.bar, data: [{ name: 'Revenue', labels, values: tr.buckets.map((b) => b.revenue) }], options: { chartColors: [t.primary], barGapWidthPct: 60 } },
    { type: pptx.ChartType.line, data: [{ name: 'Gross margin', labels, values: tr.buckets.map((b) => b.margin) }], options: { chartColors: [t.accent], lineSize: 3, lineDataSymbol: 'circle', lineDataSymbolSize: 8 } },
  ], { ...chartBase(ctx, max), x: MX + 0.9, y: 2.1, w: 6.0, h: 4.6, showValue: false });
  card(ctx, s, 7.3, 1.6, W - MX - 7.3, 5.2);
  s.addText('Revenue by category', { x: 7.55, y: 1.7, w: 5, h: 0.35, fontFace: FONT_HEAD, fontSize: 12, bold: true, color: t.ink, margin: 0 });
  if (tr.series.length) {
    s.addChart(pptx.ChartType.bar, tr.series.map((se) => ({ name: se.label, labels, values: tr.buckets.map((b) => b.byCategory[se.key] ?? 0) })),
      { ...chartBase(ctx, max), x: 7.45, y: 2.1, w: W - MX - 7.45 - 0.15, h: 4.6, barDir: 'col', barGrouping: 'stacked', chartColors: t.palette, barGapWidthPct: 60 });
  }
  const first = tr.buckets[0].revenue; const last = tr.buckets[tr.buckets.length - 1].revenue;
  s.addNotes(`Revenue moved from ${cur} ${compact(first)} to ${cur} ${compact(last)} across the window.`);
}

function mix(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const s = content(ctx, 'Revenue mix by category', 'Share of net invoiced revenue, with change against the previous period');
  const cats = data.categories.filter((c) => c.revenue > 0);
  if (!cats.length) { empty(ctx, s, 'No invoiced sales in this period.'); return; }
  card(ctx, s, MX + 0.75, 1.6, 4.9, 5.2);
  s.addChart(pptx.ChartType.doughnut, [{ name: 'Revenue', labels: cats.map((c) => c.label), values: cats.map((c) => c.revenue) }], {
    x: MX + 0.85, y: 1.7, w: 4.7, h: 5.0, holeSize: 58, chartColors: t.palette, showPercent: true, showValue: false, showLabel: false,
    showLegend: true, legendPos: 'b', legendFontSize: 9.5, legendColor: t.muted, legendFontFace: FONT, dataLabelColor: 'FFFFFF', dataLabelFontSize: 10, dataBorder: { pt: 1.5, color: t.card },
  });
  s.addText([{ text: compact(data.kpis.revenue.current), options: { fontSize: 22, bold: true, color: t.ink, breakLine: true } }, { text: cur, options: { fontSize: 10, color: t.muted } }],
    { x: MX + 2.25, y: 3.35, w: 1.9, h: 0.8, fontFace: FONT_HEAD, align: 'center', valign: 'middle', margin: 0 });
  const aligns: Array<'left' | 'right'> = ['left', 'right', 'right', 'right', 'right'];
  const rows = [header(ctx, ['Category', `Revenue (${cur})`, 'Share', 'Margin', 'vs prev.'], aligns)];
  data.categories.forEach((c, i) => rows.push(row(ctx, [
    { text: c.label, options: { bold: true, color: t.ink } } as Cell, amount(c.revenue), pctText(c.sharePercent), pctText(c.marginPercent), delta(ctx, c.changePercent)], aligns, i)));
  rows.push(row(ctx, ['Total', amount(data.kpis.revenue.current), '100%', pctText(data.kpis.marginPercent.current), delta(ctx, data.kpis.revenue.changePercent)], aligns, 0, true));
  table(ctx, s, rows, 6.55, 1.6, W - MX - 6.55, [2.05, 1.45, 0.8, 0.8, 1.0]);
}

/** Categories that get their own slide (top five with product lines). */
export const deepDives = (data: PresentationData) => data.categories.filter((c) => c.key !== '__other' && c.products.length > 0).slice(0, 5);

function categoryDeepDives(ctx: Ctx) {
  const deep = deepDives(ctx.data);
  if (!deep.length) {
    empty(ctx, content(ctx, 'Category performance', 'Product volume, revenue and contribution'), 'No product sales in this period.');
    return;
  }
  deep.forEach((c) => categorySlide(ctx, c));
}

function categorySlide(ctx: Ctx, c: PresentationCategory) {
  const { pptx, cur, t, data } = ctx;
  const s = content(ctx, `${c.label} — sales performance`, 'Product volume, revenue and revenue contribution');
  const aligns: Array<'left' | 'right'> = ['left', 'right', 'right', 'right'];
  const unit = c.unit ? ` (${c.unit})` : '';
  const rows = [header(ctx, ['Product', `Volume${unit}`, `Revenue (${cur})`, 'Share'], aligns)];
  c.products.forEach((p, i) => rows.push(row(ctx, [p.label, `${qty(p.quantity)}${!c.unit && p.unit ? ` ${p.unit}` : ''}`, amount(p.revenue), pctText(p.sharePercent)], aligns, i)));
  if (c.productCount > c.products.length) rows.push(row(ctx, [{ text: `+ ${c.productCount - c.products.length} more products`, options: { italic: true, color: t.muted } } as Cell, '', '', ''], aligns, c.products.length));
  rows.push(row(ctx, ['TOTAL', c.quantity == null ? '—' : qty(c.quantity), amount(c.revenue), '100%'], aligns, 0, true));
  table(ctx, s, rows, MX + 0.75, 1.6, 5.85, [2.6, 1.15, 1.45, 0.75]);
  s.addText('Share = product revenue ÷ category revenue × 100', { x: MX + 0.75, y: 6.6, w: 5.85, h: 0.3, fontFace: FONT, fontSize: 9, italic: true, color: t.muted, margin: 0 });

  const top = c.products.slice(0, 7);
  const max = Math.max(1, ...top.map((p) => p.revenue));
  card(ctx, s, 7.45, 1.6, W - MX - 7.45, 3.45);
  s.addChart(pptx.ChartType.bar, [{ name: 'Revenue', labels: top.map((p) => p.label), values: top.map((p) => p.revenue) }], {
    ...chartBase(ctx, max), x: 7.55, y: 1.7, w: W - MX - 7.45 - 0.2, h: 3.25, barDir: 'bar', chartColors: t.palette, showLegend: false,
    showValue: true, dataLabelPosition: 'outEnd', valAxisHidden: true, valGridLine: { style: 'none' }, catAxisOrientation: 'maxMin' as 'minMax', barGapWidthPct: 45,
  });
  const lead = c.products[0];
  const w = periodWord(data.period.days);
  const text = `${lead.label} is the top seller with ${pctText(lead.sharePercent)} of ${c.label} revenue (${cur} ${compact(lead.revenue)}). `
    + `${c.label} contributed ${pctText(c.sharePercent)} of total revenue`
    + (c.changePercent == null ? '.' : ` and ${c.changePercent >= 0 ? 'grew' : 'declined'} ${Math.abs(c.changePercent).toFixed(1)}% versus ${w.prev}.`)
    + (c.marginPercent != null ? ` Gross margin ${pctText(c.marginPercent)}.` : '');
  observation(ctx, s, 'Key observation', text, 7.45, 5.25, W - MX - 7.45, 1.6);
  s.addNotes(text);
}

function customers(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const s = content(ctx, 'Top customers', 'Largest accounts by net invoiced revenue');
  const rowsData = data.topCustomers;
  if (!rowsData.length) { empty(ctx, s, 'No customer invoices in this period.'); return; }
  const top = rowsData.slice(0, 8);
  const max = Math.max(1, ...top.map((c) => c.revenue));
  card(ctx, s, MX + 0.75, 1.6, 5.5, 5.2);
  s.addChart(pptx.ChartType.bar, [{ name: 'Revenue', labels: top.map((c) => c.name), values: top.map((c) => c.revenue) }], {
    ...chartBase(ctx, max), x: MX + 0.85, y: 1.7, w: 5.3, h: 5.0, barDir: 'bar', chartColors: [t.primary], showLegend: false, showValue: true,
    dataLabelPosition: 'outEnd', valAxisHidden: true, valGridLine: { style: 'none' }, catAxisOrientation: 'maxMin' as 'minMax', barGapWidthPct: 40,
  });
  const aligns: Array<'left' | 'right' | 'center'> = ['center', 'left', 'right', 'right', 'right'];
  const rows = [header(ctx, ['#', 'Customer', `Revenue (${cur})`, 'Share', 'vs prev.'], aligns)];
  rowsData.forEach((c, i) => rows.push(row(ctx, [String(i + 1), c.name, amount(c.revenue), pctText(c.sharePercent), delta(ctx, c.changePercent)], aligns, i)));
  table(ctx, s, rows, 6.5, 1.6, W - MX - 6.5, [0.4, 2.55, 1.45, 0.85, 0.98]);
}

function team(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const s = content(ctx, 'Sales team performance', 'Revenue attributed to the invoice / order salesperson');
  const sp = data.salespeople;
  if (!sp.length) { empty(ctx, s, 'No attributed revenue in this period.'); return; }
  const max = Math.max(1, ...sp.map((p) => p.revenue));
  card(ctx, s, MX + 0.75, 1.6, 6.0, 5.2);
  s.addChart(pptx.ChartType.bar, [
    { name: 'Revenue', labels: sp.map((p) => p.name), values: sp.map((p) => p.revenue) },
    { name: 'Gross margin', labels: sp.map((p) => p.name), values: sp.map((p) => p.margin) },
  ], { ...chartBase(ctx, max), x: MX + 0.85, y: 1.7, w: 5.8, h: 5.0, barDir: 'col', chartColors: [t.primary, t.accent], barGapWidthPct: 50 });
  const aligns: Array<'left' | 'right'> = ['left', 'right', 'right', 'right', 'right'];
  const rows = [header(ctx, ['Salesperson', `Revenue (${cur})`, 'Margin %', 'Customers', 'Invoices'], aligns)];
  sp.forEach((p, i) => rows.push(row(ctx, [p.name, amount(p.revenue), pctText(p.marginPercent), String(p.customers), String(p.invoices)], aligns, i)));
  table(ctx, s, rows, 7.0, 1.6, W - MX - 7.0, [2.0, 1.45, 0.9, 0.9, 0.88]);
}

function pipeline(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const p = data.pipeline;
  const s = content(ctx, 'Pipeline & opportunities', 'CRM opportunities: open book, wins and losses in the period');
  const cw = (W - 2 * MX - 0.75 - 3 * 0.25) / 4;
  const x0 = MX + 0.75;
  kpi(ctx, s, x0, 1.6, cw, 1.25, 'Open pipeline', `${cur} ${compact(p.open.value)}`, undefined, `${p.open.count} opportunities`, t.palette[0]);
  kpi(ctx, s, x0 + cw + 0.25, 1.6, cw, 1.25, 'Weighted value', `${cur} ${compact(p.open.weighted)}`, undefined, 'probability-adjusted', t.palette[1]);
  kpi(ctx, s, x0 + 2 * (cw + 0.25), 1.6, cw, 1.25, 'Won', `${cur} ${compact(p.won.value)}`, undefined, `${p.won.count} deals · win rate ${pctText(p.winRatePercent)}`, t.positive);
  kpi(ctx, s, x0 + 3 * (cw + 0.25), 1.6, cw, 1.25, 'New leads / opps', `${p.newLeads} / ${p.newOpportunities}`, undefined, `${p.lost.count} lost (${cur} ${compact(p.lost.value)})`, t.palette[3]);
  if (p.byStage.length) {
    const max = Math.max(1, ...p.byStage.map((x) => x.value));
    card(ctx, s, x0, 3.05, 4.6, 3.75);
    s.addText('Open value by stage', { x: x0 + 0.25, y: 3.12, w: 4, h: 0.35, fontFace: FONT_HEAD, fontSize: 12, bold: true, color: t.ink, margin: 0 });
    s.addChart(pptx.ChartType.bar, [{ name: 'Value', labels: p.byStage.map((x) => `${x.stage} (${x.count})`), values: p.byStage.map((x) => x.value) }], {
      ...chartBase(ctx, max), x: x0 + 0.1, y: 3.45, w: 4.4, h: 3.3, barDir: 'bar', chartColors: t.palette, showLegend: false, showValue: true,
      dataLabelPosition: 'outEnd', valAxisHidden: true, valGridLine: { style: 'none' }, catAxisOrientation: 'maxMin' as 'minMax', barGapWidthPct: 40,
    });
  }
  const tx0 = p.byStage.length ? x0 + 4.85 : x0;
  const aligns: Array<'left' | 'right'> = ['left', 'left', 'left', 'right', 'right', 'left'];
  const rows = [header(ctx, ['Opportunity', 'Customer', 'Stage', `Value (${cur})`, 'Prob.', 'Close'], aligns)];
  p.topOpen.forEach((o, i) => rows.push(row(ctx, [o.name, o.customer ?? '—', o.stage, amount(o.amount), `${o.probability}%`, o.expectedClose ? shortDate(o.expectedClose) : '—'], aligns, i)));
  if (p.topOpen.length) table(ctx, s, rows, tx0, 3.05, W - MX - tx0, p.byStage.length ? [1.9, 1.55, 1.05, 1.15, 0.6, 0.83] : [3.2, 2.6, 1.6, 1.6, 1.0, 1.43], 9.5);
  else empty(ctx, s, 'No open opportunities in the CRM.', 4.6);
}

function lost(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const s = content(ctx, 'Lost business', 'Cancelled orders, rejected or expired quotations and lost-opportunity reasons');
  const lb = data.lostBusiness;
  const reasons = data.pipeline.lostReasons;
  if (!lb.length && !reasons.length) { empty(ctx, s, 'No lost orders, quotations or opportunities in this period. 🎉'); return; }
  const tw = reasons.length ? 7.4 : W - 2 * MX - 0.75;
  if (lb.length) {
    const aligns: Array<'left' | 'right'> = ['left', 'left', 'left', 'right', 'left'];
    const rows = [header(ctx, ['Type', 'Number', 'Customer', `Value (${cur})`, 'Reason'], aligns)];
    lb.forEach((l, i) => rows.push(row(ctx, [l.kind === 'order' ? 'Order' : 'Quote', l.number, l.customer ?? '—', amount(l.value), l.reason ?? '—'], aligns, i)));
    const k = tw / 7.4;
    table(ctx, s, rows, MX + 0.75, 1.6, tw, [0.75, 1.15, 1.9, 1.3, 2.3].map((x) => x * k), 10);
  }
  if (reasons.length) {
    const x = MX + 0.75 + (lb.length ? 7.65 : 0);
    const w = W - MX - x;
    card(ctx, s, x, 1.6, w, 5.2);
    s.addText('Lost-opportunity reasons', { x: x + 0.25, y: 1.7, w: w - 0.4, h: 0.35, fontFace: FONT_HEAD, fontSize: 12, bold: true, color: t.ink, margin: 0 });
    s.addChart(pptx.ChartType.pie, [{ name: 'Lost', labels: reasons.map((r) => r.reason), values: reasons.map((r) => r.count) }], {
      x: x + 0.1, y: 2.05, w: w - 0.2, h: 4.65, chartColors: [t.negative, ...t.palette], showPercent: true, showLegend: true, legendPos: 'b',
      legendFontSize: 9.5, legendColor: t.muted, legendFontFace: FONT, dataLabelColor: 'FFFFFF', dataLabelFontSize: 10, dataBorder: { pt: 1.5, color: t.card },
    });
  }
  const k = data.kpis.ordersLost;
  s.addNotes(`${k.cancelledOrders} sales orders cancelled and ${k.lostQuotations} quotations rejected or expired, worth ${cur} ${compact(k.value)}.`);
}

function receivables(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const r = data.receivables;
  const k = data.kpis;
  const s = content(ctx, 'Collections & receivables', 'Cash received in the period and open customer balances as of today');
  const x0 = MX + 0.75;
  kpi(ctx, s, x0, 1.6, 3.6, 1.3, `Cash collected (${cur})`, compact(k.collections.current), k.collections.changePercent, `vs ${periodWord(data.period.days).prev}`, t.positive);
  kpi(ctx, s, x0, 3.1, 3.6, 1.3, `Open receivables (${cur})`, compact(r.total), undefined, 'all unpaid invoices', t.palette[0]);
  kpi(ctx, s, x0, 4.6, 3.6, 1.3, `Overdue (${cur})`, compact(r.overdue), undefined, `${r.overdueCustomers} customers`, t.negative);
  const collectionRate = k.revenue.current ? (k.collections.current / k.revenue.current) * 100 : null;
  s.addText(`Collections equal ${pctText(collectionRate)} of revenue invoiced in the period.`, { x: x0, y: 6.1, w: 3.6, h: 0.6, fontFace: FONT, fontSize: 10.5, color: t.muted, margin: 0 });
  const max = Math.max(1, ...r.aging.map((a) => a.amount));
  card(ctx, s, 5.2, 1.6, W - MX - 5.2, 5.2);
  s.addText(`Receivables ageing (${cur})`, { x: 5.45, y: 1.7, w: 6, h: 0.35, fontFace: FONT_HEAD, fontSize: 12, bold: true, color: t.ink, margin: 0 });
  s.addChart(pptx.ChartType.bar, [{ name: 'Amount', labels: r.aging.map((a) => a.bucket), values: r.aging.map((a) => a.amount) }], {
    ...chartBase(ctx, max), x: 5.3, y: 2.1, w: W - MX - 5.2 - 0.2, h: 4.6, barDir: 'col', showLegend: false, showValue: true, dataLabelPosition: 'outEnd',
    chartColors: [t.positive, t.accent, 'F59E0B', 'F97316', t.negative], barGapWidthPct: 50,
  });
}

function activity(ctx: Ctx) {
  const { data, t, notes } = ctx;
  const s = content(ctx, 'Field & marketing activity', 'Customer visits, calls and meetings logged in the CRM, plus marketing updates');
  const c = data.activities.counts;
  const tiles: Array<[string, number, string]> = [['Meetings / visits', c.meeting ?? 0, t.palette[0]], ['Calls', c.call ?? 0, t.palette[1]], ['Emails', c.email ?? 0, t.palette[2]], ['Tasks done', c.task ?? 0, t.palette[3]]];
  const x0 = MX + 0.75;
  const cw = (W - MX - x0 - 3 * 0.25) / 4;
  tiles.forEach(([label, n, col], i) => kpi(ctx, s, x0 + i * (cw + 0.25), 1.6, cw, 1.15, label, String(n), undefined, undefined, col));
  const hl = data.activities.highlights.map((a) => `${a.date ? `${shortDate(a.date)} · ` : ''}${a.title}${a.subject ? ` — ${a.subject}` : ''}`);
  const half = (W - MX - x0 - 0.25) / 2;
  observation(ctx, s, 'Visits & meetings', hl.length ? '' : 'No meetings or calls logged in the period.', x0, 2.95, half, 3.85);
  bullets(ctx, s, hl, x0 + 0.38, 3.3, half - 0.55, 3.4, 11.5);
  const mk = lines(notes.marketing);
  observation(ctx, s, 'Marketing update', mk.length ? '' : 'Add marketing notes on the presentation page to fill this panel.', x0 + half + 0.25, 2.95, half, 3.85);
  bullets(ctx, s, mk, x0 + half + 0.63, 3.3, half - 0.55, 3.4, 11.5);
}

function forecast(ctx: Ctx) {
  const { pptx, data, cur, t } = ctx;
  const f = data.forecast;
  const s = content(ctx, 'Outlook', `Revenue forecast for the next ${f.months.length} months · quotation conversion ${f.conversionRate}% (90 days)`);
  const labels = f.months.map((m) => { const [y, mo] = m.month.split('-').map(Number); return `${MONTHS[mo - 1]} ${y}`; });
  const max = Math.max(1, ...f.months.map((m) => Math.max(m.expected, m.openOrders + m.weightedQuotations + m.agreements)));
  card(ctx, s, MX + 0.75, 1.6, 7.0, 5.2);
  combo(s, [
    { type: pptx.ChartType.bar, data: [
      { name: 'Open orders', labels, values: f.months.map((m) => m.openOrders) },
      { name: 'Weighted quotations', labels, values: f.months.map((m) => m.weightedQuotations) },
      { name: 'Agreements', labels, values: f.months.map((m) => m.agreements) },
    ], options: { barGrouping: 'stacked', chartColors: [t.palette[0], t.palette[1], t.palette[2]], barGapWidthPct: 70 } },
    { type: pptx.ChartType.line, data: [{ name: 'Run rate', labels, values: f.months.map((m) => m.runRate) }], options: { chartColors: [t.accent], lineSize: 2, lineDash: 'dash', lineDataSymbol: 'none' } },
  ], { ...chartBase(ctx, max), x: MX + 0.85, y: 1.75, w: 6.8, h: 4.95 });
  const aligns: Array<'left' | 'right'> = ['left', 'right'];
  const rows = [header(ctx, ['Month', `Expected (${cur})`], aligns)];
  f.months.forEach((m, i) => rows.push(row(ctx, [labels[i], amount(m.expected)], aligns, i)));
  rows.push(row(ctx, ['Total', amount(f.months.reduce((a, m) => a + m.expected, 0))], aligns, 0, true));
  table(ctx, s, rows, 8.6, 1.6, W - MX - 8.6, [1.9, (W - MX - 8.6) - 1.9], 11);
  s.addText('Expected = the larger of the 3-month run rate and committed demand (open orders + weighted quotations + agreements).',
    { x: 8.6, y: 3.6, w: W - MX - 8.6, h: 1.0, fontFace: FONT, fontSize: 9.5, italic: true, color: t.muted, margin: 0, valign: 'top' });
}

function risks(ctx: Ctx) {
  const { pptx, data, t } = ctx;
  const s = content(ctx, 'Key observations & risks', 'Findings from the sales advisor that need attention');
  const adv = data.advisories.slice(0, 6);
  if (!adv.length) { empty(ctx, s, 'No advisories — the book looks healthy.'); return; }
  const x0 = MX + 0.75;
  const cw = (W - MX - x0 - 0.25) / 2;
  adv.forEach((a, i) => {
    const x = x0 + (i % 2) * (cw + 0.25);
    const y = 1.6 + Math.floor(i / 2) * 1.75;
    const col = a.severity === 'critical' ? t.negative : a.severity === 'warning' ? 'F59E0B' : t.primary;
    card(ctx, s, x, y, cw, 1.55, col);
    s.addShape(pptx.ShapeType.roundRect, { x: x + 0.4, y: y + 0.18, w: 1.0, h: 0.28, rectRadius: 0.14, fill: { color: col }, line: { color: col, width: 0 } });
    s.addText(a.severity.toUpperCase(), { x: x + 0.4, y: y + 0.18, w: 1.0, h: 0.28, fontFace: FONT, fontSize: 8, bold: true, color: 'FFFFFF', align: 'center', valign: 'middle', margin: 0 });
    s.addText([{ text: a.title, options: { bold: true, color: t.ink, fontSize: 12.5, breakLine: true } }, { text: a.detail, options: { color: t.muted, fontSize: 10.5 } }],
      { x: x + 0.4, y: y + 0.5, w: cw - 0.6, h: 0.95, fontFace: FONT, valign: 'top', margin: 0, fit: 'shrink' });
  });
}

function actions(ctx: Ctx) {
  const { data, notes, t } = ctx;
  const s = content(ctx, 'Follow-up action plan', 'Priorities for the coming period');
  type Item = { what: string; next: string; due: string; priority: 'High' | 'Medium' | 'Low' };
  const items: Item[] = [];
  lines(notes.actions).forEach((l) => {
    const [what, ...rest] = l.split(/\s+[—–-]\s+|:\s+/);
    items.push({ what: rest.length ? what : 'Team priority', next: rest.length ? rest.join(' — ') : l, due: '—', priority: 'High' });
  });
  data.activities.upcoming.forEach((a) => items.push({ what: a.subject ?? a.title, next: a.title, due: a.date ? shortDate(a.date) : '—', priority: 'Medium' }));
  data.advisories.filter((a) => a.severity !== 'info').slice(0, 4).forEach((a) => items.push({ what: a.title, next: a.detail, due: '—', priority: a.severity === 'critical' ? 'High' : 'Medium' }));
  data.pipeline.topOpen.slice(0, 3).forEach((o) => items.push({ what: `${o.customer ?? o.name} — ${o.name}`, next: `Advance from ${o.stage} toward a confirmed order`, due: o.expectedClose ? shortDate(o.expectedClose) : '—', priority: 'Medium' }));
  const list = items.slice(0, 10);
  if (!list.length) { empty(ctx, s, 'Add next actions on the presentation page to fill this plan.'); return; }
  const aligns: Array<'left' | 'center'> = ['center', 'left', 'left', 'center', 'center'];
  const pc = (p: Item['priority']) => (p === 'High' ? t.negative : p === 'Medium' ? 'F59E0B' : t.positive);
  const rows = [header(ctx, ['No.', 'Customer / activity', 'Next action', 'Due', 'Priority'], aligns)];
  list.forEach((it, i) => rows.push(row(ctx, [String(i + 1), { text: it.what, options: { bold: true } } as Cell, it.next, it.due, { text: it.priority, options: { bold: true, color: pc(it.priority) } } as Cell], aligns, i)));
  table(ctx, s, rows, MX + 0.75, 1.6, W - MX - (MX + 0.75), [0.55, 3.4, 5.6, 0.95, 0.98], 10);
  s.addText('Overall focus: keep customers engaged, formalise agreements and convert opportunities into confirmed orders.',
    { x: MX + 0.75, y: 6.55, w: W - 2 * MX - 0.75, h: 0.35, fontFace: FONT, fontSize: 11, italic: true, color: t.primary, margin: 0 });
}

function closing(ctx: Ctx) {
  const { pptx, t, data, notes } = ctx;
  const s = pptx.addSlide();
  s.background = { color: t.cover };
  s.addShape(pptx.ShapeType.ellipse, { x: -2, y: 3.2, w: 6.5, h: 6.5, fill: { color: t.primary, transparency: 80 }, line: { color: t.primary, width: 0 } });
  s.addShape(pptx.ShapeType.ellipse, { x: 10.2, y: -1.8, w: 5, h: 5, fill: { color: t.accent, transparency: 82 }, line: { color: t.accent, width: 0 } });
  s.addText('Thank you', { x: 0, y: 2.4, w: W, h: 1.2, fontFace: FONT_HEAD, fontSize: 54, bold: true, color: t.coverInk, align: 'center' });
  s.addText('Questions & discussion', { x: 0, y: 3.6, w: W, h: 0.6, fontFace: FONT, fontSize: 20, color: t.primary, align: 'center' });
  s.addText(`${notes.presenter ? `${notes.presenter}  ·  ` : ''}${data.organization.name}`, { x: 0, y: 4.5, w: W, h: 0.5, fontFace: FONT, fontSize: 13, color: 'CBD5E1', align: 'center' });
}

const BUILDERS: Record<SectionKey, (ctx: Ctx, order: SectionKey[]) => void> = {
  agenda, summary, funnel, trend, mix, categories: categoryDeepDives, customers, team, pipeline, lost, receivables,
  activity, forecast, risks, actions, closing,
};
