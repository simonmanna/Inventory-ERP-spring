import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Target, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
  type ActiveChip,
} from '@/components/list';
import { useProductOptions } from '@/features/manufacturing/options';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  CHECK_TYPE_LABEL, CONTROL_PER_LABEL, FREQUENCY_LABEL, OPERATION_LABEL, RECEIPT_MODE_LABEL, SPC_SUBGROUP_LABEL,
  useControlPoints, useSaveControlPoint, useWorksheetTemplates, type ControlPoint, type QcCheckType, type QcControlPer,
  type QcFrequency, type QcOperation,
} from '@/features/quality-control/api';
import {
  ActivePill, Field, LocationPicker, OptionalSelect, ProductPicker, StaffPicker, SupplierPicker, TeamPicker, Toggle,
} from './qc-ui';
import { optNum, toOptions, tolerance } from './qc-format';

const FILTERS = { operation: '', checkType: '', active: '' };

/** Control points (ADR-S087 §1): what to check, where, how often, and whether it blocks the operation. */
export function ControlPointsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { operation, checkType, active } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useControlPoints({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, operation: operation || undefined,
    checkType: checkType || undefined, active: active === '' ? undefined : active === 'true',
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(operation ? [{ key: 'op', label: OPERATION_LABEL[operation as QcOperation] ?? operation, onRemove: () => list.setFilter('operation', '') }] : []),
    ...(checkType ? [{ key: 'type', label: CHECK_TYPE_LABEL[checkType as QcCheckType] ?? checkType, onRemove: () => list.setFilter('checkType', '') }] : []),
    ...(active ? [{ key: 'active', label: active === 'true' ? tx('Active') : tx('Inactive'), onRemove: () => list.setFilter('active', '') }] : []),
  ];
  const columns: Column<ControlPoint>[] = [
    { key: 'ref', header: tx('Reference'), render: (p) => <span className="font-medium text-primary">{p.reference}</span> },
    { key: 'title', header: tx('Title'), render: (p) => <span className="line-clamp-1 max-w-[18rem]">{p.title}</span> },
    { key: 'op', header: tx('Operation'), render: (p) => OPERATION_LABEL[p.operation] },
    { key: 'type', header: tx('Type'), render: (p) => <div><div>{CHECK_TYPE_LABEL[p.checkType]}</div>{p.checkType === 'measure' && <div className="text-xs text-muted-foreground">{tolerance(p.toleranceMin, p.toleranceMax, p.unit)}</div>}</div> },
    { key: 'freq', header: tx('Control'), render: (p) => <div><div>{CONTROL_PER_LABEL[p.controlPer]}</div><div className="text-xs text-muted-foreground">{FREQUENCY_LABEL[p.frequency]}</div></div> },
    { key: 'team', header: tx('Team'), render: (p) => p.teamName ?? '—' },
    { key: 'checks', header: tx('Checks'), className: 'text-right', render: (p) => <span>{p.checkCount ?? 0}{p.pendingCount ? <span className="text-xs text-muted-foreground"> ({p.pendingCount} {tx('to do')})</span> : null}</span> },
    { key: 'v', header: tx('Version'), className: 'text-right', render: (p) => `v${p.version}` },
    { key: 'status', header: tx('Status'), render: (p) => <ActivePill active={p.isActive} /> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Target} title={tx('Control Points')}
        description={tx('Define which quality checks are created on receipts, production, work orders and deliveries.')}
        actions={has(PERMISSIONS.qualityControl.configure) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New control point')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search reference, title…')} />
        <FilterSelect value={operation} onChange={(v) => list.setFilter('operation', v)} options={toOptions(OPERATION_LABEL)} allLabel={tx('Any operation')} />
        <FilterSelect value={checkType} onChange={(v) => list.setFilter('checkType', v)} options={toOptions(CHECK_TYPE_LABEL)} allLabel={tx('Any type')} />
        <FilterSelect value={active} onChange={(v) => list.setFilter('active', v)} options={[{ value: 'true', label: tx('Active') }, { value: 'false', label: tx('Inactive') }]} allLabel={tx('Active and inactive')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(p) => p.id}
            onRowClick={(p) => navigate(`/quality-control/control-points/${p.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the control points.') : chips.length ? tx('No control points match these filters.') : tx('No control points yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="control point" />
        )}
      </ListCard>
      {creating && <ControlPointDialog onClose={() => setCreating(false)} onSaved={(p) => navigate(`/quality-control/control-points/${p.id}`)} />}
    </div>
  );
}

const s = (v: unknown) => (v === null || v === undefined ? '' : String(v));

/** Create a control point, or save a new version of one (every save is versioned; existing checks keep theirs). */
export function ControlPointDialog({ point, onClose, onSaved }: { point?: ControlPoint; onClose: () => void; onSaved?: (p: ControlPoint) => void }) {
  const [f, setF] = useState({
    title: s(point?.title), operation: (point?.operation ?? 'manufacturing') as QcOperation, checkType: (point?.checkType ?? 'pass_fail') as QcCheckType,
    productIds: point?.productIds ?? [], supplierPartnerId: s(point?.supplierPartnerId), routingOperationId: s(point?.routingOperationId),
    teamId: s(point?.teamId), responsibleUserId: s(point?.responsibleUserId), instructions: s(point?.instructions),
    failureMessage: s(point?.failureMessage), failureClassRequired: point?.failureClassRequired ?? false,
    norm: s(point?.norm), toleranceMin: s(point?.toleranceMin), toleranceMax: s(point?.toleranceMax), unit: s(point?.unit),
    decimals: s(point?.decimals), worksheetTemplateId: s(point?.worksheetTemplateId),
    controlPer: (point?.controlPer ?? 'operation') as QcControlPer, testQtyPct: s(point?.testQtyPct), minSampleQty: s(point?.minSampleQty),
    frequency: (point?.frequency ?? 'all') as QcFrequency, randomPct: s(point?.randomPct), periodValue: s(point?.periodValue),
    periodUnit: s(point?.periodUnit), blocking: point?.blocking ?? true, blockOnFailure: point?.blockOnFailure ?? false,
    autoAlert: point?.autoAlert ?? true, receiptMode: s(point?.receiptMode) || 'pre_receipt', failureLocationId: s(point?.failureLocationId),
    spcSubgroup: point?.spcSubgroup ?? 'individual', spcSubgroupSize: s(point?.spcSubgroupSize), spcWindowHours: s(point?.spcWindowHours),
    spcSplitByWorkCenter: point?.spcSplitByWorkCenter ?? false, sequence: s(point?.sequence), reason: '',
  });
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((x) => ({ ...x, [k]: v }));
  const [productToAdd, setProductToAdd] = useState('');
  const products = useProductOptions();
  const templates = useWorksheetTemplates(true);
  const save = useSaveControlPoint();
  const productName = (id: string) => products.data?.find((p) => p.id === id)?.name ?? id.slice(0, 8);

  const submit = async () => {
    const body = {
      title: f.title.trim(), operation: f.operation, checkType: f.checkType, productIds: f.productIds,
      supplierPartnerId: f.operation === 'receipt' ? f.supplierPartnerId || undefined : undefined,
      routingOperationId: f.operation === 'work_order' ? f.routingOperationId || undefined : undefined,
      teamId: f.teamId || undefined, responsibleUserId: f.responsibleUserId || undefined,
      instructions: f.instructions.trim() || undefined, failureMessage: f.failureMessage.trim() || undefined,
      failureClassRequired: f.failureClassRequired,
      ...(f.checkType === 'measure' ? {
        norm: optNum(f.norm), toleranceMin: optNum(f.toleranceMin), toleranceMax: optNum(f.toleranceMax), unit: f.unit.trim() || undefined,
        decimals: optNum(f.decimals),
      } : {}),
      worksheetTemplateId: f.checkType === 'worksheet' ? f.worksheetTemplateId || undefined : undefined,
      controlPer: f.controlPer, testQtyPct: f.controlPer === 'quantity' ? optNum(f.testQtyPct) : undefined,
      minSampleQty: f.controlPer === 'quantity' ? optNum(f.minSampleQty) : undefined,
      frequency: f.frequency, randomPct: f.frequency === 'random' ? optNum(f.randomPct) : undefined,
      periodValue: f.frequency === 'periodic' ? optNum(f.periodValue) : undefined,
      periodUnit: f.frequency === 'periodic' ? f.periodUnit || undefined : undefined,
      blocking: f.blocking, blockOnFailure: f.blockOnFailure, autoAlert: f.autoAlert,
      receiptMode: f.operation === 'receipt' ? f.receiptMode : undefined, failureLocationId: f.failureLocationId || undefined,
      spcSubgroup: f.spcSubgroup, spcSubgroupSize: f.spcSubgroup === 'consecutive' ? optNum(f.spcSubgroupSize) : undefined,
      spcWindowHours: f.spcSubgroup === 'time_window' ? optNum(f.spcWindowHours) : undefined,
      spcSplitByWorkCenter: f.spcSplitByWorkCenter, sequence: optNum(f.sequence), reason: f.reason.trim() || undefined,
    };
    try {
      const out = await save.mutateAsync({ id: point?.id, body });
      notify.success(point ? tx('Saved as version {{v}}', { v: out.version }) : tx('Control point {{ref}} created', { ref: out.reference }));
      onSaved?.(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the control point')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{point ? tx('Edit {{ref}}', { ref: point.reference }) : tx('New control point')}</DialogTitle>
          <DialogDescription>{point ? tx('Saving creates version {{v}}. Checks already created keep the version they were created under.', { v: point.version + 1 }) : tx('Checks are created automatically on matching operations.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Field label={tx('Title *')} className="sm:col-span-3"><Input value={f.title} onChange={(e) => set('title', e.target.value)} /></Field>
          <Field label={tx('Operation')}><OptionalSelect value={f.operation} onChange={(v) => set('operation', (v || 'manufacturing') as QcOperation)} placeholder={tx('Operation')} options={toOptions(OPERATION_LABEL)} /></Field>
          <Field label={tx('Check type')}><OptionalSelect value={f.checkType} onChange={(v) => set('checkType', (v || 'pass_fail') as QcCheckType)} placeholder={tx('Type')} options={toOptions(CHECK_TYPE_LABEL)} /></Field>
          <Field label={tx('Sequence')}><Input inputMode="numeric" value={f.sequence} onChange={(e) => set('sequence', e.target.value)} placeholder="10" /></Field>

          <Field label={tx('Products (empty = all)')} className="sm:col-span-3">
            <div className="space-y-2">
              <div className="flex flex-wrap gap-1.5">
                {f.productIds.map((id) => (
                  <span key={id} className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs">
                    {productName(id)}
                    <button type="button" aria-label={tx('Remove')} onClick={() => set('productIds', f.productIds.filter((x) => x !== id))}><X className="h-3 w-3" /></button>
                  </span>
                ))}
                {!f.productIds.length && <span className="text-xs text-muted-foreground">{tx('All products')}</span>}
              </div>
              <ProductPicker value={productToAdd} placeholder={tx('Add a product')}
                onChange={(v) => { if (v && !f.productIds.includes(v)) set('productIds', [...f.productIds, v]); setProductToAdd(''); }} />
            </div>
          </Field>
          {f.operation === 'receipt' && <Field label={tx('Supplier')}><SupplierPicker value={f.supplierPartnerId} onChange={(v) => set('supplierPartnerId', v)} /></Field>}
          {f.operation === 'receipt' && (
            <Field label={tx('Receipt mode')} className="sm:col-span-2">
              <OptionalSelect value={f.receiptMode} onChange={(v) => set('receiptMode', v || 'pre_receipt')} placeholder={tx('Receipt mode')} options={toOptions(RECEIPT_MODE_LABEL)} />
            </Field>
          )}
          <Field label={tx('Team')}><TeamPicker value={f.teamId} onChange={(v) => set('teamId', v)} /></Field>
          <Field label={tx('Responsible')}><StaffPicker value={f.responsibleUserId} onChange={(v) => set('responsibleUserId', v)} /></Field>
          <Field label={tx('Quarantine location')}><LocationPicker value={f.failureLocationId} onChange={(v) => set('failureLocationId', v)} /></Field>

          {f.checkType === 'measure' && (
            <>
              <Field label={tx('Minimum')}><Input inputMode="decimal" value={f.toleranceMin} onChange={(e) => set('toleranceMin', e.target.value)} /></Field>
              <Field label={tx('Norm')}><Input inputMode="decimal" value={f.norm} onChange={(e) => set('norm', e.target.value)} /></Field>
              <Field label={tx('Maximum')}><Input inputMode="decimal" value={f.toleranceMax} onChange={(e) => set('toleranceMax', e.target.value)} /></Field>
              <Field label={tx('Unit')}><Input value={f.unit} onChange={(e) => set('unit', e.target.value)} placeholder="mm, %, MPa" /></Field>
              <Field label={tx('Decimals')}><Input inputMode="numeric" value={f.decimals} onChange={(e) => set('decimals', e.target.value)} /></Field>
            </>
          )}
          {f.checkType === 'worksheet' && (
            <Field label={tx('Worksheet template *')} className="sm:col-span-3">
              <OptionalSelect value={f.worksheetTemplateId} onChange={(v) => set('worksheetTemplateId', v)} placeholder={tx('Choose a template')}
                options={(templates.data ?? []).map((t) => ({ value: t.id, label: `${t.name} (v${t.version})` }))} />
            </Field>
          )}
          <Field label={tx('Instructions')} className="sm:col-span-3"><Textarea rows={3} value={f.instructions} onChange={(e) => set('instructions', e.target.value)} /></Field>
          <Field label={tx('Message if the check fails')} className="sm:col-span-3"><Textarea rows={2} value={f.failureMessage} onChange={(e) => set('failureMessage', e.target.value)} /></Field>

          <Field label={tx('Control per')}><OptionalSelect value={f.controlPer} onChange={(v) => set('controlPer', (v || 'operation') as QcControlPer)} placeholder={tx('Control per')} options={toOptions(CONTROL_PER_LABEL)} /></Field>
          {f.controlPer === 'quantity' ? (
            <>
              <Field label={tx('Test quantity %')}><Input inputMode="decimal" value={f.testQtyPct} onChange={(e) => set('testQtyPct', e.target.value)} /></Field>
              <Field label={tx('Minimum sample')}><Input inputMode="decimal" value={f.minSampleQty} onChange={(e) => set('minSampleQty', e.target.value)} placeholder="1" /></Field>
            </>
          ) : <div className="hidden sm:col-span-2 sm:block" />}
          <Field label={tx('Frequency')}><OptionalSelect value={f.frequency} onChange={(v) => set('frequency', (v || 'all') as QcFrequency)} placeholder={tx('Frequency')} options={toOptions(FREQUENCY_LABEL)} /></Field>
          {f.frequency === 'random' && <Field label={tx('Chance %')}><Input inputMode="decimal" value={f.randomPct} onChange={(e) => set('randomPct', e.target.value)} /></Field>}
          {f.frequency === 'periodic' && (
            <>
              <Field label={tx('Every')}><Input inputMode="numeric" value={f.periodValue} onChange={(e) => set('periodValue', e.target.value)} /></Field>
              <Field label={tx('Period')}><OptionalSelect value={f.periodUnit} onChange={(v) => set('periodUnit', v)} placeholder={tx('Period')} options={[{ value: 'day', label: tx('Days') }, { value: 'week', label: tx('Weeks') }, { value: 'month', label: tx('Months') }]} /></Field>
            </>
          )}

          <div className="space-y-2 sm:col-span-3">
            <Toggle checked={f.blocking} onChange={(v) => set('blocking', v)} label={tx('Block the operation until the check is done')} />
            <Toggle checked={f.blockOnFailure} onChange={(v) => set('blockOnFailure', v)} label={tx('Block the operation while the check is failed')} hint={tx('A manager can waive a failed check with a reason.')} />
            <Toggle checked={f.failureClassRequired} onChange={(v) => set('failureClassRequired', v)} label={tx('A failure needs a failure class')} />
            <Toggle checked={f.autoAlert} onChange={(v) => set('autoAlert', v)} label={tx('Raise a quality alert when the check fails')} />
          </div>

          {f.checkType === 'measure' && (
            <>
              <Field label={tx('Control chart subgroup')}><OptionalSelect value={f.spcSubgroup} onChange={(v) => set('spcSubgroup', v || 'individual')} placeholder={tx('Subgroup')} options={toOptions(SPC_SUBGROUP_LABEL)} /></Field>
              {f.spcSubgroup === 'consecutive' && <Field label={tx('Subgroup size (2–10)')}><Input inputMode="numeric" value={f.spcSubgroupSize} onChange={(e) => set('spcSubgroupSize', e.target.value)} /></Field>}
              {f.spcSubgroup === 'time_window' && <Field label={tx('Window (hours)')}><Input inputMode="numeric" value={f.spcWindowHours} onChange={(e) => set('spcWindowHours', e.target.value)} /></Field>}
              <div className="flex items-end pb-2"><Toggle checked={f.spcSplitByWorkCenter} onChange={(v) => set('spcSplitByWorkCenter', v)} label={tx('Separate chart per work centre')} /></div>
            </>
          )}
          {point && <Field label={tx('Reason for the change')} className="sm:col-span-3"><Input value={f.reason} onChange={(e) => set('reason', e.target.value)} /></Field>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!f.title.trim() || save.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
