import { useNavigate } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState,
} from '@/components/list';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DOC_TYPE_LABEL, useComplianceDue, useFleetLookups, type ComplianceDocument,
} from '@/features/fleet/api';
import { ComplianceStatePill } from './fleet-ui';
import { toOptions } from './fleet-format';

const FILTERS = { due: '', docType: '', branchId: '' };

const DUE_TABS: Array<{ value: string; label: string }> = [
  { value: '', label: tx('Needs attention') },
  { value: 'expired', label: tx('Expired') },
  { value: 'expiring', label: tx('Expiring soon') },
  { value: 'all', label: tx('All documents') },
];

/** Fleet-wide renewals: expired and expiring registrations, insurance, licences, inspections, permits (fleet 1.1). */
export function FleetCompliancePage() {
  const navigate = useNavigate();
  const list = useListState(FILTERS);
  const { due, docType, branchId } = list.filters;
  const lookups = useFleetLookups();
  const branches = lookups.data?.branches ?? [];
  const { data, isLoading, isFetching, isError } = useComplianceDue({
    page: list.page, pageSize: list.pageSize, due: due || undefined, docType: docType || undefined, branchId: branchId || undefined,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const columns: Column<ComplianceDocument>[] = [
    {
      key: 'vehicle', header: tx('Vehicle'),
      render: (d) => (
        <div>
          <div className="font-medium text-primary">{d.vehicle?.code}</div>
          <div className="text-xs text-muted-foreground">{[d.vehicle?.registrationPlate, d.vehicle?.name].filter(Boolean).join(' · ')}</div>
        </div>
      ),
    },
    { key: 'docType', header: tx('Document'), render: (d) => DOC_TYPE_LABEL[d.docType] },
    { key: 'documentNumber', header: tx('Number'), render: (d) => d.documentNumber ?? '—' },
    { key: 'issuer', header: tx('Issuer'), render: (d) => d.issuer ?? '—' },
    { key: 'expiresOn', header: tx('Expires'), render: (d) => (d.expiresOn ? date(d.expiresOn) : '—') },
    {
      key: 'days', header: tx('Days'), className: 'text-right',
      render: (d) => <span className={d.daysToExpiry !== null && d.daysToExpiry < 0 ? 'tabular-nums text-rose-600' : 'tabular-nums'}>{d.daysToExpiry ?? '—'}</span>,
    },
    { key: 'state', header: tx('Status'), render: (d) => <ComplianceStatePill state={d.state} /> },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShieldAlert} title={tx('Fleet compliance')}
        description={tx('Registrations, insurance, road licences, inspections and permits that have expired or expire within their reminder lead time.')} />

      <div className="inline-flex flex-wrap rounded-lg border bg-muted/40 p-0.5" role="tablist">
        {DUE_TABS.map((t) => (
          <button key={t.value || 'attention'} type="button" role="tab" aria-selected={due === t.value}
            onClick={() => list.setFilter('due', t.value)}
            className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${due === t.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
            {t.label}
          </button>
        ))}
      </div>

      <ListToolbar>
        <FilterSelect value={docType} onChange={(v) => list.setFilter('docType', v)} options={toOptions(DOC_TYPE_LABEL)} allLabel={tx('All document types')} />
        {branches.length > 0 && (
          <FilterSelect value={branchId} onChange={(v) => list.setFilter('branchId', v)} options={branches.map((b) => ({ value: b.id, label: b.name }))} allLabel={tx('All branches')} />
        )}
      </ListToolbar>

      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={rows} loading={isLoading} getRowId={(d) => d.id}
            onRowClick={(d) => navigate(`/fleet/vehicles/${d.vehicleId}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the documents.') : tx('Nothing to renew. All documents are in order.')} />
        </div>
        {meta && (
          <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="document" />
        )}
      </ListCard>
    </div>
  );
}
