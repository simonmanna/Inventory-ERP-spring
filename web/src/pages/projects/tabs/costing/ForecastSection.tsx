import { useMemo, useState } from 'react';
import { Plus, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { money } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { selectClass, shortDate, todayIso } from '@/features/projects/ui';
import {
  FULFILMENT_CLASSES, useCostReport, useCostingMutation, useForecast, type FulfilmentClass, type JobProject,
} from '@/features/projects/costing-api';
import { CostElementSelect, FULFILMENT_LABEL, Section, WbsSelect, costElementLabel, numCell, tdClass, thClass, wbsLabel } from './shared';

interface Row {
  key: string;
  wbsId: string;
  costElement: string;
  budget: string;
  actual: string;
  costToComplete: string;
  fulfilmentClass: FulfilmentClass;
  asOf?: string;
}

interface Edit { costToComplete: string; fulfilmentClass: FulfilmentClass }

/**
 * Cost to complete per WBS element and cost element. Saving appends a new forecast version for the changed rows only
 * (the latest row per pair is in force); the fulfilment class feeds the onerous-contract assessment.
 */
export function ForecastSection({ project, canManage }: { project: JobProject; canManage: boolean }) {
  const { data: forecast = [], isLoading } = useForecast(project.id);
  const { data: report } = useCostReport(project.id);
  const [edits, setEdits] = useState<Record<string, Edit>>({});
  const [extra, setExtra] = useState<Array<{ wbsId: string; costElement: string }>>([]);
  const [adding, setAdding] = useState({ wbsId: '', costElement: 'material' });
  const [asOf, setAsOf] = useState(todayIso());

  const rows = useMemo<Row[]>(() => {
    const map = new Map<string, Row>();
    const get = (wbsId: string, costElement: string) => {
      const key = `${wbsId}:${costElement}`;
      let r = map.get(key);
      if (!r) {
        r = { key, wbsId, costElement, budget: '0', actual: '0', costToComplete: '0', fulfilmentClass: 'incremental' };
        map.set(key, r);
      }
      return r;
    };
    for (const r of report?.rows ?? []) Object.assign(get(r.wbsId, r.costElement), { budget: String(r.budget), actual: String(r.actual) });
    for (const f of forecast) {
      Object.assign(get(f.wbsId, f.costElement), { costToComplete: String(f.costToComplete), fulfilmentClass: f.fulfilmentClass, asOf: f.asOf });
    }
    for (const e of extra) get(e.wbsId, e.costElement);
    return [...map.values()].sort((a, b) => wbsLabel(project.wbs, a.wbsId).localeCompare(wbsLabel(project.wbs, b.wbsId)) || a.costElement.localeCompare(b.costElement));
  }, [report, forecast, extra, project.wbs]);

  const save = useCostingMutation(() => ({
    method: 'put',
    url: `/projects/${project.id}/forecast`,
    body: {
      asOf: asOf || undefined,
      lines: Object.entries(edits).map(([key, e]) => {
        const [wbsId, costElement] = key.split(':');
        return { wbsId, costElement, costToComplete: e.costToComplete.trim() || '0', fulfilmentClass: e.fulfilmentClass };
      }),
    },
  }), { success: tx('Forecast saved') });

  const dirty = Object.keys(edits).length;
  const valid = Object.values(edits).every((e) => e.costToComplete.trim() === '' || Number(e.costToComplete) >= 0);
  const edit = (r: Row, patch: Partial<Edit>) => setEdits((cur) => ({
    ...cur,
    [r.key]: { costToComplete: cur[r.key]?.costToComplete ?? r.costToComplete, fulfilmentClass: cur[r.key]?.fulfilmentClass ?? r.fulfilmentClass, ...patch },
  }));

  if (isLoading) return <Skeleton className="h-48 w-full" />;

  return (
    <Section
      title={tx('Cost-to-complete forecast')}
      actions={canManage && (
        <>
          <label className="flex items-center gap-2 text-xs text-muted-foreground">
            {tx('As of')}
            <Input type="date" className="h-8 w-40" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </label>
          <Button size="sm" disabled={!dirty || !valid || save.isPending} onClick={() => save.mutate(undefined, { onSuccess: () => { setEdits({}); setExtra([]); } })}>
            <Save className="mr-1.5 h-4 w-4" />{dirty ? tx('Save {{count}} change(s)', { count: dirty }) : tx('Save')}
          </Button>
        </>
      )}
    >
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">{tx('Nothing to forecast yet. Approve a budget or add a line below.')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b">
              <tr>
                <th className={thClass}>{tx('WBS')}</th>
                <th className={thClass}>{tx('Cost element')}</th>
                <th className={cn(thClass, 'text-right')}>{tx('Budget')}</th>
                <th className={cn(thClass, 'text-right')}>{tx('Actual')}</th>
                <th className={cn(thClass, 'text-right')}>{tx('Cost to complete')}</th>
                <th className={thClass}>{tx('Fulfilment class')}</th>
                <th className={thClass}>{tx('As of')}</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {rows.map((r) => {
                const e = edits[r.key];
                return (
                  <tr key={r.key} className={cn(e && 'bg-amber-50/60 dark:bg-amber-500/10')}>
                    <td className={tdClass}>{wbsLabel(project.wbs, r.wbsId)}</td>
                    <td className={tdClass}>{costElementLabel(r.costElement)}</td>
                    <td className={numCell}>{money(r.budget)}</td>
                    <td className={numCell}>{money(r.actual)}</td>
                    <td className={cn(numCell, 'w-36')}>
                      {canManage ? (
                        <Input type="number" step="0.01" min="0" className="h-8 text-right" value={e?.costToComplete ?? r.costToComplete} onChange={(ev) => edit(r, { costToComplete: ev.target.value })} />
                      ) : money(r.costToComplete)}
                    </td>
                    <td className={cn(tdClass, 'w-44')}>
                      {canManage ? (
                        <select className={cn(selectClass, 'h-8')} value={e?.fulfilmentClass ?? r.fulfilmentClass} onChange={(ev) => edit(r, { fulfilmentClass: ev.target.value as FulfilmentClass })}>
                          {FULFILMENT_CLASSES.map((c) => <option key={c} value={c}>{FULFILMENT_LABEL[c]}</option>)}
                        </select>
                      ) : FULFILMENT_LABEL[r.fulfilmentClass]}
                    </td>
                    <td className={cn(tdClass, 'text-muted-foreground')}>{r.asOf ? shortDate(r.asOf) : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {canManage && project.wbs.length > 0 && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <WbsSelect wbs={project.wbs} value={adding.wbsId} onChange={(v) => setAdding({ ...adding, wbsId: v })} className="w-64" />
          <CostElementSelect value={adding.costElement} onChange={(v) => setAdding({ ...adding, costElement: v })} className="w-40" />
          <Button
            variant="outline"
            size="sm"
            disabled={!adding.wbsId || rows.some((r) => r.key === `${adding.wbsId}:${adding.costElement}`)}
            onClick={() => { setExtra([...extra, adding]); setAdding({ ...adding, wbsId: '' }); }}
          >
            <Plus className="mr-1.5 h-4 w-4" />{tx('Add line')}
          </Button>
        </div>
      )}
    </Section>
  );
}
