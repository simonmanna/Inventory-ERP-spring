import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { AlertTriangle, Building2, Crown, MapPin, Pencil, Plus, Search, Target, Trash2, UsersRound, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { apiErrorMessage } from '@/lib/api-error';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { crmApi, useCrmLookups, useCrmMutation, useTeamCandidates, type Color, type Team, type TeamEmployee, type TeamMember } from '@/features/crm/suite-api';
import { Field, KpiCard, colorOf } from './crm-ui';
import { ColorPicker } from './CrmSettingsPage';

const onErr = (m: string) => (e: unknown) => toast.error(apiErrorMessage(e, m));

const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('') || '?';

/** Round initials badge for a team member; amber ring when the person is no longer an active HR employee. */
function Avatar({ name, color, eligible = true, size = 'md' }: { name: string; color: string; eligible?: boolean; size?: 'sm' | 'md' }) {
  return (
    <span title={eligible ? name : `${name} — ${tx('not an active HR employee')}`}
      className={cn('inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white ring-2 ring-card',
        size === 'sm' ? 'h-7 w-7 text-[10px]' : 'h-9 w-9 text-xs', colorOf(color).dot, !eligible && 'opacity-60 ring-amber-500')}>
      {initials(name)}
    </span>
  );
}

/**
 * Sales teams (CRM → Sales Teams): who leads and who sells in each team, their HR department and branch, and the
 * monthly target. Members and leaders are HR employees with a login; people can also be put on a team from their
 * HR employee page.
 */
export function SalesTeamsPage() {
  const { data: lk, isLoading } = useCrmLookups();
  const [editing, setEditing] = useState<Partial<Team> | null>(null);
  const [q, setQ] = useState('');
  const [showInactive, setShowInactive] = useState(false);
  const del = useCrmMutation(crmApi.deleteTeam);
  const teams = useMemo(() => lk?.teams ?? [], [lk]);
  const shown = useMemo(() => teams.filter((t) => (showInactive || t.isActive) && (!q.trim()
    || [t.name, t.departmentName, t.branchName, t.leader?.name, ...(t.members ?? []).map((m) => m.name)]
      .some((v) => v?.toLowerCase().includes(q.trim().toLowerCase())))), [teams, q, showInactive]);
  if (isLoading || !lk) return <Skeleton className="h-96 w-full" />;
  const active = teams.filter((t) => t.isActive);
  const people = new Set(active.flatMap((t) => [...t.memberIds, ...(t.leaderId ? [t.leaderId] : [])]));
  const target = active.reduce((s, t) => s + Number(t.monthlyTarget ?? 0), 0);
  const review = teams.reduce((s, t) => s + (t.notEligibleCount ?? 0), 0);
  const userName = (id: string | null) => lk.users.find((u) => u.id === id)?.name ?? null;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={UsersRound} title={tx('Sales Teams')}
        description={tx('Who leads and who sells in each team, with their HR department, branch and monthly target.')}
        actions={<Button onClick={() => setEditing({ color: 'sky', isActive: true, memberIds: [], monthlyTarget: 0 })}><Plus className="mr-1 h-4 w-4" />{tx('New team')}</Button>} />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard label={tx('Active teams')} value={active.length} sub={teams.length > active.length ? `${teams.length - active.length} ${tx('inactive')}` : undefined} icon={UsersRound} />
        <KpiCard label={tx('People on teams')} value={people.size} sub={tx('Leaders and members')} icon={Crown} />
        <KpiCard label={tx('Monthly target')} value={money(target)} sub={tx('All active teams')} icon={Target} tone="success" />
        <KpiCard label={tx('To review')} value={review} sub={tx('Not active HR employees')} icon={AlertTriangle} tone={review ? 'danger' : undefined} />
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <div className="relative w-full max-w-xs">
          <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input className="pl-8" placeholder={tx('Search team, person, branch')} value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <label className="flex items-center gap-2 text-sm text-muted-foreground"><input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />{tx('Show inactive teams')}</label>
        <p className="ml-auto text-xs text-muted-foreground">{tx('Leaders and members are HR employees with a login.')} <Link to="/hr/employees" className="text-primary hover:underline">{tx('Open HR employees')}</Link></p>
      </div>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {shown.map((t) => {
          const leaderName = t.leader?.name ?? userName(t.leaderId);
          const members: TeamMember[] = t.members ?? t.memberIds.map((id) => ({ userId: id, name: userName(id) ?? id, employee: null, eligible: true }));
          return (
            <section key={t.id} className={cn('group relative overflow-hidden rounded-2xl border bg-card shadow-sm transition-shadow hover:shadow-md', !t.isActive && 'opacity-60')}>
              <div className={cn('h-1.5 w-full', colorOf(t.color).bar)} />
              <div className="space-y-4 p-4">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <h2 className="truncate text-base font-semibold">{t.name}</h2>
                    <div className="mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                      {t.departmentName && <span className="inline-flex items-center gap-1"><Building2 className="h-3 w-3" />{t.departmentName}</span>}
                      {t.branchName && <span className="inline-flex items-center gap-1"><MapPin className="h-3 w-3" />{t.branchName}</span>}
                      {!t.isActive && <span className="rounded bg-muted px-1.5">{tx('Inactive')}</span>}
                    </div>
                  </div>
                  <div className="flex shrink-0">
                    <Button size="sm" variant="ghost" aria-label={tx('Edit team')} onClick={() => setEditing(t)}><Pencil className="h-4 w-4" /></Button>
                    <Button size="sm" variant="ghost" aria-label={tx('Delete team')} className="text-destructive" onClick={() => confirm(tx('Delete this team?')) && del.mutate(t.id, { onSuccess: () => toast.success(tx('Deleted')), onError: onErr(tx('Could not delete')) })}><Trash2 className="h-4 w-4" /></Button>
                  </div>
                </div>

                <div className="flex items-center gap-3 rounded-xl bg-muted/40 p-3">
                  {leaderName ? <Avatar name={leaderName} color={t.color} eligible={t.leader?.eligible ?? true} /> : <span className="flex h-9 w-9 items-center justify-center rounded-full border border-dashed text-muted-foreground"><Crown className="h-4 w-4" /></span>}
                  <div className="min-w-0 flex-1">
                    <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{tx('Team leader')}</p>
                    <p className="truncate text-sm font-medium">{leaderName ?? tx('No leader yet')}</p>
                  </div>
                  <div className="text-right">
                    <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{tx('Target / month')}</p>
                    <p className="text-sm font-semibold tabular-nums">{money(t.monthlyTarget)}</p>
                  </div>
                </div>

                <div>
                  <p className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{members.length} {members.length === 1 ? tx('member') : tx('members')}</p>
                  {members.length ? (
                    <ul className="space-y-1.5">
                      {members.slice(0, 6).map((m) => (
                        <li key={m.userId} className="flex items-center gap-2 text-sm">
                          <Avatar name={m.name} color={t.color} eligible={m.eligible} size="sm" />
                          <span className="min-w-0 flex-1 truncate">{m.name}</span>
                          <span className="truncate text-xs text-muted-foreground">{m.employee?.positionName ?? m.employee?.employeeCode ?? ''}</span>
                          {!m.eligible && <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-600" aria-label={tx('not an active HR employee')} />}
                        </li>
                      ))}
                      {members.length > 6 && <li className="text-xs text-muted-foreground">+{members.length - 6} {tx('more')}</li>}
                    </ul>
                  ) : <p className="text-sm text-muted-foreground">{tx('No members yet.')}</p>}
                </div>

                {!!t.notEligibleCount && (
                  <p className="flex items-center gap-1 rounded-md bg-amber-500/10 px-2 py-1 text-xs text-amber-700 dark:text-amber-400">
                    <AlertTriangle className="h-3.5 w-3.5" />{t.notEligibleCount} {tx('not active HR employees — review the team')}
                  </p>
                )}
              </div>
            </section>
          );
        })}
        {shown.length === 0 && (
          <div className="col-span-full rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
            <UsersRound className="mx-auto mb-2 h-8 w-8 opacity-40" />
            {teams.length ? tx('No team matches.') : tx('No sales teams yet.')}
          </div>
        )}
      </div>
      {editing && <TeamDialog team={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

export function TeamDialog({ team, onClose }: { team: Partial<Team>; onClose: () => void }) {
  const [t, setT] = useState(team);
  const save = useCrmMutation(crmApi.saveTeam);
  const cand = useTeamCandidates();
  const employees = cand.data?.employees ?? [];
  const label = (e: TeamEmployee) => `${e.name} (${e.employeeCode})${e.positionName ? ' · ' + e.positionName : ''}`;
  // People already on the team who are not (or no longer) active HR employees: kept until removed, never re-addable.
  const legacyMembers = (team.members ?? []).filter((m) => !m.eligible && (t.memberIds ?? []).includes(m.userId));
  const shown = employees.filter((e) => !t.departmentId || e.departmentId === t.departmentId || (t.memberIds ?? []).includes(e.userId));
  const leaderOptions = [
    ...employees.map((e) => ({ value: e.userId, label: label(e) })),
    ...(team.leader && !team.leader.eligible ? [{ value: team.leader.userId, label: `${team.leader.name} — ${tx('not an active HR employee')}` }] : []),
  ];
  const toggle = (userId: string) => {
    const on = (t.memberIds ?? []).includes(userId);
    setT({ ...t, memberIds: on ? t.memberIds!.filter((x) => x !== userId) : [...(t.memberIds ?? []), userId] });
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{t.id ? tx('Edit team') : tx('New team')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Name')}><Input value={t.name ?? ''} onChange={(e) => setT({ ...t, name: e.target.value })} /></Field>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={tx('HR department')}><OptionalSelect value={t.departmentId ?? ''} onChange={(v) => setT({ ...t, departmentId: v || null })} options={(cand.data?.departments ?? []).map((d) => ({ value: d.id, label: d.name }))} placeholder={tx('None')} noneLabel={tx('None')} /></Field>
            <Field label={tx('Branch')}><OptionalSelect value={t.branchId ?? ''} onChange={(v) => setT({ ...t, branchId: v || null })} options={(cand.data?.branches ?? []).map((b) => ({ value: b.id, label: b.name }))} placeholder={tx('None')} noneLabel={tx('None')} /></Field>
          </div>
          <Field label={tx('Leader')}><OptionalSelect value={t.leaderId ?? ''} onChange={(v) => setT({ ...t, leaderId: v || null })} options={leaderOptions} placeholder={tx('None')} noneLabel={tx('None')} /></Field>
          <Field label={tx('Members')}>
            {cand.isLoading ? <Skeleton className="h-16 w-full" /> : (
              <div className="flex max-h-40 flex-wrap gap-1.5 overflow-y-auto">
                {legacyMembers.map((m) => (
                  <button key={m.userId} type="button" onClick={() => toggle(m.userId)} title={tx('Not an active HR employee. Once removed, they cannot be added back until HR links them.')}
                    className="flex items-center gap-1 rounded-full border border-amber-500 bg-amber-500/10 px-2.5 py-0.5 text-xs text-amber-700 dark:text-amber-400">
                    <AlertTriangle className="h-3 w-3" />{m.name}<X className="h-3 w-3" />
                  </button>
                ))}
                {shown.map((e) => {
                  const on = (t.memberIds ?? []).includes(e.userId);
                  return <button key={e.employeeId} type="button" onClick={() => toggle(e.userId)} title={[e.departmentName, e.branchName].filter(Boolean).join(' · ')}
                    className={cn('rounded-full border px-2.5 py-0.5 text-xs', on ? 'border-primary bg-primary/10 text-primary' : 'hover:bg-muted')}>{label(e)}</button>;
                })}
                {shown.length === 0 && legacyMembers.length === 0 && (
                  <p className="text-xs text-muted-foreground">{tx('No active HR employees with a login yet.')}</p>
                )}
              </div>
            )}
            {t.departmentId && <p className="mt-1 text-xs text-muted-foreground">{tx('Showing employees of the selected department.')}</p>}
          </Field>
          <Field label={tx('Monthly target')}><Input type="number" min={0} value={t.monthlyTarget ?? 0} onChange={(e) => setT({ ...t, monthlyTarget: Number(e.target.value) })} /></Field>
          <Field label={tx('Colour')}><ColorPicker value={(t.color ?? 'sky') as Color} onChange={(c) => setT({ ...t, color: c })} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={t.isActive ?? true} onChange={(e) => setT({ ...t, isActive: e.target.checked })} />{tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!t.name || save.isPending} onClick={() => save.mutate({ id: t.id, name: t.name, leaderId: t.leaderId ?? null, memberIds: t.memberIds ?? [], monthlyTarget: t.monthlyTarget ?? 0, color: t.color, isActive: t.isActive ?? true, departmentId: t.departmentId ?? null, branchId: t.branchId ?? null },
            { onSuccess: () => { toast.success(tx('Saved')); onClose(); }, onError: onErr(tx('Could not save')) })}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
