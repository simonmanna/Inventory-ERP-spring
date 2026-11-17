import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, Trash2, Save } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { PRODUCT_PICKER_PANEL, SearchableSelect } from '@/components/ui/searchable-select';
import { useBranchChoices, useDepartmentChoices, type BudgetCheck } from '@/features/approval-controls/api';
import { useSupplierPicker } from '@/features/procurement/supplier-picker';
import { locationOption, useStockLocations } from '@/features/inventory/locations';
import { useProductSearch } from '@/features/products/product-search';
import { api } from '@/lib/api';
import { notifyApiError } from '@/lib/api-error';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { UnitSelect } from '@/components/unit-select';
import {
  defaultUnitId, unitPrice, unitPriceUnknown, withVariantPrices, type UnitAwareProduct,
} from '@/features/products/units';
import { VariantSelect } from '@/features/products/variant-select';
import { cachedVariantOption, type VariantOption } from '@/features/products/variants-api';

interface Product extends UnitAwareProduct { id: string; name: string; code: string; taxId?: string }

interface Line {
  /** Stable row identity: with index keys, removing line 1 left each dropdown showing the row it used to belong to. */
  uid: string;
  productId?: string;
  /** ADR-S058: product variant (required for a product with variants). */
  variantId?: string | null;
  description: string;
  quantity: number;
  unitPrice: number;
  taxId?: string;
  /** Unit the quantity and price are in (ADR-S003); defaults to the product's purchase unit. */
  unitOfMeasureId?: string;
  /** Screen only: the unit's conversion is unknown here, so the price was cleared and must be typed. */
  priceUnknown?: boolean;
}

let lineSeq = 0;
const newLine = (): Line => ({ uid: `line-${++lineSeq}`, description: '', quantity: 1, unitPrice: 0 });

export function PurchaseOrderCreatePage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [partnerId, setPartnerId] = useState('');
  const [description, setDescription] = useState('');
  const [paymentType, setPaymentType] = useState<'cash' | 'credit'>('cash');
  const [warehouseId, setWarehouseId] = useState('');
  const [branchId, setBranchId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [searchParams] = useSearchParams();
  const requestId = searchParams.get('requestId') ?? undefined;
  const branches = useBranchChoices();
  const departments = useDepartmentChoices();
  const [lines, setLines] = useState<Line[]>(() => [newLine()]);

  // Suppliers only (ADR-PROCUREMENT-001), searched on the server: the unsearched list stops at the first 50.
  const suppliers = useSupplierPicker();
  // Searched on the server: one page of 200 products left product #201 unpickable.
  const products = useProductSearch<Product>();
  // Every warehouse: the default page held only the 25 newest locations.
  const warehouses = useStockLocations({ type: 'warehouse' });

  const create = useMutation({
    // A stable key per payload: a retry after a lost answer reuses it (see idempotent-request.ts).
    mutationFn: async () =>
      idempotentPost('/procurement/purchase-orders', {
        partnerId,
        description,
        paymentType,
        warehouseId,
        branchId: branchId || undefined,
        departmentId: departmentId || undefined,
        requestId,
        lines: lines.filter((l) => l.quantity > 0).map((l) => ({
          productId: l.productId,
          variantId: l.variantId,
          description: l.description,
          quantity: l.quantity,
          unitPrice: l.unitPrice,
          taxId: l.taxId,
          unitOfMeasureId: l.unitOfMeasureId,
        })),
      }),
    onSuccess: (data: any) => {
      notify.success(tx('Purchase {{orderNumber}} created', { orderNumber: data.orderNumber }));
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
      navigate('/procurement/purchase-orders');
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed')),
  });

  const total = lines.reduce((s, l) => s + Number(l.quantity || 0) * Number(l.unitPrice || 0), 0);

  const budgetCheck = useQuery<BudgetCheck[]>({
    queryKey: ['budget-check', branchId, departmentId, requestId, lines],
    enabled: total > 0,
    retry: false,
    queryFn: async () => (await api.post('/procurement/budget-check', {
      kind: 'purchase_order',
      branchId: branchId || undefined,
      departmentId: departmentId || undefined,
      fromRequestId: requestId,
      lines: lines.filter((l) => l.quantity > 0).map((l) => ({
        productId: l.productId,
        amount: Number(l.quantity || 0) * Number(l.unitPrice || 0),
      })),
    })).data,
  });
  const budgetAlerts = budgetCheck.data?.filter((b) => b.exceeded || b.warning) ?? [];

  const addLine = () => setLines([...lines, newLine()]);
  const removeLine = (uid: string) => setLines(lines.filter((l) => l.uid !== uid));
  const updateLine = (uid: string, patch: Partial<Line>) =>
    setLines((cur) => cur.map((l) => (l.uid === uid ? { ...l, ...patch } : l)));
  /**
   * Purchase price of a line in `uomId` from the cost (the variant's when one is picked; costs are per base unit).
   * `unknown` when the unit cannot be converted here: the price is then cleared for the user to type, never assumed.
   */
  const costFor = (product: Product | undefined, variant: VariantOption | null | undefined, uomId: string | undefined) => {
    const priced = withVariantPrices(product, variant);
    return { price: unitPrice(priced, uomId, 'purchase'), unknown: unitPriceUnknown(priced, uomId, 'purchase') };
  };
  const pickProduct = (line: Line, productId: string) => {
    const product = products.get(productId);
    if (!product) return;
    const unitOfMeasureId = defaultUnitId(product, 'purchase');
    const { price, unknown } = costFor(product, null, unitOfMeasureId);
    setLines((cur) => cur.map((l) => (l.uid !== line.uid ? l : {
      uid: l.uid,
      productId,
      description: product.name,
      quantity: l.quantity || 1,
      // Never the sales price: no known cost leaves the price for the user.
      unitPrice: price ?? 0,
      taxId: product.taxId,
      unitOfMeasureId,
      priceUnknown: unknown,
    })));
  };
  const pickUnit = (line: Line, uomId: string) => {
    const product = products.get(line.productId);
    const { price, unknown } = costFor(product, cachedVariantOption(qc, line.productId, line.variantId), uomId);
    updateLine(line.uid, { unitOfMeasureId: uomId, unitPrice: unknown ? 0 : price ?? line.unitPrice, priceUnknown: unknown });
  };
  const pickVariant = (line: Line, variantId: string | null, opt: VariantOption | null) => {
    let pricing: Partial<Line> = {};
    if (opt && opt.costPrice != null) {
      // The variant cost is per base unit: scale it to the line's unit.
      const { price, unknown } = costFor(products.get(line.productId), opt, line.unitOfMeasureId);
      pricing = unknown ? { unitPrice: 0, priceUnknown: true } : price !== null ? { unitPrice: price, priceUnknown: false } : {};
    }
    updateLine(line.uid, { variantId, ...(opt ? { description: opt.displayName } : {}), ...pricing });
  };
  const priceMissing = lines.some((l) => l.quantity > 0 && l.priceUnknown);

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">{tx('New Purchase')}</h1>

      {budgetAlerts.map((b) => (
        <p key={b.budgetId} className={b.exceeded && b.controlMode === 'block' ? 'rounded border border-destructive p-2 text-sm text-destructive' : 'rounded border border-amber-500 p-2 text-sm text-amber-700'}>
          {b.name}: {Number(b.projected).toLocaleString()} / {Number(b.amount).toLocaleString()} ({Number(b.percent)}%)
        </p>
      ))}

      <Card>
        <CardHeader>
          <CardTitle>{tx('Supplier & details')}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-2">
          <div>
            <label className="text-sm font-medium">{tx('Supplier')}</label>
            <SearchableSelect
              className="mt-1"
              value={partnerId}
              onValueChange={setPartnerId}
              options={suppliers.options}
              onQueryChange={suppliers.onQueryChange}
              loading={suppliers.loading}
              footer={suppliers.footer}
              selectedLabel={suppliers.labelOf(partnerId)}
              placeholder={tx('Select supplier…')}
              searchPlaceholder={tx('Search suppliers…')}
            />
          </div>
          <div>
            <label className="text-sm font-medium">{tx('Description / notes')}</label>
            <Input
              className="mt-1"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={tx('Optional')}
            />
          </div>
          <div>
            <label className="text-sm font-medium">{tx('Payment type')}</label>
            <select
              value={paymentType}
              onChange={(e) => setPaymentType(e.target.value as 'cash' | 'credit')}
              className="mt-1 w-full rounded border bg-background px-2 py-1 text-sm"
            >
              <option value="cash">{tx('Cash — auto-pay on receive')}</option>
              <option value="credit">{tx('Credit — pay later')}</option>
            </select>
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
          <div>
            <label className="text-sm font-medium">{tx('Branch')}</label>
            <select
              value={branchId}
              onChange={(e) => setBranchId(e.target.value)}
              className="mt-1 w-full rounded border bg-background px-2 py-1 text-sm"
            >
              <option value="">{tx('Select a branch')}</option>
              {branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </div>
          <div>
            <label className="text-sm font-medium">{tx('Department')}</label>
            <select
              value={departmentId}
              onChange={(e) => setDepartmentId(e.target.value)}
              className="mt-1 w-full rounded border bg-background px-2 py-1 text-sm"
            >
              <option value="">{tx('All departments')}</option>
              {departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle>{tx('Products')}</CardTitle>
              <CardDescription>{tx('Fill products purchased')}</CardDescription>
            </div>
            <Button size="sm" variant="outline" onClick={addLine}><Plus className="mr-2 h-3 w-3" />{tx('Add item')}</Button>
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid gap-2 md:grid-cols-12 items-center text-xs text-muted-foreground font-medium px-1 pb-1 border-b mb-1">
            <div className="md:col-span-4">{tx('Product')}</div>
            <div className="md:col-span-3">{tx('Description')}</div>
            <div className="md:col-span-1 text-right">{tx('Qty')}</div>
            <div className="md:col-span-1">{tx('Unit')}</div>
            <div className="md:col-span-2 text-right">{tx('Unit Price')}</div>
            <div className="md:col-span-1 text-right">{tx('Total')}</div>
          </div>
          <div className="space-y-2">
            {lines.map((ln) => (
              <div key={ln.uid} className="grid gap-2 md:grid-cols-12 items-center">
                <div className="md:col-span-4">
                  {/* Controlled by the line's own product, so the screen always shows what will be saved. */}
                  <SearchableSelect
                    value={ln.productId ?? ''}
                    onValueChange={(v) => pickProduct(ln, v)}
                    options={products.options}
                    onQueryChange={products.onQueryChange}
                    loading={products.loading}
                    footer={products.footer}
                    selectedLabel={products.labelOf(ln.productId) ?? (ln.productId ? ln.description : null)}
                    placeholder={tx('Pick product (optional)…')}
                    searchPlaceholder={tx('Search by code or name…')}
                    contentClassName={PRODUCT_PICKER_PANEL}
                    wrap
                  />
                  <VariantSelect
                    productId={ln.productId}
                    value={ln.variantId}
                    onChange={(variantId, opt) => pickVariant(ln, variantId, opt)}
                  />
                </div>
                <div className="md:col-span-3">
                  <Input
                    placeholder={tx('Description')}
                    value={ln.description}
                    onChange={(e) => updateLine(ln.uid, { description: e.target.value })}
                  />
                </div>
                <div className="md:col-span-1">
                  <Input
                    type="number"
                    step="0.01"
                    min="0"
                    value={ln.quantity}
                    onChange={(e) => updateLine(ln.uid, { quantity: Number(e.target.value) })}
                  />
                </div>
                <div className="md:col-span-1">
                  <UnitSelect
                    product={products.get(ln.productId)}
                    role="purchase"
                    value={ln.unitOfMeasureId}
                    onChange={(uomId) => pickUnit(ln, uomId)}
                  />
                </div>
                <div className="md:col-span-2">
                  <Input
                    type="number"
                    step="0.01"
                    min="0"
                    value={ln.priceUnknown ? '' : ln.unitPrice}
                    className={ln.priceUnknown ? 'border-amber-500' : undefined}
                    onChange={(e) => updateLine(ln.uid, { unitPrice: Number(e.target.value), priceUnknown: false })}
                  />
                  {ln.priceUnknown && <p className="mt-0.5 text-xs text-amber-700">{tx('No conversion for this unit — enter the price')}</p>}
                </div>
                <div className="md:col-span-1 text-right text-sm tabular-nums">
                  {(ln.quantity * ln.unitPrice).toFixed(2)}
                </div>
                <div className="md:col-span-0">
                  <Button size="icon" variant="ghost" onClick={() => removeLine(ln.uid)} aria-label={tx('Remove line')}>
                    <Trash2 className="h-3 w-3 text-destructive" />
                  </Button>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-4 flex items-center justify-between border-t pt-3">
            <span className="text-sm font-medium">{tx('Total')}</span>
            <span className="text-lg font-bold tabular-nums">{total.toFixed(2)}</span>
          </div>
        </CardContent>
      </Card>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Cancel')}</Button>
        <Button
          onClick={() => create.mutate()}
          disabled={!partnerId || !warehouseId || lines.length === 0 || priceMissing || create.isPending}
        >
          <Save className="mr-2 h-4 w-4" />
          {create.isPending ? tx('Saving…') : tx('Save Purchase')}
        </Button>
      </div>
    </div>
  );
}
