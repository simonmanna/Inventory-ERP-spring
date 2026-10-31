import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useFieldArray, useForm } from 'react-hook-form';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { money } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { usePartners } from '@/features/partners/api';
import { useProducts } from '@/features/products/api';
import { useAccounts } from '@/features/accounting/api';
import {
  useCreateExpense,
  useExpense,
  usePostExpense,
  useUpdateExpense,
  type CreateExpenseInput,
} from '@/features/invoicing/api';
import { tx } from '@/lib/i18n/tx';
import { UnitSelect } from '@/components/unit-select';
import { defaultUnitId, unitPrice as priceForUnit } from '@/features/products/units';

const selectClass =
  'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

interface LineForm {
  productId?: string;
  /** GL account of a line without a product (services, utilities). */
  accountId?: string;
  description?: string;
  quantity: number;
  unitPrice?: number;
  discountPercent?: number;
  /** Product unit (ADR-S003). */
  uomId?: string;
}
interface FormValues {
  partnerId: string;
  issueDate: string;
  dueDate?: string;
  reference?: string;
  notes?: string;
  lines: LineForm[];
}

const finite = (n: number | undefined): number | undefined => (Number.isFinite(n) ? Number(n) : undefined);

/** Create a vendor bill, or edit a draft one (`/vendor-bills/:id/edit`). */
export function VendorBillEditPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const partners = usePartners({ page: 1, pageSize: 200 });
  const products = useProducts({ page: 1, pageSize: 200 });
  const accounts = useAccounts();
  const existing = useExpense(id);
  const createBill = useCreateExpense();
  const updateBill = useUpdateExpense();
  const postBill = usePostExpense();
  const [formError, setFormError] = useState<string | null>(null);

  const today = new Date().toISOString().slice(0, 10);
  const { register, control, handleSubmit, watch, setValue, getValues, reset, formState: { errors } } =
    useForm<FormValues>({
      defaultValues: { partnerId: '', issueDate: today, dueDate: '', reference: '', notes: '', lines: [{ quantity: 1 }] },
    });
  const { fields, append, remove } = useFieldArray({ control, name: 'lines' });
  const lines = watch('lines');
  const productList = products.data?.data ?? [];
  const supplierList = (partners.data?.data ?? []).filter((p) => p.isSupplier);
  const expenseAccounts = (accounts.data?.data ?? []).filter((a) => !a.isGroup && a.isActive);

  useEffect(() => {
    const bill = existing.data;
    if (!bill) return;
    reset({
      partnerId: bill.partnerId,
      issueDate: bill.issueDate.slice(0, 10),
      dueDate: bill.dueDate ? bill.dueDate.slice(0, 10) : '',
      reference: bill.reference ?? '',
      notes: bill.notes ?? '',
      lines: bill.lines.map((l) => ({
        productId: l.productId ?? undefined,
        accountId: l.accountId ?? undefined,
        description: l.description,
        quantity: Number(l.quantity),
        unitPrice: Number(l.unitPrice),
        discountPercent: Number(l.discountPercent),
        uomId: l.uomId ?? undefined,
      })),
    });
  }, [existing.data, reset]);

  const lineTotal = (l?: LineForm): number => {
    if (!l) return 0;
    const price = finite(l.unitPrice) ?? 0;
    const qty = finite(l.quantity) ?? 0;
    const disc = finite(l.discountPercent) ?? 0;
    return qty * price * (1 - disc / 100);
  };
  const subtotal = (lines ?? []).reduce((s, l) => s + lineTotal(l), 0);

  const onProductChange = (index: number, productId: string) => {
    setValue(`lines.${index}.productId`, productId);
    const p = productList.find((x) => x.id === productId);
    if (p) {
      const unit = defaultUnitId(p, 'purchase');
      setValue(`lines.${index}.description`, p.name);
      setValue(`lines.${index}.accountId`, undefined);
      setValue(`lines.${index}.uomId`, unit);
      const price = priceForUnit(p, unit, 'purchase');
      if (price !== null) setValue(`lines.${index}.unitPrice`, Number(price.toFixed(6)));
    }
  };

  const onUnitChange = (index: number, uomId: string) => {
    setValue(`lines.${index}.uomId`, uomId);
    const p = productList.find((x) => x.id === getValues(`lines.${index}.productId`));
    const price = priceForUnit(p, uomId, 'purchase');
    if (price !== null) setValue(`lines.${index}.unitPrice`, Number(price.toFixed(6)));
  };

  const onSubmit = handleSubmit(async (values, event) => {
    const submitter = (event?.nativeEvent as SubmitEvent | undefined)?.submitter as HTMLButtonElement | undefined;
    const action = submitter?.value ?? 'save';
    setFormError(null);

    const payload: CreateExpenseInput = {
      partnerId: values.partnerId,
      issueDate: values.issueDate,
      dueDate: values.dueDate || undefined,
      reference: values.reference || undefined,
      notes: values.notes || undefined,
      lines: values.lines.map((l) => ({
        productId: l.productId || undefined,
        accountId: l.productId ? undefined : l.accountId || undefined,
        description: l.description || undefined,
        quantity: finite(l.quantity) ?? 1,
        unitPrice: finite(l.unitPrice),
        discountPercent: finite(l.discountPercent),
        uomId: l.productId ? l.uomId || undefined : undefined,
      })),
    };

    try {
      const billId = id ? (await updateBill.mutateAsync({ id, input: payload })).id : (await createBill.mutateAsync(payload)).id;
      if (action === 'post') {
        try {
          await postBill.mutateAsync({ id: billId });
        } catch (error) {
          // The bill is saved; its detail page offers the overrides for match and approval holds.
          notify.warning(apiMessage(error, tx('Could not post the bill')));
        }
      }
      navigate(`/vendor-bills/${billId}`);
    } catch (error) {
      setFormError(apiMessage(error, tx('Could not save the bill')));
    }
  });

  const busy = createBill.isPending || updateBill.isPending || postBill.isPending;

  if (id && existing.isLoading) {
    return <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>;
  }
  if (id && (existing.isError || !existing.data)) {
    return <div className="text-sm text-destructive">{tx('Could not load this bill.')}</div>;
  }
  if (id && existing.data?.status !== 'draft') {
    return <div className="text-sm text-muted-foreground">{tx('Only draft bills can be edited.')}</div>;
  }

  return (
    <form onSubmit={onSubmit} className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold">{id ? tx('Edit vendor bill') : tx('New vendor bill')}</h1>
        <div className="flex gap-2">
          <Button type="button" variant="ghost" onClick={() => navigate(id ? `/vendor-bills/${id}` : '/vendor-bills')}>
            {tx('Cancel')}
          </Button>
          <Button type="submit" name="action" value="save" variant="outline" disabled={busy}>
            {tx('Save draft')}
          </Button>
          <Button type="submit" name="action" value="post" disabled={busy}>
            {tx('Save & Post')}
          </Button>
        </div>
      </div>

      {formError && <p className="text-sm text-destructive">{formError}</p>}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Details')}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-2">
            <Label>{tx('Supplier')}</Label>
            <select className={selectClass} {...register('partnerId', { required: true })}>
              <option value="">{tx('Select supplier')}</option>
              {supplierList.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            {errors.partnerId && <p className="text-xs text-destructive">{tx('Supplier is required')}</p>}
          </div>
          <div className="space-y-2">
            <Label htmlFor="reference">{tx('Reference')}</Label>
            <Input id="reference" {...register('reference')} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="issueDate">{tx('Bill date')}</Label>
            <Input id="issueDate" type="date" max={today} {...register('issueDate', { required: true })} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="dueDate">{tx('Due date')}</Label>
            <Input id="dueDate" type="date" {...register('dueDate')} />
          </div>
          <div className="space-y-2 sm:col-span-2">
            <Label htmlFor="notes">{tx('Notes')}</Label>
            <Textarea id="notes" rows={2} {...register('notes')} />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Lines')}</CardTitle>
          <Button type="button" variant="outline" size="sm" onClick={() => append({ quantity: 1 })}>
            <Plus className="h-4 w-4" /> {tx('Add line')}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {fields.map((field, index) => {
            const productId = watch(`lines.${index}.productId`);
            return (
              <div key={field.id} className="grid grid-cols-2 items-end gap-2 border-b pb-3 md:grid-cols-12 md:border-0 md:pb-0">
                <div className="col-span-2 md:col-span-3">
                  <Label className="text-xs">{tx('Product')}</Label>
                  <select
                    className={selectClass}
                    value={productId ?? ''}
                    onChange={(e) => onProductChange(index, e.target.value)}
                  >
                    <option value="">{tx('— none —')}</option>
                    {productList.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="col-span-2">
                  {productId ? (
                    <>
                      <Label className="text-xs">{tx('Description')}</Label>
                      <Input {...register(`lines.${index}.description`)} />
                    </>
                  ) : (
                    <>
                      <Label className="text-xs">{tx('Account')}</Label>
                      <select className={selectClass} {...register(`lines.${index}.accountId`)}>
                        <option value="">{tx('Default expense account')}</option>
                        {expenseAccounts.map((a) => (
                          <option key={a.id} value={a.id}>
                            {a.code} {a.name}
                          </option>
                        ))}
                      </select>
                    </>
                  )}
                </div>
                <div className="col-span-1">
                  <Label className="text-xs">{tx('Qty')}</Label>
                  <Input
                    type="number"
                    step="any"
                    min="0"
                    {...register(`lines.${index}.quantity`, { valueAsNumber: true, required: true })}
                  />
                </div>
                <div className="col-span-1 pb-1.5">
                  <Label className="text-xs">{tx('Unit')}</Label>
                  <UnitSelect
                    className={selectClass}
                    product={productList.find((p) => p.id === productId)}
                    role="purchase"
                    value={watch(`lines.${index}.uomId`)}
                    onChange={(uomId) => onUnitChange(index, uomId)}
                  />
                </div>
                <div className="col-span-1 md:col-span-2">
                  <Label className="text-xs">{tx('Unit price')}</Label>
                  <Input
                    type="number"
                    step="any"
                    min="0.01"
                    {...register(`lines.${index}.unitPrice`, { valueAsNumber: true, required: true })}
                  />
                </div>
                <div className="col-span-1">
                  <Label className="text-xs">{tx('Disc %')}</Label>
                  <Input
                    type="number"
                    step="any"
                    min="0"
                    max="100"
                    {...register(`lines.${index}.discountPercent`, { valueAsNumber: true })}
                  />
                </div>
                <div className="col-span-1 pb-2 text-right text-sm">{money(lineTotal(lines?.[index]))}</div>
                <div className="col-span-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={tx('Remove line')}
                    onClick={() => remove(index)}
                    disabled={fields.length <= 1}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
                {!productId && (
                  <div className="col-span-2 md:col-span-12">
                    <Input placeholder={tx('Description')} {...register(`lines.${index}.description`, { required: true })} />
                  </div>
                )}
              </div>
            );
          })}

          <div className="flex justify-end border-t pt-3">
            <div className="w-64 space-y-1 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">{tx('Subtotal (net of discount)')}</span>
                <span>{money(subtotal)}</span>
              </div>
              <p className="text-xs text-muted-foreground">
                {tx('Tax is applied from each product when the bill is posted.')}
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </form>
  );
}
