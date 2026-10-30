import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { MapPinned, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { SITE_STATUS_LABEL, useQuarryLookups, useSaveRow, useSites, type Site } from '@/features/quarry/api';
import { FormDialog, toOptions, tonnes, type FieldSpec } from './quarry-ui';

export function siteFields(lookups?: ReturnType<typeof useQuarryLookups>['data']): FieldSpec[] {
  return [
    { name: 'code', label: tx('Code'), hint: tx('Automatic when blank') },
    { name: 'name', label: tx('Name'), required: true },
    { name: 'status', label: tx('Status'), type: 'select', required: true, options: toOptions(SITE_STATUS_LABEL) },
    { name: 'branchId', label: tx('Branch'), type: 'select', options: (lookups?.branches ?? []).map((b) => ({ value: b.id, label: `${b.code} · ${b.name}` })) },
    { name: 'costCenterId', label: tx('Cost centre'), type: 'select', options: (lookups?.costCenters ?? []).map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` })) },
    { name: 'areaHectares', label: tx('Area (ha)'), type: 'number', min: 0 },
    { name: 'licenceNumber', label: tx('Mining licence number') },
    { name: 'licenceAuthority', label: tx('Licensing authority') },
    { name: 'licenceExpiresOn', label: tx('Licence expires on'), type: 'date' },
    { name: 'latitude', label: tx('Latitude'), type: 'number' },
    { name: 'longitude', label: tx('Longitude'), type: 'number' },
    { name: 'address', label: tx('Address / location'), wide: true },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
}

const TONE: Record<string, 'success' | 'warning' | 'neutral'> = { active: 'success', suspended: 'warning', closed: 'neutral' };

/** Quarry sites (2.2): list with status filter, search and pagination; register a site. */
export function SitesPage() {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.quarrySite.create));
  const list = useListState({ status: '' });
  const lookups = useQuarryLookups();
  const save = useSaveRow('/quarry/sites');
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isError } = useSites({ page: list.page, pageSize: list.pageSize, search: list.search, status: list.filters.status });
  const columns: Column<Site>[] = [
    { key: 'code', header: tx('Code'), render: (s) => <span className="font-medium text-primary">{s.code}</span> },
    { key: 'name', header: tx('Name'), render: (s) => s.name },
    { key: 'status', header: tx('Status'), render: (s) => <StatusPill tone={TONE[s.status] ?? 'neutral'}>{SITE_STATUS_LABEL[s.status] ?? s.status}</StatusPill> },
    { key: 'licence', header: tx('Licence'), render: (s) => (s.licenceNumber ? `${s.licenceNumber}${s.licenceExpiresOn ? ` · ${date(s.licenceExpiresOn)}` : ''}` : '—') },
    { key: 'zones', header: tx('Zones'), className: 'text-right', render: (s) => s.zoneCount ?? 0 },
    { key: 'month', header: tx('This month'), className: 'text-right', render: (s) => tonnes(s.monthTonnes) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={MapPinned} title={tx('Quarry Sites')} description={tx('Quarries and extraction sites with their mining licences.')}
        actions={canCreate && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add site')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search code, name, licence…')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(SITE_STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(s) => s.id}
          onRowClick={(s) => navigate(`/quarry/sites/${s.id}`)} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the sites. Try again.') : tx('No quarry sites yet.')} />
      </ListCard>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="site" />
      )}
      {creating && (
        <FormDialog title={tx('Add quarry site')} fields={siteFields(lookups.data)} defaults={{ status: 'active' }} saving={save.isPending}
          onSave={async (body) => {
            const s = (await save.mutateAsync({ body })) as Site;
            navigate(`/quarry/sites/${s.id}`);
          }}
          onClose={() => setCreating(false)} />
      )}
    </div>
  );
}
