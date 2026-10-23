import { Link, useNavigate } from 'react-router-dom';
import { Eye, Plus, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, ExportMenu, FilterChips, FilterSelect, ListCard,
  ListPageHeader, ListToolbar, SearchInput, StatusPill, dateRangeLabel, describeFilters,
  useListState, type ActiveChip, type Tone,
} from '@/components/list';
import { api } from '@/lib/api';
import { fetchAllPages, type ExportColumn } from '@/lib/export-list';
import { money, date, useOrgCurrency } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import {
  CHANNEL_LABEL, DISPOSITION_LABEL, useReturns, type ReturnsPage as ReturnsResult, type SalesReturn,
} from '@/features/returns/api';

const dispositionTone: Record<string, Tone> = { restock: 'success', waste: 'warning', no_return: 'neutral' };

const toOptions = (m: Record<string, string>) => Object.entries(m).map(([value, label]) => ({ value, label }));

const FILTERS = { channel: '', stockDisposition: '', dateFrom: '', dateTo: '' };

const CHANNEL_TABS: Array<{ value: string; label: string }> = [
  { value: '', label: 'All returns' },
  { value: 'retail', label: CHANNEL_LABEL.retail },
  { value: 'restaurant', label: CHANNEL_LABEL.restaurant },
];

export function ReturnsPage() {
  const navigate = useNavigate();
  const currency = useOrgCurrency();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.pos.refund));
  const list = useListState(FILTERS);
  const { channel, stockDisposition, dateFrom, dateTo } = list.filters;

  const query = {
    search: list.search || undefined,
    channel: channel || undefined,
    stockDisposition: stockDisposition || undefined,
    dateFrom: dateFrom || undefined,
    dateTo: dateTo || undefined,
  };
  const { data, isLoading, isFetching } = useReturns({ page: list.page, pageSize: list.pageSize, ...query });
  const rows = data?.data ?? [];
  const meta = data?.meta;
  const summary = data?.summary;

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(channel ? [{ key: 'channel', label: CHANNEL_LABEL[channel as keyof typeof CHANNEL_LABEL] ?? channel, onRemove: () => list.setFilter('channel', '') }] : []),
    ...(stockDisposition ? [{ key: 'disp', label: DISPOSITION_LABEL[stockDisposition as keyof typeof DISPOSITION_LABEL] ?? stockDisposition, onRemove: () => list.setFilter('stockDisposition', '') }] : []),
    ...(dateFrom || dateTo ? [{ key: 'date', label: dateRangeLabel(dateFrom, dateTo), onRemove: () => list.setFilters({ dateFrom: '', dateTo: '' }) }] : []),
  ];

  const columns: Column<SalesReturn>[] = [
    { key: 'returnNumber', header: 'Return #', render: (r) => <span className="font-medium text-primary">{r.returnNumber}</span> },
    { key: 'createdAt', header: 'Date', render: (r) => <span className="whitespace-nowrap text-muted-foreground">{date(r.createdAt)}</span> },
    {
      key: 'invoiceNumber',
      header: 'Original sale',
      render: (r) => (
        <Link to={`/pos/receipts/${r.invoiceId}`} onClick={(e) => e.stopPropagation()} className="hover:underline">
          {r.invoiceNumber}
        </Link>
      ),
    },
    { key: 'partner', header: 'Customer', render: (r) => r.partner?.name ?? <span className="text-muted-foreground">{tx('Walk-in')}</span> },
    {
      key: 'channel',
      header: 'Channel',
      render: (r) => <span className="rounded-md bg-muted px-1.5 py-0.5 text-xs font-medium">{CHANNEL_LABEL[r.channel]}</span>,
    },
    { key: 'reason', header: 'Reason', render: (r) => <span className="line-clamp-1 max-w-[16rem] text-muted-foreground">{r.reason}</span> },
    {
      key: 'stockDisposition',
      header: 'Goods',
      render: (r) => <StatusPill tone={dispositionTone[r.stockDisposition] ?? 'neutral'}>{DISPOSITION_LABEL[r.stockDisposition]}</StatusPill>,
    },
    { key: 'amount', header: 'Amount', className: 'text-right', render: (r) => <span className="font-semibold tabular-nums text-rose-600">{money(r.amount, currency)}</span> },
    {
      key: 'actions',
      header: '',
      className: 'w-12 text-right',
      render: (r) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => navigate(`/returns/${r.id}`)} aria-label={tx('View return')}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];

  const exportColumns: ExportColumn<SalesReturn>[] = [
    { header: 'Return #', value: (r) => r.returnNumber },
    { header: 'Date', value: (r) => date(r.createdAt) },
    { header: 'Original sale', value: (r) => r.invoiceNumber },
    { header: 'Customer', value: (r) => r.partner?.name ?? '' },
    { header: 'Channel', value: (r) => CHANNEL_LABEL[r.channel] },
    { header: 'Reason', value: (r) => r.reason },
    { header: 'Goods', value: (r) => DISPOSITION_LABEL[r.stockDisposition] },
    { header: 'Approved by', value: (r) => r.approvedByName ?? '' },
    { header: 'Amount', value: (r) => money(r.amount, currency), align: 'right' },
  ];

  const fetchAll = () =>
    fetchAllPages<SalesReturn>(async (page, pageSize) => {
      const res = (await api.get<ReturnsResult>('/returns', { params: { ...query, page, pageSize } })).data;
      return { rows: res.data, totalPages: res.meta.totalPages };
    }, { pageSize: 100 });

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={Undo2}
        title={tx('Sales Returns')}
        description={tx('Goods and meals returned against retail and café/restaurant sales.')}
        actions={
          <>
            <ExportMenu
              basename="sales-returns"
              title={tx('Sales Returns')}
              subtitle={describeFilters(chips)}
              columns={exportColumns}
              pageRows={rows}
              total={meta?.total}
              fetchAll={fetchAll}
              totals={(r) => ['Total', '', '', '', '', '', '', '', money(r.reduce((s, x) => s + Number(x.amount || 0), 0), currency)]}
            />
            {canCreate && (
              <Button onClick={() => navigate('/returns/new')}>
                <Plus className="mr-1.5 h-4 w-4" /> {tx('New return')}
              </Button>
            )}
          </>
        }
      />

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="inline-flex rounded-lg border bg-muted/40 p-0.5" role="tablist">
          {CHANNEL_TABS.map((t) => (
            <button
              key={t.value || 'all'}
              type="button"
              role="tab"
              aria-selected={channel === t.value}
              onClick={() => list.setFilter('channel', t.value)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                channel === t.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              {tx(t.label)}
            </button>
          ))}
        </div>
        {summary && (
          <div className="text-sm text-muted-foreground">
            <span className="tabular-nums font-medium text-foreground">{summary.count}</span> {Number(summary.count) === 1 ? tx('return') : tx('returns')}
            {' · '}
            <span className="tabular-nums font-semibold text-rose-600">{money(summary.amount, currency)}</span> {tx('refunded')}
          </div>
        )}
      </div>

      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search sale #, customer or reason…')} />
        <FilterSelect value={stockDisposition} onChange={(v) => list.setFilter('stockDisposition', v)} options={toOptions(DISPOSITION_LABEL)} allLabel="All goods outcomes" />
        <DateRangeFilter from={dateFrom} to={dateTo} onChange={(f, t) => list.setFilters({ dateFrom: f, dateTo: t })} />
      </ListToolbar>

      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable
            columns={columns}
            data={rows}
            loading={isLoading}
            loadingRows={10}
            getRowId={(r) => r.id}
            onRowClick={(r) => navigate(`/returns/${r.id}`)}
            cellClassName="py-2.5 px-4"
            headerRowClassName="h-10"
            emptyMessage={chips.length ? 'No returns match these filters.' : 'No returns recorded yet.'}
          />
        </div>
        {meta && (
          <DataTablePagination
            page={meta.page}
            pageSize={list.pageSize}
            total={meta.total}
            totalPages={meta.totalPages}
            onPageChange={list.setPage}
            onPageSizeChange={list.setPageSize}
            noun="return"
          />
        )}
      </ListCard>
    </div>
  );
}
