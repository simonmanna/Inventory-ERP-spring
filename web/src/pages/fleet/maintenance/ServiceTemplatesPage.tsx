import { useState } from 'react';
import { CalendarCog, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import {
  useApplyServiceTemplate, useDeleteServiceTemplate, useSaveServiceTemplate, useServiceTemplates, type ServiceTemplate,
} from '@/features/fleet/maintenance-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num } from '../fleet-format';
import { toNumber } from './maint-ui';

export const intervalText = (t: { intervalKm: string | null; intervalHours: string | null; intervalDays: number | null }) =>
  [t.intervalKm ? `${num(t.intervalKm, 0)} km` : null, t.intervalHours ? `${num(t.intervalHours, 0)} h` : null,
    t.intervalDays ? `${t.intervalDays} ${tx('days')}` : null].filter(Boolean).join(` ${tx('or')} `) || '—';

/** Service templates per vehicle class: intervals (whichever first), due-soon window, task list (fleet 1.6). */
export function ServiceTemplatesPage() {
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.schedule));
  const { data, isLoading, isError } = useServiceTemplates(true);
  const [editing, setEditing] = useState<ServiceTemplate | 'new' | null>(null);
  const [applying, setApplying] = useState<ServiceTemplate | null>(null);
  const remove = useDeleteServiceTemplate();

  const del = async (t: ServiceTemplate) => {
    if (!window.confirm(tx('Delete this template?'))) return;
    try {
      await remove.mutateAsync(t.id);
      notify.success(tx('Template deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the template')));
    }
  };

  const columns: Column<ServiceTemplate>[] = [
    { key: 'code', header: tx('Code'), render: (t) => <span className="font-medium">{t.code}</span> },
    { key: 'name', header: tx('Name'), render: (t) => t.name },
    { key: 'class', header: tx('Class'), render: (t) => t.category?.name ?? tx('Any class') },
    { key: 'interval', header: tx('Every'), render: (t) => intervalText(t) },
    { key: 'tasks', header: tx('Tasks'), className: 'text-right', render: (t) => t.tasks.length },
    { key: 'used', header: tx('Vehicles'), className: 'text-right', render: (t) => t.scheduleCount },
    { key: 'active', header: tx('Status'), render: (t) => <StatusPill tone={t.isActive ? 'success' : 'neutral'}>{t.isActive ? tx('Active') : tx('Inactive')}</StatusPill> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (t) => canEdit && (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="outline" disabled={!t.isActive} onClick={() => setApplying(t)}>{tx('Apply to vehicles')}</Button>
          <Button size="sm" variant="ghost" onClick={() => setEditing(t)}>{tx('Edit')}</Button>
          <Button size="sm" variant="ghost" onClick={() => del(t)}>{tx('Delete')}</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={CalendarCog} title={tx('Service Templates')}
        description={tx('Service plans per vehicle class: every so many km, engine hours or days, whichever comes first.')}
        actions={canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New template')}</Button>} />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(t) => t.id}
          emptyMessage={isError ? tx('Could not load the templates.') : tx('No service templates yet.')} />
      </ListCard>
      {editing && <TemplateDialog template={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {applying && <ApplyDialog template={applying} onClose={() => setApplying(null)} />}
    </div>
  );
}

function TemplateDialog({ template, onClose }: { template: ServiceTemplate | null; onClose: () => void }) {
  const lookups = useFleetLookups();
  const save = useSaveServiceTemplate();
  const [f, setF] = useState({
    code: template?.code ?? '', name: template?.name ?? '', categoryId: template?.categoryId ?? '',
    intervalKm: template?.intervalKm ? String(Number(template.intervalKm)) : '',
    intervalHours: template?.intervalHours ? String(Number(template.intervalHours)) : '',
    intervalDays: template?.intervalDays ? String(template.intervalDays) : '',
    leadKm: template ? String(Number(template.leadKm)) : '', leadHours: template ? String(Number(template.leadHours)) : '',
    leadDays: template ? String(template.leadDays) : '', tasks: (template?.tasks ?? []).join('\n'),
    isActive: template?.isActive ?? true,
  });
  const set = (k: keyof typeof f, v: string | boolean) => setF((p) => ({ ...p, [k]: v }));
  const hasInterval = !!(f.intervalKm || f.intervalHours || f.intervalDays);

  const submit = async () => {
    const body: Record<string, unknown> = {
      code: f.code.trim(), name: f.name.trim(), intervalKm: toNumber(f.intervalKm), intervalHours: toNumber(f.intervalHours),
      intervalDays: toNumber(f.intervalDays), leadKm: toNumber(f.leadKm), leadHours: toNumber(f.leadHours), leadDays: toNumber(f.leadDays),
      tasks: f.tasks.split('\n').map((t) => t.trim()).filter(Boolean), isActive: f.isActive,
    };
    if (f.categoryId) body.categoryId = f.categoryId;
    if (template) {
      const clear: string[] = (['intervalKm', 'intervalHours', 'intervalDays'] as const).filter((k) => !f[k] && template[k] !== null);
      if (!f.categoryId && template.categoryId) clear.push('categoryId');
      if (clear.length) body.clear = clear;
    }
    try {
      await save.mutateAsync({ id: template?.id, body });
      notify.success(tx('Template saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the template')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{template ? tx('Edit template') : tx('New template')}</DialogTitle>
          <DialogDescription>{tx('Due soon = within the lead window before the due point.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label={tx('Code *')}><Input value={f.code} onChange={(e) => set('code', e.target.value)} maxLength={40} /></Field>
          <Field label={tx('Name *')} className="sm:col-span-2"><Input value={f.name} onChange={(e) => set('name', e.target.value)} maxLength={120} /></Field>
          <Field label={tx('Class')} className="sm:col-span-3">
            <OptionalSelect value={f.categoryId} onChange={(v) => set('categoryId', v)} noneLabel={tx('Any class')}
              options={(lookups.data?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))} placeholder={tx('Any class')} />
          </Field>
          <Field label={tx('Every (km)')}><Input type="number" min="0" value={f.intervalKm} onChange={(e) => set('intervalKm', e.target.value)} /></Field>
          <Field label={tx('Every (engine hours)')}><Input type="number" min="0" value={f.intervalHours} onChange={(e) => set('intervalHours', e.target.value)} /></Field>
          <Field label={tx('Every (days)')}><Input type="number" min="0" value={f.intervalDays} onChange={(e) => set('intervalDays', e.target.value)} /></Field>
          <Field label={tx('Due soon (km before)')}><Input type="number" min="0" value={f.leadKm} onChange={(e) => set('leadKm', e.target.value)} /></Field>
          <Field label={tx('Due soon (hours before)')}><Input type="number" min="0" value={f.leadHours} onChange={(e) => set('leadHours', e.target.value)} /></Field>
          <Field label={tx('Due soon (days before)')}><Input type="number" min="0" value={f.leadDays} onChange={(e) => set('leadDays', e.target.value)} /></Field>
          <Field label={tx('Tasks (one per line)')} className="sm:col-span-3"><Textarea rows={5} value={f.tasks} onChange={(e) => set('tasks', e.target.value)} /></Field>
          <label className="flex items-center gap-2 text-sm sm:col-span-3">
            <input type="checkbox" checked={f.isActive} onChange={(e) => set('isActive', e.target.checked)} /> {tx('Active')}
          </label>
        </div>
        {!hasInterval && <p className="text-xs text-destructive">{tx('Set at least one interval.')}</p>}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!f.code.trim() || !f.name.trim() || !hasInterval || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ApplyDialog({ template, onClose }: { template: ServiceTemplate; onClose: () => void }) {
  const lookups = useFleetLookups();
  const apply = useApplyServiceTemplate();
  const [categoryId, setCategoryId] = useState(template.categoryId ?? '');
  const submit = async () => {
    try {
      const r = await apply.mutateAsync({ id: template.id, body: { categoryId } });
      notify.success(tx('{{created}} schedule(s) created, {{skipped}} already planned', { created: r.created, skipped: r.skipped }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not apply the template')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Apply {{name}}', { name: template.name })}</DialogTitle>
          <DialogDescription>{tx('Every live vehicle of the class gets this schedule, starting from today and its current meters.')}</DialogDescription>
        </DialogHeader>
        <Field label={tx('Class *')}>
          <OptionalSelect value={categoryId} onChange={setCategoryId} placeholder={tx('Choose a class')}
            options={(lookups.data?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))} />
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!categoryId || apply.isPending}>{tx('Apply')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
