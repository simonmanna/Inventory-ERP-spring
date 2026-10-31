import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { CalendarClock } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { DataTable, type Column } from '@/components/data-table';
import { DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar } from '@/components/list';
import { usePaymentSchedule, usePlanPayment, type PaymentScheduleRow } from '@/features/invoicing/api';
import { usePartners } from '@/features/partners/api';
import { date, money } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const BUCKET: Record<PaymentScheduleRow['bucket'], { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }> = {
  overdue: { label: tx('Overdue'), variant: 'destructive' },
  next_7_days: { label: tx('Next 7 days'), variant: 'default' },
  next_30_days: { label: tx('Next 30 days'), variant: 'secondary' },
  later: { label: tx('Later'), variant: 'outline' },
  on_hold: { label: tx('On hold'), variant: 'outline' },
};

/** Open vendor bills by the day AP intends to pay them; payment itself stays on the bill. */
export function PaymentSchedulePage() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [partnerId, setPartnerId] = useState('');
  const { data, isLoading, isError } = usePaymentSchedule({
    from: from || undefined,
    to: to || undefined,
    partnerId: partnerId || undefined,
  });
  const { data: partners } = usePartners({ page: 1, pageSize: 200 });
  const planPayment = usePlanPayment();
  const has = useAuthStore((s) => s.hasPermission);
  const canPlan = has(PERMISSIONS.expense.update);

  const [editing, setEditing] = useState<PaymentScheduleRow | null>(null);
  const [plannedDate, setPlannedDate] = useState('');
  const [plannedAmount, setPlannedAmount] = useState('');
  const [onHold, setOnHold] = useState(false);
  const [notes, setNotes] = useState('');

  const openPlan = (row: PaymentScheduleRow) => {
    setEditing(row);
    setPlannedDate(row.plannedDate ?? '');
    setPlannedAmount(row.plannedAmount ?? '');
    setOnHold(row.onHold);
    setNotes(row.notes ?? '');
  };

  const savePlan = async (e: FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    try {
      await planPayment.mutateAsync({
        id: editing.documentId,
        plannedDate: plannedDate || undefined,
        plannedAmount: plannedAmount ? Number(plannedAmount) : undefined,
        onHold,
        notes: notes || undefined,
      });
      setEditing(null);
      notify.success(tx('Payment plan saved'));
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not save the payment plan')));
    }
  };

  const columns: Column<PaymentScheduleRow>[] = [
    { key: 'paymentDate', header: tx('Pay on'), render: (r) => date(r.paymentDate) },
    {
      key: 'documentNumber',
      header: tx('Bill'),
      render: (r) => (
        <Link to={`/vendor-bills/${r.documentId}`} className="font-medium text-primary hover:underline">
          {r.documentNumber}
        </Link>
      ),
    },
    { key: 'partnerName', header: tx('Supplier'), render: (r) => r.partnerName ?? '-' },
    { key: 'dueDate', header: tx('Due date'), render: (r) => (r.dueDate ? date(r.dueDate) : '-') },
    { key: 'amountResidual', header: tx('Balance due'), className: 'text-right', render: (r) => money(r.amountResidual) },
    { key: 'scheduledAmount', header: tx('Scheduled'), className: 'text-right', render: (r) => money(r.scheduledAmount) },
    {
      key: 'bucket',
      header: tx('Status'),
      render: (r) => <Badge variant={BUCKET[r.bucket].variant}>{BUCKET[r.bucket].label}</Badge>,
    },
    {
      key: 'actions',
      header: '',
      render: (r) =>
        canPlan ? (
          <Button variant="ghost" size="sm" onClick={() => openPlan(r)}>
            {tx('Plan')}
          </Button>
        ) : null,
    },
  ];

  const tiles = [
    { label: tx('Overdue'), value: data?.totals.overdue },
    { label: tx('Next 7 days'), value: data?.totals.next7Days },
    { label: tx('Next 30 days'), value: data?.totals.next30Days },
    { label: tx('Later'), value: data?.totals.later },
    { label: tx('On hold'), value: data?.totals.onHold },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Payment Schedule')}
        description={tx('Open vendor bills by the day you plan to pay them.')}
        icon={CalendarClock}
      />
      <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {tiles.map((t) => (
          <Card key={t.label}>
            <CardHeader className="pb-4">
              <CardDescription>{t.label}</CardDescription>
              <CardTitle className="text-xl">{money(t.value)}</CardTitle>
            </CardHeader>
          </Card>
        ))}
      </div>
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
        <FilterSelect
          value={partnerId}
          onChange={setPartnerId}
          allLabel={tx('All suppliers')}
          options={(partners?.data ?? []).filter((p) => p.isSupplier).map((p) => ({ value: p.id, label: p.name }))}
          className="w-[200px]"
        />
      </ListToolbar>
      {isError && <p className="text-sm text-destructive">{tx('Could not load the payment schedule.')}</p>}
      <ListCard>
        <DataTable
          columns={columns}
          data={data?.rows ?? []}
          loading={isLoading}
          getRowId={(r) => r.documentId}
          emptyMessage={tx('No open vendor bills.')}
        />
      </ListCard>

      <Dialog open={!!editing} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Plan payment')} {editing?.documentNumber}</DialogTitle>
            <DialogDescription>
              {tx('No money moves here. Pay from the bill when the day comes.')}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={savePlan} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="plannedDate">{tx('Planned date')}</Label>
                <Input id="plannedDate" type="date" value={plannedDate} onChange={(e) => setPlannedDate(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="plannedAmount">{tx('Planned amount')}</Label>
                <Input
                  id="plannedAmount"
                  type="number"
                  step="any"
                  min="0.01"
                  max={editing?.amountResidual}
                  value={plannedAmount}
                  onChange={(e) => setPlannedAmount(e.target.value)}
                />
              </div>
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={onHold} onChange={(e) => setOnHold(e.target.checked)} />
              {tx('Hold this payment')}
            </label>
            <div className="space-y-2">
              <Label htmlFor="planNotes">{tx('Notes')}</Label>
              <Textarea id="planNotes" rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setEditing(null)}>{tx('Cancel')}</Button>
              <Button type="submit" disabled={planPayment.isPending}>
                {planPayment.isPending ? tx('Saving...') : tx('Save plan')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
