import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClipboardList, FileInput, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput,
  dateRangeLabel, useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DO_STATUS_LABEL, SOURCE_TYPE_LABEL, useCreateFromSource, useCustomerSites, useDeliveryOrders, useDispatchLookups,
  useDispatchSources, type DeliveryOrder,
} from '@/features/fleet/dispatch-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';
import { DeliveryOrderStatusPill } from './dispatch-ui';

const FILTERS = { status: '', sourceType: '', from: '', to: '' };
const STATUS_FILTER: Record<string, string> = { active: tx('Active'), ...DO_STATUS_LABEL };

/** Delivery orders (fleet 1.3): filters by status, source and requested date; create by hand or from a sale. */
export function DeliveryOrdersPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { status, sourceType, from, to } = list.filters;
  const [fromSource, setFromSource] = useState(false);
  const { data, isLoading, isFetching, isError } = useDeliveryOrders({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    sourceType: sourceType || undefined, dateFrom: from || undefined, dateTo: to || undefined,
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(sourceType ? [{ key: 's', label: SOURCE_TYPE_LABEL[sourceType as keyof typeof SOURCE_TYPE_LABEL] ?? sourceType, onRemove: () => list.setFilter('sourceType', '') }] : []),
    ...(from || to ? [{ key: 'd', label: dateRangeLabel(from, to), onRemove: () => list.setFilters({ from: '', to: '' }) }] : []),
  ];

  const columns: Column<DeliveryOrder>[] = [
    { key: 'code', header: tx('Order'), render: (o) => <span className="font-medium text-primary">{o.code}</span> },
    { key: 'date', header: tx('Requested'), render: (o) => <span className="whitespace-nowrap">{date(o.requestedDate)}{o.windowFrom ? ` ${o.windowFrom}–${o.windowTo ?? ''}` : ''}</span> },
    { key: 'customer', header: tx('Customer'), render: (o) => o.partnerName },
    { key: 'site', header: tx('Site'), render: (o) => o.siteName ?? '—' },
    { key: 'source', header: tx('Source'), render: (o) => (o.sourceNumber ? `${SOURCE_TYPE_LABEL[o.sourceType]} ${o.sourceNumber}` : SOURCE_TYPE_LABEL[o.sourceType]) },
    { key: 'trips', header: tx('Trips'), className: 'text-right', render: (o) => <span className="tabular-nums">{o.tripsDone}/{o.tripCount}</span> },
    { key: 'unplanned', header: tx('To plan'), className: 'text-right', render: (o) => <span className="tabular-nums">{num(o.unplannedQty, 2)}</span> },
    { key: 'status', header: tx('Status'), render: (o) => <DeliveryOrderStatusPill status={o.status} /> },
  ];
  const empty = isError ? tx('Could not load the delivery orders. Try again.') : tx('No delivery orders match these filters.');

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={ClipboardList}
        title={tx('Delivery Orders')}
        description={tx('Deliveries to customer sites from sales orders, invoices or entered by hand.')}
        actions={has(PERMISSIONS.fleetDispatch.create) && (
          <>
            <Button variant="outline" onClick={() => setFromSource(true)}><FileInput className="mr-1.5 h-4 w-4" /> {tx('From sales order / invoice')}</Button>
            <Button onClick={() => navigate('/fleet/delivery-orders/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New delivery order')}</Button>
          </>
        )}
      />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search order, customer, site…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(STATUS_FILTER)} allLabel={tx('All statuses')} />
        <FilterSelect value={sourceType} onChange={(v) => list.setFilter('sourceType', v)} options={toOptions(SOURCE_TYPE_LABEL)} allLabel={tx('All sources')} />
        <DateRangeFilter from={from} to={to} onChange={(f, t) => list.setFilters({ from: f, to: t })} />
      </ListToolbar>

      <div className="space-y-2 sm:hidden">
        {isLoading && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">…</div>}
        {!isLoading && rows.length === 0 && <div className="rounded-xl border bg-card p-4 text-center text-sm text-muted-foreground">{empty}</div>}
        {rows.map((o) => (
          <button key={o.id} type="button" onClick={() => navigate(`/fleet/delivery-orders/${o.id}`)} className="w-full rounded-xl border bg-card p-3 text-left shadow-sm active:bg-accent">
            <div className="flex items-center justify-between gap-2"><span className="font-medium">{o.code}</span><DeliveryOrderStatusPill status={o.status} /></div>
            <div className="mt-1 text-xs text-muted-foreground">{o.partnerName}{o.siteName ? ` · ${o.siteName}` : ''} · {date(o.requestedDate)}</div>
          </button>
        ))}
      </div>
      <div className="hidden sm:block">
        <ListCard>
          <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
            <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={8} getRowId={(o) => o.id}
              onRowClick={(o) => navigate(`/fleet/delivery-orders/${o.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10" emptyMessage={empty} />
          </div>
        </ListCard>
      </div>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="delivery order" />
      )}
      <FromSourceDialog open={fromSource} onClose={() => setFromSource(false)} />
    </div>
  );
}

/** Pick a confirmed sales order or a sales invoice, a site and a yard, and create its delivery order. */
function FromSourceDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const [type, setType] = useState<'sales_order' | 'invoice'>('sales_order');
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<{ id: string; partnerId: string | null; number: string } | null>(null);
  const [siteId, setSiteId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [requestedDate, setRequestedDate] = useState('');
  const sources = useDispatchSources(type, search, open);
  const lookups = useDispatchLookups();
  const sites = useCustomerSites({ page: 1, pageSize: 200, partnerId: picked?.partnerId ?? undefined }, !!picked?.partnerId);
  const create = useCreateFromSource();

  const submit = async () => {
    if (!picked) return;
    try {
      const out = await create.mutateAsync({ type, body: {
        sourceId: picked.id, siteId: siteId || undefined, locationId: locationId || undefined,
        requestedDate: requestedDate || undefined,
      } });
      notify.success(tx('Delivery order {{code}} created', { code: out.code }));
      onClose();
      navigate(`/fleet/delivery-orders/${out.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the delivery order')));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Delivery order from a sale')}</DialogTitle>
          <DialogDescription>{tx('Confirmed back-office sales orders and sales invoices without a delivery order. POS sales are not listed.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-2">
            <Button type="button" variant={type === 'sales_order' ? 'default' : 'outline'} onClick={() => { setType('sales_order'); setPicked(null); }}>{tx('Sales order')}</Button>
            <Button type="button" variant={type === 'invoice' ? 'default' : 'outline'} onClick={() => { setType('invoice'); setPicked(null); }}>{tx('Invoice')}</Button>
          </div>
          <Input placeholder={tx('Search number or customer…')} value={search} onChange={(e) => setSearch(e.target.value)} />
          <div className="max-h-56 overflow-y-auto rounded-md border text-sm">
            {sources.isLoading && <div className="p-3 text-muted-foreground">…</div>}
            {(sources.data ?? []).length === 0 && !sources.isLoading && <div className="p-3 text-muted-foreground">{tx('Nothing to deliver.')}</div>}
            {(sources.data ?? []).map((s) => (
              <button key={s.id} type="button" onClick={() => { setPicked({ id: s.id, partnerId: s.partnerId, number: s.number }); setSiteId(''); }}
                className={`flex w-full items-center justify-between gap-2 px-3 py-2 text-left hover:bg-accent ${picked?.id === s.id ? 'bg-accent' : ''}`}>
                <span><span className="font-medium">{s.number}</span> · {s.partnerName}</span>
                <span className="text-xs text-muted-foreground">{s.status}{s.total ? ` · ${money(s.total)}` : ''}</span>
              </button>
            ))}
          </div>
          {picked && (
            <>
              <Field label={tx('Customer site')}>
                <OptionalSelect value={siteId} onChange={setSiteId} noneLabel={tx('No site')} options={(sites.data?.data ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` }))} placeholder={tx('No site')} />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label={tx('Yard / warehouse')}>
                  <OptionalSelect value={locationId} onChange={setLocationId} noneLabel={tx('Default')} options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: l.name }))} placeholder={tx('Default')} />
                </Field>
                <Field label={tx('Requested date')}>
                  <Input type="date" value={requestedDate} onChange={(e) => setRequestedDate(e.target.value)} />
                </Field>
              </div>
            </>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!picked || create.isPending} onClick={submit}>{tx('Create delivery order')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
