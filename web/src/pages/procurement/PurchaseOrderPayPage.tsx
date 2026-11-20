import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { apiMessage, notify } from '@/lib/notify';
import { formatMoney } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';

interface PO {
  id: string; orderNumber: string; status: string;
  totalAmount: number; totalPaid: number | null;
  currencyCode: string; paymentType: string; paymentStatus: string | null;
}

/** The server's payable figures (GET /procurement/purchase-orders/:id/payable), in the order currency. */
interface Payable {
  currencyCode: string;
  received: number | string;
  billed: number | string;
  claimed: number | string;
  paid: number | string;
  appliedToBills: number | string;
  remaining: number | string;
  supplierOwed: number | string;
  payableNow: number | string;
  canPay: boolean;
  message: string | null;
}

interface Register { id: string; code?: string; name: string; isActive?: boolean }

const today = () => new Date().toISOString().slice(0, 10);

export function PurchaseOrderPayPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const [method, setMethod] = useState<'bank' | 'cash'>('bank');
  const [cashRegisterId, setCashRegisterId] = useState('');
  const [paidAt, setPaidAt] = useState(today());
  const [exchangeRate, setExchangeRate] = useState('');
  const baseCurrency = useAuthStore((s) => s.organization?.currencyCode ?? '');

  const po = useQuery<PO>({
    queryKey: ['purchase-order', id],
    queryFn: async () => (await api.get<PO>(`/procurement/purchase-orders/${id}`)).data,
    enabled: !!id,
  });

  // What the server will accept: receipts vouchered, less bills, claims and payments, capped by the supplier ledger.
  const payable = useQuery<Payable>({
    queryKey: ['purchase-order', id, 'payable'],
    queryFn: async () => (await api.get<Payable>(`/procurement/purchase-orders/${id}/payable`)).data,
    enabled: !!id,
  });

  const orderCurrency = (payable.data?.currencyCode ?? po.data?.currencyCode ?? '').toUpperCase();
  const foreignOrder = !!orderCurrency && !!baseCurrency && orderCurrency !== baseCurrency.toUpperCase();

  const registers = useQuery<Register[]>({
    queryKey: ['cash-registers', 'pay-po'],
    queryFn: async () =>
      (await api.get<{ data: Register[] }>('/cash-registers', { params: { pageSize: 100 } })).data?.data ?? [],
    enabled: method === 'cash',
    retry: false,
  });

  const payMut = useMutation({
    mutationFn: async () =>
      await idempotentPost(`/procurement/purchase-orders/${id}/pay`, {
        amount: amount ? Number(amount) : undefined,
        reference: reference || undefined,
        method,
        cashRegisterId: method === 'cash' && cashRegisterId ? cashRegisterId : undefined,
        paidAt: paidAt || undefined,
        // FX-3: a foreign order is paid at the stated rate (blank = the rate table); realised FX is posted.
        exchangeRate: foreignOrder && Number(exchangeRate) > 0 ? Number(exchangeRate) : undefined,
      }),
    onSuccess: () => {
      notify.success(tx('Payment recorded'));
      qc.invalidateQueries({ queryKey: ['purchase-order', id] });
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
      navigate(`/procurement/purchase-orders/${id}`);
    },
    onError: (e: any) => notify.error(apiMessage(e, tx('Failed'))),
  });

  if (po.isLoading || payable.isLoading) return <Skeleton className="h-96 w-full" />;
  if (po.error || !po.data) return <Card><CardContent className="p-8 text-center text-destructive">{tx('Not found')}</CardContent></Card>;

  const data = po.data;
  if (data.paymentType !== 'credit') {
    return (
      <Card><CardContent className="p-8 text-center">
        {tx('This is a cash purchase — payment is auto-settled on receive. No manual payment needed.')}
        <div className="mt-4"><Button onClick={() => navigate(-1)}>{tx('Go back')}</Button></div>
      </CardContent></Card>
    );
  }

  const figures = payable.data;
  const currency = figures?.currencyCode ?? data.currencyCode;
  const payableNow = Number(figures?.payableNow ?? 0);
  const canPay = !!figures?.canPay;
  const amountValue = amount ? Number(amount) : payableNow;
  const tiles: { label: string; value: number | string | undefined; tone?: string }[] = [
    { label: tx('Total'), value: data.totalAmount },
    { label: tx('Received (vouchered)'), value: figures?.received },
    { label: tx('Billed or returned'), value: figures?.billed },
    { label: tx('Settled claims'), value: figures?.claimed },
    { label: tx('Already Paid'), value: figures?.paid ?? data.totalPaid ?? 0, tone: 'text-emerald-600' },
    { label: tx('Owed to supplier (ledger)'), value: figures?.supplierOwed },
    { label: tx('Payable now'), value: payableNow, tone: 'text-amber-600' },
  ];

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <div>
          <h1 className="text-2xl font-semibold">{tx('Register Payment')}</h1>
          <p className="text-sm text-muted-foreground">{data.orderNumber}</p>
        </div>
      </div>

      {payable.error && (
        <Card><CardContent className="p-4 text-sm text-destructive">
          {apiMessage(payable.error, tx('Could not load what is payable on this order'))}
        </CardContent></Card>
      )}

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        {tiles.map((t) => (
          <Card key={t.label}>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">{t.label}</CardTitle></CardHeader>
            <CardContent className="pt-0">
              <p className={`text-xl font-bold ${t.tone ?? ''}`}>
                {t.value === undefined || t.value === null ? '—' : formatMoney(Number(t.value), currency)}
              </p>
            </CardContent>
          </Card>
        ))}
      </div>

      {figures?.message && (
        <Card><CardContent className="p-4 text-sm text-amber-700">{figures.message}</CardContent></Card>
      )}

      <Card>
        <CardHeader><CardTitle>{tx('Payment details')}</CardTitle></CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-2">
          <div>
            <label className="text-sm font-medium" htmlFor="po-pay-amount">
              {tx('Amount')} <span className="text-muted-foreground">{tx('(default: full remaining)')}</span>
            </label>
            <Input
              id="po-pay-amount"
              type="number"
              min={0.01}
              max={payableNow > 0 ? payableNow : undefined}
              step="0.01"
              className="mt-1"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder={`${payableNow.toFixed(2)}`}
            />
          </div>
          <div>
            <label className="text-sm font-medium" htmlFor="po-pay-date">{tx('Payment date')}</label>
            <Input
              id="po-pay-date"
              type="date"
              className="mt-1"
              value={paidAt}
              max={today()}
              onChange={(e) => setPaidAt(e.target.value)}
            />
          </div>
          <div>
            <label className="text-sm font-medium" htmlFor="po-pay-method">{tx('Paid from')}</label>
            <select
              id="po-pay-method"
              className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
              value={method}
              onChange={(e) => setMethod(e.target.value === 'cash' ? 'cash' : 'bank')}
            >
              <option value="bank">{tx('Bank')}</option>
              <option value="cash">{tx('Cash register')}</option>
            </select>
          </div>
          {method === 'cash' && (
            <div>
              <label className="text-sm font-medium" htmlFor="po-pay-register">{tx('Register')}</label>
              <select
                id="po-pay-register"
                className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
                value={cashRegisterId}
                onChange={(e) => setCashRegisterId(e.target.value)}
              >
                <option value="">{tx('My open register')}</option>
                {(registers.data ?? []).filter((r) => r.isActive !== false).map((r) => (
                  <option key={r.id} value={r.id}>{r.code ? `${r.code} — ${r.name}` : r.name}</option>
                ))}
              </select>
              <p className="mt-1 text-xs text-muted-foreground">
                {tx('The register must have an open session; the cash is paid out of its drawer.')}
              </p>
            </div>
          )}
          {foreignOrder && (
            <div>
              <label className="text-sm font-medium" htmlFor="po-pay-rate">
                {tx('Exchange rate (optional)')} <span className="text-muted-foreground">1 {orderCurrency} = ? {baseCurrency}</span>
              </label>
              <Input
                id="po-pay-rate"
                type="number"
                min={0}
                step="any"
                className="mt-1"
                value={exchangeRate}
                onChange={(e) => setExchangeRate(e.target.value)}
                placeholder={tx('rate table')}
              />
              <p className="mt-1 text-xs text-muted-foreground">
                {tx('Paid in {{v1}}; the difference to the bill rate is posted as realised FX gain or loss.', { v1: orderCurrency })}
              </p>
            </div>
          )}
          <div>
            <label className="text-sm font-medium" htmlFor="po-pay-reference">{tx('Reference')}</label>
            <Input
              id="po-pay-reference"
              className="mt-1"
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder={tx('Optional (e.g. receipt #)')}
            />
          </div>
        </CardContent>
      </Card>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Cancel')}</Button>
        <Button
          onClick={() => payMut.mutate()}
          disabled={payMut.isPending || !canPay || !(amountValue > 0)}
        >
          <Save className="mr-2 h-4 w-4" />
          {payMut.isPending ? tx('Recording…') : tx('Pay {{v1}}', { v1: formatMoney(amountValue, currency) })}
        </Button>
      </div>
    </div>
  );
}
