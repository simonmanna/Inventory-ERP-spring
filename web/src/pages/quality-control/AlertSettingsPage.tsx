import { useState } from 'react';
import { Plus, Settings2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  useAlertStages, useRootCauses, useSaveAlertStage, useSaveRootCause, useSaveTag, useTags, type QcAlertStage, type QcRootCause, type QcTag,
} from '@/features/quality-control/api';
import { ActivePill, Field, Toggle } from './qc-ui';
import { optNum } from './qc-format';

/** Alert settings (ADR-S087 §8): kanban stages, tags and root causes. */
export function AlertSettingsPage() {
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.qualityControl.configure));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Alert Settings')} description={tx('The stages of the alerts board, the tags and the root causes used in investigations.')} />
      <Stages canEdit={canEdit} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Tags canEdit={canEdit} />
        <RootCauses canEdit={canEdit} />
      </div>
    </div>
  );
}

function Section({ title, onNew, children }: { title: string; onNew?: () => void; children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-base">{title}</CardTitle>
        {onNew && <Button size="sm" variant="outline" onClick={onNew}><Plus className="mr-1 h-4 w-4" /> {tx('Add')}</Button>}
      </CardHeader>
      <CardContent className="p-0">{children}</CardContent>
    </Card>
  );
}

function Stages({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading } = useAlertStages();
  const [editing, setEditing] = useState<QcAlertStage | 'new' | null>(null);
  const columns: Column<QcAlertStage>[] = [
    { key: 'seq', header: tx('Order'), className: 'w-16 text-right', render: (s) => s.sequence },
    { key: 'name', header: tx('Stage'), render: (s) => <span className="font-medium">{s.name}</span> },
    { key: 'closed', header: tx('Resolves the alert'), render: (s) => (s.isClosed ? tx('Yes') : '—') },
    { key: 'folded', header: tx('Folded'), render: (s) => (s.folded ? tx('Yes') : '—') },
    { key: 'count', header: tx('Alerts'), className: 'text-right', render: (s) => s.alertCount ?? 0 },
    { key: 'active', header: tx('Status'), render: (s) => <ActivePill active={s.isActive} /> },
  ];
  return (
    <Section title={tx('Stages')} onNew={canEdit ? () => setEditing('new') : undefined}>
      <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(s) => s.id} onRowClick={canEdit ? setEditing : undefined}
        cellClassName="py-2 px-4" headerRowClassName="h-9" emptyMessage={tx('No stages.')} />
      {editing && <StageDialog stage={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Section>
  );
}

function StageDialog({ stage, onClose }: { stage?: QcAlertStage; onClose: () => void }) {
  const [name, setName] = useState(stage?.name ?? '');
  const [sequence, setSequence] = useState(stage ? String(stage.sequence) : '');
  const [isClosed, setIsClosed] = useState(stage?.isClosed ?? false);
  const [folded, setFolded] = useState(stage?.folded ?? false);
  const [isActive, setIsActive] = useState(stage?.isActive ?? true);
  const save = useSaveAlertStage();
  const submit = async () => {
    try {
      const body: Record<string, unknown> = { name: name.trim(), sequence: optNum(sequence), isClosed, folded };
      if (stage) body.isActive = isActive;
      await save.mutateAsync({ id: stage?.id, body });
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the stage')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{stage ? tx('Edit stage') : tx('New stage')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
          <Field label={tx('Order')}><Input type="number" value={sequence} onChange={(e) => setSequence(e.target.value)} /></Field>
        </div>
        <div className="space-y-2">
          <Toggle checked={isClosed} onChange={setIsClosed} label={tx('Resolves the alert')} hint={tx('Alerts moved here count as solved (closed date set).')} />
          <Toggle checked={folded} onChange={setFolded} label={tx('Folded on the board')} />
          {stage && <Toggle checked={isActive} onChange={setIsActive} label={tx('Active')} hint={tx('A stage with alerts in it cannot be deactivated.')} />}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={save.isPending || !name.trim()}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Tags({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading } = useTags();
  const [editing, setEditing] = useState<QcTag | 'new' | null>(null);
  const columns: Column<QcTag>[] = [
    { key: 'name', header: tx('Tag'), render: (t) => <span className="inline-flex items-center gap-2"><span className="h-3 w-3 rounded-full border" style={{ background: t.color ?? undefined }} />{t.name}</span> },
  ];
  return (
    <Section title={tx('Tags')} onNew={canEdit ? () => setEditing('new') : undefined}>
      <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(t) => t.id} onRowClick={canEdit ? setEditing : undefined}
        cellClassName="py-2 px-4" headerRowClassName="h-9" emptyMessage={tx('No tags.')} />
      {editing && <TagDialog tag={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Section>
  );
}

function TagDialog({ tag, onClose }: { tag?: QcTag; onClose: () => void }) {
  const [name, setName] = useState(tag?.name ?? '');
  const [color, setColor] = useState(tag?.color ?? '#2563eb');
  const save = useSaveTag();
  const submit = async () => {
    try {
      await save.mutateAsync({ id: tag?.id, body: { name: name.trim(), color } });
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the tag')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tag ? tx('Edit tag') : tx('New tag')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-[1fr_auto] gap-3">
          <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
          <Field label={tx('Colour')}><Input type="color" className="h-9 w-16 p-1" value={color} onChange={(e) => setColor(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={save.isPending || !name.trim()}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RootCauses({ canEdit }: { canEdit: boolean }) {
  const { data, isLoading } = useRootCauses();
  const [editing, setEditing] = useState<QcRootCause | 'new' | null>(null);
  const columns: Column<QcRootCause>[] = [
    { key: 'name', header: tx('Root cause'), render: (c) => <div><div className="font-medium">{c.name}</div><div className="line-clamp-1 text-xs text-muted-foreground">{c.description}</div></div> },
    { key: 'active', header: tx('Status'), render: (c) => <ActivePill active={c.isActive} /> },
  ];
  return (
    <Section title={tx('Root causes')} onNew={canEdit ? () => setEditing('new') : undefined}>
      <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(c) => c.id} onRowClick={canEdit ? setEditing : undefined}
        cellClassName="py-2 px-4" headerRowClassName="h-9" emptyMessage={tx('No root causes.')} />
      {editing && <RootCauseDialog cause={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Section>
  );
}

function RootCauseDialog({ cause, onClose }: { cause?: QcRootCause; onClose: () => void }) {
  const [name, setName] = useState(cause?.name ?? '');
  const [description, setDescription] = useState(cause?.description ?? '');
  const [isActive, setIsActive] = useState(cause?.isActive ?? true);
  const save = useSaveRootCause();
  const submit = async () => {
    try {
      const body: Record<string, unknown> = { name: name.trim(), description: description.trim() || undefined };
      if (cause) body.isActive = isActive;
      await save.mutateAsync({ id: cause?.id, body });
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the root cause')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{cause ? tx('Edit root cause') : tx('New root cause')}</DialogTitle></DialogHeader>
        <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
        <Field label={tx('Description')}><Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        {cause && <Toggle checked={isActive} onChange={setIsActive} label={tx('Active')} />}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={save.isPending || !name.trim()}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
