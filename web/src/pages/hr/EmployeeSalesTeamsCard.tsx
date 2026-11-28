import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Plus, UsersRound, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import type { HrEmployee } from '@/features/hr/api';

interface TeamOption {
  id: string; name: string; color: string; isActive: boolean; leaderId: string | null; leaderName: string | null;
  memberCount: number; departmentName: string | null; branchName: string | null;
}

/**
 * The CRM sales teams an employee leads or belongs to, with "Add to sales team" and remove (crm:configure or
 * hr:employee). The employee needs a linked user account; the API also checks they are an active HR employee.
 */
export function EmployeeSalesTeamsCard({ e }: { e: HrEmployee }) {
  const can = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.configure) || s.hasPermission(PERMISSIONS.hr.employee));
  const canCrm = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.configure));
  const qc = useQueryClient();
  const [adding, setAdding] = useState(false);
  const teams = e.salesTeams ?? [];
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['hr', 'employees'] });
    qc.invalidateQueries({ queryKey: ['crm-suite'] });
  };
  const remove = useMutation({
    mutationFn: async (teamId: string) => (await api.delete(`/crm/config/teams/${teamId}/members/${e.userId}`)).data,
    onSuccess: () => { toast.success(tx('Removed from the team')); refresh(); },
    onError: (err) => toast.error(apiErrorMessage(err, tx('Could not remove'))),
  });
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{tx('Sales teams')}</CardTitle>
        {can && e.userId && (
          <Button size="sm" variant="outline" onClick={() => setAdding(true)}><Plus className="mr-1 h-4 w-4" />{tx('Add to sales team')}</Button>
        )}
      </CardHeader>
      <CardContent>
        {teams.length ? (
          <ul className="space-y-2">
            {teams.map((t) => (
              <li key={t.id} className="flex items-center justify-between gap-2 text-sm">
                <span className={t.isActive ? '' : 'text-muted-foreground line-through'}>
                  {t.name}
                  {(t.departmentName || t.branchName) && (
                    <span className="ml-1 text-xs text-muted-foreground">{[t.departmentName, t.branchName].filter(Boolean).join(' · ')}</span>
                  )}
                </span>
                <span className="flex items-center gap-1">
                  <Badge variant={t.role === 'leader' ? 'default' : 'secondary'}>{t.role === 'leader' ? tx('Leader') : tx('Member')}</Badge>
                  {can && (
                    <Button size="icon" variant="ghost" className="h-7 w-7 text-muted-foreground hover:text-destructive" aria-label={tx('Remove from team')}
                      disabled={remove.isPending} onClick={() => confirm(tx('Remove from this team?')) && remove.mutate(t.id)}>
                      <X className="h-4 w-4" />
                    </Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">
            {e.userId ? tx('Not on any sales team yet.') : tx('Link a user account (Access tab) before adding this employee to a sales team.')}
          </p>
        )}
        {canCrm && <Link to="/crm/sales-teams" className="mt-3 inline-flex items-center gap-1 text-xs text-primary hover:underline"><UsersRound className="h-3.5 w-3.5" />{tx('Manage sales teams')}</Link>}
      </CardContent>
      {adding && e.userId && <AddDialog userId={e.userId} current={teams.map((t) => t.id)} onClose={() => setAdding(false)} onDone={refresh} />}
    </Card>
  );
}

function AddDialog({ userId, current, onClose, onDone }: { userId: string; current: string[]; onClose: () => void; onDone: () => void }) {
  const [teamId, setTeamId] = useState('');
  const [role, setRole] = useState<'member' | 'leader'>('member');
  const { data: options, isLoading } = useQuery({
    queryKey: ['crm-suite', 'team-options'],
    queryFn: async () => (await api.get<TeamOption[]>('/crm/config/team-options')).data,
  });
  const add = useMutation({
    mutationFn: async () => (await api.post(`/crm/config/teams/${teamId}/members`, { userId, role })).data,
    onSuccess: () => { toast.success(tx('Added to the team')); onDone(); onClose(); },
    onError: (err) => toast.error(apiErrorMessage(err, tx('Could not add'))),
  });
  const choices = (options ?? []).filter((t) => t.isActive && !current.includes(t.id));
  const picked = choices.find((t) => t.id === teamId);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Add to sales team')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <label className="block space-y-1">
            <span className="text-xs font-medium text-muted-foreground">{tx('Team')}</span>
            <select className="h-9 w-full rounded-md border bg-background px-2" value={teamId} onChange={(ev) => setTeamId(ev.target.value)} disabled={isLoading}>
              <option value="">{isLoading ? tx('Loading…') : choices.length ? tx('Choose a team') : tx('No other active team')}</option>
              {choices.map((t) => <option key={t.id} value={t.id}>{t.name}{t.branchName ? ` · ${t.branchName}` : ''}</option>)}
            </select>
          </label>
          {picked && (
            <p className="text-xs text-muted-foreground">
              {tx('Leader')}: {picked.leaderName ?? '—'} · {picked.memberCount} {tx('members')}
              {picked.departmentName ? ` · ${picked.departmentName}` : ''}
            </p>
          )}
          <div className="space-y-1">
            <span className="text-xs font-medium text-muted-foreground">{tx('Role')}</span>
            <div className="flex gap-4">
              <label className="flex items-center gap-2"><input type="radio" checked={role === 'member'} onChange={() => setRole('member')} />{tx('Member')}</label>
              <label className="flex items-center gap-2"><input type="radio" checked={role === 'leader'} onChange={() => setRole('leader')} />{tx('Team leader')}</label>
            </div>
            {role === 'leader' && picked?.leaderName && <p className="text-xs text-amber-700 dark:text-amber-400">{tx('Replaces the current leader')} ({picked.leaderName}).</p>}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!teamId || add.isPending} onClick={() => add.mutate()}>{tx('Add')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
