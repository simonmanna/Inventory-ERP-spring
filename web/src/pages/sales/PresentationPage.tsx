import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CheckSquare, Download, Loader2, Pencil, Plus, Presentation, RotateCcw, Sparkles, Square } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import type { DeckReply } from './presentation/chatgpt';
import { AddSlideDialog, DeckChatGptDialog, LookBar } from './presentation/DeckTools';
import { DEFAULT_LOOK, resolveLook, type DeckLook } from './presentation/look';
import {
  applyEdits, buildReport, mergeExtras, monthToDate, newCustomSlide, olderPeriod, slideNumbers, type CustomLayout, type ExtraSlide, type SlideEdit,
} from './presentation/report';
import { exportReport } from './presentation/report-pptx';
import { SlideEditor } from './presentation/SlideEditor';
import { LookProvider, ScaledSlide } from './presentation/SlideView';
import type { PresentationData } from './presentation/types';

// ---- date presets (local calendar dates) ----

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const addDays = (d: Date, n: number) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const mondayOf = (d: Date) => addDays(d, -((d.getDay() + 6) % 7));

type Preset = { key: string; label: string; range: () => [string, string] };
const PRESETS: Preset[] = [
  { key: 'last7', label: tx('Last 7 days'), range: () => { const t = new Date(); return [iso(addDays(t, -6)), iso(t)]; } },
  { key: 'thisWeek', label: tx('This week'), range: () => { const t = new Date(); return [iso(mondayOf(t)), iso(t)]; } },
  { key: 'lastWeek', label: tx('Last week'), range: () => { const m = addDays(mondayOf(new Date()), -7); return [iso(m), iso(addDays(m, 6))]; } },
  { key: 'thisMonth', label: tx('This month'), range: () => { const t = new Date(); return [iso(new Date(t.getFullYear(), t.getMonth(), 1)), iso(t)]; } },
  { key: 'lastMonth', label: tx('Last month'), range: () => { const t = new Date(); return [iso(new Date(t.getFullYear(), t.getMonth() - 1, 1)), iso(new Date(t.getFullYear(), t.getMonth(), 0))]; } },
];

const STORE_KEY = 'sales.presentation.report.v1';
const EXTRAS_KEY = 'sales.presentation.extras.v1';
const LOOK_KEY = 'sales.presentation.look.v1';
function load<T>(key: string, fallback: T): T {
  try { const v = localStorage.getItem(key); return v ? JSON.parse(v) as T : fallback; } catch { return fallback; }
}
function save(key: string, value: unknown) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage full or unavailable — state stays in memory */ }
}

const fetchData = async (from: string, to: string) =>
  (await api.get<PresentationData>('/sales/analytics/presentation', { params: { from, to } })).data;

/**
 * Sales → Generate presentation: the weekly sales & marketing report (same layout as the owner's
 * hand-made deck) filled with live data. Click a slide to edit it; tick/untick to include it.
 */
export function PresentationPage() {
  const user = useAuthStore((s) => s.user);
  const presenter = user ? `${user.firstName} ${user.lastName ?? ''}`.trim() : '';
  const [preset, setPreset] = useState('last7');
  const [[from, to], setRange] = useState<[string, string]>(PRESETS[0].range());
  const [edits, setEdits] = useState<Record<string, SlideEdit>>(() => load(STORE_KEY, {}));
  const [extras, setExtras] = useState<ExtraSlide[]>(() => load(EXTRAS_KEY, []));
  const [deckLook, setDeckLook] = useState<DeckLook>(() => ({ ...DEFAULT_LOOK, ...load<Partial<DeckLook>>(LOOK_KEY, {}) }));
  const look = useMemo(() => resolveLook(deckLook), [deckLook]);
  const [open, setOpen] = useState<string | null>(null);
  const [adding, setAdding] = useState<string | null>(null);
  const [askDeck, setAskDeck] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => save(STORE_KEY, edits), [edits]);
  useEffect(() => save(EXTRAS_KEY, extras), [extras]);
  useEffect(() => save(LOOK_KEY, deckLook), [deckLook]);

  const invalid = !from || !to || from > to;
  const current = useQuery({ queryKey: ['sales', 'analytics', 'presentation', from, to], queryFn: () => fetchData(from, to), enabled: !invalid });
  const data = current.data;
  const olderRange = data ? olderPeriod(data) : null;
  const monthRange = data ? monthToDate(data) : null;
  const older = useQuery({
    queryKey: ['sales', 'analytics', 'presentation', olderRange?.from, olderRange?.to],
    queryFn: () => fetchData(olderRange!.from, olderRange!.to), enabled: !!olderRange,
  });
  const month = useQuery({
    queryKey: ['sales', 'analytics', 'presentation', monthRange?.from, monthRange?.to],
    queryFn: () => fetchData(monthRange!.from, monthRange!.to), enabled: !!monthRange,
  });

  const slides = useMemo(() => (data
    ? mergeExtras(applyEdits(buildReport({ data, older: older.data ?? null, month: month.data ?? null, presenter }), edits), extras)
    : []), [data, older.data, month.data, presenter, edits, extras]);
  const numbers = useMemo(() => slideNumbers(slides), [slides]);
  const included = numbers.filter((x) => x != null).length;
  const openIndex = slides.findIndex((s) => s.id === open);

  const isExtra = (id: string) => extras.some((x) => x.id === id);
  /** Generated slides keep overrides in `edits`; added slides are edited in place. */
  const patch = (id: string, p: SlideEdit) => (isExtra(id)
    ? setExtras((xs) => xs.map((x) => (x.id === id ? { ...x, ...p, kind: 'custom', id } : x)))
    : setEdits((e) => ({ ...e, [id]: { ...e[id], ...p } })));
  const reset = (id: string) => setEdits((e) => { const n = { ...e }; delete n[id]; return n; });
  const setAll = (on: boolean) => {
    setEdits((e) => ({ ...e, ...Object.fromEntries(slides.filter((s) => !isExtra(s.id)).map((s) => [s.id, { ...e[s.id], on }])) }));
    setExtras((xs) => xs.map((x) => ({ ...x, on })));
  };
  const addSlide = (layout: CustomLayout, after: string) => {
    const s = newCustomSlide(layout, after);
    // a slide added after X goes before slides already chained after X, so re-anchor the old first follower
    setExtras((xs) => [...xs.map((x) => (x.after === after && after !== '' ? { ...x, after: s.id } : x)), s]);
    setAdding(null);
    setOpen(s.id);
  };
  const removeSlide = (id: string) => {
    const gone = extras.find((x) => x.id === id);
    setExtras((xs) => xs.filter((x) => x.id !== id).map((x) => (x.after === id ? { ...x, after: gone?.after ?? '' } : x)));
    setOpen(null);
  };
  const applyDeck = (r: DeckReply) => {
    const extraEdits = Object.entries(r.edits).filter(([id]) => isExtra(id));
    setEdits((e) => {
      const n = { ...e };
      for (const [id, p] of Object.entries(r.edits)) if (!isExtra(id)) n[id] = { ...n[id], ...p };
      return n;
    });
    setExtras((xs) => [...xs.map((x) => { const p = extraEdits.find(([id]) => id === x.id)?.[1]; return p ? { ...x, ...p, kind: 'custom' as const } : x; }), ...r.added]);
  };

  async function download() {
    if (!data) return;
    setBusy(true);
    try {
      const short = data.organization.name.split(/\s+/)[0] ?? 'Sales';
      const name = await exportReport(slides, `${short}_Sales-Marketing-Overview_${data.period.from}_${data.period.to}.pptx`, data.organization.name, look);
      notify.success(tx('Presentation ready'), name);
    } catch (e) {
      notify.error(tx('Could not generate the presentation'), e instanceof Error ? e.message : undefined);
    } finally {
      setBusy(false);
    }
  }

  const loading = current.isFetching || older.isFetching || month.isFetching;

  return (
    <LookProvider value={look}>
    <div className="space-y-4">
      <ListPageHeader
        icon={Presentation}
        title={tx('Generate presentation')}
        description={tx('Weekly sales & marketing report filled with live data. Pick a theme, click a slide to edit it, add your own slides with data and images, or polish it with ChatGPT.')}
        actions={
          <div className="flex flex-wrap gap-2">
          <Button variant="outline" size="lg" onClick={() => setAskDeck(true)} disabled={!data}><Sparkles className="mr-2 h-4 w-4" />{tx('ChatGPT')}</Button>
          <Button variant="outline" size="lg" onClick={() => setAdding(slides[slides.length - 1]?.id ?? '')} disabled={!data}><Plus className="mr-2 h-4 w-4" />{tx('Add slide')}</Button>
          <Button onClick={download} disabled={!data || busy || invalid || included === 0} size="lg">
            {busy ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Download className="mr-2 h-4 w-4" />}
            {tx('Download PowerPoint')}
          </Button>
          </div>
        }
      />

      <LookBar look={deckLook} onChange={setDeckLook} />

      <div className="flex flex-wrap items-end gap-3 rounded-xl border bg-card p-4 shadow-sm">
        <div className="space-y-1.5">
          <span className="text-xs font-medium text-muted-foreground">{tx('Period')}</span>
          <div className="flex flex-wrap gap-1.5">
            {PRESETS.map((p) => (
              <button key={p.key} type="button" onClick={() => { setPreset(p.key); setRange(p.range()); }}
                className={cn('rounded-full border px-3 py-1 text-xs', preset === p.key ? 'border-primary bg-primary text-primary-foreground' : 'hover:bg-muted')}>{p.label}</button>
            ))}
          </div>
        </div>
        <div className="space-y-1"><Label htmlFor="pp-from" className="text-xs">{tx('From')}</Label><Input id="pp-from" type="date" className="h-8 w-40" value={from} max={to} onChange={(e) => { setPreset('custom'); setRange([e.target.value, to]); }} /></div>
        <div className="space-y-1"><Label htmlFor="pp-to" className="text-xs">{tx('To')}</Label><Input id="pp-to" type="date" className="h-8 w-40" value={to} min={from} onChange={(e) => { setPreset('custom'); setRange([from, e.target.value]); }} /></div>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          {loading && <span className="flex items-center gap-1 text-xs text-muted-foreground"><Loader2 className="h-3 w-3 animate-spin" />{tx('Loading data…')}</span>}
          {data && <span className="text-sm text-muted-foreground">{included} / {slides.length} {tx('slides included')}</span>}
          <Button variant="outline" size="sm" onClick={() => setAll(true)} disabled={!data}><CheckSquare className="mr-1 h-3.5 w-3.5" />{tx('All')}</Button>
          <Button variant="outline" size="sm" onClick={() => setAll(false)} disabled={!data}><Square className="mr-1 h-3.5 w-3.5" />{tx('None')}</Button>
          <Button variant="outline" size="sm" onClick={() => setEdits({})} disabled={Object.keys(edits).length === 0}><RotateCcw className="mr-1 h-3.5 w-3.5" />{tx('Reset all edits')}</Button>
          {extras.length > 0 && <Button variant="outline" size="sm" onClick={() => { if (window.confirm(tx('Remove all slides you added?'))) setExtras([]); }}>{tx('Remove added slides')} ({extras.length})</Button>}
        </div>
        {invalid && <p className="w-full text-xs text-rose-600">{tx('The start date must be on or before the end date.')}</p>}
      </div>

      {current.error && <div className="rounded-xl border border-rose-300 bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-950/30">{apiMessage(current.error, tx('Could not load the sales data'))}</div>}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
        {slides.map((s, i) => (
          <div key={s.id} className={cn('group relative rounded-lg border bg-card p-2 shadow-sm transition', s.on ? 'hover:-translate-y-0.5 hover:shadow-md' : 'opacity-50')}>
            <button type="button" onClick={() => setOpen(s.id)} className="block w-full overflow-hidden rounded border text-left" aria-label={`${tx('Edit slide')} ${s.label}`}>
              <div className={cn(!s.on && 'grayscale')}><ScaledSlide slide={s} n={numbers[i]} /></div>
              <span className="absolute inset-2 hidden items-center justify-center rounded bg-black/0 group-hover:flex group-hover:bg-black/25">
                <span className="flex items-center gap-1 rounded-full bg-background px-3 py-1 text-xs font-medium shadow"><Pencil className="h-3 w-3" />{tx('Edit')}</span>
              </span>
            </button>
            <div className="mt-2 flex items-center gap-2 px-1">
              <input type="checkbox" className="h-4 w-4 cursor-pointer accent-primary" checked={s.on} aria-label={`${tx('Include')} ${s.label}`}
                onChange={(e) => patch(s.id, { on: e.target.checked })} />
              <span className="w-6 text-xs font-semibold text-muted-foreground">{numbers[i] ?? '—'}</span>
              <span className="truncate text-sm">{s.label}</span>
              {s.kind === 'custom' && <span className="ml-auto rounded bg-sky-100 px-1.5 text-[10px] font-medium text-sky-800 dark:bg-sky-900/40 dark:text-sky-200">{tx('added')}</span>}
              <button type="button" title={tx('Add a slide after this one')} aria-label={tx('Add a slide after this one')} onClick={() => setAdding(s.id)}
                className={cn('rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground', s.kind !== 'custom' && !(edits[s.id] && Object.keys(edits[s.id]).some((k) => k !== 'on')) && 'ml-auto')}><Plus className="h-3.5 w-3.5" /></button>
              {edits[s.id] && Object.keys(edits[s.id]).some((k) => k !== 'on') && <span className="ml-auto rounded bg-amber-100 px-1.5 text-[10px] font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">{tx('edited')}</span>}
            </div>
          </div>
        ))}
        {!data && !current.error && Array.from({ length: 8 }, (_, i) => <div key={i} className="aspect-video animate-pulse rounded-lg border bg-muted" />)}
      </div>

      <SlideEditor
        slide={openIndex >= 0 ? slides[openIndex] : null}
        n={openIndex >= 0 ? numbers[openIndex] : null}
        edited={!!open && !!edits[open]}
        org={data?.organization.name ?? ''}
        onChange={(p) => open && patch(open, p)}
        onReset={() => open && reset(open)}
        onDelete={open && isExtra(open) ? () => removeSlide(open) : undefined}
        onClose={() => setOpen(null)}
        onPrev={openIndex > 0 ? () => setOpen(slides[openIndex - 1].id) : undefined}
        onNext={openIndex >= 0 && openIndex < slides.length - 1 ? () => setOpen(slides[openIndex + 1].id) : undefined}
      />

      <AddSlideDialog key={adding ?? 'closed'} open={adding !== null} slides={slides} defaultAfter={adding ?? ''} onClose={() => setAdding(null)} onAdd={addSlide} />
      <DeckChatGptDialog open={askDeck} slides={slides} org={data?.organization.name ?? ''} onClose={() => setAskDeck(false)} onApply={applyDeck} />
    </div>
    </LookProvider>
  );
}
