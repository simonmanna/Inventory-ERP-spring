import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Ban, Plus, Printer, Undo2, Wallet } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import { useAuthStore } from '@/stores/auth.store';
import { printDepositReceipt } from '@/features/sales/deposits';
import { DepositCurrencyFields } from './DepositCurrencyFields';
import { depositCurrencyBody, depositCurrencyIncomplete, useBaseCurrency, type DepositCurrency } from '@/features/sales/deposits';

/** A customer deposit (advance) as returned by `GET /customer-deposits`. */
export interface Deposit {
  id: string; number: string; partnerId: string; partnerName: string | null; salesOrderId: string | null; source: string;
  paymentMethod: string | null; amount: number; appliedAmount: number; refundedAmount: number; available: number;
  status: string; receivedDate: string; reference: string | null; notes: string | null;
  /** ADR-S080: the deposit's currency (functional filled in), its rate and the functional value still held. */
  currency?: string; currencyCode?: string | null; exchangeRate?: number; availableBase?: number;
  /** FX-2 D: advance (contract liability) or refundable (monetary); null = not yet classified (pre-FX-2 row). */
  kind?: 'advance' | 'refundable' | null;
}
interface Application { id: string; kind: string; amount: number; documentId: string; documentNumber: string | null; paymentId: string; reversedAt: string | null; createdAt: string }

const idem = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });
const th = 'px-3 py-2';
const selectCls = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
const METHODS: Array<[string, string]> = [['bank', 'Bank transfer'], ['cash', 'Cash'], ['cheque', 'Cheque'], ['mobile_money', 'Mobile money (recorded)']];

type Action = { kind: 'apply' | 'refund' | 'void' | 'detail' | 'classify'; deposit: Deposit } | { kind: 'new' } | null;

/** Customer deposits / advances: take an advance (with or without an order), apply it to invoices, refund or void it. */
export function CustomerDepositsPage() {
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? 'open';
  const { data: review } = useQuery({ queryKey: ['customer-deposits', 'classification-review'],
    queryFn: async () => (await api.get<Array<{ id: string }>>('/customer-deposits/classification-review')).data });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Wallet} title={tx('Customer Deposits')}
        description={tx('Advances and down payments received from customers. Open deposits are a liability until applied to an invoice or refunded.')} />
      <div className="flex gap-2">
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => setParams((p) => { const n = new URLSearchParams(p); n.set('status', e.target.value); return n; }, { replace: true })}>
          <option value="open">{tx('Open')}</option><option value="closed">{tx('Closed')}</option><option value="void">{tx('Void')}</option><option value="all">{tx('All')}</option>
          <option value="unclassified">{tx('Needs classification')}</option>
        </select>
        {!!review?.length && status !== 'unclassified' && (
          <p className="self-center text-sm text-amber-700 dark:text-amber-400">
            {tx('{{v1}} deposit(s) need classification as advance or refundable', { v1: review.length })}
          </p>
        )}
      </div>
      <CustomerDepositsList partnerId={params.get('partnerId') ?? undefined}
        status={status === 'all' || status === 'unclassified' ? undefined : status} unclassifiedOnly={status === 'unclassified'} />
    </div>
  );
}

/** Deposit table with its actions; used on the deposits page and on a customer's page (`partnerId` fixed). */
export function CustomerDepositsList({ partnerId, partnerName, status, unclassifiedOnly }: { partnerId?: string; partnerName?: string; status?: string; unclassifiedOnly?: boolean }) {
  const qc = useQueryClient();
  const has = useAuthStore((s) => s.hasPermission);
  const canRecord = has(PERMISSIONS.sales.depositRecord);
  const canRefund = has(PERMISSIONS.sales.depositRefund);
  const canClassify = has(PERMISSIONS.sales.depositClassify);
  const qk = ['customer-deposits', partnerId ?? null, status ?? null];
  const { data } = useQuery({ queryKey: qk, queryFn: async () => (await api.get<Deposit[]>('/customer-deposits', { params: { partnerId, status } })).data });
  const [action, setAction] = useState<Action>(null);
  const done = () => { void qc.invalidateQueries({ queryKey: ['customer-deposits'] }); void qc.invalidateQueries({ queryKey: ['sales'] }); setAction(null); };
  const rows = [...(data ?? [])].reverse().filter((d) => !unclassifiedOnly || !d.kind);
  const base = useBaseCurrency();
  // functional value at each deposit's own rate, so foreign deposits are not added up at face value
  const totalOpen = rows.filter((d) => d.status === 'open').reduce((a, d) => a + Number(d.availableBase ?? d.available), 0);
  const cm = (d: Deposit, v: number) => (d.currencyCode ? money(v, d.currencyCode) : money(v));
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{tx('Open balance')}: <strong className="text-foreground">{money(totalOpen, base || undefined)}</strong></p>
        {canRecord && <Button size="sm" onClick={() => setAction({ kind: 'new' })}><Plus className="mr-1 h-4 w-4" />{tx('Record advance')}</Button>}
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[860px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{tx('Number')}</th>{!partnerId && <th className={th}>{tx('Customer')}</th>}<th className={th}>{tx('Date')}</th><th className={th}>{tx('Source')}</th>
            <th className={`${th} text-right`}>{tx('Amount')}</th><th className={`${th} text-right`}>{tx('Applied')}</th><th className={`${th} text-right`}>{tx('Refunded')}</th><th className={`${th} text-right`}>{tx('Available')}</th><th className={th}>{tx('Status')}</th><th />
          </tr></thead>
          <tbody>
            {rows.map((d) => {
              const open = d.status === 'open' && Number(d.available) > 0;
              const cashSale = d.source === 'cash_sale';
              const untouched = d.source === 'receipt' && d.status === 'open' && Number(d.appliedAmount) === 0 && Number(d.refundedAmount) === 0;
              return (
                <tr key={d.id} className="border-t">
                  <td className={th}><button className="font-medium text-primary hover:underline" onClick={() => setAction({ kind: 'detail', deposit: d })}>{d.number}</button></td>
                  {!partnerId && <td className={th}><Link className="hover:underline" to={`/crm/customers/${d.partnerId}`}>{d.partnerName}</Link></td>}
                  <td className={th}>{date(d.receivedDate)}</td>
                  <td className={th}>{d.salesOrderId ? <Link className="text-primary hover:underline" to={`/sales/orders/${d.salesOrderId}`}>{tx(sourceLabel(d.source))}</Link> : tx(sourceLabel(d.source))}
                    <div className={`text-xs ${d.kind ? 'text-muted-foreground' : 'text-amber-700 dark:text-amber-400'}`}>{tx(kindLabel(d.kind))}</div></td>
                  <td className={`${th} text-right tabular-nums`}>{cm(d, d.amount)}{d.currencyCode && <div className="text-xs text-muted-foreground">@ {Number(d.exchangeRate)}</div>}</td>
                  <td className={`${th} text-right tabular-nums`}>{cm(d, d.appliedAmount)}</td>
                  <td className={`${th} text-right tabular-nums`}>{cm(d, d.refundedAmount)}</td>
                  <td className={`${th} text-right font-medium tabular-nums`}>{cm(d, d.available)}</td>
                  <td className={th}>{tx(d.status)}</td>
                  <td className={`${th} whitespace-nowrap text-right`}>
                    <Button size="sm" variant="ghost" title={tx('Print receipt')} onClick={() => void printDepositReceipt(d)}><Printer className="h-4 w-4" /></Button>{' '}
                    {open && canRecord && !cashSale && d.kind !== 'refundable' && <Button size="sm" variant="outline" onClick={() => setAction({ kind: 'apply', deposit: d })}>{tx('Apply')}</Button>}{' '}
                    {canClassify && !cashSale && d.status !== 'void' && <Button size="sm" variant="ghost" onClick={() => setAction({ kind: 'classify', deposit: d })}>{tx('Classify')}</Button>}{' '}
                    {open && canRefund && !cashSale && <Button size="sm" variant="outline" onClick={() => setAction({ kind: 'refund', deposit: d })}><Undo2 className="mr-1 h-3 w-3" />{tx('Refund')}</Button>}{' '}
                    {untouched && canRefund && <Button size="sm" variant="ghost" onClick={() => setAction({ kind: 'void', deposit: d })}><Ban className="h-4 w-4" /></Button>}
                  </td>
                </tr>
              );
            })}
            {data && !rows.length && <tr><td colSpan={10} className="p-6 text-center text-muted-foreground">{tx('No deposits.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {action?.kind === 'new' && <NewDepositDialog partnerId={partnerId} partnerName={partnerName} onClose={() => setAction(null)} onDone={done} />}
      {action?.kind === 'apply' && <ApplyDialog deposit={action.deposit} onClose={() => setAction(null)} onDone={done} />}
      {action?.kind === 'refund' && <RefundDialog deposit={action.deposit} onClose={() => setAction(null)} onDone={done} />}
      {action?.kind === 'void' && <VoidDialog deposit={action.deposit} onClose={() => setAction(null)} onDone={done} />}
      {action?.kind === 'detail' && <DetailDialog depositId={action.deposit.id} onClose={() => setAction(null)} />}
      {action?.kind === 'classify' && <ClassifyDialog deposit={action.deposit} onClose={() => setAction(null)} onDone={done} />}
    </div>
  );
}

function kindLabel(kind?: string | null) {
  return kind === 'refundable' ? 'Refundable deposit' : kind === 'advance' ? 'Advance' : 'Not classified';
}

/** FX-2 D: classify a deposit; an open balance moves between the advance and refundable accounts at carrying value. */
function ClassifyDialog({ deposit, onClose, onDone }: { deposit: Deposit; onClose: () => void; onDone: () => void }) {
  const [kind, setKind] = useState<'advance' | 'refundable'>(deposit.kind === 'advance' ? 'refundable' : 'advance');
  const [reason, setReason] = useState('');
  const m = useAction(onDone, 'Deposit classified');
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Classify deposit')} {deposit.number}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <p className="text-sm text-muted-foreground">{tx('An advance is settled by future sales and keeps its receipt rate. A refundable deposit (security, rental) is money owed back and is revalued at period end.')}</p>
          <Field label={tx('Kind')}><select className={selectCls} value={kind} onChange={(e) => setKind(e.target.value as 'advance' | 'refundable')}>
            <option value="advance">{tx('Advance')}</option><option value="refundable">{tx('Refundable deposit')}</option></select></Field>
          <Field label={tx('Reason')}><Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={tx('Contract, lease or order reference')} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!reason.trim() || kind === deposit.kind || m.isPending}
            onClick={() => m.mutate({ url: `/customer-deposits/${deposit.id}/classify`, body: { kind, reason } })}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function sourceLabel(source: string) {
  return source === 'down_payment_invoice' ? 'Down-payment invoice' : source === 'cash_sale' ? 'Cash sale' : 'Receipt';
}

function useAction(onDone: () => void, success: string) {
  return useMutation({
    mutationFn: async ({ url, body }: { url: string; body: Record<string, unknown> }) => (await api.post(url, body, idem())).data,
    onSuccess: () => { toast.success(tx(success)); onDone(); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
  });
}

function NewDepositDialog({ partnerId: fixed, partnerName: fixedName, onClose, onDone }: { partnerId?: string; partnerName?: string; onClose: () => void; onDone: () => void }) {
  const [partnerId, setPartnerId] = useState(fixed ?? '');
  const [partnerName, setPartnerName] = useState<string | null>(fixedName ?? null);
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState('bank');
  const [receivedDate, setReceivedDate] = useState(new Date().toISOString().slice(0, 10));
  const [reference, setReference] = useState('');
  const [notes, setNotes] = useState('');
  const [fx, setFx] = useState<DepositCurrency>({ currency: '', accountId: '', rate: '' });
  const [kind, setKind] = useState<'advance' | 'refundable'>('advance');
  const base = useBaseCurrency();
  const m = useAction(onDone, 'Deposit recorded');
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Record advance')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          {!fixed && <Field label={tx('Customer')}><PartnerPicker value={partnerId} label={partnerName} onPick={(i, n) => { setPartnerId(i); setPartnerName(n || null); }} /></Field>}
          <Field label={tx('Amount')}><Input type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label={tx('Kind')}><select className={selectCls} value={kind} onChange={(e) => setKind(e.target.value as 'advance' | 'refundable')}>
            <option value="advance">{tx('Advance (settled by future sales)')}</option><option value="refundable">{tx('Refundable deposit (security, rental)')}</option></select></Field>
          <Field label={tx('Method')}><select className={selectCls} value={method} onChange={(e) => setMethod(e.target.value)}>{METHODS.map(([v, l]) => <option key={v} value={v}>{tx(l)}</option>)}</select></Field>
          <DepositCurrencyFields value={fx} onChange={setFx} />
          <Field label={tx('Received on')}><Input type="date" value={receivedDate} onChange={(e) => setReceivedDate(e.target.value)} /></Field>
          <Field label={tx('Reference')}><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
          <Field label={tx('Notes')}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!partnerId || !(Number(amount) > 0) || depositCurrencyIncomplete(fx, base) || m.isPending} onClick={() => m.mutate({ url: '/customer-deposits', body: {
            partnerId, amount: Number(amount), kind, paymentMethod: method, receivedDate, reference: reference || undefined, notes: notes || undefined, ...depositCurrencyBody(fx, base) } })}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ApplyDialog({ deposit, onClose, onDone }: { deposit: Deposit; onClose: () => void; onDone: () => void }) {
  const { data } = useQuery({
    queryKey: ['customer-deposits', 'open-invoices', deposit.partnerId],
    queryFn: async () => (await api.get<{ data: Array<{ id: string; documentNumber: string; amountResidual: number; status: string; documentType?: string; currencyId?: string | null }> }>('/invoices', { params: { partnerId: deposit.partnerId, status: 'posted', pageSize: 100 } })).data.data,
  });
  const base = useBaseCurrency();
  const depCur = deposit.currencyCode || base;
  // ADR-S080: a deposit settles invoices in its own currency only (the server enforces it too)
  const sameCurrency = (i: { currencyId?: string | null }) => !('currencyId' in i) || (i.currencyId || base).toUpperCase() === depCur;
  const open = (data ?? []).filter((i) => i.status === 'posted' && Number(i.amountResidual) > 0 && (!i.documentType || i.documentType === 'sales_invoice') && sameCurrency(i));
  const [documentId, setDocumentId] = useState('');
  const [amount, setAmount] = useState('');
  const m = useAction(onDone, 'Deposit applied');
  const pick = (id: string) => {
    setDocumentId(id);
    const inv = open.find((i) => i.id === id);
    if (inv) setAmount(String(Math.min(Number(deposit.available), Number(inv.amountResidual))));
  };
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Apply')} {deposit.number}</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">{tx('Available')}: {money(deposit.available, deposit.currencyCode || undefined)}</p>
        <Field label={tx('Invoice')}><select className={selectCls} value={documentId} onChange={(e) => pick(e.target.value)}>
          <option value="">{tx('Choose…')}</option>{open.map((i) => <option key={i.id} value={i.id}>{i.documentNumber} · {tx('open')} {money(i.amountResidual)}</option>)}
        </select></Field>
        {data && !open.length && <p className="text-xs text-muted-foreground">{tx('This customer has no posted, unpaid invoice in this currency.')}</p>}
        <Field label={tx('Amount')}><Input type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!documentId || !(Number(amount) > 0) || m.isPending} onClick={() => m.mutate({ url: `/customer-deposits/${deposit.id}/apply`, body: { documentId, amount: Number(amount) } })}>{tx('Apply')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RefundDialog({ deposit, onClose, onDone }: { deposit: Deposit; onClose: () => void; onDone: () => void }) {
  const [amount, setAmount] = useState(String(deposit.available));
  const [method, setMethod] = useState('bank');
  const [reason, setReason] = useState('');
  const base = useBaseCurrency();
  const [fx, setFx] = useState<DepositCurrency>({ currency: deposit.currencyCode ?? '', accountId: '', rate: '' });
  const m = useAction(onDone, 'Refunded');
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Refund')} {deposit.number}</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">{tx('Available')}: {money(deposit.available, deposit.currencyCode || undefined)}</p>
        <Field label={tx('Amount')}><Input type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <Field label={tx('Method')}><select className={selectCls} value={method} onChange={(e) => setMethod(e.target.value)}>{METHODS.map(([v, l]) => <option key={v} value={v}>{tx(l)}</option>)}</select></Field>
        {deposit.currencyCode && <DepositCurrencyFields value={fx} onChange={setFx} lockCurrency />}
        <Field label={tx('Reason')}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!(Number(amount) > 0) || depositCurrencyIncomplete(fx, base) || m.isPending} onClick={() => m.mutate({ url: `/customer-deposits/${deposit.id}/refund`, body: { amount: Number(amount), paymentMethod: method, reason: reason || undefined, ...refundFx(fx, base) } })}>{tx('Refund')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Refund body: the account (and optional rate) only; the currency is the deposit's own. */
function refundFx(fx: DepositCurrency, base: string) {
  const { currencyCode: _c, ...rest } = depositCurrencyBody(fx, base);
  return rest;
}

function VoidDialog({ deposit, onClose, onDone }: { deposit: Deposit; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const m = useAction(onDone, 'Deposit voided');
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Void')} {deposit.number}</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">{tx('Voiding reverses the receipt journal entry. Use it only for a deposit recorded by mistake.')}</p>
        <Field label={tx('Reason')}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button variant="destructive" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate({ url: `/customer-deposits/${deposit.id}/void`, body: { reason } })}>{tx('Void')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DetailDialog({ depositId, onClose }: { depositId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const canReverse = useAuthStore((s) => s.hasPermission(PERMISSIONS.payment.void));
  const { data: d } = useQuery({ queryKey: ['customer-deposits', 'detail', depositId], queryFn: async () => (await api.get<Deposit & { applications: Application[] }>(`/customer-deposits/${depositId}`)).data });
  const [reversing, setReversing] = useState<Application | null>(null);
  const [reason, setReason] = useState('');
  const m = useAction(() => { setReversing(null); setReason(''); void qc.invalidateQueries({ queryKey: ['customer-deposits'] }); void qc.invalidateQueries({ queryKey: ['sales'] }); }, 'Application reversed');
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle className="flex items-center gap-2">{tx('Deposit')} {d?.number}{d && <Button size="sm" variant="outline" onClick={() => void printDepositReceipt(d)}><Printer className="mr-1 h-3.5 w-3.5" />{tx('Print receipt')}</Button>}</DialogTitle></DialogHeader>
        {d && <div className="space-y-3 text-sm">
          <p>{d.partnerName} · {date(d.receivedDate)} · {tx(sourceLabel(d.source))}{d.paymentMethod ? ` · ${d.paymentMethod}` : ''}{d.reference ? ` · ${d.reference}` : ''}</p>
          <p>{tx('Amount')} {money(d.amount, d.currencyCode || undefined)} · {tx('Applied')} {money(d.appliedAmount, d.currencyCode || undefined)} · {tx('Refunded')} {money(d.refundedAmount, d.currencyCode || undefined)} · <strong>{tx('Available')} {money(d.available, d.currencyCode || undefined)}</strong></p>
          {d.currencyCode && <p className="text-xs text-muted-foreground">{tx('Exchange rate')} 1 {d.currencyCode} = {Number(d.exchangeRate)} · {tx('held at')} {money(d.availableBase)}</p>}
          {d.notes && <p className="text-muted-foreground">{d.notes}</p>}
          <div>
            <p className="mb-1 text-xs font-semibold uppercase text-muted-foreground">{tx('History')}</p>
            {d.applications.length === 0 ? <p className="text-muted-foreground">{tx('Not used yet.')}</p> : <ul className="space-y-1">
              {d.applications.map((a) => (
                <li key={a.id} className="flex flex-wrap items-center gap-2">
                  <span className={a.reversedAt ? 'line-through text-muted-foreground' : ''}>{date(a.createdAt)} · {a.kind === 'refund' ? tx('Refund') : <>{tx('Applied to')} <Link className="text-primary hover:underline" to={`/invoices/${a.documentId}`}>{a.documentNumber}</Link></>} · {money(a.amount)}</span>
                  {a.reversedAt && <span className="text-xs text-muted-foreground">{tx('reversed')} {date(a.reversedAt)}</span>}
                  {a.kind === 'application' && !a.reversedAt && canReverse && d.source !== 'cash_sale' && <Button size="sm" variant="ghost" onClick={() => setReversing(a)}>{tx('Reverse')}</Button>}
                </li>
              ))}
            </ul>}
          </div>
          {reversing && <div className="space-y-2 rounded-lg border p-3">
            <p>{tx('Reverse the application to')} {reversing.documentNumber} ({money(reversing.amount)})? {tx('The invoice re-opens and the amount returns to the deposit.')}</p>
            <Field label={tx('Reason')}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
            <div className="flex justify-end gap-2">
              <Button size="sm" variant="outline" onClick={() => setReversing(null)}>{tx('Cancel')}</Button>
              <Button size="sm" variant="destructive" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate({ url: `/customer-deposits/${d.id}/applications/${reversing.paymentId}/reverse`, body: { reason } })}>{tx('Reverse')}</Button>
            </div>
          </div>}
        </div>}
      </DialogContent>
    </Dialog>
  );
}
