import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Paperclip, Plus, RefreshCw, Trash2 } from 'lucide-react';
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
  BASIS_LABEL, EXPENSE_TYPE_LABEL, PAYMENT_METHOD_LABEL, useAddClaimLine, useClaim, useClaimAction, useClaimReceipt,
  useExportClaim, usePayClaim, usePaymentAccounts, useRemoveClaimLine, useReverseClaim, type AllowanceBasis, type Claim,
  type ClaimLine, type ExpenseType, driverLabel,
} from '@/features/fleet/drivers-api';
import { Field, OptionalSelect } from './fleet-ui';
import { num, toOptions } from './fleet-format';
import { ClaimStatusPill } from './drivers-ui';

type DialogKind = 'reject' | 'pay' | 'export' | 'reverse' | null;

/** Driver claim detail (fleet 1.2): lines, receipts, approval, payment via expenses / export to payroll, reversal. */
export function ClaimDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: c, isLoading, isError, refetch } = useClaim(id);
  const has = useAuthStore((s) => s.hasPermission);
  const action = useClaimAction();
  const [dialog, setDialog] = useState<DialogKind>(null);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !c) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Claim not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canEdit = has(PERMISSIONS.fleetDriver.allowance);
  const canApprove = has(PERMISSIONS.fleetDriver.approveAllowance);
  const draft = c.status === 'draft';

  const run = async (a: 'submit' | 'cancel' | 'approve' | 'sync', ok: string) => {
    try {
      await action.mutateAsync({ id: c.id, action: a });
      notify.success(ok);
    } catch (err) {
      notify.error(apiMessage(err, tx('The action failed')));
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/claims')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{c.code}</h1>
            <ClaimStatusPill status={c.status} />
          </div>
          <p className="text-sm text-muted-foreground">
            <Link className="hover:underline" to={`/fleet/drivers/${c.driverId}`}>{driverLabel(c)}</Link> · {date(c.claimDate)}
            {c.vehicleId && <> · <Link className="hover:underline" to={`/fleet/vehicles/${c.vehicleId}`}>{c.vehicleRegistrationPlate ?? c.vehicleCode}</Link></>}
          </p>
        </div>
        <div className="text-right">
          <div className="text-2xl font-semibold tabular-nums">{money(c.totalAmount, c.currencyCode)}</div>
        </div>
      </div>

      <div className="flex flex-wrap gap-2">
        {draft && canEdit && <Button onClick={() => run('submit', tx('Claim submitted'))} disabled={action.isPending}>{tx('Submit for approval')}</Button>}
        {(draft || c.status === 'rejected') && canEdit && <Button variant="outline" onClick={() => run('cancel', tx('Claim cancelled'))} disabled={action.isPending}>{tx('Cancel claim')}</Button>}
        {c.status === 'submitted' && canApprove && (
          <>
            <Button onClick={() => run('approve', tx('Claim approved'))} disabled={action.isPending}>{tx('Approve')}</Button>
            <Button variant="outline" onClick={() => setDialog('reject')}>{tx('Reject')}</Button>
            <Button variant="ghost" onClick={() => run('sync', tx('Claim refreshed'))} disabled={action.isPending}>{tx('Refresh approval')}</Button>
          </>
        )}
        {c.status === 'approved' && canApprove && (
          <>
            <Button onClick={() => setDialog('pay')}>{tx('Pay via expenses')}</Button>
            <Button variant="outline" onClick={() => setDialog('export')}>{tx('Export to payroll')}</Button>
          </>
        )}
        {(c.status === 'paid' || c.status === 'exported') && canApprove && (
          <Button variant="outline" onClick={() => setDialog('reverse')}>{tx('Reverse')}</Button>
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <LinesCard claim={c} editable={draft && canEdit} />
          <ReceiptsCard claim={c} editable={canEdit && c.status !== 'cancelled' && c.status !== 'rejected'} />
        </div>
        <InfoCard claim={c} />
      </div>

      {dialog === 'reject' && <RejectDialog claim={c} onClose={() => setDialog(null)} />}
      {dialog === 'pay' && <PayDialog claim={c} onClose={() => setDialog(null)} />}
      {dialog === 'export' && <ExportDialog claim={c} onClose={() => setDialog(null)} />}
      {dialog === 'reverse' && <ReverseDialog claim={c} onClose={() => setDialog(null)} />}
    </div>
  );
}

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-3 border-b py-1.5 text-sm last:border-0">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right font-medium">{children ?? '—'}</span>
    </div>
  );
}

function InfoCard({ claim: c }: { claim: Claim }) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{tx('Details')}</CardTitle></CardHeader>
      <CardContent>
        <Item label={tx('Description')}>{c.description}</Item>
        <Item label={tx('Route zone')}>{c.routeZone}</Item>
        <Item label={tx('Trip reference')}>{c.tripId}</Item>
        <Item label={tx('Cost centre')}>{c.costCenter ? `${c.costCenter.code ?? ''} ${c.costCenter.name}`.trim() : null}</Item>
        <Item label={tx('Submitted')}>{c.submittedAt ? dateTime(c.submittedAt) : null}</Item>
        <Item label={tx('Decided')}>{c.decidedAt ? dateTime(c.decidedAt) : null}</Item>
        {c.decisionNotes && <Item label={tx('Decision notes')}>{c.decisionNotes}</Item>}
        {c.approvalRequest && <Item label={tx('Approval request')}>{c.approvalRequest.status}</Item>}
        {c.expense && <Item label={tx('Expense')}>{`${c.expense.expenseCode} · ${c.expense.status}`}</Item>}
        {c.payrollInput && <Item label={tx('Payroll input')}>{[c.payrollInput.status, c.payrollInput.runNumber].filter(Boolean).join(' · ')}</Item>}
        {c.settledAt && <Item label={tx('Settled')}>{dateTime(c.settledAt)}</Item>}
        {c.reversedAt && <Item label={tx('Last reversal')}>{`${dateTime(c.reversedAt)} — ${c.reversalReason ?? ''}`}</Item>}
        {c.notes && <p className="mt-3 whitespace-pre-wrap text-sm">{c.notes}</p>}
      </CardContent>
    </Card>
  );
}

function LinesCard({ claim: c, editable }: { claim: Claim; editable: boolean }) {
  const remove = useRemoveClaimLine();
  const add = useAddClaimLine();
  const [lineType, setLineType] = useState<'allowance' | 'expense'>('allowance');
  const [basis, setBasis] = useState<AllowanceBasis>('per_day');
  const [expenseType, setExpenseType] = useState<ExpenseType>('fuel');
  const [quantity, setQuantity] = useState('1');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const invalid = lineType === 'allowance' ? !(Number(quantity) > 0) : !(Number(amount) > 0);

  const addLine = async () => {
    try {
      await add.mutateAsync({ id: c.id, body: lineType === 'allowance'
        ? { lineType, basis, quantity: Number(quantity), description: description.trim() || undefined }
        : { lineType, expenseType, amount: Number(amount), description: description.trim() || undefined } });
      setAmount('');
      setDescription('');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not add the line')));
    }
  };
  const del = async (l: ClaimLine) => {
    try {
      await remove.mutateAsync({ id: c.id, lineId: l.id });
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not remove the line')));
    }
  };

  const columns: Column<ClaimLine>[] = [
    { key: 'what', header: tx('Line'), render: (l) => (l.lineType === 'allowance'
      ? <div><div>{l.basis ? BASIS_LABEL[l.basis] : tx('Allowance')}</div><div className="text-xs text-muted-foreground">{l.ruleCode ?? ''}</div></div>
      : <div>{l.expenseType ? EXPENSE_TYPE_LABEL[l.expenseType] : tx('Expense')}</div>) },
    { key: 'description', header: tx('Description'), render: (l) => l.description ?? '—' },
    { key: 'qty', header: tx('Qty'), className: 'text-right', render: (l) => <span className="tabular-nums">{num(l.quantity, 2)}</span> },
    { key: 'rate', header: tx('Rate'), className: 'text-right', render: (l) => <span className="tabular-nums">{money(l.rate)}</span> },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (l) => <span className="tabular-nums font-medium">{money(l.amount)}</span> },
    { key: 'x', header: '', className: 'w-10', render: (l) => (editable
      ? <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Remove line')} onClick={() => del(l)}><Trash2 className="h-4 w-4" /></Button>
      : null) },
  ];
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{tx('Lines')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <DataTable columns={columns} data={c.lines ?? []} getRowId={(l) => l.id} emptyMessage={tx('No lines yet.')} />
        {editable && (
          <div className="grid grid-cols-1 gap-3 rounded-md border p-3 sm:grid-cols-12">
            <div className="sm:col-span-2">
              <Field label={tx('Kind')}>
                <OptionalSelect value={lineType} onChange={(v) => setLineType((v || 'allowance') as 'allowance' | 'expense')} placeholder={tx('Kind')}
                  options={[{ value: 'allowance', label: tx('Allowance') }, { value: 'expense', label: tx('Expense') }]} />
              </Field>
            </div>
            <div className="sm:col-span-3">
              {lineType === 'allowance' ? (
                <Field label={tx('Basis')}><OptionalSelect value={basis} onChange={(v) => setBasis((v || 'per_day') as AllowanceBasis)} placeholder={tx('Basis')} options={toOptions(BASIS_LABEL)} /></Field>
              ) : (
                <Field label={tx('Type')}><OptionalSelect value={expenseType} onChange={(v) => setExpenseType((v || 'fuel') as ExpenseType)} placeholder={tx('Type')} options={toOptions(EXPENSE_TYPE_LABEL)} /></Field>
              )}
            </div>
            <div className="sm:col-span-2">
              {lineType === 'allowance'
                ? <Field label={tx('Quantity')}><Input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} /></Field>
                : <Field label={tx('Amount')}><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>}
            </div>
            <div className="sm:col-span-4"><Field label={tx('Description')}><Input value={description} onChange={(e) => setDescription(e.target.value)} /></Field></div>
            <div className="flex items-end sm:col-span-1">
              <Button type="button" size="icon" aria-label={tx('Add line')} onClick={addLine} disabled={invalid || add.isPending}><Plus className="h-4 w-4" /></Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function ReceiptsCard({ claim: c, editable }: { claim: Claim; editable: boolean }) {
  const upload = useClaimReceipt();
  const onFiles = async (files: FileList | null) => {
    for (const file of Array.from(files ?? [])) {
      try {
        await upload.mutateAsync({ id: c.id, file, title: file.name });
      } catch (err) {
        notify.error(apiMessage(err, tx('Could not upload the receipt')));
      }
    }
  };
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle className="text-base">{tx('Receipts')}</CardTitle>
        {editable && (
          <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm hover:bg-accent">
            <Paperclip className="h-4 w-4" /> {tx('Add receipt')}
            <input type="file" accept="image/*,application/pdf" capture="environment" multiple className="hidden" onChange={(e) => onFiles(e.target.files)} />
          </label>
        )}
      </CardHeader>
      <CardContent>
        {(c.receipts ?? []).length === 0 ? <p className="text-sm text-muted-foreground">{tx('No receipts attached.')}</p> : (
          <ul className="space-y-1 text-sm">
            {(c.receipts ?? []).map((r) => <li key={r.id}><a className="text-primary hover:underline" href={r.url} target="_blank" rel="noreferrer">{r.title ?? r.filename}</a></li>)}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function RejectDialog({ claim, onClose }: { claim: Claim; onClose: () => void }) {
  const action = useClaimAction();
  const [notes, setNotes] = useState('');
  const submit = async () => {
    try {
      await action.mutateAsync({ id: claim.id, action: 'reject', notes: notes.trim() });
      notify.success(tx('Claim rejected'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not reject the claim')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Reject claim {{code}}', { code: claim.code })}</DialogTitle></DialogHeader>
        <Field label={tx('Reason *')}><Textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!notes.trim() || action.isPending}>{tx('Reject')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PayDialog({ claim, onClose }: { claim: Claim; onClose: () => void }) {
  const accounts = usePaymentAccounts(true);
  const pay = usePayClaim();
  const [method, setMethod] = useState('CASH');
  const [accountId, setAccountId] = useState('');
  const [reference, setReference] = useState('');
  const [notes, setNotes] = useState('');
  const submit = async () => {
    try {
      await pay.mutateAsync({ id: claim.id, body: { paymentMethod: method, accountId, reference: reference.trim() || undefined, notes: notes.trim() || undefined } });
      notify.success(tx('Claim paid'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not pay the claim')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Pay {{amount}} to the driver', { amount: money(claim.totalAmount, claim.currencyCode) })}</DialogTitle>
          <DialogDescription>{tx('Creates a paid expense charged to driver allowances on the vehicle cost centre.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <Field label={tx('Payment method')}><OptionalSelect value={method} onChange={(v) => setMethod(v || 'CASH')} placeholder={tx('Payment method')} options={toOptions(PAYMENT_METHOD_LABEL)} /></Field>
          <Field label={tx('Pay from *')}>
            <OptionalSelect value={accountId} onChange={setAccountId} placeholder={tx('Choose an account')}
              options={(accounts.data ?? []).map((a) => ({ value: a.id, label: `${a.name} (${money(a.currentBalance, a.currency)})` }))} />
          </Field>
          <Field label={tx('Reference')}><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
          <Field label={tx('Notes')}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!accountId || pay.isPending}>{tx('Pay')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ExportDialog({ claim, onClose }: { claim: Claim; onClose: () => void }) {
  const exp = useExportClaim();
  const [earnedOn, setEarnedOn] = useState(claim.claimDate);
  const submit = async () => {
    try {
      await exp.mutateAsync({ id: claim.id, earnedOn });
      notify.success(tx('Claim exported to payroll'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not export the claim')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Export {{amount}} to payroll', { amount: money(claim.totalAmount, claim.currencyCode) })}</DialogTitle>
          <DialogDescription>{tx('The amount is added as an earning to the driver’s next payroll run; the expense is booked on the vehicle cost centre now.')}</DialogDescription>
        </DialogHeader>
        <Field label={tx('Earned on')}><Input type="date" value={earnedOn} onChange={(e) => setEarnedOn(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!earnedOn || exp.isPending}>{tx('Export')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ReverseDialog({ claim, onClose }: { claim: Claim; onClose: () => void }) {
  const reverse = useReverseClaim();
  const [reason, setReason] = useState('');
  const submit = async () => {
    try {
      await reverse.mutateAsync({ id: claim.id, reason: reason.trim() });
      notify.success(tx('Claim reversed'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not reverse the claim')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Reverse claim {{code}}', { code: claim.code })}</DialogTitle>
          <DialogDescription>
            {claim.status === 'paid'
              ? tx('Voids the expense and reverses its payment journal. The claim returns to approved.')
              : tx('Cancels the payroll earning (only before a payroll run includes it) and reverses the accrual. The claim returns to approved.')}
          </DialogDescription>
        </DialogHeader>
        <Field label={tx('Reason *')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!reason.trim() || reverse.isPending}>{tx('Reverse')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
