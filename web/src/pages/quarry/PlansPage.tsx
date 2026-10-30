import { useState } from 'react';
import { Pencil, Plus, Target, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useDeleteRow, usePlans, useQuarryLookups, useSaveRow, type Plan } from '@/features/quarry/api';
import { FormDialog, VariancePill, qty, tonnes, type FieldSpec } from './quarry-ui';

const STATUS: Record<string, string> = { draft: tx('Draft'), active: tx('Active'), closed: tx('Closed') };

/** Extraction plans and production targets with actual tonnes, pace and cost per tonne vs budget. */
export function PlansPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ siteId: '', status: '' });
  const lookups = useQuarryLookups();
  const { data, isLoading, isError } = usePlans({ page: list.page, pageSize: list.pageSize, search: list.search, ...list.filters });
  const save = useSaveRow('/quarry/extraction-plans');
  const remove = useDeleteRow('/quarry/extraction-plans');
  const [editing, setEditing] = useState<Plan | 'new' | null>(null);
  const [siteId, setSiteId] = useState('');
  const sites = (lookups.data?.sites ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` }));
  const formSite = editing && editing !== 'new' ? editing.siteId : siteId;
  const fields: FieldSpec[] = [
    { name: 'code', label: tx('Code'), hint: tx('Automatic when blank') },
    { name: 'name', label: tx('Name'), required: true },
    { name: 'siteId', label: tx('Site'), type: 'select', required: true, options: sites },
    { name: 'zoneId', label: tx('Zone / bench / face'), type: 'select', hint: tx('Blank = whole site'),
      options: (lookups.data?.zones ?? []).filter((z) => !formSite || z.siteId === formSite).map((z) => ({ value: z.id, label: `${z.code} · ${z.name}` })) },
    { name: 'materialId', label: tx('Material'), type: 'select', options: (lookups.data?.materials ?? []).map((m) => ({ value: m.id, label: `${m.code} · ${m.name}` })) },
    { name: 'periodStart', label: tx('From'), type: 'date', required: true },
    { name: 'periodEnd', label: tx('To'), type: 'date', required: true },
    { name: 'targetTonnes', label: tx('Target (t)'), type: 'number', required: true, min: 0 },
    { name: 'budgetCostPerTonne', label: tx('Budget cost per tonne'), type: 'number', min: 0 },
    { name: 'status', label: tx('Status'), type: 'select', required: true, options: Object.entries(STATUS).map(([value, label]) => ({ value, label })) },
    { name: 'notes', label: tx('Notes'), type: 'textarea' },
  ];
  const columns: Column<Plan>[] = [
    { key: 'code', header: tx('Plan'), render: (p) => <span className="font-medium">{p.code} · {p.name}</span> },
    { key: 'scope', header: tx('Scope'), render: (p) => [p.siteCode, p.zoneCode, p.materialName].filter(Boolean).join(' · ') },
    { key: 'period', header: tx('Period'), render: (p) => `${date(p.periodStart)} – ${date(p.periodEnd)}` },
    { key: 'target', header: tx('Target'), className: 'text-right', render: (p) => tonnes(p.targetTonnes) },
    { key: 'actual', header: tx('Actual'), className: 'text-right', render: (p) => `${tonnes(p.actualTonnes)} (${qty(p.variance.achievedPct, 1)}%)` },
    { key: 'pace', header: tx('Pace'), render: (p) => <VariancePill status={p.variance.status} /> },
    { key: 'cpt', header: tx('Cost / t vs budget'), className: 'text-right', render: (p) => `${p.actualCostPerTonne ? money(p.actualCostPerTonne) : '—'} / ${p.budgetCostPerTonne ? money(p.budgetCostPerTonne) : '—'}` },
    { key: 'actions', header: '', className: 'w-20 text-right', render: (p) => has(PERMISSIONS.quarryExtraction.plan) && (
      <span className="flex justify-end gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(p)}><Pencil className="h-4 w-4" /></Button>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')}
          onClick={async () => { try { await remove.mutateAsync(p.id); notify.success(tx('Deleted')); } catch (e) { notify.error(apiMessage(e, tx('Could not delete'))); } }}>
          <Trash2 className="h-4 w-4" /></Button>
      </span>
    ) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Target} title={tx('Extraction Plans & Targets')} description={tx('Production targets per site, zone and period, against the posted extraction.')}
        actions={has(PERMISSIONS.quarryExtraction.plan) && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add plan')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search plans…')} />
        <FilterSelect value={list.filters.siteId} onChange={(v) => list.setFilter('siteId', v)} options={sites} allLabel={tx('All sites')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={Object.entries(STATUS).map(([value, label]) => ({ value, label }))} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(p) => p.id} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the plans. Try again.') : tx('No extraction plans yet.')} />
      </ListCard>
      {data?.meta && <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
        onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="plan" />}
      {editing && (
        <FormDialog title={editing === 'new' ? tx('Add extraction plan') : tx('Edit {{code}}', { code: editing.code })} fields={fields}
          row={editing === 'new' ? null : editing} defaults={{ status: 'active', siteId }} saving={save.isPending}
          onSave={(body) => { setSiteId(String(body.siteId ?? '')); return save.mutateAsync({ id: editing === 'new' ? undefined : editing.id, body }); }}
          onClose={() => setEditing(null)} />
      )}
    </div>
  );
}
