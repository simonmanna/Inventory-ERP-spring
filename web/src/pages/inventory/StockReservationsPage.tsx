import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bookmark } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { fetchAllStockLevels, fmtQty } from '@/features/inventory/stock-levels';

interface Reservation {
  id: string;
  productId: string;
  productCode: string | null;
  productName: string | null;
  locationId: string;
  locationCode: string | null;
  locationName: string | null;
  quantity: number | string;
  onHand: number | string;
  status: string;
  sourceType: string;
  sourceId: string;
  reason: string | null;
  createdAt: string;
}

interface Paged {
  data: Reservation[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

const FILTERS = { status: '', sourceType: '', locationId: '' };
const SELECT = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
const TONE: Record<string, string> = {
  active: 'bg-emerald-100 text-emerald-800',
  released: 'bg-muted text-muted-foreground',
  consumed: 'bg-sky-100 text-sky-800',
};

function useLocations() {
  return useQuery({
    queryKey: ['inventory-locations-options'],
    queryFn: async () =>
      (await api.get<{ data: { id: string; code: string; name: string }[] }>('/inventory/locations')).data.data ?? [],
  });
}

/** Soft stock reservations: what is held, for which document, and how much is left to promise (INV-1). */
export function StockReservationsPage() {
  const list = useListState(FILTERS);
  const qc = useQueryClient();
  const canMove = useAuthStore((s) => s.hasPermission('inventory:move'));
  const [creating, setCreating] = useState(false);
  const { data: locations } = useLocations();
  const { data, isLoading, isError } = useQuery({
    queryKey: ['inventory-reservations', list.page, list.pageSize, list.search, list.filters],
    queryFn: async () =>
      (
        await api.get<Paged>('/inventory/reservations/overview', {
          params: { page: list.page, pageSize: list.pageSize, q: list.search || undefined, ...list.filters },
        })
      ).data,
  });
  const close = useMutation({
    mutationFn: async ({ action, r }: { action: 'release' | 'consume'; r: Reservation }) =>
      (await api.post(`/inventory/reservations/${action}`, { sourceType: r.sourceType, sourceId: r.sourceId })).data,
    onSuccess: () => {
      notify.success(tx('Reservation updated'));
      qc.invalidateQueries({ queryKey: ['inventory-reservations'] });
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('Could not update the reservation'))),
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const columns: Column<Reservation>[] = [
    { key: 'product', header: tx('Product'), render: (r) => <span className="font-medium">{r.productCode} {r.productName}</span> },
    { key: 'location', header: tx('Location'), render: (r) => r.locationName ?? '—' },
    { key: 'qty', header: tx('Reserved'), className: 'text-right', render: (r) => <span className="tabular-nums">{fmtQty(Number(r.quantity))}</span> },
    { key: 'hand', header: tx('On hand'), className: 'text-right', render: (r) => <span className="tabular-nums">{fmtQty(Number(r.onHand))}</span> },
    { key: 'source', header: tx('Source'), render: (r) => `${r.sourceType} · ${r.sourceId}` },
    { key: 'reason', header: tx('Reason'), render: (r) => r.reason ?? '—' },
    { key: 'when', header: tx('Created'), render: (r) => <span className="whitespace-nowrap">{dateTime(r.createdAt)}</span> },
    { key: 'status', header: tx('Status'), render: (r) => <Badge className={TONE[r.status]}>{r.status}</Badge> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (r) => canMove && r.status === 'active' && (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="outline" disabled={close.isPending} onClick={() => close.mutate({ action: 'release', r })}>{tx('Release')}</Button>
          <Button size="sm" variant="ghost" disabled={close.isPending} onClick={() => close.mutate({ action: 'consume', r })}>{tx('Consume')}</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Bookmark} title={tx('Stock Reservations')}
        description={tx('Stock held for a document. Releasing or consuming acts on every reservation of that document.')}
        actions={canMove ? <Button onClick={() => setCreating(true)}>{tx('New reservation')}</Button> : undefined} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Product, source, reason…')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)}
          options={['active', 'released', 'consumed'].map((s) => ({ value: s, label: s }))} allLabel={tx('All statuses')} />
        <FilterSelect value={list.filters.locationId} onChange={(v) => list.setFilter('locationId', v)}
          options={(locations ?? []).map((l) => ({ value: l.id, label: l.name }))} allLabel={tx('All locations')} />
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(r) => r.id}
            cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the reservations. Try again.') : tx('No reservations.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="reservation" />
      )}
      {creating && <NewReservation locations={locations ?? []} onClose={() => setCreating(false)} />}
    </div>
  );
}

function NewReservation({ locations, onClose }: {
  locations: { id: string; code: string; name: string }[];
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [locationId, setLocationId] = useState('');
  const [productId, setProductId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [sourceId, setSourceId] = useState('');
  const [reason, setReason] = useState('');
  const { data: products } = useQuery({
    queryKey: ['inventory-reservation-products', locationId],
    enabled: !!locationId,
    queryFn: () => fetchAllStockLevels(locationId),
  });
  const { data: atp } = useQuery({
    queryKey: ['inventory-atp', productId, locationId],
    enabled: !!productId && !!locationId,
    queryFn: async () =>
      (
        await api.get<{ onHand: number; reserved: number; available: number }>('/inventory/reservations/atp', {
          params: { productId, locationId },
        })
      ).data,
  });
  const over = !!atp && Number(quantity) > Number(atp.available);
  const create = useMutation({
    mutationFn: async () =>
      (
        await api.post('/inventory/reservations', {
          productId, locationId, quantity: Number(quantity), sourceType: 'manual', sourceId, reason: reason || undefined,
        })
      ).data,
    onSuccess: () => {
      notify.success(tx('Reservation created'));
      qc.invalidateQueries({ queryKey: ['inventory-reservations'] });
      onClose();
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('Could not create the reservation'))),
  });

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('New reservation')}</DialogTitle>
          <DialogDescription>{tx('Reserving again for the same reference and product replaces the quantity.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label>{tx('Location')}</Label>
            <select className={SELECT} value={locationId} onChange={(e) => { setLocationId(e.target.value); setProductId(''); }}>
              <option value="">{tx('Select…')}</option>
              {locations.map((l) => <option key={l.id} value={l.id}>{l.code} — {l.name}</option>)}
            </select>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Product')}</Label>
            <select className={SELECT} value={productId} disabled={!locationId} onChange={(e) => setProductId(e.target.value)}>
              <option value="">{tx('Select…')}</option>
              {(products ?? []).map((p) => <option key={p.id} value={p.id}>{p.code} — {p.name}</option>)}
            </select>
          </div>
          {atp ? (
            <p className="rounded-md bg-muted/50 p-2 text-sm">
              {tx('On hand')}: {fmtQty(Number(atp.onHand))} · {tx('Reserved')}: {fmtQty(Number(atp.reserved))} ·{' '}
              <span className="font-medium">{tx('Available')}: {fmtQty(Number(atp.available))}</span>
            </p>
          ) : null}
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Quantity')}</Label>
              <Input type="number" min={0} value={quantity} onChange={(e) => setQuantity(e.target.value)} />
              {over ? <p className="text-xs text-amber-700">{tx('More than is available to promise.')}</p> : null}
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Reference')}</Label>
              <Input value={sourceId} onChange={(e) => setSourceId(e.target.value)} />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Reason')}</Label>
            <Input value={reason} onChange={(e) => setReason(e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={create.isPending || !productId || !locationId || !sourceId.trim() || !(Number(quantity) > 0)} onClick={() => create.mutate()}>
            {tx('Create')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
