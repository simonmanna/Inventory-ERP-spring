/**
 * ADR-S083 O5 — field sales: take an order, a quote or a payment from a customer, with or without a connection.
 * Everything comes from the synced scopes (customers, credit snapshot, price lists, payment terms, open invoices and
 * orders) and is recorded as sync ops on this registered device. The server reprices orders and quotes (differences
 * beyond the tolerance go to review), runs the credit check, and applies payments to what is still open.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Loader2, Search, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { syncEngineFor } from '@/lib/offline/web';
import { offlineWindow } from '@/lib/offline/session';
import { DEFAULT_POLICY, type DeviceInfo, type OfflinePolicy, type SyncOp } from '@/lib/offline/types';
import {
  autoAllocate, capView, creditView, describeSale, estimatePrice, lineFor, lineTotal, orderOp, orderTotal, paymentOp,
  quoteOp, SALES_OP_TYPES, unsyncedOrders, type CustomerCredit, type OpDraft, type OpenInvoice, type OrderLine,
  type PriceList,
} from '@/features/sales-field/offline-sales';

type Row = Record<string, unknown>;
interface Customer { id: string; code?: string; name: string; phone?: string; deletedAt?: string | null }
interface PaymentTerm { id: string; name: string }
interface InvoiceRow extends OpenInvoice { salespersonId?: string | null }
interface OpenOrder { id: string; number: string; status: string; onHold?: boolean; holdReason?: string | null;
  partnerId: string; partnerName?: string; ownerId?: string | null; total: number; requestedDate?: string | null }

const newId = () => crypto.randomUUID();
const today = () => new Date().toISOString().slice(0, 10);
const money = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString(undefined, { maximumFractionDigits: 2 }));

function useFieldData(org: string | undefined, me: string | undefined) {
  const [device, setDevice] = useState<(DeviceInfo & { branchId?: string | null }) | null | undefined>(undefined);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [credit, setCredit] = useState<Record<string, CustomerCredit>>({});
  const [products, setProducts] = useState<Row[]>([]);
  const [priceLists, setPriceLists] = useState<PriceList[]>([]);
  const [terms, setTerms] = useState<PaymentTerm[]>([]);
  const [invoices, setInvoices] = useState<OpenInvoice[]>([]);
  const [orders, setOrders] = useState<OpenOrder[]>([]);
  const [ops, setOps] = useState<SyncOp[]>([]);
  const [policy, setPolicy] = useState<OfflinePolicy>(DEFAULT_POLICY);
  const [lastSyncAt, setLastSyncAt] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (!org) return;
    const engine = syncEngineFor(org);
    const d = await engine.device().catch(() => undefined);
    setDevice(d ?? null);
    if (!d) return;
    const st = engine.store;
    const [cs, cc, ps, pls, pts, inv, oo, all, synced, pol] = await Promise.all([
      st.getRows<Customer>('partners'), st.getRows<CustomerCredit>('customerCredit'), st.getRows<Row>('products'),
      st.getRows<PriceList>('priceLists'), st.getRows<PaymentTerm>('paymentTerms'), st.getRows<OpenInvoice>('openInvoices'),
      st.getRows<OpenOrder>('openSalesOrders'), st.listOps(), st.getMeta<number>('lastSyncAt'),
      st.getMeta<OfflinePolicy>('offlinePolicy'),
    ]);
    setCustomers(cs.filter((c) => !c.deletedAt).sort((a, b) => a.name.localeCompare(b.name)));
    setCredit(Object.fromEntries(cc.map((c) => [c.id, c])));
    setProducts(ps.filter((p) => !p.deletedAt && p.isActive !== false && p.canBeSold !== false));
    setPriceLists(pls);
    setTerms(pts);
    setInvoices(inv);
    setOrders(oo.filter((o) => !me || o.ownerId === me));
    setOps(all.filter((o) => SALES_OP_TYPES.includes(o.type)).sort((a, b) => b.deviceSeq - a.deviceSeq));
    setPolicy({ ...DEFAULT_POLICY, ...(pol ?? {}) });
    setLastSyncAt(synced ?? null);
  }, [org, me]);

  useEffect(() => {
    if (!org) return;
    void load();
    return syncEngineFor(org).subscribe(() => void load());
  }, [org, load]);

  return { device, customers, credit, products, priceLists, terms, invoices, orders, ops, policy, lastSyncAt, me,
    reload: load };
}

type Data = ReturnType<typeof useFieldData>;

function CustomerPicker({ data, value, onChange }: { data: Data; value: string; onChange: (id: string) => void }) {
  const [q, setQ] = useState('');
  const matches = useMemo(() => {
    const s = q.trim().toLowerCase();
    return (s ? data.customers.filter((c) => c.name.toLowerCase().includes(s) || (c.code ?? '').toLowerCase().includes(s)
      || (c.phone ?? '').includes(s)) : data.customers).slice(0, 50);
  }, [q, data.customers]);
  const chosen = data.customers.find((c) => c.id === value);
  const cr = value ? data.credit[value] : undefined;
  return (
    <div className="grid gap-1.5">
      <Label>{tx('Customer')}</Label>
      {chosen ? (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-2">
          <div>
            <div className="font-medium">{chosen.name}</div>
            {cr && (
              <div className="text-xs text-muted-foreground">
                {cr.creditHold ? tx('On credit hold') : (cr.limit ?? 0) > 0
                  ? tx('Limit {{l}} · headroom {{h}} (as of sync)', { l: money(cr.limit), h: money(cr.headroom) })
                  : tx('No credit limit')}
              </div>
            )}
          </div>
          <Button type="button" variant="outline" size="sm" onClick={() => onChange('')}>{tx('Change')}</Button>
        </div>
      ) : (
        <>
          <div className="relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input className="h-12 pl-9" placeholder={tx('Search customers by name, code or phone')} value={q}
              onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="max-h-56 overflow-y-auto divide-y rounded-md border">
            {matches.map((c) => (
              <button key={c.id} type="button" className="block w-full p-2 text-left hover:bg-muted" onClick={() => onChange(c.id)}>
                {c.name} <span className="text-xs text-muted-foreground">{c.code ?? ''}</span>
              </button>
            ))}
            {!matches.length && <p className="p-2 text-sm text-muted-foreground">{tx('No customer on this device matches.')}</p>}
          </div>
        </>
      )}
    </div>
  );
}

/** Product search + lines with the device's price estimate. */
function OrderLines({ data, partnerId, lines, setLines }: { data: Data; partnerId: string; lines: OrderLine[];
    setLines: (l: OrderLine[]) => void }) {
  const [q, setQ] = useState('');
  const matches = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!s) return [];
    return data.products.filter((p) => String(p.name ?? '').toLowerCase().includes(s) || String(p.code ?? '').toLowerCase()
      .includes(s) || String(p.barcode ?? '') === s || String(p.sku ?? '').toLowerCase() === s).slice(0, 20);
  }, [q, data.products]);
  const price = (p: Row, qty: number) => estimatePrice({ product: p, quantity: qty, customer: data.credit[partnerId],
    priceLists: data.priceLists, branchId: data.device?.branchId ?? null, today: today() });
  const add = (p: Row) => {
    const i = lines.findIndex((l) => l.productId === p.id);
    if (i >= 0) {
      setQty(i, lines[i].quantity + 1);
    } else {
      setLines([...lines, lineFor(p, 1, price(p, 1))]);
    }
    setQ('');
  };
  const setQty = (i: number, qty: number) => {
    const p = data.products.find((x) => x.id === lines[i].productId);
    setLines(lines.map((l, j) => (j === i ? { ...(p ? lineFor(p, qty, price(p, qty)) : l), quantity: qty,
      manualDiscountPercent: l.manualDiscountPercent } : l)));
  };
  return (
    <div className="space-y-2">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input className="h-12 pl-9" placeholder={tx('Add an item: name, code, SKU or barcode')} value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && matches[0]) { e.preventDefault(); add(matches[0]); } }} />
      </div>
      {matches.length > 0 && (
        <div className="max-h-48 overflow-y-auto divide-y rounded-md border">
          {matches.map((p) => (
            <button key={String(p.id)} type="button" className="flex w-full justify-between p-2 text-left hover:bg-muted" onClick={() => add(p)}>
              <span>{String(p.name)}</span><span className="text-muted-foreground">{money(price(p, 1).unitPrice)}</span>
            </button>
          ))}
        </div>
      )}
      <div className="divide-y rounded-md border">
        {lines.map((l, i) => (
          <div key={l.productId} className="flex flex-wrap items-center gap-2 p-2">
            <div className="min-w-0 flex-1">
              <div className="truncate">{l.name}</div>
              <div className="text-xs text-muted-foreground">
                {money(l.unitPrice)}{l.ruleDiscountPercent ? ` −${l.ruleDiscountPercent}%` : ''}{l.taxRate && !l.taxInclusive ? ` + ${l.taxRate}% ${tx('tax')}` : ''}
              </div>
            </div>
            <Input type="number" min={0} inputMode="decimal" className="h-10 w-20" value={l.quantity} aria-label={tx('Quantity')}
              onChange={(e) => setQty(i, Number(e.target.value))} />
            <Input type="number" min={0} max={100} inputMode="decimal" className="h-10 w-20" placeholder={tx('Disc %')}
              aria-label={tx('Manual discount %')} value={l.manualDiscountPercent ?? ''}
              onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, manualDiscountPercent: e.target.value === ''
                ? undefined : Number(e.target.value) } : x)))} />
            <span className="w-24 text-right">{money(lineTotal(l))}</span>
            <Button type="button" variant="ghost" size="sm" onClick={() => setLines(lines.filter((_, j) => j !== i))}>
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        ))}
        {!lines.length && <p className="p-2 text-sm text-muted-foreground">{tx('No items yet.')}</p>}
      </div>
      {lines.length > 0 && (
        <div className="text-right text-lg font-semibold">{tx('Estimated total')}: {money(orderTotal(lines))}</div>
      )}
      <p className="text-xs text-muted-foreground">{tx('Prices are an estimate from this device. The server prices the order when it syncs.')}</p>
    </div>
  );
}

export function FieldSalesPage() {
  const org = useAuthStore((s) => s.organization?.id);
  const me = useAuthStore((s) => s.user?.id);
  const data = useFieldData(org, me);
  const [busy, setBusy] = useState(false);

  const record = useCallback(async (draft: OpDraft): Promise<boolean> => {
    if (!org || !me) return false;
    const engine = syncEngineFor(org);
    const policy = { ...DEFAULT_POLICY, ...(await engine.store.getMeta<OfflinePolicy>('offlinePolicy')) };
    const w = offlineWindow((await engine.store.getMeta<number>('lastSyncAt')) ?? null, policy, Date.now());
    if (!w.writable) {
      toast.error(w.reason === 'clock_rollback' ? tx('This device clock is behind its last sync. Fix the date and time.')
        : w.reason === 'never_synced' ? tx('This device has not synced yet. Connect once first.')
          : tx('This device has been offline too long. Connect to sync first.'));
      return false;
    }
    setBusy(true);
    try {
      await engine.enqueue({ type: draft.type, actorUserId: me, payload: draft.payload });
      void engine.sync().catch(() => undefined);
      toast.success(tx('Recorded — it reaches the office when this device syncs'));
      await data.reload();
      return true;
    } finally {
      setBusy(false);
    }
  }, [org, me, data]);

  if (data.device === undefined) return <div className="p-6"><Loader2 className="h-6 w-6 animate-spin" /></div>;
  if (data.device === null) {
    return (
      <div className="p-6 max-w-xl space-y-3">
        <h1 className="text-2xl font-semibold">{tx('Field sales')}</h1>
        <p>{tx('This browser is not registered as an offline device yet.')}</p>
        <Link className="underline" to="/settings/devices">{tx('Register it under Offline devices → This browser')}</Link>
      </div>
    );
  }

  return (
    <div className="space-y-4 p-4 md:p-6 max-w-4xl">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-semibold">{tx('Field sales')}</h1>
        <span className="text-sm text-muted-foreground">
          {data.device.name} · {data.lastSyncAt ? tx('synced {{t}}', { t: new Date(data.lastSyncAt).toLocaleString() }) : tx('not synced yet')}
        </span>
      </div>
      <Tabs defaultValue="order">
        <TabsList className="flex flex-wrap h-auto">
          <TabsTrigger value="order">{tx('New order')}</TabsTrigger>
          <TabsTrigger value="quote">{tx('Quote')}</TabsTrigger>
          <TabsTrigger value="payment">{tx('Collect payment')}</TabsTrigger>
          <TabsTrigger value="mine">{tx('My orders & invoices')}</TabsTrigger>
        </TabsList>
        <TabsContent value="order"><NewOrder data={data} record={record} busy={busy} /></TabsContent>
        <TabsContent value="quote"><NewQuote data={data} record={record} busy={busy} /></TabsContent>
        <TabsContent value="payment"><CollectPayment data={data} record={record} busy={busy} /></TabsContent>
        <TabsContent value="mine"><Mine data={data} /></TabsContent>
      </Tabs>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Recent work on this device')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          {data.ops.length === 0 && <p className="text-sm text-muted-foreground">{tx('Nothing recorded yet.')}</p>}
          {data.ops.slice(0, 25).map((o) => {
            const d = describeSale(o);
            const tone = { ok: 'text-emerald-700', wait: 'text-amber-700', warn: 'text-orange-700', bad: 'text-red-700' }[d.tone];
            const number = (o.result?.mapping?.orderNumber ?? o.result?.mapping?.quotationNumber ?? o.result?.mapping?.paymentNumber) as string | undefined;
            const who = data.customers.find((c) => c.id === o.payload.partnerId)?.name;
            return (
              <div key={o.opId} className="flex flex-wrap justify-between gap-2 border-b pb-1 text-sm">
                <span>{number ?? o.type} · {who ?? ''} · {new Date(o.occurredAt).toLocaleString()}</span>
                <span className={tone}>{tx(d.label)}{d.detail ? ` — ${d.detail}` : ''}</span>
              </div>
            );
          })}
        </CardContent>
      </Card>
    </div>
  );
}

interface TabProps { data: Data; record: (d: OpDraft) => Promise<boolean>; busy: boolean }

function submit(fn: () => OpDraft, record: TabProps['record'], after: () => void) {
  let draft: OpDraft;
  try {
    draft = fn();
  } catch (e) {
    toast.error((e as Error).message);
    return;
  }
  void record(draft).then((ok) => ok && after());
}

function NewOrder({ data, record, busy }: TabProps) {
  const [partnerId, setPartnerId] = useState('');
  const [lines, setLines] = useState<OrderLine[]>([]);
  const [paymentMode, setPaymentMode] = useState<'credit' | 'cash'>('credit');
  const [termId, setTermId] = useState('');
  const [requestedDate, setRequestedDate] = useState('');
  const [customerRef, setCustomerRef] = useState('');
  const [notes, setNotes] = useState('');
  const total = orderTotal(lines);
  const unsynced = unsyncedOrders(data.ops);
  const credit = creditView(data.credit[partnerId], paymentMode === 'cash' ? 0 : total, unsynced.byCustomer[partnerId] ?? 0);
  const cap = capView(data.policy.offlineOrderCap, unsynced.total, total);
  useEffect(() => {
    setTermId(data.credit[partnerId]?.paymentTermId ?? '');
  }, [partnerId, data.credit]);
  return (
    <Card><CardContent className="space-y-3 pt-4">
      <CustomerPicker data={data} value={partnerId} onChange={setPartnerId} />
      {partnerId && (
        <>
          <OrderLines data={data} partnerId={partnerId} lines={lines} setLines={setLines} />
          <div className="grid gap-3 md:grid-cols-2">
            <div className="grid gap-1.5"><Label>{tx('Payment')}</Label>
              <select className="h-12 rounded-md border bg-background px-3" value={paymentMode} onChange={(e) => setPaymentMode(e.target.value as 'credit' | 'cash')}>
                <option value="credit">{tx('On account')}</option><option value="cash">{tx('Cash')}</option>
              </select></div>
            <div className="grid gap-1.5"><Label>{tx('Payment term')}</Label>
              <select className="h-12 rounded-md border bg-background px-3" value={termId} onChange={(e) => setTermId(e.target.value)}>
                <option value="">{tx('Customer default')}</option>
                {data.terms.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select></div>
            <div className="grid gap-1.5"><Label>{tx('Requested date')}</Label>
              <Input type="date" className="h-12" value={requestedDate} onChange={(e) => setRequestedDate(e.target.value)} /></div>
            <div className="grid gap-1.5"><Label>{tx('Customer reference')}</Label>
              <Input className="h-12" value={customerRef} onChange={(e) => setCustomerRef(e.target.value)} /></div>
          </div>
          <div className="grid gap-1.5"><Label>{tx('Notes')}</Label>
            <Input className="h-12" value={notes} onChange={(e) => setNotes(e.target.value)} /></div>
          {credit.state !== 'ok' && (
            <p className={`rounded-md p-2 text-sm ${credit.state === 'blocked' ? 'bg-red-50 text-red-900 dark:bg-red-950 dark:text-red-100'
              : 'bg-amber-50 text-amber-900 dark:bg-amber-950 dark:text-amber-100'}`}>{tx(credit.message ?? '')}</p>
          )}
          {!cap.ok && <p className="rounded-md bg-red-50 p-2 text-sm text-red-900 dark:bg-red-950 dark:text-red-100">{tx(cap.message ?? '')}</p>}
          <Button type="button" className="h-12 w-full" disabled={busy || !cap.ok || credit.state === 'blocked'}
            onClick={() => submit(() => orderOp({ clientId: newId(), partnerId, lines, paymentMode, paymentTermId: termId,
              requestedDate, customerRef, notes }), record, () => { setLines([]); setNotes(''); setCustomerRef(''); })}>
            {tx('Record order')}
          </Button>
        </>
      )}
    </CardContent></Card>
  );
}

function NewQuote({ data, record, busy }: TabProps) {
  const [partnerId, setPartnerId] = useState('');
  const [lines, setLines] = useState<OrderLine[]>([]);
  const [validUntil, setValidUntil] = useState('');
  const [notes, setNotes] = useState('');
  return (
    <Card><CardContent className="space-y-3 pt-4">
      <CustomerPicker data={data} value={partnerId} onChange={setPartnerId} />
      {partnerId && (
        <>
          <OrderLines data={data} partnerId={partnerId} lines={lines} setLines={setLines} />
          <div className="grid gap-3 md:grid-cols-2">
            <div className="grid gap-1.5"><Label>{tx('Valid until')}</Label>
              <Input type="date" className="h-12" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} /></div>
            <div className="grid gap-1.5"><Label>{tx('Notes')}</Label>
              <Input className="h-12" value={notes} onChange={(e) => setNotes(e.target.value)} /></div>
          </div>
          <Button type="button" className="h-12 w-full" disabled={busy} onClick={() => submit(() => quoteOp({ clientId: newId(),
            partnerId, lines, validUntil, notes }), record, () => { setLines([]); setNotes(''); })}>
            {tx('Record quote')}
          </Button>
        </>
      )}
    </CardContent></Card>
  );
}

function CollectPayment({ data, record, busy }: TabProps) {
  const [partnerId, setPartnerId] = useState('');
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState('cash');
  const [reference, setReference] = useState('');
  const [alloc, setAlloc] = useState<Record<string, number>>({});
  const open = data.invoices.filter((i) => i.partnerId === partnerId);
  const amt = Number(amount);
  useEffect(() => {
    setAlloc(amt > 0 ? autoAllocate(amt, open) : {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [amount, partnerId]);
  const allocated = Object.values(alloc).reduce((s, a) => s + (a > 0 ? a : 0), 0);
  return (
    <Card><CardContent className="space-y-3 pt-4">
      <CustomerPicker data={data} value={partnerId} onChange={(id) => { setPartnerId(id); setAlloc({}); }} />
      {partnerId && (
        <>
          <div className="grid gap-3 md:grid-cols-3">
            <div className="grid gap-1.5"><Label>{tx('Amount received')}</Label>
              <Input type="number" min={0} inputMode="decimal" className="h-12 text-lg" value={amount} onChange={(e) => setAmount(e.target.value)} /></div>
            <div className="grid gap-1.5"><Label>{tx('Method')}</Label>
              <select className="h-12 rounded-md border bg-background px-3" value={method} onChange={(e) => setMethod(e.target.value)}>
                <option value="cash">{tx('Cash')}</option><option value="mobile_money">{tx('Mobile money')}</option>
                <option value="cheque">{tx('Cheque')}</option><option value="bank">{tx('Bank transfer')}</option>
                <option value="card">{tx('Card')}</option>
              </select></div>
            <div className="grid gap-1.5"><Label>{tx('Reference')}</Label>
              <Input className="h-12" value={reference} onChange={(e) => setReference(e.target.value)} placeholder={tx('Receipt or cheque no.')} /></div>
          </div>
          <div className="divide-y rounded-md border">
            {open.map((i) => (
              <div key={i.id} className="flex flex-wrap items-center gap-2 p-2">
                <div className="flex-1 min-w-0">
                  <div>{i.documentNumber}</div>
                  <div className="text-xs text-muted-foreground">{tx('Open {{a}}', { a: money(i.amountResidual) })}{i.dueDate ? ` · ${tx('due')} ${String(i.dueDate).slice(0, 10)}` : ''}</div>
                </div>
                <Input type="number" min={0} className="h-10 w-28" value={alloc[i.id] ?? ''} aria-label={tx('Allocate')}
                  onChange={(e) => setAlloc((a) => ({ ...a, [i.id]: Number(e.target.value) }))} />
              </div>
            ))}
            {!open.length && <p className="p-2 text-sm text-muted-foreground">{tx('No open invoices for this customer on this device; the payment goes on account.')}</p>}
          </div>
          {amt > 0 && (
            <p className="text-sm text-muted-foreground">{tx('Allocated {{a}} · on account {{b}}', { a: money(allocated), b: money(Math.max(0, amt - allocated)) })}</p>
          )}
          <p className="text-xs text-muted-foreground">{tx('The amount received is recorded exactly. If an invoice was paid meanwhile, the rest stays on the customer’s account for the office to allocate.')}</p>
          <Button type="button" className="h-12 w-full" disabled={busy} onClick={() => submit(() => paymentOp({ clientId: newId(),
            partnerId, amount: amt, paymentMethod: method, reference, allocations: alloc }), record,
          () => { setAmount(''); setReference(''); setAlloc({}); })}>
            {tx('Record payment')}
          </Button>
        </>
      )}
    </CardContent></Card>
  );
}

function Mine({ data }: { data: Data }) {
  // The rep's own: invoices they sold, or of customers they look after; otherwise every open invoice of the branch.
  const own = (data.invoices as InvoiceRow[]).filter((i) => i.salespersonId === data.me
    || data.credit[i.partnerId]?.salespersonId === data.me);
  const mineInvoices = own.length ? own : data.invoices;
  const byCustomer = (id: string) => data.customers.find((c) => c.id === id)?.name ?? '';
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card><CardHeader><CardTitle className="text-base">{tx('My open orders')}</CardTitle></CardHeader>
        <CardContent className="divide-y p-0">
          {data.orders.map((o) => (
            <div key={o.id} className="flex justify-between gap-2 p-2 text-sm">
              <span>{o.number} · {o.partnerName ?? byCustomer(o.partnerId)}</span>
              <span>{money(o.total)} · {o.onHold ? tx('On hold') : o.status}</span>
            </div>
          ))}
          {!data.orders.length && <p className="p-2 text-sm text-muted-foreground">{tx('No open orders of yours on this device.')}</p>}
        </CardContent></Card>
      <Card><CardHeader><CardTitle className="text-base">{tx('Open invoices')}</CardTitle></CardHeader>
        <CardContent className="divide-y p-0">
          {mineInvoices.slice(0, 100).map((i) => (
            <div key={i.id} className="flex justify-between gap-2 p-2 text-sm">
              <span>{i.documentNumber} · {byCustomer(i.partnerId)}</span>
              <span>{money(i.amountResidual)}{i.dueDate ? ` · ${String(i.dueDate).slice(0, 10)}` : ''}</span>
            </div>
          ))}
          {!mineInvoices.length && <p className="p-2 text-sm text-muted-foreground">{tx('No open invoices on this device.')}</p>}
        </CardContent></Card>
    </div>
  );
}
