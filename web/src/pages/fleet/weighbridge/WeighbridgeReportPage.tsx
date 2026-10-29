import { BarChart3 } from 'lucide-react';
import { DataTable, type Column } from '@/components/data-table';
import { DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import {
  GROUP_LABEL, SUBJECT_LABEL, useDiscrepancyReport, useWeighbridgeLookups, type DiscrepancyRow,
} from '@/features/fleet/weighbridge-api';
import { toOptions } from '../fleet-format';
import { kg } from './weighbridge-format';

/** Weighbridge discrepancy report (fleet 1.4): expected vs net by product / customer / supplier / station / period. */
export function WeighbridgeReportPage() {
  const list = useListState({ groupBy: 'product', stationId: '', subjectType: '', from: '', to: '' });
  const { groupBy, stationId, subjectType, from, to } = list.filters;
  const lookups = useWeighbridgeLookups();
  const report = useDiscrepancyReport({
    groupBy: groupBy || 'product', stationId: stationId || undefined, subjectType: subjectType || undefined,
    from: from || undefined, to: to || undefined,
  });
  const label = (r: DiscrepancyRow) => (groupBy === 'subject' ? SUBJECT_LABEL[r.label as keyof typeof SUBJECT_LABEL] ?? r.label : r.label);
  const columns: Column<DiscrepancyRow>[] = [
    { key: 'label', header: GROUP_LABEL[groupBy || 'product'] ?? tx('Group'), render: (r) => <span className="font-medium">{label(r)}</span> },
    { key: 'tickets', header: tx('Tickets'), className: 'text-right', render: (r) => `${r.tickets} (${r.compared})` },
    { key: 'expected', header: tx('Expected'), className: 'text-right', render: (r) => <span className="tabular-nums">{kg(r.expectedKg)}</span> },
    { key: 'net', header: tx('Net'), className: 'text-right', render: (r) => <span className="tabular-nums">{kg(r.netKg)}</span> },
    { key: 'variance', header: tx('Variance'), className: 'text-right', render: (r) => <span className={`tabular-nums ${Number(r.varianceKg) < 0 ? 'text-rose-600' : ''}`}>{kg(r.varianceKg)}</span> },
    { key: 'pct', header: '%', className: 'text-right', render: (r) => (r.variancePct ?? '—') },
    { key: 'abs', header: tx('Absolute variance'), className: 'text-right', render: (r) => <span className="tabular-nums">{kg(r.absVarianceKg)}</span> },
    { key: 'out', header: tx('Out of tolerance'), className: 'text-right', render: (r) => r.outOfTolerance },
    { key: 'manual', header: tx('Manual'), className: 'text-right', render: (r) => r.manual },
    { key: 'tare', header: tx('Stored tare'), className: 'text-right', render: (r) => r.storedTare },
  ];
  const rows = report.data ? [...report.data.groups, report.data.totals] : [];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('Weighbridge Discrepancies')}
        description={tx('Completed tickets: expected weight (ordered quantity) against net weight. Tickets in brackets had an expected weight.')} />
      <ListToolbar>
        <FilterSelect value={groupBy} onChange={(v) => list.setFilter('groupBy', v || 'product')} options={toOptions(GROUP_LABEL)} allLabel={GROUP_LABEL.product} />
        <FilterSelect value={stationId} onChange={(v) => list.setFilter('stationId', v)} options={(lookups.data?.stations ?? []).map((s) => ({ value: s.id, label: s.code }))} allLabel={tx('All stations')} />
        <FilterSelect value={subjectType} onChange={(v) => list.setFilter('subjectType', v)} options={toOptions(SUBJECT_LABEL)} allLabel={tx('All subjects')} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={rows} loading={report.isLoading} getRowId={(r) => `${r.key ?? 'total'}-${r.label}`}
          emptyMessage={report.isError ? tx('Could not load the report.') : tx('No completed tickets in this period.')} />
      </ListCard>
    </div>
  );
}
