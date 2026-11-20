import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, Trash2, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { InlineField, StatusRibbon } from '@/pages/sales/sales-ui';
import { money } from '@/lib/format';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { api } from '@/lib/api';
import { idempotentPatch, idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { describeApiError, notifyApiError } from '@/lib/api-error';
import { roundQty, subtractQty } from '@/lib/quantity';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import { UnitSelect } from '@/components/unit-select';
import { PRODUCT_PICKER_PANEL, SearchableSelect } from '@/components/ui/searchable-select';
import { defaultUnitId, type UnitAwareProduct } from '@/features/products/units';
import { VariantSelect } from '@/features/products/variant-select';
import { useProductSearch } from '@/features/products/product-search';
import { useSupplierPicker } from '@/features/procurement/supplier-picker';
import { useAuthStore } from '@/stores/auth.store';
import { locationOption, useStockLocations } from '@/features/inventory/locations';
import { useUomCode } from '@/features/uom/api';

interface Product extends UnitAwareProduct { id: string; name: string; code: string }
interface POLine {
  id: string; productId: string | null; description: string; quantity: number; receivedQuantity: number;
  unitPrice: number; unitOfMeasureId?: string | null; variantId?: string | null;
  variant?: { id: string; name: string; sku?: string | null } | null;
}
interface PO { id: string; orderNumber: string; partner?: { id: string; name: string } | null; lines: POLine[] }
interface POOption { id: string; orderNumber: string; status: string; partner?: { id: string; name: string } | null }

interface Line {
  /** Stable row identity: React keys must survive removing a line above. */
  uid: string;
  purchaseOrderLineId?: string;
  productId?: string;
  description: string;
  quantity: number;
  unitCost?: number;
  batchNumber?: string;
  expiryDate?: string;
  /** Unit of quantity/cost (ADR-S003). PO-backed lines always use the PO line's unit. */
  uomId?: string;
  /** ADR-S058: product variant (required for a product with variants). PO-backed lines keep the PO line's. */
  variantId?: string | null;
  /** Name of a PO-backed line's variant (from the PO), shown read-only. */
  variantName?: string;
}

let lineSeq = 0;
const lineId = () => `line-${++lineSeq}`;

/** Purchase orders that can still be received. */
const RECEIVABLE_STATUSES = ['active', 'partially_received'];

/**
 * Receivable purchase orders searched on the server by number, supplier or notes (the list takes one status at a
 * time, so both receivable statuses are asked for). Replaces the raw PO-id box.
 */
function useReceivablePurchaseOrders(enabled: boolean) {
  const [query, setQuery] = useState('');
  const search = useDebouncedValue(query.trim(), 250);
  const result = useQuery({
    queryKey: ['purchase-orders', 'receivable-options', search],
    enabled,
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    queryFn: async () => {
      const pages = await Promise.all(RECEIVABLE_STATUSES.map(async (status) => (await api.get<{
        data: POOption[]; meta?: { total?: number };
      }>('/procurement/purchase-orders', { params: { status, search: search || undefined, pageSize: 25 } })).data));
      return {
        rows: pages.flatMap((p) => p.data ?? []),
        total: pages.reduce((n, p) => n + (p.meta?.total ?? p.data?.length ?? 0), 0),
      };
    },
  });
  const rows = result.data?.rows ?? [];
  const total = result.data?.total ?? 0;
  return {
    options: rows.map((o) => ({ value: o.id, label: o.partner?.name ?? '—', code: o.orderNumber })),
    onQueryChange: setQuery,
    loading: result.isFetching,
    footer: total > rows.length
      ? tx('Showing {{shown}} of {{total}}: keep typing to narrow the list.', { shown: rows.length, total })
      : undefined,
  };
}

export function GoodsReceiptCreatePage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const uomCode = useUomCode();
  const [params] = useSearchParams();
  const poId = params.get('poId') ?? '';
  const [warehouseId, setWarehouseId] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<Line[]>(() => [{ uid: lineId(), description: '', quantity: 1, unitCost: 0 }]);
  // Blocker fix B1: a receipt names its supplier (from the PO, else picked here). Supplier bills are matched only to
  // that supplier's receipts, so a receipt without one was received a second time by the bill.
  const [partnerId, setPartnerId] = useState('');
  // Searched on the server as the user types (debounced); a full page of 50 says to keep typing.
  const suppliers = useSupplierPicker();
  // The order picker lists purchase orders, which needs purchase_order:read; without it the field is not offered.
  const canReadOrders = useAuthStore((s) => s.hasPermission)(PERMISSIONS.procurement.purchaseOrder.read);
  const purchaseOrders = useReceivablePurchaseOrders(canReadOrders);

  // Every warehouse: the default page held only the 25 newest locations.
  const warehouses = useStockLocations({ type: 'warehouse' });
  const po = useQuery<PO>({
    queryKey: ['po-for-grn', poId],
    queryFn: async () => (await api.get<PO>(`/procurement/purchase-orders/${poId}`)).data,
    enabled: !!poId,
  });
  // Searched on the server: one page of 200 products left the rest unpickable.
  const products = useProductSearch<Product>();

  // When PO loads, prefill lines from PO lines; without a PO, PO-backed lines no longer apply.
  useEffect(() => {
    if (po.data) {
      setLines(
        po.data.lines.map((ln) => ({
          uid: lineId(),
          purchaseOrderLineId: ln.id,
          productId: ln.productId ?? undefined,
          description: ln.description,
          uomId: ln.unitOfMeasureId ?? undefined,
          variantId: ln.variantId ?? undefined,
          variantName: ln.variant?.name ?? undefined,
          // Exact to 6 decimals: plain subtraction sent 0.20000000000000107 and was refused as over-receiving.
          quantity: Math.max(0, subtractQty(ln.quantity, ln.receivedQuantity)),
          unitCost: Number(ln.unitPrice) || 0,
        })).filter((l) => l.quantity > 0),
      );
    } else if (!poId) {
      setLines((cur) => (cur.some((l) => l.purchaseOrderLineId)
        ? [{ uid: lineId(), description: '', quantity: 1, unitCost: 0 }]
        : cur));
    }
  }, [po.data, poId]);

  const updateLine = (uid: string, patch: Partial<Line>) =>
    setLines((cur) => cur.map((l) => (l.uid === uid ? { ...l, ...patch } : l)));

  const create = useMutation({
    mutationFn: async () => {
      // A stable key per payload: a retry after a lost answer reuses it (see idempotent-request.ts).
      const data = await idempotentPost<{ id: string; receiptNumber: string }>('/procurement/goods-receipts', {
        purchaseOrderId: poId || undefined,
        // With a PO the server takes the PO's supplier.
        partnerId: poId ? undefined : partnerId || undefined,
        warehouseId,
        notes,
        lines: lines.filter((l) => l.description && l.quantity > 0).map((l) => ({
          purchaseOrderLineId: l.purchaseOrderLineId,
          productId: l.productId,
          description: l.description,
          quantity: roundQty(l.quantity),
          unitCost: l.unitCost,
          uomId: l.uomId,
          variantId: l.variantId || undefined,
          batchNumber: l.batchNumber || undefined,
          expiryDate: l.expiryDate || undefined,
        })),
      });
      // Immediately post it (commits the stock-in). The draft already exists, so a failed post is reported with it.
      try {
        await idempotentPatch(`/procurement/goods-receipts/${data.id}/post`);
        return { data, postError: null };
      } catch (e) {
        return { data, postError: describeApiError(e, tx('Posting failed')) };
      }
    },
    onSuccess: ({ data, postError }) => {
      qc.invalidateQueries({ queryKey: ['goods-receipts'] });
      if (postError) {
        // Go to the saved draft (post it from there) instead of leaving this form submittable for a duplicate.
        notify.error(tx('Saved as a draft, but posting failed: {{error}}', { error: postError }));
        navigate(`/procurement/goods-receipts/${data.id}`);
        return;
      }
      notify.success(tx('GRN {{receiptNumber}} posted', { receiptNumber: data.receiptNumber }));
      navigate(`/procurement/goods-receipts/${data.id}`);
    },
    onError: (e) => notifyApiError(e, tx('Could not create the goods receipt')),
  });

  const selectCls = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
  const total = lines.reduce((s, l) => s + Number(l.quantity || 0) * Number(l.unitCost || 0), 0);
  const canSave = !!warehouseId && (!!poId || !!partnerId) && lines.length > 0 && !create.isPending;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to="/procurement/goods-receipts" className="font-medium text-primary hover:underline">{tx('Goods Receipts')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{tx('New')}</span>
        </div>
        <div className="w-full max-w-xs"><StatusRibbon steps={[tx('Draft'), tx('Posted')]} current={0} /></div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => create.mutate()} disabled={!canSave}>
          <Truck className="mr-1.5 h-4 w-4" />{create.isPending ? tx('Saving…') : tx('Save & post')}
        </Button>
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Discard')}</Button>
        <span className="text-xs text-muted-foreground">
          {!warehouseId || (!poId && !partnerId)
            ? tx('Choose a supplier (or a purchase order) and a warehouse to save.')
            : tx('Receipts post stock-in moves automatically.')}
        </span>
      </div>

      <section className="rounded-xl border bg-card shadow-sm">
        <div className="px-5 pt-5">
          <h1 className="flex items-baseline gap-1.5 text-2xl font-bold">
            <span>{tx('New')}</span>
            <span className="text-sm font-medium text-muted-foreground">{tx('Goods receipt')}</span>
          </h1>
        </div>
        <div className="grid grid-cols-1 gap-x-12 px-5 pb-5 pt-4 md:grid-cols-2">
          <div>
            {canReadOrders && (
              <InlineField label={tx('Purchase order')}>
                <SearchableSelect
                  value={poId}
                  onValueChange={(v) => navigate(
                    v ? `/procurement/goods-receipts/new?poId=${encodeURIComponent(v)}` : '/procurement/goods-receipts/new',
                    { replace: true },
                  )}
                  options={purchaseOrders.options}
                  onQueryChange={purchaseOrders.onQueryChange}
                  loading={purchaseOrders.loading}
                  footer={purchaseOrders.footer}
                  selectedLabel={po.data ? `${po.data.orderNumber} — ${po.data.partner?.name ?? '—'}` : null}
                  placeholder={tx('Select a purchase order (auto-fills lines)…')}
                  searchPlaceholder={tx('Search by PO number or supplier…')}
                  emptyText={tx('No open purchase orders match')}
                  clearLabel={tx('No purchase order')}
                />
              </InlineField>
            )}
            <InlineField label={tx('Supplier')}>
              {poId ? (
                <span className="font-medium">{po.data?.partner?.name ?? (po.isLoading ? tx('Loading…') : '—')}</span>
              ) : (
                <div>
                  <SearchableSelect
                    value={partnerId}
                    onValueChange={setPartnerId}
                    options={suppliers.options}
                    onQueryChange={suppliers.onQueryChange}
                    selectedLabel={suppliers.labelOf(partnerId)}
                    loading={suppliers.loading}
                    footer={suppliers.footer}
                    placeholder={tx('Select supplier…')}
                    searchPlaceholder={tx('Search suppliers…')}
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    {tx("The supplier's bills are matched to this receipt, so the goods are not received twice.")}
                  </p>
                </div>
              )}
            </InlineField>
          </div>
          <div>
            <InlineField label={tx('Warehouse')}>
              <SearchableSelect
                value={warehouseId}
                onValueChange={setWarehouseId}
                options={(warehouses.locations ?? []).map(locationOption)}
                footer={warehouses.footer}
                placeholder={tx('Select warehouse…')}
                searchPlaceholder={tx('Search warehouses…')}
              />
            </InlineField>
            <InlineField label={tx('Notes')}>
              <Input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder={tx('Optional')} />
            </InlineField>
          </div>
        </div>

        <Tabs defaultValue="lines">
          <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0 px-5 pt-1.5">
            <TabsTrigger value="lines" className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{tx('Received lines')}</TabsTrigger>
          </TabsList>

          <TabsContent value="lines" className="mt-0 space-y-4 p-5">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[1000px] text-sm">
                <thead className="border-b text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-2">{tx('Product')}</th>
                    <th className="py-2 pr-2">{tx('Description')}</th>
                    <th className="w-24 py-2 pr-2 text-right">{tx('Quantity')}</th>
                    <th className="w-24 py-2 pr-2">{tx('UoM')}</th>
                    <th className="w-28 py-2 pr-2 text-right">{tx('Unit Cost')}</th>
                    <th className="w-28 py-2 pr-2">{tx('Batch #')}</th>
                    <th className="w-36 py-2 pr-2">{tx('Expiry')}</th>
                    <th className="w-28 py-2 pr-2 text-right">{tx('Subtotal')}</th>
                    <th className="w-10 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {lines.map((ln) => (
                    <tr key={ln.uid} className="border-b align-top">
                      <td className="py-1.5 pr-2">
                        <SearchableSelect
                          value={ln.productId ?? ''}
                          onValueChange={(v) => {
                            const product = products.get(v);
                            updateLine(ln.uid, {
                              productId: v,
                              variantId: null,
                              description: product?.name ?? ln.description,
                              uomId: defaultUnitId(product, 'purchase'),
                            });
                          }}
                          options={products.options}
                          onQueryChange={products.onQueryChange}
                          loading={products.loading}
                          footer={products.footer}
                          selectedLabel={products.labelOf(ln.productId) ?? (ln.productId ? ln.description : null)}
                          placeholder={tx('Pick product…')}
                          searchPlaceholder={tx('Search by code or name…')}
                          className="w-64"
                          contentClassName={PRODUCT_PICKER_PANEL}
                          wrap
                        />
                        {ln.purchaseOrderLineId && ln.variantName && (
                          <span className="mt-0.5 block text-xs text-muted-foreground">{ln.variantName}</span>
                        )}
                        {!ln.purchaseOrderLineId && (
                          <VariantSelect
                            productId={ln.productId}
                            value={ln.variantId}
                            className="w-64"
                            onChange={(variantId, opt) => updateLine(ln.uid, { variantId, ...(opt ? { description: opt.displayName } : {}) })}
                          />
                        )}
                      </td>
                      <td className="py-1.5 pr-2">
                        <Input
                          placeholder={tx('Description')}
                          value={ln.description}
                          onChange={(e) => updateLine(ln.uid, { description: e.target.value })}
                        />
                      </td>
                      <td className="py-1.5 pr-2">
                        <Input
                          type="number" step="0.01" min="0" className="text-right"
                          value={ln.quantity}
                          onChange={(e) => updateLine(ln.uid, { quantity: Number(e.target.value) })}
                        />
                      </td>
                      <td className="py-1.5 pr-2 pt-2.5">
                        {ln.purchaseOrderLineId ? (
                          // A PO-backed line is received in the PO line's unit.
                          <span className="text-sm text-muted-foreground">{uomCode(ln.uomId) || '—'}</span>
                        ) : (
                          <UnitSelect
                            product={products.get(ln.productId)}
                            role="purchase"
                            value={ln.uomId}
                            className={selectCls}
                            onChange={(uomId) => updateLine(ln.uid, { uomId })}
                          />
                        )}
                      </td>
                      <td className="py-1.5 pr-2">
                        <Input
                          type="number" step="0.01" min="0" className="text-right"
                          placeholder={tx('Cost')}
                          value={ln.unitCost ?? 0}
                          onChange={(e) => updateLine(ln.uid, { unitCost: Number(e.target.value) })}
                        />
                      </td>
                      <td className="py-1.5 pr-2">
                        <Input
                          placeholder={tx('Batch #')}
                          value={ln.batchNumber ?? ''}
                          onChange={(e) => updateLine(ln.uid, { batchNumber: e.target.value })}
                        />
                      </td>
                      <td className="py-1.5 pr-2">
                        <Input
                          type="date"
                          value={ln.expiryDate ?? ''}
                          onChange={(e) => updateLine(ln.uid, { expiryDate: e.target.value })}
                        />
                      </td>
                      <td className="py-1.5 pr-2 pt-3 text-right tabular-nums">
                        {money(Number(ln.quantity || 0) * Number(ln.unitCost || 0))}
                      </td>
                      <td className="py-1.5 pt-2">
                        <button type="button" className="rounded p-1 text-muted-foreground hover:bg-muted" aria-label={tx('Remove line')}
                          onClick={() => setLines(lines.filter((l) => l.uid !== ln.uid))}>
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Button variant="outline" size="sm" onClick={() => setLines([...lines, { uid: lineId(), description: '', quantity: 1 }])}>
              <Plus className="mr-1 h-4 w-4" />{tx('Add a line')}
            </Button>
            <div className="grid gap-4 border-t pt-4 md:grid-cols-[minmax(0,1fr)_320px]">
              <div />
              <div className="text-sm">
                <h2 className="mb-2 font-semibold">{tx('Totals')}</h2>
                <div className="flex items-center justify-between py-0.5"><span className="text-muted-foreground">{tx('Total Items')}</span><span className="tabular-nums">{lines.length}</span></div>
                <div className="mt-2 flex items-center justify-between border-t pt-2">
                  <span className="text-muted-foreground">{tx('Total Cost')}</span>
                  <strong className="text-lg tabular-nums">{money(total)}</strong>
                </div>
              </div>
            </div>
          </TabsContent>
        </Tabs>
      </section>
    </div>
  );
}
