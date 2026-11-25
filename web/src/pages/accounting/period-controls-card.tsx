import { useEffect, useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import {
  usePeriodControls,
  useUpdatePeriodControls,
  type PeriodControls,
  type PeriodControlsInput,
} from '@/features/accounting/api';
import { tx } from '@/lib/i18n/tx';

/** The day before an ISO date (YYYY-MM-DD). */
function dayBefore(day: string): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

function inputOf(c: PeriodControls): PeriodControlsInput {
  return {
    booksLockDate: c.booksLockDate,
    requireFiscalPeriod: c.requireFiscalPeriod,
    autoCreatePeriods: c.autoCreatePeriods,
    futurePostingLimitDays: c.futurePostingLimitDays,
  };
}

/**
 * Period controls (finance readiness audit B1): the books lock date, whether every posting needs an open period,
 * automatic creation of next year's periods and the furthest posting date ahead. Same permissions as the backend:
 * fiscal_period:update to change them, and fiscal_period:reopen as well to move the lock date earlier or remove it.
 */
export function PeriodControlsCard() {
  const has = useAuthStore((s) => s.hasPermission);
  const canUpdate = has(PERMISSIONS.fiscalPeriod.update);
  const canReopen = has(PERMISSIONS.fiscalPeriod.reopen);
  const { data, isLoading } = usePeriodControls();
  const save = useUpdatePeriodControls();
  const [form, setForm] = useState<PeriodControlsInput | null>(null);

  useEffect(() => {
    if (data) setForm(inputOf(data));
  }, [data]);

  if (isLoading || !data || !form) {
    return <Skeleton className="h-40 w-full" />;
  }

  const dirty = JSON.stringify(form) !== JSON.stringify(inputOf(data));
  const loosens =
    data.booksLockDate != null && (form.booksLockDate == null || form.booksLockDate < data.booksLockDate);
  const needsReopen = loosens && !canReopen;
  const limitValid =
    Number.isInteger(form.futurePostingLimitDays) &&
    form.futurePostingLimitDays >= 0 &&
    form.futurePostingLimitDays <= 36500;
  const set = (patch: Partial<PeriodControlsInput>) => setForm({ ...form, ...patch });

  return (
    <Card>
      <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg flex-row items-center justify-between">
        <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
          <ShieldCheck className="h-3.5 w-3.5" /> {tx('Period controls')}
        </CardTitle>
        <span className="text-[10px] text-muted-foreground">
          {data.periodsThrough
            ? tx('Periods run through {{date}}', { date: data.periodsThrough })
            : tx('No fiscal periods yet')}
        </span>
      </CardHeader>
      <CardContent className="p-5 grid gap-5 md:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="books-lock-date">{tx('Books locked through')}</Label>
          <div className="flex items-center gap-2">
            <Input
              id="books-lock-date"
              type="date"
              className="max-w-[12rem]"
              value={form.booksLockDate ?? ''}
              max={dayBefore(data.today)}
              disabled={!canUpdate}
              onChange={(e) => set({ booksLockDate: e.target.value || null })}
            />
            {form.booksLockDate && canUpdate && (
              <Button type="button" variant="outline" size="sm" onClick={() => set({ booksLockDate: null })}>
                {tx('Clear')}
              </Button>
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            {tx('Nothing dated on or before this day can be posted, whatever its period. Moving it earlier or clearing it needs the reopen permission.')}
          </p>
          {needsReopen && (
            <p className="text-xs text-amber-700">
              {tx('You need the fiscal period reopen permission to move the lock date earlier or clear it.')}
            </p>
          )}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="future-limit">{tx('Furthest posting date ahead (days)')}</Label>
          <Input
            id="future-limit"
            type="number"
            min={0}
            max={36500}
            className="max-w-[12rem]"
            value={Number.isNaN(form.futurePostingLimitDays) ? '' : form.futurePostingLimitDays}
            disabled={!canUpdate}
            onChange={(e) => set({ futurePostingLimitDays: e.target.value === '' ? Number.NaN : Number(e.target.value) })}
          />
          <p className="text-xs text-muted-foreground">
            {tx('A posting dated further ahead is refused, to catch a mistyped year. 0 = no limit; 366 suits most businesses.')}
          </p>
        </div>

        <label className="flex items-start gap-2.5 text-sm">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={form.requireFiscalPeriod}
            disabled={!canUpdate}
            onChange={(e) => set({ requireFiscalPeriod: e.target.checked })}
          />
          <span className="space-y-0.5">
            <span className="block font-medium">{tx('Require an open period for every posting')}</span>
            <span className="block text-xs text-muted-foreground">
              {tx('A posting on a day that no open fiscal period covers is refused.')}
            </span>
            {!data.openPeriodCoversToday && (
              <span className="block text-xs text-amber-700">{tx('No open period covers today.')}</span>
            )}
          </span>
        </label>

        <label className="flex items-start gap-2.5 text-sm">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={form.autoCreatePeriods}
            disabled={!canUpdate}
            onChange={(e) => set({ autoCreatePeriods: e.target.checked })}
          />
          <span className="space-y-0.5">
            <span className="block font-medium">{tx("Create next year's periods automatically")}</span>
            <span className="block text-xs text-muted-foreground">
              {tx('{{days}} days before the last period ends, the next fiscal year is created with the same periodicity. Off: an alert is raised instead.', { days: data.autoCreateLeadDays })}
            </span>
          </span>
        </label>

        {canUpdate && (
          <div className="md:col-span-2 flex justify-end gap-2">
            <Button type="button" variant="outline" disabled={!dirty || save.isPending} onClick={() => setForm(inputOf(data))}>
              {tx('Reset')}
            </Button>
            <Button
              type="button"
              disabled={!dirty || needsReopen || !limitValid || save.isPending}
              onClick={() => save.mutate(form)}
            >
              {save.isPending ? tx('Saving…') : tx('Save controls')}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
