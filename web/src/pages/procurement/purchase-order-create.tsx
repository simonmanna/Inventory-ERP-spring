import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, Trash2, Save } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useBranchChoices, useDepartmentChoices, type BudgetCheck } from '@/features/approval-controls/api';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { UnitSelect } from '@/components/unit-select';
import { defaultUnitId, unitPrice, type UnitAwareProduct } from '@/features/products/units';

interface Partner { id: string; name: string; code: string }
interface Product extends UnitAwareProduct { id: string; name: string; code: string; taxId?: string }
interface Warehouse { id: string; name: string; code: string }

interface Line {
  productId?: string;
  description: string;
  quantity: number;
  unitPrice: number;
  taxId?: string;
  /** Unit the quantity and price are in (ADR-S003); defaults to the product's purchase unit. */
  unitOfMeasureId?: string;
}

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
  const [lines, setLines] = useState<Line[]>([{ description: '', quantity: 1, unitPrice: 0 }]);

  const partners = useQuery<Partner[]>({
    queryKey: ['partners-supplier'],
    queryFn: async () => {
      // Suppliers only (ADR-PROCUREMENT-001): the old `search=SUPP` text match missed most suppliers.
      const res = await api.get<Partner[]>('/procurement/suppliers/options');
      return res.data ?? [];
    },
  });
  const products = useQuery<Product[]>({
    queryKey: ['products'],
    queryFn: async () => {
      const res = await api.get<{ data: Product[] }>('/products?pageSize=200');
      return res.data.data ?? [];
    },
  });
  const warehouses = useQuery<Warehouse[]>({
    queryKey: ['warehouses'],
    queryFn: async () => {
      const res = await api.get<{ data: Warehouse[] }>('/inventory/locations?type=warehouse');
      return res.data.data ?? [];
    },
  });

  const create = useMutation({
    mutationFn: async () =>
      (await api.post('/procurement/purchase-orders', {
        partnerId,
        description,
        paymentType,
        warehouseId,
        branchId: branchId || undefined,
        departmentId: departmentId || undefined,
        requestId,
        lines: lines.filter((l) => l.quantity > 0),
      })).data,
    onSuccess: (data: any) => {
      notify.success(tx('Purchase {{orderNumber}} created', { orderNumber: data.orderNumber }));
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
      navigate('/procurement/purchase-orders');
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Failed')),
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

  const addLine = () => setLines([...lines, { description: '', quantity: 1, unitPrice: 0 }]);
  const removeLine = (idx: number) => setLines(lines.filter((_, i) => i !== idx));
  const pickProduct = (idx: number, productId: string) => {
    const product = products.data?.find((p) => p.id === productId);
    if (!product) return;
    const next = [...lines];
    const unitOfMeasureId = defaultUnitId(product, 'purchase');
    next[idx] = {
      productId,
      description: product.name,
      quantity: next[idx].quantity || 1,
      unitPrice: unitPrice(product, unitOfMeasureId, 'purchase') ?? Number(product.costPrice ?? product.salesPrice ?? 0),
      taxId: product.taxId,
      unitOfMeasureId,
    };
    setLines(next);
  };
  const pickUnit = (idx: number, uomId: string) => {
    const product = products.data?.find((p) => p.id === lines[idx].productId);
    const next = [...lines];
    const price = unitPrice(product, uomId, 'purchase');
    next[idx] = { ...next[idx], unitOfMeasureId: uomId, unitPrice: price ?? next[idx].unitPrice };
    setLines(next);
  };

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
            <select
              value={partnerId}
              onChange={(e) => setPartnerId(e.target.value)}
              className="mt-1 w-full rounded border bg-background px-2 py-1 text-sm"
            >
              <option value="">{tx('Select supplier…')}</option>
              {partners.data?.map((p) => (
                <option key={p.id} value={p.id}>{p.code} — {p.name}</option>
              ))}
            </select>
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
            <select
              value={warehouseId}
              onChange={(e) => setWarehouseId(e.target.value)}
              className="mt-1 w-full rounded border bg-background px-2 py-1 text-sm"
            >
              <option value="">{tx('Select warehouse…')}</option>
              {warehouses.data?.map((w) => (
                <option key={w.id} value={w.id}>{w.code} — {w.name}</option>
              ))}
            </select>
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
            {lines.map((ln, idx) => (
              <div key={idx} className="grid gap-2 md:grid-cols-12 items-center">
                <div className="md:col-span-4">
                  <select
                    className="w-full rounded border bg-background px-2 py-1 text-sm"
                    onChange={(e) => pickProduct(idx, e.target.value)}
                    defaultValue=""
                  >
                    <option value="">{tx('Pick product (optional)…')}</option>
                    {products.isLoading ? (
                      <option>{tx('Loading…')}</option>
                    ) : (
                      products.data?.map((p) => (
                        <option key={p.id} value={p.id}>{p.code} — {p.name}</option>
                      ))
                    )}
                  </select>
                </div>
                <div className="md:col-span-3">
                  <Input
                    placeholder={tx('Description')}
                    value={ln.description}
                    onChange={(e) => {
                      const next = [...lines];
                      next[idx] = { ...next[idx], description: e.target.value };
                      setLines(next);
                    }}
                  />
                </div>
                <div className="md:col-span-1">
                  <Input
                    type="number"
                    step="0.01"
                    min="0"
                    value={ln.quantity}
                    onChange={(e) => {
                      const next = [...lines];
                      next[idx] = { ...next[idx], quantity: Number(e.target.value) };
                      setLines(next);
                    }}
                  />
                </div>
                <div className="md:col-span-1">
                  <UnitSelect
                    product={products.data?.find((p) => p.id === ln.productId)}
                    role="purchase"
                    value={ln.unitOfMeasureId}
                    onChange={(uomId) => pickUnit(idx, uomId)}
                  />
                </div>
                <div className="md:col-span-2">
                  <Input
                    type="number"
                    step="0.01"
                    min="0"
                    value={ln.unitPrice}
                    onChange={(e) => {
                      const next = [...lines];
                      next[idx] = { ...next[idx], unitPrice: Number(e.target.value) };
                      setLines(next);
                    }}
                  />
                </div>
                <div className="md:col-span-1 text-right text-sm tabular-nums">
                  {(ln.quantity * ln.unitPrice).toFixed(2)}
                </div>
                <div className="md:col-span-0">
                  <Button size="icon" variant="ghost" onClick={() => removeLine(idx)} aria-label={tx('Remove line')}>
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
          disabled={!partnerId || !warehouseId || lines.length === 0 || create.isPending}
        >
          <Save className="mr-2 h-4 w-4" />
          {create.isPending ? tx('Saving…') : tx('Save Purchase')}
        </Button>
      </div>
    </div>
  );
}
