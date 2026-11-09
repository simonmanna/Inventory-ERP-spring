import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { money } from '@/lib/format';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useProductPicker } from '@/features/menu/api';
import { selectClass, todayIso } from '@/features/projects/ui';
import { cn } from '@/lib/utils';
import {
  decimalOrUndefined, useCostingMutation, useLocationOptions, useReclassable, type JobProject, type ReclassableLine,
  type TimesheetCapture,
} from '@/features/projects/costing-api';
import { CostElementSelect, Field, WbsSelect, num } from './shared';

/** Posted expense lines still open for reclass, searchable by entry number, text, account or partner. */
function ReclassPicker({ projectId, value, onPick }: { projectId: string; value: ReclassableLine | null; onPick: (l: ReclassableLine) => void }) {
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const q = useDebouncedValue(search, 250);
  const { data: lines = [], isFetching } = useReclassable(projectId, { search: q.trim(), from, to });
  return (
    <div className="space-y-2">
      <div className="grid gap-2 sm:grid-cols-[1fr_9rem_9rem]">
        <Input placeholder={tx('Search entry, text, account or partner…')} value={search} onChange={(e) => setSearch(e.target.value)} />
        <Input type="date" aria-label={tx('From')} value={from} onChange={(e) => setFrom(e.target.value)} />
        <Input type="date" aria-label={tx('To')} value={to} onChange={(e) => setTo(e.target.value)} />
      </div>
      <div className="max-h-56 overflow-auto rounded-md border">
        {lines.length === 0 ? (
          <p className="p-3 text-sm text-muted-foreground">
            {isFetching ? tx('Loading…') : tx('No posted expense or cost-of-sales lines left to reclass.')}
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead className="sticky top-0 border-b bg-card">
              <tr className="text-left text-xs text-muted-foreground">
                <th className="px-2 py-1.5 font-medium">{tx('Entry')}</th>
                <th className="px-2 py-1.5 font-medium">{tx('Account')}</th>
                <th className="px-2 py-1.5 font-medium">{tx('Description')}</th>
                <th className="px-2 py-1.5 text-right font-medium">{tx('Open')}</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {lines.map((l) => (
                <tr
                  key={l.journalLineId}
                  className={cn('cursor-pointer hover:bg-muted/50', value?.journalLineId === l.journalLineId && 'bg-primary/10')}
                  onClick={() => onPick(l)}
                >
                  <td className="whitespace-nowrap px-2 py-1.5">
                    <span className="font-mono text-xs">{l.entryNumber}</span>
                    <span className="block text-xs text-muted-foreground">{l.postingDate.slice(0, 10)}</span>
                  </td>
                  <td className="px-2 py-1.5"><span className="font-mono text-xs">{l.accountCode}</span> {l.accountName}</td>
                  <td className="px-2 py-1.5 text-muted-foreground">{l.description}{l.partnerName && ` · ${l.partnerName}`}</td>
                  <td className="whitespace-nowrap px-2 py-1.5 text-right tabular-nums">{money(l.open)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

export type CaptureKind = 'issue' | 'timesheets' | 'equipment' | 'reclass';

const TITLE: Record<CaptureKind, string> = {
  issue: tx('Issue stock to the project'),
  timesheets: tx('Capture approved timesheets'),
  equipment: tx('Charge equipment hours'),
  reclass: tx('Reclass an expense to the project'),
};

const HINT: Record<CaptureKind, string> = {
  issue: tx('Takes the stock out of the location at carrying amount and debits project WIP.'),
  timesheets: tx('Prices every approved, not yet captured HR timesheet entry linked to this project in the window.'),
  equipment: tx('Debits project WIP and credits equipment absorbed at hours × rate.'),
  reclass: tx('Moves (part of) a posted expense or cost-of-sales journal line into project WIP.'),
};

function SiteSelect({ project, value, onChange }: { project: JobProject; value: string; onChange: (v: string) => void }) {
  return (
    <select className={selectClass} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{tx('No site')}</option>
      {project.sites.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
    </select>
  );
}

const blank = {
  wbsId: '', siteId: '', productId: '', productLabel: '', locationId: '', quantity: '', date: '', notes: '',
  excludeFromProgress: false, from: '', to: '', ratePerHour: '', equipmentRef: '', hours: '', journalLineId: '',
  amount: '', costElement: 'other',
};

/** Capture cost to a project: stock issue, approved timesheets, equipment hours or an expense reclass. */
export function CaptureCostDialog({ kind, project, onClose }: { kind: CaptureKind | null; project: JobProject; onClose: () => void }) {
  const [f, setF] = useState(blank);
  const [picked, setPicked] = useState<ReclassableLine | null>(null);
  const set = (patch: Partial<typeof blank>) => setF((cur) => ({ ...cur, ...patch }));
  useEffect(() => {
    setPicked(null);
    if (kind) setF({ ...blank, from: kind === 'timesheets' ? todayIso().slice(0, 8) + '01' : '', to: kind === 'timesheets' ? todayIso() : '' });
  }, [kind]);

  const [productSearch, setProductSearch] = useState('');
  const q = useDebouncedValue(productSearch, 250);
  const { data: products = [], isFetching } = useProductPicker(kind === 'issue' ? q : undefined);
  const { data: locations = [] } = useLocationOptions(kind === 'issue');

  const capture = useCostingMutation<void, TimesheetCapture | Record<string, unknown>>(() => {
    const base = `/projects/${project.id}/costs`;
    switch (kind) {
      case 'issue':
        return {
          method: 'post', money: true, url: `${base}/issue`,
          body: {
            wbsId: f.wbsId, siteId: f.siteId || undefined, productId: f.productId, locationId: f.locationId || undefined,
            quantity: decimalOrUndefined(f.quantity), date: f.date || undefined,
            excludeFromProgress: f.excludeFromProgress || undefined, notes: f.notes.trim() || undefined,
          },
        };
      case 'timesheets':
        return {
          method: 'post', money: true, url: `${base}/timesheets`,
          body: { wbsId: f.wbsId, from: f.from, to: f.to, ratePerHour: decimalOrUndefined(f.ratePerHour) },
        };
      case 'equipment':
        return {
          method: 'post', money: true, url: `${base}/equipment`,
          body: {
            wbsId: f.wbsId, siteId: f.siteId || undefined, equipmentRef: f.equipmentRef.trim() || undefined,
            hours: decimalOrUndefined(f.hours), ratePerHour: decimalOrUndefined(f.ratePerHour), date: f.date || undefined,
            notes: f.notes.trim() || undefined,
          },
        };
      default:
        return {
          method: 'post', money: true, url: `${base}/reclass`,
          body: {
            wbsId: f.wbsId, siteId: f.siteId || undefined, journalLineId: f.journalLineId.trim(),
            amount: decimalOrUndefined(f.amount), costElement: f.costElement,
            excludeFromProgress: f.excludeFromProgress || undefined, date: f.date || undefined,
            notes: f.notes.trim() || undefined,
          },
        };
    }
  });

  if (!kind) return null;

  const valid = !!f.wbsId && (
    kind === 'issue' ? !!f.productId && !!f.quantity
      : kind === 'timesheets' ? !!f.from && !!f.to && !!f.ratePerHour
        : kind === 'equipment' ? !!f.hours && !!f.ratePerHour
          : !!f.journalLineId.trim() && (f.amount.trim() === '' || (num(f.amount) > 0 && num(f.amount) <= num(picked?.open)))
  );

  const submit = () => capture.mutate(undefined, {
    onSuccess: (res) => {
      if (kind === 'timesheets' && res && 'entries' in res) {
        const r = res as TimesheetCapture;
        notify.success(tx('{{count}} timesheet entries captured · {{total}}', { count: r.entries, total: money(r.total) }));
      } else {
        notify.success(tx('Cost posted to the project'));
      }
      onClose();
    },
  });

  const site = project.sites.find((s) => s.id === f.siteId);

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className={kind === 'reclass' ? 'max-w-2xl' : 'max-w-lg'}>
        <DialogHeader>
          <DialogTitle>{TITLE[kind]}</DialogTitle>
          <DialogDescription>{HINT[kind]}</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-3"
          onSubmit={(e) => { e.preventDefault(); if (valid && !capture.isPending) submit(); }}
        >
          <Field label={tx('WBS element')}>
            <WbsSelect wbs={project.wbs} value={f.wbsId} onChange={(v) => set({ wbsId: v })} costableOnly />
          </Field>
          {kind !== 'timesheets' && project.sites.length > 0 && (
            <Field label={tx('Site')}><SiteSelect project={project} value={f.siteId} onChange={(v) => set({ siteId: v })} /></Field>
          )}

          {kind === 'issue' && (
            <>
              <Field label={tx('Product')}>
                <SearchableSelect
                  value={f.productId}
                  onValueChange={(v) => {
                    const p = products.find((x) => x.id === v);
                    set({ productId: v, productLabel: p ? `${p.code} · ${p.name}` : '' });
                  }}
                  options={products.map((p) => ({ value: p.id, label: p.name, code: p.code }))}
                  onQueryChange={setProductSearch}
                  selectedLabel={f.productLabel}
                  loading={isFetching}
                  placeholder={tx('Select a product…')}
                  searchPlaceholder={tx('Type at least 2 characters…')}
                />
              </Field>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label={tx('Location')} hint={site?.deliveryLocationId && !f.locationId ? tx('Blank uses the site delivery location.') : undefined}>
                  <select className={selectClass} value={f.locationId} onChange={(e) => set({ locationId: e.target.value })}>
                    <option value="">{site?.deliveryLocationId ? tx('Site delivery location') : tx('Select a location…')}</option>
                    {locations.map((l) => <option key={l.id} value={l.id}>{l.code ? `${l.code} · ` : ''}{l.name}</option>)}
                  </select>
                </Field>
                <Field label={tx('Quantity')}>
                  <Input type="number" step="any" min="0" value={f.quantity} onChange={(e) => set({ quantity: e.target.value })} />
                </Field>
              </div>
            </>
          )}

          {kind === 'timesheets' && (
            <div className="grid gap-3 sm:grid-cols-3">
              <Field label={tx('From')}><Input type="date" value={f.from} onChange={(e) => set({ from: e.target.value })} /></Field>
              <Field label={tx('To')}><Input type="date" value={f.to} onChange={(e) => set({ to: e.target.value })} /></Field>
              <Field label={tx('Rate per hour')}>
                <Input type="number" step="0.01" min="0" value={f.ratePerHour} onChange={(e) => set({ ratePerHour: e.target.value })} />
              </Field>
            </div>
          )}

          {kind === 'equipment' && (
            <>
              <Field label={tx('Equipment reference')}>
                <Input placeholder={tx('e.g. Excavator EX-02')} value={f.equipmentRef} onChange={(e) => set({ equipmentRef: e.target.value })} />
              </Field>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label={tx('Hours')}><Input type="number" step="0.01" min="0" value={f.hours} onChange={(e) => set({ hours: e.target.value })} /></Field>
                <Field label={tx('Rate per hour')}>
                  <Input type="number" step="0.01" min="0" value={f.ratePerHour} onChange={(e) => set({ ratePerHour: e.target.value })} />
                </Field>
              </div>
              {f.hours && f.ratePerHour && (
                <p className="text-xs text-muted-foreground">{tx('Amount')}: {money(Number(f.hours) * Number(f.ratePerHour))}</p>
              )}
            </>
          )}

          {kind === 'reclass' && (
            <>
              <Field label={tx('Expense line')} hint={tx('The posted expense or cost-of-sales journal line to move into the project.')}>
                <ReclassPicker projectId={project.id} value={picked} onPick={(l) => { setPicked(l); set({ journalLineId: l.journalLineId }); }} />
              </Field>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label={tx('Cost element')}><CostElementSelect value={f.costElement} onChange={(v) => set({ costElement: v })} /></Field>
                <Field
                  label={tx('Amount')}
                  hint={picked ? tx('Blank reclasses all {{open}} still open on the line.', { open: money(picked.open) }) : tx('Blank reclasses everything still open on the line.')}
                >
                  <Input type="number" step="0.01" min="0" value={f.amount} onChange={(e) => set({ amount: e.target.value })} />
                </Field>
              </div>
            </>
          )}

          {kind !== 'timesheets' && (
            <>
              <Field label={tx('Date')} hint={tx('Blank uses today.')}>
                <Input type="date" value={f.date} onChange={(e) => set({ date: e.target.value })} />
              </Field>
              <Field label={tx('Notes')}>
                <Textarea rows={2} value={f.notes} onChange={(e) => set({ notes: e.target.value })} />
              </Field>
            </>
          )}
          {(kind === 'issue' || kind === 'reclass') && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" className="h-4 w-4 accent-primary" checked={f.excludeFromProgress} onChange={(e) => set({ excludeFromProgress: e.target.checked })} />
              {tx('Exclude from progress (cost does not depict transfer of control, e.g. uninstalled materials)')}
            </label>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
            <Button type="submit" disabled={!valid || capture.isPending}>{capture.isPending ? tx('Posting…') : tx('Post cost')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
