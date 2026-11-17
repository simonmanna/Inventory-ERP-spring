import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, Trash2, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
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
      navigate('/procurement/goods-receipts');
    },
    onError: (e) => notifyApiError(e, tx('Could not create the goods receipt')),
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">{tx('New Goods Receipt')}</h1>

      <Card>
        <CardHeader>
          <CardTitle>{tx('Receipt details')}</CardTitle>
          <CardDescription>{tx('Receipts post stock-in moves automatically.')}</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-2">
          {canReadOrders && (<div>
            <label className="text-sm font-medium">{tx('PO (optional)')}</label>
            <SearchableSelect
              className="mt-1"
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
          </div>)}
          <div>
            <label className="text-sm font-medium">{tx('Supplier')}</label>
            {poId ? (
              <p className="mt-2 text-sm">{po.data?.partner?.name ?? (po.isLoading ? tx('Loading…') : '—')}</p>
            ) : (
              <SearchableSelect
                className="mt-1"
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
            )}
            {!poId && (
              <p className="mt-1 text-xs text-muted-foreground">
                {tx("The supplier's bills are matched to this receipt, so the goods are not received twice.")}
              </p>
            )}
          </div>
          <div>
            <label className="text-sm font-medium">{tx('Warehouse')}</label>
            <SearchableSelect
              className="mt-1"
              value={warehouseId}
              onValueChange={setWarehouseId}
              options={(warehouses.locations ?? []).map(locationOption)}
              footer={warehouses.footer}
              placeholder={tx('Select warehouse…')}
              searchPlaceholder={tx('Search warehouses…')}
            />
          </div>
          <div className="md:col-span-2">
            <label className="text-sm font-medium">{tx('Notes')}</label>
            <Input className="mt-1" value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>{tx('Lines')}</CardTitle>
            <Button size="sm" variant="outline" onClick={() => setLines([...lines, { uid: lineId(), description: '', quantity: 1 }])}>
              <Plus className="mr-2 h-3 w-3" />{tx('Add line')}
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-2">
          {lines.map((ln) => (
            <div key={ln.uid} className="grid gap-2 md:grid-cols-12 items-center">
              <div className="md:col-span-3">
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
                    onChange={(variantId, opt) => updateLine(ln.uid, { variantId, ...(opt ? { description: opt.displayName } : {}) })}
                  />
                )}
              </div>
              <div className="md:col-span-2">
                <Input
                  placeholder={tx('Description')}
                  value={ln.description}
                  onChange={(e) => updateLine(ln.uid, { description: e.target.value })}
                />
              </div>
              <div className="md:col-span-1">
                <Input
                  type="number" step="0.01" min="0"
                  value={ln.quantity}
                  onChange={(e) => updateLine(ln.uid, { quantity: Number(e.target.value) })}
                />
              </div>
              <div className="md:col-span-1">
                {ln.purchaseOrderLineId ? (
                  // A PO-backed line is received in the PO line's unit.
                  <span className="text-sm">{uomCode(ln.uomId) || '—'}</span>
                ) : (
                  <UnitSelect
                    product={products.get(ln.productId)}
                    role="purchase"
                    value={ln.uomId}
                    onChange={(uomId) => updateLine(ln.uid, { uomId })}
                  />
                )}
              </div>
              <div className="md:col-span-1">
                <Input
                  type="number" step="0.01" min="0"
                  placeholder={tx('Cost')}
                  value={ln.unitCost ?? 0}
                  onChange={(e) => updateLine(ln.uid, { unitCost: Number(e.target.value) })}
                />
              </div>
              <div className="md:col-span-1">
                <Input
                  placeholder={tx('Batch #')}
                  value={ln.batchNumber ?? ''}
                  onChange={(e) => updateLine(ln.uid, { batchNumber: e.target.value })}
                />
              </div>
              <div className="md:col-span-1">
                <Input
                  type="date"
                  value={ln.expiryDate ?? ''}
                  onChange={(e) => updateLine(ln.uid, { expiryDate: e.target.value })}
                />
              </div>
              <Button size="icon" variant="ghost" onClick={() => setLines(lines.filter((l) => l.uid !== ln.uid))}>
                <Trash2 className="h-3 w-3 text-destructive" />
              </Button>
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Cancel')}</Button>
        <Button
          onClick={() => create.mutate()}
          disabled={!warehouseId || (!poId && !partnerId) || lines.length === 0 || create.isPending}
        >
          <Truck className="mr-2 h-4 w-4" />{tx('Save & post')}
        </Button>
      </div>
    </div>
  );
}
