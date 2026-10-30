import { useNavigate } from 'react-router-dom';
import { Building2 } from 'lucide-react';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, useListState,
} from '@/components/list';
import { SUPPLIER_STATUS_TONE, useSuppliers, type SupplierRow } from '@/features/procurement/api';
import { tx } from '@/lib/i18n/tx';

export const SUPPLIER_STATUS_LABEL: Record<string, string> = {
  prospect: tx('Prospect'),
  provisional: tx('Provisional'),
  approved: tx('Approved'),
  suspended: tx('Suspended'),
  blacklisted: tx('Blacklisted'),
  unqualified: tx('Not qualified'),
};

/** Supplier register (ADR-PROCUREMENT-001): every supplier partner with its qualification status. */
export function SuppliersPage() {
  const navigate = useNavigate();
  const list = useListState({ status: '' });
  const { data, isLoading } = useSuppliers({
    search: list.search || undefined,
    status: list.filters.status || undefined,
    page: list.page,
    pageSize: list.pageSize,
  });

  const columns: Column<SupplierRow>[] = [
    { key: 'name', header: tx('Supplier'), render: (r) => <div><div className="font-medium">{r.name}</div><div className="text-xs text-muted-foreground">{r.code}</div></div> },
    { key: 'status', header: tx('Status'), render: (r) => (
      <StatusPill tone={SUPPLIER_STATUS_TONE[r.lifecycleStatus ?? 'none']}>
        {SUPPLIER_STATUS_LABEL[r.lifecycleStatus ?? 'unqualified']}
      </StatusPill>
    ) },
    { key: 'risk', header: tx('Risk'), render: (r) => r.riskClass ?? '—' },
    { key: 'tin', header: tx('TIN'), render: (r) => r.taxNumber ?? '—' },
    { key: 'contact', header: tx('Contact'), render: (r) => [r.phone, r.email].filter(Boolean).join(' · ') || '—' },
    { key: 'lead', header: tx('Lead time'), render: (r) => (r.leadTimeDays != null ? tx('{{n}} days', { n: r.leadTimeDays }) : '—') },
    { key: 'certs', header: tx('Certificates'), render: (r) => (Number(r.certificatesExpiring) > 0
      ? <StatusPill tone="warning">{tx('{{n}} expiring', { n: r.certificatesExpiring })}</StatusPill> : '—') },
  ];

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader
        title={tx('Suppliers')}
        description={tx('Qualify suppliers before you buy: approval status, bank details, certificates and price history.')}
        icon={Building2}
      />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search name, code, TIN, email…')} />
        <FilterSelect
          value={list.filters.status}
          onChange={(v) => list.setFilter('status', v)}
          allLabel={tx('All statuses')}
          options={Object.entries(SUPPLIER_STATUS_LABEL).map(([value, label]) => ({ value, label }))}
        />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} onRowClick={(r) => navigate(`/procurement/suppliers/${r.id}`)} />
        {data && (
          <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total}
            totalPages={data.meta.totalPages} onPageChange={list.setPage} noun={tx('supplier')} />
        )}
      </ListCard>
    </div>
  );
}
