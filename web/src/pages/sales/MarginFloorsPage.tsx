import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Percent, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useCategoryOptions, useProductOptions } from '@/features/crm/suite-api';
import { pricingApi, useMarginFloors, useSalesMutation, type MarginFloor } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';

const blank = (): MarginFloor => ({ productId: null, productCategoryId: null, branchId: null, partnerCategoryId: null, minMarginPercent: 10, validFrom: null, validTo: null, isActive: true });

/** Minimum margin rules: selling below the floor needs approval (ADR-S011 S2). */
export function MarginFloorsPage() {
  const { data } = useMarginFloors();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.pricingManage));
  const products = useProductOptions();
  const productCats = useCategoryOptions('product');
  const partnerCats = useCategoryOptions('partner');
  const productOpts = useMemo(() => (products.data ?? []).map((x) => ({ value: x.id, label: x.name })), [products.data]);
  const [edit, setEdit] = useState<MarginFloor | null>(null);
  const save = useSalesMutation(pricingApi.saveFloor);
  const del = useSalesMutation(pricingApi.deleteFloor);
  const err = (e: unknown) => toast.error(apiErrorMessage(e, tx('Could not save')));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Percent} title={tx('Margin floors')} description={tx('Quotations and orders priced below the minimum margin go to approval.')}
        actions={canManage && <Button onClick={() => setEdit(blank())}><Plus className="mr-1 h-4 w-4" />{tx('New floor')}</Button>} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Product / category')}</th><th className="px-3 py-2">{tx('Customer category')}</th><th className="px-3 py-2 text-right">{tx('Min. margin %')}</th><th className="px-3 py-2">{tx('Valid')}</th><th /></tr></thead>
          <tbody>
            {(data ?? []).map((f) => (
              <tr key={f.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => canManage && setEdit({ ...f })}>
                <td className="px-3 py-2">{f.productName ?? productCats.data?.find((c) => c.id === f.productCategoryId)?.name ?? tx('All products')}</td>
                <td className="px-3 py-2">{partnerCats.data?.find((c) => c.id === f.partnerCategoryId)?.name ?? tx('All customers')}</td>
                <td className="px-3 py-2 text-right tabular-nums">{Number(f.minMarginPercent)}%</td>
                <td className="px-3 py-2 text-xs">{f.validFrom ?? '…'} → {f.validTo ?? '…'}</td>
                <td className="px-3 py-2">{canManage && <button className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={(e) => { e.stopPropagation(); del.mutate(f.id!, { onError: err }); }}><Trash2 className="h-4 w-4" /></button>}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={5} className="p-6 text-center text-muted-foreground">{tx('No margin floors.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {edit && (
        <Dialog open onOpenChange={(v) => !v && setEdit(null)}>
          <DialogContent className="max-w-lg">
            <DialogHeader><DialogTitle>{tx('Margin floor')}</DialogTitle></DialogHeader>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Product')} className="sm:col-span-2"><SearchableSelect value={edit.productId ?? ''} onValueChange={(v) => setEdit({ ...edit, productId: v || null })} options={productOpts} placeholder={tx('All products')} /></Field>
              <Field label={tx('Product category')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.productCategoryId ?? ''} onChange={(e) => setEdit({ ...edit, productCategoryId: e.target.value || null })}>
                  <option value="" />{(productCats.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select></Field>
              <Field label={tx('Customer category')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.partnerCategoryId ?? ''} onChange={(e) => setEdit({ ...edit, partnerCategoryId: e.target.value || null })}>
                  <option value="" />{(partnerCats.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select></Field>
              <Field label={tx('Min. margin %')}><Input type="number" value={edit.minMarginPercent} onChange={(e) => setEdit({ ...edit, minMarginPercent: Number(e.target.value) })} /></Field>
              <Field label={tx('Valid from')}><Input type="date" value={edit.validFrom ?? ''} onChange={(e) => setEdit({ ...edit, validFrom: e.target.value || null })} /></Field>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setEdit(null)}>{tx('Cancel')}</Button>
              <Button onClick={() => save.mutate(edit, { onSuccess: () => setEdit(null), onError: err })}>{tx('Save')}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
