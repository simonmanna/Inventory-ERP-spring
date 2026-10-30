import { useState } from 'react';
import { Gem, Pencil, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { useMaterials, useSaveRow, type Material } from '@/features/quarry/api';
import { FormDialog, type FieldSpec } from './quarry-ui';

/** Material types and grades (geology) and the inventory product each is stocked as (AVCO / FIFO, tonnes). */
export function MaterialsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const [showInactive, setShowInactive] = useState(false);
  const { data, isLoading, isError } = useMaterials(showInactive);
  const save = useSaveRow('/quarry/materials');
  const [editing, setEditing] = useState<Material | 'new' | null>(null);
  const fields: FieldSpec[] = [
    { name: 'code', label: tx('Code'), hint: tx('Automatic when blank') },
    { name: 'name', label: tx('Name'), required: true },
    { name: 'rockType', label: tx('Rock type'), hint: tx('Granite, limestone, basalt, gravel…') },
    { name: 'grade', label: tx('Grade') },
    { name: 'densityTPerM3', label: tx('Density (t/m³)'), type: 'number', min: 0 },
    { name: 'productId', label: tx('Stock product'), type: 'product', required: true,
      pickedLabel: editing && editing !== 'new' ? editing.productName : null },
    { name: 'isActive', label: tx('Active'), type: 'checkbox', hint: tx('Active') },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
  const columns: Column<Material>[] = [
    { key: 'code', header: tx('Code'), render: (m) => <span className="font-medium">{m.code}</span> },
    { key: 'name', header: tx('Name'), render: (m) => m.name },
    { key: 'rock', header: tx('Rock type / grade'), render: (m) => [m.rockType, m.grade].filter(Boolean).join(' · ') || '—' },
    { key: 'density', header: tx('Density (t/m³)'), className: 'text-right', render: (m) => m.densityTPerM3 ?? '—' },
    { key: 'product', header: tx('Stock product'), render: (m) => `${m.productName ?? ''} (${m.costingMethod ?? ''})` },
    { key: 'active', header: tx('Status'), render: (m) => (m.isActive ? <StatusPill tone="success">{tx('Active')}</StatusPill> : <StatusPill>{tx('Inactive')}</StatusPill>) },
    { key: 'edit', header: '', className: 'w-12', render: (m) => has(PERMISSIONS.quarrySite.update) && (
      <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(m)}><Pencil className="h-4 w-4" /></Button>
    ) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Gem} title={tx('Materials & Grades')} description={tx('Raw stone types and grades, and the inventory product each is stocked as.')}
        actions={
          <>
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} /> {tx('Show inactive')}
            </label>
            {has(PERMISSIONS.quarrySite.create) && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add material')}</Button>}
          </>
        } />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(m) => m.id} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the materials. Try again.') : tx('No materials yet.')} />
      </ListCard>
      {editing && (
        <FormDialog title={editing === 'new' ? tx('Add material') : tx('Edit {{code}}', { code: editing.code })} fields={fields}
          row={editing === 'new' ? null : editing} defaults={{ isActive: true }} saving={save.isPending}
          onSave={(body) => save.mutateAsync({ id: editing === 'new' ? undefined : editing.id, body })} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}
