import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, Microscope, Plus } from 'lucide-react';
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
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  LOT_STATUS_LABEL, RESULT_LABEL, STAGE_LABEL, useCreateLot, useInspectionLots, type InspectionLot, type LotResult,
  type LotStatus, type Stage,
} from '@/features/quality/api';
import { Field, LocationPicker, LotStatusPill, OptionalSelect, ProductPicker, ResultPill, SupplierPicker } from './quality-ui';
import { label, num, toOptions } from './quality-format';

const FILTERS = { status: '', stage: '', result: '' };

/** Inspection lots (ADR-S013): incoming (GRN), in-process (production stages) and final lots. */
export function InspectionLotsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { status, stage, result } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useInspectionLots({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    stage: stage || undefined, result: result || undefined,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: LOT_STATUS_LABEL[status as LotStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(stage ? [{ key: 'stage', label: STAGE_LABEL[stage as Stage] ?? stage, onRemove: () => list.setFilter('stage', '') }] : []),
    ...(result ? [{ key: 'result', label: RESULT_LABEL[result as LotResult] ?? result, onRemove: () => list.setFilter('result', '') }] : []),
  ];
  const columns: Column<InspectionLot>[] = [
    { key: 'lot', header: tx('Lot'), render: (l) => <span className="font-medium text-primary">{l.lotNumber}</span> },
    { key: 'created', header: tx('Opened'), render: (l) => <span className="whitespace-nowrap">{dateTime(l.createdAt)}</span> },
    { key: 'stage', header: tx('Stage'), render: (l) => STAGE_LABEL[l.stage] },
    { key: 'product', header: tx('Product'), render: (l) => <div><div>{l.productName}</div><div className="text-xs text-muted-foreground">{l.batchNumber ?? ''}</div></div> },
    { key: 'qty', header: tx('Quantity'), className: 'text-right', render: (l) => <span className="tabular-nums">{num(l.quantity, 3)} {l.uom ?? ''}</span> },
    { key: 'source', header: tx('Source'), render: (l) => l.sourceRef ?? label(l.sourceType) },
    { key: 'supplier', header: tx('Supplier'), render: (l) => l.supplierName ?? '—' },
    { key: 'result', header: tx('Result'), render: (l) => <ResultPill result={l.result} /> },
    { key: 'status', header: tx('Status'), render: (l) => <LotStatusPill status={l.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (l) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View')} onClick={() => navigate(`/quality/lots/${l.id}`)}><Eye className="h-4 w-4" /></Button>
      ),
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Microscope} title={tx('Inspection Lots')}
        description={tx('Record test results, then hold, quarantine, release, reject or downgrade the lot.')}
        actions={has(PERMISSIONS.quality.inspect) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New lot')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search lot, batch, product, source…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(LOT_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={stage} onChange={(v) => list.setFilter('stage', v)} options={toOptions(STAGE_LABEL)} allLabel={tx('All stages')} />
        <FilterSelect value={result} onChange={(v) => list.setFilter('result', v)} options={toOptions({ pending: RESULT_LABEL.pending, pass: RESULT_LABEL.pass, fail: RESULT_LABEL.fail })} allLabel={tx('Any result')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(l) => l.id}
            onRowClick={(l) => navigate(`/quality/lots/${l.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the inspection lots.') : chips.length ? tx('No lots match these filters.') : tx('No inspection lots yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="lot" />
        )}
      </ListCard>
      {creating && <LotCreateDialog onClose={() => setCreating(false)} onSaved={(l) => navigate(`/quality/lots/${l.id}`)} />}
    </div>
  );
}

function LotCreateDialog({ onClose, onSaved }: { onClose: () => void; onSaved: (l: InspectionLot) => void }) {
  const [stage, setStage] = useState<Stage>('final');
  const [productId, setProductId] = useState('');
  const [batchNumber, setBatchNumber] = useState('');
  const [quantity, setQuantity] = useState('');
  const [locationId, setLocationId] = useState('');
  const [supplierId, setSupplierId] = useState('');
  const [stageKey, setStageKey] = useState('');
  const [sourceRef, setSourceRef] = useState('');
  const [applyHold, setApplyHold] = useState(true);
  const [notes, setNotes] = useState('');
  const create = useCreateLot();
  const valid = productId && quantity !== '' && Number(quantity) > 0;
  const submit = async () => {
    try {
      const out = await create.mutateAsync({
        stage, productId, batchNumber: batchNumber.trim() || undefined, quantity: Number(quantity),
        locationId: locationId || undefined, supplierPartnerId: supplierId || undefined,
        stageKey: stage === 'in_process' ? stageKey.trim() || undefined : undefined, sourceRef: sourceRef.trim() || undefined,
        applyHoldPolicy: applyHold, notes: notes.trim() || undefined,
      });
      notify.success(tx('Lot {{number}} opened', { number: out.lotNumber }));
      onSaved(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not open the lot')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>{tx('New lot')}</DialogTitle><DialogDescription>{tx('The best matching active inspection plan of the product is used.')}</DialogDescription></DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Stage *')}><OptionalSelect value={stage} onChange={(v) => setStage((v || 'final') as Stage)} placeholder={tx('Stage')} options={toOptions(STAGE_LABEL)} /></Field>
          <Field label={tx('Product *')}><ProductPicker value={productId} onChange={setProductId} /></Field>
          <Field label={tx('Batch number')}><Input value={batchNumber} onChange={(e) => setBatchNumber(e.target.value)} /></Field>
          <Field label={tx('Quantity *')}><Input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} /></Field>
          <Field label={tx('Stock location')}><LocationPicker value={locationId} onChange={setLocationId} /></Field>
          {stage === 'incoming' && <Field label={tx('Supplier')}><SupplierPicker value={supplierId} onChange={setSupplierId} /></Field>}
          {stage === 'in_process' && <Field label={tx('Stage key')}><Input value={stageKey} onChange={(e) => setStageKey(e.target.value)} /></Field>}
          <Field label={tx('Reference')}><Input value={sourceRef} onChange={(e) => setSourceRef(e.target.value)} /></Field>
          <label className="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" checked={applyHold} onChange={(e) => setApplyHold(e.target.checked)} /> {tx('Apply the plan hold policy (quarantine moves the stock)')}</label>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending}>{tx('Open lot')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
