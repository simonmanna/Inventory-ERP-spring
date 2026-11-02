import { useMemo, useState } from 'react';
import { CalendarPlus, Lock, LockOpen, Star, Trash2, UserPlus } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { useHrEmployees } from '@/features/hr/api';
import {
  useAssignPayGroupMembers, useDeletePayGroup, useGeneratePeriods, usePayCalendar, usePayGroupMembers, usePayGroups,
  usePeriodLock, useSavePayGroup, type PayGroup,
} from '@/features/hr/payroll-setup-api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

const selectClass = 'h-9 rounded-md border bg-background px-3 text-sm';
const EMPTY = { code: '', name: '', payFrequency: 'MONTHLY', anchorDate: '', paymentDay: '0', isDefault: false };
const day = (v: string | null) => (v ? v.slice(0, 10) : '—');

const frequencyLabel = (f: string) =>
  ({ MONTHLY: tx('Monthly'), BIWEEKLY: tx('Every two weeks'), WEEKLY: tx('Weekly') })[f] ?? f;

/** Pay groups, their members and their pay calendars with period locks (HP-0). */
export function HrPayGroupsPanel() {
  const { data, isLoading, isError } = usePayGroups();
  const saveGroup = useSavePayGroup();
  const deleteGroup = useDeletePayGroup();
  const [form, setForm] = useState(EMPTY);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const groups = useMemo(() => data?.rows ?? [], [data]);
  const selected = groups.find((g) => g.id === selectedId) ?? groups[0];
  const set = (k: keyof typeof EMPTY, v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const fail = (fallback: string) => (err: unknown) => notify.error(apiErrorMessage(err, fallback));

  const create = () =>
    saveGroup.mutate(
      {
        dto: {
          code: form.code,
          name: form.name,
          payFrequency: form.payFrequency,
          anchorDate: form.payFrequency === 'MONTHLY' ? undefined : form.anchorDate,
          paymentDay: Number(form.paymentDay),
          isDefault: form.isDefault,
        },
      },
      {
        onSuccess: (g) => {
          notify.success(tx('Pay group added'));
          setForm(EMPTY);
          setSelectedId(g.id);
        },
        onError: fail(tx('Could not add the pay group')),
      },
    );
  const valid = form.code && form.name && (form.payFrequency === 'MONTHLY' || form.anchorDate);

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        {tx('Employees without a pay group are paid with the default group. A period generated for a group pays only its members.')}
      </p>
      <Card>
        <CardContent className="grid gap-3 pt-6 md:grid-cols-4">
          <div className="grid gap-1.5">
            <Label htmlFor="pg-code">{tx('Code')}</Label>
            <Input id="pg-code" value={form.code} onChange={(e) => set('code', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="pg-name">{tx('Name')}</Label>
            <Input id="pg-name" value={form.name} onChange={(e) => set('name', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="pg-freq">{tx('Pay frequency')}</Label>
            <select id="pg-freq" className={selectClass} value={form.payFrequency} onChange={(e) => set('payFrequency', e.target.value)}>
              {['MONTHLY', 'BIWEEKLY', 'WEEKLY'].map((f) => <option key={f} value={f}>{frequencyLabel(f)}</option>)}
            </select>
          </div>
          {form.payFrequency === 'MONTHLY' ? (
            <div className="grid gap-1.5">
              <Label htmlFor="pg-day">{tx('Pay day of month (0 = last day)')}</Label>
              <Input id="pg-day" type="number" min={0} max={31} value={form.paymentDay} onChange={(e) => set('paymentDay', e.target.value)} />
            </div>
          ) : (
            <>
              <div className="grid gap-1.5">
                <Label htmlFor="pg-anchor">{tx('First period starts')}</Label>
                <Input id="pg-anchor" type="date" value={form.anchorDate} onChange={(e) => set('anchorDate', e.target.value)} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="pg-lag">{tx('Paid days after period end')}</Label>
                <Input id="pg-lag" type="number" min={0} max={31} value={form.paymentDay} onChange={(e) => set('paymentDay', e.target.value)} />
              </div>
            </>
          )}
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.isDefault} onChange={(e) => set('isDefault', e.target.checked)} />
            {tx('Default group')}
          </label>
          <div className="flex items-end">
            <Button disabled={saveGroup.isPending || !valid} onClick={create}>{tx('Add')}</Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="pt-6">
          {isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : isError ? (
            <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the pay groups')}</p>
          ) : groups.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                    <th className="py-2">{tx('Code')}</th>
                    <th className="py-2">{tx('Name')}</th>
                    <th className="py-2">{tx('Pay frequency')}</th>
                    <th className="py-2 text-right">{tx('Employees')}</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {groups.map((g) => (
                    <tr key={g.id} className={`cursor-pointer border-b last:border-0 ${selected?.id === g.id ? 'bg-muted/50' : ''}`}
                      onClick={() => setSelectedId(g.id)}>
                      <td className="py-2 font-mono text-xs">{g.code}</td>
                      <td className="py-2">
                        {g.name} {g.isDefault && <Badge variant="secondary" className="ml-1">{tx('Default')}</Badge>}
                        {!g.isActive && <Badge variant="outline" className="ml-1">{tx('Inactive')}</Badge>}
                      </td>
                      <td className="py-2">{frequencyLabel(g.payFrequency)}</td>
                      <td className="py-2 text-right tabular-nums">{g.employeeCount}</td>
                      <td className="py-2 text-right">
                        {!g.isDefault && (
                          <>
                            <Button size="sm" variant="ghost" aria-label={tx('Make default')} title={tx('Make default')}
                              onClick={(e) => { e.stopPropagation(); saveGroup.mutate({ id: g.id, dto: { isDefault: true } }, { onError: fail(tx('The action failed')) }); }}>
                              <Star className="h-4 w-4" />
                            </Button>
                            <Button size="sm" variant="ghost" aria-label={tx('Delete')}
                              onClick={(e) => { e.stopPropagation(); deleteGroup.mutate(g.id, { onError: fail(tx('Could not delete the pay group')) }); }}>
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="py-8 text-center text-sm text-muted-foreground">{tx('No pay groups yet. Payroll runs pay every active employee.')}</p>
          )}
        </CardContent>
      </Card>

      {selected && (
        <div className="grid gap-4 lg:grid-cols-2">
          <MembersCard group={selected} />
          <CalendarCard group={selected} />
        </div>
      )}
    </div>
  );
}

function MembersCard({ group }: { group: PayGroup }) {
  const [search, setSearch] = useState('');
  const [adding, setAdding] = useState(false);
  const { data, isLoading } = usePayGroupMembers(group.id, search || undefined);

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{tx('Members of {{name}}', { name: group.name })}</CardTitle>
        <Button size="sm" variant="outline" onClick={() => setAdding(true)}>
          <UserPlus className="mr-1 h-4 w-4" />{tx('Add employees')}
        </Button>
      </CardHeader>
      <CardContent className="space-y-3">
        <Input placeholder={tx('Search')} value={search} onChange={(e) => setSearch(e.target.value)} aria-label={tx('Search')} />
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : data?.rows.length ? (
          <ul className="max-h-72 divide-y overflow-y-auto text-sm">
            {data.rows.map((m) => (
              <li key={m.id} className="flex items-center justify-between py-1.5">
                <span>
                  <span className="mr-2 font-mono text-xs text-muted-foreground">{m.employeeCode}</span>
                  {m.firstName} {m.lastName}
                </span>
                {m.payGroupId === null && <Badge variant="outline">{tx('by default')}</Badge>}
              </li>
            ))}
          </ul>
        ) : (
          <p className="py-6 text-center text-sm text-muted-foreground">{tx('No employees in this group.')}</p>
        )}
      </CardContent>
      {adding && <AddMembersDialog group={group} onClose={() => setAdding(false)} />}
    </Card>
  );
}

function AddMembersDialog({ group, onClose }: { group: PayGroup; onClose: () => void }) {
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const { data } = useHrEmployees({ search: search || undefined, isActive: 'true', pageSize: 100 });
  const assign = useAssignPayGroupMembers();
  const rows: { id: string; employeeCode: string; firstName: string; lastName?: string }[] = data?.rows ?? [];
  const toggle = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]));

  const submit = () =>
    assign.mutate(
      { id: group.id, employeeIds: picked },
      {
        onSuccess: () => { notify.success(tx('Employees moved to {{name}}', { name: group.name })); onClose(); },
        onError: (err) => notify.error(apiErrorMessage(err, tx('Could not move the employees'))),
      },
    );

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Add employees to {{name}}', { name: group.name })}</DialogTitle>
        </DialogHeader>
        <p className="text-sm text-muted-foreground">{tx('An employee belongs to one pay group; adding moves them out of their current group.')}</p>
        <Input placeholder={tx('Search')} value={search} onChange={(e) => setSearch(e.target.value)} aria-label={tx('Search')} />
        <ul className="max-h-72 divide-y overflow-y-auto text-sm">
          {rows.map((e) => (
            <li key={e.id}>
              <label className="flex items-center gap-2 py-1.5">
                <input type="checkbox" checked={picked.includes(e.id)} onChange={() => toggle(e.id)} />
                <span className="font-mono text-xs text-muted-foreground">{e.employeeCode}</span>
                {e.firstName} {e.lastName}
              </label>
            </li>
          ))}
        </ul>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!picked.length || assign.isPending} onClick={submit}>
            {tx('Add {{count}}', { count: picked.length })}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function CalendarCard({ group }: { group: PayGroup }) {
  const [year, setYear] = useState(new Date().getFullYear());
  const { data, isLoading } = usePayCalendar(group.id, year);
  const generate = useGeneratePeriods();
  const lock = usePeriodLock();
  const fail = (fallback: string) => (err: unknown) => notify.error(apiErrorMessage(err, fallback));

  const toggleLock = (id: string, locked: boolean) => {
    if (!locked) {
      lock.mutate({ id, lock: true }, { onError: fail(tx('Could not lock the period')) });
      return;
    }
    const reason = window.prompt(tx('Why reopen this period?'));
    if (reason) lock.mutate({ id, lock: false, reason }, { onError: fail(tx('Could not unlock the period')) });
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{tx('Pay calendar')}</CardTitle>
        <div className="flex items-center gap-2">
          <Input type="number" className="h-8 w-24" value={year} aria-label={tx('Year')}
            onChange={(e) => setYear(Number(e.target.value))} />
          <Button size="sm" variant="outline" disabled={generate.isPending || !group.isActive}
            onClick={() => generate.mutate({ id: group.id, year }, {
              onSuccess: (r) => notify.success(tx('{{created}} periods created, {{skipped}} already existed', { created: r.created.length, skipped: r.skipped })),
              onError: fail(tx('Could not create the periods')),
            })}>
            <CalendarPlus className="mr-1 h-4 w-4" />{tx('Generate')}
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : data?.rows.length ? (
          <div className="max-h-80 overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                  <th className="py-2">{tx('Period')}</th>
                  <th className="py-2">{tx('Dates')}</th>
                  <th className="py-2">{tx('Pay date')}</th>
                  <th className="py-2">{tx('Run')}</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {data.rows.map((p) => {
                  const locked = p.status === 'LOCKED';
                  return (
                    <tr key={p.id} className="border-b last:border-0">
                      <td className="py-1.5 font-mono text-xs">{p.periodCode}</td>
                      <td className="py-1.5 tabular-nums">{day(p.startDate)} → {day(p.endDate)}</td>
                      <td className="py-1.5 tabular-nums">{day(p.paymentDate)}</td>
                      <td className="py-1.5">{p.run ? <Badge variant="outline">{p.run.runNumber} · {p.run.status}</Badge> : '—'}</td>
                      <td className="py-1.5 text-right">
                        <Button size="sm" variant="ghost" aria-label={locked ? tx('Unlock') : tx('Lock')} title={locked ? tx('Unlock') : tx('Lock')}
                          disabled={lock.isPending} onClick={() => toggleLock(p.id, locked)}>
                          {locked ? <Lock className="h-4 w-4 text-amber-600" /> : <LockOpen className="h-4 w-4 text-muted-foreground" />}
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-6 text-center text-sm text-muted-foreground">{tx('No periods for {{year}}. Generate the calendar.', { year })}</p>
        )}
      </CardContent>
    </Card>
  );
}
