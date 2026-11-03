import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CheckSquare, Download, Loader2, Pencil, Presentation, RotateCcw, Square } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import { applyEdits, buildReport, monthToDate, olderPeriod, slideNumbers, type SlideEdit } from './presentation/report';
import { exportReport } from './presentation/report-pptx';
import { SlideEditor } from './presentation/SlideEditor';
import { ScaledSlide } from './presentation/SlideView';
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
function loadEdits(): Record<string, SlideEdit> {
  try { return JSON.parse(localStorage.getItem(STORE_KEY) ?? '{}') as Record<string, SlideEdit>; } catch { return {}; }
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
  const [edits, setEdits] = useState<Record<string, SlideEdit>>(loadEdits);
  const [open, setOpen] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(edits)); } catch { /* storage full or unavailable — edits stay in memory */ }
  }, [edits]);

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
    ? applyEdits(buildReport({ data, older: older.data ?? null, month: month.data ?? null, presenter }), edits)
    : []), [data, older.data, month.data, presenter, edits]);
  const numbers = useMemo(() => slideNumbers(slides), [slides]);
  const included = numbers.filter((x) => x != null).length;
  const openIndex = slides.findIndex((s) => s.id === open);

  const patch = (id: string, p: SlideEdit) => setEdits((e) => ({ ...e, [id]: { ...e[id], ...p } }));
  const reset = (id: string) => setEdits((e) => { const n = { ...e }; delete n[id]; return n; });
  const setAll = (on: boolean) => setEdits((e) => Object.fromEntries(slides.map((s) => [s.id, { ...e[s.id], on }])));

  async function download() {
    if (!data) return;
    setBusy(true);
    try {
      const short = data.organization.name.split(/\s+/)[0] ?? 'Sales';
      const name = await exportReport(slides, `${short}_Sales-Marketing-Overview_${data.period.from}_${data.period.to}.pptx`, data.organization.name);
      notify.success(tx('Presentation ready'), name);
    } catch (e) {
      notify.error(tx('Could not generate the presentation'), e instanceof Error ? e.message : undefined);
    } finally {
      setBusy(false);
    }
  }

  const loading = current.isFetching || older.isFetching || month.isFetching;

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Presentation}
        title={tx('Generate presentation')}
        description={tx('Weekly sales & marketing report filled with live data. Click a slide to edit it; untick a slide to leave it out.')}
        actions={
          <Button onClick={download} disabled={!data || busy || invalid || included === 0} size="lg">
            {busy ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Download className="mr-2 h-4 w-4" />}
            {tx('Download PowerPoint')}
          </Button>
        }
      />

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
        onChange={(p) => open && patch(open, p)}
        onReset={() => open && reset(open)}
        onClose={() => setOpen(null)}
        onPrev={openIndex > 0 ? () => setOpen(slides[openIndex - 1].id) : undefined}
        onNext={openIndex >= 0 && openIndex < slides.length - 1 ? () => setOpen(slides[openIndex + 1].id) : undefined}
      />
    </div>
  );
}
