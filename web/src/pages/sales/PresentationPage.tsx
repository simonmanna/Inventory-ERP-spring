import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Download, Loader2, Presentation, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import { KpiCard } from '@/pages/crm/crm-ui';
import { SECTIONS, THEMES, compact, deepDives, generateDeck, insights, rangeLabel } from './presentation/deck';
import type { DeckNotes, PresentationData, SectionKey, ThemeKey } from './presentation/types';

// ---- date presets (local calendar dates) ----

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const addDays = (d: Date, n: number) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const mondayOf = (d: Date) => addDays(d, -((d.getDay() + 6) % 7));

type Preset = { key: string; label: string; range: () => [string, string] };
const PRESETS: Preset[] = [
  { key: 'thisWeek', label: tx('This week'), range: () => { const t = new Date(); return [iso(mondayOf(t)), iso(t)]; } },
  { key: 'lastWeek', label: tx('Last week'), range: () => { const m = addDays(mondayOf(new Date()), -7); return [iso(m), iso(addDays(m, 6))]; } },
  { key: 'last7', label: tx('Last 7 days'), range: () => { const t = new Date(); return [iso(addDays(t, -6)), iso(t)]; } },
  { key: 'thisMonth', label: tx('This month'), range: () => { const t = new Date(); return [iso(new Date(t.getFullYear(), t.getMonth(), 1)), iso(t)]; } },
  { key: 'lastMonth', label: tx('Last month'), range: () => { const t = new Date(); return [iso(new Date(t.getFullYear(), t.getMonth() - 1, 1)), iso(new Date(t.getFullYear(), t.getMonth(), 0))]; } },
  { key: 'thisQuarter', label: tx('This quarter'), range: () => { const t = new Date(); return [iso(new Date(t.getFullYear(), Math.floor(t.getMonth() / 3) * 3, 1)), iso(t)]; } },
  { key: 'ytd', label: tx('Year to date'), range: () => { const t = new Date(); return [iso(new Date(t.getFullYear(), 0, 1)), iso(t)]; } },
];

const STORE_KEY = 'sales.presentation.v1';
interface Saved { theme: ThemeKey; notes: Partial<DeckNotes>; off: SectionKey[] }
function loadSaved(): Partial<Saved> {
  try { return JSON.parse(localStorage.getItem(STORE_KEY) ?? '{}') as Partial<Saved>; } catch { return {}; }
}

/** Sales → Generate presentation: pick a date range, add commentary, download a stakeholder-ready PPTX. */
export function PresentationPage() {
  const user = useAuthStore((s) => s.user);
  const saved = useMemo(loadSaved, []);
  const [preset, setPreset] = useState('lastWeek');
  const [[from, to], setRange] = useState<[string, string]>(PRESETS[1].range());
  const [theme, setTheme] = useState<ThemeKey>(saved.theme ?? 'corporate');
  const [off, setOff] = useState<Set<SectionKey>>(new Set(saved.off ?? []));
  const [busy, setBusy] = useState(false);
  const [notes, setNotes] = useState<DeckNotes>({
    title: 'Sales & Marketing Review', subtitle: '', role: '', highlights: '', marketing: '', actions: '',
    presenter: user ? `${user.firstName} ${user.lastName ?? ''}`.trim() : '',
    ...saved.notes,
  });

  useEffect(() => {
    try { localStorage.setItem(STORE_KEY, JSON.stringify({ theme, notes, off: [...off] } satisfies Saved)); } catch { /* storage unavailable */ }
  }, [theme, notes, off]);

  const invalid = !from || !to || from > to;
  const { data, isFetching, error } = useQuery({
    queryKey: ['sales', 'analytics', 'presentation', from, to],
    queryFn: async () => (await api.get<PresentationData>('/sales/analytics/presentation', { params: { from, to } })).data,
    enabled: !invalid,
  });

  const sections = useMemo(() => new Set(SECTIONS.map((s) => s.key).filter((k) => !off.has(k))), [off]);
  const slideCount = useMemo(() => {
    if (!data) return 0;
    const deep = Math.max(1, deepDives(data).length);
    return 1 + [...sections].reduce((n, k) => n + (k === 'categories' ? deep : 1), 0);
  }, [data, sections]);

  const set = (k: keyof DeckNotes) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setNotes((n) => ({ ...n, [k]: e.target.value }));
  const toggle = (k: SectionKey) => setOff((s) => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n; });

  async function generate() {
    if (!data) return;
    setBusy(true);
    try {
      const name = await generateDeck(data, notes, theme, sections);
      notify.success(tx('Presentation ready'), name);
    } catch (e) {
      notify.error(tx('Could not generate the presentation'), e instanceof Error ? e.message : undefined);
    } finally {
      setBusy(false);
    }
  }

  const cur = data?.organization.currencyCode ?? '';
  const k = data?.kpis;
  const delta = (v: number | null | undefined) => (v == null ? tx('no prior data') : `${v > 0 ? '+' : ''}${v.toFixed(1)}% ${tx('vs previous')}`);

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Presentation}
        title={tx('Generate presentation')}
        description={tx('Build a stakeholder-ready sales deck (PowerPoint) for any date range, with live charts you can edit.')}
        actions={
          <Button onClick={generate} disabled={!data || busy || invalid} size="lg">
            {busy ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Download className="mr-2 h-4 w-4" />}
            {tx('Download PowerPoint')}
          </Button>
        }
      />

      <div className="grid gap-4 xl:grid-cols-[380px_minmax(0,1fr)]">
        {/* ------------------------------------------------ settings */}
        <div className="space-y-4">
          <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
            <h3 className="text-sm font-semibold">{tx('Period')}</h3>
            <div className="flex flex-wrap gap-1.5">
              {PRESETS.map((p) => (
                <button key={p.key} type="button" onClick={() => { setPreset(p.key); setRange(p.range()); }}
                  className={cn('rounded-full border px-3 py-1 text-xs', preset === p.key ? 'border-primary bg-primary text-primary-foreground' : 'hover:bg-muted')}>{p.label}</button>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1"><Label htmlFor="pp-from">{tx('From')}</Label><Input id="pp-from" type="date" value={from} max={to} onChange={(e) => { setPreset('custom'); setRange([e.target.value, to]); }} /></div>
              <div className="space-y-1"><Label htmlFor="pp-to">{tx('To')}</Label><Input id="pp-to" type="date" value={to} min={from} onChange={(e) => { setPreset('custom'); setRange([from, e.target.value]); }} /></div>
            </div>
            {invalid && <p className="text-xs text-rose-600">{tx('The start date must be on or before the end date.')}</p>}
            {data && <p className="text-xs text-muted-foreground">{tx('Compared with')} {rangeLabel(data.previousPeriod.from, data.previousPeriod.to)}</p>}
          </section>

          <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
            <h3 className="text-sm font-semibold">{tx('Look & feel')}</h3>
            <div className="grid grid-cols-2 gap-2">
              {(Object.keys(THEMES) as ThemeKey[]).map((key) => {
                const th = THEMES[key];
                return (
                  <button key={key} type="button" onClick={() => setTheme(key)} aria-pressed={theme === key}
                    className={cn('overflow-hidden rounded-lg border text-left transition', theme === key ? 'ring-2 ring-primary ring-offset-1' : 'hover:border-primary/50')}>
                    <div className="flex h-12 items-end gap-1 p-2" style={{ background: `#${th.cover}` }}>
                      {th.palette.slice(0, 5).map((c, i) => <span key={c} className="w-3 rounded-sm" style={{ background: `#${c}`, height: `${12 + i * 5}px` }} />)}
                    </div>
                    <div className="px-2 py-1 text-xs font-medium">{th.label}</div>
                  </button>
                );
              })}
            </div>
          </section>

          <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
            <h3 className="text-sm font-semibold">{tx('Cover')}</h3>
            <div className="space-y-1"><Label htmlFor="pp-title">{tx('Title')}</Label><Input id="pp-title" value={notes.title} onChange={set('title')} /></div>
            <div className="space-y-1"><Label htmlFor="pp-sub">{tx('Subtitle')}</Label><Input id="pp-sub" value={notes.subtitle} onChange={set('subtitle')} placeholder={tx('e.g. Weekly activity report')} /></div>
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1"><Label htmlFor="pp-by">{tx('Presented by')}</Label><Input id="pp-by" value={notes.presenter} onChange={set('presenter')} /></div>
              <div className="space-y-1"><Label htmlFor="pp-role">{tx('Role')}</Label><Input id="pp-role" value={notes.role} onChange={set('role')} placeholder={tx('Sales & Marketing')} /></div>
            </div>
          </section>

          <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
            <h3 className="text-sm font-semibold">{tx('Commentary')}</h3>
            <p className="text-xs text-muted-foreground">{tx('One point per line. Leave blank to use the auto-generated insights only.')}</p>
            <div className="space-y-1"><Label htmlFor="pp-hl">{tx('Highlights & achievements')}</Label><Textarea id="pp-hl" rows={3} value={notes.highlights} onChange={set('highlights')} placeholder={tx('Convinced a client to switch to an in-stock colour and secured the order')} /></div>
            <div className="space-y-1"><Label htmlFor="pp-mk">{tx('Marketing update')}</Label><Textarea id="pp-mk" rows={3} value={notes.marketing} onChange={set('marketing')} placeholder={tx('Social media campaign and flyers for aggregates and blocks')} /></div>
            <div className="space-y-1"><Label htmlFor="pp-ac">{tx('Next actions')}</Label><Textarea id="pp-ac" rows={3} value={notes.actions} onChange={set('actions')} placeholder={tx('Customer — next action')} /></div>
          </section>
        </div>

        {/* ------------------------------------------------ preview */}
        <div className="space-y-4">
          {error && <div className="rounded-xl border border-rose-300 bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-950/30">{apiMessage(error, tx('Could not load the sales data'))}</div>}
          {k && (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <KpiCard label={`${tx('Revenue')} (${cur})`} value={compact(k.revenue.current)} sub={delta(k.revenue.changePercent)} tone={k.revenue.changePercent != null && k.revenue.changePercent < 0 ? 'danger' : 'success'} />
              <KpiCard label={tx('Gross margin')} value={k.marginPercent.current == null ? '—' : `${k.marginPercent.current}%`} sub={`${cur} ${compact(k.margin.current)}`} />
              <KpiCard label={tx('Orders confirmed')} value={k.ordersConfirmed.count} sub={`${cur} ${compact(k.ordersConfirmed.value)}`} />
              <KpiCard label={`${tx('Cash collected')} (${cur})`} value={compact(k.collections.current)} sub={delta(k.collections.changePercent)} />
            </div>
          )}

          {data && (
            <div className="rounded-xl border bg-card p-4 shadow-sm">
              <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold"><Sparkles className="h-4 w-4 text-primary" />{tx('Auto-generated insights')}</h3>
              <ul className="list-disc space-y-1 pl-5 text-sm">{insights(data).map((s) => <li key={s}>{s}</li>)}</ul>
            </div>
          )}

          <div className="rounded-xl border bg-card p-4 shadow-sm">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-semibold">{tx('Slides')} {data && <span className="font-normal text-muted-foreground">· {slideCount} {tx('slides')}</span>}</h3>
              {isFetching && <span className="flex items-center gap-1 text-xs text-muted-foreground"><Loader2 className="h-3 w-3 animate-spin" />{tx('Loading data…')}</span>}
            </div>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 2xl:grid-cols-4">
              <Thumb theme={theme} cover title={notes.title || tx('Sales & Marketing Review')} sub={data ? rangeLabel(data.period.from, data.period.to) : ''} n={1} on />
              {SECTIONS.map((s, i) => (
                <Thumb key={s.key} theme={theme} title={tx(s.title)} sub={tx(s.hint)} n={i + 2} on={!off.has(s.key)} onToggle={() => toggle(s.key)} cover={s.key === 'closing'} bars={i} />
              ))}
            </div>
            <p className="mt-3 text-xs text-muted-foreground">{tx('Click a slide to include or exclude it. Charts are native PowerPoint charts — edit them after download.')}</p>
          </div>
        </div>
      </div>
    </div>
  );
}

/** 16:9 slide thumbnail drawn in the selected theme. */
function Thumb({ theme, title, sub, n, on, onToggle, cover, bars = 0 }: { theme: ThemeKey; title: string; sub: string; n: number; on: boolean; onToggle?: () => void; cover?: boolean; bars?: number }) {
  const t = THEMES[theme];
  const bg = cover ? t.cover : t.bg;
  const ink = cover ? t.coverInk : t.ink;
  const heights = [40, 65, 50, 80, 60, 90].map((h, i) => ((h + bars * 17 + i * 11) % 70) + 25);
  return (
    <button type="button" onClick={onToggle} disabled={!onToggle} aria-pressed={on}
      className={cn('group relative aspect-video overflow-hidden rounded-lg border text-left shadow-sm transition', on ? 'opacity-100' : 'opacity-35 grayscale', onToggle && 'hover:-translate-y-0.5 hover:shadow-md')}
      style={{ background: `#${bg}` }}>
      {!cover && <span className="absolute inset-y-0 left-0 w-1" style={{ background: `#${t.primary}` }} />}
      {cover && <span className="absolute -right-6 -top-8 h-24 w-24 rounded-full opacity-30" style={{ background: `#${t.primary}` }} />}
      <div className="absolute inset-0 flex flex-col p-2.5 pl-3.5">
        <span className="text-[9px] font-bold" style={{ color: `#${t.primary}` }}>{String(n).padStart(2, '0')}</span>
        <span className="line-clamp-2 text-[11px] font-semibold leading-tight" style={{ color: `#${ink}` }}>{title}</span>
        <span className="mt-0.5 line-clamp-1 text-[9px]" style={{ color: `#${cover ? 'CBD5E1' : t.muted}` }}>{sub}</span>
        {!cover && (
          <div className="mt-auto flex h-1/3 items-end gap-1">
            {heights.map((h, i) => <span key={i} className="flex-1 rounded-t-sm" style={{ height: `${h}%`, background: `#${t.palette[i % t.palette.length]}` }} />)}
          </div>
        )}
      </div>
      {onToggle && <span className="absolute right-1.5 top-1.5 rounded-full px-1.5 text-[9px] font-semibold" style={{ background: on ? `#${t.primary}` : '#64748B', color: '#fff' }}>{on ? '✓' : '—'}</span>}
    </button>
  );
}
