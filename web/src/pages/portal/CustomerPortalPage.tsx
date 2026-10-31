import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { getApiBaseUrl } from '@/lib/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';

// Customer portal (ADR-S011 S10): public page, authenticated only by the magic-link token, which is kept in
// sessionStorage and sent as X-Portal-Token. No staff session or app shell.

const KEY = 'portal-token';

interface Me { name: string; companyName: string; email: string | null; openBalance: number; overdue: number; mobileMoneyProviders: Array<{ code: string; name: string }> }
interface Quote { id: string; number: string; status: string; issueDate: string; validUntil: string | null; total: number }
interface Order { id: string; number: string; status: string; deliveryStatus: string; invoiceStatus: string; customerRef: string | null; total: number; confirmedAt: string | null }
interface OrderDetail extends Order {
  lines: Array<{ description: string; quantity: number; uomCode: string | null; unitPrice: number; lineTotal: number; qtyDelivered: number; qtyInvoiced: number }>;
  deliveries: Array<{ id: string; number: string; status: string; trips: Array<{ number: string; status: string; loadedTonnes: number | null; deliveredTonnes: number | null; dispatchedAt: string | null; deliveredAt: string | null }> | null }>;
}
interface Invoice { id: string; number: string; type: string; status: string; issueDate: string; dueDate: string | null; total: number; residual: number }
interface Complaint { id: string; number: string; category: string; status: string; description: string; resolution: string | null; createdAt: string }

type Tab = 'quotations' | 'orders' | 'invoices' | 'complaints';

class PortalError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

function useToken(): string | null {
  const [params, setParams] = useSearchParams();
  const fromUrl = params.get('token');
  useEffect(() => {
    if (fromUrl) {
      try { sessionStorage.setItem(KEY, fromUrl); } catch { /* private mode: keep it in memory only */ }
      params.delete('token');
      setParams(params, { replace: true });
    }
  }, [fromUrl, params, setParams]);
  if (fromUrl) return fromUrl;
  try { return sessionStorage.getItem(KEY); } catch { return null; }
}

export default function CustomerPortalPage() {
  const token = useToken();
  const call = useCallback(async <T,>(path: string, init?: RequestInit): Promise<T> => {
    const res = await fetch(`${getApiBaseUrl()}/sales-portal${path}`, {
      ...init,
      headers: { 'X-Portal-Token': token ?? '', ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers },
    });
    const body = res.headers.get('content-type')?.includes('json') ? await res.json() : null;
    if (!res.ok) throw new PortalError(res.status, body?.message ?? res.statusText);
    return body as T;
  }, [token]);

  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('orders');
  const [tick, setTick] = useState(0);
  const refresh = () => setTick((t) => t + 1);

  useEffect(() => {
    if (!token) { setError(tx('This page needs the personal link you received from us.')); return; }
    call<Me>('/me').then(setMe).catch((e: PortalError) => setError(e.status === 401 ? tx('This link is invalid or has expired. Ask us for a new one.') : e.message));
  }, [token, call, tick]);

  if (error) return <Shell><p className="rounded-lg border bg-card p-6 text-center text-sm">{error}</p></Shell>;
  if (!me) return <Shell><p className="p-6 text-center text-sm text-muted-foreground">{tx('Loading…')}</p></Shell>;
  const TABS: Array<[Tab, string]> = [['orders', tx('Orders & deliveries')], ['quotations', tx('Quotations')], ['invoices', tx('Invoices & payments')], ['complaints', tx('Complaints')]];
  return (
    <Shell company={me.companyName}>
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div><h1 className="text-xl font-semibold">{me.name}</h1><p className="text-sm text-muted-foreground">{tx('Open balance')} {money(me.openBalance)}{Number(me.overdue) > 0 && <span className="text-rose-600"> · {tx('overdue')} {money(me.overdue)}</span>}</p></div>
        <a className="text-sm text-primary hover:underline" href="#" onClick={async (e) => {
          e.preventDefault();
          const res = await fetch(`${getApiBaseUrl()}/sales-portal/statement.pdf`, { headers: { 'X-Portal-Token': token ?? '' } });
          if (res.ok) window.open(URL.createObjectURL(await res.blob()), '_blank');
        }}>{tx('Download statement (PDF)')}</a>
      </div>
      <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
        {TABS.map(([k, l]) => <button key={k} onClick={() => setTab(k)} className={cn('rounded-md px-3 py-1.5 text-sm', tab === k ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{l}</button>)}
      </div>
      {tab === 'orders' && <Orders call={call} />}
      {tab === 'quotations' && <Quotations call={call} />}
      {tab === 'invoices' && <Invoices call={call} me={me} onPaid={refresh} />}
      {tab === 'complaints' && <Complaints call={call} />}
    </Shell>
  );
}

type Call = <T>(path: string, init?: RequestInit) => Promise<T>;

function Shell({ company, children }: { company?: string; children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-muted/30">
      <header className="border-b bg-card"><div className="mx-auto max-w-5xl px-4 py-3 font-semibold">{company ?? tx('Customer portal')}</div></header>
      <main className="mx-auto max-w-5xl space-y-4 px-4 py-6">{children}</main>
    </div>
  );
}

function useLoad<T>(load: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { load().then(setData).catch((e: Error) => setErr(e.message)); }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  return { data, err, reload: () => load().then(setData).catch((e: Error) => setErr(e.message)) };
}

const card = 'overflow-x-auto rounded-xl border bg-card shadow-sm';
const th = 'px-3 py-2';

function Orders({ call }: { call: Call }) {
  const { data } = useLoad(() => call<Order[]>('/orders'), []);
  const [open, setOpen] = useState<OrderDetail | null>(null);
  return (
    <div className="space-y-3">
      <div className={card}>
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Order')}</th><th className={th}>{tx('Your ref.')}</th><th className={th}>{tx('Status')}</th><th className={th}>{tx('Delivery')}</th><th className={cn(th, 'text-right')}>{tx('Total')}</th></tr></thead>
          <tbody>{(data ?? []).map((o) => (
            <tr key={o.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => call<OrderDetail>(`/orders/${o.id}`).then(setOpen)}>
              <td className={cn(th, 'font-medium')}>{o.number}</td><td className={th}>{o.customerRef ?? '—'}</td><td className={th}>{o.status}</td><td className={th}>{o.deliveryStatus}</td><td className={cn(th, 'text-right tabular-nums')}>{money(o.total)}</td></tr>
          ))}{data && data.length === 0 && <tr><td colSpan={5} className="p-6 text-center text-muted-foreground">{tx('No orders yet.')}</td></tr>}</tbody>
        </table>
      </div>
      {open && (
        <div className="space-y-2 rounded-xl border bg-card p-4 text-sm shadow-sm">
          <div className="flex justify-between"><h3 className="font-semibold">{open.number}</h3><button className="text-xs text-muted-foreground" onClick={() => setOpen(null)}>{tx('Close')}</button></div>
          <table className="w-full"><thead className="text-left text-xs uppercase text-muted-foreground"><tr><th className="py-1">{tx('Item')}</th><th className="text-right">{tx('Ordered')}</th><th className="text-right">{tx('Delivered')}</th><th className="text-right">{tx('Invoiced')}</th><th className="text-right">{tx('Amount')}</th></tr></thead>
            <tbody>{open.lines.map((l, i) => <tr key={i} className="border-t"><td className="py-1">{l.description}</td><td className="text-right tabular-nums">{l.quantity} {l.uomCode ?? ''}</td><td className="text-right tabular-nums">{l.qtyDelivered}</td><td className="text-right tabular-nums">{l.qtyInvoiced}</td><td className="text-right tabular-nums">{money(l.lineTotal)}</td></tr>)}</tbody></table>
          {open.deliveries.map((d) => (
            <div key={d.id} className="rounded border p-2"><p className="font-medium">{tx('Delivery')} {d.number} · {d.status}</p>
              <ul className="text-xs text-muted-foreground">{(d.trips ?? []).map((t) => <li key={t.number}>{t.number} · {t.status}{t.loadedTonnes != null && ` · ${t.loadedTonnes} t`}{t.dispatchedAt && ` · ${tx('dispatched')} ${date(t.dispatchedAt)}`}{t.deliveredAt && ` · ${tx('delivered')} ${date(t.deliveredAt)}`}</li>)}</ul></div>
          ))}
        </div>
      )}
    </div>
  );
}

function Quotations({ call }: { call: Call }) {
  const { data, reload } = useLoad(() => call<Quote[]>('/quotations'), []);
  const [msg, setMsg] = useState<string | null>(null);
  const act = async (id: string, action: 'accept' | 'decline') => {
    try {
      await call(`/quotations/${id}/${action}`, { method: 'POST', body: JSON.stringify(action === 'decline' ? { reason: prompt(tx('Reason (optional)')) ?? '' } : {}) });
      setMsg(action === 'accept' ? tx('Thank you — we have your acceptance.') : tx('Quotation declined.'));
      reload();
    } catch (e) { setMsg((e as Error).message); }
  };
  return (
    <div className="space-y-2">
      {msg && <p className="rounded border bg-card p-2 text-sm">{msg}</p>}
      <div className={card}>
        <table className="w-full min-w-[600px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Quotation')}</th><th className={th}>{tx('Date')}</th><th className={th}>{tx('Valid until')}</th><th className={th}>{tx('Status')}</th><th className={cn(th, 'text-right')}>{tx('Total')}</th><th /></tr></thead>
          <tbody>{(data ?? []).map((q) => (
            <tr key={q.id} className="border-t"><td className={cn(th, 'font-medium')}>{q.number}</td><td className={th}>{date(q.issueDate)}</td><td className={th}>{q.validUntil ? date(q.validUntil) : '—'}</td><td className={th}>{q.status}</td>
              <td className={cn(th, 'text-right tabular-nums')}>{money(q.total)}</td>
              <td className={cn(th, 'space-x-1 text-right')}>{q.status === 'sent' && <><Button size="sm" onClick={() => act(q.id, 'accept')}>{tx('Accept')}</Button><Button size="sm" variant="outline" onClick={() => act(q.id, 'decline')}>{tx('Decline')}</Button></>}</td></tr>
          ))}{data && data.length === 0 && <tr><td colSpan={6} className="p-6 text-center text-muted-foreground">{tx('No quotations.')}</td></tr>}</tbody>
        </table>
      </div>
    </div>
  );
}

function Invoices({ call, me, onPaid }: { call: Call; me: Me; onPaid: () => void }) {
  const { data, reload } = useLoad(() => call<Invoice[]>('/invoices'), []);
  const [pay, setPay] = useState<{ invoice: Invoice; provider: string; phone: string } | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const start = async () => {
    if (!pay) return;
    try {
      const r = await call<{ id: string; status: string }>(`/invoices/${pay.invoice.id}/pay`, { method: 'POST', body: JSON.stringify({ provider: pay.provider, phone: pay.phone }) });
      setStatus(tx('Approve the payment on your phone…'));
      for (let i = 0; i < 20; i++) {
        await new Promise((res) => setTimeout(res, 3000));
        const s = await call<{ status: string }>(`/payments/${r.id}`);
        if (s.status !== 'pending') { setStatus(s.status === 'succeeded' ? tx('Payment received. Thank you!') : tx('Payment failed or was cancelled.')); break; }
      }
      setPay(null); reload(); onPaid();
    } catch (e) { setStatus((e as Error).message); }
  };
  return (
    <div className="space-y-2">
      {status && <p className="rounded border bg-card p-2 text-sm">{status}</p>}
      <div className={card}>
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Document')}</th><th className={th}>{tx('Date')}</th><th className={th}>{tx('Due')}</th><th className={cn(th, 'text-right')}>{tx('Total')}</th><th className={cn(th, 'text-right')}>{tx('Open')}</th><th /></tr></thead>
          <tbody>{(data ?? []).map((d) => (
            <tr key={d.id} className="border-t"><td className={cn(th, 'font-medium')}>{d.number}{d.type === 'credit_note' && <span className="ml-1 text-xs text-muted-foreground">{tx('credit note')}</span>}</td>
              <td className={th}>{date(d.issueDate)}</td><td className={cn(th, d.dueDate && new Date(d.dueDate) < new Date() && Number(d.residual) > 0 && 'text-rose-600')}>{d.dueDate ? date(d.dueDate) : '—'}</td>
              <td className={cn(th, 'text-right tabular-nums')}>{money(d.total)}</td><td className={cn(th, 'text-right tabular-nums')}>{d.type === 'sales_invoice' ? money(d.residual) : '—'}</td>
              <td className={cn(th, 'text-right')}>{d.type === 'sales_invoice' && Number(d.residual) > 0 && me.mobileMoneyProviders.length > 0 && <Button size="sm" onClick={() => setPay({ invoice: d, provider: me.mobileMoneyProviders[0].code, phone: '' })}>{tx('Pay')}</Button>}</td></tr>
          ))}</tbody>
        </table>
      </div>
      {pay && (
        <div className="space-y-2 rounded-xl border bg-card p-4 shadow-sm">
          <p className="text-sm font-medium">{tx('Pay')} {pay.invoice.number} · {money(pay.invoice.residual)}</p>
          <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={pay.provider} onChange={(e) => setPay({ ...pay, provider: e.target.value })}>
            {me.mobileMoneyProviders.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}</select>
          <Input placeholder="+2567…" value={pay.phone} onChange={(e) => setPay({ ...pay, phone: e.target.value })} />
          <div className="flex gap-2"><Button onClick={start} disabled={!pay.phone}>{tx('Send payment request')}</Button><Button variant="outline" onClick={() => setPay(null)}>{tx('Cancel')}</Button></div>
        </div>
      )}
    </div>
  );
}

function Complaints({ call }: { call: Call }) {
  const { data, reload } = useLoad(() => call<Complaint[]>('/complaints'), []);
  const [form, setForm] = useState({ category: 'quality', description: '' });
  const [msg, setMsg] = useState<string | null>(null);
  const submit = async () => {
    try {
      await call('/complaints', { method: 'POST', body: JSON.stringify(form) });
      setForm({ category: 'quality', description: '' }); setMsg(tx('Thank you — we will get back to you.')); reload();
    } catch (e) { setMsg((e as Error).message); }
  };
  return (
    <div className="space-y-3">
      <div className="space-y-2 rounded-xl border bg-card p-4 shadow-sm">
        <p className="text-sm font-medium">{tx('Report a problem')}</p>
        <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
          <option value="quality">{tx('Quality')}</option><option value="quantity">{tx('Quantity')}</option><option value="delivery">{tx('Delivery')}</option><option value="billing">{tx('Billing')}</option><option value="other">{tx('Other')}</option></select>
        <Textarea rows={3} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        <Button onClick={submit} disabled={!form.description.trim()}>{tx('Send')}</Button>
        {msg && <p className="text-sm">{msg}</p>}
      </div>
      <ul className="space-y-1 text-sm">{(data ?? []).map((c) => <li key={c.id} className="rounded border bg-card p-2">{c.number} · {c.category} · {c.status} — {c.description}{c.resolution && <span className="block text-muted-foreground">{c.resolution}</span>}</li>)}</ul>
    </div>
  );
}
