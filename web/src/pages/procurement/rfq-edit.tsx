import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useFieldArray, useForm } from 'react-hook-form';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { usePartners } from '@/features/partners/api';
import { useProducts } from '@/features/products/api';
import { useRfq, useSaveRfq } from '@/features/procurement/api';
import { apiMessage } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const selectClass = 'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm';

interface FormValues {
  title: string;
  dueDate?: string;
  currencyCode: string;
  notes?: string;
  lines: { productId?: string; description: string; quantity: number }[];
}

/** Create an RFQ, or edit a draft one. */
export function RfqEditPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const org = useAuthStore((s) => s.organization);
  const existing = useRfq(id);
  const save = useSaveRfq();
  const partners = usePartners({ page: 1, pageSize: 200 });
  const products = useProducts({ page: 1, pageSize: 200 });
  const [supplierIds, setSupplierIds] = useState<string[]>([]);
  const [formError, setFormError] = useState<string | null>(null);

  const { register, control, handleSubmit, reset, setValue, formState: { errors } } = useForm<FormValues>({
    defaultValues: { title: '', dueDate: '', currencyCode: org?.currencyCode ?? '', notes: '', lines: [{ description: '', quantity: 1 }] },
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'lines' });
  const productList = products.data?.data ?? [];
  const supplierList = (partners.data?.data ?? []).filter((p) => p.isSupplier);

  useEffect(() => {
    const rfq = existing.data;
    if (!rfq) return;
    reset({
      title: rfq.title,
      dueDate: rfq.dueDate ?? '',
      currencyCode: rfq.currencyCode,
      notes: rfq.notes ?? '',
      lines: rfq.lines.map((l) => ({
        productId: l.productId ?? undefined,
        description: l.description,
        quantity: Number(l.quantity),
      })),
    });
    setSupplierIds(rfq.suppliers.map((s) => s.partnerId));
  }, [existing.data, reset]);

  const toggleSupplier = (partnerId: string) =>
    setSupplierIds((ids) => (ids.includes(partnerId) ? ids.filter((x) => x !== partnerId) : [...ids, partnerId]));

  const onProduct = (index: number, productId: string) => {
    setValue(`lines.${index}.productId`, productId || undefined);
    const product = productList.find((p) => p.id === productId);
    if (product) setValue(`lines.${index}.description`, product.name);
  };

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id,
        input: {
          title: values.title,
          dueDate: values.dueDate || undefined,
          currencyCode: values.currencyCode,
          notes: values.notes || undefined,
          lines: values.lines.map((l) => ({
            productId: l.productId || undefined,
            description: l.description,
            quantity: Number(l.quantity),
          })),
          supplierIds,
        },
      });
      navigate(`/procurement/rfqs/${saved.id}`);
    } catch (error) {
      setFormError(apiMessage(error, tx('Could not save the RFQ')));
    }
  });

  if (id && existing.isLoading) return <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>;
  if (id && existing.data?.status !== 'draft') {
    return <div className="text-sm text-muted-foreground">{tx('Only a draft RFQ can be edited.')}</div>;
  }

  return (
    <form onSubmit={onSubmit} className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold">{id ? tx('Edit RFQ') : tx('New RFQ')}</h1>
        <div className="flex gap-2">
          <Button type="button" variant="ghost" onClick={() => navigate(id ? `/procurement/rfqs/${id}` : '/procurement/rfqs')}>
            {tx('Cancel')}
          </Button>
          <Button type="submit" disabled={save.isPending}>{tx('Save draft')}</Button>
        </div>
      </div>

      {formError && <p className="text-sm text-destructive">{formError}</p>}

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Details')}</CardTitle></CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-3">
          <div className="space-y-2 sm:col-span-3">
            <Label htmlFor="title">{tx('Title')}</Label>
            <Input id="title" {...register('title', { required: true })} />
            {errors.title && <p className="text-xs text-destructive">{tx('Title is required')}</p>}
          </div>
          <div className="space-y-2">
            <Label htmlFor="dueDate">{tx('Due date')}</Label>
            <Input id="dueDate" type="date" {...register('dueDate')} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="currencyCode">{tx('Currency')}</Label>
            <Input id="currencyCode" {...register('currencyCode', { required: true })} />
          </div>
          <div className="space-y-2 sm:col-span-3">
            <Label htmlFor="notes">{tx('Notes')}</Label>
            <Textarea id="notes" rows={2} {...register('notes')} />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Lines')}</CardTitle>
          <Button type="button" variant="outline" size="sm" onClick={() => append({ description: '', quantity: 1 })}>
            <Plus className="h-4 w-4" /> {tx('Add line')}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {fields.map((field, index) => (
            <div key={field.id} className="grid grid-cols-2 items-end gap-2 md:grid-cols-12">
              <div className="col-span-2 md:col-span-4">
                <Label className="text-xs">{tx('Product')}</Label>
                <select
                  className={selectClass}
                  defaultValue={field.productId ?? ''}
                  onChange={(e) => onProduct(index, e.target.value)}
                >
                  <option value="">{tx('— none —')}</option>
                  {productList.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </div>
              <div className="col-span-2 md:col-span-5">
                <Label className="text-xs">{tx('Description')}</Label>
                <Input {...register(`lines.${index}.description`, { required: true })} />
              </div>
              <div className="col-span-1 md:col-span-2">
                <Label className="text-xs">{tx('Qty')}</Label>
                <Input
                  type="number"
                  step="any"
                  min="0.001"
                  {...register(`lines.${index}.quantity`, { valueAsNumber: true, required: true })}
                />
              </div>
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
            </div>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Suppliers to invite')}</CardTitle></CardHeader>
        <CardContent className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {supplierList.map((p) => (
            <label key={p.id} className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={supplierIds.includes(p.id)} onChange={() => toggleSupplier(p.id)} />
              {p.name}
            </label>
          ))}
        </CardContent>
      </Card>
    </form>
  );
}
