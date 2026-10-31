import { useState } from 'react';
import { ClipboardList, Pencil, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill,
  useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  HOLD_LABEL, SAMPLING_LABEL, STAGE_LABEL, useDeletePlan, useInspectionPlans, useSavePlan, useSpecifications,
  type HoldPolicy, type InspectionPlan, type SamplingMethod, type Stage,
} from '@/features/quality/api';
import { Field, OptionalSelect, ProductPicker, SupplierPicker } from './quality-ui';
import { optNum, toOptions } from './quality-format';

const FILTERS = { stage: '' };

/** Inspection plans (ADR-S013): what is inspected at which stage, against which specification, how many samples. */
export function InspectionPlansPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { stage } = list.filters;
  const [editing, setEditing] = useState<InspectionPlan | 'new' | null>(null);
  const remove = useDeletePlan();
  const { data, isLoading, isError } = useInspectionPlans({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, stage: stage || undefined,
  });
  const canEdit = has(PERMISSIONS.quality.configure);
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(stage ? [{ key: 'stage', label: STAGE_LABEL[stage as Stage] ?? stage, onRemove: () => list.setFilter('stage', '') }] : []),
  ];
  const del = async (p: InspectionPlan) => {
    if (!window.confirm(tx('Delete inspection plan {{code}}?', { code: p.code }))) return;
    try {
      await remove.mutateAsync(p.id);
      notify.success(tx('Inspection plan deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the inspection plan')));
    }
  };
  const sampling = (p: InspectionPlan) => {
    const m = SAMPLING_LABEL[p.samplingMethod];
    if (p.samplingMethod === 'fixed') return `${m}: ${p.sampleSize ?? 1}`;
    if (p.samplingMethod === 'percent') return `${m}: ${p.samplePct}%`;
    if (p.samplingMethod === 'per_quantity') return `${m}: ${p.perQuantity}`;
    return m;
  };
  const columns: Column<InspectionPlan>[] = [
    { key: 'code', header: tx('Code'), render: (p) => <span className="font-medium">{p.code}</span> },
    { key: 'name', header: tx('Name'), render: (p) => p.name },
    { key: 'stage', header: tx('Stage'), render: (p) => STAGE_LABEL[p.stage] + (p.stageKey ? ` · ${p.stageKey}` : '') },
    { key: 'product', header: tx('Product'), render: (p) => p.productName ?? '—' },
    { key: 'supplier', header: tx('Supplier'), render: (p) => p.supplierName ?? '—' },
    { key: 'spec', header: tx('Specification'), render: (p) => (p.specificationCode ? `${p.specificationCode} v${p.specificationVersion}` : '—') },
    { key: 'sampling', header: tx('Sampling'), render: sampling },
    { key: 'hold', header: tx('Hold policy'), render: (p) => HOLD_LABEL[p.holdPolicy] },
    { key: 'active', header: tx('Status'), render: (p) => <StatusPill tone={p.isActive ? 'success' : 'neutral'}>{p.isActive ? tx('Active') : tx('Inactive')}</StatusPill> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (p) => canEdit && (
        <div className="flex justify-end gap-1">
          <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(p)}><Pencil className="h-4 w-4" /></Button>
          <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(p)}><Trash2 className="h-4 w-4" /></Button>
        </div>
      ),
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ClipboardList} title={tx('Inspection Plans')}
        description={tx('Incoming plans open a lot on every goods receipt; in-process plans gate production stages; final plans inspect before release.')}
        actions={canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New plan')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search code, name, product…')} />
        <FilterSelect value={stage} onChange={(v) => list.setFilter('stage', v)} options={toOptions(STAGE_LABEL)} allLabel={tx('All stages')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(p) => p.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          onRowClick={canEdit ? (p) => setEditing(p) : undefined}
          emptyMessage={isError ? tx('Could not load the inspection plans.') : tx('No inspection plans yet.')} />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="plan" />
        )}
      </ListCard>
      {editing && <PlanDialog plan={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function PlanDialog({ plan, onClose }: { plan?: InspectionPlan; onClose: () => void }) {
  const [code, setCode] = useState(plan?.code ?? '');
  const [name, setName] = useState(plan?.name ?? '');
  const [stage, setStage] = useState<Stage>(plan?.stage ?? 'incoming');
  const [productId, setProductId] = useState(plan?.productId ?? '');
  const [supplierId, setSupplierId] = useState(plan?.supplierPartnerId ?? '');
  const [stageKey, setStageKey] = useState(plan?.stageKey ?? '');
  const [specificationId, setSpecificationId] = useState(plan?.specificationId ?? '');
  const [method, setMethod] = useState<SamplingMethod>(plan?.samplingMethod ?? 'fixed');
  const [sampleSize, setSampleSize] = useState(plan?.sampleSize != null ? String(plan.sampleSize) : '1');
  const [samplePct, setSamplePct] = useState(plan?.samplePct ?? '');
  const [perQuantity, setPerQuantity] = useState(plan?.perQuantity ?? '');
  const [minSamples, setMinSamples] = useState(String(plan?.minSamples ?? 1));
  const [maxSamples, setMaxSamples] = useState(plan?.maxSamples != null ? String(plan.maxSamples) : '');
  const [holdPolicy, setHoldPolicy] = useState<HoldPolicy>(plan?.holdPolicy ?? 'none');
  const [independent, setIndependent] = useState(plan?.independentRelease ?? true);
  const [autoNcr, setAutoNcr] = useState(plan?.autoNcr ?? true);
  const [active, setActive] = useState(plan?.isActive ?? true);
  const [notes, setNotes] = useState(plan?.notes ?? '');
  const specs = useSpecifications({ page: 1, pageSize: 200 });
  const save = useSavePlan();
  const valid = code.trim() && name.trim() && productId && specificationId
    && (method !== 'percent' || samplePct !== '') && (method !== 'per_quantity' || perQuantity !== '');
  const submit = async () => {
    const body: Record<string, unknown> = {
      name: name.trim(), stageKey: stage === 'in_process' ? stageKey.trim() : undefined,
      supplierPartnerId: stage === 'incoming' ? supplierId : undefined, specificationId, samplingMethod: method,
      sampleSize: method === 'fixed' ? optNum(sampleSize) : undefined, samplePct: method === 'percent' ? optNum(samplePct) : undefined,
      perQuantity: method === 'per_quantity' ? optNum(perQuantity) : undefined, minSamples: optNum(minSamples),
      maxSamples: optNum(maxSamples), holdPolicy, independentRelease: independent, autoNcr, isActive: active, notes: notes.trim(),
    };
    if (!plan) Object.assign(body, { code: code.trim(), stage, productId, supplierPartnerId: stage === 'incoming' ? supplierId || undefined : undefined, stageKey: stage === 'in_process' ? stageKey.trim() || undefined : undefined, notes: notes.trim() || undefined });
    try {
      await save.mutateAsync({ id: plan?.id, body });
      notify.success(tx('Inspection plan saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the inspection plan')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{plan ? tx('Edit plan {{code}}', { code: plan.code }) : tx('New plan')}</DialogTitle>
          <DialogDescription>{tx('A lot opens with the active version of the specification and the sample size of this plan.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Code *')}>{plan ? <p className="text-sm font-medium">{plan.code}</p> : <Input value={code} onChange={(e) => setCode(e.target.value)} />}</Field>
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Stage *')}>
            {plan ? <p className="text-sm font-medium">{STAGE_LABEL[plan.stage]}</p>
              : <OptionalSelect value={stage} onChange={(v) => setStage((v || 'incoming') as Stage)} placeholder={tx('Stage')} options={toOptions(STAGE_LABEL)} />}
          </Field>
          <Field label={tx('Product *')}>{plan ? <p className="text-sm font-medium">{plan.productName}</p> : <ProductPicker value={productId} onChange={setProductId} />}</Field>
          {stage === 'incoming' && <Field label={tx('Supplier')}><SupplierPicker value={supplierId} onChange={setSupplierId} /></Field>}
          {stage === 'in_process' && <Field label={tx('Stage key')}><Input value={stageKey} onChange={(e) => setStageKey(e.target.value)} placeholder={tx('Operation code (blank = any stage)')} /></Field>}
          <Field label={tx('Specification *')}>
            <SearchableSelect value={specificationId} onValueChange={setSpecificationId} placeholder={tx('Choose a specification')} searchPlaceholder={tx('Search…')}
              options={(specs.data?.data ?? []).filter((s) => s.status !== 'archived').map((s) => ({ value: s.id, label: `${s.code} v${s.version} — ${s.name}`, hint: s.status }))} />
          </Field>
          <Field label={tx('Sampling')}><OptionalSelect value={method} onChange={(v) => setMethod((v || 'fixed') as SamplingMethod)} placeholder={tx('Sampling')} options={toOptions(SAMPLING_LABEL)} /></Field>
          {method === 'fixed' && <Field label={tx('Samples')}><Input inputMode="numeric" value={sampleSize} onChange={(e) => setSampleSize(e.target.value)} /></Field>}
          {method === 'percent' && <Field label={tx('Percent *')}><Input inputMode="decimal" value={samplePct} onChange={(e) => setSamplePct(e.target.value)} /></Field>}
          {method === 'per_quantity' && <Field label={tx('One sample per *')}><Input inputMode="decimal" value={perQuantity} onChange={(e) => setPerQuantity(e.target.value)} /></Field>}
          <Field label={tx('Minimum samples')}><Input inputMode="numeric" value={minSamples} onChange={(e) => setMinSamples(e.target.value)} /></Field>
          <Field label={tx('Maximum samples')}><Input inputMode="numeric" value={maxSamples} onChange={(e) => setMaxSamples(e.target.value)} /></Field>
          <Field label={tx('Hold policy')}><OptionalSelect value={holdPolicy} onChange={(v) => setHoldPolicy((v || 'none') as HoldPolicy)} placeholder={tx('Hold policy')} options={toOptions(HOLD_LABEL)} /></Field>
          <div className="space-y-2 pt-6 text-sm sm:col-span-2">
            <label className="flex items-center gap-2"><input type="checkbox" checked={independent} onChange={(e) => setIndependent(e.target.checked)} /> {tx('Independent release (the inspector cannot decide on the lot)')}</label>
            <label className="flex items-center gap-2"><input type="checkbox" checked={autoNcr} onChange={(e) => setAutoNcr(e.target.checked)} /> {tx('Raise an NCR when a lot fails')}</label>
            <label className="flex items-center gap-2"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {tx('Active')}</label>
          </div>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
