import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { RefreshCw, Repeat } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, ListCard, ListPageHeader, ListToolbar, FilterSelect } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useProducts } from '@/features/products/api';
import { fmtQty } from '@/features/inventory/stock-levels';

interface Rule {
  id: string;
  productId: string;
  productCode: string;
  productName: string;
  locationId: string;
  locationName: string;
  minQty: number | string;
  maxQty: number | string | null;
  reorderQty: number | string | null;
  supplierName: string | null;
  leadTimeDays: number;
  autoRequisition: boolean;
  isActive: boolean;
}

interface Alert {
  id: string;
  productCode: string;
  productName: string;
  locationName: string;
  onHand: number | string;
  reserved: number | string;
  available: number | string;
  minQty: number | string;
  suggestedQty: number | string;
  supplierName: string | null;
  status: string;
  requestNumber: string | null;
  statusReason: string | null;
  createdAt: string;
}

interface Paged<T> {
  data: T[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

const SELECT = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
const TONE: Record<string, string> = {
  open: 'bg-amber-100 text-amber-800',
  requested: 'bg-sky-100 text-sky-800',
  requisitioned: 'bg-emerald-100 text-emerald-800',
  resolved: 'bg-muted text-muted-foreground',
  dismissed: 'bg-muted text-muted-foreground',
};
const KEY = ['inventory-replenishment'];
const qty = (v: number | string | null) => (v === null ? '—' : fmtQty(Number(v)));

/** Rules that watch stock levels, the alerts they raise, and requisitions from those alerts (INV-3). */
export function ReplenishmentPage() {
  const [tab, setTab] = useState('alerts');
  const qc = useQueryClient();
  const canEdit = useAuthStore((s) => s.hasPermission('inventory:replenish'));
  const scan = useMutation({
    mutationFn: async () =>
      (await api.post<{ raised: number; resolved: number }>('/inventory/replenishment/scan')).data,
    onSuccess: (r) => {
      notify.success(tx('{{raised}} raised, {{resolved}} resolved', { raised: r.raised, resolved: r.resolved }));
      qc.invalidateQueries({ queryKey: KEY });
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('The action failed'))),
  });

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Repeat} title={tx('Replenishment')}
        description={tx('Available stock is on hand less reservations. Scans run every hour.')}
        actions={canEdit ? (
          <Button variant="outline" disabled={scan.isPending} onClick={() => scan.mutate()}>
            <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Scan now')}
          </Button>
        ) : undefined} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="alerts">{tx('Alerts')}</TabsTrigger>
          <TabsTrigger value="rules">{tx('Rules')}</TabsTrigger>
        </TabsList>
        <TabsContent value="alerts" className="mt-4 space-y-4"><AlertsTab canEdit={canEdit} /></TabsContent>
        <TabsContent value="rules" className="mt-4 space-y-4"><RulesTab canEdit={canEdit} /></TabsContent>
      </Tabs>
    </div>
  );
}

function AlertsTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState('live');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [selected, setSelected] = useState<string[]>([]);
  const [dismissing, setDismissing] = useState<Alert | null>(null);
  const { data, isLoading, isError } = useQuery({
    queryKey: [...KEY, 'alerts', status, page, pageSize],
    queryFn: async () =>
      (await api.get<Paged<Alert>>('/inventory/replenishment/alerts', { params: { status: status === 'all' ? undefined : status, page, pageSize } })).data,
  });
  const requisition = useMutation({
    mutationFn: async () => (await api.post('/inventory/replenishment/requisition', { alertIds: selected })).data,
    onSuccess: () => {
      notify.success(tx('Requisition requested'));
      setSelected([]);
      qc.invalidateQueries({ queryKey: KEY });
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('Could not request the requisition'))),
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;
  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const columns: Column<Alert>[] = [
    {
      key: 'pick', header: '',
      render: (a) => canEdit && a.status === 'open' && (
        <input type="checkbox" aria-label={tx('Select')} checked={selected.includes(a.id)} onChange={() => toggle(a.id)} />
      ),
    },
    { key: 'product', header: tx('Product'), render: (a) => <span className="font-medium">{a.productCode} {a.productName}</span> },
    { key: 'location', header: tx('Location'), render: (a) => a.locationName },
    { key: 'available', header: tx('Available'), className: 'text-right', render: (a) => <span className="tabular-nums">{qty(a.available)}</span> },
    { key: 'min', header: tx('Minimum'), className: 'text-right', render: (a) => <span className="tabular-nums">{qty(a.minQty)}</span> },
    { key: 'suggested', header: tx('Suggested'), className: 'text-right', render: (a) => <span className="font-medium tabular-nums">{qty(a.suggestedQty)}</span> },
    { key: 'supplier', header: tx('Supplier'), render: (a) => a.supplierName ?? '—' },
    { key: 'when', header: tx('Raised'), render: (a) => <span className="whitespace-nowrap">{dateTime(a.createdAt)}</span> },
    {
      key: 'status', header: tx('Status'),
      render: (a) => (
        <span className="flex flex-wrap items-center gap-1">
          <Badge className={TONE[a.status]}>{a.status}</Badge>
          {a.requestNumber ? <Link className="text-primary hover:underline" to="/purchase-requests">{a.requestNumber}</Link> : null}
        </span>
      ),
    },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (a) => canEdit && a.status === 'open' && (
        <Button size="sm" variant="ghost" onClick={() => setDismissing(a)}>{tx('Dismiss')}</Button>
      ),
    },
  ];

  return (
    <>
      <ListToolbar>
        <FilterSelect value={status === 'all' ? '' : status} onChange={(v) => { setStatus(v || 'all'); setPage(1); }}
          options={['live', 'resolved', 'dismissed'].map((s) => ({ value: s, label: s }))} allLabel={tx('All')} />
        {canEdit ? (
          <Button size="sm" disabled={!selected.length || requisition.isPending} onClick={() => requisition.mutate()}>
            {tx('Raise requisition')} {selected.length ? `(${selected.length})` : ''}
          </Button>
        ) : null}
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(a) => a.id}
            cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the alerts. Try again.') : tx('No alerts. Everything is above its minimum.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={setPage} onPageSizeChange={setPageSize} noun="alert" />
      )}
      {dismissing && <DismissDialog alert={dismissing} onClose={() => setDismissing(null)} />}
    </>
  );
}

function DismissDialog({ alert, onClose }: { alert: Alert; onClose: () => void }) {
  const qc = useQueryClient();
  const [reason, setReason] = useState('');
  const dismiss = useMutation({
    mutationFn: async () => (await api.post(`/inventory/replenishment/alerts/${alert.id}/dismiss`, { reason })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
      onClose();
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('The action failed'))),
  });
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Dismiss alert')}</DialogTitle>
          <DialogDescription>{tx('The rule stays quiet until stock recovers and falls again.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-1.5">
          <Label>{tx('Reason')}</Label>
          <Input value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!reason.trim() || dismiss.isPending} onClick={() => dismiss.mutate()}>{tx('Dismiss')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RulesTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isError } = useQuery({
    queryKey: [...KEY, 'rules', page, pageSize],
    queryFn: async () => (await api.get<Paged<Rule>>('/inventory/replenishment/rules', { params: { page, pageSize } })).data,
  });
  const remove = useMutation({
    mutationFn: async (id: string) => (await api.delete(`/inventory/replenishment/rules/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
    onError: (err) => notify.error(apiErrorMessage(err, tx('The action failed'))),
  });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const columns: Column<Rule>[] = [
    { key: 'product', header: tx('Product'), render: (r) => <span className="font-medium">{r.productCode} {r.productName}</span> },
    { key: 'location', header: tx('Location'), render: (r) => r.locationName },
    { key: 'min', header: tx('Minimum'), className: 'text-right', render: (r) => qty(r.minQty) },
    { key: 'max', header: tx('Maximum'), className: 'text-right', render: (r) => qty(r.maxQty) },
    { key: 'reorder', header: tx('Reorder qty'), className: 'text-right', render: (r) => qty(r.reorderQty) },
    { key: 'supplier', header: tx('Supplier'), render: (r) => r.supplierName ?? '—' },
    { key: 'lead', header: tx('Lead time (days)'), className: 'text-right', render: (r) => r.leadTimeDays },
    { key: 'auto', header: tx('Automatic'), render: (r) => (r.autoRequisition ? tx('Yes') : tx('No')) },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (r) => canEdit && <Button size="sm" variant="ghost" onClick={() => remove.mutate(r.id)}>{tx('Delete')}</Button>,
    },
  ];

  return (
    <>
      {canEdit ? <div className="flex justify-end"><Button onClick={() => setCreating(true)}>{tx('New rule')}</Button></div> : null}
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(r) => r.id}
            cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the rules. Try again.') : tx('No replenishment rules yet.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={setPage} onPageSizeChange={setPageSize} noun="rule" />
      )}
      {creating && <RuleDialog onClose={() => setCreating(false)} />}
    </>
  );
}

function RuleDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const [search, setSearch] = useState('');
  const { data: products } = useProducts({ page: 1, pageSize: 50, search: search || undefined });
  const { data: suppliers } = useQuery({
    queryKey: ['replenishment-suppliers'],
    queryFn: async () =>
      (await api.get<{ data: { id: string; name: string }[] }>('/partners', { params: { page: 1, pageSize: 200, isSupplier: true } })).data,
  });
  const { data: locations } = useQuery({
    queryKey: ['inventory-locations-options'],
    queryFn: async () =>
      (await api.get<{ data: { id: string; code: string; name: string }[] }>('/inventory/locations')).data.data ?? [],
  });
  const [form, setForm] = useState({
    productId: '', locationId: '', minQty: '', maxQty: '', reorderQty: '', supplierId: '', leadTimeDays: '0', autoRequisition: false,
  });
  const set = (k: string, v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const min = Number(form.minQty);
  const invalid = !form.productId || !form.locationId || !(min > 0) || (!!form.maxQty && Number(form.maxQty) < min);
  const create = useMutation({
    mutationFn: async () =>
      (
        await api.post('/inventory/replenishment/rules', {
          productId: form.productId,
          locationId: form.locationId,
          minQty: min,
          maxQty: form.maxQty ? Number(form.maxQty) : undefined,
          reorderQty: form.reorderQty ? Number(form.reorderQty) : undefined,
          supplierId: form.supplierId || undefined,
          leadTimeDays: Number(form.leadTimeDays || 0),
          autoRequisition: form.autoRequisition,
        })
      ).data,
    onSuccess: () => {
      notify.success(tx('Rule created'));
      qc.invalidateQueries({ queryKey: KEY });
      onClose();
    },
    onError: (err) => notify.error(apiErrorMessage(err, tx('Could not create the rule'))),
  });

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('New rule')}</DialogTitle>
          <DialogDescription>{tx('With a maximum, the order fills up to it; otherwise it is the reorder quantity or the shortfall.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label>{tx('Product')}</Label>
            <Input placeholder={tx('Search')} value={search} onChange={(e) => setSearch(e.target.value)} />
            <select className={SELECT} value={form.productId} onChange={(e) => set('productId', e.target.value)}>
              <option value="">{tx('Select…')}</option>
              {(products?.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.code} — {p.name}</option>)}
            </select>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Location')}</Label>
            <select className={SELECT} value={form.locationId} onChange={(e) => set('locationId', e.target.value)}>
              <option value="">{tx('Select…')}</option>
              {(locations ?? []).map((l) => <option key={l.id} value={l.id}>{l.code} — {l.name}</option>)}
            </select>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Minimum')}</Label>
              <Input type="number" min={0} value={form.minQty} onChange={(e) => set('minQty', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Maximum')}</Label>
              <Input type="number" min={0} value={form.maxQty} onChange={(e) => set('maxQty', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Reorder qty')}</Label>
              <Input type="number" min={0} value={form.reorderQty} onChange={(e) => set('reorderQty', e.target.value)} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Supplier')}</Label>
              <select className={SELECT} value={form.supplierId} onChange={(e) => set('supplierId', e.target.value)}>
                <option value="">{tx('The product\'s supplier')}</option>
                {(suppliers?.data ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Lead time (days)')}</Label>
              <Input type="number" min={0} value={form.leadTimeDays} onChange={(e) => set('leadTimeDays', e.target.value)} />
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.autoRequisition} onChange={(e) => set('autoRequisition', e.target.checked)} />
            {tx('Raise the purchase request automatically')}
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={create.isPending || invalid} onClick={() => create.mutate()}>{tx('Create')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
