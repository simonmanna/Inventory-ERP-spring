import { useNavigate } from 'react-router-dom';
import { Eye, Factory, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { RUN_STATUS_LABEL, SHIFT_LABEL, useCrushingLookups, useRuns, type Run } from '@/features/crushing/api';
import { RunStatusPill, pct, toOptions, tonnes } from './crushing-ui';

/** Crushing runs by shift: filters by plant, status, shift and date; phone cards on small screens. */
export function CrushingRunsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ plantId: '', status: '', shift: '', from: '', to: '' });
  const f = list.filters;
  const lookups = useCrushingLookups();
  const { data, isLoading, isFetching, isError } = useRuns({
    page: list.page, pageSize: list.pageSize, search: list.search, plantId: f.plantId, status: f.status, shift: f.shift, from: f.from, to: f.to,
  });
  const rows = data?.data ?? [];
  const open = (r: Run) => navigate(`/manufacturing/crushing/runs/${r.id}`);
  const columns: Column<Run>[] = [
    { key: 'runDate', header: tx('Date'), render: (r) => <span className="whitespace-nowrap">{date(r.runDate)} · {SHIFT_LABEL[r.shift]}</span> },
    { key: 'code', header: tx('Run'), render: (r) => <span className="font-medium text-primary">{r.code}</span> },
    { key: 'plant', header: tx('Plant'), render: (r) => r.plantCode },
    { key: 'feed', header: tx('Feed'), className: 'text-right', render: (r) => tonnes(r.feedTonnes) },
    { key: 'output', header: tx('Output'), className: 'text-right', render: (r) => tonnes(r.outputTonnes) },
    { key: 'yield', header: tx('Yield'), className: 'text-right', render: (r) => pct(r.actualYieldPct) },
    { key: 'cost', header: tx('Per tonne'), className: 'text-right', render: (r) => (r.costPerTonne ? money(r.costPerTonne) : '—') },
    { key: 'status', header: tx('Status'), render: (r) => <RunStatusPill status={r.status} /> },
    { key: 'view', header: '', className: 'w-12', render: (r) => (
      <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View crushing run')} onClick={() => open(r)}><Eye className="h-4 w-4" /></Button>
    ) },
  ];
  const empty = isError ? tx('Could not load the crushing runs. Try again.') : tx('No crushing run recorded yet.');
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Factory} title={tx('Crushing Runs')} description={tx('Feed tonnes in, sized products out into stockpiles, by plant and shift.')}
        actions={has(PERMISSIONS.crushingRun.record) && <Button onClick={() => navigate('/manufacturing/crushing/runs/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New crushing run')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search run or operator…')} />
        <FilterSelect value={f.plantId} onChange={(v) => list.setFilter('plantId', v)} options={(lookups.data?.plants ?? []).map((p) => ({ value: p.id, label: p.code }))} allLabel={tx('All plants')} />
        <FilterSelect value={f.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(RUN_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={f.shift} onChange={(v) => list.setFilter('shift', v)} options={toOptions(SHIFT_LABEL)} allLabel={tx('All shifts')} />
        <DateRangeFilter from={f.from} to={f.to} onChange={(a, b) => list.setFilters({ from: a, to: b })} />
      </ListToolbar>
      <div className="space-y-2 sm:hidden">
        {!isLoading && rows.length === 0 && <div className="rounded-xl border bg-card p-4 text-center text-sm text-muted-foreground">{empty}</div>}
        {rows.map((r) => (
          <button key={r.id} type="button" onClick={() => open(r)} className="w-full rounded-xl border bg-card p-3 text-left shadow-sm active:bg-accent">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{r.plantCode} · {tonnes(r.feedTonnes)}</span>
              <RunStatusPill status={r.status} />
            </div>
            <div className="mt-1 text-xs text-muted-foreground">{date(r.runDate)} · {SHIFT_LABEL[r.shift]} · {r.code}</div>
          </button>
        ))}
      </div>
      <div className="hidden sm:block">
        <ListCard>
          <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
            <DataTable columns={columns} data={rows} loading={isLoading} getRowId={(r) => r.id} onRowClick={open} cellClassName="py-2.5 px-4" emptyMessage={empty} />
          </div>
        </ListCard>
      </div>
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
        onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="crushing run" />}
    </div>
  );
}
