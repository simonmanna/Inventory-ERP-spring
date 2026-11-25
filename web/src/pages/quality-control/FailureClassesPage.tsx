import { useState } from 'react';
import { Plus, Tags } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useFailureClasses, useSaveFailureClass, type QcFailureClass } from '@/features/quality-control/api';
import { ActivePill, Field, Toggle } from './qc-ui';
import { optNum } from './qc-format';

/** Failure classes (ADR-S087 §9): why a check failed; used by alerts and the Pareto chart. */
export function FailureClassesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading, isError } = useFailureClasses();
  const [editing, setEditing] = useState<QcFailureClass | 'new' | null>(null);
  const canEdit = has(PERMISSIONS.qualityControl.configure);
  const columns: Column<QcFailureClass>[] = [
    { key: 'code', header: tx('Code'), render: (c) => <span className="font-mono text-xs">{c.code}</span> },
    { key: 'name', header: tx('Name'), render: (c) => <span className="font-medium">{c.name}</span> },
    { key: 'sort', header: tx('Order'), className: 'text-right', render: (c) => c.sortOrder },
    { key: 'status', header: tx('Status'), render: (c) => <ActivePill active={c.isActive} /> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Tags} title={tx('Failure Classes')} description={tx('Classify why checks fail: material, dimensions, strength, moisture…')}
        actions={canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New class')}</Button>} />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(c) => c.id}
          onRowClick={canEdit ? (c) => setEditing(c) : undefined} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          emptyMessage={isError ? tx('Could not load the failure classes.') : tx('No failure classes.')} />
      </ListCard>
      {editing && <ClassDialog fc={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ClassDialog({ fc, onClose }: { fc?: QcFailureClass; onClose: () => void }) {
  const [code, setCode] = useState(fc?.code ?? '');
  const [name, setName] = useState(fc?.name ?? '');
  const [sortOrder, setSortOrder] = useState(fc ? String(fc.sortOrder) : '');
  const [isActive, setIsActive] = useState(fc?.isActive ?? true);
  const save = useSaveFailureClass();
  const submit = async () => {
    try {
      await save.mutateAsync({
        id: fc?.id,
        body: fc ? { name: name.trim(), sortOrder: optNum(sortOrder), isActive } : { code: code.trim(), name: name.trim(), sortOrder: optNum(sortOrder) },
      });
      notify.success(tx('Failure class saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the failure class')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>{fc ? tx('Edit failure class') : tx('New failure class')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Code *')}><Input value={code} disabled={!!fc} onChange={(e) => setCode(e.target.value.toUpperCase())} /></Field>
          <Field label={tx('Order')}><Input inputMode="numeric" value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} /></Field>
          <Field label={tx('Name *')} className="col-span-2"><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          {fc && <div className="col-span-2"><Toggle checked={isActive} onChange={setIsActive} label={tx('Active')} /></div>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!name.trim() || (!fc && !code.trim()) || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
