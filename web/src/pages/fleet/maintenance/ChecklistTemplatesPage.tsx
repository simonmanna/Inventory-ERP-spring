import { useState } from 'react';
import { ListChecks, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import {
  INSPECTION_KIND_LABEL, SEVERITY_LABEL, useDeleteInspectionTemplate, useInspectionTemplates, useSaveInspectionTemplate,
  type ChecklistItem, type InspectionKind, type InspectionTemplate, type Severity,
} from '@/features/fleet/maintenance-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { toOptions } from '../fleet-format';

/** Inspection / pre-trip (DVIR) checklists per vehicle class, with the severity of a failed item (fleet 1.6). */
export function ChecklistTemplatesPage() {
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.schedule));
  const { data, isLoading, isError } = useInspectionTemplates({ includeInactive: true });
  const [editing, setEditing] = useState<InspectionTemplate | 'new' | null>(null);
  const remove = useDeleteInspectionTemplate();

  const del = async (t: InspectionTemplate) => {
    if (!window.confirm(tx('Delete this checklist?'))) return;
    try {
      await remove.mutateAsync(t.id);
      notify.success(tx('Checklist deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the checklist')));
    }
  };

  const columns: Column<InspectionTemplate>[] = [
    { key: 'code', header: tx('Code'), render: (t) => <span className="font-medium">{t.code}</span> },
    { key: 'name', header: tx('Name'), render: (t) => t.name },
    { key: 'kind', header: tx('Kind'), render: (t) => INSPECTION_KIND_LABEL[t.kind] },
    { key: 'class', header: tx('Class'), render: (t) => t.category?.name ?? tx('Any class') },
    { key: 'items', header: tx('Items'), className: 'text-right', render: (t) => t.items.length },
    { key: 'critical', header: tx('Critical items'), className: 'text-right', render: (t) => t.items.filter((i) => i.severity === 'critical').length },
    { key: 'active', header: tx('Status'), render: (t) => <StatusPill tone={t.isActive ? 'success' : 'neutral'}>{t.isActive ? tx('Active') : tx('Inactive')}</StatusPill> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (t) => canEdit && (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="ghost" onClick={() => setEditing(t)}>{tx('Edit')}</Button>
          <Button size="sm" variant="ghost" onClick={() => del(t)}>{tx('Delete')}</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={ListChecks} title={tx('Inspection Checklists')}
        description={tx('Daily pre-trip and periodic checks. A failed critical item blocks the vehicle from dispatch.')}
        actions={canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New checklist')}</Button>} />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(t) => t.id}
          emptyMessage={isError ? tx('Could not load the checklists.') : tx('No checklists yet.')} />
      </ListCard>
      {editing && <ChecklistDialog template={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ChecklistDialog({ template, onClose }: { template: InspectionTemplate | null; onClose: () => void }) {
  const lookups = useFleetLookups();
  const save = useSaveInspectionTemplate();
  const [code, setCode] = useState(template?.code ?? '');
  const [name, setName] = useState(template?.name ?? '');
  const [kind, setKind] = useState<InspectionKind>(template?.kind ?? 'pre_trip');
  const [categoryId, setCategoryId] = useState(template?.categoryId ?? '');
  const [isActive, setIsActive] = useState(template?.isActive ?? true);
  const [items, setItems] = useState<ChecklistItem[]>(template?.items ?? [{ code: '', label: '', section: null, severity: 'minor' }]);
  const setItem = (i: number, patch: Partial<ChecklistItem>) => setItems((p) => p.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  const valid = code.trim() && name.trim() && items.length > 0 && items.every((i) => i.code.trim() && i.label.trim());

  const submit = async () => {
    const body: Record<string, unknown> = {
      code: code.trim(), name: name.trim(), kind, isActive,
      items: items.map((i) => ({ code: i.code.trim(), label: i.label.trim(), section: i.section?.trim() || undefined, severity: i.severity })),
    };
    if (categoryId) body.categoryId = categoryId;
    else if (template?.categoryId) body.clear = ['categoryId'];
    try {
      await save.mutateAsync({ id: template?.id, body });
      notify.success(tx('Checklist saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the checklist')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader><DialogTitle>{template ? tx('Edit checklist') : tx('New checklist')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value)} maxLength={40} /></Field>
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} maxLength={120} /></Field>
          <Field label={tx('Kind')}><OptionalSelect value={kind} onChange={(v) => setKind((v || 'pre_trip') as InspectionKind)} options={toOptions(INSPECTION_KIND_LABEL)} placeholder={tx('Kind')} /></Field>
          <Field label={tx('Class')}>
            <OptionalSelect value={categoryId} onChange={setCategoryId} noneLabel={tx('Any class')} placeholder={tx('Any class')}
              options={(lookups.data?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))} />
          </Field>
        </div>
        <div className="space-y-2">
          <p className="text-sm font-medium">{tx('Items')}</p>
          {items.map((it, i) => (
            <div key={i} className="grid grid-cols-12 gap-2">
              <Input className="col-span-3" placeholder={tx('Code')} value={it.code} onChange={(e) => setItem(i, { code: e.target.value })} />
              <Input className="col-span-5" placeholder={tx('Check')} value={it.label} onChange={(e) => setItem(i, { label: e.target.value })} />
              <div className="col-span-3">
                <OptionalSelect value={it.severity} onChange={(v) => setItem(i, { severity: (v || 'minor') as Severity })} options={toOptions(SEVERITY_LABEL)} placeholder={tx('Severity')} />
              </div>
              <Button type="button" size="icon" variant="ghost" className="col-span-1" aria-label={tx('Delete')} onClick={() => setItems((p) => p.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          <Button type="button" variant="outline" size="sm" onClick={() => setItems((p) => [...p, { code: '', label: '', section: null, severity: 'minor' }])}>
            <Plus className="mr-1 h-4 w-4" /> {tx('Add item')}
          </Button>
        </div>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} /> {tx('Active')}</label>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
