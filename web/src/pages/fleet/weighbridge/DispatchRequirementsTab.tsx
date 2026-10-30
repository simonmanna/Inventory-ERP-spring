import { useState } from 'react';
import { Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  REQUIREMENT_MODE_LABEL, useDeleteDispatchRequirement, useDispatchRequirements, usePutDispatchRequirement,
  type DispatchRequirement, type DispatchRequirementOverview, type RequirementMode,
} from '@/features/fleet/weighbridge-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { toOptions } from '../fleet-format';

/**
 * "Weighbridge required for dispatch" (fleet 1.4b): one rule for the organization and optional rules per branch (a
 * branch rule wins). When a trip is covered, dispatch needs a completed weighbridge ticket and the loaded quantity
 * cannot be typed in by hand.
 */
export function DispatchRequirementsTab() {
  const data = useDispatchRequirements();
  const has = useAuthStore((s) => s.hasPermission);
  const canEdit = has(PERMISSIONS.fleet.settings);
  const remove = useDeleteDispatchRequirement();
  const [editing, setEditing] = useState<DispatchRequirement | 'new' | null>(null);

  const drop = async (r: DispatchRequirement) => {
    try {
      await remove.mutateAsync(r.id);
      notify.success(tx('Rule removed'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not remove the rule')));
    }
  };

  const columns: Column<DispatchRequirement>[] = [
    { key: 'scope', header: tx('Applies to'), render: (r) => <span className="font-medium">{r.branchName ?? tx('Whole organization')}</span> },
    { key: 'mode', header: tx('Weighbridge required for'), render: (r) => REQUIREMENT_MODE_LABEL[r.mode] ?? r.mode },
    { key: 'cats', header: tx('Categories'), render: (r) => r.categories.map((c) => c.name).join(', ') || '—' },
    { key: 'notes', header: tx('Notes'), render: (r) => r.notes ?? '—' },
    { key: 'updated', header: tx('Updated'), render: (r) => dateTime(r.updatedAt) },
    {
      key: 'actions', header: '', className: 'text-right', render: (r) => canEdit && (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="ghost" onClick={() => setEditing(r)}>{tx('Edit')}</Button>
          <Button size="sm" variant="ghost" onClick={() => drop(r)} disabled={remove.isPending}>{tx('Remove')}</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-3 pt-3">
      <p className="text-sm text-muted-foreground">
        {tx('When the weighbridge is required, a trip can only be dispatched after a completed weighbridge ticket gave its loaded quantity. A branch rule replaces the organization rule.')}
      </p>
      {canEdit && <div className="flex justify-end"><Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add rule')}</Button></div>}
      <ListCard>
        <DataTable columns={columns} data={data.data?.rules ?? []} loading={data.isLoading} getRowId={(r) => r.id}
          emptyMessage={data.isError ? tx('Could not load the rules.') : tx('No rule: the weighbridge is optional for dispatch.')} />
      </ListCard>
      {editing && data.data && (
        <RequirementDialog rule={editing === 'new' ? undefined : editing} overview={data.data} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}

function RequirementDialog({ rule, overview, onClose }: { rule?: DispatchRequirement; overview: DispatchRequirementOverview; onClose: () => void }) {
  const put = usePutDispatchRequirement();
  const [branchId, setBranchId] = useState(rule?.branchId ?? '');
  const [mode, setMode] = useState<RequirementMode>(rule?.mode ?? 'bulk');
  const [categoryIds, setCategoryIds] = useState<string[]>(rule?.categoryIds ?? []);
  const [notes, setNotes] = useState(rule?.notes ?? '');
  const valid = mode !== 'categories' || categoryIds.length > 0;
  const toggle = (id: string) => setCategoryIds((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));

  const submit = async () => {
    try {
      await put.mutateAsync({ branchId: branchId || undefined, mode, categoryIds: mode === 'categories' ? categoryIds : undefined, notes: notes.trim() || undefined });
      notify.success(tx('Rule saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the rule')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Weighbridge required for dispatch')}</DialogTitle>
          <DialogDescription>{tx('Bulk products are sold by weight or volume (t, kg, m³) or have weighbridge product settings.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3">
          <Field label={tx('Branch')}>
            {rule ? <div className="text-sm font-medium">{rule.branchName ?? tx('Whole organization')}</div> : (
              <OptionalSelect value={branchId} onChange={setBranchId} placeholder={tx('Whole organization')} noneLabel={tx('Whole organization')}
                options={overview.branches.map((b) => ({ value: b.id, label: `${b.code} — ${b.name}` }))} />
            )}
          </Field>
          <Field label={tx('Required for *')}>
            <OptionalSelect value={mode} onChange={(v) => setMode((v || 'bulk') as RequirementMode)} placeholder={tx('Required for')}
              options={toOptions(REQUIREMENT_MODE_LABEL)} />
          </Field>
          {mode === 'categories' && (
            <Field label={tx('Product categories * (sub-categories included)')}>
              <div className="max-h-48 space-y-1 overflow-y-auto rounded-md border p-2">
                {overview.categories.length === 0 && <p className="text-sm text-muted-foreground">{tx('No product categories yet.')}</p>}
                {overview.categories.map((c) => (
                  <label key={c.id} className="flex items-center gap-2 text-sm">
                    <input type="checkbox" className="h-4 w-4" checked={categoryIds.includes(c.id)} onChange={() => toggle(c.id)} />
                    {c.name}
                  </label>
                ))}
              </div>
            </Field>
          )}
          <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || put.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
