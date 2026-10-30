import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ReceiptText, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column } from '@/components/data-table';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  useCancelFreightBill, useFreightBill, useMatchFreightBill, useRemoveFreightLine, type FreightBillLine,
} from '@/features/fleet/costing-api';
import { num } from '../fleet-format';
import { Field } from '../fleet-ui';
import { ReasonDialog } from '../dispatch/dispatch-ui';
import { FreightStatusPill } from './FreightBillsPage';

const today = () => new Date().toISOString().slice(0, 10);

/** Freight bill detail (fleet 1.9): trips, supplier-invoice matching with the tolerance, AP bill, GL, cancel. */
export function FreightBillDetailPage() {
  const { id } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: b, isLoading, isError } = useFreightBill(id);
  const match = useMatchFreightBill();
  const cancel = useCancelFreightBill();
  const removeLine = useRemoveFreightLine();
  const [form, setForm] = useState({ supplierReference: '', supplierInvoiceDate: today(), supplierAmount: '', dueDate: '' });
  const [cancelling, setCancelling] = useState(false);
  const canBill = has(PERMISSIONS.fleetFreight.bill);
  const canAccept = has(PERMISSIONS.fleetFreight.approveVariance);

  if (isLoading) return <Skeleton className="h-64 w-full" />;
  if (isError || !b) return <p className="text-sm text-muted-foreground">{tx('Could not load the freight bill.')}</p>;

  const open = b.status === 'draft' || b.status === 'disputed';
  const outbound = b.direction === 'outbound';
  const submit = async (acceptVariance = false) => {
    try {
      const out = await match.mutateAsync({
        id: b.id,
        body: {
          supplierReference: (form.supplierReference || b.supplierReference || '').trim(),
          supplierInvoiceDate: form.supplierInvoiceDate,
          supplierAmount: Number(form.supplierAmount || b.supplierAmount),
          dueDate: form.dueDate || undefined,
          acceptVariance: acceptVariance || undefined,
        },
      });
      if (out.status === 'disputed') notify.warning(tx('Outside the tolerance — the bill is disputed'), tx('Variance {{v}} (allowed {{a}}).', { v: money(out.varianceAmount), a: money(out.allowedVariance) }));
      else notify.success(tx('Matched · AP bill {{n}}', { n: out.vendorBillNumber ?? '' }));
    } catch (e) {
      notify.error(apiMessage(e, tx('Could not match the supplier invoice')));
    }
  };

  const columns: Column<FreightBillLine>[] = [
    { key: 'trip', header: tx('Trip'), render: (l) => (l.tripId ? <Link className="font-medium text-primary hover:underline" to={`/fleet/trips/${l.tripId}`}>{l.tripCode}</Link> : l.description) },
    { key: 'order', header: tx('Delivery order'), render: (l) => (l.deliveryOrderId ? <Link className="text-primary hover:underline" to={`/fleet/delivery-orders/${l.deliveryOrderId}`}>{l.deliveryOrderCode}</Link> : '—') },
    { key: 'customer', header: tx('Customer'), render: (l) => l.customerName ?? '—' },
    { key: 'closed', header: tx('Closed'), render: (l) => (l.closedAt ? date(l.closedAt) : '—') },
    { key: 'basis', header: tx('Freight'), render: (l) => (l.freightBasis ? `${l.freightBasis.replace('_', ' ')} · ${money(l.freightRate)}` : '—') },
    { key: 'tonnes', header: tx('Tonnes'), className: 'text-right tabular-nums', render: (l) => num(l.tonnes, 3) },
    { key: 'km', header: tx('km'), className: 'text-right tabular-nums', render: (l) => num(l.distanceKm, 1) },
    { key: 'amount', header: tx('Amount'), className: 'text-right tabular-nums font-medium', render: (l) => <span className={l.status === 'cancelled' ? 'line-through text-muted-foreground' : ''}>{money(l.amount)}</span> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (l) => canBill && open && l.status === 'active' && (
        <Button size="icon" variant="ghost" aria-label={tx('Remove trip')} onClick={async () => {
          try { await removeLine.mutateAsync({ id: b.id, lineId: l.id }); notify.success(tx('Trip removed from the bill')); } catch (e) { notify.error(apiMessage(e, tx('Could not remove the trip'))); }
        }}><Trash2 className="h-4 w-4" /></Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Link to="/fleet/freight-bills" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="h-4 w-4" />{tx('Freight Bills')}</Link>
      <ListPageHeader icon={ReceiptText} title={`${b.code} · ${b.transporter?.name ?? ''}`}
        description={outbound ? tx('Trips closed {{from}} – {{to}}', { from: date(b.periodFrom), to: date(b.periodTo) }) : tx('Inbound freight capitalised as landed cost')}
        actions={(
          <div className="flex items-center gap-2">
            <FreightStatusPill status={b.status} />
            {canBill && b.status !== 'cancelled' && <Button variant="outline" onClick={() => setCancelling(true)}>{tx('Cancel bill')}</Button>}
          </div>
        )} />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={tx('Trips')} value={String(b.tripCount)} />
        <Stat label={tx('Freight of the trips')} value={money(b.totalAmount)} />
        <Stat label={tx('Supplier invoice')} value={b.supplierAmount ? money(b.supplierAmount) : '—'} hint={b.supplierReference ?? undefined} />
        <Stat label={tx('Variance')} value={b.varianceAmount == null ? '—' : `${money(b.varianceAmount)}${b.variancePct ? ` (${num(b.variancePct, 2)} %)` : ''}`}
          hint={b.varianceAccepted ? tx('Accepted') : b.allowedVariance ? tx('Allowed {{a}}', { a: money(b.allowedVariance) }) : undefined} />
      </div>
      {outbound && open && canBill && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Match the supplier invoice')}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            <p className="text-sm text-muted-foreground">{tx('Within {{pct}} % (or {{amount}}) of the trips\' freight the AP bill is raised and the freight accrued (Dr outbound freight / Cr freight to receive); otherwise the bill is disputed.', { pct: num(b.tolerance.pct, 2), amount: money(b.tolerance.amount) })}</p>
            <div className="grid gap-3 sm:grid-cols-4">
              <Field label={tx('Supplier invoice no.')}><Input value={form.supplierReference || b.supplierReference || ''} onChange={(e) => setForm({ ...form, supplierReference: e.target.value })} /></Field>
              <Field label={tx('Invoice date')}><Input type="date" max={today()} value={form.supplierInvoiceDate} onChange={(e) => setForm({ ...form, supplierInvoiceDate: e.target.value })} /></Field>
              <Field label={tx('Invoice amount')}><Input inputMode="decimal" value={form.supplierAmount || b.supplierAmount || ''} onChange={(e) => setForm({ ...form, supplierAmount: e.target.value })} /></Field>
              <Field label={tx('Due date')}><Input type="date" value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} /></Field>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button disabled={match.isPending || !(form.supplierReference || b.supplierReference) || !(Number(form.supplierAmount || b.supplierAmount) > 0) || !b.tripCount} onClick={() => submit(false)}>{tx('Match')}</Button>
              {b.status === 'disputed' && canAccept && (
                <Button variant="outline" disabled={match.isPending} onClick={() => submit(true)}>{tx('Accept the variance and bill')}</Button>
              )}
            </div>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader><CardTitle className="text-base">{outbound ? tx('Trips on the bill') : tx('Charge')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto p-0">
          <DataTable columns={columns} data={b.lines} getRowId={(l) => l.id} cellClassName="py-2 px-4" headerRowClassName="h-10" emptyMessage={tx('No trips.')} />
        </CardContent>
      </Card>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Accounts Payable')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {b.vendorBill ? (
              <>
                <div className="flex justify-between"><span>{tx('Vendor bill')}</span><span className="font-medium">{b.vendorBill.documentNumber}</span></div>
                <div className="flex justify-between"><span>{tx('Status')}</span><span>{b.vendorBill.status} · {b.vendorBill.paymentStatus}</span></div>
                <div className="flex justify-between"><span>{tx('Amount')}</span><span className="tabular-nums">{money(b.vendorBill.totalAmount)}</span></div>
              </>
            ) : <p className="text-muted-foreground">{tx('No AP bill yet — match the supplier invoice first.')}</p>}
            {b.landedCost && <div className="flex justify-between"><span>{tx('Landed cost voucher')}</span><span className="font-medium">{b.landedCost.code} · {b.landedCost.status}</span></div>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Journal entries')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {b.journalEntries.length === 0 && <p className="text-muted-foreground">{tx('Nothing posted yet.')}</p>}
            {b.journalEntries.map((j) => (
              <div key={j.id} className="flex justify-between gap-2"><span className="font-medium">{j.entryNumber}</span><span className="truncate text-muted-foreground">{j.description}</span><span>{dateTime(j.postingDate)}</span></div>
            ))}
            {b.cancelReason && <p className="pt-2 text-muted-foreground">{tx('Cancelled: {{r}}', { r: b.cancelReason })}</p>}
          </CardContent>
        </Card>
      </div>
      <ReasonDialog open={cancelling} title={tx('Cancel {{code}}', { code: b.code })}
        description={b.status === 'billed' ? tx('The freight accrual is reversed and the trips can be billed again. A posted AP bill must be cancelled in Accounts Payable first.') : tx('The trips can be billed again.')}
        confirmLabel={tx('Cancel bill')} busy={cancel.isPending} onClose={() => setCancelling(false)}
        onConfirm={async (reason) => {
          try { await cancel.mutateAsync({ id: b.id, reason }); notify.success(tx('Freight bill cancelled')); setCancelling(false); } catch (e) { notify.error(apiMessage(e, tx('Could not cancel the freight bill'))); }
        }} />
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card><CardContent className="space-y-0.5 pt-4">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-xl font-semibold tabular-nums">{value}</div>
      {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
    </CardContent></Card>
  );
}
