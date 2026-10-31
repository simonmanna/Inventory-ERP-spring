import { useState } from 'react';
import { Plus, Trash2, TrendingDown } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { LOSS_LABEL, useDeleteRow, useLosses, useQuarryLookups, useSaveRow, type Loss } from '@/features/quarry/api';
import { FormDialog, toOptions, tonnes, type FieldSpec } from './quarry-ui';

/** Losses: unusable rock, waste and spillage of extraction logs, plus standalone overburden stripping. */
export function LossesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ siteId: '', kind: '', from: '', to: '' });
  const f = list.filters;
  const lookups = useQuarryLookups();
  const { data, isLoading, isError } = useLosses({ page: list.page, pageSize: list.pageSize, siteId: f.siteId, kind: f.kind, dateFrom: f.from, dateTo: f.to });
  const save = useSaveRow('/quarry/losses');
  const remove = useDeleteRow('/quarry/losses');
  const [adding, setAdding] = useState(false);
  const L = lookups.data;
  const fields: FieldSpec[] = [
    { name: 'siteId', label: tx('Site'), type: 'select', required: true, options: (L?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` })) },
    { name: 'zoneId', label: tx('Zone / bench / face'), type: 'select', options: (L?.zones ?? []).map((z) => ({ value: z.id, label: `${z.code} · ${z.name}` })) },
    { name: 'kind', label: tx('Kind'), type: 'select', required: true, options: toOptions(LOSS_LABEL) },
    { name: 'tonnes', label: tx('Tonnes'), type: 'number', required: true, min: 0 },
    { name: 'lossOn', label: tx('Date'), type: 'date', required: true },
    { name: 'materialId', label: tx('Material'), type: 'select', options: (L?.materials ?? []).map((m) => ({ value: m.id, label: m.name })) },
    { name: 'reason', label: tx('Reason'), wide: true },
  ];
  const columns: Column<Loss>[] = [
    { key: 'date', header: tx('Date'), render: (x) => date(x.lossOn) },
    { key: 'site', header: tx('Site / zone'), render: (x) => [x.siteCode, x.zoneCode].filter(Boolean).join(' · ') },
    { key: 'kind', header: tx('Kind'), render: (x) => LOSS_LABEL[x.kind] ?? x.kind },
    { key: 'tonnes', header: tx('Tonnes'), className: 'text-right', render: (x) => tonnes(x.tonnes) },
    { key: 'log', header: tx('Extraction log'), render: (x) => x.logCode ?? tx('Standalone') },
    { key: 'reason', header: tx('Reason'), render: (x) => x.reason ?? '—' },
    { key: 'del', header: '', className: 'w-12', render: (x) => !x.logId && has(PERMISSIONS.quarryExtraction.update) && (
      <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')}
        onClick={async () => { try { await remove.mutateAsync(x.id); notify.success(tx('Deleted')); } catch (e) { notify.error(apiMessage(e, tx('Could not delete'))); } }}>
        <Trash2 className="h-4 w-4" /></Button>
    ) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={TrendingDown} title={tx('Losses')} description={tx('Overburden, unusable rock, waste and spillage by site and period.')}
        actions={has(PERMISSIONS.quarryExtraction.create) && <Button onClick={() => setAdding(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Record loss')}</Button>} />
      <ListToolbar>
        <FilterSelect value={f.siteId} onChange={(v) => list.setFilter('siteId', v)} options={(L?.sites ?? []).map((s) => ({ value: s.id, label: s.code }))} allLabel={tx('All sites')} />
        <FilterSelect value={f.kind} onChange={(v) => list.setFilter('kind', v)} options={toOptions(LOSS_LABEL)} allLabel={tx('All kinds')} />
        <DateRangeFilter from={f.from} to={f.to} onChange={(a, b) => list.setFilters({ from: a, to: b })} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(x) => x.id} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the losses. Try again.') : tx('No losses recorded.')} />
      </ListCard>
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
        onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="loss" />}
      {adding && (
        <FormDialog title={tx('Record loss')} fields={fields} defaults={{ kind: 'overburden', lossOn: new Date().toISOString().slice(0, 10) }}
          saving={save.isPending} onSave={(body) => save.mutateAsync({ body })} onClose={() => setAdding(false)} />
      )}
    </div>
  );
}
