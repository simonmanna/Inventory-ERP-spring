import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trash2 } from 'lucide-react';
import { useHrDepartments } from '@/features/hr/api';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

interface OvertimeRule {
  id: string;
  code: string;
  name: string;
  appliesTo: string | null;
  weekdayMultiplier: number | string;
  weekendMultiplier: number | string;
  holidayMultiplier: number | string;
  weekendDays: string;
  isActive: boolean;
}

const KEY = ['hr', 'overtime-rules'];
const EMPTY = { code: '', name: '', departmentId: '', weekday: '1.5', weekend: '1.5', holiday: '2', weekendDays: 'SATURDAY,SUNDAY' };

/** Overtime multipliers per kind of day, for everyone or one department (HR-3). */
export function HrOvertimePanel() {
  const qc = useQueryClient();
  const { data, isLoading, isError } = useQuery({
    queryKey: KEY,
    queryFn: async () => (await api.get<{ rows: OvertimeRule[] }>('/hr/payroll/overtime-rules')).data,
  });
  const { data: departments } = useHrDepartments();
  const [form, setForm] = useState(EMPTY);
  const set = (k: string, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const refresh = () => qc.invalidateQueries({ queryKey: KEY });

  const create = useMutation({
    mutationFn: async () =>
      (
        await api.post('/hr/payroll/overtime-rules', {
          code: form.code,
          name: form.name,
          appliesTo: form.departmentId ? `departmentId:${form.departmentId}` : undefined,
          weekdayMultiplier: Number(form.weekday),
          weekendMultiplier: Number(form.weekend),
          holidayMultiplier: Number(form.holiday),
          weekendDays: form.weekendDays,
        })
      ).data,
    onSuccess: () => {
      notify.success(tx('Overtime rule added'));
      setForm(EMPTY);
      refresh();
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('Could not add the overtime rule'))),
  });

  const remove = useMutation({
    mutationFn: async (id: string) => (await api.delete(`/hr/payroll/overtime-rules/${id}`)).data,
    onSuccess: refresh,
    onError: (err) => notify.error(apiErrorMessage(err, tx('The action failed'))),
  });

  const departmentName = (appliesTo: string | null) => {
    if (!appliesTo) return tx('Everyone');
    const id = appliesTo.split(':')[1];
    return (departments?.rows ?? []).find((d: { id: string }) => d.id === id)?.name ?? appliesTo;
  };
  const valid = form.code && form.name && Number(form.weekday) > 0 && Number(form.weekend) > 0 && Number(form.holiday) > 0;

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        {tx('Without a rule, overtime is paid at 1.5 times the hourly rate. A department rule beats the general one.')}
      </p>
      <Card>
        <CardContent className="grid gap-3 pt-6 md:grid-cols-4">
          <div className="grid gap-1.5">
            <Label>{tx('Code')}</Label>
            <Input value={form.code} onChange={(e) => set('code', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Name')}</Label>
            <Input value={form.name} onChange={(e) => set('name', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Department')}</Label>
            <select
              className="h-9 rounded-md border bg-background px-3 text-sm"
              value={form.departmentId}
              onChange={(e) => set('departmentId', e.target.value)}
            >
              <option value="">{tx('Everyone')}</option>
              {(departments?.rows ?? []).map((d: { id: string; name: string }) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Weekend days')}</Label>
            <Input value={form.weekendDays} onChange={(e) => set('weekendDays', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Weekday multiplier')}</Label>
            <Input type="number" step="0.05" min={0} value={form.weekday} onChange={(e) => set('weekday', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Weekend multiplier')}</Label>
            <Input type="number" step="0.05" min={0} value={form.weekend} onChange={(e) => set('weekend', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Holiday multiplier')}</Label>
            <Input type="number" step="0.05" min={0} value={form.holiday} onChange={(e) => set('holiday', e.target.value)} />
          </div>
          <div className="flex items-end">
            <Button disabled={create.isPending || !valid} onClick={() => create.mutate()}>
              {tx('Add')}
            </Button>
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardContent className="pt-6">
          {isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : isError ? (
            <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the overtime rules')}</p>
          ) : data?.rows.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                    <th className="py-2">{tx('Code')}</th>
                    <th className="py-2">{tx('Name')}</th>
                    <th className="py-2">{tx('Applies to')}</th>
                    <th className="py-2 text-right">{tx('Weekday')}</th>
                    <th className="py-2 text-right">{tx('Weekend')}</th>
                    <th className="py-2 text-right">{tx('Holiday')}</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => (
                    <tr key={r.id} className="border-b last:border-0">
                      <td className="py-2 font-mono text-xs">{r.code}</td>
                      <td className="py-2">{r.name}</td>
                      <td className="py-2">{departmentName(r.appliesTo)}</td>
                      <td className="py-2 text-right">× {Number(r.weekdayMultiplier)}</td>
                      <td className="py-2 text-right">× {Number(r.weekendMultiplier)}</td>
                      <td className="py-2 text-right">× {Number(r.holidayMultiplier)}</td>
                      <td className="py-2 text-right">
                        <Button size="sm" variant="ghost" aria-label={tx('Delete')} onClick={() => remove.mutate(r.id)}>
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="py-8 text-center text-sm text-muted-foreground">{tx('No overtime rules yet.')}</p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
