import { useEffect, useMemo, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { DataTable, type Column } from '@/components/data-table';
import { useBranchOptions } from '@/features/finance/api';
import { useCategoryOptions, useProductOptions } from '@/features/crm/suite-api';
import {
  useDeletePriceList, usePriceList, usePriceLists, useSavePriceList,
  type PriceListInput, type PriceListItem, type PriceListRow, type PriceListTier,
} from '@/features/invoicing/price-list-api';
import { useUoms } from '@/features/uom/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import { Field } from '@/pages/crm/crm-ui';
import { useAuthStore } from '@/stores/auth.store';

const PAGE_SIZE = 25;

const TIERS: { value: PriceListTier; label: () => string }[] = [
  { value: 'retail', label: () => tx('Retail') },
  { value: 'wholesale', label: () => tx('Wholesale') },
  { value: 'contract', label: () => tx('Contract') },
  { value: 'negotiated', label: () => tx('Negotiated') },
];

const EMPTY: PriceListInput = {
  code: '', name: '', tier: 'retail', isDefault: false, branchId: null, validFrom: null, validTo: null, priority: 0,
  isActive: true, notes: null, items: [], partnerIds: [], partnerCategoryIds: [],
};

/** Named price lists (SALES-3): applied to orders, invoices and deliveries when a line has no price of its own. */
export function PriceListsPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.priceList.manage));
  const [search, setSearch] = useState('');
  const [tier, setTier] = useState('');
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<{ id?: string } | null>(null);
  const { data, isLoading, isError } = usePriceLists({
    search: search || undefined, tier: tier || undefined, page, pageSize: PAGE_SIZE,
  });
  const del = useDeletePriceList();
  const meta = data?.meta;

  const columns: Column<PriceListRow>[] = [
    { key: 'code', header: tx('Code') },
    { key: 'name', header: tx('Name') },
    { key: 'tier', header: tx('Tier'), render: (r) => TIERS.find((t) => t.value === r.tier)?.label() ?? r.tier },
    { key: 'branchName', header: tx('Branch'), render: (r) => r.branchName ?? tx('All branches') },
    { key: 'itemCount', header: tx('Lines'), className: 'text-right' },
    {
      key: 'isActive',
      header: tx('Status'),
      render: (r) => (
        <span className="flex gap-1">
          {r.isDefault && <Badge variant="secondary">{tx('Default')}</Badge>}
          {r.isActive ? <Badge variant="secondary">{tx('Active')}</Badge> : <Badge variant="outline">{tx('Disabled')}</Badge>}
        </span>
      ),
    },
    {
      key: 'actions',
      header: '',
      render: (r) => canManage && (
        <Button
          size="icon"
          variant="ghost"
          aria-label={tx('Delete')}
          onClick={(e) => {
            e.stopPropagation();
            del.mutate(r.id, { onError: (err) => toast.error(apiErrorMessage(err, tx('Could not save'))) });
          }}
        >
          <Trash2 className="h-4 w-4 text-destructive" />
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Price Lists')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Used on orders, invoices and deliveries when a line has no price of its own. A price typed on a line always wins.')}
          </p>
        </div>
        {canManage && <Button onClick={() => setEditing({})}><Plus className="mr-1 h-4 w-4" />{tx('New price list')}</Button>}
      </div>
      <div className="flex flex-wrap gap-2">
        <Input
          className="max-w-xs"
          placeholder={tx('Search')}
          value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(1); }}
        />
        <select
          aria-label={tx('Tier')}
          className="rounded border bg-background px-2 py-1 text-sm"
          value={tier}
          onChange={(e) => { setTier(e.target.value); setPage(1); }}
        >
          <option value="">{tx('All')}</option>
          {TIERS.map((t) => <option key={t.value} value={t.value}>{t.label()}</option>)}
        </select>
      </div>
      {isError ? (
        <p className="text-sm text-destructive">{tx('Could not load')}</p>
      ) : (
        <DataTable
          columns={columns}
          data={data?.data ?? []}
          loading={isLoading}
          getRowId={(r) => r.id}
          onRowClick={(r) => setEditing({ id: r.id })}
          emptyMessage={tx('No price lists yet.')}
        />
      )}
      {meta && meta.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
          <span>{tx('Page')} {meta.page} {tx('of')} {meta.totalPages}</span>
          <Button variant="outline" size="sm" disabled={page >= meta.totalPages} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
        </div>
      )}
      {editing && <PriceListDialog id={editing.id} canManage={canManage} onClose={() => setEditing(null)} />}
    </div>
  );
}

function PriceListDialog({ id, canManage, onClose }: { id?: string; canManage: boolean; onClose: () => void }) {
  const { data: existing } = usePriceList(id);
  const save = useSavePriceList(id);
  const { data: branches = [] } = useBranchOptions();
  const { data: units = [] } = useUoms();
  const products = useProductOptions();
  const categories = useCategoryOptions('partner');
  const [v, setV] = useState<PriceListInput>(EMPTY);

  useEffect(() => {
    if (existing) setV({ ...EMPTY, ...existing });
  }, [existing]);

  const productOptions = useMemo(
    () => (products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined })),
    [products.data],
  );
  const set = (patch: Partial<PriceListInput>) => setV((s) => ({ ...s, ...patch }));
  const setItem = (i: number, patch: Partial<PriceListItem>) => set({
    items: v.items.map((it, n) => (n === i ? { ...it, ...patch } : it)),
  });
  const num = (s: string) => (s === '' ? null : Number(s));

  // Send only what the route accepts: a loaded list also carries read-only columns.
  const payload = (): PriceListInput => ({
    code: v.code, name: v.name, tier: v.tier, isDefault: v.isDefault, branchId: v.branchId,
    validFrom: v.validFrom ? v.validFrom.slice(0, 10) : null, validTo: v.validTo ? v.validTo.slice(0, 10) : null,
    priority: Number(v.priority), isActive: v.isActive, notes: v.notes, partnerIds: v.partnerIds,
    partnerCategoryIds: v.partnerCategoryIds,
    items: v.items.map((i) => ({
      productId: i.productId, productCategoryId: i.productCategoryId, uomId: i.uomId, siteId: i.siteId, zone: i.zone,
      minQuantity: i.minQuantity, unitPrice: i.unitPrice, discountPercent: i.discountPercent,
    })),
  });

  const submit = () => save.mutate(payload(), {
    onSuccess: () => { toast.success(tx('Saved')); onClose(); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-3xl overflow-y-auto">
        <DialogHeader><DialogTitle>{id ? tx('Edit price list') : tx('New price list')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label={tx('Code')}><Input value={v.code} onChange={(e) => set({ code: e.target.value })} /></Field>
          <Field label={tx('Name')} className="sm:col-span-2"><Input value={v.name} onChange={(e) => set({ name: e.target.value })} /></Field>
          <Field label={tx('Tier')}>
            <select className="w-full rounded border bg-background px-2 py-2 text-sm" aria-label={tx('Tier')} value={v.tier} onChange={(e) => set({ tier: e.target.value as PriceListTier })}>
              {TIERS.map((t) => <option key={t.value} value={t.value}>{t.label()}</option>)}
            </select>
          </Field>
          <Field label={tx('Branch')}>
            <select className="w-full rounded border bg-background px-2 py-2 text-sm" aria-label={tx('Branch')} value={v.branchId ?? ''} onChange={(e) => set({ branchId: e.target.value || null })}>
              <option value="">{tx('All branches')}</option>
              {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </Field>
          <Field label={tx('Priority')}><Input type="number" value={v.priority} onChange={(e) => set({ priority: Number(e.target.value) })} /></Field>
          <Field label={tx('Valid from')}><Input type="date" value={v.validFrom ?? ''} onChange={(e) => set({ validFrom: e.target.value || null })} /></Field>
          <Field label={tx('Valid to')}><Input type="date" value={v.validTo ?? ''} onChange={(e) => set({ validTo: e.target.value || null })} /></Field>
          <div className="flex flex-col justify-end gap-1 text-sm">
            <label className="flex items-center gap-2"><input type="checkbox" checked={v.isDefault} onChange={(e) => set({ isDefault: e.target.checked })} />{tx('Default for all customers')}</label>
            <label className="flex items-center gap-2"><input type="checkbox" checked={v.isActive} onChange={(e) => set({ isActive: e.target.checked })} />{tx('Active')}</label>
          </div>
        </div>

        <div className="space-y-2">
          <p className="text-sm font-medium">{tx('Customers')}</p>
          <PartnerPicker
            value=""
            onPick={(pid) => { if (pid && !v.partnerIds.includes(pid)) set({ partnerIds: [...v.partnerIds, pid] }); }}
          />
          {v.partnerIds.length > 0 && (
            <p className="text-xs text-muted-foreground">
              {v.partnerIds.length} {tx('customer(s) on this list')}{' '}
              <button type="button" className="underline" onClick={() => set({ partnerIds: [] })}>{tx('Clear')}</button>
            </p>
          )}
          <div className="flex flex-wrap gap-3 text-sm">
            {(categories.data ?? []).map((c) => (
              <label key={c.id} className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={v.partnerCategoryIds.includes(c.id)}
                  onChange={() => set({
                    partnerCategoryIds: v.partnerCategoryIds.includes(c.id)
                      ? v.partnerCategoryIds.filter((x) => x !== c.id) : [...v.partnerCategoryIds, c.id],
                  })}
                />
                {c.name}
              </label>
            ))}
          </div>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium">{tx('Lines')}</p>
            <Button size="sm" variant="outline" onClick={() => set({ items: [...v.items, { minQuantity: 0 }] })}>
              <Plus className="mr-1 h-4 w-4" />{tx('Add a line')}
            </Button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="p-1">{tx('Product')}</th><th className="p-1">{tx('Unit')}</th><th className="p-1">{tx('Zone')}</th>
                  <th className="p-1">{tx('From qty')}</th><th className="p-1">{tx('Unit price')}</th><th className="p-1">{tx('Disc %')}</th><th />
                </tr>
              </thead>
              <tbody>
                {v.items.map((it, i) => (
                  <tr key={i}>
                    <td className="min-w-48 p-1"><SearchableSelect value={it.productId ?? ''} onValueChange={(x) => setItem(i, { productId: x || null })} options={productOptions} placeholder={tx('All products')} /></td>
                    <td className="p-1">
                      <select aria-label={tx('Unit')} className="rounded border bg-background px-1 py-1.5" value={it.uomId ?? ''} onChange={(e) => setItem(i, { uomId: e.target.value || null })}>
                        <option value="">{tx('Sales unit')}</option>
                        {units.map((u) => <option key={u.id} value={u.id}>{u.code}</option>)}
                      </select>
                    </td>
                    <td className="p-1"><Input className="w-24" aria-label={tx('Zone')} value={it.zone ?? ''} onChange={(e) => setItem(i, { zone: e.target.value || null })} /></td>
                    <td className="p-1"><Input className="w-20" type="number" min={0} aria-label={tx('From qty')} value={it.minQuantity ?? 0} onChange={(e) => setItem(i, { minQuantity: Number(e.target.value) })} /></td>
                    <td className="p-1"><Input className="w-24" type="number" min={0} aria-label={tx('Unit price')} value={it.unitPrice ?? ''} onChange={(e) => setItem(i, { unitPrice: num(e.target.value) })} /></td>
                    <td className="p-1"><Input className="w-20" type="number" min={0} max={100} aria-label={tx('Disc %')} value={it.discountPercent ?? ''} onChange={(e) => setItem(i, { discountPercent: num(e.target.value) })} /></td>
                    <td className="p-1">
                      <Button size="icon" variant="ghost" aria-label={tx('Delete')} onClick={() => set({ items: v.items.filter((_, n) => n !== i) })}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          {canManage && <Button onClick={submit} disabled={save.isPending || !v.code || !v.name}>{tx('Save')}</Button>}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
