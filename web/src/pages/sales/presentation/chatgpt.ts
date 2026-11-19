import { CUSTOM_LAYOUTS, newCustomSlide, type CustomLayout, type ExtraSlide, type ReportSlide, type SlideEdit } from './report';

/**
 * Copy/paste bridge to ChatGPT (no API key, nothing leaves the browser except what the presenter
 * pastes into ChatGPT themselves). We build a prompt that carries the slide content as JSON and asks
 * for a JSON reply; the reply is parsed, sanitised to the editable fields and applied as slide edits.
 */

export const CHATGPT_URL = 'https://chatgpt.com/';

/** Editable content of a slide, without images (data URLs are huge and ChatGPT cannot edit them). */
function content(s: ReportSlide): Record<string, unknown> {
  const o: Record<string, unknown> = { id: s.id, slide: s.label, title: s.title };
  if (s.layout) o.layout = s.layout;
  for (const k of ['subtitle', 'heading', 'text', 'note'] as const) if (s[k]) o[k] = s[k];
  if (s.list) o.list = s.list;
  if (s.table) o.table = s.table;
  if (s.chart) o.chart = s.chart;
  if (s.cards) o.cards = s.cards;
  return o;
}

const FIELD_RULES = `Fields you may return (all optional, return only the ones you change):
- "title", "subtitle", "heading", "text", "note": strings ("text" may contain line breaks as \\n)
- "list": array of strings (bullet points / next actions)
- "table": {"header": string[], "rows": string[][], "total"?: string[]} — every row has as many cells as the header
- "chart": {"title": string, "xTitle": string, "yTitle": string, "series": string, "labels": string[], "values": number[]} — same number of labels and values
- "cards": [{"title": string, "body": string}] (up to 4)`;

const RULES = `Rules:
- Reply with ONE JSON code block and nothing else.
- Keep figures and currency exactly as given unless I ask you to change them; never invent sales numbers.
- Keep text short enough to fit a 16:9 slide (titles under 70 characters, bullets under 120).`;

/** Prompt for improving one slide. */
export function slidePrompt(s: ReportSlide, instruction: string, org: string): string {
  return [
    `You are helping me edit one slide of a sales & marketing presentation for ${org}.`,
    `My request: ${instruction.trim() || 'Improve the wording so it is clear, concise and persuasive for management.'}`,
    '',
    'Current slide content (JSON):',
    '```json',
    JSON.stringify(content(s), null, 2),
    '```',
    '',
    FIELD_RULES,
    '',
    RULES,
    '- Do not include "id", "slide" or "layout" in your reply.',
  ].join('\n');
}

/** Prompt for reviewing the whole (included) deck and optionally proposing new slides. */
export function deckPrompt(slides: ReportSlide[], instruction: string, org: string): string {
  const layouts = CUSTOM_LAYOUTS.map((l) => `"${l.key}" (${l.label})`).join(', ');
  return [
    `You are helping me edit a sales & marketing presentation for ${org}. It has ${slides.length} slides.`,
    `My request: ${instruction.trim() || 'Review the deck, sharpen the wording and suggest management actions.'}`,
    '',
    'Slides (JSON):',
    '```json',
    JSON.stringify(slides.map(content), null, 2),
    '```',
    '',
    'Reply with this JSON shape:',
    '{"slides": [{"id": "<id of an existing slide>", ...changed fields}], "newSlides": [{"after": "<id of the slide it follows>", "layout": "<layout>", "title": "...", ...fields}]}',
    `Layouts for new slides: ${layouts}.`,
    '',
    FIELD_RULES,
    '',
    RULES,
    '- Only list slides you actually change. Leave "newSlides" empty unless my request needs new slides.',
  ].join('\n');
}

// ---------------------------------------------------------------- reply parsing

/** Pulls the JSON object out of a ChatGPT reply (code fence, surrounding prose, smart quotes). */
export function extractJson(reply: string): unknown {
  const fence = /```(?:json)?\s*([\s\S]*?)```/i.exec(reply);
  let body = (fence ? fence[1] : reply).trim();
  const start = body.search(/[[{]/);
  if (start < 0) throw new Error('No JSON found in the reply.');
  const open = body[start];
  const end = body.lastIndexOf(open === '{' ? '}' : ']');
  if (end <= start) throw new Error('The JSON in the reply is incomplete.');
  body = body.slice(start, end + 1).replace(/[“”]/g, '"').replace(/[‘’]/g, "'").replace(/,\s*([}\]])/g, '$1');
  try {
    return JSON.parse(body);
  } catch (e) {
    throw new Error(`The reply is not valid JSON: ${e instanceof Error ? e.message : String(e)}`);
  }
}

const str = (v: unknown, max = 4000): string | undefined => (typeof v === 'string' ? v.slice(0, max) : typeof v === 'number' ? String(v) : undefined);
const strs = (v: unknown, max = 40): string[] | undefined => (Array.isArray(v) ? v.slice(0, max).map((x) => str(x, 1000) ?? '') : undefined);
const num = (v: unknown): number => {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  const n = Number(String(v ?? '').replace(/[^0-9.+-]/g, ''));
  return Number.isFinite(n) ? n : 0;
};
const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : null);

/** Keeps only editable, well-formed fields from a ChatGPT object; throws when nothing usable is left. */
export function sanitizeEdit(raw: unknown, current?: ReportSlide): SlideEdit {
  const o = obj(raw);
  if (!o) throw new Error('Expected a JSON object.');
  const e: SlideEdit = {};
  for (const k of ['title', 'subtitle', 'heading', 'text', 'note'] as const) {
    const v = str(o[k]);
    if (v !== undefined) e[k] = v;
  }
  const list = strs(o.list);
  if (list) e.list = list;
  const t = obj(o.table);
  if (t) {
    const header = strs(t.header, 12);
    const rows = Array.isArray(t.rows) ? t.rows.slice(0, 60).map((r) => strs(r, 12) ?? []) : undefined;
    if (header && header.length && rows) {
      const w = header.length;
      const fit = (r: string[]) => Array.from({ length: w }, (_, i) => r[i] ?? '');
      e.table = { header, rows: rows.map(fit) };
      const total = strs(t.total, 12);
      if (total && total.length) e.table.total = fit(total);
    }
  }
  const ch = obj(o.chart);
  if (ch) {
    const labels = strs(ch.labels, 30);
    const values = Array.isArray(ch.values) ? ch.values.slice(0, 30).map(num) : undefined;
    if (labels && values) {
      const n = Math.min(labels.length, values.length);
      const prev = current?.chart;
      e.chart = {
        title: str(ch.title, 200) ?? prev?.title ?? '', xTitle: str(ch.xTitle, 200) ?? prev?.xTitle ?? '',
        yTitle: str(ch.yTitle, 200) ?? prev?.yTitle ?? '', series: str(ch.series, 200) ?? prev?.series ?? 'Value',
        labels: labels.slice(0, n), values: values.slice(0, n),
      };
    }
  }
  if (Array.isArray(o.cards)) {
    e.cards = o.cards.slice(0, 4).map((c) => { const x = obj(c) ?? {}; return { title: str(x.title, 300) ?? '', body: str(x.body, 2000) ?? '' }; });
  }
  if (Object.keys(e).length === 0) throw new Error('The reply has no slide fields (title, text, table, chart, …) to apply.');
  return e;
}

export interface DeckReply { edits: Record<string, SlideEdit>; added: ExtraSlide[]; skipped: string[] }

/** Parses a whole-deck reply: edits for known slide ids and new custom slides. */
export function parseDeckReply(reply: string, slides: ReportSlide[]): DeckReply {
  const root = extractJson(reply);
  const o = obj(root) ?? (Array.isArray(root) ? { slides: root } : null);
  if (!o) throw new Error('Expected a JSON object with "slides".');
  const byId = new Map(slides.map((s) => [s.id, s]));
  const out: DeckReply = { edits: {}, added: [], skipped: [] };
  for (const item of Array.isArray(o.slides) ? o.slides : []) {
    const id = str(obj(item)?.id);
    const s = id ? byId.get(id) : undefined;
    if (!s || !id) { out.skipped.push(id ?? '(no id)'); continue; }
    try { out.edits[id] = sanitizeEdit(item, s); } catch { out.skipped.push(id); }
  }
  let anchor = '';
  for (const item of Array.isArray(o.newSlides) ? o.newSlides : []) {
    const x = obj(item);
    if (!x) continue;
    const layout = (CUSTOM_LAYOUTS.some((l) => l.key === x.layout) ? x.layout : 'bullets') as CustomLayout;
    const after = str(x.after);
    // chain consecutive new slides with the same anchor so they keep ChatGPT's order
    const target = after && byId.has(after) ? after : anchor;
    const base = newCustomSlide(layout, target);
    let patch: SlideEdit = {};
    try { patch = sanitizeEdit(x, base); } catch { /* keep the placeholder slide */ }
    const slide: ExtraSlide = { ...base, ...patch, kind: 'custom', layout, after: target, label: 'ChatGPT slide' };
    out.added.push(slide);
    anchor = slide.id;
  }
  if (!Object.keys(out.edits).length && !out.added.length) throw new Error('Nothing in the reply matched a slide in this deck.');
  return out;
}

// ---------------------------------------------------------------- pasted data

/**
 * Parses a table pasted from Excel / Google Sheets (tab separated), CSV or a Markdown table
 * (what ChatGPT usually produces). The first row is the header.
 */
export function parseTable(text: string): { header: string[]; rows: string[][] } {
  const lines = text.replace(/\r/g, '').split('\n').map((l) => l.trimEnd()).filter((l) => l.trim());
  if (!lines.length) throw new Error('Nothing to import.');
  let cells: string[][];
  if (lines.some((l) => l.trim().startsWith('|'))) {
    cells = lines
      .filter((l) => !/^\s*\|?\s*:?-{2,}/.test(l))
      .map((l) => l.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim().replace(/\*\*/g, '')));
  } else if (lines.some((l) => l.includes('\t'))) {
    cells = lines.map((l) => l.split('\t').map((c) => c.trim()));
  } else {
    const sep = lines[0].split(';').length > lines[0].split(',').length ? ';' : ',';
    cells = lines.map((l) => splitCsv(l, sep));
  }
  const w = Math.max(...cells.map((r) => r.length));
  const fit = (r: string[]) => Array.from({ length: w }, (_, i) => r[i] ?? '');
  const [header, ...rows] = cells.map(fit);
  return { header, rows };
}

function splitCsv(line: string, sep: string): string[] {
  const out: string[] = [];
  let cur = '';
  let q = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (q) {
      if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (ch === '"') q = false; else cur += ch;
    } else if (ch === '"') q = true;
    else if (ch === sep) { out.push(cur.trim()); cur = ''; } else cur += ch;
  }
  out.push(cur.trim());
  return out;
}

/** Chart bars from pasted rows: first column = label, last numeric column = value. Header row optional. */
export function parseChart(text: string): { labels: string[]; values: number[] } {
  const { header, rows } = parseTable(text);
  const all = [header, ...rows];
  const numeric = (v: string) => v.trim() !== '' && Number.isFinite(Number(v.replace(/[^0-9.+-]/g, ''))) && /\d/.test(v);
  const body = all.filter((r) => r.slice(1).some(numeric));
  if (!body.length) throw new Error('No numbers found. Paste two columns: label and value.');
  const labels = body.map((r) => r[0]);
  const values = body.map((r) => { const v = [...r.slice(1)].reverse().find(numeric) ?? '0'; return num(v); });
  return { labels, values };
}
