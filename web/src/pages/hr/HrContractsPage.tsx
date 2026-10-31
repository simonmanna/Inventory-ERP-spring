import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import {
  useContractAction,
  useExpiringContracts,
  useHrContracts,
  type HrContract,
} from '@/features/hr/contracts-api';
import { useHrEmployees } from '@/features/hr/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

const PAGE = 25;
const TYPES = ['permanent', 'fixed_term', 'casual', 'seasonal', 'consultant', 'apprenticeship'];
const DATED = ['fixed_term', 'seasonal', 'apprenticeship'];
const STATUSES = ['draft', 'active', 'expired', 'terminated', 'superseded'];
const TONE: Record<string, string> = {
  active: 'bg-emerald-100 text-emerald-800',
  draft: 'bg-sky-100 text-sky-800',
  expired: 'bg-amber-100 text-amber-800',
  terminated: 'bg-rose-100 text-rose-800',
};
const SELECT = 'h-9 rounded-md border bg-background px-3 text-sm';
const label = (s: string) => s.replace(/_/g, ' ');

function ContractTable({ rows, onAction }: { rows: HrContract[]; onAction: (c: HrContract, a: string) => void }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs uppercase text-muted-foreground">
            <th className="py-2">{tx('Code')}</th>
            <th className="py-2">{tx('Employee')}</th>
            <th className="py-2">{tx('Type')}</th>
            <th className="py-2">{tx('Start')}</th>
            <th className="py-2">{tx('End')}</th>
            <th className="py-2">{tx('Status')}</th>
            <th className="py-2" />
          </tr>
        </thead>
        <tbody>
          {rows.map((c) => (
            <tr key={c.id} className="border-b last:border-0">
              <td className="py-2 font-mono text-xs">{c.code}</td>
              <td className="py-2">
                <Link className="hover:underline" to={`/hr/employees/${c.employeeId}`}>
                  {c.employee?.firstName} {c.employee?.lastName ?? ''}
                </Link>
              </td>
              <td className="py-2">{label(c.contractType)}</td>
              <td className="py-2">{c.startDate}</td>
              <td className="py-2">{c.terminatedOn ?? c.endDate ?? '—'}</td>
              <td className="py-2">
                <Badge className={TONE[c.status] ?? 'bg-muted text-muted-foreground'}>{label(c.status)}</Badge>
              </td>
              <td className="py-2 text-right">
                <div className="flex justify-end gap-1">
                  {c.status === 'draft' ? (
                    <>
                      <Button size="sm" variant="outline" onClick={() => onAction(c, 'activate')}>
                        {tx('Activate')}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => onAction(c, 'delete')}>
                        {tx('Delete')}
                      </Button>
                    </>
                  ) : null}
                  {c.status === 'active' || c.status === 'expired' ? (
                    <Button size="sm" variant="outline" onClick={() => onAction(c, 'renew')}>
                      {tx('Renew')}
                    </Button>
                  ) : null}
                  {c.status === 'active' ? (
                    <Button size="sm" variant="ghost" onClick={() => onAction(c, 'terminate')}>
                      {tx('Terminate')}
                    </Button>
                  ) : null}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Employment contracts: who holds what, what is ending soon, and renewals. */
export function HrContractsPage() {
  const [tab, setTab] = useState('all');
  const [status, setStatus] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [skip, setSkip] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [dialog, setDialog] = useState<{ contract: HrContract; kind: 'renew' | 'terminate' } | null>(null);
  const { data, isLoading, isError } = useHrContracts({ status, employeeId, take: PAGE, skip });
  const { data: expiring } = useExpiringContracts(60);
  const { data: employees } = useHrEmployees({ pageSize: 200 });
  const action = useContractAction();

  const onAction = async (c: HrContract, a: string) => {
    if (a === 'renew' || a === 'terminate') {
      setDialog({ contract: c, kind: a });
      return;
    }
    try {
      await action.mutateAsync(
        a === 'delete'
          ? { method: 'delete', path: `/hr/contracts/${c.id}` }
          : { method: 'post', path: `/hr/contracts/${c.id}/activate` },
      );
      notify.success(tx('Contract updated'));
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not update the contract')));
    }
  };

  const total = data?.total ?? 0;

  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{tx('Contracts')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Employment contracts. Activating a contract replaces the employee\'s current one.')}
          </p>
        </div>
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="mr-1.5 h-4 w-4" />
          {tx('New contract')}
        </Button>
      </div>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="all">{tx('All contracts')}</TabsTrigger>
          <TabsTrigger value="expiring">
            {tx('Ending soon')} {expiring?.total ? `(${expiring.total})` : ''}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="all" className="mt-4">
          <Card>
            <CardContent className="space-y-3 pt-6">
              <div className="flex flex-wrap gap-2">
                <select
                  className={SELECT}
                  value={employeeId}
                  onChange={(e) => {
                    setEmployeeId(e.target.value);
                    setSkip(0);
                  }}
                >
                  <option value="">{tx('All employees')}</option>
                  {(employees?.rows ?? []).map((e: any) => (
                    <option key={e.id} value={e.id}>
                      {e.firstName} {e.lastName ?? ''} ({e.employeeCode})
                    </option>
                  ))}
                </select>
                <select
                  className={SELECT}
                  value={status}
                  onChange={(e) => {
                    setStatus(e.target.value);
                    setSkip(0);
                  }}
                >
                  <option value="">{tx('All statuses')}</option>
                  {STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {label(s)}
                    </option>
                  ))}
                </select>
              </div>
              {isLoading ? (
                <Skeleton className="h-40 w-full" />
              ) : isError ? (
                <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the contracts')}</p>
              ) : data?.rows.length ? (
                <ContractTable rows={data.rows} onAction={onAction} />
              ) : (
                <p className="py-8 text-center text-sm text-muted-foreground">{tx('No contracts yet.')}</p>
              )}
              {total > PAGE ? (
                <div className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
                  <span>
                    {skip + 1}–{Math.min(skip + PAGE, total)} / {total}
                  </span>
                  <Button size="sm" variant="outline" disabled={skip === 0} onClick={() => setSkip(Math.max(0, skip - PAGE))}>
                    {tx('Previous')}
                  </Button>
                  <Button size="sm" variant="outline" disabled={skip + PAGE >= total} onClick={() => setSkip(skip + PAGE)}>
                    {tx('Next')}
                  </Button>
                </div>
              ) : null}
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="expiring" className="mt-4">
          <Card>
            <CardContent className="pt-6">
              {expiring?.rows.length ? (
                <ContractTable rows={expiring.rows} onAction={onAction} />
              ) : (
                <p className="py-8 text-center text-sm text-muted-foreground">
                  {tx('No contract ends in the next 60 days.')}
                </p>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
      <CreateDialog open={createOpen} onOpenChange={setCreateOpen} employees={employees?.rows ?? []} />
      <FollowUpDialog state={dialog} onClose={() => setDialog(null)} />
    </div>
  );
}

function CreateDialog({
  open,
  onOpenChange,
  employees,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  employees: any[];
}) {
  const action = useContractAction();
  const empty = { employeeId: '', contractType: 'permanent', startDate: '', endDate: '', hoursPerWeek: '', signedOn: '' };
  const [form, setForm] = useState(empty);
  const set = (k: string, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const needsEnd = DATED.includes(form.contractType);
  const invalid =
    !form.employeeId || !form.startDate || (needsEnd && !form.endDate) || (!!form.endDate && form.endDate < form.startDate);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('New contract')}</DialogTitle>
          <DialogDescription>{tx('Saved as a draft until you activate it.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label>{tx('Employee')}</Label>
            <select className={SELECT} value={form.employeeId} onChange={(e) => set('employeeId', e.target.value)}>
              <option value="">{tx('Select…')}</option>
              {employees.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.firstName} {e.lastName ?? ''} ({e.employeeCode})
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Type')}</Label>
            <select className={SELECT} value={form.contractType} onChange={(e) => set('contractType', e.target.value)}>
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {label(t)}
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Start')}</Label>
              <Input type="date" value={form.startDate} onChange={(e) => set('startDate', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('End')}</Label>
              <Input type="date" value={form.endDate} onChange={(e) => set('endDate', e.target.value)} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Hours per week')}</Label>
              <Input type="number" min={0} value={form.hoursPerWeek} onChange={(e) => set('hoursPerWeek', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Signed on')}</Label>
              <Input type="date" value={form.signedOn} onChange={(e) => set('signedOn', e.target.value)} />
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {tx('Cancel')}
          </Button>
          <Button
            disabled={action.isPending || invalid}
            onClick={async () => {
              try {
                await action.mutateAsync({
                  method: 'post',
                  path: '/hr/contracts',
                  body: {
                    employeeId: form.employeeId,
                    contractType: form.contractType,
                    startDate: form.startDate,
                    endDate: form.endDate || undefined,
                    hoursPerWeek: form.hoursPerWeek ? Number(form.hoursPerWeek) : undefined,
                    signedOn: form.signedOn || undefined,
                  },
                });
                notify.success(tx('Contract created'));
                onOpenChange(false);
                setForm(empty);
              } catch (err) {
                notify.error(apiErrorMessage(err, tx('Could not create the contract')));
              }
            }}
          >
            {tx('Create')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function FollowUpDialog({
  state,
  onClose,
}: {
  state: { contract: HrContract; kind: 'renew' | 'terminate' } | null;
  onClose: () => void;
}) {
  const action = useContractAction();
  const [value, setValue] = useState('');
  const renew = state?.kind === 'renew';

  return (
    <Dialog open={!!state} onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{renew ? tx('Renew contract') : tx('Terminate contract')}</DialogTitle>
          <DialogDescription>
            {renew
              ? tx('Creates a draft with the same terms, starting the day after this one ends.')
              : tx('Ends the contract today. The employee\'s status is changed separately.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-1.5">
          <Label>{renew ? tx('New end date') : tx('Reason')}</Label>
          <Input type={renew ? 'date' : 'text'} value={value} onChange={(e) => setValue(e.target.value)} />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {tx('Cancel')}
          </Button>
          <Button
            disabled={action.isPending || (!renew && !value)}
            onClick={async () => {
              try {
                await action.mutateAsync({
                  method: 'post',
                  path: `/hr/contracts/${state?.contract.id}/${state?.kind}`,
                  body: renew ? { endDate: value || undefined } : { reason: value },
                });
                notify.success(tx('Contract updated'));
                setValue('');
                onClose();
              } catch (err) {
                notify.error(apiErrorMessage(err, tx('Could not update the contract')));
              }
            }}
          >
            {tx('Confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
