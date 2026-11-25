import { useState } from 'react';
import { Plus, Users, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import { useStaffOptions } from '@/features/inventory/staff-options';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useQcTeams, useSaveTeam, type QcTeam } from '@/features/quality-control/api';
import { ActivePill, Field, StaffPicker, Toggle } from './qc-ui';

/** Quality teams (ADR-S087): control points, checks and alerts are assigned to a team. */
export function QcTeamsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading, isError } = useQcTeams();
  const [editing, setEditing] = useState<QcTeam | 'new' | null>(null);
  const canEdit = has(PERMISSIONS.qualityControl.configure);
  const columns: Column<QcTeam>[] = [
    { key: 'name', header: tx('Team'), render: (t) => <span className="font-medium">{t.name}</span> },
    { key: 'desc', header: tx('Description'), render: (t) => <span className="line-clamp-1 max-w-[20rem] text-muted-foreground">{t.description ?? '—'}</span> },
    { key: 'members', header: tx('Members'), render: (t) => (t.members ?? []).map((m) => m.name).join(', ') || '—' },
    { key: 'status', header: tx('Status'), render: (t) => <ActivePill active={t.isActive} /> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Users} title={tx('Quality Teams')} description={tx('Who handles the checks and alerts of a control point.')}
        actions={canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New team')}</Button>} />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(t) => t.id}
          onRowClick={canEdit ? (t) => setEditing(t) : undefined} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          emptyMessage={isError ? tx('Could not load the teams.') : tx('No quality teams yet.')} />
      </ListCard>
      {editing && <TeamDialog team={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function TeamDialog({ team, onClose }: { team?: QcTeam; onClose: () => void }) {
  const [name, setName] = useState(team?.name ?? '');
  const [description, setDescription] = useState(team?.description ?? '');
  const [isActive, setIsActive] = useState(team?.isActive ?? true);
  const [members, setMembers] = useState<string[]>((team?.members ?? []).map((m) => m.userId));
  const [adding, setAdding] = useState('');
  const staff = useStaffOptions();
  const save = useSaveTeam();
  const label = (id: string) => staff.data?.find((s) => s.value === id)?.label ?? team?.members?.find((m) => m.userId === id)?.name ?? id.slice(0, 8);
  const submit = async () => {
    try {
      await save.mutateAsync({ id: team?.id, body: { name: name.trim(), description: description.trim() || undefined, memberUserIds: members, ...(team ? { isActive } : {}) } });
      notify.success(tx('Team saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the team')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader><DialogTitle>{team ? tx('Edit team') : tx('New team')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Description')}><Input value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <Field label={tx('Members')}>
            <div className="space-y-2">
              <div className="flex flex-wrap gap-1.5">
                {members.map((id) => (
                  <span key={id} className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs">
                    {label(id)}<button type="button" aria-label={tx('Remove')} onClick={() => setMembers(members.filter((m) => m !== id))}><X className="h-3 w-3" /></button>
                  </span>
                ))}
              </div>
              <StaffPicker value={adding} placeholder={tx('Add a member')} onChange={(v) => { if (v && !members.includes(v)) setMembers([...members, v]); setAdding(''); }} />
            </div>
          </Field>
          {team && <Toggle checked={isActive} onChange={setIsActive} label={tx('Active')} />}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!name.trim() || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
