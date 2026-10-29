import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Edit, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DEDUCTION_STATUS_LABEL, useChargeFine, useEmployeePicker, useFine, useFineStatus, usePaymentAccounts,
  useReverseFineCharge, type Fine, type FineDeduction,
} from '@/features/fleet/tyres-api';
import { Field, OptionalSelect, RemotePicker } from './fleet-ui';
import { DeductionStatusPill, FineFormDialog, FineStatusPill } from './FinesPage';

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium">{children ?? '—'}</dd>
    </div>
  );
}

/** Traffic fine detail (fleet 1.7): dispute / cancel, charge to the company or the driver, reverse a charge. */
export function FineDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: f, isLoading, isError, refetch } = useFine(id);
  const has = useAuthStore((s) => s.hasPermission);
  const [charging, setCharging] = useState(false);
  const [reversing, setReversing] = useState(false);
  const [statusTo, setStatusTo] = useState<'open' | 'disputed' | 'cancelled' | null>(null);
  const [editing, setEditing] = useState(false);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !f) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Fine not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canUpdate = has(PERMISSIONS.fleetIncident.update);
  const canCharge = has(PERMISSIONS.fleetIncident.charge);
  const deductionColumns: Column<FineDeduction>[] = [
    { key: 'requestedAt', header: tx('Requested'), render: (d) => dateTime(d.requestedAt) },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (d) => money(d.amount) },
    { key: 'status', header: tx('Status'), render: (d) => <DeductionStatusPill status={d.status} /> },
    { key: 'ref', header: tx('Reference'), render: (d) => d.exportReference ?? d.settledReference ?? d.cancelReason ?? '—' },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/fines')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{f.fineNumber}</h1>
            <FineStatusPill status={f.status} />
            {f.overdue && <span className="rounded-full bg-rose-100 px-2 py-0.5 text-xs font-medium text-rose-700">{tx('Overdue')}</span>}
          </div>
          <p className="text-sm text-muted-foreground">{f.offence} · {money(f.amount)}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {canCharge && f.status === 'open' && <Button onClick={() => setCharging(true)}>{tx('Charge')}</Button>}
          {canCharge && (f.status === 'charged' || f.status === 'settled') && <Button variant="outline" onClick={() => setReversing(true)}>{tx('Reverse charge')}</Button>}
          {canUpdate && f.status === 'open' && <Button variant="outline" onClick={() => setStatusTo('disputed')}>{tx('Dispute')}</Button>}
          {canUpdate && f.status === 'disputed' && <Button variant="outline" onClick={() => setStatusTo('open')}>{tx('Resolve dispute')}</Button>}
          {canUpdate && (f.status === 'open' || f.status === 'disputed') && (
            <>
              <Button variant="outline" onClick={() => setEditing(true)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>
              <Button variant="ghost" onClick={() => setStatusTo('cancelled')}>{tx('Cancel fine')}</Button>
            </>
          )}
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Item label={tx('Vehicle')}><Link className="text-primary hover:underline" to={`/fleet/vehicles/${f.vehicle.id}`}>{f.vehicle.code}</Link></Item>
            <Item label={tx('Driver')}>{f.driver?.name}</Item>
            <Item label={tx('When')}>{dateTime(f.offenceAt)}</Item>
            <Item label={tx('Place')}>{f.location}</Item>
            <Item label={tx('Authority')}>{f.authority}</Item>
            <Item label={tx('Notice number')}>{f.noticeNumber}</Item>
            <Item label={tx('Due date')}>{f.dueDate ? date(f.dueDate) : null}</Item>
            <Item label={tx('Incident')}>{f.incident ? <Link className="text-primary hover:underline" to={`/fleet/incidents/${f.incident.id}`}>{f.incident.incidentNumber}</Link> : null}</Item>
            <Item label={tx('Charged to')}>{f.chargedTo === 'company' ? tx('Company') : f.chargedTo === 'driver' ? tx('Driver') : null}</Item>
            <Item label={tx('Charged at')}>{f.chargedAt ? dateTime(f.chargedAt) : null}</Item>
            <Item label={tx('Expense')}>
              {f.expense ? <Link className="text-primary hover:underline" to={`/expenses?q=${encodeURIComponent(f.expense.expenseCode)}`}>{f.expense.expenseCode} · {f.expense.paymentStatus}</Link> : null}
            </Item>
            <Item label={tx('Deduction')}>{f.deductionStatus ? DEDUCTION_STATUS_LABEL[f.deductionStatus] : null}</Item>
          </dl>
          {f.statusReason && <p className="mt-4 text-sm text-muted-foreground">{tx('Status reason')}: {f.statusReason}</p>}
          {f.notes && <p className="mt-2 whitespace-pre-wrap text-sm">{f.notes}</p>}
        </CardContent>
      </Card>

      {(f.deductions ?? []).length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Salary deductions')}</CardTitle></CardHeader>
          <CardContent><DataTable columns={deductionColumns} data={f.deductions ?? []} getRowId={(d) => d.id} /></CardContent>
        </Card>
      )}

      {charging && <ChargeDialog fine={f} onClose={() => setCharging(false)} />}
      {reversing && <ReverseDialog fine={f} onClose={() => setReversing(false)} />}
      {statusTo && <FineStatusDialog fine={f} to={statusTo} onClose={() => setStatusTo(null)} />}
      {editing && <FineFormDialog fine={f} onClose={() => setEditing(false)} />}
    </div>
  );
}

function ChargeDialog({ fine, onClose }: { fine: Fine; onClose: () => void }) {
  const [to, setTo] = useState<'company' | 'driver'>(fine.driverEmployeeId ? 'driver' : 'company');
  const [paymentType, setPaymentType] = useState<'CREDIT' | 'CASH'>('CREDIT');
  const [accountId, setAccountId] = useState('');
  const [method, setMethod] = useState('BANK_TRANSFER');
  const [reference, setReference] = useState('');
  const [employee, setEmployee] = useState({ id: fine.driverEmployeeId ?? '', label: fine.driver?.name ?? '' });
  const [search, setSearch] = useState('');
  const [notes, setNotes] = useState('');
  const accounts = usePaymentAccounts(to === 'company' && paymentType === 'CASH');
  const employees = useEmployeePicker(search, search.trim().length > 0);
  const charge = useChargeFine();
  const valid = to === 'driver' ? !!employee.id : paymentType === 'CREDIT' || !!accountId;
  const submit = async () => {
    try {
      await charge.mutateAsync({ id: fine.id, body: to === 'driver'
        ? { chargeTo: 'driver', employeeId: employee.id, notes: notes.trim() || undefined }
        : { chargeTo: 'company', paymentType, paymentMethod: paymentType === 'CASH' ? method : undefined,
          accountId: paymentType === 'CASH' ? accountId : undefined, paymentReference: reference.trim() || undefined, notes: notes.trim() || undefined } });
      notify.success(tx('Fine charged'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not charge the fine')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Charge fine {{number}}', { number: fine.fineNumber })} · {money(fine.amount)}</DialogTitle>
          <DialogDescription>
            {to === 'company'
              ? tx('Raises an expense on the vehicle cost centre. Paid now from a cash / bank account, or later in Expenses.')
              : tx('Raises a salary-deduction request for payroll. Nothing is posted until payroll deducts it.')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-2">
            <Button variant={to === 'company' ? 'default' : 'outline'} className="h-11" onClick={() => setTo('company')}>{tx('Company')}</Button>
            <Button variant={to === 'driver' ? 'default' : 'outline'} className="h-11" onClick={() => setTo('driver')}>{tx('Driver')}</Button>
          </div>
          {to === 'driver' ? (
            <Field label={tx('Employee *')}>
              <RemotePicker value={employee.id} selectedLabel={employee.label} search={search} onSearch={setSearch}
                results={(employees.data ?? []).map((e) => ({ value: e.id, label: e.name, hint: e.employeeCode }))}
                loading={employees.isFetching} onPick={(v, label) => setEmployee({ id: v, label })} placeholder={tx('Search employees…')} clearLabel={tx('Clear')} />
            </Field>
          ) : (
            <>
              <Field label={tx('Payment')}>
                <OptionalSelect value={paymentType} onChange={(v) => setPaymentType((v || 'CREDIT') as 'CREDIT' | 'CASH')} placeholder={tx('Payment')}
                  options={[{ value: 'CREDIT', label: tx('Pay later in Expenses') }, { value: 'CASH', label: tx('Paid now') }]} />
              </Field>
              {paymentType === 'CASH' && (
                <>
                  <Field label={tx('Paid from *')}>
                    <OptionalSelect value={accountId} onChange={setAccountId} placeholder={tx('Choose an account')}
                      options={(accounts.data ?? []).map((a) => ({ value: a.id, label: `${a.name} · ${money(a.currentBalance)}` }))} />
                  </Field>
                  <Field label={tx('Method')}>
                    <OptionalSelect value={method} onChange={(v) => setMethod(v || 'BANK_TRANSFER')} placeholder={tx('Method')}
                      options={[{ value: 'CASH', label: tx('Cash') }, { value: 'BANK_TRANSFER', label: tx('Bank transfer') }, { value: 'MTN_MOBILE_MONEY', label: 'MTN Mobile Money' }, { value: 'AIRTEL_MONEY', label: 'Airtel Money' }]} />
                  </Field>
                  <Field label={tx('Reference')}><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
                </>
              )}
            </>
          )}
          <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || charge.isPending}>{tx('Charge')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ReverseDialog({ fine, onClose }: { fine: Fine; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const reverse = useReverseFineCharge();
  const submit = async () => {
    try {
      await reverse.mutateAsync({ id: fine.id, reason: reason.trim() });
      notify.success(tx('Charge reversed'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not reverse the charge')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Reverse charge')}</DialogTitle>
          <DialogDescription>
            {fine.chargedTo === 'company' ? tx('The expense is voided and its payment reversed in the ledger.') : tx('The salary-deduction request is cancelled.')}
          </DialogDescription>
        </DialogHeader>
        <Field label={tx('Reason *')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!reason.trim() || reverse.isPending}>{tx('Reverse charge')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function FineStatusDialog({ fine, to, onClose }: { fine: Fine; to: 'open' | 'disputed' | 'cancelled'; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const change = useFineStatus();
  const needsReason = to !== 'open';
  const submit = async () => {
    try {
      await change.mutateAsync({ id: fine.id, status: to, reason: reason.trim() || undefined });
      notify.success(tx('Saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not change the status')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{to === 'disputed' ? tx('Dispute') : to === 'cancelled' ? tx('Cancel fine') : tx('Resolve dispute')}</DialogTitle>
          <DialogDescription>{fine.fineNumber}</DialogDescription>
        </DialogHeader>
        <Field label={needsReason ? tx('Reason *') : tx('Reason')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={(needsReason && !reason.trim()) || change.isPending}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
