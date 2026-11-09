import { AlertTriangle } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { money } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { StatCard } from '@/features/projects/ui';
import { useCostReport, type JobProject } from '@/features/projects/costing-api';
import { Section, costElementLabel, num, numCell, tdClass, thClass, wbsLabel } from './shared';

/** Budget vs committed vs actual vs estimate at completion, margin against the contract, per-WBS rows, WIP tie-out. */
export function SummarySection({ project }: { project: JobProject }) {
  const { data: r, isLoading } = useCostReport(project.id);
  if (isLoading || !r) return <Skeleton className="h-64 w-full" />;

  const t = r.totals;
  const contract = project.contractValue == null || project.contractValue === '' ? null : num(project.contractValue);
  const margin = contract == null ? null : contract - num(t.eac);
  const marginPct = contract ? (margin! / contract) * 100 : null;
  const tieOff = num(r.wipTieOut.difference) !== 0;

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <StatCard label={tx('Approved budget')} value={money(t.budget)} hint={r.budgetId ? undefined : tx('No approved budget')} tone={r.budgetId ? undefined : 'warning'} />
        <StatCard label={tx('Committed')} value={money(t.committed)} hint={tx('Open purchase commitments')} />
        <StatCard label={tx('Actual cost')} value={money(t.actual)} />
        <StatCard label={tx('Cost to complete')} value={money(t.costToComplete)} />
        <StatCard label={tx('Estimate at completion')} value={money(t.eac)} hint={tx('Actual + committed + to complete')} />
        <StatCard label={tx('Variance')} value={money(t.variance)} tone={num(t.variance) < 0 ? 'danger' : 'success'} hint={tx('Budget − estimate at completion')} />
      </div>
      {contract != null && (
        <div className="grid gap-3 sm:grid-cols-3">
          <StatCard label={tx('Contract value')} value={money(contract)} />
          <StatCard label={tx('Projected margin')} value={money(margin)} tone={margin! < 0 ? 'danger' : 'success'} />
          <StatCard label={tx('Projected margin %')} value={marginPct == null ? '—' : `${marginPct.toFixed(1)} %`} tone={margin! < 0 ? 'danger' : undefined} />
        </div>
      )}

      <Section title={tx('By WBS element and cost element')}>
        {r.rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">{tx('No budget, cost or forecast recorded yet.')}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b">
                <tr>
                  <th className={thClass}>{tx('WBS')}</th>
                  <th className={thClass}>{tx('Cost element')}</th>
                  <th className={cn(thClass, 'text-right')}>{tx('Budget')}</th>
                  <th className={cn(thClass, 'text-right')}>{tx('Actual')}</th>
                  <th className={cn(thClass, 'text-right')}>{tx('To complete')}</th>
                  <th className={cn(thClass, 'text-right')}>{tx('EAC')}</th>
                  <th className={cn(thClass, 'text-right')}>{tx('Variance')}</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {r.rows.map((row) => (
                  <tr key={`${row.wbsId}:${row.costElement}`}>
                    <td className={tdClass}>{wbsLabel(project.wbs, row.wbsId, row.wbsCode)}</td>
                    <td className={tdClass}>{costElementLabel(row.costElement)}</td>
                    <td className={numCell}>{money(row.budget)}</td>
                    <td className={numCell}>{money(row.actual)}</td>
                    <td className={numCell}>{money(row.costToComplete)}</td>
                    <td className={numCell}>{money(row.eac)}</td>
                    <td className={cn(numCell, num(row.variance) < 0 && 'text-rose-600')}>{money(row.variance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <Section title={tx('WIP tie-out')}>
        <div className="grid gap-3 text-sm sm:grid-cols-3">
          <div><p className="text-xs text-muted-foreground">{tx('General ledger WIP')}</p><p className="font-semibold tabular-nums">{money(r.wipTieOut.glWip)}</p></div>
          <div><p className="text-xs text-muted-foreground">{tx('Cost sub-ledger (net of releases)')}</p><p className="font-semibold tabular-nums">{money(r.wipTieOut.subledger)}</p></div>
          <div>
            <p className="text-xs text-muted-foreground">{tx('Difference')}</p>
            <p className={cn('flex items-center gap-1 font-semibold tabular-nums', tieOff && 'text-rose-600')}>
              {tieOff && <AlertTriangle className="h-4 w-4" />}{money(r.wipTieOut.difference)}
            </p>
          </div>
        </div>
      </Section>
    </div>
  );
}
