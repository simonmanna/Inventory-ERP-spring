import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bomb, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { BLAST_STATUS_LABEL, useBlasts, useQuarryLookups, useSaveRow, type Blast } from '@/features/quarry/api';
import { BlastStatusPill, FormDialog, qty, toOptions, tonnes } from './quarry-ui';
import { blastFields } from './blast-fields';

/** Drilling & blasting records: pattern, estimated tonnes, explosives used, cost pool and its absorption. */
export function BlastsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ siteId: '', status: '' });
  const lookups = useQuarryLookups();
  const { data, isLoading, isError } = useBlasts({ page: list.page, pageSize: list.pageSize, search: list.search, ...list.filters });
  const save = useSaveRow<Blast>('/quarry/blasts');
  const [creating, setCreating] = useState(false);
  const columns: Column<Blast>[] = [
    { key: 'code', header: tx('Blast'), render: (b) => <span className="font-medium text-primary">{b.code}</span> },
    { key: 'when', header: tx('When'), render: (b) => dateTime(b.firedAt ?? b.plannedAt) },
    { key: 'site', header: tx('Site / face'), render: (b) => [b.siteCode, b.zoneCode].filter(Boolean).join(' · ') },
    { key: 'holes', header: tx('Holes'), className: 'text-right', render: (b) => b.holeCount ?? '—' },
    { key: 'tonnes', header: tx('Estimated'), className: 'text-right', render: (b) => tonnes(b.estimatedTonnes) },
    { key: 'kg', header: tx('Explosives'), className: 'text-right', render: (b) => `${qty(b.explosivesKg)} kg` },
    { key: 'cost', header: tx('Cost / absorbed'), className: 'text-right', render: (b) => `${money(b.totalCost)} / ${money(b.absorbedCost)}` },
    { key: 'status', header: tx('Status'), render: (b) => <BlastStatusPill status={b.status} /> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Bomb} title={tx('Drilling & Blasting')} description={tx('Blast patterns, compliance records, explosives and the blast cost per tonne.')}
        actions={has(PERMISSIONS.quarryBlasting.create) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Plan blast')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search blast, permit, face…')} />
        <FilterSelect value={list.filters.siteId} onChange={(v) => list.setFilter('siteId', v)} options={(lookups.data?.sites ?? []).map((s) => ({ value: s.id, label: s.code }))} allLabel={tx('All sites')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(BLAST_STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(b) => b.id} onRowClick={(b) => navigate(`/quarry/blasts/${b.id}`)}
          cellClassName="py-2.5 px-4" emptyMessage={isError ? tx('Could not load the blasts. Try again.') : tx('No blasts yet.')} />
      </ListCard>
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
        onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="blast" />}
      {creating && (
        <FormDialog title={tx('Plan blast')} fields={blastFields(lookups.data)} defaults={{ patternType: 'staggered' }} saving={save.isPending}
          onSave={async (body) => { const b = (await save.mutateAsync({ body })) as Blast; navigate(`/quarry/blasts/${b.id}`); }}
          onClose={() => setCreating(false)} />
      )}
    </div>
  );
}
