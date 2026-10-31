import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ClipboardList, Eye, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
  type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money, useOrgCurrency } from '@/lib/format';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import {
  BASIS_LABEL, ORDER_STATUS_LABEL, useCreatePrecastOrder, useMixDesigns, useMixScale, usePrecastOrders,
  type PrecastOrder, type ScaleBasis,
} from '@/features/manufacturing/api-precast';
import type { ProductionOrderStatus } from '@/features/manufacturing/types';
import { Field, OptionalSelect, OrderStatusPill, ScalingSummary } from './precast-ui';
import { ClearableLocation } from './MixDesignForm';
import { num, positive, toOptions } from './precast-format';

const FILTERS = { status: '' };

/** Precast orders (2.4): block production orders created from an active mix design, scaled by blocks, bags, pallets… */
export function PrecastOrdersPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const currency = useOrgCurrency();
  const list = useListState(FILTERS);
  const { status } = list.filters;
  const [params, setParams] = useSearchParams();
  const [prefill, setPrefill] = useState<Prefill | null>(null);

  // "Create order from this" on a mix design lands here with ?new=1&mixDesignId=…; open the form once and drop the params.
  useEffect(() => {
    if (params.get('new') !== '1') return;
    setPrefill({
      mixDesignId: params.get('mixDesignId') ?? '',
      basis: (params.get('basis') as ScaleBasis) || 'units',
      value: params.get('value') ?? '',
      wholeBatches: params.get('wholeBatches') !== 'false',
    });
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      ['new', 'mixDesignId', 'basis', 'value', 'wholeBatches'].forEach((k) => next.delete(k));
      return next;
    }, { replace: true });
  }, [params, setParams]);

  const { data, isLoading, isFetching, isError } = usePrecastOrders({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: ORDER_STATUS_LABEL[status as ProductionOrderStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
  ];
  const columns: Column<PrecastOrder>[] = [
    { key: 'code', header: tx('Order'), render: (o) => <span className="font-mono text-xs text-primary">{o.orderCode}</span> },
    { key: 'product', header: tx('Block product'), render: (o) => <div><div>{o.productName}</div><div className="text-xs text-muted-foreground">{o.bomCode ? `${o.bomCode} v${o.bomVersion ?? ''}` : ''}</div></div> },
    { key: 'planned', header: tx('Planned'), className: 'text-right', render: (o) => <span className="tabular-nums">{num(o.plannedQty, 0)}</span> },
    { key: 'produced', header: tx('Good blocks'), className: 'text-right', render: (o) => <span className="tabular-nums">{num(o.producedQty, 0)}</span> },
    { key: 'batches', header: tx('Curing batches'), className: 'text-right', render: (o) => num(o.curingBatches, 0) },
    { key: 'batch', header: tx('Batch number'), render: (o) => o.batchNumber ?? '—' },
    { key: 'scheduled', header: tx('Scheduled'), render: (o) => (o.scheduledFor ? date(o.scheduledFor) : '—') },
    { key: 'cost', header: tx('Total cost'), className: 'text-right', render: (o) => <span className="tabular-nums">{money(o.totalCost, currency)}</span> },
    { key: 'status', header: tx('Status'), render: (o) => <OrderStatusPill status={o.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (o) => <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View')} onClick={() => navigate(`/manufacturing/precast/orders/${o.id}`)}><Eye className="h-4 w-4" /></Button>,
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ClipboardList} title={tx('Precast orders')}
        description={tx('Block production orders: press, cure, cube. Costs per acceptable block on every order.')}
        actions={has(PERMISSIONS.productionOrder.create) && (
          <Button onClick={() => setPrefill({ mixDesignId: '', basis: 'units', value: '', wholeBatches: true })}><Plus className="mr-1.5 h-4 w-4" /> {tx('New precast order')}</Button>
        )} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search order, product, batch…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(ORDER_STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(o) => o.id}
            onRowClick={(o) => navigate(`/manufacturing/precast/orders/${o.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the precast orders.') : chips.length ? tx('No orders match these filters.') : tx('No precast orders yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="order" />
        )}
      </ListCard>
      {prefill && has(PERMISSIONS.productionOrder.create) && (
        <PrecastOrderDialog prefill={prefill} onClose={() => setPrefill(null)} onSaved={(id) => navigate(`/manufacturing/precast/orders/${id}`)} />
      )}
    </div>
  );
}

interface Prefill { mixDesignId: string; basis: ScaleBasis; value: string; wholeBatches: boolean }

function PrecastOrderDialog({ prefill, onClose, onSaved }: { prefill: Prefill; onClose: () => void; onSaved: (id: string) => void }) {
  const mixes = useMixDesigns({ page: 1, pageSize: 200, status: 'active' });
  const [mixDesignId, setMixDesignId] = useState(prefill.mixDesignId);
  const [basis, setBasis] = useState<ScaleBasis>(prefill.basis);
  const [value, setValue] = useState(prefill.value);
  const [wholeBatches, setWholeBatches] = useState(prefill.wholeBatches);
  const [locationId, setLocationId] = useState('');
  const [outputLocationId, setOutputLocationId] = useState('');
  const [scheduledFor, setScheduledFor] = useState('');
  const [batchNumber, setBatchNumber] = useState('');
  const [confirm, setConfirm] = useState(true);
  const [notes, setNotes] = useState('');
  const create = useCreatePrecastOrder();
  const debouncedValue = useDebouncedValue(value, 400);
  const valid = !!mixDesignId && positive(value);
  const preview = useMixScale(mixDesignId || undefined,
    positive(debouncedValue) ? { basis, value: Number(debouncedValue), wholeBatches } : null);
  const submit = async () => {
    try {
      const out = await create.mutateAsync({
        mixDesignId, basis, value: Number(value), wholeBatches, locationId: locationId || undefined,
        outputLocationId: outputLocationId || undefined, scheduledFor: scheduledFor || undefined,
        batchNumber: batchNumber.trim() || undefined, confirm, notes: notes.trim() || undefined,
      });
      notify.success(tx('Order {{code}} created', { code: out.orderCode }));
      onSaved(out.id);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the order')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{tx('New precast order')}</DialogTitle>
          <DialogDescription>{tx('Size the order by blocks, batches, cement bags, volume, press cycles or pallets.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Mix design *')} className="sm:col-span-2">
            <SearchableSelect value={mixDesignId} onValueChange={setMixDesignId} placeholder={tx('Choose an active mix design')} searchPlaceholder={tx('Search mix designs…')}
              options={(mixes.data?.data ?? []).map((m) => ({ value: m.id, label: m.name, code: m.bomCode, hint: m.productName ?? undefined }))} />
          </Field>
          <Field label={tx('Basis')}>
            <OptionalSelect value={basis} onChange={(v) => setBasis((v || 'units') as ScaleBasis)} placeholder={tx('Basis')} options={toOptions(BASIS_LABEL)} />
          </Field>
          <Field label={tx('Value *')} error={value !== '' && !positive(value) ? tx('Enter a positive number') : undefined}>
            <Input inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} />
          </Field>
          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <input type="checkbox" checked={wholeBatches} onChange={(e) => setWholeBatches(e.target.checked)} /> {tx('Whole batches')}
          </label>
        </div>
        {preview.isError && <p className="text-sm text-destructive">{apiMessage(preview.error, tx('Could not scale the mix design'))}</p>}
        {preview.data && mixDesignId && positive(value) && (
          <div className={preview.isFetching ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
            <ScalingSummary scaling={preview.data} />
          </div>
        )}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Production location')}><ClearableLocation value={locationId} onChange={setLocationId} /></Field>
          <Field label={tx('Output location')}><ClearableLocation value={outputLocationId} onChange={setOutputLocationId} /></Field>
          <Field label={tx('Scheduled for')}><Input type="date" value={scheduledFor} onChange={(e) => setScheduledFor(e.target.value)} /></Field>
          <Field label={tx('Batch number')}><Input value={batchNumber} onChange={(e) => setBatchNumber(e.target.value)} /></Field>
          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <input type="checkbox" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} /> {tx('Confirm the order now')}
          </label>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending}>{tx('Create order')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default PrecastOrdersPage;
