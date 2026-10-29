import { useState } from 'react';
import { Edit, Layers, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  KIND_LABEL, METER_TYPE_LABEL, useCreateCategory, useDeleteCategory, useFleetCategories, useUpdateCategory,
  type CategoryKind, type MeterType, type VehicleCategory,
} from '@/features/fleet/api';
import { Field, OptionalSelect } from './fleet-ui';
import { toOptions } from './fleet-format';

/** Fleet settings: configurable vehicle / equipment classes (fleet 1.1). */
export function FleetCategoriesPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleet.settings));
  const { data, isLoading, isError } = useFleetCategories(true);
  const [editing, setEditing] = useState<VehicleCategory | 'new' | null>(null);
  const remove = useDeleteCategory();
  const update = useUpdateCategory();

  const del = async (c: VehicleCategory) => {
    if (!window.confirm(tx('Delete class {{name}}?', { name: c.name }))) return;
    try {
      await remove.mutateAsync(c.id);
      notify.success(tx('Class deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the class')));
    }
  };

  const toggle = async (c: VehicleCategory) => {
    try {
      await update.mutateAsync({ id: c.id, body: { isActive: !c.isActive } });
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the class')));
    }
  };

  const columns: Column<VehicleCategory>[] = [
    { key: 'code', header: tx('Code'), render: (c) => <span className="font-mono text-xs">{c.code}</span> },
    { key: 'name', header: tx('Name'), render: (c) => <span className="font-medium">{c.name}</span> },
    { key: 'kind', header: tx('Kind'), render: (c) => KIND_LABEL[c.kind] },
    { key: 'meter', header: tx('Default meter'), render: (c) => METER_TYPE_LABEL[c.defaultMeterType] },
    { key: 'vehicleCount', header: tx('Vehicles'), className: 'text-right', render: (c) => <span className="tabular-nums">{c.vehicleCount ?? 0}</span> },
    { key: 'isActive', header: tx('Status'), render: (c) => <StatusPill tone={c.isActive ? 'success' : 'neutral'}>{c.isActive ? tx('Active') : tx('Inactive')}</StatusPill> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (c) => canManage && (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="ghost" onClick={() => toggle(c)}>{c.isActive ? tx('Deactivate') : tx('Activate')}</Button>
          <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(c)}><Edit className="h-4 w-4" /></Button>
          {(c.vehicleCount ?? 0) === 0 && (
            <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(c)}><Trash2 className="h-4 w-4" /></Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Layers}
        title={tx('Vehicle classes')}
        description={tx('Classes group the fleet for filters, reports and maintenance templates. Road vehicles and trailers need a registration plate.')}
        actions={canManage && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add class')}</Button>}
      />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(c) => c.id} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the classes.') : tx('No classes yet.')} />
      </ListCard>
      {editing && <CategoryDialog category={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function CategoryDialog({ category, onClose }: { category: VehicleCategory | null; onClose: () => void }) {
  const [code, setCode] = useState(category?.code ?? '');
  const [name, setName] = useState(category?.name ?? '');
  const [kind, setKind] = useState<CategoryKind>(category?.kind ?? 'road_vehicle');
  const [meter, setMeter] = useState<MeterType>(category?.defaultMeterType ?? 'odometer');
  const [description, setDescription] = useState(category?.description ?? '');
  const [sortOrder, setSortOrder] = useState(String(category?.sortOrder ?? 0));
  const create = useCreateCategory();
  const update = useUpdateCategory();
  const valid = code.trim().length > 0 && name.trim().length > 0 && Number.isInteger(Number(sortOrder)) && Number(sortOrder) >= 0;

  const submit = async () => {
    const body = { code: code.trim(), name: name.trim(), kind, defaultMeterType: meter, description: description.trim(), sortOrder: Number(sortOrder) };
    try {
      if (category) await update.mutateAsync({ id: category.id, body });
      else await create.mutateAsync(body);
      notify.success(category ? tx('Class updated') : tx('Class added'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the class')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{category ? tx('Edit class') : tx('Add class')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Code')}><Input value={code} onChange={(e) => setCode(e.target.value)} className="uppercase" /></Field>
          <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Kind')}>
            <OptionalSelect value={kind} onChange={(v) => setKind((v || 'road_vehicle') as CategoryKind)} placeholder={tx('Kind')} options={toOptions(KIND_LABEL)} />
          </Field>
          <Field label={tx('Default meter')}>
            <OptionalSelect value={meter} onChange={(v) => setMeter((v || 'odometer') as MeterType)} placeholder={tx('Default meter')} options={toOptions(METER_TYPE_LABEL)} />
          </Field>
          <Field label={tx('Description')} className="sm:col-span-2"><Input value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <Field label={tx('Sort order')}><Input inputMode="numeric" value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
