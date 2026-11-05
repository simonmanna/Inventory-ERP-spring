import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, BookOpen, ChevronDown, FileDown, Mail, Wallet, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { money, date, statusLabel } from '@/lib/format';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import { useCancelInvoice, useCreatePayment, useInvoice, usePostInvoice } from '@/features/invoicing/api';
import { useJournalEntry } from '@/features/accounting/api';
import { usePartner } from '@/features/partners/api';
import { useOpenSession, useReceivePayment } from '@/pages/pos/api';
import { Skeleton } from '@/components/ui/skeleton';
import { tx } from '@/lib/i18n/tx';
import { useUomCode } from '@/features/uom/api';
import { openPdf, useEmailInvoice } from '@/features/finance/api';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { Field } from '@/pages/crm/crm-ui';
import { SmartButton, StatusRibbon } from '@/pages/sales/sales-ui';

/** Display labels for the back-office invoice payment method (InvoicePaymentMode). */
const PAYMENT_MODE_LABELS: Record<string, string> = {
  cash: tx('Cash'),
  card: tx('Card'),
  mobile_money: tx('Mobile Money'),
  mixed: tx('Mixed'),
  credit: tx('Credit (house account)'),
};

const selectClass = 'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm';

function Row({ label, value }: { label: string; value: ReactNode }) {
  return <div className="flex items-center justify-between py-0.5"><span className="text-muted-foreground">{label}</span><span className="tabular-nums">{value}</span></div>;
}

function TabSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="mb-3 border-b pb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h3>
      <div className="grid gap-3 sm:grid-cols-2 lg:max-w-3xl">{children}</div>
    </div>
  );
}

/** Neutral status pill in the sales-order style. */
function InvoiceStatusPill({ status, paid }: { status: string; paid: boolean }) {
  const label = status === 'cancelled' ? tx('Cancelled') : status === 'draft' ? tx('Draft') : paid ? tx('Paid') : tx('Posted');
  return <span className="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground">{label}</span>;
}

/** Render a tax rate stored as fraction (0.15) or percent (15). */
function rateLabel(rate: string | number | undefined | null): string {
  const n = Number(rate ?? 0);
  if (!n) return '';
  return `${n <= 1 ? Math.round(n * 1000) / 10 : n}%`;
}

export function InvoiceDetailPage() {
  const uomCode = useUomCode();
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: inv, isLoading } = useInvoice(id);
  const { data: partner } = usePartner(inv?.partnerId);
  const postInvoice = usePostInvoice();
  const cancelInvoice = useCancelInvoice();
  const createPayment = useCreatePayment();
  /* POS sales settle through the POS billing path, not the generic payment
   * endpoint — see submitPayment. */
  const receivePosPayment = useReceivePayment();
  const { data: openSession } = useOpenSession();
  const has = useAuthStore((s) => s.hasPermission);
  const currency = useAuthStore((s) => s.organization?.currencyCode ?? 'IDR');

  const [activeTab, setActiveTab] = useState<'lines' | 'journal' | 'info' | 'payments' | 'attachments'>('lines');
  const [payOpen, setPayOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [emailOpen, setEmailOpen] = useState(false);
  const [emailTo, setEmailTo] = useState('');
  const [emailMessage, setEmailMessage] = useState('');
  const emailInvoice = useEmailInvoice();
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState('cash');
  const [reference, setReference] = useState('');
  const [payError, setPayError] = useState<string | null>(null);

  const entry = useJournalEntry(inv?.journalEntryId ?? undefined);

  const receiptRows = (inv?.allocations ?? [])
    .filter(a => a.payment?.direction === 'inbound')
    .sort((a, b) =>
      new Date(b.payment!.paymentDate).getTime() -
      new Date(a.payment!.paymentDate).getTime()
    )
    .map(a => ({
      id: a.paymentId,
      paymentDate: a.payment!.paymentDate,
      paymentMethod: a.payment!.paymentMethod,
      reference: a.payment!.reference ?? null,
      amount: a.amount,
      paymentNumber: (a.payment! as any).paymentNumber,
    }));

  if (isLoading) return <Skeleton className="h-96 w-full" />;

  if (!inv) {
    return (
      <div className="space-y-4">
        <div className="text-sm">
          <Link to="/invoices" className="font-medium text-primary hover:underline">{tx('Invoices')}</Link>
        </div>
        <div className="rounded-xl border bg-card p-8 text-center shadow-sm">
          <h1 className="text-xl font-semibold">{tx('Not Found')}</h1>
          <p className="mt-2 text-sm text-muted-foreground">{tx('Invoice not found or you don\'t have permission to view it.')}</p>
          <Button variant="outline" className="mt-4" onClick={() => navigate('/invoices')}>
            <ArrowLeft className="mr-2 h-4 w-4" /> {tx('Back to Invoices')}
          </Button>
        </div>
      </div>
    );
  }

  const isDraft = inv.status === 'draft';
  const residual = Number(inv.amountResidual);
  const canPay = inv.status === 'posted' && residual > 0;
  /** POS sale (Invoice table) rather than a back-office Document. */
  const isPosInvoice = (inv as any).source === 'pos';
  /** Sold on account and still owed — the receivable the cashier chases. */
  const isOpenCredit = (inv as any).paymentMode === 'credit' && residual > 0;
  const canModify = inv.status !== 'cancelled';
  const paid = residual <= 0.005;
  const ribbon = [tx('Draft'), tx('Posted'), tx('Paid')];
  const step = isDraft ? 0 : paid ? 2 : 1;

  // Odoo-style per-tax breakdown (aggregate line taxAmount by tax name+rate).
  const taxGroups = new Map<string, { label: string; amount: number }>();
  for (const l of inv.lines) {
    if ((l.lineType ?? 'product') === 'product' && l.tax && Number(l.taxAmount) > 0) {
      const key = `${l.tax.id}`;
      const g = taxGroups.get(key) ?? { label: `${l.tax.name} ${rateLabel(l.tax.rate)}`.trim(), amount: 0 };
      g.amount += Number(l.taxAmount);
      taxGroups.set(key, g);
    }
  }

  const openPay = () => {
    setAmount(inv.amountResidual);
    setMethod('cash');
    setReference('');
    setPayError(null);
    setPayOpen(true);
  };

  const submitPayment = async (e: FormEvent) => {
    e.preventDefault();
    const amt = Number(amount);
    if (!Number.isFinite(amt) || amt <= 0) { setPayError(tx('Enter an amount greater than zero.')); return; }
    if (amt > residual + 0.01) { setPayError(`Amount exceeds the ${money(residual, currency)} still due.`); return; }
    setPayError(null);
    try {
      if (isPosInvoice) {
        /* A POS sale lives in the Invoice table, not Document — the generic
         * payment endpoint allocates by documentId and would not find it. The
         * POS path also advances settlementStatus, issues the partial/settlement
         * receipt and closes the order, which the generic one never does. */
        await receivePosPayment.mutateAsync({
          invoiceId: inv.id,
          tenders: [{ method: method as 'cash' | 'bank' | 'card' | 'mobile_money', amount: amt, reference: reference.trim() || undefined }],
          allowPartial: amt < residual - 0.01,
          cashSessionId: method === 'cash' ? openSession?.id : undefined,
        });
      } else {
        await createPayment.mutateAsync({
          partnerId: inv.partnerId,
          paymentDate: new Date().toISOString().slice(0, 10),
          amount: amt,
          paymentMethod: method,
          allocations: [{ documentId: inv.id, amount: amt }],
        });
      }
      setPayOpen(false);
    } catch (err: any) {
      setPayError(err?.response?.data?.message || err?.message || tx('Payment failed'));
    }
  };

  const primaryAddress = partner?.addresses?.find(a => a.isPrimary) ?? partner?.addresses?.[0];
  const addressLines = primaryAddress
    ? [
        primaryAddress.line1,
        primaryAddress.line2,
        [primaryAddress.city, primaryAddress.state].filter(Boolean).join(', '),
        primaryAddress.postalCode,
        primaryAddress.country,
      ].filter(Boolean)
    : [];

  const journalTotalDebit = entry?.data?.lines?.reduce((s, l) => s + Number(l.debit || 0), 0) ?? 0;
  const journalTotalCredit = entry?.data?.lines?.reduce((s, l) => s + Number(l.credit || 0), 0) ?? 0;
  const balanced = Math.abs(journalTotalDebit - journalTotalCredit) < 0.01;
  const th = 'py-2 pr-2 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground';
  const canEmail = !isDraft && inv.status !== 'cancelled' && has(PERMISSIONS.invoice.send);
  const canCancel = canModify && has(PERMISSIONS.invoice.cancel);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to="/invoices" className="font-medium text-primary hover:underline">{tx('Invoices')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{inv.documentNumber}</span>
          <span className="ml-3"><InvoiceStatusPill status={inv.status} paid={paid} /></span>
        </div>
        {inv.status !== 'cancelled' && <div className="w-full max-w-md"><StatusRibbon steps={ribbon} current={step} /></div>}
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {isDraft && has(PERMISSIONS.invoice.post) && (
            <Button onClick={() => postInvoice.mutate(inv.id)} disabled={postInvoice.isPending}>
              {postInvoice.isPending ? tx('Posting...') : tx('Confirm')}
            </Button>
          )}
          {canPay && has(PERMISSIONS.payment.create) && <Button onClick={openPay}>{tx('Register Payment')}</Button>}
          <Button variant="outline" onClick={() => openPdf(`/invoices/${inv.id}/pdf`)}><FileDown className="mr-1.5 h-4 w-4" />{tx('PDF')}</Button>
          {(canEmail || canCancel) && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline">{tx('Actions')}<ChevronDown className="ml-1.5 h-4 w-4" /></Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="min-w-[200px]">
                {canEmail && <DropdownMenuItem onSelect={() => setEmailOpen(true)}><Mail className="mr-2 h-4 w-4" />{tx('Email')}</DropdownMenuItem>}
                {canEmail && canCancel && <DropdownMenuSeparator />}
                {canCancel && <DropdownMenuItem onSelect={() => setCancelOpen(true)} className="text-destructive focus:text-destructive"><XCircle className="mr-2 h-4 w-4" />{tx('Cancel Invoice')}</DropdownMenuItem>}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          <SmartButton icon={<Wallet className="h-4 w-4" />} count={receiptRows.length} label={tx('Payments')} disabled={!receiptRows.length} onClick={() => setActiveTab('payments')} />
          <SmartButton icon={<BookOpen className="h-4 w-4" />} count={inv.journalEntryId ? 1 : 0} label={tx('Journal Items')} disabled={!inv.journalEntryId} onClick={() => setActiveTab('journal')} />
        </div>
      </div>

      {isOpenCredit && (
        <div className="rounded-xl border p-3 text-sm">
          <span className="font-semibold">{tx('On account —')}</span> {money(residual, currency)} {tx('due')}
        </div>
      )}

      <section className="rounded-xl border bg-card shadow-sm">
        <div className="px-5 pt-5">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{tx('Customer invoice')}</p>
          <h1 className="text-2xl font-bold">{inv.documentNumber}</h1>
          {inv.invoicingJournalName && <p className="text-xs text-muted-foreground">{inv.invoicingJournalName}</p>}
        </div>
        <div className="grid gap-x-10 gap-y-3 p-5 md:grid-cols-2">
          <div className="space-y-3">
            <Field label={tx('Customer')}>
              <div className="py-2 text-sm">
                <p className="font-medium">{inv.partner?.name ?? '—'}</p>
                {partner?.taxNumber && <p className="text-xs text-muted-foreground">{tx('TIN:')} {partner.taxNumber}</p>}
                {addressLines.length > 0 && (
                  <div className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {addressLines.map((l, i) => <p key={i}>{l}</p>)}
                  </div>
                )}
              </div>
            </Field>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={tx('Invoice Date')}><p className="py-2 text-sm">{date(inv.issueDate)}</p></Field>
            <Field label={tx('Due Date')}><p className="py-2 text-sm">{inv.dueDate ? date(inv.dueDate) : '-'}</p></Field>
            <Field label={tx('Payment Terms')}><p className="py-2 text-sm">{inv.paymentTermName ?? '-'}</p></Field>
            <Field label={tx('Currency')}><p className="py-2 text-sm">{currency}</p></Field>
            <Field label={tx('Source Doc')}><p className="py-2 text-sm">{inv.sourceDocument ?? inv.reference ?? '-'}</p></Field>
            <Field label={tx('Salesperson')}><p className="py-2 text-sm">{inv.salespersonName ?? '-'}</p></Field>
          </div>
        </div>

        <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as typeof activeTab)}>
          <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0 px-5">
            {([
              ['lines', tx('Invoice Lines')], ['journal', tx('Journal Items')], ['info', tx('Other Info')],
              ['payments', `${tx('Payments')}${receiptRows.length ? ` (${receiptRows.length})` : ''}`], ['attachments', tx('Attachments')],
            ] as const).map(([v, label]) => (
              <TabsTrigger key={v} value={v} className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{label}</TabsTrigger>
            ))}
          </TabsList>

          <TabsContent value="lines" className="mt-0 space-y-4 p-5">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[860px] text-sm">
                <thead className="border-b">
                  <tr>
                    <th className={th}>{tx('Product')}</th>
                    <th className={th}>{tx('Account')}</th>
                    <th className={cn(th, 'w-24 text-right')}>{tx('Qty')}</th>
                    <th className={cn(th, 'w-28 text-right')}>{tx('Unit Price')}</th>
                    <th className={cn(th, 'w-32 text-right')}>{tx('Tax')}</th>
                    <th className={cn(th, 'w-24 text-right')}>{tx('Discount')}</th>
                    <th className={cn(th, 'w-32 text-right')}>{tx('Amount')}</th>
                  </tr>
                </thead>
                <tbody>
                  {inv.lines.length === 0 ? (
                    <tr><td colSpan={7} className="py-10 text-center text-muted-foreground">{tx('No line items found.')}</td></tr>
                  ) : inv.lines.map((l) => {
                    const isSection = (l.lineType ?? 'product') === 'section';
                    const isNote = (l.lineType ?? 'product') === 'note';
                    if (isSection || isNote) {
                      return (
                        <tr key={l.id} className={cn('border-b', isSection && 'bg-muted/40')}>
                          <td colSpan={7} className={isSection ? 'py-1.5 pr-2 font-semibold' : 'py-1.5 pr-2 italic text-muted-foreground'}>
                            {l.description || (isSection ? tx('Section') : tx('Note'))}
                          </td>
                        </tr>
                      );
                    }
                    const discountLabel = l.discountType === 'fixed_amount'
                      ? `-${money(l.discountAmount)}`
                      : l.discountPercent && Number(l.discountPercent) > 0
                        ? `${Number(l.discountPercent).toFixed(1)}%`
                        : '-';
                    return (
                      <tr key={l.id} className="border-b align-top">
                        <td className="py-2 pr-2 font-medium">
                          {l.description}
                          {l.discountReason ? <span className="block text-[11px] font-normal text-muted-foreground">({l.discountReason})</span> : null}
                        </td>
                        <td className="py-2 pr-2 text-muted-foreground">
                          {l.account ? <span><span className="font-mono text-xs">{l.account.code}</span> {l.account.name}</span> : '-'}
                        </td>
                        <td className="py-2 pr-2 text-right tabular-nums">{money(l.quantity)} <span className="text-xs text-muted-foreground">{uomCode(l.uomId)}</span></td>
                        <td className="py-2 pr-2 text-right tabular-nums">{money(l.unitPrice)}</td>
                        <td className="py-2 pr-2 text-right">{l.tax && Number(l.taxAmount) > 0 ? `${l.tax.name} ${rateLabel(l.tax.rate)}`.trim() : '-'}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{discountLabel}</td>
                        <td className="py-2 pr-2 text-right font-medium tabular-nums">{money(l.total)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="grid gap-4 border-t pt-4 md:grid-cols-[minmax(0,1fr)_320px]">
              <div>
                {inv.notes ? (
                  <Field label={tx('Terms & Conditions')}><p className="whitespace-pre-wrap py-2 text-sm text-muted-foreground">{inv.notes}</p></Field>
                ) : null}
              </div>
              <div className="text-sm">
                <h2 className="mb-2 font-semibold">{tx('Totals')}</h2>
                <Row label={tx('Untaxed Amount')} value={money(inv.subtotal)} />
                {taxGroups.size > 0
                  ? [...taxGroups.values()].map(g => <Row key={g.label} label={g.label} value={money(g.amount)} />)
                  : inv.taxAmount && Number(inv.taxAmount) > 0 ? <Row label={tx('Taxes')} value={money(inv.taxAmount)} /> : null}
                {inv.discountTotal && Number(inv.discountTotal) > 0 && (
                  <Row
                    label={tx('Discount {{v1}}', { v1: inv.discountType === 'fixed_amount' ? '(fixed)' : inv.discountType === 'percentage' ? `(${inv.discountValue ? Number(inv.discountValue).toFixed(1) : 'pct'}%)` : '' })}
                    value={`-${money(inv.discountTotal)}`}
                  />
                )}
                <div className="mt-2 border-t pt-2"><Row label={tx('Total')} value={<strong className="text-lg">{money(inv.totalAmount)}</strong>} /></div>
                <Row label={tx('Total Paid')} value={money(inv.amountPaid)} />
                <Row label={tx('Amount Due')} value={<strong>{money(inv.amountResidual)}</strong>} />
              </div>
            </div>
          </TabsContent>

          <TabsContent value="journal" className="mt-0 p-5">
            {!inv.journalEntryId ? (
              <p className="py-6 text-center text-sm text-muted-foreground">{tx('No journal entry yet — the invoice posts to the ledger when confirmed. Journal items appear here (Account · Label · Debit · Credit) once the invoice is confirmed.')}</p>
            ) : entry?.isLoading ? (
              <Skeleton className="mx-auto h-6 w-56" />
            ) : entry?.data?.lines?.length ? (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[640px] text-sm">
                  <thead className="border-b">
                    <tr>
                      <th className={cn(th, 'w-10')}>#</th>
                      <th className={th}>{tx('Account')}</th>
                      <th className={th}>{tx('Label')}</th>
                      <th className={cn(th, 'w-36 text-right')}>{tx('Debit')}</th>
                      <th className={cn(th, 'w-36 text-right')}>{tx('Credit')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entry.data.lines.map((l, i) => (
                      <tr key={l.id} className="border-b">
                        <td className="py-2 pr-2 font-mono text-xs text-muted-foreground">{i + 1}</td>
                        <td className="py-2 pr-2"><span className="font-mono text-xs text-muted-foreground">{l.account.code}</span> <span className="font-medium">{l.account.name}</span></td>
                        <td className="py-2 pr-2 text-muted-foreground">{l.description ?? '-'}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{Number(l.debit) ? money(l.debit) : ''}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{Number(l.credit) ? money(l.credit) : ''}</td>
                      </tr>
                    ))}
                    <tr>
                      <td colSpan={3} className="py-2 pr-2 text-right text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tx('Totals')} {balanced ? tx('· Balanced') : tx('· NOT Balanced')}</td>
                      <td className="py-2 pr-2 text-right font-semibold tabular-nums">{money(journalTotalDebit)}</td>
                      <td className="py-2 pr-2 text-right font-semibold tabular-nums">{money(journalTotalCredit)}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="py-6 text-center text-sm text-muted-foreground">{tx('Journal entry has no lines.')}</p>
            )}
          </TabsContent>

          <TabsContent value="info" className="mt-0 space-y-4 p-5">
            <TabSection title={tx('Invoicing')}>
              <Field label={tx('Payment Terms')}><p className="py-2 text-sm">{inv.paymentTermName ?? '-'}</p></Field>
              <Field label={tx('Fiscal Position')}><p className="py-2 text-sm">{inv.fiscalPositionName ?? '-'}</p></Field>
              <Field label={tx('Invoicing Journal')}><p className="py-2 text-sm">{inv.invoicingJournalName ?? '-'}</p></Field>
              <Field label={tx('Payment Method')}><p className="py-2 text-sm">{inv.paymentMode ? PAYMENT_MODE_LABELS[inv.paymentMode] ?? inv.paymentMode : '-'}</p></Field>
              <Field label={tx('Payment Status')}><p className="py-2 text-sm">{statusLabel(inv.paymentStatus)}</p></Field>
              <Field label={tx('Reference')}><p className="py-2 text-sm">{inv.reference ?? '-'}</p></Field>
              <Field label={tx('Source Document')}><p className="py-2 text-sm">{inv.sourceDocument ?? '-'}</p></Field>
              <Field label={tx('Salesperson')}><p className="py-2 text-sm">{inv.salespersonName ?? '-'}</p></Field>
            </TabSection>
            <TabSection title={tx('Delivery')}>
              <Field label={tx('Delivery Date')}><p className="py-2 text-sm">{inv.deliveryDate ? date(inv.deliveryDate) : '-'}</p></Field>
              <Field label={tx('Incoterm')}><p className="py-2 text-sm">{inv.incoterm ? `${inv.incoterm}${inv.incotermLocation ? ' · ' + inv.incotermLocation : ''}` : '-'}</p></Field>
              <Field label={tx('Delivery Address')} className="sm:col-span-2"><p className="py-2 text-sm">{inv.deliveryAddress ?? '-'}</p></Field>
            </TabSection>
          </TabsContent>

          <TabsContent value="payments" className="mt-0 space-y-4 p-5">
            {receiptRows.length === 0 ? (
              <p className="text-sm text-muted-foreground">{tx('No payments recorded for this invoice.')}</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[560px] text-sm">
                  <thead className="border-b">
                    <tr>
                      <th className={cn(th, 'w-16')}>{tx('Sr.')}</th>
                      <th className={th}>{tx('Date')}</th>
                      <th className={th}>{tx('Method')}</th>
                      <th className={th}>{tx('Ref.')}</th>
                      <th className={cn(th, 'w-36 text-right')}>{tx('Amount')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {receiptRows.map((p, i) => (
                      <tr key={p.id} className="border-b">
                        <td className="py-2 pr-2 font-mono text-xs text-muted-foreground">{i + 1}</td>
                        <td className="py-2 pr-2">{date(p.paymentDate || '')}</td>
                        <td className="py-2 pr-2 capitalize">{p.paymentMethod?.replace(/_/g, ' ') ?? p.paymentMethod}</td>
                        <td className="py-2 pr-2 font-mono text-xs">{p.paymentNumber ?? p.reference ?? '-'}</td>
                        <td className="py-2 pr-2 text-right font-medium tabular-nums">{money(p.amount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="ml-auto w-full max-w-[320px] border-t pt-3 text-sm">
              <Row label={tx('Total Paid')} value={money(inv.amountPaid)} />
              <Row label={tx('Amount Due')} value={<strong>{money(inv.amountResidual)}</strong>} />
            </div>
          </TabsContent>

          <TabsContent value="attachments" className="mt-0 p-5">
            <AttachmentPanel resType="invoice" resId={inv.id} bare />
          </TabsContent>
        </Tabs>
      </section>

      {/* Payment Dialog */}
      {canModify && (
        <Dialog open={payOpen} onOpenChange={setPayOpen}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{tx('Register Payment')}</DialogTitle>
              <DialogDescription>
                {tx('Record a payment against')} {inv.documentNumber}. {money(residual, currency)} {tx('is still due — pay it in full or part of it now.')}
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={submitPayment} className="space-y-4 pt-2">
              <div className="space-y-2">
                <Label htmlFor="amount">{tx('Amount')}</Label>
                <Input id="amount" type="number" step="any" value={amount} onChange={(e) => setAmount(e.target.value)} required />
                <div className="flex items-center gap-2 text-xs">
                  <button type="button" className="rounded border px-2 py-0.5 font-medium hover:bg-muted" onClick={() => setAmount(inv.amountResidual)}>
                    {tx('Pay full')} {money(residual, currency)}
                  </button>
                  {Number(amount) > 0 && Number(amount) < residual - 0.01 ? (
                    <span className="text-muted-foreground">{money(residual - Number(amount), currency)} {tx('will stay outstanding')}</span>
                  ) : null}
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="method">{tx('Method')}</Label>
                <select id="method" className={selectClass} value={method} onChange={(e) => setMethod(e.target.value)}>
                  <option value="cash">{tx('Cash')}</option>
                  <option value="bank">{tx('Bank')}</option>
                  <option value="mobile_money">{tx('Mobile Money')}</option>
                  <option value="card">{tx('Card')}</option>
                </select>
              </div>
              {isPosInvoice && method !== 'cash' ? (
                <div className="space-y-2">
                  <Label htmlFor="pay-reference">{tx('Reference (optional)')}</Label>
                  <Input id="pay-reference" value={reference} onChange={(e) => setReference(e.target.value)} placeholder={tx('e.g. MTN-12345')} className="font-mono" />
                </div>
              ) : null}
              {payError ? <p className="text-sm font-medium text-destructive">{payError}</p> : null}
              <DialogFooter>
                <Button type="submit" disabled={createPayment.isPending || receivePosPayment.isPending}>
                  {createPayment.isPending || receivePosPayment.isPending ? tx('Saving...') : tx('Save Payment')}
                </Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
      )}

      {/* Email invoice (ADR-S005) */}
      <Dialog open={emailOpen} onOpenChange={setEmailOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{tx('Email Invoice')}</DialogTitle>
            <DialogDescription>{tx('Send')} {inv.documentNumber} {tx('as a PDF attachment.')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1">
              <Label>{tx('To')}</Label>
              <Input value={emailTo} onChange={(e) => setEmailTo(e.target.value)} placeholder={tx("Customer's email on file")} />
            </div>
            <div className="space-y-1">
              <Label>{tx('Message (optional)')}</Label>
              <Input value={emailMessage} onChange={(e) => setEmailMessage(e.target.value)} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEmailOpen(false)}>{tx('Close')}</Button>
            <Button
              disabled={emailInvoice.isPending}
              onClick={() =>
                emailInvoice.mutate(
                  { id: inv.id, to: emailTo || undefined, message: emailMessage || undefined },
                  { onSuccess: () => setEmailOpen(false) },
                )
              }
            >
              {emailInvoice.isPending ? tx('Sending...') : tx('Send')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Cancel confirm */}
      <Dialog open={cancelOpen} onOpenChange={setCancelOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{tx('Cancel Invoice')}</DialogTitle>
            <DialogDescription>{tx('This will cancel')} {inv.documentNumber} {tx('and prevent further changes or payments.')}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelOpen(false)}>{tx('Keep')}</Button>
            <Button variant="destructive" disabled={cancelInvoice.isPending} onClick={() => cancelInvoice.mutate(inv.id, { onSuccess: () => setCancelOpen(false) })}>
              {cancelInvoice.isPending ? tx('Cancelling...') : tx('Cancel Invoice')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
