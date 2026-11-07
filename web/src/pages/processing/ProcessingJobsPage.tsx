import { useNavigate } from 'react-router-dom';
import { Plus, Workflow } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { JOB_STATUS_LABEL, useJobs, useTemplates, type JobSummary } from '@/features/processing/api';
import { JobStatusPill, qty, toOptions } from './processing-ui';

const lines = (rows?: Array<{ productName: string; batchNumber: string | null }>) => (rows ?? []).map((r) => (r.batchNumber ? `${r.productName} · ${r.batchNumber}` : r.productName));

/** Every processing step: what went in, what came out, and where it stands. */
export function ProcessingJobsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ status: '', templateId: '' });
  const f = list.filters;
  const templates = useTemplates();
  const { data, isLoading, isFetching, isError } = useJobs({ page: list.page, pageSize: list.pageSize, search: list.search, status: f.status, templateId: f.templateId });
  const rows = data?.data ?? [];
  const open = (r: JobSummary) => navigate(`/manufacturing/processing/jobs/${r.id}`);
  const columns: Column<JobSummary>[] = [
    { key: 'code', header: tx('Job'), render: (r) => <div><div className="whitespace-nowrap font-medium text-primary">{r.code}</div><div className="text-xs text-muted-foreground">{date(r.jobDate)}</div></div> },
    { key: 'step', header: tx('Step'), render: (r) => r.templateName },
    { key: 'in', header: tx('Input'), render: (r) => <div className="max-w-56 space-y-0.5 text-xs">{lines(r.inputSummary).map((l) => <div key={l} className="truncate" title={l}>{l}</div>)}</div> },
    { key: 'out', header: tx('Output'), render: (r) => <div className="max-w-56 space-y-0.5 text-xs">{lines(r.outputSummary).map((l) => <div key={l} className="truncate" title={l}>{l}</div>)}</div> },
    { key: 'progress', header: tx('Processed'), className: 'w-40', render: (r) => {
      const input = Number(r.inputBasis ?? 0);
      const done = Number(r.outputBasis ?? 0) + Number(r.lossBasis ?? 0);
      const pct = input > 0 ? Math.min(100, Math.round((done / input) * 100)) : 0;
      return (
        <div>
          <div className="flex justify-between text-xs text-muted-foreground"><span>{qty(done)} / {qty(input)} {r.yieldBasisUnit}</span><span>{pct}%</span></div>
          <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted"><div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} /></div>
        </div>
      );
    } },
    { key: 'wip', header: tx('In process'), className: 'text-right', render: (r) => (Number(r.wipValue ?? 0) ? money(r.wipValue) : '—') },
    { key: 'status', header: tx('Status'), render: (r) => <JobStatusPill status={r.status} /> },
  ];
  const empty = isError ? tx('Could not load the jobs. Try again.') : tx('No processing job yet. Start one from a lot, or with New job.');
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Workflow} title={tx('Processing Jobs')} description={tx('One job is one step: cut, polish, modify. Its output can be sold, or processed further.')}
        actions={has(PERMISSIONS.processing.post) && <Button onClick={() => navigate('/manufacturing/processing/jobs/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New job')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search job, step or lot…')} />
        <FilterSelect value={f.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(JOB_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={f.templateId} onChange={(v) => list.setFilter('templateId', v)} options={(templates.data ?? []).map((t) => ({ value: t.id, label: `${t.name} v${t.version}` }))} allLabel={tx('All steps')} />
      </ListToolbar>
      <div className="space-y-2 sm:hidden">
        {!isLoading && rows.length === 0 && <div className="rounded-xl border bg-card p-4 text-center text-sm text-muted-foreground">{empty}</div>}
        {rows.map((r) => (
          <button key={r.id} type="button" onClick={() => open(r)} className="w-full rounded-xl border bg-card p-3 text-left shadow-sm active:bg-accent">
            <div className="flex items-center justify-between gap-2"><span className="font-medium">{r.templateName}</span><JobStatusPill status={r.status} /></div>
            <div className="mt-1 text-xs text-muted-foreground">{r.code} · {date(r.jobDate)}</div>
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
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="job" />}
    </div>
  );
}
