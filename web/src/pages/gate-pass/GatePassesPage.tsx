import { useState } from 'react';
import { DoorOpen, Printer, Ban, AlertTriangle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import {
  BEARER_LABELS, PROBLEM_LABELS, SOURCE_LABELS, useGatePass, useGatePasses, type GatePass, type GatePassSourceType,
} from '@/features/gate-pass/api';
import { GatePassStateBadge, STATE_LABELS, VoidGatePassDialog, WarningList, printGatePass } from '@/features/gate-pass/GatePassPanel';

const EVENT_LABELS: Record<string, string> = {
  issued: tx('Issued'), printed: tx('Printed'), gate_out: tx('Gated out'), refused: tx('Refused at the gate'), voided: tx('Voided'),
};

const selectClass = 'h-9 rounded-md border bg-background px-2 text-sm';

/** Register of every gate pass: filter by state, document type, date and text; open one for its items and history. */
export function GatePassesPage() {
  const [status, setStatus] = useState('');
  const [sourceType, setSourceType] = useState('');
  const [q, setQ] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState<string | null>(null);
  const list = useGatePasses({ status, sourceType, q, from, to, page, pageSize: 25 });
  const meta = list.data?.meta;
  return (
    <div className="space-y-4 p-4 sm:p-6">
      <ListPageHeader title={tx('Gate passes')} icon={DoorOpen}
        description={tx('Every gate pass issued for deliveries, cash sales, invoices, transfers, pick orders, rentals, repairs and supplier returns.')} />
      <div className="flex flex-wrap gap-2">
        <Input className="h-9 w-56" placeholder={tx('Number, bearer, plate, document')} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} />
        <select className={selectClass} value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }} aria-label={tx('State')}>
          <option value="">{tx('All states')}</option>
          {(['issued', 'used', 'expired', 'void'] as const).map((s) => <option key={s} value={s}>{STATE_LABELS[s]}</option>)}
        </select>
        <select className={selectClass} value={sourceType} onChange={(e) => { setSourceType(e.target.value); setPage(1); }} aria-label={tx('Document')}>
          <option value="">{tx('All documents')}</option>
          {(Object.keys(SOURCE_LABELS) as GatePassSourceType[]).map((t) => <option key={t} value={t}>{SOURCE_LABELS[t]}</option>)}
        </select>
        <Input type="date" className="h-9 w-40" value={from} onChange={(e) => { setFrom(e.target.value); setPage(1); }} aria-label={tx('From')} />
        <Input type="date" className="h-9 w-40" value={to} onChange={(e) => { setTo(e.target.value); setPage(1); }} aria-label={tx('To')} />
      </div>
      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm">
          <thead className="bg-muted/50 text-xs text-muted-foreground">
            <tr>
              <th className="px-3 py-2 text-left">{tx('Number')}</th>
              <th className="px-3 py-2 text-left">{tx('Document')}</th>
              <th className="px-3 py-2 text-left">{tx('Bearer')}</th>
              <th className="px-3 py-2 text-left">{tx('Vehicle')}</th>
              <th className="px-3 py-2 text-left">{tx('Issued')}</th>
              <th className="px-3 py-2 text-left">{tx('Gate-out')}</th>
              <th className="px-3 py-2 text-left">{tx('State')}</th>
            </tr>
          </thead>
          <tbody>
            {list.isLoading && <tr><td colSpan={7} className="p-3"><Skeleton className="h-6 w-full" /></td></tr>}
            {list.data?.data.map((p) => (
              <tr key={p.id} className="cursor-pointer border-t hover:bg-muted/40" onClick={() => setOpen(p.id)}>
                <td className="px-3 py-2 font-medium">{p.number}{p.warnings.length > 0 && <AlertTriangle className="ml-1 inline h-3.5 w-3.5 text-amber-600" aria-label={tx('Has warnings')} />}</td>
                <td className="px-3 py-2">{SOURCE_LABELS[p.sourceType]} {p.sourceNumber}<div className="text-xs text-muted-foreground">{p.partnerName}</div></td>
                <td className="px-3 py-2">{p.bearerName}</td>
                <td className="px-3 py-2">{p.vehiclePlate ?? '—'}</td>
                <td className="px-3 py-2 whitespace-nowrap">{dateTime(p.issuedAt)}</td>
                <td className="px-3 py-2 whitespace-nowrap">{p.gateOutAt ? dateTime(p.gateOutAt) : '—'}</td>
                <td className="px-3 py-2"><GatePassStateBadge state={p.state} /></td>
              </tr>
            ))}
            {list.data && list.data.data.length === 0 && (
              <tr><td colSpan={7} className="p-6 text-center text-sm text-muted-foreground">{tx('No gate passes match.')}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      {meta && meta.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage(page - 1)}>{tx('Previous')}</Button>
          <span>{page} / {meta.totalPages}</span>
          <Button size="sm" variant="outline" disabled={page >= meta.totalPages} onClick={() => setPage(page + 1)}>{tx('Next')}</Button>
        </div>
      )}
      {open && <GatePassDetailDialog id={open} onClose={() => setOpen(null)} />}
    </div>
  );
}

function GatePassDetailDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const q = useGatePass(id);
  const p = q.data;
  const canVoid = useAuthStore((s) => s.permissions.includes(PERMISSIONS.gatePass.void));
  const [voiding, setVoiding] = useState<GatePass | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader><DialogTitle className="flex items-center gap-2">{p?.number ?? tx('Gate pass')}{p && <GatePassStateBadge state={p.state} />}</DialogTitle></DialogHeader>
        {!p ? <Skeleton className="h-40 w-full" /> : (
          <div className="space-y-3 text-sm">
            <p className="text-muted-foreground">{SOURCE_LABELS[p.sourceType]} {p.sourceNumber}{p.partnerName ? ` · ${p.partnerName}` : ''}</p>
            {p.state === 'issued' && p.problems.map((x) => <p key={x} className="text-destructive">{PROBLEM_LABELS[x] ?? x}</p>)}
            <WarningList warnings={p.warnings} />
            <dl className="grid gap-2 sm:grid-cols-2">
              <Row label={BEARER_LABELS[p.bearerType]} value={p.bearerName} />
              <Row label={tx('ID / licence no.')} value={p.bearerIdNumber} />
              <Row label={tx('Phone')} value={p.bearerPhone} />
              <Row label={tx('Vehicle plate')} value={p.vehiclePlate} />
              <Row label={tx('Vehicle type')} value={p.vehicleType} />
              <Row label={tx('Transport company')} value={p.transportCompany} />
              <Row label={tx('Destination')} value={p.destination} />
              <Row label={tx('Purpose')} value={p.purpose} />
              <Row label={tx('Valid until')} value={dateTime(p.validUntil)} />
              <Row label={tx('Prints')} value={String(p.printCount)} />
              {p.voidReason && <Row label={tx('Void reason')} value={p.voidReason} />}
            </dl>
            <table className="w-full rounded-md border text-sm">
              <tbody>
                {p.lines.map((l) => (
                  <tr key={l.id} className="border-t first:border-t-0">
                    <td className="px-2 py-1.5">{l.description}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{Number(l.quantity)} {l.unit ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div>
              <p className="mb-1 font-semibold">{tx('History')}</p>
              <ul className="space-y-1 text-xs">
                {p.events.map((e) => (
                  <li key={e.id}>
                    <span className="font-medium">{EVENT_LABELS[e.type] ?? e.type}</span> · {dateTime(e.at)}{e.byName ? ` · ${e.byName}` : ''}
                    {e.note ? ` — ${e.note}` : ''}
                    {typeof e.data?.sourceUpdate === 'string' ? <span className="block text-muted-foreground">{e.data.sourceUpdate}</span> : null}
                  </li>
                ))}
              </ul>
            </div>
            <div className="flex justify-end gap-2">
              {canVoid && p.status === 'issued' && (
                <Button variant="outline" className="text-destructive" onClick={() => setVoiding(p)}><Ban className="mr-1 h-4 w-4" />{tx('Void')}</Button>
              )}
              <Button onClick={() => printGatePass(p.id, () => q.refetch())}><Printer className="mr-1 h-4 w-4" />{tx('Print')}</Button>
            </div>
          </div>
        )}
        {voiding && <VoidGatePassDialog pass={voiding} onClose={() => { setVoiding(null); void q.refetch(); }} />}
      </DialogContent>
    </Dialog>
  );
}

function Row({ label, value }: { label: string; value: string | null | undefined }) {
  if (!value) return null;
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="font-medium">{value}</dd>
    </div>
  );
}
