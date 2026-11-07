import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field, KpiCard } from '@/pages/crm/crm-ui';
import { HeaderSelect, useHeaderOptions } from '@/pages/sales/OrderHeaderFields';
import { useAuthStore } from '@/stores/auth.store';

interface Profile {
  customerType: string | null; homeBranchId: string | null; defaultCurrencyCode: string | null; altPhone: string | null; district: string | null;
  suspendedAt: string | null; suspendedReason: string | null; mergedIntoPartnerId: string | null; mergedAt: string | null; mergeReason: string | null;
  creditControllerId: string | null;
  mergedFrom: Array<{ id: string; code: string; name: string }>;
  kpis: { outstanding: number; overdue: number; lifetimeSales: number; invoices: number; orders: number; openOrders: number; lastInvoiceDate: string | null };
}
const TYPES = ['retail', 'wholesale', 'contractor', 'distributor', 'government', 'corporate', 'other'];

/** Type, home branch, currency, phones, KPIs, suspension, merge and attachments of one customer. */
export function CustomerProfileTab({ partnerId }: { partnerId: string }) {
  const qc = useQueryClient();
  const key = ['sales', 'customer-profile', partnerId];
  const { data: p } = useQuery({ queryKey: key, queryFn: async () => (await api.get<Profile>(`/sales/customers/${partnerId}/profile`)).data, retry: false });
  const opts = useHeaderOptions();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.customerManage));
  const canMerge = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.customerMerge));
  const [form, setForm] = useState({ customerType: '', homeBranchId: '', defaultCurrencyCode: '', altPhone: '', district: '', creditControllerId: '' });
  const byBranch = useQuery({ queryKey: ['sales', 'credit-by-branch', partnerId], queryFn: async () => (await api.get<Array<{ branchId: string | null; branchName: string | null; outstanding: number; overdue: number; branchLimit: number | null }>>(`/sales/customers/${partnerId}/credit-by-branch`)).data, retry: false });
  const [intoCode, setIntoCode] = useState('');
  useEffect(() => { if (p) setForm({ customerType: p.customerType ?? '', homeBranchId: p.homeBranchId ?? '', defaultCurrencyCode: p.defaultCurrencyCode ?? '', altPhone: p.altPhone ?? '', district: p.district ?? '', creditControllerId: p.creditControllerId ?? '' }); }, [p]);
  const act = useMutation({
    mutationFn: async ({ path, body, method }: { path: string; body?: unknown; method?: 'put' }) => (method === 'put' ? await api.put(`/sales/customers/${partnerId}/${path}`, body) : await api.post(`/sales/customers/${partnerId}/${path}`, body ?? {})).data,
    onSuccess: () => { toast.success(tx('Saved')); qc.invalidateQueries({ queryKey: key }); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Action failed'))),
  });
  if (!p) return <p className="text-sm text-muted-foreground">{tx('You cannot view this customer profile.')}</p>;
  const k = p.kpis;
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));
  const currencies = opts.currencies.map((c) => ({ id: c.code ?? c.id, name: c.code ?? c.name }));
  return (
    <div className="space-y-4">
      {p.mergedIntoPartnerId && <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-transparent">{tx('This record was merged and takes no new documents.')} {p.mergeReason}</div>}
      {p.suspendedAt && <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-900 dark:bg-transparent">{tx('Suspended')}: {p.suspendedReason}</div>}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <KpiCard label={tx('Lifetime sales')} value={money(k.lifetimeSales)} />
        <KpiCard label={tx('Outstanding')} value={money(k.outstanding)} />
        <KpiCard label={tx('Overdue')} value={money(k.overdue)} />
        <KpiCard label={tx('Orders')} value={`${k.orders} (${k.openOrders} ${tx('open')})`} />
        <KpiCard label={tx('Last invoice')} value={k.lastInvoiceDate ? date(k.lastInvoiceDate) : '—'} />
      </div>
      <section className="rounded-xl border bg-card p-5 shadow-sm">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label={tx('Customer type')}><HeaderSelect value={form.customerType} disabled={!canManage} options={TYPES.map((t) => ({ id: t, name: tx(t) }))} none={tx('Not set')} onChange={(customerType) => set({ customerType })} /></Field>
          <Field label={tx('Home branch')}><HeaderSelect value={form.homeBranchId} disabled={!canManage} options={opts.branches} none={tx('Not set')} onChange={(homeBranchId) => set({ homeBranchId })} /></Field>
          <Field label={tx('Default currency')}><HeaderSelect value={form.defaultCurrencyCode} disabled={!canManage} options={currencies} none={tx('Base currency')} onChange={(defaultCurrencyCode) => set({ defaultCurrencyCode })} /></Field>
          <Field label={tx('Second phone')}><Input disabled={!canManage} value={form.altPhone} onChange={(e) => set({ altPhone: e.target.value })} /></Field>
          <Field label={tx('District')}><Input disabled={!canManage} value={form.district} onChange={(e) => set({ district: e.target.value })} /></Field>
          <Field label={tx('Credit controller (user ID)')}><Input disabled={!canManage} value={form.creditControllerId} onChange={(e) => set({ creditControllerId: e.target.value })} /></Field>
        </div>
        {canManage && (
          <div className="mt-4 flex flex-wrap gap-2">
            <Button disabled={act.isPending} onClick={() => act.mutate({ path: 'profile', body: form, method: 'put' })}>{tx('Save')}</Button>
            {p.suspendedAt
              ? <Button variant="outline" onClick={() => act.mutate({ path: 'reinstate' })}>{tx('Reinstate')}</Button>
              : <Button variant="outline" onClick={() => { const reason = prompt(tx('Why suspend this customer?')); if (reason) act.mutate({ path: 'suspend', body: { reason } }); }}>{tx('Suspend')}</Button>}
          </div>
        )}
      </section>
      {(byBranch.data?.length ?? 0) > 0 && (
        <section className="rounded-xl border bg-card p-5 text-sm shadow-sm">
          <h2 className="mb-2 font-semibold">{tx('Receivables by branch')}</h2>
          <table className="w-full"><thead className="text-left text-xs text-muted-foreground"><tr><th>{tx('Branch')}</th><th className="text-right">{tx('Outstanding')}</th><th className="text-right">{tx('Overdue')}</th><th className="text-right">{tx('Branch limit')}</th></tr></thead>
            <tbody>{byBranch.data!.map((b) => <tr key={b.branchId ?? 'none'} className="border-t"><td className="py-1">{b.branchName ?? tx('No branch')}</td><td className="py-1 text-right tabular-nums">{money(b.outstanding)}</td><td className="py-1 text-right tabular-nums">{money(b.overdue)}</td><td className="py-1 text-right tabular-nums">{b.branchLimit == null ? '—' : money(b.branchLimit)}</td></tr>)}</tbody></table>
        </section>
      )}
      {canMerge && !p.mergedIntoPartnerId && (
        <section className="rounded-xl border bg-card p-5 shadow-sm">
          <h2 className="font-semibold">{tx('Merge into another customer')}</h2>
          <p className="mb-3 text-xs text-muted-foreground">{tx('Posted documents stay as they are. This record stops taking new documents and its figures roll up to the surviving customer. This cannot be undone here.')}</p>
          <div className="flex flex-wrap gap-2">
            <Input className="max-w-xs" placeholder={tx('Surviving customer ID')} value={intoCode} onChange={(e) => setIntoCode(e.target.value)} />
            <Button variant="destructive" disabled={!intoCode.trim()} onClick={() => { const reason = prompt(tx('Reason for the merge')); if (reason) act.mutate({ path: 'merge', body: { intoPartnerId: intoCode.trim(), reason } }); }}>{tx('Merge')}</Button>
          </div>
        </section>
      )}
      {p.mergedFrom.length > 0 && <section className="rounded-xl border bg-card p-5 text-sm shadow-sm"><h2 className="mb-2 font-semibold">{tx('Merged records')}</h2><ul className="list-disc pl-4">{p.mergedFrom.map((m) => <li key={m.id}>{m.code} · {m.name}</li>)}</ul></section>}
      <AttachmentPanel resType="partner" resId={partnerId} />
    </div>
  );
}
