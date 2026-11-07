import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ArrowDown, ArrowUp, BadgePercent, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ListPageHeader } from '@/components/list';
import { useProductOptions } from '@/features/crm/suite-api';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field } from '@/pages/crm/crm-ui';
import { useAuthStore } from '@/stores/auth.store';

const th = 'px-3 py-2';
const fail = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));

function useProducts() {
  const products = useProductOptions();
  return (products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined }));
}

function Promotions({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['sales', 'promotions'], queryFn: async () => (await api.get<Array<{ id: string; name: string; kind: string; value: number; productName: string | null; categoryName: string | null; startDate: string; endDate: string; isActive: boolean }>>('/sales/promotions')).data });
  const productOpts = useProducts();
  const blank = { name: '', kind: 'percent', value: 10, productId: '', startDate: new Date().toISOString().slice(0, 10), endDate: new Date().toISOString().slice(0, 10) };
  const [f, setF] = useState(blank);
  const refresh = () => qc.invalidateQueries({ queryKey: ['sales', 'promotions'] });
  return (
    <div className="space-y-3">
      {canManage && (
        <div className="grid items-end gap-3 rounded-xl border bg-card p-4 shadow-sm sm:grid-cols-3 lg:grid-cols-6">
          <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label={tx('Type')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}><option value="percent">{tx('Percent off')}</option><option value="fixed_price">{tx('Fixed price')}</option><option value="amount_off">{tx('Amount off')}</option></select></Field>
          <Field label={tx('Value')}><Input type="number" min={0} value={f.value} onChange={(e) => setF({ ...f, value: Number(e.target.value) })} /></Field>
          <Field label={tx('Product')}><SearchableSelect value={f.productId} onValueChange={(productId) => setF({ ...f, productId })} options={productOpts} placeholder={tx('All products')} /></Field>
          <Field label={tx('From')}><Input type="date" value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} /></Field>
          <Field label={tx('To')}><Input type="date" value={f.endDate} onChange={(e) => setF({ ...f, endDate: e.target.value })} /></Field>
          <Button disabled={!f.name.trim()} onClick={() => api.post('/sales/promotions', { ...f, productId: f.productId || null }).then(() => { setF(blank); void refresh(); }).catch(fail)}>{tx('Add promotion')}</Button>
        </div>
      )}
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[640px] text-sm"><thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Name')}</th><th className={th}>{tx('Applies to')}</th><th className={th}>{tx('Offer')}</th><th className={th}>{tx('Dates')}</th><th className="w-8" /></tr></thead>
          <tbody>{(data ?? []).map((p) => (
            <tr key={p.id} className="border-t"><td className={th}>{p.name}</td><td className={th}>{p.productName ?? p.categoryName ?? tx('All products')}</td><td className={th}>{tx(p.kind)} · {Number(p.value)}</td><td className={th}>{date(p.startDate)} – {date(p.endDate)}</td>
              <td className={th}>{canManage && <button aria-label={tx('Delete')} onClick={() => api.delete(`/sales/promotions/${p.id}`).then(refresh).catch(fail)}><Trash2 className="h-4 w-4 text-muted-foreground" /></button>}</td></tr>
          ))}</tbody></table>
      </div>
    </div>
  );
}

function Limits({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['sales', 'price-limits'], queryFn: async () => (await api.get<Array<{ id: string; productName: string | null; categoryName: string | null; minPrice: number | null; maxPrice: number | null }>>('/sales/price-limits')).data });
  const productOpts = useProducts();
  const [f, setF] = useState({ productId: '', minPrice: '', maxPrice: '' });
  const refresh = () => qc.invalidateQueries({ queryKey: ['sales', 'price-limits'] });
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">{tx('An order priced outside these limits goes to approval.')}</p>
      {canManage && (
        <div className="grid items-end gap-3 rounded-xl border bg-card p-4 shadow-sm sm:grid-cols-4">
          <Field label={tx('Product')}><SearchableSelect value={f.productId} onValueChange={(productId) => setF({ ...f, productId })} options={productOpts} placeholder={tx('Product…')} /></Field>
          <Field label={tx('Minimum price')}><Input type="number" min={0} value={f.minPrice} onChange={(e) => setF({ ...f, minPrice: e.target.value })} /></Field>
          <Field label={tx('Maximum price')}><Input type="number" min={0} value={f.maxPrice} onChange={(e) => setF({ ...f, maxPrice: e.target.value })} /></Field>
          <Button disabled={!f.productId} onClick={() => api.put('/sales/price-limits', { productId: f.productId, minPrice: f.minPrice === '' ? null : Number(f.minPrice), maxPrice: f.maxPrice === '' ? null : Number(f.maxPrice) }).then(() => { setF({ productId: '', minPrice: '', maxPrice: '' }); void refresh(); }).catch(fail)}>{tx('Save limit')}</Button>
        </div>
      )}
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full text-sm"><thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Applies to')}</th><th className={`${th} text-right`}>{tx('Minimum')}</th><th className={`${th} text-right`}>{tx('Maximum')}</th><th className="w-8" /></tr></thead>
          <tbody>{(data ?? []).map((l) => <tr key={l.id} className="border-t"><td className={th}>{l.productName ?? l.categoryName}</td><td className={`${th} text-right tabular-nums`}>{l.minPrice ?? '—'}</td><td className={`${th} text-right tabular-nums`}>{l.maxPrice ?? '—'}</td><td className={th}>{canManage && <button aria-label={tx('Delete')} onClick={() => api.delete(`/sales/price-limits/${l.id}`).then(refresh).catch(fail)}><Trash2 className="h-4 w-4 text-muted-foreground" /></button>}</td></tr>)}</tbody></table>
      </div>
    </div>
  );
}

function TierGrid() {
  const [productId, setProductId] = useState('');
  const productOpts = useProducts();
  const { data } = useQuery({ queryKey: ['sales', 'pricing-grid', productId], queryFn: async () => (await api.get<Array<{ system: string; listId: string; listName: string; itemId: string; minQuantity: number; compute: string; value: number; uomCode: string | null }>>('/sales/pricing/grid', { params: { productId } })).data, enabled: !!productId });
  return (
    <div className="space-y-3">
      <div className="max-w-sm"><Field label={tx('Product')}><SearchableSelect value={productId} onValueChange={setProductId} options={productOpts} placeholder={tx('Product…')} /></Field></div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full text-sm"><thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('List')}</th><th className={`${th} text-right`}>{tx('From quantity')}</th><th className={th}>{tx('Unit')}</th><th className={th}>{tx('Method')}</th><th className={`${th} text-right`}>{tx('Value')}</th></tr></thead>
          <tbody>{(data ?? []).map((r) => <tr key={r.itemId} className="border-t"><td className={th}>{r.listName}</td><td className={`${th} text-right tabular-nums`}>{Number(r.minQuantity ?? 0)}</td><td className={th}>{r.uomCode ?? '—'}</td><td className={th}>{tx(r.compute)}</td><td className={`${th} text-right tabular-nums`}>{Number(r.value)}</td></tr>)}
            {productId && !data?.length && <tr><td colSpan={5} className="p-6 text-center text-muted-foreground">{tx('No price list covers this product.')}</td></tr>}</tbody></table>
      </div>
    </div>
  );
}

const TIER_LABEL: Record<string, string> = { agreement: 'Customer agreement', promotion: 'Promotion', customer_rule: 'Customer rule', crm_rule: 'CRM rule', customer_pricelist: 'Customer pricelist', category_pricelist: 'Customer category pricelist', price_list: 'Named price list', branch_default: 'Branch default', org_default: 'Organization default', standing_discount: 'Standing discount' };

function Precedence({ canManage }: { canManage: boolean }) {
  const { data } = useQuery({ queryKey: ['sales', 'pricing-policy'], queryFn: async () => (await api.get<{ precedence: string[]; tiers: string[] }>('/sales/pricing/policy')).data });
  const [order, setOrder] = useState<string[]>([]);
  useEffect(() => { if (data) setOrder(data.precedence); }, [data]);
  const move = (i: number, d: number) => setOrder((o) => { const n = [...o]; const j = i + d; if (j < 0 || j >= n.length) return o; [n[i], n[j]] = [n[j], n[i]]; return n; });
  const missing = (data?.tiers ?? []).filter((t) => !order.includes(t));
  return (
    <div className="max-w-xl space-y-3">
      <p className="text-sm text-muted-foreground">{tx('The first tier that has a price for a line wins.')}</p>
      <ol className="space-y-1 text-sm">{order.map((t, i) => (
        <li key={t} className="flex items-center justify-between rounded-lg border bg-card px-3 py-2"><span>{i + 1}. {tx(TIER_LABEL[t] ?? t)}</span>
          {canManage && <span className="flex gap-1"><button aria-label={tx('Move up')} onClick={() => move(i, -1)}><ArrowUp className="h-4 w-4" /></button><button aria-label={tx('Move down')} onClick={() => move(i, 1)}><ArrowDown className="h-4 w-4" /></button><button aria-label={tx('Turn off')} onClick={() => setOrder((o) => o.filter((x) => x !== t))}><Trash2 className="h-4 w-4 text-muted-foreground" /></button></span>}</li>
      ))}</ol>
      {missing.length > 0 && <div className="flex flex-wrap items-center gap-2 text-sm"><span className="text-muted-foreground">{tx('Turned off')}:</span>{missing.map((t) => <Button key={t} size="sm" variant="outline" disabled={!canManage} onClick={() => setOrder((o) => [...o, t])}>{tx(TIER_LABEL[t] ?? t)}</Button>)}</div>}
      {canManage && <Button onClick={() => api.put('/sales/pricing/policy', { precedence: order }).then(() => toast.success(tx('Saved'))).catch(fail)}>{tx('Save order')}</Button>}
    </div>
  );
}

function History() {
  const { data } = useQuery({ queryKey: ['sales', 'pricing-history'], queryFn: async () => (await api.get<Array<{ id: number; system: string; action: string; productName: string | null; oldValue: unknown; newValue: unknown; changedAt: string }>>('/sales/pricing/history')).data });
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <table className="w-full min-w-[720px] text-sm"><thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('When')}</th><th className={th}>{tx('Product')}</th><th className={th}>{tx('Change')}</th><th className={th}>{tx('Before')}</th><th className={th}>{tx('After')}</th></tr></thead>
        <tbody>{(data ?? []).map((h) => <tr key={h.id} className="border-t align-top"><td className={th}>{date(h.changedAt)}</td><td className={th}>{h.productName ?? tx('All products')}</td><td className={th}>{tx(h.action)}</td><td className={`${th} break-all text-xs text-muted-foreground`}>{h.oldValue ? JSON.stringify(h.oldValue) : '—'}</td><td className={`${th} break-all text-xs`}>{h.newValue ? JSON.stringify(h.newValue) : '—'}</td></tr>)}</tbody></table>
    </div>
  );
}

/** Promotions, price limits, tier grid, precedence and price history. */
export function PricingToolsPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.pricingManage));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BadgePercent} title={tx('Pricing Tools')} description={tx('Promotions, absolute price limits, quantity tiers across all lists, price precedence and history of changes.')} />
      <Tabs defaultValue="promotions">
        <TabsList><TabsTrigger value="promotions">{tx('Promotions')}</TabsTrigger><TabsTrigger value="limits">{tx('Price limits')}</TabsTrigger><TabsTrigger value="grid">{tx('Tier grid')}</TabsTrigger><TabsTrigger value="precedence">{tx('Precedence')}</TabsTrigger><TabsTrigger value="history">{tx('History')}</TabsTrigger></TabsList>
        <TabsContent value="promotions" className="mt-3"><Promotions canManage={canManage} /></TabsContent>
        <TabsContent value="limits" className="mt-3"><Limits canManage={canManage} /></TabsContent>
        <TabsContent value="grid" className="mt-3"><TierGrid /></TabsContent>
        <TabsContent value="precedence" className="mt-3"><Precedence canManage={canManage} /></TabsContent>
        <TabsContent value="history" className="mt-3"><History /></TabsContent>
      </Tabs>
    </div>
  );
}
