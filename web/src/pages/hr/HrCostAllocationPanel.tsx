import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import { useHrDepartments, useHrEmployees } from '@/features/hr/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

interface CostLine {
  departmentId: string | null;
  departmentName: string | null;
  costCenterId: string | null;
  costCenterCode: string | null;
  costCenterName: string | null;
  branchId: string | null;
  branchName: string | null;
  employees: number;
  amount: number | string;
}

interface Line {
  costCenterId: string;
  percent: string;
}

const SELECT = 'h-9 rounded-md border bg-background px-3 text-sm';

/** Which cost centres carry an employee's or department's pay, and what payroll charged them (HR-5). */
export function HrCostAllocationPanel() {
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        {tx('An employee\'s split beats the department\'s. Without any split, payroll posts one salary line as before.')}
      </p>
      <SplitEditor />
      <CostReport />
    </div>
  );
}

function SplitEditor() {
  const qc = useQueryClient();
  const [scope, setScope] = useState('department');
  const [targetId, setTargetId] = useState('');
  const [lines, setLines] = useState<Line[]>([{ costCenterId: '', percent: '100' }]);
  const { data: departments } = useHrDepartments();
  const { data: employees } = useHrEmployees({ pageSize: 200 });
  const { data: centers } = useQuery({
    queryKey: ['cost-centers', 'hr-allocation'],
    queryFn: async () =>
      (await api.get<{ data: { id: string; code: string; name: string }[] }>('/cost-centers', { params: { pageSize: 200 } })).data,
  });
  const { data: rules } = useQuery({
    queryKey: ['hr', 'cost-rules', scope, targetId],
    enabled: !!targetId,
    queryFn: async () =>
      (
        await api.get<{ rows: { costCenterId: string; percent: number | string }[] }>(
          '/hr/payroll/cost-allocation-rules',
          { params: { scope, targetId } },
        )
      ).data,
  });
  useEffect(() => {
    if (!rules) return;
    setLines(
      rules.rows.length
        ? rules.rows.map((r) => ({ costCenterId: r.costCenterId, percent: String(Number(r.percent)) }))
        : [{ costCenterId: '', percent: '100' }],
    );
  }, [rules]);
  const save = useMutation({
    mutationFn: async (body: { costCenterId: string; percent: number }[]) =>
      (await api.put('/hr/payroll/cost-allocation-rules', { scope, targetId, lines: body })).data,
    onSuccess: () => {
      notify.success(tx('Cost split saved'));
      qc.invalidateQueries({ queryKey: ['hr', 'cost-rules'] });
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('Could not save the cost split'))),
  });

  const used = lines.filter((l) => l.costCenterId);
  const total = used.reduce((s, l) => s + Number(l.percent || 0), 0);
  const setLine = (i: number, patch: Partial<Line>) =>
    setLines((ls) => ls.map((l, n) => (n === i ? { ...l, ...patch } : l)));

  return (
    <Card>
      <CardHeader className="bg-muted/30 border-b rounded-t-lg">
        <CardTitle className="text-sm">{tx('Cost split')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 pt-4">
        <div className="flex flex-wrap gap-2">
          <select
            className={SELECT}
            value={scope}
            onChange={(e) => {
              setScope(e.target.value);
              setTargetId('');
            }}
          >
            <option value="department">{tx('Department')}</option>
            <option value="employee">{tx('Employee')}</option>
          </select>
          <select className={SELECT} value={targetId} onChange={(e) => setTargetId(e.target.value)}>
            <option value="">{tx('Select…')}</option>
            {scope === 'department'
              ? (departments?.rows ?? []).map((d: { id: string; name: string }) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))
              : (employees?.rows ?? []).map((e: any) => (
                  <option key={e.id} value={e.id}>
                    {e.firstName} {e.lastName ?? ''} ({e.employeeCode})
                  </option>
                ))}
          </select>
        </div>
        {targetId ? (
          <>
            {lines.map((l, i) => (
              <div key={i} className="flex flex-wrap items-center gap-2">
                <select
                  className={SELECT}
                  value={l.costCenterId}
                  onChange={(e) => setLine(i, { costCenterId: e.target.value })}
                >
                  <option value="">{tx('Select…')}</option>
                  {(centers?.data ?? []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.code} — {c.name}
                    </option>
                  ))}
                </select>
                <Input
                  className="w-28"
                  type="number"
                  min={0}
                  max={100}
                  value={l.percent}
                  onChange={(e) => setLine(i, { percent: e.target.value })}
                />
                <span className="text-sm text-muted-foreground">%</span>
                <Button
                  size="sm"
                  variant="ghost"
                  aria-label={tx('Delete')}
                  onClick={() => setLines((ls) => ls.filter((_, n) => n !== i))}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <div className="flex flex-wrap items-center gap-3">
              <Button
                size="sm"
                variant="outline"
                onClick={() => setLines((ls) => [...ls, { costCenterId: '', percent: '' }])}
              >
                <Plus className="mr-1.5 h-4 w-4" />
                {tx('Add line')}
              </Button>
              <span className={`text-sm ${total === 100 || used.length === 0 ? 'text-muted-foreground' : 'text-destructive'}`}>
                {tx('Total')}: {total}%
              </span>
              <Button
                size="sm"
                disabled={save.isPending || (used.length > 0 && total !== 100)}
                onClick={() => save.mutate(used.map((l) => ({ costCenterId: l.costCenterId, percent: Number(l.percent) })))}
              >
                {tx('Save')}
              </Button>
            </div>
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}

function CostReport() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { data, isError, isFetching } = useQuery({
    queryKey: ['hr', 'cost-report', from, to],
    enabled: !!from && !!to,
    queryFn: async () =>
      (await api.get<{ rows: CostLine[] }>('/hr/payroll/cost-report', { params: { from, to } })).data,
  });
  const rows = data?.rows ?? [];
  const total = rows.reduce((s, r) => s + Number(r.amount), 0);

  return (
    <Card>
      <CardHeader className="bg-muted/30 border-b rounded-t-lg">
        <CardTitle className="text-sm">{tx('Payroll cost by department and cost centre')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 pt-4">
        <div className="flex flex-wrap gap-3">
          <div className="grid gap-1.5">
            <Label>{tx('From')}</Label>
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('To')}</Label>
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
        </div>
        {!from || !to ? (
          <p className="py-6 text-center text-sm text-muted-foreground">{tx('Pick the payroll periods to report on.')}</p>
        ) : isError ? (
          <p className="py-6 text-center text-sm text-destructive">{tx('Could not load the report')}</p>
        ) : rows.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                  <th className="py-2">{tx('Department')}</th>
                  <th className="py-2">{tx('Cost center')}</th>
                  <th className="py-2">{tx('Branch')}</th>
                  <th className="py-2 text-right">{tx('Employees')}</th>
                  <th className="py-2 text-right">{tx('Amount')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i} className="border-b last:border-0">
                    <td className="py-2">{r.departmentName ?? '—'}</td>
                    <td className="py-2">{r.costCenterName ?? tx('Unallocated')}</td>
                    <td className="py-2">{r.branchName ?? '—'}</td>
                    <td className="py-2 text-right">{r.employees}</td>
                    <td className="py-2 text-right">{Number(r.amount).toLocaleString()}</td>
                  </tr>
                ))}
                <tr className="font-medium">
                  <td className="py-2" colSpan={4}>
                    {tx('Total')}
                  </td>
                  <td className="py-2 text-right">{total.toLocaleString()}</td>
                </tr>
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-6 text-center text-sm text-muted-foreground">
            {isFetching ? tx('Loading…') : tx('No approved payroll in this range.')}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
