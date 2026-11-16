import { useState, type ReactNode } from 'react';
import {
  AlertTriangle, CalendarClock, Check, CheckCircle2, CircleDashed, ClipboardList, FileText, Lightbulb, ListChecks, NotebookPen, PackageCheck, Save,
  Settings2, Truck, Undo2, XCircle, type LucideIcon,
} from 'lucide-react';
import { ColumnPicker, useColumns, type ColumnDef } from '@/components/column-picker';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { date as fmtDate } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { PlanMethod } from '@/features/sales/delivery-api';
import type { HandoverForm } from '../handover-draft';
import { Labeled } from './DeliveryEditorDialog';
import { DeliveryPlanPanel } from './DeliveryPlanPanel';
import { DeliveryDocuments, DeliveryNotePreview, ReturnsList, type DeliveryAttachments, type ReturnView } from './delivery-docs';
import { Kpi, LineBar, Progress, Section, n, qty, qtyUnit } from './delivery-ui';

/** Handover status of an invoice / branch cash sale (one handover per document). */
export type HandoverStatus = 'pending' | 'scheduled' | 'dispatched' | 'delivered' | 'failed';

/** One document line in the fulfilment table. {@code issued} null = the document does not track stock issues. */
export interface HandoverLineView {
  id: string; name: string; description?: string | null; uom: string | null; ordered: number; issued: number | null; handedOver: number;
  /** Picked / loaded for the handover; invoiced; returned by the customer (credit note or void). */
  picked?: number | null; invoiced?: number | null; returned?: number | null;
}

/** The delivery note and returns of the document, shown as tabs beside the line operations. */
export interface HandoverDocs {
  noteNumber: string | null; sourceLabel: string; sourceNumber?: string | null; customerName?: string | null;
  deliveredAt?: string | null; onNote?: (mode: 'print' | 'download') => void; returns?: ReturnView[]; currency?: string;
}

type LineCol = 'product' | 'description' | 'scheduled' | 'deadline' | 'ordered' | 'picked' | 'issued' | 'toIssue' | 'delivered'
  | 'invoiced' | 'returned' | 'outstanding' | 'progress';

/** Fields that only make sense when goods travel (dropped when the customer collects). */
export const TRAVEL_ONLY: Array<keyof HandoverForm> = ['driver', 'vehicle', 'landmark', 'offloadingEquipment', 'gpsCoordinates', 'offloadingSite'];

/** Request body of a handover save: every editable field (empty = null), travel-only fields cleared on a pickup. */
export function handoverBody(f: HandoverForm, only?: Array<keyof HandoverForm>) {
  const b: Record<string, unknown> = {};
  (Object.keys(f) as Array<keyof HandoverForm>).forEach((k) => { if (!only || only.includes(k)) b[k] = f[k] || null; });
  if (!only) {
    b.method = f.method;
    if (f.method === 'pickup') TRAVEL_ONLY.forEach((k) => { if (k in b) b[k] = null; });
    if (f.method !== 'transfer' && 'destinationLocationId' in b) b.destinationLocationId = null;
    if (f.method !== 'courier' && 'courierName' in b) b.courierName = null;
  }
  return b;
}

const STEPS: Array<{ key: HandoverStatus; icon: LucideIcon }> = [
  { key: 'pending', icon: CircleDashed }, { key: 'scheduled', icon: CalendarClock }, { key: 'dispatched', icon: Truck }, { key: 'delivered', icon: CheckCircle2 },
];

/** Pending → Scheduled → Dispatched → Delivered / Collected, with a Failed marker. */
export function HandoverStepper({ status, collect }: { status: HandoverStatus; collect: boolean }) {
  const label: Record<HandoverStatus, string> = {
    pending: tx('Pending'), scheduled: tx('Scheduled'), dispatched: tx('Dispatched'), delivered: collect ? tx('Collected') : tx('Delivered'), failed: tx('Failed'),
  };
  const at = STEPS.findIndex((s) => s.key === status);
  return (
    <ol className="flex flex-wrap items-center gap-2" aria-label={tx('Delivery status')}>
      {STEPS.filter((s) => !(collect && s.key === 'dispatched')).map((s) => {
        const Icon = s.icon;
        const idx = STEPS.indexOf(s);
        const current = idx === at;
        const done = status !== 'failed' && (idx < at || status === 'delivered');
        return (
          <li key={s.key} aria-current={current ? 'step' : undefined}
            className={cn('flex items-center gap-2 rounded-full border px-3.5 py-1.5 text-sm transition-colors',
              current && status !== 'delivered' && 'border-primary bg-primary text-primary-foreground shadow-sm',
              current && status === 'delivered' && 'border-emerald-600 bg-emerald-600 text-white shadow-sm',
              !current && done && 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-300',
              !current && !done && 'bg-card text-muted-foreground')}>
            <Icon className="h-4 w-4" /><span className="font-medium">{label[s.key]}</span>
          </li>
        );
      })}
      {status === 'failed' && (
        <li className="flex items-center gap-2 rounded-full border border-rose-300 bg-rose-50 px-3.5 py-1.5 text-sm font-medium text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300">
          <XCircle className="h-4 w-4" />{label.failed}
        </li>
      )}
    </ol>
  );
}

/**
 * Delivery & fulfilment of a document with a single handover (invoice, branch cash sale): status, overview, the same
 * delivery plan as a sales order (method cards, address / site, location & site details), transport and schedule,
 * receipt and notes, and per-line fulfilment. Pure view: the caller owns loading, saving and the status actions.
 */
export function HandoverDeliveryView({
  form, onChange, status, lines, stockLocationName, issuedLabel = tx('Issued from stock'), methods, hide = [], planLocked, receiptLocked,
  actions, notice, draft, dirty, saving, onSave, onDiscard, completed, tip, docs, attachments,
}: {
  form: HandoverForm; onChange: (f: HandoverForm) => void; status: HandoverStatus; lines?: HandoverLineView[];
  stockLocationName?: string | null; issuedLabel?: string; methods?: PlanMethod[]; hide?: Array<keyof HandoverForm>;
  planLocked: boolean; receiptLocked: boolean; actions?: ReactNode; notice?: ReactNode; draft?: boolean;
  dirty?: boolean; saving?: boolean; onSave?: () => void; onDiscard?: () => void; completed?: ReactNode; tip?: string;
  docs?: HandoverDocs; attachments?: DeliveryAttachments;
}) {
  const set = <K extends keyof HandoverForm>(k: K, v: HandoverForm[K]) => onChange({ ...form, [k]: v });
  const collect = form.method === 'pickup';
  const rows = lines ?? [];
  const tracksIssue = rows.some((l) => l.issued != null);
  // Quantities only add up within one unit; with mixed units (tonnes + pieces) the tiles count lines instead.
  const units = new Set(rows.map((l) => l.uom ?? ''));
  const oneUnit = units.size <= 1;
  const unit = oneUnit ? [...units][0] || null : null;
  const sum = (f: (l: HandoverLineView) => number) => rows.reduce((a, l) => a + f(l), 0);
  const count = (f: (l: HandoverLineView) => boolean) => rows.filter(f).length;
  const ordered = oneUnit ? sum((l) => l.ordered) : rows.length;
  const issued = oneUnit ? sum((l) => n(l.issued)) : count((l) => l.ordered > 0 && n(l.issued) >= l.ordered);
  const handed = oneUnit ? sum((l) => l.handedOver) : count((l) => l.ordered > 0 && l.handedOver >= l.ordered);
  const outstanding = Math.max(0, ordered - handed);
  const pct = ordered > 0 ? (handed / ordered) * 100 : 0;
  const show = (v: number) => (oneUnit ? qtyUnit(v, unit) : `${v} ${tx('lines')}`);

  const field = (k: keyof HandoverForm, label: string, locked: boolean, type = 'text', placeholder?: string) => !hide.includes(k) && (
    <Labeled label={label}>
      {locked ? <p className="py-2 text-sm">{(type === 'date' && form[k] ? fmtDate(form[k] as string) : (form[k] as string)) || '—'}</p>
        : <Input type={type} value={(form[k] as string) ?? ''} placeholder={placeholder} onChange={(e) => set(k, e.target.value as never)} />}
    </Labeled>
  );

  return (
    <div className={cn('space-y-5', dirty && onSave && 'pb-16')}>
      {/* status */}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,300px)] xl:items-center">
        <div className="space-y-3">
          <HandoverStepper status={status} collect={collect} />
          {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
        </div>
        <div className="flex items-start gap-3 rounded-xl border border-blue-200 bg-blue-50/70 px-4 py-3 text-sm dark:border-blue-900 dark:bg-blue-950/30">
          <Lightbulb className="mt-0.5 h-5 w-5 shrink-0 text-blue-600" />
          <div><p className="font-semibold text-blue-900 dark:text-blue-200">{collect ? tx('Customer collect') : tx('Delivery to the customer')}</p>
            <p className="text-xs text-blue-800/80 dark:text-blue-300/80">{tip ?? tx('Plan the handover, then record dispatch and who received the goods.')}</p></div>
        </div>
      </div>
      {notice && <p className="rounded-lg border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">{notice}</p>}
      {completed && <p className="flex items-center gap-1.5 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-300"><CheckCircle2 className="h-4 w-4" />{completed}</p>}

      {/* overview */}
      {!draft && rows.length > 0 && (
        <div className="space-y-3">
          <div className={cn('grid gap-3 sm:grid-cols-2', tracksIssue ? 'xl:grid-cols-4' : 'xl:grid-cols-3')}>
            <Kpi icon={ClipboardList} label={tx('Ordered')} value={show(ordered)} hint={`${rows.length} ${rows.length === 1 ? tx('line') : tx('lines')}`} />
            {tracksIssue && <Kpi icon={Truck} tone="blue" label={issuedLabel} value={show(issued)} hint={stockLocationName ?? undefined} />}
            <Kpi icon={PackageCheck} tone="green" label={collect ? tx('Collected') : tx('Delivered')} value={show(handed)} />
            <Kpi icon={AlertTriangle} tone="amber" label={tx('Outstanding')} value={show(outstanding)} />
          </div>
          <div className="flex items-center gap-3 rounded-xl border bg-card px-4 py-2.5 shadow-sm">
            <span className="whitespace-nowrap text-sm font-medium">{collect ? tx('Total collected') : tx('Total delivered')}: {qty(pct)}%</span>
            <Progress value={pct} />
          </div>
        </div>
      )}

      {/* plan */}
      <Section icon={Settings2} title={tx('Delivery plan')}>
        <DeliveryPlanPanel value={form} onChange={onChange} locked={planLocked} fallbackLocationName={stockLocationName} methods={methods}
          hide={[...hide, 'scheduledDate', 'deadline']} />
      </Section>

      <div className="grid gap-5 lg:grid-cols-2">
        <Section icon={Truck} title={collect ? tx('Pickup schedule') : tx('Transport & schedule')}>
          <div className="grid gap-3 sm:grid-cols-2">
            {field('scheduledDate', collect ? tx('Pickup date') : tx('Scheduled date'), planLocked, 'date')}
            {field('deadline', tx('Deadline'), planLocked, 'date')}
            {!collect && field('driver', tx('Driver'), planLocked)}
            {!collect && field('vehicle', tx('Vehicle / plate'), planLocked, 'text', tx('e.g. UBA 123X'))}
          </div>
        </Section>
        <Section icon={NotebookPen} title={tx('Receipt & notes')}>
          <div className="grid gap-3">
            {!draft && field('receivedBy', collect ? tx('Collected by') : tx('Received by'), receiptLocked)}
            {!hide.includes('notes') && (
              <Labeled label={tx('Delivery notes')}>
                {receiptLocked ? <p className="whitespace-pre-wrap py-2 text-sm">{form.notes || '—'}</p>
                  : <Textarea rows={3} value={form.notes} placeholder={tx('Instructions for the driver or the store')} onChange={(e) => set('notes', e.target.value)} />}
              </Labeled>
            )}
          </div>
        </Section>
      </div>

      {/* delivery documents: printable delivery note and uploaded delivery documents */}
      {!draft && (docs || attachments) && (
        <DeliveryDocuments attachments={attachments}
          emptyNote={tx('The delivery note is numbered once the document is saved.')}
          notes={docs?.noteNumber ? [{
            key: 'note', number: docs.noteNumber,
            caption: [docs.sourceNumber ? `${docs.sourceLabel} ${docs.sourceNumber}` : null,
              status === 'delivered' ? (collect ? tx('Collected') : tx('Delivered')) : null].filter(Boolean).join(' · ') || null,
            preview: <HandoverNotePreview docs={docs} form={form} rows={rows} done={status === 'delivered'} collect={collect} />,
            onPrint: docs.onNote && (() => docs.onNote!('print')), onDownload: docs.onNote && (() => docs.onNote!('download')),
          }] : []} />
      )}

      {/* fulfilment: operations, delivery note, returns */}
      {!draft && (
        <Fulfilment rows={rows} status={status} collect={collect} tracksIssue={tracksIssue} issuedLabel={issuedLabel}
          stockLocationName={stockLocationName} form={form} docs={docs} />
      )}

      {dirty && onSave && (
        <div className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-card/95 px-4 py-2.5 shadow-lg backdrop-blur">
          <span className="text-sm text-muted-foreground">{tx('You have unsaved delivery changes')}</span>
          <div className="flex gap-2">
            {onDiscard && <Button size="sm" variant="ghost" disabled={saving} onClick={onDiscard}><Undo2 className="mr-1 h-4 w-4" />{tx('Discard')}</Button>}
            <Button size="sm" disabled={saving} onClick={onSave}><Save className="mr-1 h-4 w-4" />{tx('Save delivery details')}</Button>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Per-line fulfilment of a single-handover document, with optional columns (date scheduled, deadline, picked,
 * issued, delivered, invoiced, returned, outstanding) and, when {@code docs} is given, the delivery note and returns.
 */
function Fulfilment({ rows, status, collect, tracksIssue, issuedLabel, stockLocationName, form, docs }: {
  rows: HandoverLineView[]; status: HandoverStatus; collect: boolean; tracksIssue: boolean; issuedLabel: string;
  stockLocationName?: string | null; form: HandoverForm; docs?: HandoverDocs;
}) {
  const [tab, setTab] = useState('operations');
  const done = status === 'delivered';
  const hasPicked = rows.some((l) => l.picked != null);
  const hasInvoiced = rows.some((l) => l.invoiced != null);
  const hasReturned = rows.some((l) => l.returned != null);
  const defs: ColumnDef<LineCol>[] = [
    { key: 'product', label: tx('Product'), locked: true },
    { key: 'description', label: tx('Description'), defaultVisible: false },
    { key: 'scheduled', label: collect ? tx('Pickup date') : tx('Date scheduled') },
    { key: 'deadline', label: tx('Deadline') },
    { key: 'ordered', label: tx('Demand'), locked: true },
    ...(hasPicked ? [{ key: 'picked' as const, label: tx('Picked') }] : []),
    ...(tracksIssue ? [{ key: 'issued' as const, label: issuedLabel }, { key: 'toIssue' as const, label: tx('To issue'), defaultVisible: false }] : []),
    { key: 'delivered', label: collect ? tx('Collected') : tx('Delivered') },
    ...(hasInvoiced ? [{ key: 'invoiced' as const, label: tx('Invoiced') }] : []),
    ...(hasReturned ? [{ key: 'returned' as const, label: tx('Returned') }] : []),
    { key: 'outstanding', label: tx('Outstanding'), defaultVisible: false },
    { key: 'progress', label: tx('Progress') },
  ];
  const cols = useColumns<LineCol>('handover-lines', defs);
  const on = (k: LineCol) => defs.some((d) => d.key === k) && cols.shown(k);
  const late = !!form.deadline && !done && form.deadline < new Date().toISOString().slice(0, 10);
  const returns = docs?.returns ?? [];
  const th = 'px-3 py-2 text-right';

  return (
    <Section icon={ListChecks} title={tx('Line fulfilment')} bodyClassName="p-0"
      actions={<span className="flex items-center gap-2">
        {stockLocationName && <span className="hidden text-xs text-muted-foreground sm:inline">{collect ? tx('Collect from') : tx('Ships from')}: <span className="font-medium text-foreground">{stockLocationName}</span></span>}
        {tab === 'operations' && <ColumnPicker columns={cols} />}
      </span>}>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="mx-4 mt-3">
          <TabsTrigger value="operations">{tx('Operations')}</TabsTrigger>
          {docs && <TabsTrigger value="note"><FileText className="mr-1 h-3.5 w-3.5" />{tx('Delivery note')}</TabsTrigger>}
          {docs && <TabsTrigger value="returns"><Undo2 className="mr-1 h-3.5 w-3.5" />{tx('Returns')}{returns.length ? ` (${returns.length})` : ''}</TabsTrigger>}
        </TabsList>
        <TabsContent value="operations" className="mt-2">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="border-b bg-muted/40 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground"><tr>
                <th className="px-4 py-2">{tx('Product')}</th>
                {on('description') && <th className="px-3 py-2">{tx('Description')}</th>}
                {on('scheduled') && <th className="px-3 py-2">{collect ? tx('Pickup date') : tx('Date scheduled')}</th>}
                {on('deadline') && <th className="px-3 py-2">{tx('Deadline')}</th>}
                <th className={th}>{tx('Demand')}</th>
                {on('picked') && <th className={th}>{tx('Picked')}</th>}
                {on('issued') && <th className={th}>{issuedLabel}</th>}
                {on('toIssue') && <th className={th}>{tx('To issue')}</th>}
                {on('delivered') && <th className={th}>{collect ? tx('Collected') : tx('Delivered')}</th>}
                {on('invoiced') && <th className={th}>{tx('Invoiced')}</th>}
                {on('returned') && <th className={th}>{tx('Returned')}</th>}
                {on('outstanding') && <th className={th}>{tx('Outstanding')}</th>}
                {on('progress') && <th className="w-40 px-4 py-2">{tx('Progress')}</th>}
              </tr></thead>
              <tbody>
                {rows.map((l) => {
                  const rem = l.ordered - n(l.issued);
                  const out = Math.max(0, l.ordered - l.handedOver);
                  return (
                    <tr key={l.id} className="border-b last:border-0">
                      <td className="px-4 py-2"><span className="font-medium">{l.name}</span>
                        {!on('description') && l.description && l.description !== l.name && <span className="ml-1 text-xs text-muted-foreground">{l.description}</span>}</td>
                      {on('description') && <td className="px-3 py-2 text-muted-foreground">{l.description && l.description !== l.name ? l.description : '—'}</td>}
                      {on('scheduled') && <td className="whitespace-nowrap px-3 py-2">{form.scheduledDate ? fmtDate(form.scheduledDate) : '—'}</td>}
                      {on('deadline') && <td className={cn('whitespace-nowrap px-3 py-2', late && 'font-medium text-rose-600')}>{form.deadline ? fmtDate(form.deadline) : '—'}</td>}
                      <td className="px-3 py-2 text-right tabular-nums">{qtyUnit(l.ordered, l.uom)}</td>
                      {on('picked') && <td className="px-3 py-2 text-right tabular-nums"><PickedCell picked={n(l.picked)} ordered={l.ordered} /></td>}
                      {on('issued') && <td className="px-3 py-2 text-right tabular-nums">{qty(l.issued)}</td>}
                      {on('toIssue') && <td className={cn('px-3 py-2 text-right tabular-nums', rem > 0 && 'text-amber-600')}>{qty(Math.max(0, rem))}</td>}
                      {on('delivered') && <td className="px-3 py-2 text-right tabular-nums">{qty(l.handedOver)}</td>}
                      {on('invoiced') && <td className="px-3 py-2 text-right tabular-nums">{qty(l.invoiced)}</td>}
                      {on('returned') && <td className={cn('px-3 py-2 text-right tabular-nums', n(l.returned) > 0 && 'font-medium text-orange-600')}>{n(l.returned) > 0 ? qty(l.returned) : '—'}</td>}
                      {on('outstanding') && <td className={cn('px-3 py-2 text-right tabular-nums', out > 0 && 'text-amber-700 dark:text-amber-400')}>{qty(out)}</td>}
                      {on('progress') && <td className="px-4 py-2"><LineBar ordered={l.ordered} delivered={l.handedOver} dispatched={status === 'dispatched' ? l.ordered - l.handedOver : 0} scheduled={0} /></td>}
                    </tr>
                  );
                })}
                {rows.length === 0 && <tr><td colSpan={cols.count + 1} className="px-4 py-6 text-center text-muted-foreground">{tx('No lines yet.')}</td></tr>}
              </tbody>
            </table>
          </div>
        </TabsContent>
        {docs && (
          <TabsContent value="note" className="px-4 pb-4">
            <HandoverNotePreview docs={docs} form={form} rows={rows} done={done} collect={collect} />
          </TabsContent>
        )}
        {docs && (
          <TabsContent value="returns" className="px-4 pb-4">
            <ReturnsList returns={returns} currency={docs.currency} empty={tx('Nothing has been returned on this document.')} />
          </TabsContent>
        )}
      </Tabs>
    </Section>
  );
}

/** On-screen delivery note of a single-handover document (the same content as the printed PDF). */
function HandoverNotePreview({ docs, form, rows, done, collect }: {
  docs: HandoverDocs; form: HandoverForm; rows: HandoverLineView[]; done: boolean; collect: boolean;
}) {
  return (
    <DeliveryNotePreview number={docs.noteNumber} sourceLabel={docs.sourceLabel} sourceNumber={docs.sourceNumber}
      customer={docs.customerName ?? form.contactName} address={collect ? null : form.address}
      contact={[form.contactName, form.contactPhone].filter(Boolean).join(' · ') || null}
      shippingDate={done ? docs.deliveredAt : null} scheduledDate={form.scheduledDate || null} deadline={form.deadline || null}
      receivedBy={done ? form.receivedBy || null : null}
      lines={rows.map((l) => ({ id: l.id, name: l.name, uom: l.uom, ordered: l.ordered, delivered: l.handedOver, returned: n(l.returned) }))}
      onPrint={docs.onNote && (() => docs.onNote!('print'))} onDownload={docs.onNote && (() => docs.onNote!('download'))} />
  );
}

/** Picked quantity with a tick once the whole line is picked (like a picking checkbox). */
export function PickedCell({ picked, ordered }: { picked: number; ordered: number }) {
  const full = ordered > 0 && picked >= ordered;
  return (
    <span className="inline-flex items-center justify-end gap-1.5">
      {picked > 0 ? qty(picked) : '—'}
      <span className={cn('flex h-4 w-4 items-center justify-center rounded border', full ? 'border-primary bg-primary text-primary-foreground' : 'border-muted-foreground/40')}
        aria-label={full ? tx('Picked') : tx('Not picked')}>{full && <Check className="h-3 w-3" />}</span>
    </span>
  );
}
