import { Fragment, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CheckCircle2, ChevronDown, FileText, FileX, History, Pencil, Plus, Receipt, RefreshCw, RotateCcw, Send, ShieldCheck, Trash2, Undo2, Wallet, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { SmartButton, StatusRibbon } from '@/pages/sales/sales-ui';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import { useProductOptions, useTaxOptions } from '@/features/crm/suite-api';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import {
  METHOD_LABEL, idem, useBranchProfiles, useCashSale,
  type CashSale, type TimelineEvent,
} from '@/features/sales/cash-sales-api';
import { ExecutionPill, FiscalPill, ProcessingPill } from './cash-sales-ui';

interface DraftLine { productId: string; description: string; quantity: number; unitPrice: number | ''; discountPercent: number; taxId: string | null }

const CHECKS = [
  tx('Customer information verified'), tx('Products and prices verified'), tx('Tax verified'),
  tx('Payment evidence verified'), tx('Stock issue verified'), tx('Delivery confirmed'),
];

/**
 * One branch cash sale (ADR-S056). Draft: the showroom edits lines and captures / verifies payments, then confirms
 * the sale and handover (stock, payments and revenue post once). Afterwards the financial fields are frozen; head
 * office reviews it side by side with a verification checklist and returns, rejects, approves and invoices it.
 */
export function CashSalePage() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: s, isLoading } = useCashSale(isNew ? undefined : id);
  const profiles = useBranchProfiles();
  const products = useProductOptions();
  const taxes = useTaxOptions();
  const productOpts = useMemo(() => (products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined })), [products.data]);
  const taxOpts = useMemo(() => (taxes.data ?? []).map((t) => ({ value: t.id, label: `${t.name} (${Number(t.rate)}%)` })), [taxes.data]);
  const [branchId, setBranchId] = useState('');
  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [customer, setCustomer] = useState({ customerName: '', customerPhone: '', customerTin: '', customerAddress: '', remarks: '' });
  const [lines, setLines] = useState<DraftLine[]>(() => (isNew ? [{ productId: '', description: '', quantity: 1, unitPrice: '', discountPercent: 0, taxId: null }] : []));
  const [busy, setBusy] = useState(false);
  const [dialog, setDialog] = useState<null | 'return' | 'reject' | 'reopen' | 'void' | 'cancel_invoice' | 'amend' | 'payment'>(null);
  const [tab, setTab] = useState('lines');
  const editable = isNew || s?.executionStatus === 'draft';

  useEffect(() => {
    if (!s) return;
    setPartnerId(s.partnerId);
    setPartnerName(s.partnerName);
    setCustomer({ customerName: s.customerName ?? '', customerPhone: s.customerPhone ?? '', customerTin: s.customerTin ?? '', customerAddress: s.customerAddress ?? '', remarks: s.remarks ?? '' });
    setLines((s.lines ?? []).map((l) => ({ productId: l.productId, description: l.description, quantity: Number(l.quantity), unitPrice: Number(l.unitPrice), discountPercent: Number(l.discountPercent), taxId: l.taxId })));
  }, [s]);
  useEffect(() => {
    if (isNew && !branchId && profiles.data?.length === 1) setBranchId(profiles.data[0].branchId);
  }, [isNew, branchId, profiles.data]);

  const refresh = async () => { await qc.invalidateQueries({ queryKey: ['cash-sales'] }); };
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    try { await fn(); toast.success(ok); await refresh(); } catch (e) { toast.error(apiErrorMessage(e, tx('Action failed'))); } finally { setBusy(false); }
  };
  const body = () => {
    const b: Record<string, unknown> = {
      lines: lines.filter((l) => l.productId).map((l) => ({ productId: l.productId, description: l.description || undefined, quantity: Number(l.quantity), unitPrice: l.unitPrice === '' ? undefined : Number(l.unitPrice), discountPercent: Number(l.discountPercent) || 0, taxId: l.taxId })),
    };
    if (partnerId) b.partnerId = partnerId;
    Object.entries(customer).forEach(([k, v]) => { b[k] = v || null; });
    return b;
  };
  const save = () => run(async () => {
    if (isNew) {
      const r = await api.post<CashSale>('/sales/cash-sales', { ...body(), branchId: branchId || undefined });
      navigate(`/sales/cash-sales/${r.data.id}`, { replace: true });
    } else {
      await api.patch(`/sales/cash-sales/${id}`, body());
    }
  }, tx('Saved'));
  const act = (path: string, ok: string, payload?: unknown, money?: boolean) =>
    run(() => api.post(`/sales/cash-sales/${id}/${path}`, payload ?? null, money ? idem() : undefined), ok);

  if (!isNew && isLoading) return <Skeleton className="h-96 w-full" />;
  const actions = s?.actions ?? [];
  const has = (a: string) => actions.includes(a);
  const ho = s && !editable && ['submitted', 'under_review', 'approved', 'rejected', 'invoiced'].includes(s.processingStatus);
  const ribbon = [tx('Draft'), tx('Confirmed'), tx('Submitted'), tx('Approved'), tx('Invoiced')];
  const step = !s || s.executionStatus === 'draft' ? 0
    : ({ submitted: 2, under_review: 2, approved: 3, invoiced: 4 } as Record<string, number>)[s.processingStatus] ?? 1;
  const showRibbon = !s || !['voided', 'cancelled'].includes(s.executionStatus);
  const ico = 'mr-2 h-4 w-4';
  type MenuItem = { key: string; label: string; icon: ReactNode; onSelect: () => void; danger?: boolean };
  const menu: MenuItem[] = [];
  if (has('amend')) menu.push({ key: 'amend', label: tx('Correct details'), icon: <Pencil className={ico} />, onSelect: () => setDialog('amend') });
  if (has('reopen')) menu.push({ key: 'reopen', label: tx('Reopen'), icon: <RotateCcw className={ico} />, onSelect: () => setDialog('reopen') });
  if (has('cancel_invoice')) menu.push({ key: 'cancel_invoice', label: tx('Cancel invoice'), icon: <FileX className={ico} />, danger: true, onSelect: () => setDialog('cancel_invoice') });
  if (has('void')) menu.push({ key: 'void', label: tx('Void (goods returned)'), icon: <XCircle className={ico} />, danger: true, onSelect: () => setDialog('void') });
  if (has('discard')) menu.push({ key: 'discard', label: tx('Discard draft'), icon: <Trash2 className={ico} />, danger: true, onSelect: () => { void act('discard', tx('Draft discarded')); } });
  const patchLine = (i: number, p: Partial<DraftLine>) => setLines(lines.map((x, j) => (j === i ? { ...x, ...p } : x)));
  const lineEst = (l: DraftLine, i: number) => (l.unitPrice === '' ? (s?.lines?.[i] ? Number(s.lines[i].lineTotal) : null)
    : Number(l.quantity) * Number(l.unitPrice) * (1 - Number(l.discountPercent || 0) / 100));
  const est = lines.reduce((a, l, i) => a + (lineEst(l, i) ?? 0), 0);
  const paymentsCount = s?.payments?.length ?? 0;
  const custLabel = { customerName: tx('Customer name'), customerPhone: tx('Phone'), customerTin: tx('TIN'), customerAddress: tx('Address') };
  const custField = (k: keyof typeof custLabel, className?: string) => (
    <Field key={k} label={custLabel[k]} className={className}>
      {editable ? <Input value={customer[k]} onChange={(e) => setCustomer({ ...customer, [k]: e.target.value })} /> : <p className="py-2 text-sm">{s?.[k] ?? '—'}</p>}
    </Field>
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-1.5 text-sm">
          <Link to="/sales/cash-sales" className="font-medium text-primary hover:underline">{tx('Cash Sales')}</Link>
          <span className="text-muted-foreground">/</span>
          <span className="font-semibold">{isNew ? tx('New') : s?.number}</span>
          {s && <span className="ml-2 inline-flex flex-wrap items-center gap-1.5">
            <ExecutionPill status={s.executionStatus} /><ProcessingPill status={s.processingStatus} />
            {s.fiscalStatus !== 'none' && <FiscalPill status={s.fiscalStatus} />}
          </span>}
        </div>
        {showRibbon && <div className="w-full max-w-lg"><StatusRibbon steps={ribbon} current={step} /></div>}
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {editable && <Button disabled={busy} onClick={save}>{tx('Save')}</Button>}
          {has('confirm') && <Button variant="secondary" disabled={busy} onClick={() => act('confirm', tx('Sale confirmed: stock, payments and revenue posted'), null, true)}><CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Confirm sale & handover')}</Button>}
          {has('submit') && <Button disabled={busy} onClick={() => act('submit', tx('Submitted to head office'))}><Send className="mr-1.5 h-4 w-4" />{tx('Submit to head office')}</Button>}
          {has('resubmit') && <Button disabled={busy} onClick={() => act('resubmit', tx('Resubmitted'))}><Send className="mr-1.5 h-4 w-4" />{tx('Resubmit')}</Button>}
          {has('start_review') && <Button disabled={busy} onClick={() => act('start-review', tx('Review started'))}>{tx('Start review')}</Button>}
          {has('approve') && <Button disabled={busy} onClick={() => act('approve', tx('Approved'))}><ShieldCheck className="mr-1.5 h-4 w-4" />{tx('Approve')}</Button>}
          {has('return') && <Button variant="outline" disabled={busy} onClick={() => setDialog('return')}><Undo2 className="mr-1.5 h-4 w-4" />{tx('Return for correction')}</Button>}
          {has('reject') && <Button variant="outline" disabled={busy} onClick={() => setDialog('reject')}><XCircle className="mr-1.5 h-4 w-4" />{tx('Reject')}</Button>}
          {has('generate_invoice') && <Button disabled={busy} onClick={() => act('generate-invoice', tx('Invoice generated'), null, true)}><FileText className="mr-1.5 h-4 w-4" />{tx('Generate invoice')}</Button>}
          {menu.length > 0 && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" disabled={busy}>{tx('Actions')}<ChevronDown className="ml-1.5 h-4 w-4" /></Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="min-w-[200px]">
                {menu.map((m, i) => (
                  <Fragment key={m.key}>
                    {m.danger && i > 0 && !menu[i - 1].danger && <DropdownMenuSeparator />}
                    <DropdownMenuItem onSelect={m.onSelect} className={cn(m.danger && 'text-destructive focus:text-destructive')}>{m.icon}{m.label}</DropdownMenuItem>
                  </Fragment>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
        {s && (
          <div className="flex flex-wrap gap-2">
            <SmartButton icon={<Receipt className="h-4 w-4" />} count={s.currentInvoiceId ? 1 : 0} label={tx('Invoice')} disabled={!s.currentInvoiceId} onClick={() => s.currentInvoiceId && navigate(`/invoices/${s.currentInvoiceId}`)} />
            <SmartButton icon={<Wallet className="h-4 w-4" />} count={paymentsCount} label={tx('Payments')} onClick={() => setTab('payments')} />
            <SmartButton icon={<History className="h-4 w-4" />} count="" label={tx('Timeline')} onClick={() => setTab('history')} />
          </div>
        )}
      </div>

      {s?.processingStatus === 'returned_for_correction' && <Notice tone="orange">{tx('Returned by head office for correction — the reason is in the timeline. Correct the customer details, then resubmit.')}</Notice>}
      {s?.processingStatus === 'rejected' && <Notice tone="rose">{tx('Rejected')}: {s.rejectionReason} · {tx('The sale stays posted until it is reopened or returned.')}</Notice>}
      {s?.executionStatus === 'voided' && <Notice tone="rose">{tx('Voided')}: {s.voidReason}</Notice>}
      {s?.currentInvoiceId && <Notice tone="emerald">{tx('Official invoice')} <Link className="font-semibold underline" to={`/invoices/${s.currentInvoiceId}`}>{s.invoiceNumber}</Link></Notice>}

      <div className={cn('grid items-start gap-4', ho ? 'lg:grid-cols-[minmax(0,1fr)_320px]' : '')}>
        <section className="rounded-xl border bg-card shadow-sm">
          <div className="px-5 pt-5">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{tx('Cash sale')}</p>
            <h1 className="text-2xl font-bold">{isNew ? tx('New') : s?.number}</h1>
          </div>
          <div className="grid gap-x-10 gap-y-3 p-5 md:grid-cols-2">
            <div className="space-y-3">
              <Field label={tx('Customer')} hint={editable ? tx('Empty: the branch walk-in customer') : undefined}>
                {editable ? <PartnerPicker value={partnerId} label={partnerName} onPick={(i, nm) => { setPartnerId(i); setPartnerName(nm || null); }} />
                  : <p className="py-2 text-sm font-medium">{s?.partnerName}</p>}
              </Field>
              <div className="grid gap-3 sm:grid-cols-2">
                {custField('customerName')}
                {custField('customerPhone')}
              </div>
            </div>
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2">
                {isNew ? (
                  <Field label={tx('Branch')}>
                    <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={branchId} onChange={(e) => setBranchId(e.target.value)}>
                      <option value="">{tx('My branch')}</option>
                      {(profiles.data ?? []).filter((p) => p.isActive).map((p) => <option key={p.id} value={p.branchId}>{p.branchName}</option>)}
                    </select>
                  </Field>
                ) : (
                  <Field label={tx('Branch')}><p className="py-2 text-sm">{s?.branchName} · {s?.locationName}</p></Field>
                )}
                <Field label={tx('Sale date')}><p className="py-2 text-sm">{s ? date(s.saleDate) : date(new Date().toISOString())}</p></Field>
                {custField('customerTin')}
                <Field label={tx('Salesperson')}><p className="py-2 text-sm">{s?.salespersonName ?? tx('Me')}</p></Field>
              </div>
              {custField('customerAddress')}
            </div>
          </div>

          <Tabs value={tab} onValueChange={setTab}>
            <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0 px-5">
              {([['lines', tx('Sale lines')], ['payments', `${tx('Payments')}${paymentsCount ? ` (${paymentsCount})` : ''}`], ['documents', tx('Supporting documents')], ['history', tx('Timeline')]] as const).map(([v, label]) => (
                <TabsTrigger key={v} value={v} className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{label}</TabsTrigger>
              ))}
            </TabsList>

            <TabsContent value="lines" className="mt-0 space-y-4 p-5">
              <div className="overflow-x-auto">
                <table className="w-full min-w-[900px] text-sm">
                  <thead className="border-b text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                    <tr><th className="py-2 pr-2">{tx('Product')}</th><th className="py-2 pr-2">{tx('Description')}</th><th className="w-20 py-2 pr-2 text-right">{tx('Qty')}</th>
                      <th className="w-28 py-2 pr-2 text-right">{tx('Unit price')}</th><th className="w-16 py-2 pr-2 text-right">{tx('Disc. %')}</th><th className="w-32 py-2 pr-2">{tx('Tax')}</th>
                      <th className="w-28 py-2 pr-2 text-right">{tx('Subtotal')}</th><th className="w-8" /></tr>
                  </thead>
                  <tbody>
                    {editable ? lines.map((l, i) => {
                      const sub = lineEst(l, i);
                      return (
                        <tr key={i} className="border-b align-top">
                          <td className="py-1.5 pr-2"><SearchableSelect value={l.productId} onValueChange={(v) => patchLine(i, { productId: v, unitPrice: '', description: l.description || productOpts.find((p) => p.value === v)?.label || '', taxId: products.data?.find((p) => p.id === v)?.taxId ?? l.taxId })} options={productOpts} placeholder={tx('Product…')} className="w-52" /></td>
                          <td className="py-1.5 pr-2"><Input value={l.description} onChange={(e) => patchLine(i, { description: e.target.value })} /></td>
                          <td className="py-1.5 pr-2"><Input type="number" min={0} className="text-right" value={l.quantity} onChange={(e) => patchLine(i, { quantity: Number(e.target.value) })} /></td>
                          <td className="py-1.5 pr-2"><Input type="number" min={0} className="text-right" placeholder={tx('List')} value={l.unitPrice} onChange={(e) => patchLine(i, { unitPrice: e.target.value === '' ? '' : Number(e.target.value) })} /></td>
                          <td className="py-1.5 pr-2"><Input type="number" min={0} max={100} className="text-right" value={l.discountPercent} onChange={(e) => patchLine(i, { discountPercent: Number(e.target.value) })} /></td>
                          <td className="py-1.5 pr-2"><OptionalSelect value={l.taxId ?? ''} onChange={(v) => patchLine(i, { taxId: v || null })} options={taxOpts} placeholder={tx('No tax')} noneLabel={tx('No tax')} /></td>
                          <td className="py-1.5 pr-2 pt-3 text-right tabular-nums">{sub == null ? <span className="text-xs text-muted-foreground">{tx('List')}</span> : money(sub)}</td>
                          <td className="py-1.5 pt-2"><button className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => setLines(lines.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></button></td>
                        </tr>
                      );
                    }) : (s?.lines ?? []).map((l) => (
                      <tr key={l.id} className="border-b align-top">
                        <td className="py-2 pr-2 font-medium">{l.productName}</td>
                        <td className="py-2 pr-2 text-muted-foreground">{l.description}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{Number(l.quantity)} {l.uomCode ?? ''}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{money(l.unitPrice)}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{Number(l.discountPercent) || '—'}</td>
                        <td className="py-2 pr-2">{l.taxName ?? '—'}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{money(l.lineTotal)}</td><td />
                      </tr>
                    ))}
                    {editable && lines.length === 0 && <tr><td colSpan={8} className="py-4 text-center text-muted-foreground">{tx('No lines yet.')}</td></tr>}
                  </tbody>
                </table>
              </div>
              {editable && <Button variant="outline" size="sm" onClick={() => setLines([...lines, { productId: '', description: '', quantity: 1, unitPrice: '', discountPercent: 0, taxId: null }])}><Plus className="mr-1 h-4 w-4" />{tx('Add a line')}</Button>}
              <div className="grid gap-4 border-t pt-4 md:grid-cols-[minmax(0,1fr)_320px]">
                <Field label={tx('Remarks')}>
                  {editable ? <Textarea rows={3} value={customer.remarks} onChange={(e) => setCustomer({ ...customer, remarks: e.target.value })} /> : <p className="py-2 text-sm">{s?.remarks ?? '—'}</p>}
                </Field>
                <div className="text-sm">
                  <h2 className="mb-2 font-semibold">{tx('Totals')}{editable && <span className="ml-2 text-xs font-normal text-amber-600">{tx('(estimate — save to recalculate)')}</span>}</h2>
                  <Row label={tx('Untaxed amount')} value={money(editable || !s ? est : s.subtotal)} />
                  {s && !editable && <Row label={tx('Taxes')} value={money(s.taxTotal)} />}
                  {s && <div className="mt-2 border-t pt-2"><Row label={tx('Total')} value={<strong className="text-lg">{money(s.grandTotal)}</strong>} /></div>}
                  {s && <Row label={tx('Paid')} value={money(s.paidTotal)} />}
                </div>
              </div>
            </TabsContent>

            <TabsContent value="payments" className="mt-0 p-5">
              {s ? <Payments sale={s} busy={busy} onAdd={() => setDialog('payment')} run={run} /> : <p className="text-sm text-muted-foreground">{tx('Save the draft to capture payments.')}</p>}
            </TabsContent>
            <TabsContent value="documents" className="mt-0 p-5">
              {s ? <AttachmentPanel resType="sales_submission" resId={s.id} title={tx('Supporting documents')} writePermission={PERMISSIONS.sales.cashSaleCreate} bare />
                : <p className="text-sm text-muted-foreground">{tx('Save the draft to add supporting documents.')}</p>}
            </TabsContent>
            <TabsContent value="history" className="mt-0 p-5">
              {s ? <Timeline id={s.id} sale={s} /> : <p className="text-sm text-muted-foreground">{tx('No history yet.')}</p>}
            </TabsContent>
          </Tabs>
        </section>
        {ho && s && <ReviewPanel sale={s} busy={busy} run={run} />}
      </div>

      {s && dialog && dialog !== 'payment' && dialog !== 'amend' && (
        <ReasonDialog kind={dialog} onClose={() => setDialog(null)} onSubmit={(payload) => {
          const map = { return: ['return', tx('Returned for correction'), false], reject: ['reject', tx('Rejected'), false], reopen: ['reopen', tx('Reopened'), false],
            void: ['void', tx('Sale voided'), true], cancel_invoice: ['cancel-invoice', tx('Invoice cancelled'), true] } as const;
          const [path, ok, isMoney] = map[dialog];
          setDialog(null);
          void act(path, ok, payload, isMoney);
        }} />
      )}
      {s && dialog === 'amend' && <AmendDialog sale={s} onClose={() => setDialog(null)} onSubmit={(p, fiscal) => { setDialog(null); void act(fiscal ? 'fiscal/correct' : 'amend', fiscal ? tx('Fiscal correction requested') : tx('Corrected'), p); }} />}
      {s && dialog === 'payment' && <PaymentDialog sale={s} onClose={() => setDialog(null)} onSubmit={(p) => { setDialog(null); void act('payments', tx('Payment recorded'), p); }} />}
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return <div className="flex items-center justify-between py-0.5"><span className="text-muted-foreground">{label}</span><span className="tabular-nums">{value}</span></div>;
}

function Notice({ tone, children }: { tone: 'orange' | 'rose' | 'emerald'; children: React.ReactNode }) {
  const c = { orange: 'border-orange-200 bg-orange-50 text-orange-900', rose: 'border-rose-200 bg-rose-50 text-rose-900', emerald: 'border-emerald-200 bg-emerald-50 text-emerald-900' }[tone];
  return <div className={cn('rounded-lg border px-3 py-2 text-sm dark:bg-transparent', c)}>{children}</div>;
}

function Payments({ sale, busy, onAdd, run }: { sale: CashSale; busy: boolean; onAdd: () => void; run: (fn: () => Promise<unknown>, ok: string) => Promise<void> }) {
  const draft = sale.executionStatus === 'draft';
  const canVerifyAll = useAuthStore((st) => st.hasPermission(PERMISSIONS.sales.cashSaleVerifyPayment));
  const canConfirm = useAuthStore((st) => st.hasPermission(PERMISSIONS.sales.cashSaleConfirm));
  const paid = (sale.payments ?? []).reduce((a, p) => a + Number(p.amount), 0);
  const verified = (sale.payments ?? []).filter((p) => p.verificationStatus === 'verified').reduce((a, p) => a + Number(p.amount), 0);
  return (
    <section>
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{tx('Payments')}</h3>
        <div className="flex items-center gap-3 text-xs text-muted-foreground">
          <span>{tx('Collected')} {money(paid)} · {tx('Verified')} {money(verified)} / {money(sale.grandTotal)}</span>
          {draft && <Button size="sm" variant="outline" disabled={busy} onClick={onAdd}><Plus className="mr-1 h-4 w-4" />{tx('Add payment')}</Button>}
        </div>
      </div>
      <table className="w-full text-sm">
        <thead className="text-left text-xs uppercase text-muted-foreground"><tr><th className="py-1">{tx('Method')}</th><th className="py-1">{tx('Reference')}</th><th className="py-1">{tx('Account')}</th><th className="py-1 text-right">{tx('Amount')}</th><th className="py-1">{tx('Collected by')}</th><th className="py-1">{tx('Verification')}</th><th /></tr></thead>
        <tbody>
          {(sale.payments ?? []).map((p) => {
            const canVerify = p.method === 'cash' ? canVerifyAll || canConfirm : canVerifyAll;
            return (
              <tr key={p.id} className="border-t">
                <td className="py-1.5">{METHOD_LABEL[p.method]}{p.provider ? ` · ${p.provider.toUpperCase()}` : ''}</td>
                <td className="py-1.5 text-xs">{p.reference ?? '—'}</td>
                <td className="py-1.5 text-xs">{p.accountCode} {p.accountName}</td>
                <td className="py-1.5 text-right tabular-nums">{money(p.amount)}</td>
                <td className="py-1.5 text-xs">{p.collectedByName}</td>
                <td className="py-1.5 text-xs">
                  <span className={cn('rounded px-1.5 py-0.5', p.verificationStatus === 'verified' ? 'bg-emerald-100 text-emerald-800' : p.verificationStatus === 'failed' ? 'bg-rose-100 text-rose-800' : 'bg-amber-100 text-amber-800')}>
                    {p.verificationStatus === 'verified' ? tx('Verified') : p.verificationStatus === 'failed' ? tx('Failed') : tx('Not verified')}
                  </span>
                  {p.verifiedByName && <span className="ml-1 text-muted-foreground">{p.verifiedByName}</span>}
                </td>
                <td className="py-1.5 text-right">
                  {draft && p.verificationStatus !== 'verified' && canVerify && <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => api.post(`/sales/cash-sales/${sale.id}/payments/${p.id}/verify`, { status: 'verified' }), tx('Payment verified'))}>{tx('Verify')}</Button>}
                  {draft && <Button size="icon" variant="ghost" disabled={busy} onClick={() => run(() => api.delete(`/sales/cash-sales/${sale.id}/payments/${p.id}`), tx('Payment removed'))}><Trash2 className="h-4 w-4" /></Button>}
                </td>
              </tr>
            );
          })}
          {(sale.payments ?? []).length === 0 && <tr><td colSpan={7} className="py-3 text-center text-muted-foreground">{tx('No payment captured yet.')}</td></tr>}
        </tbody>
      </table>
      <p className="mt-2 text-xs text-muted-foreground">{tx('Only verified payments post. A mobile-money or bank reference typed at the counter is not a verification.')}</p>
    </section>
  );
}

function ReviewPanel({ sale, busy, run }: { sale: CashSale; busy: boolean; run: (fn: () => Promise<unknown>, ok: string) => Promise<void> }) {
  const [checked, setChecked] = useState<boolean[]>(CHECKS.map(() => false));
  return (
    <aside className="space-y-3">
      <div className="rounded-xl border bg-card p-4 shadow-sm">
        <h3 className="mb-2 text-sm font-semibold">{tx('Verification')}</h3>
        <ul className="space-y-1.5 text-sm">
          {CHECKS.map((c, i) => (
            <li key={c}><label className="flex items-center gap-2"><input type="checkbox" checked={checked[i]} onChange={(e) => setChecked(checked.map((x, j) => (j === i ? e.target.checked : x)))} />{c}</label></li>
          ))}
        </ul>
        <p className="mt-2 text-xs text-muted-foreground">{checked.every(Boolean) ? tx('All checks done.') : tx('Tick each check before approving.')}</p>
      </div>
      <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
        <h3 className="mb-2 font-semibold">{tx('Posted at confirmation')}</h3>
        <dl className="grid grid-cols-2 gap-1 text-xs">
          <dt className="text-muted-foreground">{tx('Revenue + VAT (accrued)')}</dt><dd className="text-right tabular-nums">{money(sale.grandTotal)}</dd>
          <dt className="text-muted-foreground">{tx('Customer payments held')}</dt><dd className="text-right tabular-nums">{money(sale.paidTotal)}</dd>
          <dt className="text-muted-foreground">{tx('Stock issued from')}</dt><dd className="text-right">{sale.locationName}</dd>
        </dl>
        <p className="mt-2 text-xs text-muted-foreground">{tx('Generating the invoice only moves the accrual to receivables and applies the payments — nothing is posted twice.')}</p>
      </div>
      {(sale.deposits ?? []).length > 0 && (
        <div className="rounded-xl border bg-card p-4 text-xs shadow-sm">
          <h3 className="mb-2 text-sm font-semibold">{tx('Deposits')}</h3>
          {(sale.deposits ?? []).map((d) => <p key={d.id} className="flex justify-between"><span>{d.number} · {d.status}</span><span className="tabular-nums">{money(d.amount)} / {tx('applied')} {money(d.appliedAmount)}</span></p>)}
        </div>
      )}
      {(sale.invoices ?? []).length > 0 && (
        <div className="rounded-xl border bg-card p-4 text-xs shadow-sm">
          <h3 className="mb-2 text-sm font-semibold">{tx('Invoice history')}</h3>
          {(sale.invoices ?? []).map((i) => <p key={i.id}>#{i.attempt} <Link className="text-primary hover:underline" to={`/invoices/${i.invoiceId}`}>{i.documentNumber}</Link> · {i.status}{i.cancelReason ? ` — ${i.cancelReason}` : ''}</p>)}
        </div>
      )}
      {(sale.fiscalDocuments ?? []).length > 0 && (
        <div className="rounded-xl border bg-card p-4 text-xs shadow-sm">
          <div className="mb-2 flex items-center justify-between"><h3 className="text-sm font-semibold">{tx('Fiscal documents')}</h3>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => run(() => api.post(`/sales/cash-sales/${sale.id}/fiscal/retry`), tx('Fiscal operations retried'))}><RefreshCw className="h-3.5 w-3.5" /></Button></div>
          {(sale.fiscalDocuments ?? []).map((f) => <p key={f.id}>{f.operation} · {f.status}{f.fiscalNumber ? ` · ${f.fiscalNumber}` : ''}{f.lastError ? ` — ${f.lastError}` : ''}</p>)}
        </div>
      )}
    </aside>
  );
}

function Timeline({ id, sale }: { id: string; sale: CashSale }) {
  const { data } = useQuery({ queryKey: ['cash-sales', id, 'timeline', sale.updatedAt], queryFn: async () => (await api.get<TimelineEvent[]>(`/sales/cash-sales/${id}/timeline`)).data });
  return (
    <section>
      <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><History className="h-4 w-4" />{tx('Timeline')}</h3>
      <ol className="space-y-1 text-sm">
        {(data ?? []).map((e) => (
          <li key={e.id} className="flex flex-wrap gap-x-2">
            <span className="text-xs text-muted-foreground">{date(e.createdAt)}</span>
            <span className="font-medium">{e.action.replace(/_/g, ' ')}</span>
            {e.field && <span className="text-xs">{e.field}: {e.oldValue ?? '—'} → {e.newValue ?? '—'}</span>}
            {e.reason && <span className="text-xs text-muted-foreground">“{e.reason}”</span>}
            <span className="text-xs text-muted-foreground">{e.userName}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}

function ReasonDialog({ kind, onClose, onSubmit }: { kind: 'return' | 'reject' | 'reopen' | 'void' | 'cancel_invoice'; onClose: () => void; onSubmit: (p: Record<string, unknown>) => void }) {
  const [reason, setReason] = useState('');
  const [goodsReturned, setGoodsReturned] = useState(false);
  const [depositAction, setDepositAction] = useState<'refund' | 'keep'>('refund');
  const title = { return: tx('Return for correction'), reject: tx('Reject submission'), reopen: tx('Reopen submission'), void: tx('Void sale'), cancel_invoice: tx('Cancel invoice') }[kind];
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{title}</DialogTitle></DialogHeader>
        {kind === 'reject' && <p className="text-sm text-muted-foreground">{tx('Rejecting does not reverse anything: revenue, stock and payment stay posted and the sale joins the exception queue.')}</p>}
        {kind === 'void' && <p className="text-sm text-muted-foreground">{tx('Only for goods physically back on the shelf. A delivered sale is reversed through a sales return, not a void.')}</p>}
        <Field label={tx('Reason')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        {kind === 'void' && (
          <div className="space-y-2 text-sm">
            <label className="flex items-center gap-2"><input type="checkbox" checked={goodsReturned} onChange={(e) => setGoodsReturned(e.target.checked)} />{tx('The goods are physically back in stock')}</label>
            <label className="flex items-center gap-2"><input type="radio" checked={depositAction === 'refund'} onChange={() => setDepositAction('refund')} />{tx('Refund the payment')}</label>
            <label className="flex items-center gap-2"><input type="radio" checked={depositAction === 'keep'} onChange={() => setDepositAction('keep')} />{tx('Keep it as a customer advance')}</label>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button disabled={!reason.trim() && kind !== 'reopen' || (kind === 'void' && !goodsReturned)}
            onClick={() => onSubmit(kind === 'void' ? { reason, goodsReturned, depositAction } : { reason })}>{title}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function AmendDialog({ sale, onClose, onSubmit }: { sale: CashSale; onClose: () => void; onSubmit: (p: Record<string, unknown>, fiscal: boolean) => void }) {
  const [v, setV] = useState({ customerName: sale.customerName ?? '', customerPhone: sale.customerPhone ?? '', customerTin: sale.customerTin ?? '', customerAddress: sale.customerAddress ?? '', remarks: sale.remarks ?? '' });
  const fiscalIssued = sale.fiscalStatus === 'issued';
  const changed = Object.fromEntries(Object.entries(v).filter(([k, x]) => (x || null) !== ((sale as unknown as Record<string, string | null>)[k] || null)));
  const identity = ['customerName', 'customerTin', 'customerAddress'].some((k) => k in changed);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Correct details')}</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">{tx('Only customer details and remarks can change after confirmation. Wrong quantities, prices or payments need a void and a new sale.')}</p>
        {(['customerName', 'customerPhone', 'customerTin', 'customerAddress', 'remarks'] as const).map((k) => (
          <Field key={k} label={{ customerName: tx('Customer name'), customerPhone: tx('Phone'), customerTin: tx('TIN'), customerAddress: tx('Address'), remarks: tx('Remarks') }[k]}>
            <Input value={v[k]} onChange={(e) => setV({ ...v, [k]: e.target.value })} />
          </Field>
        ))}
        {fiscalIssued && identity && <p className="text-xs text-amber-700">{tx('A fiscal document is issued: name, TIN and address are corrected through the fiscal authority first.')}</p>}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button disabled={Object.keys(changed).length === 0} onClick={() => {
            if (fiscalIssued && identity) {
              const p: Record<string, unknown> = { reason: tx('Customer identity correction') };
              ['customerName', 'customerTin', 'customerAddress'].forEach((k) => { if (k in changed) p[k] = changed[k]; });
              onSubmit(p, true);
            } else onSubmit(changed, false);
          }}>{tx('Save correction')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PaymentDialog({ sale, onClose, onSubmit }: { sale: CashSale; onClose: () => void; onSubmit: (p: Record<string, unknown>) => void }) {
  const remaining = Math.max(0, Number(sale.grandTotal) - (sale.payments ?? []).reduce((a, p) => a + Number(p.amount), 0));
  const [method, setMethod] = useState<'cash' | 'mobile_money' | 'bank'>('cash');
  const [provider, setProvider] = useState<'mtn' | 'airtel'>('mtn');
  const [amount, setAmount] = useState<number>(remaining);
  const [reference, setReference] = useState('');
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Add payment')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Method')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={method} onChange={(e) => setMethod(e.target.value as typeof method)}><option value="cash">{tx('Cash')}</option><option value="mobile_money">{tx('Mobile money')}</option><option value="bank">{tx('Bank')}</option></select></Field>
          {method === 'mobile_money' && <Field label={tx('Provider')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={provider} onChange={(e) => setProvider(e.target.value as typeof provider)}><option value="mtn">MTN</option><option value="airtel">Airtel</option></select></Field>}
          <Field label={tx('Amount')}><Input type="number" min={0} value={amount} onChange={(e) => setAmount(Number(e.target.value))} /></Field>
          {method !== 'cash' && <Field label={tx('Transaction reference')}><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>}
        </div>
        <p className="text-xs text-muted-foreground">{tx('The receiving account comes from the branch sales profile.')}</p>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button disabled={amount <= 0 || (method !== 'cash' && !reference.trim())} onClick={() => onSubmit({ method, provider: method === 'mobile_money' ? provider : undefined, amount, reference: reference || undefined })}>{tx('Add payment')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
