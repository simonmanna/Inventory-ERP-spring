import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  type FieldType,
  useCreateCustomObject,
  useCreateFieldDefinition,
  useCustomEntities,
  useCustomObjects,
  useFieldDefinitions,
  useUpdateCustomObject,
  useUpdateFieldDefinition,
} from '@/features/customization/api';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const TYPES: FieldType[] = ['text', 'textarea', 'number', 'decimal', 'date', 'boolean', 'select', 'multiselect', 'reference'];

/** Custom fields on core records and custom objects (ADR-S029 §1–2). */
export default function CustomFieldsPage() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.customization.manage);
  const { data: entities } = useCustomEntities();
  const [entity, setEntity] = useState('partner');

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Custom fields & objects')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Add your own fields to partners, products and documents, or define new record types.')}
        </p>
      </div>
      <div className="grid gap-4 lg:grid-cols-[260px_1fr]">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('Entities')}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1">
            {(entities ?? []).map((e) => (
              <button key={e.entity} type="button" onClick={() => setEntity(e.entity)}
                className={`flex w-full items-center justify-between rounded px-2 py-1.5 text-left text-sm hover:bg-muted ${entity === e.entity ? 'bg-muted font-medium' : ''}`}>
                <span>{e.label}{e.custom && <span className="ml-1 text-xs text-muted-foreground">({tx('custom')})</span>}</span>
                <Badge variant="secondary">{e.fieldCount}</Badge>
              </button>
            ))}
          </CardContent>
        </Card>
        <div className="space-y-4">
          <FieldsCard entity={entity} canManage={canManage} />
          <ObjectsCard canManage={canManage} />
        </div>
      </div>
    </div>
  );
}

function FieldsCard({ entity, canManage }: { entity: string; canManage: boolean }) {
  const { data: fields, isLoading } = useFieldDefinitions(entity, true);
  const create = useCreateFieldDefinition();
  const update = useUpdateFieldDefinition();
  const [key, setKey] = useState('');
  const [label, setLabel] = useState('');
  const [type, setType] = useState<FieldType>('text');
  const [choices, setChoices] = useState('');
  const [refEntity, setRefEntity] = useState('partner');
  const [required, setRequired] = useState(false);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{tx('Fields')} — {entity}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Label')}</TableHead>
                <TableHead>{tx('Key')}</TableHead>
                <TableHead>{tx('Type')}</TableHead>
                <TableHead>{tx('Required')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(fields ?? []).map((f) => (
                <TableRow key={f.id}>
                  <TableCell>{f.label}</TableCell>
                  <TableCell className="font-mono text-xs">{f.key}</TableCell>
                  <TableCell>
                    {f.type}
                    {f.options.values && <span className="text-xs text-muted-foreground"> ({f.options.values.join(', ')})</span>}
                  </TableCell>
                  <TableCell>{f.required ? tx('Yes') : tx('No')}</TableCell>
                  <TableCell>
                    <Badge variant={f.isActive ? 'default' : 'secondary'}>{f.isActive ? tx('Active') : tx('Inactive')}</Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    {canManage && (
                      <Button size="sm" variant="ghost" onClick={() => update.mutate({ id: f.id, isActive: !f.isActive })}>
                        {f.isActive ? tx('Deactivate') : tx('Activate')}
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {canManage && (
          <form
            className="grid gap-3 md:grid-cols-3"
            onSubmit={(e) => {
              e.preventDefault();
              const options = type === 'select' || type === 'multiselect'
                ? { values: choices.split(',').map((c) => c.trim()).filter(Boolean) }
                : type === 'reference' ? { entity: refEntity } : undefined;
              create.mutate({ entity, key, label, type, options, required } as never, {
                onSuccess: () => { setKey(''); setLabel(''); setChoices(''); setRequired(false); },
              });
            }}
          >
            <div className="space-y-1">
              <Label>{tx('Label')}</Label>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label>{tx('Key')}</Label>
              <Input value={key} onChange={(e) => setKey(e.target.value)} required placeholder="deliveryWindow" />
            </div>
            <div className="space-y-1">
              <Label>{tx('Type')}</Label>
              <select className={`${SELECT} w-full`} value={type} onChange={(e) => setType(e.target.value as FieldType)}>
                {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            {(type === 'select' || type === 'multiselect') && (
              <div className="space-y-1 md:col-span-2">
                <Label>{tx('Choices (comma separated)')}</Label>
                <Input value={choices} onChange={(e) => setChoices(e.target.value)} required />
              </div>
            )}
            {type === 'reference' && (
              <div className="space-y-1">
                <Label>{tx('Refers to')}</Label>
                <Input value={refEntity} onChange={(e) => setRefEntity(e.target.value)} />
              </div>
            )}
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={required} onChange={(e) => setRequired(e.target.checked)} />
              {tx('Required')}
            </label>
            <div className="md:col-span-3">
              <Button type="submit" disabled={create.isPending}>{tx('Add field')}</Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function ObjectsCard({ canManage }: { canManage: boolean }) {
  const { data: objects } = useCustomObjects();
  const create = useCreateCustomObject();
  const update = useUpdateCustomObject();
  const [key, setKey] = useState('');
  const [label, setLabel] = useState('');

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{tx('Custom objects')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Object')}</TableHead>
              <TableHead>{tx('Key')}</TableHead>
              <TableHead className="text-right">{tx('Records')}</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {(objects ?? []).map((o) => (
              <TableRow key={o.id}>
                <TableCell>
                  <Link className="text-primary hover:underline" to={`/custom-objects/${o.key}`}>{o.pluralLabel}</Link>
                </TableCell>
                <TableCell className="font-mono text-xs">x_{o.key}</TableCell>
                <TableCell className="text-right">{o.recordCount ?? 0}</TableCell>
                <TableCell className="text-right">
                  {canManage && (
                    <Button size="sm" variant="ghost" onClick={() => update.mutate({ key: o.key, isActive: !o.isActive })}>
                      {o.isActive ? tx('Deactivate') : tx('Activate')}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {canManage && (
          <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => {
            e.preventDefault();
            create.mutate({ key, label }, { onSuccess: () => { setKey(''); setLabel(''); } });
          }}>
            <div className="space-y-1">
              <Label>{tx('Label')}</Label>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} required className="w-48" />
            </div>
            <div className="space-y-1">
              <Label>{tx('Key')}</Label>
              <Input value={key} onChange={(e) => setKey(e.target.value)} required placeholder="site_visit" className="w-48" />
            </div>
            <Button type="submit" disabled={create.isPending}>{tx('Create object')}</Button>
            <p className="w-full text-xs text-muted-foreground">
              {tx('Then pick the object on the left to add its fields.')}
            </p>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
