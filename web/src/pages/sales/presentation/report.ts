import type { PresentationCategory, PresentationData } from './types';

/**
 * Weekly sales & marketing report deck — a faithful replica of the owner's hand-made
 * `presentation/presentation-real.pptx`, pre-filled from the sales analytics endpoint.
 * Every slide is plain data so the page can preview it, edit it in a dialog and export it to PPTX.
 */

export type SlideKind =
  | 'cover' | 'summary' | 'catTable' | 'catChart' | 'catCompare' | 'lost' | 'visits'
  | 'market' | 'progress' | 'digital' | 'results' | 'objectives' | 'plan';

export interface SlideTable { header: string[]; rows: string[][]; total?: string[] }
export interface SlideChart { title: string; xTitle: string; yTitle: string; series: string; labels: string[]; values: number[] }
export interface SlideCard { title: string; body: string }

export interface ReportSlide {
  id: string;
  kind: SlideKind;
  /** Short caption under the thumbnail. */
  label: string;
  on: boolean;
  title: string;
  /** Sub-heading / strap line under the title. */
  subtitle: string;
  /** Second panel heading (e.g. "KEY OBSERVATION", "GRANITE SALES UPDATE"). */
  heading: string;
  /** Main free text (observation, update, response). */
  text: string;
  /** Footer / caption line. */
  note: string;
  table?: SlideTable;
  chart?: SlideChart;
  cards?: SlideCard[];
  list?: string[];
  /** Image data URLs or public paths; slot meaning depends on the kind. */
  images?: string[];
  /** Cover / header key-value fields. */
  meta?: Record<string, string>;
}

/** Fields a user may override; ids are stable across data reloads so edits survive a date change. */
export type SlideEdit = Partial<Omit<ReportSlide, 'id' | 'kind'>>;

export interface ReportInput {
  data: PresentationData;
  /** Period two lengths back (for the "last 3 weeks" comparison); null while loading. */
  older: PresentationData | null;
  /** Month-to-date numbers for the "Month" column; null while loading. */
  month: PresentationData | null;
  presenter: string;
}

// ---------------------------------------------------------------- formatting

const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
const nf2 = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const nfq = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
const nfShare = (v: number | null | undefined) => (v == null ? '0.0%' : `${v.toFixed(1)}%`);
export const money2 = (v: number) => nf2.format(v);
export const quantity = (v: number | null | undefined) => (v == null ? '0.0' : v === 0 ? '0.0' : nfq.format(v));

const parts = (iso: string) => { const [y, m, d] = iso.split('-').map(Number); return { y, m, d }; };
/** "2026-10-19" → "19 OCT". */
export const dayMon = (iso: string) => { const p = parts(iso); return `${String(p.d).padStart(2, '0')} ${MONTHS[p.m - 1]}`; };
/** Cover range: "19oct–23oct 2026". */
export function coverRange(from: string, to: string): string {
  const a = parts(from); const b = parts(to);
  const f = (p: { d: number; m: number }) => `${p.d}${MONTHS[p.m - 1].toLowerCase()}`;
  return a.y === b.y ? `${f(a)}–${f(b)} ${b.y}` : `${f(a)} ${a.y}–${f(b)} ${b.y}`;
}
/** Report date "02-NOV-2026". */
export const reportDate = (d = new Date()) => `${String(d.getDate()).padStart(2, '0')}-${MONTHS[d.getMonth()]}-${d.getFullYear()}`;
const weekLabel = (from: string, to: string) => `${dayMon(from)} - ${dayMon(to)}`;

const isoDate = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const shift = (iso: string, days: number) => { const p = parts(iso); return isoDate(new Date(p.y, p.m - 1, p.d + days)); };

/** The period of equal length immediately before `previousPeriod` (third bar of the comparison). */
export function olderPeriod(data: PresentationData): { from: string; to: string } {
  const days = data.period.days;
  return { from: shift(data.previousPeriod.from, -days), to: shift(data.previousPeriod.from, -1) };
}

/** First of the month of the period end up to the period end. */
export function monthToDate(data: PresentationData): { from: string; to: string } {
  const p = parts(data.period.to);
  return { from: `${p.y}-${String(p.m).padStart(2, '0')}-01`, to: data.period.to };
}

const first = (name: string) => name.split(/\s+/)[0] ?? name;
const title = (s: string) => s.replace(/\b\w/g, (c) => c.toUpperCase());
const UNIT: Record<string, string> = { 'm²': 'M²', m2: 'M²', t: 'TON', ton: 'TON', pc: 'PCS', pcs: 'PCS', rm: 'M', m: 'M', trip: 'TRIPS' };
const unitLabel = (u: string | null) => (u ? UNIT[u.toLowerCase()] ?? u.toUpperCase() : 'QTY');
/** Strip a "Granite slab - " style prefix so product rows read like the hand-made deck. */
const productName = (label: string) => (label.includes(' - ') ? label.split(' - ').slice(1).join(' - ') : label).toUpperCase();

/** Categories that get the 3-slide treatment (table, chart, comparison), largest first. */
export const deckCategories = (data: PresentationData) =>
  data.categories.filter((c) => c.key !== '__other' && c.products.length > 0).slice(0, 6);

// ---------------------------------------------------------------- builder

/** Builds the 19-slide (for three categories) weekly report with live numbers. */
export function buildReport({ data, older, month, presenter }: ReportInput): ReportSlide[] {
  const cur = data.organization.currencyCode;
  const org = data.organization.name;
  const short = title(first(org).toLowerCase());
  const k = data.kpis;
  const slides: ReportSlide[] = [];
  const base = (id: string, kind: SlideKind, label: string, on = true): ReportSlide =>
    ({ id, kind, label, on, title: '', subtitle: '', heading: '', text: '', note: '' });

  // 1 — cover
  slides.push({
    ...base('cover', 'cover', 'Cover'),
    title: `${short}  Sales & Marketing overview|  ${coverRange(data.period.from, data.period.to)}`,
    meta: { logoText: org.toUpperCase(), logoSub: 'GROUP' },
    images: ['', '/presentation/cover.jpg'],
  });

  // 2 — weekly activity report + commercial summary
  const visits = (d: PresentationData | null) => (d ? Object.values(d.activities.counts).reduce((a, b) => a + Number(b), 0) : null);
  const meetings = (d: PresentationData | null) => (d ? Number(d.activities.counts.meeting ?? 0) : null);
  const m = month;
  const reasons = [...new Set(data.lostBusiness.map((l) => l.reason).filter(Boolean) as string[])];
  const s = (v: number | null | undefined) => (v == null ? '' : String(v));
  slides.push({
    ...base('summary', 'summary', 'Weekly activity report'),
    title: `WEEKLY ACTIVITY REPORT — ${org.toUpperCase()}`,
    heading: '1.COMMERCIAL SUMMARY',
    meta: { submittedBy: presenter, role: 'Acting Sales & Marketing', reportDate: reportDate() },
    table: {
      header: ['Metric', 'This Week', 'Month', 'Comment'],
      rows: [
        ['Visitors', s(visits(data)), s(visits(m)), ''],
        ['Site visits completed', s(meetings(data)), s(meetings(m)), ''],
        ['Quotes issued', s(k.quotationsIssued.count), s(m?.kpis.quotationsIssued.count), ''],
        ['Orders confirmed', s(k.ordersConfirmed.count), s(m?.kpis.ordersConfirmed.count), ''],
        ['Orders lost', s(k.ordersLost.count), s(m?.kpis.ordersLost.count), reasons.join('\n')],
        [`Revenue confirmed (${cur})`, money2(k.revenue.current), m ? money2(m.kpis.revenue.current) : '', 'Invoiced sales, net of returns'],
        ['', '', '', ''], ['', '', '', ''], ['', '', '', ''],
      ],
    },
  });

  // 3.. — per category: performance table, revenue breakdown chart, 3-period comparison
  const cats = deckCategories(data);
  let n = 1;
  cats.forEach((c, i) => {
    const on = i < 3;
    const id = c.key ?? 'uncat';
    const L = c.label.toUpperCase();
    const total = c.products.reduce((a, p) => a + p.revenue, 0);
    const qty = c.products.reduce((a, p) => a + p.quantity, 0);
    slides.push({
      ...base(`cat-table-${id}`, 'catTable', `${c.label} — performance`, on),
      title: `${n++}.${L} SALES PERFORMANCE`,
      subtitle: 'Product volume, revenue and revenue contribution',
      note: 'Revenue % = Product revenue ÷ Total revenue × 100',
      table: {
        header: [`${L} / PRODUCT ITEM`, `${c.unit === 'pc' ? 'QUANTITY' : 'VOLUME'} (${unitLabel(c.unit)})`, `TOTAL REVENUE (${cur})`, '(%) share'],
        rows: c.products.map((p) => [productName(p.label), quantity(p.quantity), money2(p.revenue), nfShare(total ? (p.revenue / total) * 100 : 0)]),
        total: ['TOTAL', quantity(qty), money2(total), '100%'],
      },
    });
    slides.push({
      ...base(`cat-chart-${id}`, 'catChart', `${c.label} — revenue chart`, on),
      title: `${n++}.${L} PRODUCT REVENUE COMPARISON`,
      subtitle: c.products.map((p) => productName(p.label)).join('  •  '),
      heading: 'KEY OBSERVATION',
      text: observation(c, cur),
      chart: {
        title: `${c.label} Product Revenue Breakdown (${cur})`, xTitle: `${c.label} Item / Specification`, yTitle: `Revenue (${cur})`,
        series: `Total Revenue (${cur})`, labels: c.products.map((p) => productName(p.label)), values: c.products.map((p) => p.revenue),
      },
    });
    const olderCat = older?.categories.find((x) => x.key === c.key);
    const weeks = [
      { label: older ? weekLabel(older.period.from, older.period.to) : '', value: olderCat?.revenue ?? 0 },
      { label: weekLabel(data.previousPeriod.from, data.previousPeriod.to), value: c.previousRevenue },
      { label: weekLabel(data.period.from, data.period.to), value: c.revenue },
    ];
    slides.push({
      ...base(`cat-compare-${id}`, 'catCompare', `${c.label} — 3-week comparison`, on),
      title: `${n++}. ${L} SALES COMPARISION`,
      heading: `${L} SALES UPDATE`,
      text: comparisonText(c, weeks[0].value, cur),
      note: 'LAST 3 WEEKS GRAPH',
      chart: { title: '', xTitle: '', yTitle: '', series: 'SALES', labels: weeks.map((w) => w.label), values: weeks.map((w) => w.value) },
    });
  });

  // lost sales opportunities
  const lostRows = data.lostBusiness.map((l) => [
    l.customer ?? '—',
    `${l.kind === 'order' ? 'Sales order' : 'Quotation'} ${l.number} — ${cur} ${money2(l.value)}`,
    l.reason ?? (l.kind === 'order' ? 'Order cancelled' : 'Quotation not accepted'),
  ]);
  for (const r of data.pipeline.lostReasons) lostRows.push(['CRM opportunities', `${r.count} opportunit${r.count === 1 ? 'y' : 'ies'} worth ${cur} ${money2(r.value)}`, r.reason]);
  slides.push({
    ...base('lost', 'lost', 'Lost sales opportunities'),
    title: 'LOST SALES OPPORTUNITIES',
    subtitle: 'Summary of customers and products not secured',
    table: { header: ['Customer', 'Lost Product(s)', 'Reason / Remarks'], rows: lostRows.length ? lostRows.slice(0, 6) : [['No lost orders this period', '', '']] },
  });

  // marketing update — sites visited
  const visited = [...new Set([...data.activities.highlights, ...data.activities.upcoming].map((a) => a.subject).filter(Boolean) as string[])];
  slides.push({
    ...base('visits', 'visits', 'Marketing update — visits'),
    title: `${3 * Math.min(3, cats.length) + 2}. Marketing update`,
    heading: 'Visiting the following office & Sites',
    list: visited.slice(0, 5).map((v) => v.toUpperCase()),
    images: ['', '', '', ''],
  });

  // market update — open pipeline
  const nextFor = (customer: string | null) => data.activities.upcoming.find((a) => a.subject && a.subject === customer);
  slides.push({
    ...base('market', 'market', 'Market update'),
    title: 'MARKET UPDATE',
    subtitle: 'Sales pipeline – customer engagement, quotations and follow-up',
    note: 'Priority: Maintain customer contact and follow up on all submitted quotations.',
    table: {
      header: ['Customer', 'Market Update', 'Current Status / Next Action'],
      rows: data.pipeline.topOpen.slice(0, 4).map((o) => {
        const next = nextFor(o.customer);
        return [
          o.customer ?? o.name,
          `${o.name}. Estimated value ${cur} ${money2(o.amount)} (${o.probability}% probability).`,
          `${o.stage}. ${next ? `${next.title}${next.date ? ` (${dayMon(next.date)})` : ''}.` : 'Follow-up ongoing.'}${o.expectedClose ? ` Expected close ${dayMon(o.expectedClose)}.` : ''}`,
        ];
      }),
    },
  });

  // market update — sales progress
  const won = data.topCustomers.slice(0, 2);
  const progressRows = 3; // keeps the table clear of the key-achievement box
  slides.push({
    ...base('progress', 'progress', 'Sales progress'),
    title: 'MARKET UPDATE – SALES PROGRESS',
    subtitle: 'Confirmed agreement and upcoming customer engagement',
    heading: 'KEY ACHIEVEMENT',
    text: `${k.ordersConfirmed.count} order${k.ordersConfirmed.count === 1 ? '' : 's'} confirmed  •  ${cur} ${money2(k.ordersConfirmed.value)} order value  •  ${cur} ${money2(k.collections.current)} collected`,
    note: 'Next focus: Convert pending quotations into confirmed orders and progress the scheduled discussions.',
    table: {
      header: ['Customer', 'Opportunity / Progress', 'Status / Next Action'],
      rows: [
        ...won.map((c) => [c.name, `Invoiced ${cur} ${money2(c.revenue)} across ${c.invoices} invoice${c.invoices === 1 ? '' : 's'} (${nfShare(c.sharePercent)} of revenue).`, 'Delivery in progress. Keep the account active with follow-up orders.']),
        ...data.activities.upcoming.filter((a) => a.subject && !won.some((c) => c.name === a.subject)).slice(0, 2)
          .map((a) => [a.subject ?? '', a.title, `Scheduled${a.date ? ` for ${dayMon(a.date)}` : ''}.`]),
      ].slice(0, progressRows),
    },
  });

  // digital marketing (commentary — defaults mirror the reference deck, edit freely)
  slides.push({
    ...base('digital', 'digital', 'Digital marketing update'),
    title: 'DIGITAL MARKETING UPDATE',
    subtitle: 'Marketing activities, platform development and customer response',
    note: 'Focus: Build consistent online visibility and present our construction materials to a wider audience.',
    cards: [
      { title: '1. Previous activity', body: 'A new marketing flyer was prepared as part of our earlier marketing activities.' },
      { title: '2. Tik Tok activation', body: 'We have continued our digital marketing efforts by starting product promotion on Tik Tok.' },
      { title: '3. Content development', body: 'We have begun publishing selected product videos supported by AI-assisted posts. The initiative is still at an early stage.' },
      { title: '4. Expansion plan', body: 'We plan to strengthen our digital marketing and expand our activities on Facebook and Instagram.' },
    ],
    images: [''],
  });
  slides.push({
    ...base('results', 'results', 'Digital marketing — results'),
    title: 'DIGITAL MARKETING – RESULTS & NEXT STEPS',
    subtitle: 'Early response from TikTok and planned follow-up',
    heading: 'EARLY POSITIVE RESPONSE',
    text: 'Tik Tok has started generating positive engagement.\nWe are receiving inquiries, particularly from resellers interested in our products.',
    note: 'Management comment: Digital marketing is beginning to create visibility, engagement and potential reseller opportunities.',
    list: [
      'Continue publishing product videos and AI-supported posts.',
      'Strengthen consistency on Tik Tok.',
      'Expand product promotion on Facebook and Instagram.',
      'Follow up on reseller inquiries and convert interest into sales.',
    ],
  });
  slides.push({
    ...base('objectives', 'objectives', 'Marketing objectives'),
    title: 'MARKETING OBJECTIVES',
    subtitle: 'Customer service, quotation follow-up and sales conversion',
    note: 'Overall objective: Build customer confidence, strengthen relationships and increase sales conversion.',
    cards: [
      { title: '1. Strengthen customer relationships', body: 'Maintain regular contact with all customers we have visited and engaged, and understand their requirements.' },
      { title: '2. Deliver excellent service', body: 'Continue improving the quality, efficiency and speed of our customer service.' },
      { title: '3. Follow up on quotations', body: 'Closely follow up with customers who have received quotations and respond promptly to their questions.' },
      { title: '4. Convert opportunities into sales', body: 'Make every reasonable effort to secure orders and turn customer interest into confirmed business.' },
    ],
  });
  slides.push({
    ...base('plan', 'plan', 'Marketing action plan'),
    title: 'MARKETING ACTION PLAN',
    subtitle: 'Key priorities to achieve our marketing objectives',
    note: 'Management focus: Deliver reliable service and turn submitted quotations into confirmed orders.',
    table: {
      header: ['Priority', 'Planned Action', 'Expected Outcome'],
      rows: [
        ['Customer engagement', 'Maintain regular communication with visited and existing customers.', 'Stronger customer relationships'],
        ['Service quality', 'Provide timely, efficient and professional service.', 'Higher customer satisfaction'],
        ['Quotation follow-up', 'Contact customers who received quotations and address their feedback.', 'Faster customer decisions'],
        ['Sales conversion', 'Negotiate, resolve concerns and follow up until a decision is reached.', 'More confirmed orders'],
      ],
    },
  });
  return slides;
}

function observation(c: PresentationCategory, cur: string): string {
  const [top, ...rest] = c.products;
  if (!top || c.revenue <= 0) return `No ${c.label.toLowerCase()} sales were invoiced in this period.`;
  const share = c.revenue ? ((top.revenue / c.revenue) * 100).toFixed(1) : '0';
  const low = rest.filter((p) => c.revenue && p.revenue / c.revenue < 0.05).map((p) => productName(p.label).toLowerCase());
  return `${title(productName(top.label).toLowerCase())} is our leading ${c.label.toLowerCase()} product with ${share}% of category revenue (${cur} ${money2(top.revenue)}).`
    + (low.length ? ` Sales of ${low.slice(0, 3).join(', ')} remain low and need attention.` : '');
}

function comparisonText(c: PresentationCategory, olderRevenue: number, cur: string): string {
  const prev = c.previousRevenue;
  const now = c.revenue;
  if (prev === 0 && olderRevenue === 0) return `${c.label} recorded ${cur} ${money2(now)} in sales this week — the first sales in the three-week window.`;
  const pct = prev ? ((now - prev) / prev) * 100 : null;
  const dir = pct == null ? 'rose' : pct >= 0 ? 'rose' : 'dropped';
  return `${c.label} sales ${dir}${pct == null ? '' : ` ${Math.abs(pct).toFixed(1)}%`} to ${cur} ${money2(now)} compared with last week (${cur} ${money2(prev)}). We are following up with our main clients to keep orders moving.`;
}

/** Applies stored edits on top of freshly built slides. */
export function applyEdits(slides: ReportSlide[], edits: Record<string, SlideEdit>): ReportSlide[] {
  return slides.map((s) => (edits[s.id] ? { ...s, ...edits[s.id] } : s));
}

/** Display number for slide `index` among included slides (1-based), or null if excluded. */
export const slideNumbers = (slides: ReportSlide[]) => {
  let i = 0;
  return slides.map((s) => (s.on ? ++i : null));
};
