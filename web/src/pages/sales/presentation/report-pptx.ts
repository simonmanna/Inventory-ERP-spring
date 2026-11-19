import type PptxGenJS from 'pptxgenjs';
import { evenCols, fitRowH, G, resolveLook, type Box, type Look, type Palette } from './look';
import type { ReportSlide, SlideTable } from './report';

/** Exports the (edited) weekly report slides to a .pptx that mirrors presentation-real.pptx. */

type Slide = PptxGenJS.Slide;
type Cell = PptxGenJS.TableCell;
type TextOpts = PptxGenJS.TextPropsOptions;

const IN = (px: number) => px / 96;
const pos = (b: Box) => ({ x: IN(b.x), y: IN(b.y), w: IN(b.w), h: IN(b.h) });
/** Fresh object per use — pptxgenjs converts shadow units in place, so a shared object compounds. */
const shadow = (): PptxGenJS.ShadowProps => ({ type: 'outer', blur: 4, offset: 2, angle: 90, color: '000000', opacity: 0.35 });

/** Loads a public path or data URL into a data URL pptxgenjs can embed. */
async function dataUrl(src: string): Promise<string | null> {
  if (!src) return null;
  if (src.startsWith('data:')) return src;
  try {
    const blob = await (await fetch(src)).blob();
    return await new Promise((res) => { const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = () => res(null); r.readAsDataURL(blob); });
  } catch { return null; }
}

export async function exportReport(slides: ReportSlide[], fileName: string, orgName: string, look: Look = resolveLook()): Promise<string> {
  const { default: Pptx } = await import('pptxgenjs');
  const pptx = new Pptx();
  pptx.layout = 'LAYOUT_WIDE';
  pptx.company = orgName;
  pptx.title = slides.find((s) => s.kind === 'cover')?.title ?? 'Sales & Marketing overview';
  pptx.theme = { headFontFace: look.font, bodyFontFace: look.font };
  const ctx: Ctx = { pptx, c: look.c, style: look.style, font: look.font, arial: look.font === 'Calibri' ? 'Arial' : look.font };
  for (const s of slides.filter((x) => x.on)) await render(ctx, pptx.addSlide(), s);
  await pptx.writeFile({ fileName });
  return fileName;
}

interface Ctx { pptx: PptxGenJS; c: Palette; style: Look['style']; font: string; arial: string }
/** Shadow for classic / bold; minimal is flat. */
const sh = (ctx: Ctx) => (ctx.style === 'minimal' ? undefined : shadow());

function rect(ctx: Ctx, s: Slide, b: Box, color: string, opts: Partial<PptxGenJS.ShapeProps> = {}) {
  s.addShape(ctx.pptx.ShapeType.rect, { ...pos(b), fill: { color }, line: { color, width: 0 }, ...opts });
}
function round(ctx: Ctx, s: Slide, b: Box, fill: string, line: string, radius: number) {
  const r = ctx.style === 'minimal' ? 0.08 : ctx.style === 'bold' ? 0.04 : radius;
  s.addShape(ctx.pptx.ShapeType.roundRect, { ...pos(b), fill: { color: fill }, line: { color: line, width: 0.75 }, rectRadius: r, shadow: sh(ctx) });
  if (ctx.style === 'bold') rect(ctx, s, { x: b.x, y: b.y, w: 8, h: b.h }, ctx.c.cyan);
}
function text(ctx: Ctx, s: Slide, value: string | PptxGenJS.TextProps[], b: Box, o: TextOpts) {
  s.addText(value, { ...pos(b), fontFace: ctx.font, margin: 0, valign: 'middle', ...o });
}
/** Header block (see SlideView `Header`); returns the colour for text placed on it. */
function header(ctx: Ctx, s: Slide, b: Box, color: string): string {
  if (ctx.style === 'minimal') { rect(ctx, s, { x: 55, y: b.y + b.h - 10, w: 180, h: 5 }, ctx.c.blue); return ctx.c.navy; }
  rect(ctx, s, b, color, { shadow: sh(ctx) });
  if (ctx.style === 'bold') rect(ctx, s, { x: 0, y: b.y + b.h, w: b.w, h: 8 }, ctx.c.cyan);
  return ctx.c.white;
}
function band(ctx: Ctx, s: Slide, slide: ReportSlide, h: number = G.band.h) {
  const ink = header(ctx, s, { ...G.band, h }, ctx.c.navyBand);
  text(ctx, s, slide.title, G.bandTitle, { fontSize: 24, bold: true, color: ink });
  if (slide.subtitle) text(ctx, s, slide.subtitle, G.bandSub, { fontSize: 13, color: ctx.c.muted });
}
function topBar(ctx: Ctx, s: Slide) {
  if (ctx.style === 'minimal') { rect(ctx, s, { ...G.topBar, h: 6 }, ctx.c.blue); return; }
  rect(ctx, s, G.topBar, ctx.c.navy, { shadow: sh(ctx) });
  if (ctx.style === 'bold') rect(ctx, s, { x: 0, y: G.topBar.h, w: 1280, h: 6 }, ctx.c.cyan);
}
function pill(ctx: Ctx, s: Slide, b: Box, value: string, size: number, color?: string, align: 'left' | 'center' = 'left') {
  const C = ctx.c;
  if (ctx.style === 'minimal') rect(ctx, s, { x: b.x, y: b.y, w: 6, h: b.h }, C.blue);
  else if (ctx.style === 'bold') rect(ctx, s, b, C.navy, { shadow: sh(ctx) });
  else s.addShape(ctx.pptx.ShapeType.roundRect, { ...pos(b), rectRadius: 0.1, fill: { color: C.pill }, line: { color: C.pill, width: 0 }, shadow: shadow() });
  const ink = ctx.style === 'bold' ? C.white : (color ?? C.navy);
  text(ctx, s, value, { ...b, x: b.x + 14, w: b.w - 28 }, { fontSize: size, bold: true, color: ink, align });
}

interface TableLook {
  head: string; headSize: number; headH: number; size: number; firstSize?: number; rowH: number; zebra?: string;
  bodyBold?: boolean; align?: Array<'left' | 'center'>; totalBg?: string; totalSize?: number; fontFace?: string;
}

function table(ctx: Ctx, s: Slide, t: SlideTable, x: number, y: number, cols: readonly number[], look: TableLook) {
  const al = (i: number) => look.align?.[i] ?? 'left';
  const C = ctx.c;
  const face = look.fontFace ?? ctx.font;
  const border = (c: string): [PptxGenJS.BorderProps, PptxGenJS.BorderProps, PptxGenJS.BorderProps, PptxGenJS.BorderProps] => [{ type: 'none' }, { type: 'solid', color: c, pt: 1 }, { type: 'none' }, { type: 'solid', color: c, pt: 1 }];
  const rows: Cell[][] = [
    t.header.map((h, i) => ({ text: h, options: { bold: true, color: C.white, fill: { color: look.head }, fontSize: look.headSize, align: al(i), fontFace: face, border: border(C.white) } })),
    ...t.rows.map((r, ri) => r.map((v, i): Cell => ({ text: v, options: {
      fill: { color: ri % 2 ? (look.zebra ?? C.zebra) : C.white }, color: C.ink, align: al(i), fontFace: face,
      bold: i === 0 || look.bodyBold, fontSize: i === 0 && look.firstSize ? look.firstSize : look.size, border: border(ri % 2 ? C.white : C.zebra),
    } }))),
  ];
  if (t.total) rows.push(t.total.map((v, i) => ({ text: v, options: { bold: true, color: C.white, fill: { color: look.totalBg ?? C.total }, fontSize: look.totalSize ?? 12, align: al(i), fontFace: face, border: border(C.white) } })));
  s.addTable(rows, {
    x: IN(x), y: IN(y), colW: cols.map(IN), rowH: [IN(look.headH), ...t.rows.map(() => IN(look.rowH)), ...(t.total ? [IN(look.rowH)] : [])],
    valign: 'middle', margin: [3, 8, 3, 8], autoPage: false,
  });
}

async function image(s: Slide, src: string | undefined, b: Box, sizing: 'cover' | 'contain' = 'cover') {
  const data = await dataUrl(src ?? '');
  if (!data) return;
  const dims = await new Promise<{ w: number; h: number }>((res) => {
    const i = new Image();
    i.onload = () => res({ w: i.naturalWidth || b.w, h: i.naturalHeight || b.h });
    i.onerror = () => res({ w: b.w, h: b.h });
    i.src = data;
  });
  // pptxgenjs crops/fits relative to the image's own size, so pass it as w/h and the box via sizing.
  const ratio = dims.w / dims.h;
  s.addImage({ data, x: IN(b.x), y: IN(b.y), w: IN(b.h) * ratio, h: IN(b.h), sizing: { type: sizing, w: IN(b.w), h: IN(b.h) } });
}

async function render(ctx: Ctx, s: Slide, sl: ReportSlide) {
  const { pptx, c: C } = ctx;
  switch (sl.kind) {
    case 'cover': {
      const g = G.cover;
      const [logo, photo] = sl.images ?? [];
      await image(s, photo, g.photo);
      rect(ctx, s, g.logo, C.logoBg);
      if (logo) await image(s, logo, g.logo, 'contain');
      else text(ctx, s, [
        { text: sl.meta?.logoText ?? '', options: { fontSize: 34, bold: true, color: C.gold, charSpacing: 6, breakLine: !!sl.meta?.logoSub } },
        ...(sl.meta?.logoSub ? [{ text: sl.meta.logoSub, options: { fontSize: 22, bold: true, color: C.gold, charSpacing: 14 } }] : []),
      ], g.logo, { align: 'center', fontFace: 'Georgia' });
      text(ctx, s, sl.title, g.title, { fontSize: 22, bold: true, color: C.black, align: 'center', fontFace: ctx.arial });
      rect(ctx, s, { x: 0, y: 645, w: 1280, h: 75 }, C.white);
      return;
    }
    case 'summary': {
      const g = G.summary;
      const ink = header(ctx, s, g.band, C.navySummary);
      text(ctx, s, sl.title, g.title, { fontSize: 19, bold: true, color: ink, fontFace: ctx.arial });
      if (ctx.style === 'classic') rect(ctx, s, g.strip, C.cyan, { line: { color: C.orange, width: 1.5 } });
      const kv: Array<[string, string]> = [['Submitted by:', sl.meta?.submittedBy ?? ''], ['Role:', sl.meta?.role ?? ''], ['Report date:', sl.meta?.reportDate ?? '']];
      // fresh border objects per cell: pptxgenjs converts units in place
      const lines = (): [PptxGenJS.BorderProps, PptxGenJS.BorderProps, PptxGenJS.BorderProps, PptxGenJS.BorderProps] =>
        [0, 1, 2, 3].map(() => ({ type: 'solid', color: C.gridLine, pt: 0.75 })) as never;
      s.addTable(kv.map(([k, v]) => [
        { text: k, options: { bold: true, fontSize: 17, color: C.keyLabel, fill: { color: C.grey }, border: lines() } },
        { text: v, options: { fontSize: 13.5, color: '232323', fill: { color: C.white }, border: lines() } },
      ]), { x: IN(g.kv.x), y: IN(g.kv.y), colW: g.kvCols.map(IN), rowH: IN(g.kvRow), fontFace: ctx.arial, valign: 'middle', margin: [0, 8, 0, 8] });
      text(ctx, s, sl.heading, g.heading, { fontSize: 18, bold: true, color: C.white, fill: { color: C.navySummary }, fontFace: ctx.arial, margin: [0, 18, 0, 18] });
      if (sl.table) table(ctx, s, sl.table, g.table.x, g.table.y, g.cols, { head: C.tan, headSize: 10, headH: 25, size: 10, rowH: 32, zebra: C.grey, align: ['left', 'center', 'center', 'left'], fontFace: ctx.arial });
      return;
    }
    case 'catTable': {
      const g = G.catTable;
      s.background = { color: 'F5F7FA' };
      topBar(ctx, s);
      text(ctx, s, sl.title, g.title, { fontSize: 24, bold: true, color: C.navyTable });
      text(ctx, s, sl.subtitle, g.sub, { fontSize: 10.5, color: C.muted });
      const rows = sl.table?.rows.length ?? 0;
      const rowH = Math.min(46, Math.floor(380 / Math.max(1, rows)));
      if (sl.table) table(ctx, s, sl.table, g.table.x, g.table.y, g.cols, { head: C.navyTable, headSize: 10, headH: 44, size: 10.5, rowH, align: ['left', 'center', 'center', 'center'] });
      text(ctx, s, sl.note, { x: 88, y: g.table.y + 44 + (rows + 1) * rowH + 10, w: 900, h: 22 }, { fontSize: 10.5, color: C.black });
      return;
    }
    case 'catChart': {
      const g = G.catChart;
      const withText = !!sl.text.trim();
      const ink = header(ctx, s, g.band, C.navy);
      if (ctx.style === 'classic') rect(ctx, s, g.strip, C.blue, { shadow: sh(ctx) });
      text(ctx, s, sl.title, g.title, { fontSize: 18, bold: true, color: ink });
      text(ctx, s, sl.subtitle, g.sub, { fontSize: 9, color: ctx.style === 'minimal' ? C.muted : C.white });
      barChart(ctx, s, sl, withText ? g.chartWithText : g.chartWide);
      if (withText) {
        rect(ctx, s, g.divider, C.bar, { shadow: sh(ctx) });
        round(ctx, s, g.card, C.panel, C.panelLine, 0.4);
        s.addText([
          { text: sl.heading, options: { fontSize: 12.5, bold: true, underline: { style: 'sng' }, color: C.link, breakLine: true } },
          { text: sl.text, options: { fontSize: 12.5, bold: true, italic: true, color: C.black, fontFace: ctx.arial } },
        ], { ...pos({ ...g.card, x: g.card.x + 25, w: g.card.w - 50 }), fontFace: ctx.font, align: 'center', valign: 'middle', margin: 0 });
      }
      rect(ctx, s, g.bottom, C.bar, { shadow: sh(ctx) });
      return;
    }
    case 'catCompare': {
      const g = G.catCompare;
      topBar(ctx, s);
      pill(ctx, s, g.titlePill, sl.title, 21);
      pill(ctx, s, g.headPill, sl.heading, 21, C.navy, 'center');
      const ch = sl.chart!;
      s.addChart(pptx.ChartType.bar3d, [{ name: ch.series, labels: ch.labels, values: ch.values }], {
        ...pos(g.chart), barDir: 'col', chartColors: [...C.bars3d], barGapWidthPct: 150, showLegend: true, legendPos: 'b', legendFontSize: 10, legendColor: '1F3864',
        catAxisLabelColor: '1F3864', catAxisLabelFontSize: 10, valAxisLabelColor: '1F3864', valAxisLabelFontSize: 10, valAxisLabelFormatCode: '#,##0.00',
        valGridLine: { color: 'DBE5F1', size: 0.75 }, v3DRotX: 15, v3DRotY: 20, v3DRAngAx: true, serAxisHidden: true, catAxisLineShow: false, valAxisLineShow: false,
      } as PptxGenJS.IChartOpts);
      rect(ctx, s, g.divider, C.bar, { shadow: sh(ctx) });
      round(ctx, s, g.card, C.panel, C.panelLine, 0.4);
      text(ctx, s, sl.text, { ...g.card, x: g.card.x + 30, w: g.card.w - 60 }, { fontSize: 12.5, bold: true, italic: true, align: 'center', color: C.black });
      rect(ctx, s, g.bottom, C.bar, { shadow: sh(ctx) });
      text(ctx, s, sl.note, g.note, { fontSize: 9, color: C.muted });
      return;
    }
    case 'lost': {
      const g = G.lost;
      const ink = header(ctx, s, { ...G.band, h: 110 }, C.navyBand);
      text(ctx, s, sl.title, { x: 50, y: 25, w: 1100, h: 62 }, { fontSize: 24, bold: true, color: ink });
      text(ctx, s, sl.subtitle, g.sub, { fontSize: 13.5, color: C.muted });
      if (sl.table) table(ctx, s, sl.table, g.table.x, g.table.y, g.cols, { head: C.brown, headSize: 14, headH: 75, size: 13.5, firstSize: 13.5, rowH: 76 });
      return;
    }
    case 'visits': {
      const g = G.visits;
      topBar(ctx, s);
      pill(ctx, s, g.titlePill, sl.title, 20);
      pill(ctx, s, g.headPill, sl.heading, 13.5, C.black);
      (sl.list ?? []).slice(0, 5).forEach((v, i) => {
        const ib = { ...g.item, y: g.item.y + i * g.itemStep };
        s.addShape(pptx.ShapeType.roundRect, { ...pos(ib), rectRadius: 0.08, fill: { color: C.panel }, line: { color: C.panelLine, width: 0.75 }, shadow: shadow() });
        text(ctx, s, v, { ...ib, x: ib.x + 28, w: ib.w - 36 }, { fontSize: 12, bold: true, color: C.black });
      });
      for (const [i, b] of g.images.entries()) await image(s, sl.images?.[i], b);
      return;
    }
    case 'market':
    case 'progress':
    case 'plan': {
      band(ctx, s, sl);
      const g = sl.kind === 'market' ? G.market : sl.kind === 'progress' ? G.progress : G.plan;
      const look: TableLook = sl.kind === 'plan'
        ? { head: C.brown, headSize: 11, headH: 69, size: 10, firstSize: 13, rowH: 72, bodyBold: true }
        : { head: C.brown, headSize: 14, headH: sl.kind === 'market' ? 88 : 84, size: 10, firstSize: 10.5, rowH: sl.kind === 'market' ? 90 : 70, bodyBold: true };
      if (sl.table) table(ctx, s, sl.table, g.table.x, g.table.y, g.cols, look);
      if (sl.kind === 'progress') {
        round(ctx, s, G.progress.key, C.greenBg, C.greenLine, 0.18);
        text(ctx, s, `${sl.heading}   |   ${sl.text}`, { ...G.progress.key, x: G.progress.key.x + 38, w: G.progress.key.w - 60, h: 80 }, { fontSize: 14, bold: true, color: C.green });
      }
      if (sl.note) {
        const nb = sl.kind === 'progress' ? G.progress.note : sl.kind === 'plan' ? G.plan.note : { x: 69, y: 655, w: 1100, h: 26 };
        text(ctx, s, sl.note, nb, { fontSize: sl.kind === 'plan' ? 13 : 10, italic: true, color: C.muted });
      }
      return;
    }
    case 'digital':
    case 'objectives': {
      band(ctx, s, sl);
      const g = sl.kind === 'digital' ? G.digital : G.objectives;
      (sl.cards ?? []).slice(0, 4).forEach((c, i) => {
        round(ctx, s, g.cards[i], C.white, C.cardLine, 0.28);
        s.addText([
          { text: c.title, options: { fontSize: 14, bold: true, color: C.navyText, breakLine: true } },
          { text: c.body, options: { fontSize: 11.5, color: C.ink } },
        ], { ...pos({ x: g.cards[i].x + 30, y: g.cards[i].y + 14, w: g.cards[i].w - 50, h: g.cards[i].h - 28 }), fontFace: ctx.font, valign: 'top', margin: 0, paraSpaceAfter: 6 });
      });
      if (sl.kind === 'digital') await image(s, sl.images?.[0], G.digital.image);
      text(ctx, s, sl.note, g.note, sl.kind === 'digital' ? { fontSize: 13, italic: true, color: C.muted } : { fontSize: 13, bold: true, color: C.green });
      return;
    }
    case 'custom':
      await custom(ctx, s, sl);
      return;
    case 'results': {
      const g = G.results;
      band(ctx, s, sl);
      round(ctx, s, g.card, C.greenBg, C.greenLine, 0.5);
      text(ctx, s, sl.heading, g.heading, { fontSize: 16, bold: true, color: C.green });
      s.addText(sl.text.split('\n').filter(Boolean).map((p) => ({ text: p, options: { breakLine: true } })),
        { ...pos(g.text), fontFace: ctx.font, fontSize: 13.5, color: C.ink, valign: 'top', margin: 0, paraSpaceAfter: 9 });
      text(ctx, s, 'NEXT ACTIONS', g.actHead, { fontSize: 16, bold: true, color: C.navyText });
      (sl.list ?? []).forEach((v, i) => text(ctx, s, `• ${v}`, { ...g.act, y: g.act.y + i * g.act.h }, { fontSize: 13, color: C.ink }));
      text(ctx, s, sl.note, g.note, { fontSize: 10, italic: true, color: C.muted });
      return;
    }
  }
}

function barChart(ctx: Ctx, s: Slide, sl: ReportSlide, b: Box) {
  const C = ctx.c;
  const ch = sl.chart!;
  s.addChart(ctx.pptx.ChartType.bar, [{ name: ch.series, labels: ch.labels, values: ch.values }], {
    ...pos(b), barDir: 'col', chartColors: [C.chartBar], chartColorsOpacity: 85, barGapWidthPct: 60,
    chartArea: { fill: { color: 'E4E4E4' }, border: { color: 'BFBFBF', pt: 0.75 } }, plotArea: { fill: { color: 'EDEDED' } },
    showTitle: true, title: ch.title, titleFontSize: 16, titleColor: '404040', titleFontFace: ctx.font,
    showLegend: true, legendPos: 'b', legendFontSize: 8, legendColor: '404040',
    showCatAxisTitle: true, catAxisTitle: ch.xTitle, catAxisTitleFontSize: 8, catAxisTitleColor: '404040',
    showValAxisTitle: true, valAxisTitle: ch.yTitle, valAxisTitleFontSize: 8, valAxisTitleColor: '404040',
    catAxisLabelFontSize: 8, catAxisLabelColor: '404040', valAxisHidden: true, valGridLine: { color: 'A6A6A6', size: 0.75 },
    catAxisLineShow: true, catAxisLineColor: '404040',
    showValue: ch.values.length <= 4, dataLabelColor: C.white, dataLabelFontSize: 9, dataLabelFontBold: true, dataLabelPosition: 'inEnd', dataLabelFormatCode: '#,##0.00',
  } as PptxGenJS.IChartOpts);
}

/** Paragraphs from `text`, then bullets from `list` (same order as the HTML preview). */
function prose(ctx: Ctx, s: Slide, sl: ReportSlide, b: Box, size = 15) {
  const C = ctx.c;
  const parts: PptxGenJS.TextProps[] = [];
  if (sl.heading) parts.push({ text: sl.heading, options: { fontSize: size + 2, bold: true, color: C.navyText, breakLine: true } });
  for (const p of sl.text.split('\n').filter((x) => x.trim())) parts.push({ text: p, options: { fontSize: size, color: C.ink, breakLine: true } });
  for (const v of (sl.list ?? []).filter((x) => x.trim())) parts.push({ text: v, options: { fontSize: size, color: C.ink, bullet: { code: '25A0' }, breakLine: true } });
  if (parts.length) s.addText(parts, { ...pos(b), fontFace: ctx.font, valign: 'top', margin: 0, paraSpaceAfter: 8 });
}

async function custom(ctx: Ctx, s: Slide, sl: ReportSlide) {
  const C = ctx.c;
  const g = G.custom;
  const layout = sl.layout ?? 'bullets';
  if (layout === 'image') {
    await image(s, sl.images?.[0], { x: 0, y: 0, w: 1280, h: 720 });
    rect(ctx, s, g.caption, '000000', { fill: { color: '000000', transparency: 45 }, line: { type: 'none' } });
    s.addText([
      { text: sl.title, options: { fontSize: 26, bold: true, color: C.white, breakLine: !!(sl.subtitle || sl.text) } },
      ...(sl.subtitle || sl.text ? [{ text: sl.subtitle || sl.text, options: { fontSize: 13.5, color: C.white } }] : []),
    ], { ...pos({ ...g.caption, x: 60, w: 1160 }), fontFace: ctx.font, valign: 'middle', margin: 0 });
    return;
  }
  band(ctx, s, sl);
  if (layout === 'bullets') prose(ctx, s, sl, g.full, 16.5);
  if (layout === 'textImage') { prose(ctx, s, sl, g.left); await image(s, sl.images?.[0], g.right); }
  if (layout === 'imageText') { await image(s, sl.images?.[0], g.left); prose(ctx, s, sl, { ...g.right, x: g.right.x + 10 }); }
  if (layout === 'table' && sl.table) {
    const t = sl.table;
    table(ctx, s, t, g.full.x, g.full.y, evenCols(t.header.length, g.full.w),
      { head: C.navyTable, headSize: 11, headH: 44, size: 10.5, rowH: fitRowH(t.rows.length + (t.total ? 1 : 0), 440) });
  }
  if (layout === 'chart' && sl.chart) barChart(ctx, s, sl, g.full);
  if (layout === 'chartText' && sl.chart) {
    barChart(ctx, s, sl, g.chart);
    round(ctx, s, g.side, C.panel, C.panelLine, 0.24);
    prose(ctx, s, sl, { x: g.side.x + 30, y: g.side.y + 26, w: g.side.w - 60, h: g.side.h - 52 }, 13.5);
  }
  if (layout === 'cards') {
    (sl.cards ?? []).slice(0, 4).forEach((c, i) => {
      const b = g.cards[i];
      round(ctx, s, b, C.white, C.cardLine, 0.28);
      s.addText([
        { text: c.title, options: { fontSize: 16, bold: true, color: C.navyText, breakLine: true } },
        { text: c.body, options: { fontSize: 12, color: C.ink } },
      ], { ...pos({ x: b.x + 30, y: b.y + 18, w: b.w - 50, h: b.h - 36 }), fontFace: ctx.font, valign: 'top', margin: 0, paraSpaceAfter: 6 });
    });
  }
  if (sl.note) text(ctx, s, sl.note, g.note, { fontSize: 10.5, italic: true, color: C.muted });
}
