import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { History, Paperclip, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, dateTime, localDateInput, money, wallDate } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useUploadRecordAttachments } from '@/features/dms/api';
import { useSalesMutation, type FiscalOperation, type SaleFiscalStatus, type SaleTender, type SalesOrder, type TenderMethod } from '@/features/sales/api';
import {
  CONDITION_LABEL, DISPOSITION_LABEL, FISCAL_OPEN, FISCAL_OPERATION_LABEL, FISCAL_STATUS_LABEL, MOBILE_MONEY_PROVIDERS,
  RETURN_REASON_LABEL, REVERSAL_STAGE_LABEL, SALE_FISCAL_LABEL, TENDER_METHOD_LABEL, changeDue, eventLabel, fiscalDocumentNumber,
  goodsLines, handoverBody, reverseBody, reverseLineProblem, saleCashApi, serialList, serialsExpected, tenderBody,
  useCashProfiles, useSaleEvents, useSaleFiscal, useSalePaymentModes, type CashIdentity, type Condition, type Disposition,
  type HandoverForm, type ReverseForm,
} from '@/features/sales/sale-cash-api';
import { Field } from '@/pages/crm/crm-ui';
import { HeaderSelect, useHeaderOptions } from './OrderHeaderFields';
import { InlineField } from './sales-ui';

const P = PERMISSIONS.sales;
const pill = 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium';
const select = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
const n = (v: unknown) => Number(v ?? 0);

// ---- Header: buyer identity, branch and counter -----------------------------------------------------------------

export function CashIdentityFields({ value, disabled, onChange }: { value: CashIdentity; disabled: boolean; onChange: (v: CashIdentity) => void }) {
  const set = (p: Partial<CashIdentity>) => onChange({ ...value, ...p });
  const hint = tx('From the customer');
  return (
    <>
      <InlineField label={tx('Buyer name')}>
        <Input disabled={disabled} value={value.customerName} placeholder={hint} onChange={(e) => set({ customerName: e.target.value })} />
      </InlineField>
      <InlineField label={tx('Buyer phone')}>
        <Input disabled={disabled} value={value.customerPhone} placeholder={hint} onChange={(e) => set({ customerPhone: e.target.value })} />
      </InlineField>
      <InlineField label={tx('Buyer TIN')}>
        <Input disabled={disabled} value={value.customerTin} placeholder={hint} onChange={(e) => set({ customerTin: e.target.value })} />
      </InlineField>
      <InlineField label={tx('Buyer address')}>
        <Input disabled={disabled} value={value.customerAddress} placeholder={hint} onChange={(e) => set({ customerAddress: e.target.value })} />
      </InlineField>
    </>
  );
}

/** Branch (chosen once, on a new sale), counter location, sale date and fiscal status of a cash sale. */
export function CashSaleInfo({ order, branchId, onBranch }: { order?: SalesOrder; branchId: string; onBranch: (v: string) => void }) {
  const profiles = useCashProfiles();
  const options = useHeaderOptions();
  const profile = order ? (profiles.data ?? []).find((p) => p.id === order.profileId) : (profiles.data ?? []).find((p) => p.branchId === branchId);
  const active = (profiles.data ?? []).filter((p) => p.isActive);
  const branchName = profile?.branchName ?? options.branches.find((b) => b.id === order?.branchId)?.name ?? '—';
  const locationName = profile?.locationName ?? options.locations.find((l) => l.id === order?.locationId)?.name ?? '—';
  return (
    <>
      <InlineField label={tx('Branch')}>
        {order ? <span>{branchName}</span> : (
          <select className={select} value={branchId} onChange={(e) => onBranch(e.target.value)}>
            <option value="">{tx('My branch')}</option>
            {active.map((p) => <option key={p.id} value={p.branchId}>{p.branchName ?? p.branchCode}</option>)}
          </select>
        )}
      </InlineField>
      <InlineField label={tx('Counter location')}><span>{order || profile ? locationName : tx('From the branch profile')}</span></InlineField>
      {order && <InlineField label={tx('Sale date')}><span>{order.saleDate ? wallDate(order.saleDate) : date(order.createdAt)}</span></InlineField>}
      {order && order.status !== 'draft' && <InlineField label={tx('Fiscal')}><SaleFiscalPill status={order.fiscalStatus} /></InlineField>}
    </>
  );
}

export function SaleFiscalPill({ status }: { status?: SaleFiscalStatus }) {
  if (!status) return null;
  const tone = status === 'issued' ? 'border-emerald-300 text-emerald-800 dark:text-emerald-300'
    : status === 'failed' ? 'border-rose-300 text-rose-800 dark:text-rose-300'
      : status === 'none' || status === 'cancelled' ? 'border-slate-300 text-slate-700 dark:text-slate-300'
        : 'border-amber-300 text-amber-800 dark:text-amber-300';
  return <span className={cn(pill, 'border', tone)}>{SALE_FISCAL_LABEL[status] ?? status}</span>;
}

/** Where a confirmed cash sale's fiscal document and reversal stand, in words. */
export function CashSaleNotices({ order: o }: { order: SalesOrder }) {
  const notes: Array<{ tone: 'amber' | 'rose' | 'slate'; text: string }> = [];
  if (o.reversalStage === 'reversed') notes.push({ tone: 'slate', text: `${tx('Reversed')} ${o.reversedAt ? date(o.reversedAt) : ''}${o.reversalReason ? ` — ${o.reversalReason}` : ''}` });
  else if (o.reversalStage === 'approval_pending') notes.push({ tone: 'amber', text: tx('The reversal waits for its credit note to be approved. Run Reverse again once it is approved.') });
  else if (o.reversalStage === 'approval_rejected') notes.push({ tone: 'rose', text: tx('The reversal\'s credit note was rejected. The sale stays as it is.') });
  else if (o.reversalStage === 'fiscal_cancel_pending') notes.push({ tone: 'amber', text: tx('The fiscal document is being cancelled. Once the authority confirms it, run Reverse again to complete the reversal.') });
  else if (o.reversalStage === 'reversal_pending') notes.push({ tone: 'amber', text: tx('The fiscal document is cancelled but the reversal is not complete. Run Reverse again to complete it.') });
  if (!o.reversalStage && o.status !== 'draft') {
    if (o.fiscalStatus === 'pending') notes.push({ tone: 'amber', text: tx('Fiscal pending: the receipt is being registered with the tax authority. Print the invoice once it is registered.') });
    if (o.fiscalStatus === 'failed') notes.push({ tone: 'rose', text: tx('The tax authority refused the fiscal receipt. See the Fiscal tab.') });
  }
  if (!notes.length) return null;
  const tone = { amber: 'border-amber-200 bg-amber-50 text-amber-900', rose: 'border-rose-200 bg-rose-50 text-rose-900', slate: 'border-slate-200 bg-slate-50 text-slate-800' };
  return <>{notes.map((x) => <div key={x.text} className={cn('rounded-xl border p-3 text-sm dark:bg-transparent', tone[x.tone])}>{x.text}</div>)}</>;
}

// ---- Tenders ----------------------------------------------------------------------------------------------------

/** The cash sale's tender lines: captured and verified on the draft, each posted as a receipt at confirm. */
export function TenderPanel({ order: o }: { order: SalesOrder }) {
  const has = useAuthStore((s) => s.hasPermission);
  const [adding, setAdding] = useState(false);
  const verify = useSalesMutation(saleCashApi.verifyTender);
  const remove = useSalesMutation(saleCashApi.removeTender);
  const draft = o.status === 'draft';
  const tenders = o.payments ?? [];
  const captured = tenders.filter((t) => t.verificationStatus !== 'failed').reduce((a, t) => a + n(t.amount), 0);
  const due = draft ? n(o.balanceDue) : 0;
  const canAdd = draft && !!o.actions?.includes('add_payment');
  const canVerify = (t: SaleTender) => t.method === 'cash' ? has(P.cashSaleVerifyPayment) || has(P.cashSaleConfirm) : has(P.cashSaleVerifyPayment);
  const busy = verify.isPending || remove.isPending;
  const fail = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));
  const pending = tenders.filter((t) => t.verificationStatus === 'pending');
  const failed = tenders.filter((t) => t.verificationStatus === 'failed');
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{tx('Payments')}</h3>
        <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
          <span>{tx('Collected')} {money(captured, o.currencyCode)} / {money(o.total, o.currencyCode)}</span>
          {draft && due > 0 && <span className="font-medium text-amber-700">{tx('Balance due')} {money(due, o.currencyCode)}</span>}
          {canAdd && <Button data-tour="sales.cash.add-payment" size="sm" variant="outline" disabled={due <= 0} title={due <= 0 ? tx('The sale is fully paid') : undefined} onClick={() => setAdding(true)}><Plus className="mr-1 h-4 w-4" />{tx('Add payment')}</Button>}
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="text-left text-xs uppercase text-muted-foreground">
            <tr><th className="py-1">{tx('Date')}</th><th className="py-1">{tx('Payment mode')}</th><th className="py-1">{tx('Reference')}</th><th className="py-1">{tx('Payer')}</th>
              <th className="py-1 text-right">{tx('Amount')}</th><th className="py-1 text-right">{tx('Change')}</th><th className="py-1">{tx('Status')}</th><th /></tr>
          </thead>
          <tbody>
            {tenders.map((t) => (
              <tr key={t.id} className={cn('border-t align-top', t.verificationStatus === 'failed' && 'text-muted-foreground line-through decoration-rose-400')}>
                <td className="py-1.5 text-xs whitespace-nowrap">{t.paymentDate ? wallDate(t.paymentDate) : date(t.collectedAt)}</td>
                <td className="py-1.5">{t.paymentModeLabel ?? `${TENDER_METHOD_LABEL[t.method]}${t.provider ? ` · ${t.provider}` : ''}`}
                  {t.accountCode && <div className="text-xs text-muted-foreground">{t.accountCode} {t.accountName}</div>}
                  {t.notes && <div className="text-xs text-muted-foreground">{t.notes}</div>}</td>
                <td className="py-1.5 text-xs">{t.reference ?? '—'}{(t.attachmentIds?.length ?? 0) > 0 && <span className="ml-1 inline-flex items-center gap-0.5 text-muted-foreground" title={tx('In the sale\'s attachments')}><Paperclip className="h-3 w-3" />{t.attachmentIds.length}</span>}</td>
                <td className="py-1.5 text-xs">{t.payerName ?? '—'}{t.payerAccount && <div className="text-muted-foreground">{t.payerAccount}</div>}</td>
                <td className="py-1.5 text-right tabular-nums">{money(t.amount, o.currencyCode)}</td>
                <td className="py-1.5 text-right text-xs tabular-nums">{n(t.changeGiven) > 0 ? <span title={`${tx('Handed over')} ${money(t.amountTendered, o.currencyCode)}`}>{money(t.changeGiven, o.currencyCode)}</span> : '—'}</td>
                <td className="py-1.5 text-xs"><TenderStatus tender={t} /></td>
                <td className="py-1.5 text-right whitespace-nowrap">
                  {draft && t.verificationStatus === 'pending' && canVerify(t) && <>
                    <Button size="sm" variant="outline" disabled={busy} onClick={() => verify.mutate({ id: o.id, paymentId: t.id, status: 'verified' }, { onSuccess: () => toast.success(tx('Payment verified')), onError: fail })}>{tx('Verify')}</Button>
                    <Button size="sm" variant="ghost" className="text-rose-600" disabled={busy} title={tx('The money did not arrive')} onClick={() => verify.mutate({ id: o.id, paymentId: t.id, status: 'failed' }, { onSuccess: () => toast.success(tx('Payment marked as not received')), onError: fail })}>{tx('Not received')}</Button>
                  </>}
                  {draft && t.status === 'captured' && has(P.cashSaleCreate) && <Button size="icon" variant="ghost" disabled={busy} aria-label={tx('Remove payment')} onClick={() => remove.mutate({ id: o.id, paymentId: t.id }, { onSuccess: () => toast.success(tx('Payment removed')), onError: fail })}><Trash2 className="h-4 w-4" /></Button>}
                </td>
              </tr>
            ))}
            {tenders.length === 0 && <tr><td colSpan={8} className="py-3 text-center text-muted-foreground">{draft ? tx('No payment captured yet.') : tx('No payments.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {draft && due > 0 && tenders.length > 0 && <p className="text-xs text-muted-foreground">{tx('Payments must add up to the sale total before the sale can be confirmed.')}</p>}
      {draft && pending.length > 0 && <p className="text-xs text-muted-foreground">{tx('Each payment is verified before the sale is confirmed: cash by the cashier who confirms, mobile money, bank and card by a verifier.')}</p>}
      {draft && failed.length > 0 && <p className="text-xs font-medium text-rose-700">{tx('A payment was marked as not received. It does not count; capture the payment again.')}</p>}
      {adding && <TenderDialog order={o} onClose={() => setAdding(false)} />}
    </section>
  );
}

function TenderStatus({ tender: t }: { tender: SaleTender }) {
  if (t.status === 'posted') return <span className={cn(pill, 'border border-emerald-300 text-emerald-800 dark:text-emerald-300')}>{tx('Received')}{t.paymentNumber ? ` · ${t.paymentNumber}` : ''}</span>;
  if (t.status === 'voided') return <span className={cn(pill, 'border border-slate-300 text-slate-600')}>{tx('Voided')}</span>;
  if (t.verificationStatus === 'verified') return <span className={cn(pill, 'border border-sky-300 text-sky-800 dark:text-sky-300')}>{tx('Verified')}</span>;
  if (t.verificationStatus === 'failed') return <span className={cn(pill, 'bg-rose-100 text-rose-800')}>{tx('Not received')}</span>;
  return <span className={cn(pill, 'bg-amber-100 text-amber-800')}>{tx('Awaiting verification')}</span>;
}

const METHODS: TenderMethod[] = ['cash', 'mobile_money', 'bank', 'card'];

/** Capture one tender: payment mode, amount (cash: what was handed over and the change), reference and evidence. */
function TenderDialog({ order: o, onClose }: { order: SalesOrder; onClose: () => void }) {
  const has = useAuthStore((s) => s.hasPermission);
  const modes = useSalePaymentModes();
  const upload = useUploadRecordAttachments();
  const add = useSalesMutation(saleCashApi.addTender);
  const today = localDateInput();
  const remaining = n(o.balanceDue);
  const [modeId, setModeId] = useState('');
  const [method, setMethod] = useState<TenderMethod>('cash');
  const [provider, setProvider] = useState(MOBILE_MONEY_PROVIDERS[0][0]);
  const [amount, setAmount] = useState(remaining);
  const [tendered, setTendered] = useState<number | ''>('');
  const [reference, setReference] = useState('');
  const [paymentDate, setPaymentDate] = useState(today);
  const [payerName, setPayerName] = useState(o.customerName ?? '');
  const [payerAccount, setPayerAccount] = useState(o.customerPhone ?? '');
  const [notes, setNotes] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [uploaded, setUploaded] = useState<{ files: File[]; ids: string[] }>({ files: [], ids: [] });
  const [saving, setSaving] = useState(false);
  const list = modes.data ?? [];
  const mode = list.length ? list.find((m) => m.id === modeId) ?? list[0] : undefined;
  const kind: TenderMethod = mode?.kind ?? method;
  const needsRef = kind !== 'cash' || !!mode?.requiresReference;
  const change = kind === 'cash' && tendered !== '' ? changeDue(amount, tendered) : 0;
  const tooMuch = amount > remaining + 0.000001;
  const shortTendered = kind === 'cash' && tendered !== '' && tendered < amount;
  const canAttach = has(PERMISSIONS.dms.documentWrite);
  const refLabel = kind === 'card' ? tx('Approval code') : kind === 'cash' ? tx('Receipt no.') : tx('Transaction reference');
  const invalid = !(amount > 0) || tooMuch || shortTendered || (needsRef && !reference.trim()) || (!!paymentDate && paymentDate > today);
  const submit = async () => {
    setSaving(true);
    let ids: string[] | null = files.length === 0 ? [] : uploaded.files.length === files.length && files.every((f, i) => f === uploaded.files[i]) ? uploaded.ids : null;
    if (ids === null) {
      try {
        ids = (await upload.mutateAsync({ resType: 'sales_order', resId: o.id, files })).map((d) => d.id);
        setUploaded({ files, ids });
      } catch (e) {
        toast.error(apiErrorMessage(e, tx('Could not upload the file')));
        setSaving(false);
        return;
      }
    }
    const body = tenderBody({ modeId: mode?.id, method: kind, provider, amount, tendered: tendered === '' ? null : tendered, reference,
      paymentDate, payerName, payerAccount, notes, attachmentIds: ids });
    add.mutate({ id: o.id, body }, {
      onSuccess: () => { toast.success(tx('Payment added')); onClose(); },
      onError: (e) => { toast.error(apiErrorMessage(e, tx('Could not add the payment'))); setSaving(false); },
    });
  };
  return (
    <Dialog open onOpenChange={(v) => !v && !saving && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader><DialogTitle>{tx('Add payment')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Payment mode')}>
            {list.length > 0 ? (
              <select className={select} value={mode?.id ?? ''} onChange={(e) => setModeId(e.target.value)}>
                {list.map((m) => <option key={m.id} value={m.id}>{m.label}{m.provider && m.kind !== 'cash' && m.provider !== m.label ? ` · ${m.provider}` : ''}</option>)}
              </select>
            ) : (
              <select className={select} value={method} onChange={(e) => setMethod(e.target.value as TenderMethod)} disabled={modes.isLoading}>
                {METHODS.map((m) => <option key={m} value={m}>{TENDER_METHOD_LABEL[m]}</option>)}
              </select>
            )}
          </Field>
          {!mode && method === 'mobile_money' ? (
            <Field label={tx('Provider')}>
              <select className={select} value={provider} onChange={(e) => setProvider(e.target.value)}>
                {MOBILE_MONEY_PROVIDERS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </Field>
          ) : <div className="hidden sm:block" />}
          <Field label={tx('Amount')}>
            <Input type="number" min={0} step="0.01" value={amount} onChange={(e) => setAmount(Number(e.target.value))} />
            <p className={cn('mt-1 text-xs', tooMuch ? 'text-rose-700' : 'text-muted-foreground')}>
              {tooMuch ? `${tx('More than the balance due')} (${money(remaining, o.currencyCode)})` : `${tx('Balance due')} ${money(remaining, o.currencyCode)}`}
            </p>
          </Field>
          {kind === 'cash' ? (
            <Field label={tx('Cash handed over')}>
              <Input type="number" min={0} step="0.01" value={tendered} placeholder={String(amount)} onChange={(e) => setTendered(e.target.value === '' ? '' : Number(e.target.value))} />
              <p className={cn('mt-1 text-xs', shortTendered ? 'text-rose-700' : 'text-muted-foreground')}>
                {shortTendered ? tx('Less than the amount') : `${tx('Change to give back')} ${money(change, o.currencyCode)}`}
              </p>
            </Field>
          ) : <div className="hidden sm:block" />}
          <Field label={needsRef ? `${refLabel} *` : refLabel}><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
          <Field label={tx('Payment date')}>
            <Input type="date" max={today} value={paymentDate} onChange={(e) => setPaymentDate(e.target.value)} />
            {!!paymentDate && paymentDate > today && <p className="mt-1 text-xs text-rose-700">{tx('The payment date cannot be in the future')}</p>}
          </Field>
          <Field label={tx('Payer name')}><Input value={payerName} onChange={(e) => setPayerName(e.target.value)} /></Field>
          <Field label={kind === 'bank' ? tx('Payer account no.') : kind === 'mobile_money' ? tx('Payer phone') : tx('Payer phone / account no.')}><Input value={payerAccount} onChange={(e) => setPayerAccount(e.target.value)} /></Field>
          <div className="sm:col-span-2"><Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field></div>
          {canAttach && (
            <div className="sm:col-span-2">
              <Field label={tx('Attachments')}>
                <label className="flex cursor-pointer items-center gap-2 rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground hover:bg-muted/40">
                  <Paperclip className="h-4 w-4" />{tx('Attach payment slip, receipt or screenshot')}
                  <input type="file" multiple className="hidden" onChange={(e) => { const f = Array.from(e.target.files ?? []); setFiles((cur) => [...cur, ...f]); e.target.value = ''; }} />
                </label>
                {files.length > 0 && (
                  <ul className="mt-2 divide-y rounded-md border text-sm">
                    {files.map((f, i) => (
                      <li key={`${f.name}-${i}`} className="flex items-center gap-2 px-2 py-1">
                        <span className="min-w-0 flex-1 truncate">{f.name}</span>
                        <Button size="icon" variant="ghost" aria-label={tx('Remove')} onClick={() => setFiles((cur) => cur.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
                      </li>
                    ))}
                  </ul>
                )}
              </Field>
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={saving} onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={saving || invalid || modes.isLoading} onClick={submit}>{tx('Add payment')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---- Confirm with handover --------------------------------------------------------------------------------------

/**
 * Confirm a paid cash sale at the counter (§5.2, §5.3): the customer receives every line in full, the cashier affirms it
 * and names who took the goods. Stock, invoice, receipts and the fiscal request are posted together, or not at all.
 */
export function HandoverDialog({ order: o, onClose }: { order: SalesOrder; onClose: () => void }) {
  const confirm = useSalesMutation(saleCashApi.confirm);
  const goods = goodsLines(o.lines);
  const serialized = goods.filter((l) => l.serialTracking);
  const [form, setForm] = useState<HandoverForm>({ affirmed: false, receivedByName: o.customerName ?? '', receivedByPhone: o.customerPhone ?? '', notes: '', serials: {} });
  const wrongCount = serialized.filter((l) => { const want = serialsExpected(l); return want !== null && serialList(form.serials[l.id!]).length !== want; });
  const ready = form.affirmed && !!form.receivedByName.trim() && wrongCount.length === 0;
  const submit = () => confirm.mutate({ id: o.id, handover: handoverBody(form, o.lines) }, {
    onSuccess: (r) => {
      toast.success(tx('Sale confirmed: goods handed over, invoice and receipts posted'));
      if (r?.fiscalStatus === 'pending') toast.info(tx('The fiscal receipt is being registered with the tax authority.'));
      onClose();
    },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not confirm the sale'))),
  });
  return (
    <Dialog open onOpenChange={(v) => !v && !confirm.isPending && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader><DialogTitle>{tx('Confirm sale & hand over')}</DialogTitle></DialogHeader>
        <div className="space-y-4 text-sm">
          <div className="rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-muted/40 text-left text-xs uppercase text-muted-foreground"><tr><th className="px-3 py-1.5">{tx('Product')}</th><th className="px-3 py-1.5 text-right">{tx('Quantity')}</th><th className="px-3 py-1.5">{tx('Serial numbers')}</th></tr></thead>
              <tbody>
                {goods.map((l) => {
                  const want = serialsExpected(l);
                  const got = serialList(form.serials[l.id!]).length;
                  return (
                    <tr key={l.id} className="border-t align-top">
                      <td className="px-3 py-1.5">{l.productName ?? l.description}{l.variantName && <span className="block text-xs text-muted-foreground">{l.variantName}</span>}</td>
                      <td className="px-3 py-1.5 text-right tabular-nums">{n(l.quantity) - n(l.qtyCancelled)} {l.uomCode ?? ''}</td>
                      <td className="px-3 py-1.5">{l.serialTracking ? (
                        <>
                          <Input aria-label={tx('Serial numbers')} placeholder={tx('Serial numbers, comma separated')} value={form.serials[l.id!] ?? ''}
                            onChange={(e) => setForm({ ...form, serials: { ...form.serials, [l.id!]: e.target.value } })} />
                          {want !== null && <p className={cn('mt-0.5 text-xs', got === want ? 'text-muted-foreground' : 'text-amber-700')}>{got} / {want}</p>}
                        </>
                      ) : <span className="text-muted-foreground">—</span>}</td>
                    </tr>
                  );
                })}
                {goods.length === 0 && <tr><td colSpan={3} className="px-3 py-2 text-muted-foreground">{tx('No goods to hand over: services only.')}</td></tr>}
              </tbody>
            </table>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={`${tx('Received by')} *`}><Input value={form.receivedByName} onChange={(e) => setForm({ ...form, receivedByName: e.target.value })} /></Field>
            <Field label={tx('Phone')}><Input value={form.receivedByPhone} onChange={(e) => setForm({ ...form, receivedByPhone: e.target.value })} /></Field>
            <div className="sm:col-span-2"><Field label={tx('Notes')}><Textarea rows={2} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></Field></div>
          </div>
          <label className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-amber-900 dark:bg-transparent">
            <input type="checkbox" className="mt-0.5 h-4 w-4" checked={form.affirmed} onChange={(e) => setForm({ ...form, affirmed: e.target.checked })} />
            <span>{tx('The customer has received these goods, every line in full.')}</span>
          </label>
          <p className="text-xs text-muted-foreground">{tx('Confirming issues the stock, posts the invoice and one receipt per payment, and asks for the fiscal receipt. It cannot be edited afterwards; a mistake is corrected with Reverse.')}</p>
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={confirm.isPending} onClick={onClose}>{tx('Back')}</Button>
          <Button disabled={!ready || confirm.isPending} onClick={submit}>{tx('Confirm and hand over')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---- Reverse ----------------------------------------------------------------------------------------------------

/**
 * Reverse a completed cash sale (§7.4): every goods line comes back in full and is inspected, the fiscal document is
 * cancelled, then the return, the credit note and the refund (or the customer credit) are posted together.
 */
export function ReverseDialog({ order: o, onClose }: { order: SalesOrder; onClose: () => void }) {
  const options = useHeaderOptions();
  const reverse = useSalesMutation(saleCashApi.reverse);
  const goods = goodsLines(o.lines);
  const [form, setForm] = useState<ReverseForm>({ reason: '', settlement: 'refund', returnReason: 'other', locationId: o.locationId ?? '', lines: {} });
  const setLine = (id: string, p: ReverseForm['lines'][string]) => setForm((f) => ({ ...f, lines: { ...f.lines, [id]: { ...f.lines[id], ...p } } }));
  const problems = goods.map((l) => reverseLineProblem(form.lines[l.id!] ?? {})).filter(Boolean);
  const serialGaps = goods.filter((l) => { const want = serialsExpected(l); return l.serialTracking && want !== null && serialList(form.lines[l.id!]?.serials).length !== want; });
  const ready = !!form.reason.trim() && problems.length === 0 && serialGaps.length === 0;
  const submit = () => reverse.mutate({ id: o.id, body: reverseBody(form, o.lines) }, {
    onSuccess: (r) => {
      if (r?.reversalStage === 'reversed') toast.success(form.settlement === 'refund' ? tx('Sale reversed and refunded') : tx('Sale reversed; the amount is kept as customer credit'));
      else toast.info(r?.reversalStage ? REVERSAL_STAGE_LABEL[r.reversalStage] : tx('Reversal requested'));
      onClose();
    },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not reverse the sale'))),
  });
  return (
    <Dialog open onOpenChange={(v) => !v && !reverse.isPending && onClose()}>
      <DialogContent className="sm:max-w-4xl">
        <DialogHeader><DialogTitle>{tx('Reverse cash sale')} {o.number}</DialogTitle></DialogHeader>
        <div className="space-y-4 text-sm">
          <p className="text-xs text-muted-foreground">{tx('Every line comes back in full. To take part of a sale back, use a return instead.')}</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="sm:col-span-2"><Field label={`${tx('Reason')} *`}><Textarea rows={2} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></Field></div>
            <Field label={tx('Settlement')}>
              <select className={select} value={form.settlement} onChange={(e) => setForm({ ...form, settlement: e.target.value as 'refund' | 'credit' })}>
                <option value="refund">{tx('Refund over the payments received')}</option>
                <option value="credit">{tx('Keep as customer credit')}</option>
              </select>
            </Field>
            <Field label={tx('Return reason')}>
              <select className={select} value={form.returnReason} onChange={(e) => setForm({ ...form, returnReason: e.target.value })}>
                {Object.entries(RETURN_REASON_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </Field>
            <Field label={tx('Goods come back to')}><HeaderSelect value={form.locationId} options={options.locations} none={tx('The sale\'s location')} onChange={(locationId) => setForm({ ...form, locationId })} /></Field>
          </div>
          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full min-w-[760px] text-sm">
              <thead className="bg-muted/40 text-left text-xs uppercase text-muted-foreground">
                <tr><th className="px-3 py-1.5">{tx('Product')}</th><th className="px-3 py-1.5 text-right">{tx('Quantity')}</th><th className="px-3 py-1.5">{tx('Serial numbers')}</th>
                  <th className="px-3 py-1.5">{tx('Condition')}</th><th className="px-3 py-1.5">{tx('Disposition')}</th><th className="px-3 py-1.5">{tx('Notes')}</th></tr>
              </thead>
              <tbody>
                {goods.map((l) => {
                  const g = form.lines[l.id!] ?? {};
                  const problem = reverseLineProblem(g);
                  const want = serialsExpected(l);
                  return (
                    <tr key={l.id} className="border-t align-top">
                      <td className="px-3 py-1.5">{l.productName ?? l.description}{l.variantName && <span className="block text-xs text-muted-foreground">{l.variantName}</span>}</td>
                      <td className="px-3 py-1.5 text-right tabular-nums">{n(l.quantity) - n(l.qtyCancelled)} {l.uomCode ?? ''}</td>
                      <td className="px-3 py-1.5">{l.serialTracking ? (
                        <>
                          <Input aria-label={tx('Serial numbers that came back')} placeholder={tx('Serial numbers, comma separated')} value={g.serials ?? ''} onChange={(e) => setLine(l.id!, { serials: e.target.value })} />
                          {want !== null && <p className="mt-0.5 text-xs text-muted-foreground">{serialList(g.serials).length} / {want}</p>}
                        </>
                      ) : <span className="text-muted-foreground">—</span>}</td>
                      <td className="px-3 py-1.5">
                        <select aria-label={tx('Condition')} className={select} value={g.condition ?? ''} onChange={(e) => setLine(l.id!, { condition: e.target.value as Condition | '' })}>
                          <option value="">{tx('Condition…')}</option>
                          {(Object.keys(CONDITION_LABEL) as Condition[]).map((k) => <option key={k} value={k}>{CONDITION_LABEL[k]}</option>)}
                        </select>
                      </td>
                      <td className="px-3 py-1.5">
                        <select aria-label={tx('Disposition')} className={select} value={g.disposition ?? 'restock'} onChange={(e) => setLine(l.id!, { disposition: e.target.value as Disposition })}>
                          {(Object.keys(DISPOSITION_LABEL) as Disposition[]).map((k) => <option key={k} value={k}>{DISPOSITION_LABEL[k]}</option>)}
                        </select>
                        {(g.disposition === 'quarantine' || g.disposition === 'damaged') && (
                          <div className="mt-1"><HeaderSelect value={g.locationId ?? ''} options={options.locations} none={tx('Default location')} onChange={(locationId) => setLine(l.id!, { locationId })} /></div>
                        )}
                        {problem && <p className="mt-0.5 text-xs text-rose-700">{problem}</p>}
                      </td>
                      <td className="px-3 py-1.5"><Input aria-label={tx('Notes')} value={g.notes ?? ''} onChange={(e) => setLine(l.id!, { notes: e.target.value })} /></td>
                    </tr>
                  );
                })}
                {goods.length === 0 && <tr><td colSpan={6} className="px-3 py-2 text-muted-foreground">{tx('No goods to take back: services only.')}</td></tr>}
              </tbody>
            </table>
          </div>
          <p className="text-xs text-muted-foreground">{tx('The fiscal document is cancelled first. If the authority has not confirmed the cancel yet, the reversal waits; run Reverse again later to complete it.')}</p>
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={reverse.isPending} onClick={onClose}>{tx('Back')}</Button>
          <Button variant="destructive" disabled={!ready || reverse.isPending} onClick={submit}>{tx('Reverse sale')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---- Fiscal -----------------------------------------------------------------------------------------------------

/**
 * The sale's fiscal operations (§5.5): a cash sale's one document, or each invoice and credit note of an order (C4b).
 * Open operations can be sent again; an outcome the provider cannot report is reconciled by hand.
 */
export function FiscalPanel({ order: o, hideEmpty }: { order: SalesOrder; hideEmpty?: boolean }) {
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading } = useSaleFiscal(o.id);
  const retry = useSalesMutation(saleCashApi.retryFiscal);
  const [resolving, setResolving] = useState<FiscalOperation | null>(null);
  const cash = o.saleType === 'cash';
  const canResolve = has(cash ? P.cashSaleReview : P.invoiceCreate);
  const ops = data ?? [];
  const open = ops.some((d) => d.status === 'pending' || d.status === 'sent' || d.status === 'unknown');
  if (hideEmpty && !isLoading && ops.length === 0) return null;
  return (
    <section className="space-y-3" data-tour="sales.cash.fiscal">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="flex items-center gap-2 text-sm font-semibold">{tx('Fiscal registration')}{cash && <SaleFiscalPill status={o.fiscalStatus} />}</h3>
        {open && <Button size="sm" variant="outline" disabled={retry.isPending} onClick={() => retry.mutate(o.id, { onSuccess: () => toast.success(tx('Sent again')), onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))) })}><RefreshCw className="mr-1 h-4 w-4" />{tx('Send again')}</Button>}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="text-left text-xs uppercase text-muted-foreground">
            <tr><th className="py-1">{tx('Operation')}</th><th className="py-1">{tx('Document')}</th><th className="py-1">{tx('Provider')}</th><th className="py-1">{tx('Status')}</th>
              <th className="py-1">{tx('Fiscal number')}</th><th className="py-1">{tx('Details')}</th><th className="py-1">{tx('Date')}</th><th /></tr>
          </thead>
          <tbody>
            {ops.map((d) => (
              <tr key={d.id} className="border-t align-top">
                <td className="py-1.5">{FISCAL_OPERATION_LABEL[d.operation] ?? d.operation}</td>
                <td className="py-1.5 text-xs">{fiscalDocumentNumber(d) ?? '—'}</td>
                <td className="py-1.5 text-xs">{d.provider}</td>
                <td className="py-1.5"><FiscalStatusPill status={d.status} /></td>
                <td className="py-1.5 text-xs font-medium">{d.fiscalNumber ?? '—'}</td>
                <td className="py-1.5 text-xs text-muted-foreground">
                  {d.lastError && <div className="text-rose-700">{d.lastError}</div>}
                  {d.resolution && <div>{tx('Reconciled')}: {d.resolution}</div>}
                  {(d.submissions ?? 0) > 1 && <div>{tx('Sent')} {d.submissions}×</div>}
                </td>
                <td className="py-1.5 text-xs whitespace-nowrap">{dateTime(d.createdAt)}</td>
                <td className="py-1.5 text-right">{(d.status === 'unknown' || d.status === 'review') && canResolve && <Button size="sm" variant="outline" onClick={() => setResolving(d)}>{tx('Reconcile')}</Button>}</td>
              </tr>
            ))}
            {!isLoading && ops.length === 0 && <tr><td colSpan={8} className="py-3 text-center text-muted-foreground">{o.status === 'draft' ? tx('The fiscal receipt is requested when the sale is confirmed.') : tx('No fiscal operations.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {ops.some((d) => d.status === 'review') && <p className="text-xs text-muted-foreground">{tx('The provider can neither report on this request nor ignore a repeat, so nothing is sent again automatically. Check with the provider, then reconcile it.')}</p>}
      {resolving && <ResolveDialog order={o} op={resolving} onClose={() => setResolving(null)} />}
    </section>
  );
}

function FiscalStatusPill({ status }: { status: FiscalOperation['status'] }) {
  const tone = status === 'succeeded' ? 'border-emerald-300 text-emerald-800 dark:text-emerald-300' : status === 'failed' ? 'border-rose-300 text-rose-800 dark:text-rose-300'
    : FISCAL_OPEN.includes(status) && status !== 'pending' ? 'border-amber-300 text-amber-800 dark:text-amber-300' : 'border-slate-300 text-slate-700 dark:text-slate-300';
  return <span className={cn(pill, 'border', tone)}>{FISCAL_STATUS_LABEL[status] ?? status}</span>;
}

function ResolveDialog({ order: o, op, onClose }: { order: SalesOrder; op: FiscalOperation; onClose: () => void }) {
  const resolve = useSalesMutation(saleCashApi.resolveFiscal);
  const [resolution, setResolution] = useState<'issued' | 'not_received'>('issued');
  const [fiscalNumber, setFiscalNumber] = useState('');
  const [externalRef, setExternalRef] = useState('');
  const [note, setNote] = useState('');
  const needsNumber = resolution === 'issued' && (op.operation === 'issue' || op.operation === 'credit_note');
  const ready = !!note.trim() && (!needsNumber || !!fiscalNumber.trim());
  const submit = () => resolve.mutate({ id: o.id, fiscalId: op.id, body: { resolution, note: note.trim(), ...(resolution === 'issued' && fiscalNumber.trim() ? { fiscalNumber: fiscalNumber.trim() } : {}), ...(resolution === 'issued' && externalRef.trim() ? { externalRef: externalRef.trim() } : {}) } }, {
    onSuccess: () => { toast.success(tx('Fiscal operation reconciled')); onClose(); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
  });
  return (
    <Dialog open onOpenChange={(v) => !v && !resolve.isPending && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader><DialogTitle>{tx('Reconcile fiscal operation')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <p className="text-xs text-muted-foreground">{FISCAL_OPERATION_LABEL[op.operation]} · {fiscalDocumentNumber(op) ?? op.requestRef} · {op.provider}</p>
          <Field label={tx('What the provider says')}>
            <select className={select} value={resolution} onChange={(e) => setResolution(e.target.value as 'issued' | 'not_received')}>
              <option value="issued">{tx('Registered: the provider has it')}</option>
              <option value="not_received">{tx('Not received: send it again under the same reference')}</option>
            </select>
          </Field>
          {resolution === 'issued' && <>
            <Field label={needsNumber ? `${tx('Fiscal number')} *` : tx('Fiscal number')}><Input value={fiscalNumber} onChange={(e) => setFiscalNumber(e.target.value)} /></Field>
            <Field label={tx('Provider reference')}><Input value={externalRef} onChange={(e) => setExternalRef(e.target.value)} /></Field>
          </>}
          <Field label={`${tx('Note')} *`}><Textarea rows={2} value={note} placeholder={tx('How you checked with the provider')} onChange={(e) => setNote(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={resolve.isPending} onClick={onClose}>{tx('Back')}</Button>
          <Button disabled={!ready || resolve.isPending} onClick={submit}>{tx('Reconcile')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---- Timeline ---------------------------------------------------------------------------------------------------

/** The cash sale's timeline: tenders, verification, confirm, fiscal steps and the reversal. */
export function SaleTimeline({ orderId }: { orderId: string }) {
  const { data, isLoading } = useSaleEvents(orderId);
  const events = useMemo(() => [...(data ?? [])].reverse(), [data]);
  return (
    <section className="rounded-xl border bg-card p-4 shadow-sm">
      <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><History className="h-4 w-4" />{tx('Timeline')}</h3>
      <ol className="space-y-1.5 text-sm">
        {events.map((e) => (
          <li key={e.id} className="flex flex-wrap items-baseline gap-x-2">
            <span className="text-xs text-muted-foreground">{dateTime(e.createdAt)}</span>
            <span className="font-medium">{eventLabel(e.action)}</span>
            {e.fromStatus && e.toStatus && <span className="text-xs text-muted-foreground">{e.fromStatus} → {e.toStatus}</span>}
            {e.note && <span className="text-xs">{e.note}</span>}
            {e.userName && <span className="text-xs text-muted-foreground">· {e.userName}</span>}
          </li>
        ))}
        {!isLoading && events.length === 0 && <li className="text-muted-foreground">{tx('Nothing recorded yet.')}</li>}
      </ol>
    </section>
  );
}
