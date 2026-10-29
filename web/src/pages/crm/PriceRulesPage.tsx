import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Pencil, Plus, Tags, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader, StatusPill } from '@/components/list';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  crmApi, useCategoryOptions, useCrmMutation, usePriceRules, useProductOptions, type PriceRule,
} from '@/features/crm/suite-api';
import { Field } from './crm-ui';
import { PartnerPicker } from './crm-dialogs';

/** Customer-specific price rules: fixed prices or discounts per customer / category and product / category. */
export function PriceRulesPage() {
  const { data, isLoading } = usePriceRules();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.pricingManage));
  const [editing, setEditing] = useState<Partial<PriceRule> | null>(null);
  const [q, setQ] = useState('');
  const del = useCrmMutation(crmApi.deleteRule);
  const rows = (data ?? []).filter((r) => !q || [r.name, r.partnerName, r.productName, r.partnerCategoryName, r.productCategoryName].some((x) => x?.toLowerCase().includes(q.toLowerCase())));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Tags} title={tx('Price rules')} description={tx('Negotiated prices and discounts applied automatically on quotations. The most specific rule wins.')}
        actions={canManage && <Button onClick={() => setEditing({ kind: 'discount_percent', isActive: true, priority: 0, minQuantity: 0 })}><Plus className="mr-1 h-4 w-4" />{tx('New rule')}</Button>} />
      <Input className="max-w-sm" placeholder={tx('Search rules…')} value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[860px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr><th className="px-3 py-2">{tx('Rule')}</th><th className="px-3 py-2">{tx('Applies to')}</th><th className="px-3 py-2">{tx('Product')}</th>
              <th className="px-3 py-2">{tx('Price')}</th><th className="px-3 py-2">{tx('Min qty')}</th><th className="px-3 py-2">{tx('Validity')}</th>
              <th className="px-3 py-2">{tx('Priority')}</th><th className="px-3 py-2" /></tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={8} className="p-3"><Skeleton className="h-24 w-full" /></td></tr>}
            {rows.map((r) => (
              <tr key={r.id} className="border-t">
                <td className="px-3 py-2 font-medium">{r.name} {!r.isActive && <StatusPill tone="neutral">{tx('Inactive')}</StatusPill>}</td>
                <td className="px-3 py-2">{r.partnerName ?? (r.partnerCategoryName ? `${tx('Category')}: ${r.partnerCategoryName}` : tx('All customers'))}</td>
                <td className="px-3 py-2">{r.productName ?? (r.productCategoryName ? `${tx('Category')}: ${r.productCategoryName}` : tx('All products'))}</td>
                <td className="px-3 py-2 tabular-nums">{r.kind === 'fixed_price' ? money(r.value) : `${Number(r.value)}% ${tx('off')}`}</td>
                <td className="px-3 py-2 tabular-nums">{Number(r.minQuantity) > 0 ? `≥ ${Number(r.minQuantity)}` : '—'}</td>
                <td className="px-3 py-2 text-muted-foreground">{r.validFrom || r.validTo ? `${r.validFrom ?? '…'} → ${r.validTo ?? '…'}` : tx('Always')}</td>
                <td className="px-3 py-2 tabular-nums">{r.priority}</td>
                <td className="px-3 py-2 text-right">
                  {canManage && <>
                    <Button size="sm" variant="ghost" onClick={() => setEditing(r)}><Pencil className="h-4 w-4" /></Button>
                    <Button size="sm" variant="ghost" className="text-destructive" onClick={() => confirm(tx('Delete this rule?')) && del.mutate(r.id)}><Trash2 className="h-4 w-4" /></Button>
                  </>}
                </td>
              </tr>
            ))}
            {data && rows.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No price rules yet.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {editing && <RuleDialog rule={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function RuleDialog({ rule, onClose }: { rule: Partial<PriceRule>; onClose: () => void }) {
  const [r, setR] = useState<Partial<PriceRule>>(rule);
  const [who, setWho] = useState<'all' | 'partner' | 'category'>(rule.partnerId ? 'partner' : rule.partnerCategoryId ? 'category' : 'all');
  const [what, setWhat] = useState<'all' | 'product' | 'category'>(rule.productId ? 'product' : rule.productCategoryId ? 'category' : 'all');
  const products = useProductOptions();
  const pcats = useCategoryOptions('partner');
  const prcats = useCategoryOptions('product');
  const save = useCrmMutation(crmApi.saveRule);
  useEffect(() => setR(rule), [rule]);
  const productOpts = useMemo(() => (products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined })), [products.data]);
  const set = (p: Partial<PriceRule>) => setR((s) => ({ ...s, ...p }));
  const submit = () => save.mutate({
    id: r.id, name: r.name, kind: r.kind, value: Number(r.value ?? 0), minQuantity: Number(r.minQuantity ?? 0), priority: Number(r.priority ?? 0),
    validFrom: r.validFrom || null, validTo: r.validTo || null, isActive: r.isActive ?? true, notes: r.notes ?? null,
    partnerId: who === 'partner' ? r.partnerId ?? null : null, partnerCategoryId: who === 'category' ? r.partnerCategoryId ?? null : null,
    productId: what === 'product' ? r.productId ?? null : null, productCategoryId: what === 'category' ? r.productCategoryId ?? null : null,
  } as Partial<PriceRule> & { id?: string }, {
    onSuccess: () => { toast.success(tx('Saved')); onClose(); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-xl overflow-y-auto">
        <DialogHeader><DialogTitle>{r.id ? tx('Edit price rule') : tx('New price rule')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Name')} className="sm:col-span-2"><Input value={r.name ?? ''} onChange={(e) => set({ name: e.target.value })} placeholder={tx('e.g. Acme contract prices')} /></Field>
          <Field label={tx('Customers')}>
            <OptionalSelect value={who} onChange={(v) => setWho((v || 'all') as typeof who)} options={[{ value: 'all', label: tx('All customers') }, { value: 'partner', label: tx('One customer') }, { value: 'category', label: tx('Customer category') }]} placeholder="" />
          </Field>
          <Field label={tx('Products')}>
            <OptionalSelect value={what} onChange={(v) => setWhat((v || 'all') as typeof what)} options={[{ value: 'all', label: tx('All products') }, { value: 'product', label: tx('One product') }, { value: 'category', label: tx('Product category') }]} placeholder="" />
          </Field>
          {who === 'partner' && <Field label={tx('Customer')} className="sm:col-span-2"><PartnerPicker value={r.partnerId ?? ''} label={r.partnerName} onPick={(id, n) => set({ partnerId: id || null, partnerName: n })} /></Field>}
          {who === 'category' && <Field label={tx('Customer category')}><OptionalSelect value={r.partnerCategoryId ?? ''} onChange={(v) => set({ partnerCategoryId: v || null })} options={(pcats.data ?? []).map((c) => ({ value: c.id, label: c.name }))} placeholder={tx('Choose…')} /></Field>}
          {what === 'product' && <Field label={tx('Product')} className="sm:col-span-2"><SearchableSelect value={r.productId ?? ''} onValueChange={(v) => set({ productId: v || null })} options={productOpts} placeholder={tx('Product…')} /></Field>}
          {what === 'category' && <Field label={tx('Product category')}><OptionalSelect value={r.productCategoryId ?? ''} onChange={(v) => set({ productCategoryId: v || null })} options={(prcats.data ?? []).map((c) => ({ value: c.id, label: c.name }))} placeholder={tx('Choose…')} /></Field>}
          <Field label={tx('Rule type')}>
            <OptionalSelect value={r.kind ?? 'discount_percent'} onChange={(v) => set({ kind: (v || 'discount_percent') as PriceRule['kind'] })} options={[{ value: 'discount_percent', label: tx('Discount %') }, { value: 'fixed_price', label: tx('Fixed price') }]} placeholder="" />
          </Field>
          <Field label={r.kind === 'fixed_price' ? tx('Unit price') : tx('Discount %')}><Input type="number" min={0} value={r.value ?? ''} onChange={(e) => set({ value: Number(e.target.value) })} /></Field>
          <Field label={tx('Minimum quantity')}><Input type="number" min={0} value={r.minQuantity ?? 0} onChange={(e) => set({ minQuantity: Number(e.target.value) })} /></Field>
          <Field label={tx('Priority')} hint={tx('Higher wins when several rules match')}><Input type="number" value={r.priority ?? 0} onChange={(e) => set({ priority: Number(e.target.value) })} /></Field>
          <Field label={tx('Valid from')}><Input type="date" value={r.validFrom ?? ''} onChange={(e) => set({ validFrom: e.target.value })} /></Field>
          <Field label={tx('Valid to')}><Input type="date" value={r.validTo ?? ''} onChange={(e) => set({ validTo: e.target.value })} /></Field>
          <label className="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" checked={r.isActive ?? true} onChange={(e) => set({ isActive: e.target.checked })} />{tx('Active')}</label>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={r.notes ?? ''} onChange={(e) => set({ notes: e.target.value })} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={save.isPending || !r.name}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
