import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { money, date, statusLabel } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import {
  useCreateSupplierPayment,
  useExpense,
  usePostExpense,
  useVoidExpense,
  useWriteOffBill,
  type PostExpenseOptions,
} from '@/features/invoicing/api';
import { isPostingAccount, isReceivablePayableControl, useAccounts, useCashAccounts } from '@/features/accounting/api';
import { tx } from '@/lib/i18n/tx';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';

const selectClass = 'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm';

function SummaryRow({ label, value, bold }: { label: string; value: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between ${bold ? 'font-semibold' : ''}`}>
      <span className={bold ? '' : 'text-muted-foreground'}>{label}</span>
      <span>{value}</span>
    </div>
  );
}

/**
 * The P&L account a supplier write-off credits (control-account policy: never the AR / AP control accounts, which the
 * server refuses too). Mounted only while the dialog is open for a user who can read the chart of accounts.
 */
function WriteOffAccountSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const { data } = useAccounts();
  const options = (data?.data ?? []).filter(
    (a) =>
      isPostingAccount(a) &&
      !isReceivablePayableControl(a) &&
      (a.category?.classification === 'revenue' || a.category?.classification === 'expense'),
  );
  return (
    <div className="space-y-2">
      <Label htmlFor="write-off-account">{tx('Account')}</Label>
      <select id="write-off-account" className={selectClass} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{tx('Purchase discounts received (default)')}</option>
        {options.map((a) => (
          <option key={a.id} value={a.id}>
            {a.code} — {a.name}
          </option>
        ))}
      </select>
    </div>
  );
}

/** What stopped a post: the server's message plus the override that gets past it. */
interface PostHold {
  message: string;
  override: keyof PostExpenseOptions;
  permission: string;
}

function holdFrom(message: string): PostHold | null {
  if (message.startsWith('Three-way match failed')) {
    return { message, override: 'override3WM', permission: 'three_way_match:override' };
  }
  if (message.startsWith('This bill is awaiting approval')) {
    return { message, override: 'overrideApproval', permission: 'approvals:decide' };
  }
  return null;
}

export function VendorBillDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: bill, isLoading, isError } = useExpense(id);
  const postBill = usePostExpense();
  const voidBill = useVoidExpense();
  const paySupplier = useCreateSupplierPayment();
  const writeOffBill = useWriteOffBill();
  const { data: cashAccounts = [] } = useCashAccounts();
  const has = useAuthStore((s) => s.hasPermission);
  const canWriteOffPerm = has(PERMISSIONS.expense.writeOff);

  const [payOpen, setPayOpen] = useState(false);
  const [voidOpen, setVoidOpen] = useState(false);
  const [voidReason, setVoidReason] = useState('');
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState('bank');
  const [accountId, setAccountId] = useState('');
  const [hold, setHold] = useState<PostHold | null>(null);
  const [overrides, setOverrides] = useState<PostExpenseOptions>({});
  const [writeOffOpen, setWriteOffOpen] = useState(false);
  const [writeOffAmount, setWriteOffAmount] = useState('');
  const [writeOffAccountId, setWriteOffAccountId] = useState('');
  const [writeOffReason, setWriteOffReason] = useState('');

  if (isLoading) {
    return <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>;
  }
  if (isError || !bill) {
    return <div className="text-sm text-destructive">{tx('Could not load this bill.')}</div>;
  }

  const isDraft = bill.status === 'draft';
  const residual = Number(bill.amountResidual);
  const canPay = bill.status === 'posted' && residual > 0;
  const canVoid = bill.status === 'posted' && Number(bill.amountPaid) <= 0;
  const writtenOff = Number(bill.writtenOff ?? 0);

  const submitPost = async (options: PostExpenseOptions) => {
    try {
      await postBill.mutateAsync({ id: bill.id, options });
      setHold(null);
      setOverrides({});
      notify.success(tx('Bill posted'));
    } catch (error) {
      const message = apiMessage(error, tx('Could not post the bill'));
      const next = holdFrom(message);
      setHold(next);
      if (!next) notify.error(message);
    }
  };

  const overrideHold = () => {
    if (!hold) return;
    const next = { ...overrides, [hold.override]: true };
    setOverrides(next);
    void submitPost(next);
  };

  const openPay = () => {
    setAmount(bill.amountResidual);
    setMethod('bank');
    setAccountId('');
    setPayOpen(true);
  };

  const submitPayment = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await paySupplier.mutateAsync({
        partnerId: bill.partnerId,
        paymentDate: new Date().toISOString().slice(0, 10),
        amount: Number(amount),
        paymentMethod: method,
        accountId: accountId || undefined,
        allocations: [{ documentId: bill.id, amount: Number(amount) }],
      });
      setPayOpen(false);
      notify.success(tx('Payment recorded successfully'));
    } catch (error) {
      notify.error(apiMessage(error, tx('Failed to record payment')));
    }
  };

  const openWriteOff = () => {
    setWriteOffAmount(bill.amountResidual);
    setWriteOffAccountId('');
    setWriteOffReason('');
    setWriteOffOpen(true);
  };

  const submitWriteOff = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await writeOffBill.mutateAsync({
        id: bill.id,
        body: {
          amount: writeOffAmount ? Number(writeOffAmount) : undefined,
          accountId: writeOffAccountId || undefined,
          reason: writeOffReason.trim(),
        },
      });
      setWriteOffOpen(false);
      notify.success(tx('Balance written off'));
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not write off the balance')));
    }
  };

  const confirmVoid = async () => {
    try {
      await voidBill.mutateAsync({ id: bill.id, reason: voidReason.trim() });
      setVoidOpen(false);
      setVoidReason('');
      notify.success(tx('Bill voided'));
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not void the bill')));
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold">{bill.documentNumber}</h1>
            <Badge variant={bill.status === 'cancelled' ? 'destructive' : 'default'}>{statusLabel(bill.status)}</Badge>
            <Badge variant="outline">{statusLabel(bill.paymentStatus)}</Badge>
          </div>
          <p className="text-sm text-muted-foreground">
            <Link to={`/suppliers/${bill.partnerId}/ledger`} className="hover:underline">{bill.partner?.name}</Link>
            {' · '}{date(bill.issueDate)}
            {bill.dueDate ? tx(' · due {{v1}}', { v1: date(bill.dueDate) }) : ''}
            {bill.reference ? ` · ${bill.reference}` : ''}
          </p>
        </div>
        <div className="flex flex-wrap gap-2 print:hidden">
          <Button variant="outline" onClick={() => navigate('/vendor-bills')}>
            {tx('Back')}
          </Button>
          <Button variant="outline" onClick={() => window.print()}>
            {tx('Print')}
          </Button>
          {isDraft && has(PERMISSIONS.expense.update) && (
            <Button variant="outline" asChild>
              <Link to={`/vendor-bills/${bill.id}/edit`}>{tx('Edit')}</Link>
            </Button>
          )}
          {isDraft && has(PERMISSIONS.expense.post) && (
            <Button onClick={() => void submitPost(overrides)} disabled={postBill.isPending}>
              {tx('Post')}
            </Button>
          )}
          {canPay && has(PERMISSIONS.payment.create) && <Button onClick={openPay}>{tx('Pay supplier')}</Button>}
          {canPay && canWriteOffPerm && (
            <Button variant="outline" onClick={openWriteOff} disabled={writeOffBill.isPending}>
              {tx('Write off')}
            </Button>
          )}
          {canVoid && has(PERMISSIONS.expense.cancel) && (
            <Button variant="outline" onClick={() => setVoidOpen(true)} disabled={voidBill.isPending}>
              {tx('Void')}
            </Button>
          )}
        </div>
      </div>

      {hold && (
        <Card className="border-amber-300 bg-amber-50 dark:bg-amber-500/10">
          <CardContent className="flex flex-wrap items-center justify-between gap-3 pt-6 text-sm">
            <span>{hold.message}</span>
            {has(hold.permission) && (
              <Button variant="outline" onClick={overrideHold} disabled={postBill.isPending}>
                {tx('Post with override')}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardContent className="overflow-x-auto pt-6">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Description')}</TableHead>
                <TableHead className="text-right">{tx('Qty')}</TableHead>
                <TableHead className="text-right">{tx('Unit price')}</TableHead>
                <TableHead className="text-right">{tx('Net')}</TableHead>
                <TableHead className="text-right">{tx('Tax')}</TableHead>
                <TableHead className="text-right">{tx('Total')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {bill.lines.map((l) => (
                <TableRow key={l.id}>
                  <TableCell>{l.description}</TableCell>
                  <TableCell className="text-right">{money(l.quantity)}</TableCell>
                  <TableCell className="text-right">{money(l.unitPrice)}</TableCell>
                  <TableCell className="text-right">{money(l.subtotal)}</TableCell>
                  <TableCell className="text-right">{money(l.taxAmount)}</TableCell>
                  <TableCell className="text-right">{money(l.total)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>

          <div className="mt-4 flex justify-end">
            <div className="w-64 space-y-1 text-sm">
              <SummaryRow label={tx('Subtotal')} value={money(bill.subtotal)} />
              <SummaryRow label={tx('Tax')} value={money(bill.taxAmount)} />
              <SummaryRow label={tx('Total')} value={money(bill.totalAmount)} bold />
              {Number((bill as { withholdingAmount?: string | number }).withholdingAmount ?? 0) !== 0 && (
                <SummaryRow
                  label={tx('Withholding tax')}
                  value={`− ${money((bill as { withholdingAmount?: string | number }).withholdingAmount ?? 0)}`}
                />
              )}
              <SummaryRow label={tx('Paid')} value={money(Number(bill.amountPaid) - writtenOff)} />
              {writtenOff > 0 && <SummaryRow label={tx('Written off')} value={money(writtenOff)} />}
              <SummaryRow label={tx('Balance due')} value={money(bill.amountResidual)} bold />
            </div>
          </div>
        </CardContent>
      </Card>

      {(bill.allocations?.length ?? 0) > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('Payments')}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            {bill.allocations?.map((a) => (
              <div key={a.paymentId} className="flex justify-between">
                <Link to={`/payments/${a.paymentId}`} className="text-primary hover:underline">
                  {a.payment?.paymentNumber ?? tx('Payment')}
                </Link>
                <span>{money(a.amount)}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <AttachmentPanel resType="vendor_bill" resId={bill.id} title={tx('Supplier documents')} />

      <Dialog open={payOpen} onOpenChange={setPayOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Pay supplier')}</DialogTitle>
            <DialogDescription>{tx('Record a payment for')} {bill.documentNumber}.</DialogDescription>
          </DialogHeader>
          <form onSubmit={submitPayment} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="amount">{tx('Amount')}</Label>
              <Input
                id="amount"
                type="number"
                step="any"
                min="0.01"
                max={bill.amountResidual}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="method">{tx('Method')}</Label>
              <select id="method" className={selectClass} value={method} onChange={(e) => setMethod(e.target.value)}>
                <option value="cash">{tx('Cash')}</option>
                <option value="bank">{tx('Bank')}</option>
                <option value="mobile_money">{tx('Mobile Money')}</option>
                <option value="cheque">{tx('Cheque')}</option>
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="account">{tx('Account')}</Label>
              <select
                id="account"
                className={selectClass}
                value={accountId}
                onChange={(e) => setAccountId(e.target.value)}
              >
                <option value="">{tx('Default account for this method')}</option>
                {cashAccounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </select>
            </div>
            <DialogFooter>
              <Button type="submit" disabled={paySupplier.isPending}>
                {paySupplier.isPending ? tx('Saving...') : tx('Save payment')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={writeOffOpen} onOpenChange={setWriteOffOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Write off')} {bill.documentNumber}</DialogTitle>
            <DialogDescription>
              {tx('Clear what is no longer owed to the supplier: debits the supplier payable and credits the chosen income or expense account. A written-off bill cannot be voided.')}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={submitWriteOff} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="write-off-amount">{tx('Amount')}</Label>
              <Input
                id="write-off-amount"
                type="number"
                step="any"
                min="0.01"
                max={bill.amountResidual}
                value={writeOffAmount}
                onChange={(e) => setWriteOffAmount(e.target.value)}
                required
              />
            </div>
            {/* Without account read access the write-off goes to the purchase discounts mapping. */}
            {writeOffOpen && has(PERMISSIONS.account.read) && (
              <WriteOffAccountSelect value={writeOffAccountId} onChange={setWriteOffAccountId} />
            )}
            <div className="space-y-2">
              <Label htmlFor="write-off-reason">{tx('Reason')}</Label>
              <Textarea
                id="write-off-reason"
                value={writeOffReason}
                maxLength={2000}
                onChange={(e) => setWriteOffReason(e.target.value)}
                placeholder={tx('Why is this balance being written off?')}
                required
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setWriteOffOpen(false)}>
                {tx('Cancel')}
              </Button>
              <Button type="submit" disabled={writeOffBill.isPending || !writeOffReason.trim()}>
                {writeOffBill.isPending ? tx('Saving...') : tx('Write off')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={voidOpen} onOpenChange={setVoidOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Void')} {bill.documentNumber}</DialogTitle>
            <DialogDescription>
              {tx('Void this bill? This will reverse the journal entry and cannot be undone.')}{' '}
              {tx('A bill with payments, write-offs, debit notes or settled claims applied cannot be voided until those are reversed.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="bill-void-reason">{tx('Reason')}</Label>
            <Textarea
              id="bill-void-reason"
              value={voidReason}
              maxLength={2000}
              onChange={(e) => setVoidReason(e.target.value)}
              placeholder={tx('Why is this bill being voided?')}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setVoidOpen(false)}>{tx('Cancel')}</Button>
            <Button variant="destructive" onClick={() => void confirmVoid()} disabled={voidBill.isPending || !voidReason.trim()}>
              {tx('Void')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
