import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { BookOpen, FileText, Mail } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import { useAuthStore } from '@/stores/auth.store';
import { HeaderSelect, useHeaderOptions } from './OrderHeaderFields';

interface Outstanding { partnerId: string; partnerName: string; email: string | null; documents: number; outstanding: number; overdue: number; oldestDue: string | null; lastSentAt: string | null }
interface Statement { closingBalance: string; openingBalance: string; filtered: boolean; mergedRecords: number; lines: Array<{ date: string; reference: string; description: string | null; debit: string | number; credit: string | number; balance: string }>;
  reconciliation: { openInvoices: number; openCredits: number; documentsTotal: number; closingBalance: number; difference: number; reconciled: boolean | null; note: string | null };
  aging?: { buckets: Record<string, string> };
  log: Array<{ id: string; createdAt: string; sentTo: string | null; delivered: boolean; error: string | null; closingBalance: number; confirmedAt: string | null; confirmedBy: string | null }>;
  disputes: Array<{ id: string; reason: string; amount: number; status: string; resolution: string | null; documentNumber: string | null; createdAt: string }> }

const th = 'px-3 py-2';
const fail = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));

/** Customer statements: outstanding balances, one customer's statement with filters, sending and disputes. */
export function StatementsPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.collectionsManage));
  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [selected, setSelected] = useState<Record<string, boolean>>({});
  const [tab, setTab] = useState('outstanding');
  const outstanding = useQuery({ queryKey: ['sales', 'statements', 'outstanding'], queryFn: async () => (await api.get<Outstanding[]>('/sales/statements/outstanding')).data });
  const open = (o: Outstanding) => { setPartnerId(o.partnerId); setPartnerName(o.partnerName); setTab('statement'); };
  const bulk = (partnerIds?: string[]) => api.post('/sales/statements/send-bulk', { partnerIds }).then((r) => toast.success(`${r.data.sent} ${tx('sent')}, ${r.data.failed} ${tx('failed')}`)).catch(fail);
  const picked = Object.keys(selected).filter((k) => selected[k]);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BookOpen} title={tx('Customer Statements')} description={tx('Who owes what, statements that reconcile to the ledger, and what was sent to whom.')} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList><TabsTrigger value="outstanding">{tx('Outstanding')}</TabsTrigger><TabsTrigger value="statement">{tx('Statement')}</TabsTrigger></TabsList>
        <TabsContent value="outstanding" className="mt-3 space-y-2">
          {canManage && <div className="flex flex-wrap gap-2"><Button variant="outline" disabled={!picked.length} onClick={() => bulk(picked)}><Mail className="mr-1.5 h-4 w-4" />{tx('Send to selected')} ({picked.length})</Button><Button variant="outline" onClick={() => { if (confirm(tx('Send a statement to every customer with a balance?'))) void bulk(); }}>{tx('Send to all')}</Button></div>}
          <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
            <table className="w-full min-w-[720px] text-sm">
              <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className="w-8" /><th className={th}>{tx('Customer')}</th><th className={`${th} text-right`}>{tx('Documents')}</th><th className={`${th} text-right`}>{tx('Outstanding')}</th><th className={`${th} text-right`}>{tx('Overdue')}</th><th className={th}>{tx('Oldest due')}</th><th className={th}>{tx('Last sent')}</th></tr></thead>
              <tbody>{(outstanding.data ?? []).map((o) => (
                <tr key={o.partnerId} className="border-t hover:bg-muted/30"><td className="pl-3"><input type="checkbox" aria-label={o.partnerName} checked={!!selected[o.partnerId]} onChange={(e) => setSelected({ ...selected, [o.partnerId]: e.target.checked })} /></td>
                  <td className={`${th} cursor-pointer font-medium text-primary`} onClick={() => open(o)}>{o.partnerName}{!o.email && <span className="ml-2 text-xs font-normal text-amber-600">{tx('no email')}</span>}</td>
                  <td className={`${th} text-right tabular-nums`}>{Number(o.documents)}</td><td className={`${th} text-right tabular-nums`}>{money(o.outstanding)}</td><td className={`${th} text-right tabular-nums`}>{money(o.overdue)}</td><td className={th}>{o.oldestDue ? date(o.oldestDue) : '—'}</td><td className={th}>{o.lastSentAt ? date(o.lastSentAt) : '—'}</td></tr>
              ))}</tbody>
            </table>
          </div>
        </TabsContent>
        <TabsContent value="statement" className="mt-3 space-y-3">
          <div className="max-w-sm"><Field label={tx('Customer')}><PartnerPicker value={partnerId} label={partnerName} onPick={(i, n) => { setPartnerId(i); setPartnerName(n || null); }} /></Field></div>
          {partnerId ? <OneStatement partnerId={partnerId} canManage={canManage} /> : <p className="text-sm text-muted-foreground">{tx('Choose a customer.')}</p>}
        </TabsContent>
      </Tabs>
    </div>
  );
}

function OneStatement({ partnerId, canManage }: { partnerId: string; canManage: boolean }) {
  const qc = useQueryClient();
  const opts = useHeaderOptions();
  const [f, setF] = useState({ from: '', to: '', branchId: '', currencyCode: '', type: '' });
  const params = Object.fromEntries(Object.entries(f).filter(([, v]) => v));
  const { data: s } = useQuery({ queryKey: ['sales', 'statement', partnerId, f], queryFn: async () => (await api.get<Statement>(`/sales/statements/${partnerId}`, { params })).data });
  const refresh = () => qc.invalidateQueries({ queryKey: ['sales', 'statement', partnerId] });
  const pdf = async () => {
    try {
      const r = await api.get(`/sales/statements/${partnerId}/pdf`, { params: { from: f.from || undefined, to: f.to || undefined }, responseType: 'blob' });
      const url = URL.createObjectURL(r.data as Blob);
      const a = document.createElement('a'); a.href = url; a.download = 'statement.pdf'; a.click(); URL.revokeObjectURL(url);
    } catch (e) { fail(e); }
  };
  if (!s) return null;
  const rec = s.reconciliation;
  return (
    <div className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <Field label={tx('From')}><Input type="date" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} /></Field>
        <Field label={tx('To')}><Input type="date" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} /></Field>
        <Field label={tx('Branch')}><HeaderSelect value={f.branchId} options={opts.branches} none={tx('All')} onChange={(branchId) => setF({ ...f, branchId })} /></Field>
        <Field label={tx('Currency')}><HeaderSelect value={f.currencyCode} options={opts.currencies.map((c) => ({ id: c.code ?? c.id, name: c.code }))} none={tx('All')} onChange={(currencyCode) => setF({ ...f, currencyCode })} /></Field>
        <Field label={tx('Type')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}><option value="">{tx('All')}</option><option value="invoice">{tx('Invoices')}</option><option value="credit">{tx('Credit notes')}</option><option value="payment">{tx('Payments')}</option></select></Field>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={pdf}><FileText className="mr-1.5 h-4 w-4" />PDF</Button>
        {canManage && <Button variant="outline" onClick={() => api.post(`/sales/statements/${partnerId}/send`, { from: f.from || undefined, to: f.to || undefined }).then((r) => { if (r.data.error) toast.error(r.data.error); else toast.success(tx('Sent')); void refresh(); }).catch(fail)}><Mail className="mr-1.5 h-4 w-4" />{tx('Email statement')}</Button>}
        <Button variant="outline" onClick={() => { const reason = prompt(tx('What does the customer dispute?')); if (reason) api.post(`/sales/statements/${partnerId}/disputes`, { reason }).then(refresh).catch(fail); }}>{tx('Record a dispute')}</Button>
      </div>
      <div className={`rounded-xl border p-3 text-sm ${rec.reconciled === false ? 'border-amber-300 bg-amber-50 text-amber-900 dark:bg-transparent' : 'bg-card'}`}>
        <p><strong>{tx('Closing balance')}: {money(rec.closingBalance)}</strong>{s.filtered && <span className="ml-2 text-xs">({tx('filtered')})</span>}{s.mergedRecords > 0 && <span className="ml-2 text-xs">({s.mergedRecords} {tx('merged records')})</span>}</p>
        <p>{tx('Open invoices')} {money(rec.openInvoices)} − {tx('open credits')} {money(rec.openCredits)} = {money(rec.documentsTotal)}{rec.reconciled === false && <> · {tx('Difference')}: <strong>{money(rec.difference)}</strong></>}</p>
        {rec.note && <p className="text-xs">{tx(rec.note)}</p>}
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Date')}</th><th className={th}>{tx('Reference')}</th><th className={th}>{tx('Description')}</th><th className={`${th} text-right`}>{tx('Debit')}</th><th className={`${th} text-right`}>{tx('Credit')}</th><th className={`${th} text-right`}>{tx('Balance')}</th></tr></thead>
          <tbody>{s.lines.map((l, i) => <tr key={i} className="border-t"><td className={th}>{date(l.date)}</td><td className={th}>{l.reference}</td><td className={th}>{l.description}</td><td className={`${th} text-right tabular-nums`}>{Number(l.debit).toLocaleString()}</td><td className={`${th} text-right tabular-nums`}>{Number(l.credit).toLocaleString()}</td><td className={`${th} text-right tabular-nums`}>{Number(l.balance).toLocaleString()}</td></tr>)}</tbody>
        </table>
      </div>
      <div className="grid items-start gap-3 lg:grid-cols-2">
        <section className="rounded-xl border bg-card p-4 text-sm shadow-sm"><h2 className="mb-2 font-semibold">{tx('Sent statements')}</h2>
          <ul className="space-y-1">{s.log.map((l) => <li key={l.id} className="flex items-center justify-between gap-2 rounded border p-2"><span>{date(l.createdAt)} · {l.sentTo ?? '—'} · {l.error ? <span className="text-rose-600">{l.error}</span> : l.delivered ? tx('delivered') : tx('queued')}{l.confirmedAt && <span className="block text-xs text-emerald-700">{tx('Confirmed by')} {l.confirmedBy}</span>}</span>
            {canManage && !l.confirmedAt && !l.error && <Button size="sm" variant="outline" onClick={() => { const confirmedBy = prompt(tx('Who at the customer confirmed the balance?')); if (confirmedBy) api.post(`/sales/statements/log/${l.id}/confirm`, { confirmedBy }).then(refresh).catch(fail); }}>{tx('Confirmed')}</Button>}</li>)}</ul></section>
        <section className="rounded-xl border bg-card p-4 text-sm shadow-sm"><h2 className="mb-2 font-semibold">{tx('Disputes')}</h2>
          <ul className="space-y-1">{s.disputes.map((d) => <li key={d.id} className="flex items-center justify-between gap-2 rounded border p-2"><span>{d.reason} · {tx(d.status)}{d.resolution && <span className="block text-xs text-muted-foreground">{d.resolution}</span>}</span>
            {canManage && d.status === 'open' && <Button size="sm" variant="outline" onClick={() => { const resolution = prompt(tx('How was this resolved?')); if (resolution) api.post(`/sales/statements/disputes/${d.id}/resolve`, { status: 'resolved', resolution }).then(refresh).catch(fail); }}>{tx('Resolve')}</Button>}</li>)}</ul></section>
      </div>
    </div>
  );
}
