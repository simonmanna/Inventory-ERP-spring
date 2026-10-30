import { useNavigate } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState } from '@/components/list';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { ISSUE_STATUS_LABEL, useIssues, type ExplosiveIssue } from '@/features/quarry/api';
import { IssueStatusPill, toOptions } from './quarry-ui';

/** Explosive issues: requests waiting for approval, approved ones to issue, issued ones to reconcile. */
export function ExplosiveIssuesPage() {
  const navigate = useNavigate();
  const list = useListState({ status: '' });
  const { data, isLoading, isError } = useIssues({ page: list.page, pageSize: list.pageSize, search: list.search, status: list.filters.status });
  const columns: Column<ExplosiveIssue>[] = [
    { key: 'code', header: tx('Issue'), render: (i) => <span className="font-medium text-primary">{i.code}</span> },
    { key: 'blast', header: tx('Blast'), render: (i) => `${i.blastCode} · ${i.siteCode}` },
    { key: 'magazine', header: tx('Magazine'), render: (i) => i.magazineCode },
    { key: 'requested', header: tx('Requested'), render: (i) => dateTime(i.requestedAt) },
    { key: 'value', header: tx('Issued value'), className: 'text-right', render: (i) => money(Number(i.issuedValue) - Number(i.returnedValue)) },
    { key: 'status', header: tx('Status'), render: (i) => <IssueStatusPill status={i.status} /> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShieldAlert} title={tx('Explosive Issues')} description={tx('Controlled stock: requested, approved in the approval inbox, issued by the magazine keeper, reconciled after the blast.')} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search issue or blast…')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(ISSUE_STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(i) => i.id} onRowClick={(i) => navigate(`/quarry/explosive-issues/${i.id}`)}
          cellClassName="py-2.5 px-4" emptyMessage={isError ? tx('Could not load the explosive issues. Try again.') : tx('No explosive issues yet.')} />
      </ListCard>
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
        onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="issue" />}
    </div>
  );
}
