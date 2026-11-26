import { useState } from 'react';
import { idempotentPost } from '@/lib/idempotent-request';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { FileText, Printer, Wallet } from 'lucide-react';
import { printDepositReceipt } from '@/features/sales/deposits';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { SalesOrder } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';
import { DepositCurrencyFields } from './DepositCurrencyFields';
import { depositCurrencyBody, depositCurrencyIncomplete, useBaseCurrency, type DepositCurrency } from '@/features/sales/deposits';

interface Billing {
  /** Phase A A4: historical Fleet goods invoices awaiting a two-person review block goods invoicing. */
  blocked?: boolean;
  legacyFleetInvoices?: Array<{ deliveryOrderCode: string; documentId: string; documentNumber: string | null; status: string }>;
  lines: Array<{ lineId: string; description: string; policy: string; quantity: number; amount: number }>;
  amount: number;
  deposits: Array<{ id: string; number: string; amount: number; available: number; status: string; source: string; receivedDate: string; paymentMethod: string | null; currencyCode?: string | null }>;
  invoices: Array<{ id: string; documentNumber: string; status: string; totalAmount: number; amountResidual: number; downPayment: boolean }>;
}


/** Invoicing, deposits / down payments and mobile-money collection of a sales order (ADR-S011 S6). */
export function BillingPanel({ order }: { order: SalesOrder }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const has = useAuthStore((s) => s.hasPermission);
  const { data } = useQuery({ queryKey: ['sales', 'billing', order.id], queryFn: async () => (await api.get<Billing>(`/sales/orders/${order.id}/billing`)).data });
  // Only gateways the server reports as connected (the test gateway is off outside tests and demos).
  const { data: gateways } = useQuery({
    queryKey: ['mobile-money', 'providers'],
    queryFn: async () => (await api.get<Array<{ code: string; configured: boolean }>>('/payments/mobile-money/providers')).data,
  });
  const liveGateways = (gateways ?? []).filter((g) => g.configured);
  const [gateway, setGateway] = useState('');
  const refresh = () => qc.invalidateQueries({ queryKey: ['sales'] });
  const err = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));
  const invoice = useMutation({
    mutationFn: async (body: Record<string, unknown>) => idempotentPost<{ id: string; documentNumber: string }>(`/sales/orders/${order.id}/invoices`, body),
    onSuccess: (d) => { refresh(); toast.success(tx('Draft invoice') + ' ' + d.documentNumber); navigate(`/invoices/${d.id}`); },
    onError: err,
  });
  const deposit = useMutation({
    mutationFn: async (body: Record<string, unknown>) => idempotentPost(`/sales/orders/${order.id}/deposits`, body),
    onSuccess: () => { refresh(); setDialog(null); toast.success(tx('Deposit recorded')); }, onError: err,
  });
  const momo = useMutation({
    mutationFn: async (body: Record<string, unknown>) => {
      const r = await idempotentPost<{ id: string }>('/payments/mobile-money', body);
      return (await api.post<{ status: string }>(`/payments/mobile-money/${r.id}/refresh`, {})).data;
    },
    onSuccess: (r) => { refresh(); setDialog(null); toast[r.status === 'succeeded' ? 'success' : 'message'](tx('Mobile money') + ': ' + r.status); },
    onError: err,
  });
  const apply = useMutation({
    mutationFn: async ({ depositId, documentId }: { depositId: string; documentId: string }) => idempotentPost(`/customer-deposits/${depositId}/apply`, { documentId }),
    onSuccess: () => { refresh(); toast.success(tx('Deposit applied')); }, onError: err,
  });
  const settle = useMutation({
    mutationFn: async ({ url, body }: { url: string; body: Record<string, unknown> }) => idempotentPost(url, body),
    onSuccess: () => { refresh(); setRefunding(null); setVoiding(null); setReason(''); toast.success(tx('Done')); }, onError: err,
  });
  const [refunding, setRefunding] = useState<Billing['deposits'][number] | null>(null);
  const [voiding, setVoiding] = useState<Billing['deposits'][number] | null>(null);
  const [reason, setReason] = useState('');
  const [dialog, setDialog] = useState<'deposit' | 'down' | null>(null);
  const [amount, setAmount] = useState(0);
  const [percent, setPercent] = useState(30);
  const [method, setMethod] = useState('bank');
  const [phone, setPhone] = useState('');
  const [target, setTarget] = useState<Record<string, string>>({});
  const base = useBaseCurrency();
  const [fx, setFx] = useState<DepositCurrency>({ currency: '', accountId: '', rate: '' });
  if (!data) return null;
  // a deposit for a foreign-currency order defaults to the order's currency, so it can settle the order's invoices
  const orderFx = (): DepositCurrency => ({ currency: order.currencyCode && order.currencyCode !== base ? order.currencyCode : '', accountId: '', rate: '' });
  const canInvoice = has(PERMISSIONS.sales.invoiceCreate);
  const canDeposit = has(PERMISSIONS.sales.depositRecord);
  const canRefund = has(PERMISSIONS.sales.depositRefund);
  const postedOpen = data.invoices.filter((i) => i.status === 'posted' && Number(i.amountResidual) > 0 && !i.downPayment);
  return (
    <div data-tour="sales.order.billing" className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <h2 className="mb-2 font-semibold">{tx('Billing')}</h2>
      {data.blocked && (
        <p className="mb-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
          {tx('Invoicing is blocked: historical Fleet invoices await reconciliation')} —{' '}
          {(data.legacyFleetInvoices ?? []).map((i) => `${i.documentNumber ?? i.documentId} (${i.deliveryOrderCode})`).join(', ')}.{' '}
          {tx('A reviewer matches them to the order lines and a second person approves (sales:billing:override).')}
        </p>
      )}
      {data.lines.length > 0 ? (
        <p className="text-xs text-muted-foreground">{tx('To invoice')}: {data.lines.map((l) => `${l.description} ${l.quantity}`).join(' · ')} — <strong>{money(data.amount, order.currencyCode)}</strong></p>
      ) : <p className="text-xs text-muted-foreground">{tx('Nothing to invoice right now.')}</p>}
      <div className="mt-2 flex flex-wrap gap-2">
        {canInvoice && data.lines.length > 0 && !data.blocked && <Button data-tour="sales.order.invoice" size="sm" disabled={invoice.isPending} onClick={() => invoice.mutate({ mode: 'regular' })}><FileText className="mr-1 h-3.5 w-3.5" />{tx('Create invoice')}</Button>}
        {canInvoice && <Button size="sm" variant="outline" onClick={() => setDialog('down')}>{tx('Down payment invoice')}</Button>}
        {canDeposit && <Button size="sm" variant="outline" onClick={() => { setAmount(0); setFx(orderFx()); setDialog('deposit'); }}><Wallet className="mr-1 h-3.5 w-3.5" />{tx('Record deposit')}</Button>}
      </div>
      {data.invoices.length > 0 && <ul className="mt-3 space-y-1 text-xs">
        {data.invoices.map((i) => (
          <li key={i.id}><button className="text-primary hover:underline" onClick={() => navigate(`/invoices/${i.id}`)}>{i.documentNumber}</button>{i.downPayment && ` · ${tx('down payment')}`} · {i.status} · {money(i.totalAmount)} · {tx('open')} {money(i.amountResidual)}</li>
        ))}
      </ul>}
      {data.deposits.length > 0 && <div className="mt-3">
        <p className="text-xs font-medium">{tx('Deposits')}</p>
        <ul className="space-y-1 text-xs">
          {data.deposits.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center gap-2">
              <span>{d.number} · {date(d.receivedDate)} · {money(d.amount, d.currencyCode || undefined)} · {tx('available')} {money(d.available, d.currencyCode || undefined)} · {d.status}</span>
              <Button size="sm" variant="ghost" title={tx('Print receipt')} onClick={() => void printDepositReceipt(d)}><Printer className="h-3.5 w-3.5" /></Button>
              {d.status === 'open' && Number(d.available) > 0 && postedOpen.length > 0 && canDeposit && <>
                <select className="h-7 rounded border bg-background px-1" value={target[d.id] ?? ''} onChange={(e) => setTarget({ ...target, [d.id]: e.target.value })}>
                  <option value="">{tx('Apply to…')}</option>{postedOpen.map((i) => <option key={i.id} value={i.id}>{i.documentNumber}</option>)}
                </select>
                <Button size="sm" variant="ghost" disabled={!target[d.id] || apply.isPending} onClick={() => apply.mutate({ depositId: d.id, documentId: target[d.id] })}>{tx('Apply')}</Button>
              </>}
              {d.status === 'open' && Number(d.available) > 0 && d.source !== 'cash_sale' && canRefund && <Button size="sm" variant="ghost" onClick={() => { setAmount(Number(d.available)); setMethod('bank'); setFx({ currency: d.currencyCode ?? '', accountId: '', rate: '' }); setRefunding(d); }}>{tx('Refund')}</Button>}
              {d.source === 'receipt' && d.status === 'open' && Number(d.available) === Number(d.amount) && canRefund && <Button size="sm" variant="ghost" onClick={() => setVoiding(d)}>{tx('Void')}</Button>}
            </li>
          ))}
        </ul>
      </div>}
      <Dialog open={refunding !== null || voiding !== null} onOpenChange={(v) => { if (!v) { setRefunding(null); setVoiding(null); } }}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{refunding ? `${tx('Refund')} ${refunding.number}` : `${tx('Void')} ${voiding?.number ?? ''}`}</DialogTitle></DialogHeader>
          {refunding && <div className="grid gap-3">
            <p className="text-xs text-muted-foreground">{tx('Available')}: {money(refunding.available, refunding.currencyCode || undefined)}</p>
            <Field label={tx('Amount')}><Input type="number" value={amount} onChange={(e) => setAmount(Number(e.target.value))} /></Field>
            <Field label={tx('Method')}>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={method} onChange={(e) => setMethod(e.target.value)}>
                <option value="bank">{tx('Bank transfer')}</option><option value="cash">{tx('Cash')}</option><option value="cheque">{tx('Cheque')}</option><option value="mobile_money">{tx('Mobile money (recorded)')}</option>
              </select></Field>
            {refunding.currencyCode && <DepositCurrencyFields value={fx} onChange={setFx} lockCurrency />}
          </div>}
          <Field label={tx('Reason')}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => { setRefunding(null); setVoiding(null); }}>{tx('Cancel')}</Button>
            <Button variant={voiding ? 'destructive' : 'default'} disabled={settle.isPending || (voiding ? !reason.trim() : !(amount > 0) || depositCurrencyIncomplete(fx, base))} onClick={() => {
              if (refunding) settle.mutate({ url: `/customer-deposits/${refunding.id}/refund`, body: { amount, paymentMethod: method === 'momo_request' ? 'mobile_money' : method, reason: reason || undefined, accountId: fx.accountId || undefined, exchangeRate: Number(fx.rate) > 0 ? Number(fx.rate) : undefined } });
              else if (voiding) settle.mutate({ url: `/customer-deposits/${voiding.id}/void`, body: { reason } });
            }}>{tx('Confirm')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={dialog !== null} onOpenChange={(v) => !v && setDialog(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{dialog === 'down' ? tx('Down payment invoice') : tx('Record deposit')}</DialogTitle></DialogHeader>
          {dialog === 'down' ? (
            <Field label={tx('Percent of the order total')}><Input type="number" value={percent} onChange={(e) => setPercent(Number(e.target.value))} /></Field>
          ) : <div className="grid gap-3">
            <Field label={tx('Amount')}><Input type="number" value={amount} onChange={(e) => setAmount(Number(e.target.value))} /></Field>
            <Field label={tx('Method')}>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={method} onChange={(e) => setMethod(e.target.value)}>
                <option value="bank">{tx('Bank transfer')}</option><option value="cash">{tx('Cash')}</option><option value="cheque">{tx('Cheque')}</option>
                <option value="mobile_money">{tx('Mobile money (recorded)')}</option>{liveGateways.length > 0 && <option value="momo_request">{tx('Mobile money — request on phone')}</option>}
              </select></Field>
            {method !== 'momo_request' && <DepositCurrencyFields value={fx} onChange={setFx} />}
            {method === 'momo_request' && liveGateways.length > 1 && <Field label={tx('Provider')}>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={gateway || liveGateways[0].code} onChange={(e) => setGateway(e.target.value)}>
                {liveGateways.map((g) => <option key={g.code} value={g.code}>{g.code}</option>)}
              </select></Field>}
            {method === 'momo_request' && <Field label={tx('Customer phone')}><Input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+2567…" /></Field>}
          </div>}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>{tx('Cancel')}</Button>
            <Button disabled={invoice.isPending || deposit.isPending || momo.isPending
              || (dialog === 'deposit' && method === 'momo_request' && liveGateways.length === 0)
              || (dialog === 'deposit' && method !== 'momo_request' && depositCurrencyIncomplete(fx, base))} onClick={() => {
              if (dialog === 'down') { invoice.mutate({ mode: 'down_payment', percent }); setDialog(null); }
              else if (method === 'momo_request') momo.mutate({ provider: gateway || liveGateways[0]?.code, partnerId: order.partnerId, phone, amount, purpose: 'deposit', salesOrderId: order.id });
              else deposit.mutate({ amount, paymentMethod: method, ...depositCurrencyBody(fx, base) });
            }}>{tx('Confirm')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
