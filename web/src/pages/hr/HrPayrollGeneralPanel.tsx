import { useEffect, useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { usePayrollSettings, useSavePayrollSettings, useStatutoryPacks, type PayrollSettings } from '@/features/hr/payroll-setup-api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const dayLabels = (): Record<string, string> => ({
  MONDAY: tx('Mon'), TUESDAY: tx('Tue'), WEDNESDAY: tx('Wed'), THURSDAY: tx('Thu'), FRIDAY: tx('Fri'), SATURDAY: tx('Sat'),
  SUNDAY: tx('Sun'),
});
const prorationOptions = (): { value: PayrollSettings['prorationBasis']; label: string }[] => [
  { value: 'CALENDAR_DAYS', label: tx('Calendar days in the period') },
  { value: 'WORKING_DAYS', label: tx('Working days in the period') },
  { value: 'FIXED_30', label: tx('Fixed 30-day month') },
];

const selectClass = 'h-9 rounded-md border bg-background px-3 text-sm';

/** Organisation payroll settings (HP-0): statutory pack, proration, rounding and the approval controls. */
export function HrPayrollGeneralPanel() {
  const { data, isLoading, isError } = usePayrollSettings();
  const { data: packs } = useStatutoryPacks();
  const save = useSavePayrollSettings();
  const [form, setForm] = useState<PayrollSettings | null>(null);

  useEffect(() => {
    if (data) setForm(data);
  }, [data]);

  if (isLoading || !form) {
    return isError ? (
      <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the payroll settings')}</p>
    ) : (
      <Skeleton className="h-64 w-full" />
    );
  }

  const set = <K extends keyof PayrollSettings>(k: K, v: PayrollSettings[K]) => setForm((f) => (f ? { ...f, [k]: v } : f));
  const weekend = form.weekendDays ? form.weekendDays.split(',') : [];
  const toggleDay = (d: string) =>
    set('weekendDays', (weekend.includes(d) ? weekend.filter((x) => x !== d) : [...weekend, d]).sort((a, b) => DAYS.indexOf(a) - DAYS.indexOf(b)).join(','));
  const pack = (packs ?? []).find((p) => p.code === form.statutoryPack);

  const submit = () =>
    save.mutate(
      {
        statutoryPack: form.statutoryPack,
        prorationBasis: form.prorationBasis,
        weekendDays: form.weekendDays,
        standardHoursPerDay: Number(form.standardHoursPerDay),
        roundingScale: Number(form.roundingScale),
        requireSeparateApprover: form.requireSeparateApprover,
        lockPeriodOnApproval: form.lockPeriodOnApproval,
        payslipNote: form.payslipNote ?? '',
      },
      {
        onSuccess: () => notify.success(tx('Payroll settings saved')),
        onError: (err) => notify.error(apiErrorMessage(err, tx('Could not save the payroll settings'))),
      },
    );

  return (
    <div className="space-y-4">
      {!form.configured && (
        <p className="rounded-md border border-dashed p-3 text-sm text-muted-foreground">
          {tx('Not saved yet: payroll runs work as before, without approval controls or period locks. Save to turn them on.')}
        </p>
      )}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Statutory rules')}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <div className="grid gap-1.5">
            <Label htmlFor="pack">{tx('Country pack')}</Label>
            <select id="pack" className={selectClass} value={form.statutoryPack}
              onChange={(e) => set('statutoryPack', e.target.value as PayrollSettings['statutoryPack'])}>
              {(packs ?? []).map((p) => (
                <option key={p.code} value={p.code}>
                  {p.name}{p.available ? '' : ` (${tx('coming soon')})`}
                </option>
              ))}
            </select>
            {pack && <p className="text-xs text-muted-foreground">{pack.description}</p>}
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Calculation engine')}</Label>
            <div className="flex h-9 items-center gap-2 text-sm">
              <Badge variant="outline">{form.engine}</Badge>
              <span className="text-muted-foreground">{tx('The decimal engine (v2) arrives with the statutory packs.')}</span>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Working time and rounding')}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <div className="grid gap-1.5">
            <Label htmlFor="proration">{tx('Partial period pay')}</Label>
            <select id="proration" className={selectClass} value={form.prorationBasis}
              onChange={(e) => set('prorationBasis', e.target.value as PayrollSettings['prorationBasis'])}>
              {prorationOptions().map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
            </select>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="hours">{tx('Standard hours per day')}</Label>
            <Input id="hours" type="number" min={1} max={24} step="0.5" value={String(form.standardHoursPerDay)}
              onChange={(e) => set('standardHoursPerDay', e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="scale">{tx('Decimal places on pay')}</Label>
            <Input id="scale" type="number" min={0} max={4} step={1} value={String(form.roundingScale)}
              onChange={(e) => set('roundingScale', Number(e.target.value))} />
          </div>
          <div className="grid gap-1.5 md:col-span-3">
            <Label>{tx('Weekend days')}</Label>
            <div className="flex flex-wrap gap-2">
              {DAYS.map((d) => (
                <Button key={d} type="button" size="sm" variant={weekend.includes(d) ? 'default' : 'outline'}
                  aria-pressed={weekend.includes(d)} onClick={() => toggleDay(d)}>
                  {dayLabels()[d]}
                </Button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Controls')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" className="mt-1" checked={form.requireSeparateApprover}
              onChange={(e) => set('requireSeparateApprover', e.target.checked)} />
            <span>
              <span className="font-medium">{tx('Separate approver')}</span>
              <span className="block text-muted-foreground">{tx('The person who calculates a payroll run cannot approve it.')}</span>
            </span>
          </label>
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" className="mt-1" checked={form.lockPeriodOnApproval}
              onChange={(e) => set('lockPeriodOnApproval', e.target.checked)} />
            <span>
              <span className="font-medium">{tx('Lock the period on approval')}</span>
              <span className="block text-muted-foreground">{tx('No new run for an approved period until its run is reversed.')}</span>
            </span>
          </label>
          <div className="grid gap-1.5">
            <Label htmlFor="note">{tx('Payslip note')}</Label>
            <Textarea id="note" rows={2} value={form.payslipNote ?? ''} onChange={(e) => set('payslipNote', e.target.value)} />
          </div>
        </CardContent>
      </Card>

      <div className="flex justify-end">
        <Button disabled={save.isPending || weekend.length > 6} onClick={submit}>
          {tx('Save settings')}
        </Button>
      </div>
    </div>
  );
}
