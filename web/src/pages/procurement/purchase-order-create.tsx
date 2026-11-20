import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, Trash2, Save } from 'lucide-react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { InlineField, StatusRibbon } from '@/pages/sales/sales-ui';
import { cn } from '@/lib/utils';
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
      navigate(data?.id ? `/procurement/purchase-orders/${data.id}` : '/procurement/purchase-orders');
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

  const selectCls = 'h-9 w-full rounded-md border bg-background px-2 text-sm';

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to="/procurement/purchase-orders" className="font-medium text-primary hover:underline">{tx('Purchases')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{tx('New')}</span>
        </div>
        <div className="w-full max-w-md">
          <StatusRibbon steps={[tx('Purchase order'), tx('Sent'), tx('Received'), tx('Paid')]} current={0} />
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          onClick={() => create.mutate()}
          disabled={!partnerId || !warehouseId || lines.length === 0 || priceMissing || create.isPending}
        >
          <Save className="mr-1.5 h-4 w-4" />
          {create.isPending ? tx('Saving…') : tx('Save Purchase')}
        </Button>
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Discard')}</Button>
        {(!partnerId || !warehouseId) && (
          <span className="text-xs text-muted-foreground">{tx('Choose a supplier and a warehouse to save.')}</span>
        )}
      </div>

      {budgetAlerts.map((b) => (
        <p key={b.budgetId} className={b.exceeded && b.controlMode === 'block' ? 'rounded-xl border border-destructive p-3 text-sm text-destructive' : 'rounded-xl border border-amber-500 p-3 text-sm text-amber-700'}>
          {b.name}: {Number(b.projected).toLocaleString()} / {Number(b.amount).toLocaleString()} ({Number(b.percent)}%)
        </p>
      ))}

      <section className="rounded-xl border bg-card shadow-sm">
        <div className="px-5 pt-5">
          <h1 className="flex items-baseline gap-1.5 text-2xl font-bold">
            <span>{tx('New')}</span>
            <span className="text-sm font-medium text-muted-foreground">{tx('Purchase order')}</span>
          </h1>
        </div>
        <div className="grid grid-cols-1 gap-x-12 px-5 pb-5 pt-4 md:grid-cols-2">
          <div>
            <InlineField label={tx('Supplier')}>
              <SearchableSelect
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
            </InlineField>
            <InlineField label={tx('Payment type')}>
              <select
                value={paymentType}
                onChange={(e) => setPaymentType(e.target.value as 'cash' | 'credit')}
                className={selectCls}
              >
                <option value="cash">{tx('Cash — auto-pay on receive')}</option>
                <option value="credit">{tx('Credit — pay later')}</option>
              </select>
            </InlineField>
            <InlineField label={tx('Description')}>
              <Input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={tx('Optional')}
              />
            </InlineField>
            {requestId && <InlineField label={tx('Source Doc')}><span className="text-muted-foreground">{tx('Purchase request')}</span></InlineField>}
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
            <InlineField label={tx('Branch')}>
              <select
                value={branchId}
                onChange={(e) => setBranchId(e.target.value)}
                className={selectCls}
              >
                <option value="">{tx('Select a branch')}</option>
                {branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
              </select>
            </InlineField>
            <InlineField label={tx('Department')}>
              <select
                value={departmentId}
                onChange={(e) => setDepartmentId(e.target.value)}
                className={selectCls}
              >
                <option value="">{tx('All departments')}</option>
                {departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </InlineField>
          </div>
        </div>

        <Tabs defaultValue="lines">
          <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0 px-5 pt-1.5">
            <TabsTrigger value="lines" className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{tx('Order lines')}</TabsTrigger>
          </TabsList>

          <TabsContent value="lines" className="mt-0 space-y-4 p-5">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[900px] text-sm">
                <thead className="border-b text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-2">{tx('Product')}</th>
                    <th className="py-2 pr-2">{tx('Description')}</th>
                    <th className="w-24 py-2 pr-2 text-right">{tx('Quantity')}</th>
                    <th className="w-28 py-2 pr-2">{tx('UoM')}</th>
                    <th className="w-32 py-2 pr-2 text-right">{tx('Unit price')}</th>
                    <th className="w-28 py-2 pr-2 text-right">{tx('Subtotal')}</th>
                    <th className="w-10 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {lines.map((ln) => (
                    <tr key={ln.uid} className="border-b align-top">
                      <td className="py-1.5 pr-2">
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
                          className="w-72"
                          contentClassName={PRODUCT_PICKER_PANEL}
                          wrap
                        />
                        <VariantSelect
                          productId={ln.productId}
                          value={ln.variantId}
                          className="w-72"
                          onChange={(variantId, opt) => pickVariant(ln, variantId, opt)}
                        />
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
                          type="number"
                          step="0.01"
                          min="0"
                          className="text-right"
                          value={ln.quantity}
                          onChange={(e) => updateLine(ln.uid, { quantity: Number(e.target.value) })}
                        />
                      </td>
                      <td className="py-1.5 pr-2">
                        <UnitSelect
                          product={products.get(ln.productId)}
                          role="purchase"
                          value={ln.unitOfMeasureId}
                          className={selectCls}
                          onChange={(uomId) => pickUnit(ln, uomId)}
                        />
                      </td>
                      <td className="py-1.5 pr-2">
                        <Input
                          type="number"
                          step="0.01"
                          min="0"
                          value={ln.priceUnknown ? '' : ln.unitPrice}
                          className={cn('text-right', ln.priceUnknown && 'border-amber-500')}
                          onChange={(e) => updateLine(ln.uid, { unitPrice: Number(e.target.value), priceUnknown: false })}
                        />
                        {ln.priceUnknown && <p className="mt-0.5 text-xs text-amber-700">{tx('No conversion for this unit — enter the price')}</p>}
                      </td>
                      <td className="py-1.5 pr-2 pt-3 text-right tabular-nums">
                        {(ln.quantity * ln.unitPrice).toFixed(2)}
                      </td>
                      <td className="py-1.5 pt-2">
                        <button type="button" className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => removeLine(ln.uid)} aria-label={tx('Remove line')}>
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Button variant="outline" size="sm" onClick={addLine}><Plus className="mr-1 h-4 w-4" />{tx('Add a line')}</Button>
            <div className="grid gap-4 border-t pt-4 md:grid-cols-[minmax(0,1fr)_320px]">
              <div />
              <div className="text-sm">
                <h2 className="mb-2 font-semibold">{tx('Totals')}<span className="ml-2 text-xs font-normal text-muted-foreground">{tx('(estimate — taxes are added on save)')}</span></h2>
                <div className="mt-2 flex items-center justify-between border-t pt-2">
                  <span className="text-muted-foreground">{tx('Total')}</span>
                  <strong className="text-lg tabular-nums">{total.toFixed(2)}</strong>
                </div>
              </div>
            </div>
          </TabsContent>
        </Tabs>
      </section>
    </div>
  );
}
