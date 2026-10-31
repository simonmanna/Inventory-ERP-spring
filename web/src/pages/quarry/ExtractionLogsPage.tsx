import { useNavigate } from 'react-router-dom';
import { ClipboardList, Eye, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { LOG_STATUS_LABEL, SHIFT_LABEL, useExtractionLogs, useQuarryLookups, type ExtractionLog } from '@/features/quarry/api';
import { LogStatusPill, toOptions, tonnes } from './quarry-ui';

/** Daily extraction logs: filters by site, material, status, shift and date; phone cards on small screens. */
export function ExtractionLogsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ siteId: '', materialId: '', status: '', shift: '', from: '', to: '' });
  const f = list.filters;
  const lookups = useQuarryLookups();
  const { data, isLoading, isFetching, isError } = useExtractionLogs({
    page: list.page, pageSize: list.pageSize, search: list.search, siteId: f.siteId, materialId: f.materialId, status: f.status,
    shift: f.shift, dateFrom: f.from, dateTo: f.to, sortBy: list.sort?.by, sortOrder: list.sort?.order,
  });
  const rows = data?.data ?? [];
  const open = (l: ExtractionLog) => navigate(`/quarry/extraction/${l.id}`);
  const columns: Column<ExtractionLog>[] = [
    { key: 'extractedOn', header: tx('Date'), sortKey: 'extractedOn', render: (l) => <span className="whitespace-nowrap">{date(l.extractedOn)} · {SHIFT_LABEL[l.shift]}</span> },
    { key: 'code', header: tx('Log'), sortKey: 'code', render: (l) => <span className="font-medium text-primary">{l.code}</span> },
    { key: 'site', header: tx('Site / zone'), sortKey: 'site', render: (l) => [l.siteCode, l.zoneCode].filter(Boolean).join(' · ') },
    { key: 'material', header: tx('Material'), render: (l) => l.materialName },
    { key: 'gross', header: tx('Gross'), className: 'text-right', render: (l) => tonnes(l.grossTonnes) },
    { key: 'netTonnes', header: tx('Net'), sortKey: 'netTonnes', className: 'text-right', render: (l) => tonnes(l.netTonnes) },
    { key: 'totalCost', header: tx('Cost'), sortKey: 'totalCost', className: 'text-right', render: (l) => (l.status === 'draft' ? '—' : money(l.totalCost)) },
    { key: 'unit', header: tx('Per tonne'), className: 'text-right', render: (l) => (l.unitCost ? money(l.unitCost) : '—') },
    { key: 'status', header: tx('Status'), render: (l) => <LogStatusPill status={l.status} /> },
    { key: 'view', header: '', className: 'w-12', render: (l) => (
      <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View extraction log')} onClick={() => open(l)}><Eye className="h-4 w-4" /></Button>
    ) },
  ];
  const empty = isError ? tx('Could not load the extraction logs. Try again.') : tx('No extraction logged yet.');
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ClipboardList} title={tx('Extraction Logs')} description={tx('Daily extraction by shift, zone, operator and equipment, posted into ROM stock at cost.')}
        actions={has(PERMISSIONS.quarryExtraction.create) && <Button onClick={() => navigate('/quarry/extraction/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New extraction log')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search log, site, zone…')} />
        <FilterSelect value={f.siteId} onChange={(v) => list.setFilter('siteId', v)} options={(lookups.data?.sites ?? []).map((s) => ({ value: s.id, label: s.code }))} allLabel={tx('All sites')} />
        <FilterSelect value={f.materialId} onChange={(v) => list.setFilter('materialId', v)} options={(lookups.data?.materials ?? []).map((m) => ({ value: m.id, label: m.name }))} allLabel={tx('All materials')} />
        <FilterSelect value={f.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(LOG_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={f.shift} onChange={(v) => list.setFilter('shift', v)} options={toOptions(SHIFT_LABEL)} allLabel={tx('All shifts')} />
        <DateRangeFilter from={f.from} to={f.to} onChange={(a, b) => list.setFilters({ from: a, to: b })} />
      </ListToolbar>
      {data?.summary && (
        <p className="text-sm text-muted-foreground">
          {tx('Posted in this view: {{tonnes}} · {{cost}} · {{perTonne}} per tonne', {
            tonnes: tonnes(data.summary.netTonnes), cost: money(data.summary.totalCost), perTonne: data.summary.costPerTonne ? money(data.summary.costPerTonne) : '—',
          })}
        </p>
      )}
      <div className="space-y-2 sm:hidden">
        {!isLoading && rows.length === 0 && <div className="rounded-xl border bg-card p-4 text-center text-sm text-muted-foreground">{empty}</div>}
        {rows.map((l) => (
          <button key={l.id} type="button" onClick={() => open(l)} className="w-full rounded-xl border bg-card p-3 text-left shadow-sm active:bg-accent">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{l.siteCode} · {tonnes(l.netTonnes)}</span>
              <LogStatusPill status={l.status} />
            </div>
            <div className="mt-1 text-xs text-muted-foreground">{date(l.extractedOn)} · {SHIFT_LABEL[l.shift]} · {l.code} · {l.materialName}</div>
          </button>
        ))}
      </div>
      <div className="hidden sm:block">
        <ListCard>
          <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
            <DataTable columns={columns} data={rows} loading={isLoading} getRowId={(l) => l.id} onRowClick={open} sort={list.sort}
              onSortChange={list.setSort} cellClassName="py-2.5 px-4" emptyMessage={empty} />
          </div>
        </ListCard>
      </div>
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
        onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="extraction log" />}
    </div>
  );
}
