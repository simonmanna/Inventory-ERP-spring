import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CheckCircle2, FileText, History, Plus, RefreshCw, Send, ShieldCheck, Trash2, Undo2, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
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
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [dialog, setDialog] = useState<null | 'return' | 'reject' | 'reopen' | 'void' | 'cancel_invoice' | 'amend' | 'payment'>(null);
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

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs text-muted-foreground"><Link to="/sales/cash-sales" className="hover:underline">{tx('Cash Sales')}</Link></p>
          <h1 className="text-2xl font-bold tracking-tight">{isNew ? tx('New cash sale') : s?.number}</h1>
          {s && (
            <div className="mt-1 flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
              <ExecutionPill status={s.executionStatus} /><ProcessingPill status={s.processingStatus} />
              {s.fiscalStatus !== 'none' && <FiscalPill status={s.fiscalStatus} />}
              <span>· {s.branchName} · {s.locationName} · {date(s.saleDate)}</span>
            </div>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          {editable && <Button variant="outline" disabled={busy} onClick={save}>{tx('Save draft')}</Button>}
          {has('confirm') && <Button disabled={busy} onClick={() => act('confirm', tx('Sale confirmed: stock, payments and revenue posted'), null, true)}><CheckCircle2 className="mr-1 h-4 w-4" />{tx('Confirm sale & handover')}</Button>}
          {has('submit') && <Button disabled={busy} onClick={() => act('submit', tx('Submitted to head office'))}><Send className="mr-1 h-4 w-4" />{tx('Submit to head office')}</Button>}
          {has('amend') && <Button variant="outline" disabled={busy} onClick={() => setDialog('amend')}>{tx('Correct details')}</Button>}
          {has('resubmit') && <Button disabled={busy} onClick={() => act('resubmit', tx('Resubmitted'))}><Send className="mr-1 h-4 w-4" />{tx('Resubmit')}</Button>}
          {has('start_review') && <Button disabled={busy} onClick={() => act('start-review', tx('Review started'))}>{tx('Start review')}</Button>}
          {has('return') && <Button variant="outline" disabled={busy} onClick={() => setDialog('return')}><Undo2 className="mr-1 h-4 w-4" />{tx('Return for correction')}</Button>}
          {has('reject') && <Button variant="outline" disabled={busy} onClick={() => setDialog('reject')}><XCircle className="mr-1 h-4 w-4" />{tx('Reject')}</Button>}
          {has('approve') && <Button disabled={busy} onClick={() => act('approve', tx('Approved'))}><ShieldCheck className="mr-1 h-4 w-4" />{tx('Approve')}</Button>}
          {has('reopen') && <Button variant="outline" disabled={busy} onClick={() => setDialog('reopen')}>{tx('Reopen')}</Button>}
          {has('generate_invoice') && <Button disabled={busy} onClick={() => act('generate-invoice', tx('Invoice generated'), null, true)}><FileText className="mr-1 h-4 w-4" />{tx('Generate invoice')}</Button>}
          {has('cancel_invoice') && <Button variant="outline" disabled={busy} onClick={() => setDialog('cancel_invoice')}>{tx('Cancel invoice')}</Button>}
          {has('void') && <Button variant="ghost" className="text-rose-600" disabled={busy} onClick={() => setDialog('void')}>{tx('Void (goods returned)')}</Button>}
          {has('discard') && <Button variant="ghost" disabled={busy} onClick={() => act('discard', tx('Draft discarded'))}><Trash2 className="h-4 w-4" /></Button>}
        </div>
      </div>

      {s?.processingStatus === 'returned_for_correction' && <Notice tone="orange">{tx('Returned by head office for correction — the reason is in the timeline. Correct the customer details, then resubmit.')}</Notice>}
      {s?.processingStatus === 'rejected' && <Notice tone="rose">{tx('Rejected')}: {s.rejectionReason} · {tx('The sale stays posted until it is reopened or returned.')}</Notice>}
      {s?.executionStatus === 'voided' && <Notice tone="rose">{tx('Voided')}: {s.voidReason}</Notice>}
      {s?.currentInvoiceId && <Notice tone="emerald">{tx('Official invoice')} <Link className="font-semibold underline" to={`/invoices/${s.currentInvoiceId}`}>{s.invoiceNumber}</Link></Notice>}

      <div className={cn('grid gap-4', ho ? 'lg:grid-cols-[1fr_320px]' : '')}>
        <div className="space-y-4">
          <section className="grid gap-3 rounded-xl border bg-card p-4 shadow-sm sm:grid-cols-2 lg:grid-cols-3">
            {isNew && (
              <Field label={tx('Branch')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={branchId} onChange={(e) => setBranchId(e.target.value)}>
                  <option value="">{tx('My branch')}</option>
                  {(profiles.data ?? []).filter((p) => p.isActive).map((p) => <option key={p.id} value={p.branchId}>{p.branchName}</option>)}
                </select>
              </Field>
            )}
            <Field label={tx('Customer account')} hint={tx('Empty: the branch walk-in customer')}>
              {editable ? <PartnerPicker value={partnerId} label={partnerName} onPick={(i, nm) => { setPartnerId(i); setPartnerName(nm || null); }} />
                : <p className="py-2 text-sm font-medium">{s?.partnerName}</p>}
            </Field>
            {(['customerName', 'customerPhone', 'customerTin', 'customerAddress'] as const).map((k) => (
              <Field key={k} label={{ customerName: tx('Customer name'), customerPhone: tx('Phone'), customerTin: tx('TIN'), customerAddress: tx('Address') }[k]}>
                {editable ? <Input value={customer[k]} onChange={(e) => setCustomer({ ...customer, [k]: e.target.value })} /> : <p className="py-2 text-sm">{s?.[k] ?? '—'}</p>}
              </Field>
            ))}
            <Field label={tx('Salesperson')}><p className="py-2 text-sm">{s?.salespersonName ?? tx('Me')}</p></Field>
            <Field label={tx('Remarks')} className="sm:col-span-2 lg:col-span-3">
              {editable ? <Textarea rows={2} value={customer.remarks} onChange={(e) => setCustomer({ ...customer, remarks: e.target.value })} /> : <p className="text-sm">{s?.remarks ?? '—'}</p>}
            </Field>
          </section>

          <section className="overflow-x-auto rounded-xl border bg-card shadow-sm">
            <table className="w-full min-w-[760px] text-sm">
              <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
                <th className="px-3 py-2">{tx('Product')}</th><th className="px-3 py-2 text-right">{tx('Qty')}</th><th className="px-3 py-2 text-right">{tx('Unit price')}</th>
                <th className="px-3 py-2 text-right">{tx('Disc. %')}</th><th className="px-3 py-2">{tx('Tax')}</th><th className="px-3 py-2 text-right">{tx('Total')}</th><th />
              </tr></thead>
              <tbody>
                {editable ? lines.map((l, i) => (
                  <tr key={i} className="border-t">
                    <td className="px-3 py-1.5"><SearchableSelect value={l.productId} onValueChange={(v) => setLines(lines.map((x, j) => j === i ? { ...x, productId: v, unitPrice: '', taxId: products.data?.find((p) => p.id === v)?.taxId ?? x.taxId } : x))} options={productOpts} placeholder={tx('Product…')} className="w-56" /></td>
                    <td className="px-3 py-1.5"><Input type="number" min={0} className="h-8 w-24 text-right" value={l.quantity} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, quantity: Number(e.target.value) } : x))} /></td>
                    <td className="px-3 py-1.5"><Input type="number" min={0} className="h-8 w-28 text-right" placeholder={tx('List')} value={l.unitPrice} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, unitPrice: e.target.value === '' ? '' : Number(e.target.value) } : x))} /></td>
                    <td className="px-3 py-1.5"><Input type="number" min={0} max={100} className="h-8 w-20 text-right" value={l.discountPercent} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, discountPercent: Number(e.target.value) } : x))} /></td>
                    <td className="px-3 py-1.5"><select className="h-8 rounded-md border bg-background px-1 text-sm" value={l.taxId ?? ''} onChange={(e) => setLines(lines.map((x, j) => j === i ? { ...x, taxId: e.target.value || null } : x))}><option value="">{tx('No tax')}</option>{taxOpts.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}</select></td>
                    <td className="px-3 py-1.5 text-right text-xs text-muted-foreground">{s?.lines?.[i] ? money(s.lines[i].lineTotal) : '—'}</td>
                    <td className="px-1"><Button size="icon" variant="ghost" onClick={() => setLines(lines.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button></td>
                  </tr>
                )) : (s?.lines ?? []).map((l) => (
                  <tr key={l.id} className="border-t">
                    <td className="px-3 py-2 font-medium">{l.productName}<span className="block text-xs text-muted-foreground">{l.description}</span></td>
                    <td className="px-3 py-2 text-right tabular-nums">{Number(l.quantity)} {l.uomCode ?? ''}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{money(l.unitPrice)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{Number(l.discountPercent) || '—'}</td>
                    <td className="px-3 py-2 text-xs">{l.taxName ?? '—'}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{money(l.lineTotal)}</td><td />
                  </tr>
                ))}
              </tbody>
              <tfoot className="border-t text-sm">
                {editable && <tr><td colSpan={7} className="px-3 py-2"><Button size="sm" variant="outline" onClick={() => setLines([...lines, { productId: '', description: '', quantity: 1, unitPrice: '', discountPercent: 0, taxId: null }])}><Plus className="mr-1 h-4 w-4" />{tx('Add line')}</Button></td></tr>}
                {s && <>
                  <tr><td colSpan={5} className="px-3 py-1 text-right text-muted-foreground">{tx('Subtotal')}</td><td className="px-3 py-1 text-right tabular-nums">{money(s.subtotal)}</td><td /></tr>
                  <tr><td colSpan={5} className="px-3 py-1 text-right text-muted-foreground">{tx('Tax')}</td><td className="px-3 py-1 text-right tabular-nums">{money(s.taxTotal)}</td><td /></tr>
                  <tr><td colSpan={5} className="px-3 py-1 text-right font-semibold">{tx('Total')}</td><td className="px-3 py-1 text-right font-semibold tabular-nums">{money(s.grandTotal)}</td><td /></tr>
                </>}
              </tfoot>
            </table>
            {editable && !isNew && <p className="px-3 pb-2 text-xs text-muted-foreground">{tx('Save the draft to recompute prices, tax and totals.')}</p>}
          </section>

          {s && <Payments sale={s} busy={busy} onAdd={() => setDialog('payment')} run={run} />}
          {s && <AttachmentPanel resType="sales_submission" resId={s.id} title={tx('Supporting documents')} writePermission={PERMISSIONS.sales.cashSaleCreate} />}
          {s && <Timeline id={s.id} sale={s} />}
        </div>
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
    <section className="rounded-xl border bg-card p-4 shadow-sm">
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
    <section className="rounded-xl border bg-card p-4 shadow-sm">
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
