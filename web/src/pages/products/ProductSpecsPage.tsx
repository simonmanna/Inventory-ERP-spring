import { useEffect, useState } from 'react';
import { Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useProductCategories, useProducts } from '@/features/products/api';
import {
  useProductAttributes,
  useProductGrades,
  useProductSpecification,
  useSpecAction,
  type ProductAttribute,
} from '@/features/products/specs-api';

const SELECT = 'h-9 w-full rounded-md border bg-background px-3 text-sm';

/** Grades, attribute definitions and the specification of each product (INV-2). */
export function ProductSpecsPage() {
  const [tab, setTab] = useState('products');
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.product.update));
  return (
    <div className="space-y-4 p-6">
      <div>
        <h1 className="text-xl font-semibold">{tx('Product specifications')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Grades and attributes such as strength, size or packaging, set per product.')}
        </p>
      </div>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="products">{tx('Products')}</TabsTrigger>
          <TabsTrigger value="grades">{tx('Grades')}</TabsTrigger>
          <TabsTrigger value="attributes">{tx('Attributes')}</TabsTrigger>
        </TabsList>
        <TabsContent value="products" className="mt-4">
          <SpecificationEditor canEdit={canEdit} />
        </TabsContent>
        <TabsContent value="grades" className="mt-4">
          <GradesTab canEdit={canEdit} />
        </TabsContent>
        <TabsContent value="attributes" className="mt-4">
          <AttributesTab canEdit={canEdit} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function valueOf(values: { attributeId: string; valueText: string | null; valueNumber: number | string | null; valueBoolean: boolean | null }[], id: string) {
  const v = values.find((x) => x.attributeId === id);
  if (!v) return '';
  if (v.valueBoolean !== null) return String(v.valueBoolean);
  if (v.valueNumber !== null) return String(Number(v.valueNumber));
  return v.valueText ?? '';
}

function AttributeInput({ attribute, value, onChange }: { attribute: ProductAttribute; value: string; onChange: (v: string) => void }) {
  if (attribute.dataType === 'boolean') {
    return (
      <select className={SELECT} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">—</option>
        <option value="true">{tx('Yes')}</option>
        <option value="false">{tx('No')}</option>
      </select>
    );
  }
  if (attribute.dataType === 'option') {
    return (
      <select className={SELECT} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">—</option>
        {attribute.options.map((o) => <option key={o} value={o}>{o}</option>)}
      </select>
    );
  }
  return <Input type={attribute.dataType === 'number' ? 'number' : 'text'} value={value} onChange={(e) => onChange(e.target.value)} />;
}

function SpecificationEditor({ canEdit }: { canEdit: boolean }) {
  const [search, setSearch] = useState('');
  const [productId, setProductId] = useState('');
  const { data: products } = useProducts({ page: 1, pageSize: 50, search: search || undefined });
  const { data: spec, isLoading } = useProductSpecification(productId);
  const { data: grades } = useProductGrades();
  const action = useSpecAction();
  const [gradeId, setGradeId] = useState('');
  const [standardRef, setStandardRef] = useState('');
  const [specification, setSpecification] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!spec) return;
    setGradeId(spec.specification?.gradeId ?? '');
    setStandardRef(spec.specification?.standardRef ?? '');
    setSpecification(spec.specification?.specification ?? '');
    setValues(Object.fromEntries(spec.attributes.map((a) => [a.id, valueOf(spec.values, a.id)])));
  }, [spec]);

  const missing = (spec?.attributes ?? []).some((a) => a.isRequired && !values[a.id]);

  const save = async () => {
    try {
      await action.mutateAsync({
        method: 'put',
        path: `/product-specifications/${productId}`,
        body: {
          gradeId: gradeId || undefined,
          standardRef: standardRef || undefined,
          specification: specification || undefined,
          attributes: Object.entries(values)
            .filter(([, v]) => v !== '')
            .map(([attributeId, value]) => ({ attributeId, value })),
        },
      });
      notify.success(tx('Specification saved'));
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not save the specification')));
    }
  };

  return (
    <Card>
      <CardContent className="grid gap-4 pt-6 md:grid-cols-2">
        <div className="grid content-start gap-2">
          <Label>{tx('Product')}</Label>
          <Input placeholder={tx('Search')} value={search} onChange={(e) => setSearch(e.target.value)} />
          <select className={SELECT} size={10} style={{ height: 'auto' }} value={productId} onChange={(e) => setProductId(e.target.value)}>
            {(products?.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.code} — {p.name}</option>)}
          </select>
        </div>
        <div className="grid content-start gap-3">
          {!productId ? (
            <p className="py-8 text-center text-sm text-muted-foreground">{tx('Pick a product.')}</p>
          ) : isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : (
            <>
              <div className="grid gap-1.5">
                <Label>{tx('Grade')}</Label>
                <select className={SELECT} value={gradeId} onChange={(e) => setGradeId(e.target.value)} disabled={!canEdit}>
                  <option value="">—</option>
                  {(grades ?? []).filter((g) => g.isActive).map((g) => <option key={g.id} value={g.id}>{g.code} — {g.name}</option>)}
                </select>
              </div>
              <div className="grid gap-1.5">
                <Label>{tx('Standard')}</Label>
                <Input value={standardRef} onChange={(e) => setStandardRef(e.target.value)} disabled={!canEdit} />
              </div>
              {(spec?.attributes ?? []).map((a) => (
                <div key={a.id} className="grid gap-1.5">
                  <Label>{a.name}{a.unit ? ` (${a.unit})` : ''}{a.isRequired ? ' *' : ''}</Label>
                  <AttributeInput attribute={a} value={values[a.id] ?? ''} onChange={(v) => setValues((s) => ({ ...s, [a.id]: v }))} />
                </div>
              ))}
              <div className="grid gap-1.5">
                <Label>{tx('Specification')}</Label>
                <Textarea rows={3} value={specification} onChange={(e) => setSpecification(e.target.value)} disabled={!canEdit} />
              </div>
              {canEdit ? (
                <div>
                  <Button disabled={action.isPending || missing} onClick={save}>{tx('Save')}</Button>
                </div>
              ) : null}
            </>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

function GradesTab({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading, isError } = useProductGrades();
  const action = useSpecAction();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');

  const run = async (method: 'post' | 'delete', path: string, body?: unknown) => {
    try {
      await action.mutateAsync({ method, path, body });
      setCode('');
      setName('');
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('The action failed')));
    }
  };

  return (
    <Card>
      <CardContent className="space-y-4 pt-6">
        {canEdit ? (
          <div className="flex flex-wrap items-end gap-2">
            <div className="grid gap-1.5">
              <Label>{tx('Code')}</Label>
              <Input className="w-32" value={code} onChange={(e) => setCode(e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Name')}</Label>
              <Input className="w-56" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <Button size="sm" disabled={action.isPending || !code || !name} onClick={() => run('post', '/product-grades', { code, name })}>
              {tx('Add')}
            </Button>
          </div>
        ) : null}
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : isError ? (
          <p className="py-6 text-center text-sm text-destructive">{tx('Could not load the grades')}</p>
        ) : data?.length ? (
          <ul className="divide-y text-sm">
            {data.map((g) => (
              <li key={g.id} className="flex items-center justify-between gap-3 py-2">
                <span><span className="font-mono text-xs">{g.code}</span> {g.name}</span>
                {canEdit ? (
                  <Button size="sm" variant="ghost" aria-label={tx('Delete')} onClick={() => run('delete', `/product-grades/${g.id}`)}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        ) : (
          <p className="py-6 text-center text-sm text-muted-foreground">{tx('No grades yet.')}</p>
        )}
      </CardContent>
    </Card>
  );
}

function AttributesTab({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading, isError } = useProductAttributes();
  const { data: categories } = useProductCategories();
  const action = useSpecAction();
  const empty = { code: '', name: '', dataType: 'text', unit: '', options: '', categoryId: '', isRequired: false };
  const [form, setForm] = useState(empty);
  const set = (k: string, v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const needsOptions = form.dataType === 'option' && !form.options.trim();

  const add = async () => {
    try {
      await action.mutateAsync({
        method: 'post',
        path: '/product-attributes',
        body: {
          code: form.code,
          name: form.name,
          dataType: form.dataType,
          unit: form.unit || undefined,
          options: form.options.split(',').map((s) => s.trim()).filter(Boolean),
          categoryId: form.categoryId || undefined,
          isRequired: form.isRequired,
        },
      });
      setForm(empty);
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not add the attribute')));
    }
  };

  const remove = async (id: string) => {
    try {
      await action.mutateAsync({ method: 'delete', path: `/product-attributes/${id}` });
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('The action failed')));
    }
  };

  return (
    <Card>
      <CardContent className="space-y-4 pt-6">
        {canEdit ? (
          <div className="grid gap-3 md:grid-cols-4">
            <div className="grid gap-1.5">
              <Label>{tx('Code')}</Label>
              <Input value={form.code} onChange={(e) => set('code', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Name')}</Label>
              <Input value={form.name} onChange={(e) => set('name', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Type')}</Label>
              <select className={SELECT} value={form.dataType} onChange={(e) => set('dataType', e.target.value)}>
                <option value="text">{tx('Text')}</option>
                <option value="number">{tx('Number')}</option>
                <option value="boolean">{tx('Yes / No')}</option>
                <option value="option">{tx('Choice')}</option>
              </select>
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Unit')}</Label>
              <Input value={form.unit} onChange={(e) => set('unit', e.target.value)} />
            </div>
            <div className="grid gap-1.5 md:col-span-2">
              <Label>{tx('Choices, separated by commas')}</Label>
              <Input value={form.options} disabled={form.dataType !== 'option'} onChange={(e) => set('options', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Category')}</Label>
              <select className={SELECT} value={form.categoryId} onChange={(e) => set('categoryId', e.target.value)}>
                <option value="">{tx('All categories')}</option>
                {(categories ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
            <div className="flex items-end gap-3">
              <label className="flex items-center gap-1.5 text-sm">
                <input type="checkbox" checked={form.isRequired} onChange={(e) => set('isRequired', e.target.checked)} />
                {tx('Required')}
              </label>
              <Button size="sm" disabled={action.isPending || !form.code || !form.name || needsOptions} onClick={add}>
                {tx('Add')}
              </Button>
            </div>
          </div>
        ) : null}
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : isError ? (
          <p className="py-6 text-center text-sm text-destructive">{tx('Could not load the attributes')}</p>
        ) : data?.length ? (
          <ul className="divide-y text-sm">
            {data.map((a) => (
              <li key={a.id} className="flex items-center justify-between gap-3 py-2">
                <span>
                  <span className="font-mono text-xs">{a.code}</span> {a.name}
                  <span className="text-muted-foreground"> · {a.dataType}{a.unit ? ` · ${a.unit}` : ''}{a.options.length ? ` · ${a.options.join(', ')}` : ''}</span>
                </span>
                {canEdit ? (
                  <Button size="sm" variant="ghost" aria-label={tx('Delete')} onClick={() => remove(a.id)}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        ) : (
          <p className="py-6 text-center text-sm text-muted-foreground">{tx('No attributes yet.')}</p>
        )}
      </CardContent>
    </Card>
  );
}
