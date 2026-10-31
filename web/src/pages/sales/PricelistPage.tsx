import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useCategoryOptions, useProductOptions } from '@/features/crm/suite-api';
import { pricingApi, usePricelist, useSalesMutation, type PriceCompute, type PricelistItem, type PricelistType } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import { PRICELIST_TYPE_LABEL } from './PricelistsPage';

const COMPUTE_LABEL: Record<PriceCompute, string> = {
  fixed: tx('Fixed price'), discount: tx('% discount on base'), surcharge: tx('% surcharge on base'), cost_plus: tx('Cost + %'),
};

const blankItem = (): PricelistItem => ({
  scope: 'product', productId: null, productCategoryId: null, partnerId: null, partnerCategoryId: null, uomId: null, minQuantity: 0,
  siteZone: null, branchId: null, validFrom: null, validTo: null, compute: 'fixed', value: 0, priority: 0,
});

/** Pricelist editor: header, price rules, assigned customers / categories. */
export function PricelistPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: p, isLoading } = usePricelist(id);
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.pricingManage));
  const products = useProductOptions();
  const productCats = useCategoryOptions('product');
  const partnerCats = useCategoryOptions('partner');
  const productOpts = useMemo(() => (products.data ?? []).map((x) => ({ value: x.id, label: x.name })), [products.data]);
  const [name, setName] = useState('');
  const [type, setType] = useState<PricelistType>('wholesale');
  const [currency, setCurrency] = useState('');
  const [isDefault, setIsDefault] = useState(false);
  const [isActive, setIsActive] = useState(true);
  const [edit, setEdit] = useState<PricelistItem | null>(null);
  const [assignCat, setAssignCat] = useState('');
  const save = useSalesMutation(pricingApi.savePricelist);
  const saveItem = useSalesMutation(pricingApi.saveItem);
  const delItem = useSalesMutation(pricingApi.deleteItem);
  const assign = useSalesMutation(pricingApi.assign);
  const del = useSalesMutation(pricingApi.deletePricelist);

  useEffect(() => { if (p) { setName(p.name); setType(p.type); setCurrency(p.currencyCode ?? ''); setIsDefault(p.isDefault); setIsActive(p.isActive); } }, [p]);
  if (isLoading || !p) return <Skeleton className="h-96 w-full" />;
  const err = (e: unknown) => toast.error(apiErrorMessage(e, tx('Could not save')));

  return (
    <div className="space-y-4">
      <div className="text-sm"><Link to="/sales/pricelists" className="font-medium text-primary hover:underline">{tx('Pricelists')}</Link><span className="mx-1.5 text-muted-foreground">/</span><span className="font-semibold">{p.code}</span></div>
      <div className="grid gap-3 rounded-xl border bg-card p-4 shadow-sm sm:grid-cols-2 lg:grid-cols-5">
        <Field label={tx('Name')} className="lg:col-span-2"><Input disabled={!canManage} value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label={tx('Type')}>
          <select disabled={!canManage} className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={type} onChange={(e) => setType(e.target.value as PricelistType)}>
            {Object.entries(PRICELIST_TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </Field>
        <Field label={tx('Currency')} hint={tx('Empty = any currency')}><Input disabled={!canManage} value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} /></Field>
        <div className="flex flex-col justify-end gap-1 text-sm">
          <label className="flex items-center gap-2"><input type="checkbox" disabled={!canManage} checked={isDefault} onChange={(e) => setIsDefault(e.target.checked)} />{tx('Organization default')}</label>
          <label className="flex items-center gap-2"><input type="checkbox" disabled={!canManage} checked={isActive} onChange={(e) => setIsActive(e.target.checked)} />{tx('Active')}</label>
        </div>
        {canManage && <div className="flex gap-2 lg:col-span-5">
          <Button onClick={() => save.mutate({ id: p.id, name, type, currencyCode: currency || null, isDefault, isActive }, { onSuccess: () => toast.success(tx('Saved')), onError: err })}>{tx('Save')}</Button>
          <Button variant="ghost" className="text-destructive" onClick={() => { if (confirm(tx('Delete this pricelist?'))) del.mutate(p.id, { onSuccess: () => navigate('/sales/pricelists'), onError: err }); }}><Trash2 className="h-4 w-4" /></Button>
        </div>}
      </div>

      <div className="rounded-xl border bg-card p-4 shadow-sm">
        <div className="mb-2 flex items-center justify-between"><h2 className="font-semibold">{tx('Price rules')}</h2>
          {canManage && <Button size="sm" onClick={() => setEdit(blankItem())}><Plus className="mr-1 h-4 w-4" />{tx('Add rule')}</Button>}</div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-sm">
            <thead className="text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
              <th className="py-1.5">{tx('Applies to')}</th><th>{tx('Customer')}</th><th className="text-right">{tx('Min qty')}</th><th>{tx('Zone')}</th>
              <th>{tx('Price')}</th><th>{tx('Valid')}</th><th className="text-right">{tx('Priority')}</th><th /></tr></thead>
            <tbody>
              {(p.items ?? []).map((it) => (
                <tr key={it.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => canManage && setEdit({ ...it })}>
                  <td className="py-1.5">{it.scope === 'product' ? it.productName : it.scope === 'category' ? productCats.data?.find((c) => c.id === it.productCategoryId)?.name : tx('All products')}</td>
                  <td>{it.partnerName ?? (it.partnerCategoryId ? partnerCats.data?.find((c) => c.id === it.partnerCategoryId)?.name : '—')}</td>
                  <td className="text-right tabular-nums">{Number(it.minQuantity)}</td>
                  <td>{it.siteZone ?? '—'}</td>
                  <td>{COMPUTE_LABEL[it.compute]} {Number(it.value)}</td>
                  <td className="text-xs">{it.validFrom ?? '…'} → {it.validTo ?? '…'}</td>
                  <td className="text-right">{it.priority}</td>
                  <td>{canManage && <button className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={(e) => { e.stopPropagation(); delItem.mutate({ pricelistId: p.id, id: it.id! }, { onError: err }); }}><Trash2 className="h-4 w-4" /></button>}</td>
                </tr>
              ))}
              {(p.items ?? []).length === 0 && <tr><td colSpan={8} className="py-4 text-center text-muted-foreground">{tx('No rules: this pricelist charges the base price.')}</td></tr>}
            </tbody>
          </table>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
          <h2 className="mb-2 font-semibold">{tx('Customers on this pricelist')}</h2>
          <ul className="mb-2 space-y-1">{(p.customers ?? []).map((c) => <li key={c.id}>{c.name}</li>)}</ul>
          {canManage && <PartnerPicker value="" placeholder={tx('Add a customer…')} onPick={(pid) => assign.mutate({ pricelistId: p.id, partnerIds: [pid] }, { onError: err })} />}
        </div>
        <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
          <h2 className="mb-2 font-semibold">{tx('Customer categories on this pricelist')}</h2>
          <ul className="mb-2 space-y-1">{(p.categories ?? []).map((c) => <li key={c.id}>{c.name}</li>)}</ul>
          {canManage && <div className="flex gap-2">
            <select className="h-9 flex-1 rounded-md border bg-background px-2" value={assignCat} onChange={(e) => setAssignCat(e.target.value)}>
              <option value="">{tx('Choose a category…')}</option>{(partnerCats.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
            <Button size="sm" disabled={!assignCat} onClick={() => assign.mutate({ pricelistId: p.id, partnerCategoryIds: [assignCat] }, { onSuccess: () => setAssignCat(''), onError: err })}>{tx('Assign')}</Button>
          </div>}
        </div>
      </div>

      {edit && (
        <Dialog open onOpenChange={(v) => !v && setEdit(null)}>
          <DialogContent className="max-w-2xl">
            <DialogHeader><DialogTitle>{edit.id ? tx('Edit rule') : tx('New rule')}</DialogTitle></DialogHeader>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Applies to')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.scope} onChange={(e) => setEdit({ ...edit, scope: e.target.value as PricelistItem['scope'] })}>
                  <option value="product">{tx('One product')}</option><option value="category">{tx('Product category')}</option><option value="all">{tx('All products')}</option>
                </select>
              </Field>
              {edit.scope === 'product' && <Field label={tx('Product')}><SearchableSelect value={edit.productId ?? ''} onValueChange={(v) => setEdit({ ...edit, productId: v || null })} options={productOpts} /></Field>}
              {edit.scope === 'category' && <Field label={tx('Product category')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.productCategoryId ?? ''} onChange={(e) => setEdit({ ...edit, productCategoryId: e.target.value || null })}>
                  <option value="" />{(productCats.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select></Field>}
              <Field label={tx('Only for customer')} hint={tx('Optional — customer-specific price')}><PartnerPicker value={edit.partnerId ?? ''} label={edit.partnerName} onPick={(pid, nm) => setEdit({ ...edit, partnerId: pid || null, partnerName: nm })} /></Field>
              <Field label={tx('Only for customer category')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.partnerCategoryId ?? ''} onChange={(e) => setEdit({ ...edit, partnerCategoryId: e.target.value || null })}>
                  <option value="" />{(partnerCats.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select></Field>
              <Field label={tx('Price method')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.compute} onChange={(e) => setEdit({ ...edit, compute: e.target.value as PriceCompute })}>
                  {Object.entries(COMPUTE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select></Field>
              <Field label={edit.compute === 'fixed' ? tx('Price per unit') : tx('Percent')}><Input type="number" value={edit.value} onChange={(e) => setEdit({ ...edit, value: Number(e.target.value) })} /></Field>
              <Field label={tx('Minimum quantity')}><Input type="number" value={edit.minQuantity} onChange={(e) => setEdit({ ...edit, minQuantity: Number(e.target.value) })} /></Field>
              <Field label={tx('Delivery zone')} hint={tx('Customer site zone, optional')}><Input value={edit.siteZone ?? ''} onChange={(e) => setEdit({ ...edit, siteZone: e.target.value || null })} /></Field>
              <Field label={tx('Valid from')}><Input type="date" value={edit.validFrom ?? ''} onChange={(e) => setEdit({ ...edit, validFrom: e.target.value || null })} /></Field>
              <Field label={tx('Valid to')}><Input type="date" value={edit.validTo ?? ''} onChange={(e) => setEdit({ ...edit, validTo: e.target.value || null })} /></Field>
              <Field label={tx('Priority')}><Input type="number" value={edit.priority} onChange={(e) => setEdit({ ...edit, priority: Number(e.target.value) })} /></Field>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setEdit(null)}>{tx('Cancel')}</Button>
              <Button onClick={() => saveItem.mutate({ ...edit, pricelistId: p.id }, { onSuccess: () => setEdit(null), onError: err })}>{tx('Save')}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
