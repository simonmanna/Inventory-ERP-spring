import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Beaker, Eye, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
  type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { MIX_STATUS_LABEL, useMixDesigns, type MixDesign } from '@/features/manufacturing/api-precast';
import type { BomStatus } from '@/features/manufacturing/types';
import { MixStatusPill } from './precast-ui';
import { MixDesignFormDialog } from './MixDesignForm';
import { num, toOptions } from './precast-format';

const FILTERS = { status: '' };

/** Mix designs (2.4): concrete block recipes per batch, backed by a versioned BOM. */
export function MixDesignsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { status } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useMixDesigns({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: MIX_STATUS_LABEL[status as BomStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
  ];
  const columns: Column<MixDesign>[] = [
    { key: 'code', header: tx('Code'), render: (m) => <span className="font-mono text-xs text-primary">{m.bomCode}</span> },
    { key: 'name', header: tx('Name'), render: (m) => <span className="font-medium">{m.name}</span> },
    { key: 'product', header: tx('Block product'), render: (m) => <div><div>{m.productName}</div><div className="text-xs text-muted-foreground">{m.productSku ?? ''}</div></div> },
    { key: 'units', header: tx('Blocks per batch'), className: 'text-right', render: (m) => <span className="tabular-nums">{num(m.unitsPerBatch, 0)}</span> },
    { key: 'cement', header: tx('Cement'), render: (m) => m.cementProductName ?? '—' },
    { key: 'curing', header: tx('Min. curing days'), className: 'text-right', render: (m) => num(m.minCuringDays, 0) },
    { key: 'version', header: tx('Ver'), className: 'text-center', render: (m) => m.version },
    { key: 'status', header: tx('Status'), render: (m) => <MixStatusPill status={m.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (m) => <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View')} onClick={() => navigate(`/manufacturing/precast/mix-designs/${m.id}`)}><Eye className="h-4 w-4" /></Button>,
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Beaker} title={tx('Mix designs')}
        description={tx('Concrete block recipes per batch: materials, cement bags, curing rules. Scale them to any order size.')}
        actions={has(PERMISSIONS.bom.create) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New mix design')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search name, code, product…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(MIX_STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(m) => m.id}
            onRowClick={(m) => navigate(`/manufacturing/precast/mix-designs/${m.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the mix designs.') : chips.length ? tx('No mix designs match these filters.') : tx('No mix designs yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="mix design" />
        )}
      </ListCard>
      {creating && <MixDesignFormDialog onClose={() => setCreating(false)} onSaved={(m) => navigate(`/manufacturing/precast/mix-designs/${m.id}`)} />}
    </div>
  );
}

export default MixDesignsPage;
