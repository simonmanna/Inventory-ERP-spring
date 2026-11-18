import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Plus, Trash2, Save, Undo2, Truck, PackageCheck, CornerUpLeft, X } from 'lucide-react';
import { ReasonDialog } from '@/features/inventory/reason-dialog';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { PRODUCT_PICKER_PANEL, SearchableSelect } from '@/components/ui/searchable-select';
import { ConfirmDialog } from '@/components/confirm-dialog';
import { DataTablePagination } from '@/components/list/data-table-pagination';
import { useStaffOptions } from '@/features/inventory/staff-options';
import { locationOption, useStockLocations } from '@/features/inventory/locations';
import { useProductSearch } from '@/features/products/product-search';
import { describeApiError } from '@/lib/api-error';
import { dateTime } from '@/lib/format';
import { subtractQty } from '@/lib/quantity';
import { useClientPage } from '@/lib/use-client-page';
import {
  useTransfers, useCreateTransfer, useApproveTransfer, useReverseTransfer,
  useDispatchTransfer, useReceiveTransfer, useRecallTransfer, useCancelTransfer,
  type StockTransfer,
} from '@/features/inventory/transfers-api';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { VariantSelect } from '@/features/products/variant-select';

interface Product { id: string; code: string; name: string }

interface TransferLine {
  /** Stable row identity: React keys must survive removing a line above. */
  uid: string;
  productId: string;
  /** ADR-S058: variant of a product with variants. */
  variantId?: string | null;
  productName: string;
  quantity: number;
  distStrategy: string;
}

const STATUS_COLORS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-700',
  pending: 'bg-amber-100 text-amber-700',
  completed: 'bg-green-100 text-green-700',
  approved: 'bg-blue-100 text-blue-700',
  in_transit: 'bg-indigo-100 text-indigo-700',
  partially_received: 'bg-orange-100 text-orange-700',
  cancelled: 'bg-red-100 text-red-700',
  reversed: 'bg-slate-200 text-slate-700',
};

const STATUS_LABEL: Record<string, string> = {
  in_transit: tx('in transit'),
  partially_received: tx('partially received'),
};

const num = (v: unknown) => Number(v ?? 0);
/** Still in transit, exact to the server's 6 decimals (plain float subtraction prefilled 0.20000000000000107). */
const outstanding = (it: StockTransfer['items'][number]) =>
  subtractQty(it.qtyDispatched, it.qtyReceived, it.qtyDamaged, it.qtyShort, it.qtyRecalled);

type ReceiveRow = { itemId: string; productName: string; open: number; received: string; damaged: string; short: string };

let lineSeq = 0;
const newLine = (): TransferLine => ({ uid: `line-${++lineSeq}`, productId: '', productName: '', quantity: 1, distStrategy: 'FEFO' });

const STATUS_FILTERS = ['pending', 'approved', 'in_transit', 'partially_received', 'completed', 'cancelled', 'reversed'];

export function StockTransfersPage() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [fromLocId, setFromLocId] = useState('');
  const [toLocId, setToLocId] = useState('');
  const [notes, setNotes] = useState('');
  const [mode, setMode] = useState<'immediate' | 'transit'>('immediate');
  const [responsibleId, setResponsibleId] = useState('');
  const [approvedId, setApprovedId] = useState('');
  const [lines, setLines] = useState<TransferLine[]>(() => [newLine()]);
  const [statusFilter, setStatusFilter] = useState('');
  const [confirming, setConfirming] = useState<{ action: 'approve' | 'cancel'; transfer: StockTransfer } | null>(null);

  const { data: transfers, isLoading } = useTransfers(statusFilter || undefined);
  const paged = useClientPage(transfers, 25, statusFilter);
  const createTransfer = useCreateTransfer();
  const approveTransfer = useApproveTransfer();
  const reverseTransfer = useReverseTransfer();
  const [reversing, setReversing] = useState<{ id: string; code: string } | null>(null);
  const dispatchTransfer = useDispatchTransfer();
  const receiveTransfer = useReceiveTransfer();
  const recallTransfer = useRecallTransfer();
  const cancelTransfer = useCancelTransfer();
  const [recalling, setRecalling] = useState<{ id: string; code: string } | null>(null);
  const [receiving, setReceiving] = useState<{ id: string; code: string; rows: ReceiveRow[] } | null>(null);
  const [receiveNotes, setReceiveNotes] = useState('');
  const has = useAuthStore((s) => s.hasPermission);
  const canCreate = has('inventory_doc:create');
  const canApprove = has('inventory_doc:approve');
  const canMove = has('inventory_doc:update');

  const openReceive = (tr: StockTransfer) => {
    setReceiveNotes('');
    receiveTransfer.reset();
    setReceiving({
      id: tr.id,
      code: tr.transferCode,
      rows: tr.items
        .map((it) => ({ itemId: it.id, productName: it.productName, open: outstanding(it), received: '', damaged: '', short: '' }))
        .filter((r) => r.open > 0)
        .map((r) => ({ ...r, received: String(r.open) })),
    });
  };
  const receiveInvalid = receiving?.rows.some((r) => {
    const total = num(r.received) + num(r.damaged) + num(r.short);
    return [r.received, r.damaged, r.short].some((v) => num(v) < 0) || total > r.open + 1e-6;
  }) ?? false;
  const receiveEmpty = receiving ? receiving.rows.every((r) => num(r.received) + num(r.damaged) + num(r.short) === 0) : true;

  const staffOptionsQuery = useStaffOptions();
  const staffOptions = staffOptionsQuery.data ?? [];

  // Searched on the server: a single page of 200 products left the rest unpickable.
  const products = useProductSearch<Product>({ enabled: showForm });
  const locations = useStockLocations();

  const locMap = new Map(locations.locations?.map((l) => [l.id, l]));
  // The transit location is system-managed: never a pickable source or destination.
  const locationOptions = (locations.locations ?? []).filter((l) => l.type !== 'transit').map(locationOption);

  const updateLine = (uid: string, patch: Partial<TransferLine>) =>
    setLines((cur) => cur.map((x) => (x.uid === uid ? { ...x, ...patch } : x)));

  const pickProduct = (uid: string, productId: string) => {
    const p = products.get(productId);
    if (!p) return;
    updateLine(uid, { productId, variantId: null, productName: p.name });
  };

  const confirmTransfer = () => {
    if (!confirming) return;
    const done = { onSuccess: () => setConfirming(null) };
    if (confirming.action === 'approve') approveTransfer.mutate(confirming.transfer.id, done);
    else cancelTransfer.mutate(confirming.transfer.id, done);
  };

  const handleSubmit = () => {
    const items = lines
      .filter((l) => l.productId && l.quantity > 0)
      .map((l) => ({
        productId: l.productId,
        ...(l.variantId ? { variantId: l.variantId } : {}),
        qtyRequested: l.quantity,
        distStrategy: l.distStrategy,
      }));
    createTransfer.mutate(
      { fromLocationId: fromLocId, toLocationId: toLocId, responsibleById: responsibleId, approvedById: approvedId, mode, items, notes: notes || undefined },
      {
        onSuccess: () => {
          setShowForm(false);
          setFromLocId('');
          setToLocId('');
          setNotes('');
          setMode('immediate');
          setResponsibleId('');
          setApprovedId('');
          setLines([newLine()]);
          qc.invalidateQueries({ queryKey: ['inventory-product-stock-levels'] });
          qc.invalidateQueries({ queryKey: ['inventory-stats'] });
        },
      },
    );
  };

  const canSubmit = fromLocId && toLocId && fromLocId !== toLocId && responsibleId && approvedId && lines.some((l) => l.productId && l.quantity > 0) && !createTransfer.isPending;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Stock Transfers')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Move stock between locations — immediately on the same premises, or via dispatch and receipt between branches')}</p>
        </div>
        {canCreate && (
          <Button onClick={() => { createTransfer.reset(); setShowForm(true); }}>
            <Plus className="mr-2 h-4 w-4" />{tx('New Transfer')}
          </Button>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="h-9 w-48" aria-label={tx('Status')}><SelectValue placeholder={tx('All statuses')} /></SelectTrigger>
          <SelectContent>
            <SelectItem value="">{tx('All statuses')}</SelectItem>
            {STATUS_FILTERS.map((s) => <SelectItem key={s} value={s}>{STATUS_LABEL[s] ?? s}</SelectItem>)}
          </SelectContent>
        </Select>
        {paged.totalPages > 1 && (
          <p className="text-xs text-muted-foreground">
            {tx('All {{count}} transfers are loaded at once. Filter by status to narrow the list.', { count: paged.total.toLocaleString() })}
          </p>
        )}
      </div>

      {isLoading && <Skeleton className="h-48 w-full" />}
      {transfers && transfers.length === 0 && (
        <Card><CardContent className="p-8 text-center text-muted-foreground">
          {statusFilter ? tx('No transfers with this status.') : tx('No transfers yet. Click "New Transfer" to create one.')}
        </CardContent></Card>
      )}
      {transfers && transfers.length > 0 && (
        <div className="rounded-md border overflow-x-auto">
          <table className="w-full min-w-[720px] text-sm">
            <thead>
              <tr className="border-b bg-muted/50">
                <th scope="col" className="px-3 py-2 text-left font-medium">{tx('Transfer #')}</th>
                <th className="px-3 py-2 text-left font-medium">{tx('Date')}</th>
                <th className="px-3 py-2 text-left font-medium">{tx('From → To')}</th>
                <th className="px-3 py-2 text-left font-medium">{tx('Mode')}</th>
                <th className="px-3 py-2 text-right font-medium">{tx('Items')}</th>
                <th className="px-3 py-2 text-left font-medium">{tx('Status')}</th>
                <th className="px-3 py-2 text-left font-medium">{tx('Notes')}</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody>
              {paged.pageRows.map((tr) => {
                const fromLoc = locMap.get(tr.fromLocId);
                const toLoc = locMap.get(tr.toLocId);
                return (
                  <tr key={tr.id} className="border-b hover:bg-muted/30">
                    <td className="px-3 py-2 font-mono text-xs font-medium">{tr.transferCode}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{dateTime(tr.createdAt)}</td>
                    <td className="px-3 py-2">
                      {fromLoc?.code ?? '—'} → {toLoc?.code ?? '—'}
                    </td>
                    <td className="px-3 py-2 text-xs text-muted-foreground">{tr.mode === 'transit' ? tx('Transit') : tx('Immediate')}</td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums">{tr.items?.length ?? 0}</td>
                    <td className="px-3 py-2">
                      <span className={`inline-flex px-2.5 py-0.5 rounded text-xs font-semibold whitespace-nowrap ${STATUS_COLORS[tr.status] ?? 'bg-gray-100 text-gray-700'}`}>
                        {STATUS_LABEL[tr.status] ?? tr.status}
                      </span>
                    </td>
                    <td className="px-3 py-2 max-w-48 truncate text-muted-foreground">{tr.notes ?? '—'}</td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap justify-end gap-1">
                      {tr.status === 'pending' && canApprove && (
                        <Button size="sm" variant="outline" onClick={() => setConfirming({ action: 'approve', transfer: tr })} disabled={approveTransfer.isPending}>
                          {tx('Approve')}
                        </Button>
                      )}
                      {tr.status === 'approved' && tr.mode === 'transit' && canMove && (
                        <Button size="sm" variant="outline" onClick={() => dispatchTransfer.mutate(tr.id)} disabled={dispatchTransfer.isPending}>
                          <Truck className="mr-1 h-3.5 w-3.5" />{dispatchTransfer.isPending ? tx('Dispatching…') : tx('Dispatch')}
                        </Button>
                      )}
                      {(tr.status === 'pending' || (tr.status === 'approved' && tr.mode === 'transit')) && canApprove && (
                        <Button size="sm" variant="ghost" aria-label={tx('Cancel {{transferCode}}', { transferCode: tr.transferCode })} onClick={() => setConfirming({ action: 'cancel', transfer: tr })} disabled={cancelTransfer.isPending}>
                          <X className="mr-1 h-3.5 w-3.5" />{tx('Cancel')}
                        </Button>
                      )}
                      {(tr.status === 'in_transit' || tr.status === 'partially_received') && canMove && (
                        <Button size="sm" variant="outline" onClick={() => openReceive(tr)}>
                          <PackageCheck className="mr-1 h-3.5 w-3.5" />{tx('Receive')}
                        </Button>
                      )}
                      {(tr.status === 'in_transit' || tr.status === 'partially_received') && canApprove && (
                        <Button size="sm" variant="ghost" onClick={() => setRecalling({ id: tr.id, code: tr.transferCode })} disabled={recallTransfer.isPending}>
                          <CornerUpLeft className="mr-1 h-3.5 w-3.5" />{tx('Recall')}
                        </Button>
                      )}
                      {tr.status === 'completed' && canApprove && (
                        <Button size="sm" variant="ghost" title={tx('Reverse posted transfer')}
                          onClick={() => setReversing({ id: tr.id, code: tr.transferCode })} disabled={reverseTransfer.isPending}>
                          <Undo2 className="mr-1 h-3.5 w-3.5" />{tx('Reverse')}
                        </Button>
                      )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {paged.totalPages > 1 && (
            <DataTablePagination
              page={paged.page} pageSize={paged.pageSize} total={paged.total} totalPages={paged.totalPages}
              onPageChange={paged.setPage} onPageSizeChange={paged.setPageSize} pageSizeOptions={[25, 50, 100]}
              noun={tx('transfer')}
            />
          )}
        </div>
      )}

      <Dialog open={showForm} onOpenChange={setShowForm}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          <DialogHeader><DialogTitle>{tx('New Stock Transfer')}</DialogTitle></DialogHeader>
          <div className="space-y-4">
            <fieldset>
              <legend className="text-sm font-medium">{tx('Transfer type')}</legend>
              <div className="mt-1 grid gap-2 sm:grid-cols-2">
                {([
                  ['immediate', tx('Immediate'), tx('Same premises. Stock moves the moment it is approved.')],
                  ['transit', tx('Branch (in transit)'), tx('Approve, dispatch, then the destination receives — with damage and shortage recorded.')],
                ] as const).map(([value, label, hint]) => (
                  <label key={value} className={`flex cursor-pointer gap-2 rounded-md border p-2 text-sm ${mode === value ? 'border-primary bg-primary/5' : ''}`}>
                    <input type="radio" name="transfer-mode" value={value} checked={mode === value} onChange={() => setMode(value)} className="mt-1" />
                    <span><span className="font-medium">{label}</span><span className="block text-xs text-muted-foreground">{hint}</span></span>
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="text-sm font-medium">{tx('From Location')}</label>
                <SearchableSelect
                  value={fromLocId}
                  onValueChange={setFromLocId}
                  options={locationOptions}
                  footer={locations.footer}
                  placeholder={tx('Select source')}
                  searchPlaceholder={tx('Search locations…')}
                />
              </div>
              <div>
                <label className="text-sm font-medium">{tx('To Location')}</label>
                <SearchableSelect
                  value={toLocId}
                  onValueChange={setToLocId}
                  options={locationOptions}
                  footer={locations.footer}
                  placeholder={tx('Select destination')}
                  searchPlaceholder={tx('Search locations…')}
                />
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="text-sm font-medium">{tx('Responsible Person')} <span className="text-destructive">*</span></label>
                <SearchableSelect
                  value={responsibleId}
                  onValueChange={setResponsibleId}
                  options={staffOptions}
                  placeholder={tx('Select staff…')}
                  searchPlaceholder={tx('Search staff…')}
                  emptyText={tx('No staff match')}
                />
              </div>
              <div>
                <label className="text-sm font-medium">{tx('Approved By')} <span className="text-destructive">*</span></label>
                <SearchableSelect
                  value={approvedId}
                  onValueChange={setApprovedId}
                  options={staffOptions}
                  placeholder={tx('Select staff…')}
                  searchPlaceholder={tx('Search staff…')}
                  emptyText={tx('No staff match')}
                />
              </div>
            </div>
            {fromLocId && toLocId && fromLocId === toLocId && (
              <p className="text-sm text-destructive">{tx('Source and destination must be different')}</p>
            )}

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium">{tx('Items')}</label>
                <Button size="sm" variant="outline" onClick={() => setLines([...lines, newLine()])}>
                  <Plus className="mr-1 h-3 w-3" />{tx('Add Item')}
                </Button>
              </div>
              <div className="rounded-md border overflow-x-auto">
                <table className="w-full min-w-[420px] text-sm">
                  <thead>
                    <tr className="border-b bg-muted/50">
                      <th className="px-2 py-1 text-left font-medium">{tx('Product')}</th>
                      <th className="px-2 py-1 text-right font-medium">{tx('Quantity')}</th>
                      <th className="px-2 py-1 font-medium">{tx('Strategy')}</th>
                      <th className="w-8"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((ln) => (
                      <tr key={ln.uid} className="border-b">
                        <td className="px-2 py-1">
                          <SearchableSelect
                            className="h-7 py-0.5"
                            value={ln.productId}
                            onValueChange={(v) => pickProduct(ln.uid, v)}
                            options={products.options}
                            onQueryChange={products.onQueryChange}
                            loading={products.loading}
                            footer={products.footer}
                            selectedLabel={products.labelOf(ln.productId) ?? ln.productName}
                            placeholder={tx('Select product…')}
                            searchPlaceholder={tx('Search by code or name…')}
                            contentClassName={PRODUCT_PICKER_PANEL}
                            wrap
                          />
                          <VariantSelect
                            productId={ln.productId || null}
                            value={ln.variantId}
                            onChange={(v) => updateLine(ln.uid, { variantId: v })}
                          />
                        </td>
                        <td className="px-2 py-1">
                          <Input
                            type="number" min="1" className="h-7 w-24 text-right"
                            value={ln.quantity}
                            onChange={(e) => updateLine(ln.uid, { quantity: Number(e.target.value) })}
                          />
                        </td>
                        <td className="px-2 py-1">
                          <Select value={ln.distStrategy} onValueChange={(v) => updateLine(ln.uid, { distStrategy: v })}>
                            <SelectTrigger className="h-7 w-24"><SelectValue /></SelectTrigger>
                            <SelectContent>
                              <SelectItem value="FEFO">{tx('FEFO')}</SelectItem>
                              <SelectItem value="FIFO">{tx('FIFO')}</SelectItem>
                              <SelectItem value="MANUAL">{tx('Manual')}</SelectItem>
                            </SelectContent>
                          </Select>
                        </td>
                        <td className="px-2 py-1">
                          <Button size="icon" variant="ghost" className="h-6 w-6" onClick={() => setLines(lines.filter((x) => x.uid !== ln.uid))}>
                            <Trash2 className="h-3 w-3 text-destructive" />
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div>
              <label className="text-sm font-medium">{tx('Notes')}</label>
              <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} placeholder={tx('Optional transfer notes')} rows={2} />
            </div>
            {createTransfer.isError && (
              <p role="alert" className="text-sm text-destructive">{describeApiError(createTransfer.error, tx('Failed to create transfer'))}</p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowForm(false)}>{tx('Cancel')}</Button>
            <Button onClick={handleSubmit} disabled={!canSubmit}>
              <Save className="mr-2 h-4 w-4" />{createTransfer.isPending ? tx('Creating…') : tx('Create Transfer')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!confirming}
        onOpenChange={(o) => { if (!o) setConfirming(null); }}
        title={confirming?.action === 'cancel'
          ? tx('Cancel {{transferCode}}?', { transferCode: confirming.transfer.transferCode })
          : tx('Approve {{transferCode}}?', { transferCode: confirming?.transfer.transferCode ?? '' })}
        description={<p>{confirming?.action === 'cancel'
          ? tx('The transfer is closed without moving any stock. A cancelled transfer cannot be reopened.')
          : confirming?.transfer.mode === 'transit'
            ? tx('The transfer can then be dispatched. Stock leaves the source location when it is dispatched.')
            : tx('Stock moves from {{from}} to {{to}} as soon as it is approved. Undoing it later needs a reversal.', {
              from: locMap.get(confirming?.transfer.fromLocId ?? '')?.code ?? '—',
              to: locMap.get(confirming?.transfer.toLocId ?? '')?.code ?? '—',
            })}</p>}
        confirmLabel={confirming?.action === 'cancel' ? tx('Cancel transfer') : tx('Approve transfer')}
        pendingLabel={confirming?.action === 'cancel' ? tx('Cancelling…') : tx('Approving…')}
        destructive={confirming?.action === 'cancel'}
        pending={approveTransfer.isPending || cancelTransfer.isPending}
        onConfirm={confirmTransfer}
      />

      <ReasonDialog
        open={!!reversing}
        onOpenChange={(o) => { if (!o) setReversing(null); }}
        title={tx('Reverse {{v1}}', { v1: reversing?.code ?? '' })}
        description={<p>{tx('Moves the transferred quantities back to the source location today. It fails if the destination no longer holds them.')}</p>}
        confirmLabel={tx('Reverse transfer')}
        pendingLabel={tx('Reversing…')}
        destructive
        pending={reverseTransfer.isPending}
        onConfirm={(reason) => reversing && reverseTransfer.mutate({ id: reversing.id, reason }, { onSuccess: () => setReversing(null) })}
      />

      <ReasonDialog
        open={!!recalling}
        onOpenChange={(o) => { if (!o) setRecalling(null); }}
        title={tx('Recall {{v1}}', { v1: recalling?.code ?? '' })}
        description={<p>{tx('Returns everything still in transit to the source location. Quantities already received, damaged or short are not affected.')}</p>}
        confirmLabel={tx('Recall to source')}
        pendingLabel={tx('Recalling…')}
        destructive
        pending={recallTransfer.isPending}
        onConfirm={(reason) => recalling && recallTransfer.mutate({ id: recalling.id, reason }, { onSuccess: () => setRecalling(null) })}
      />

      <Dialog open={!!receiving} onOpenChange={(o) => { if (!o) setReceiving(null); }}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          <DialogHeader><DialogTitle>{tx('Receive')} {receiving?.code}</DialogTitle></DialogHeader>
          <p className="text-sm text-muted-foreground">
            {tx('Split what is still in transit into what arrived in good condition, what arrived damaged (written off as waste) and what never arrived (written off as a loss). Anything left blank stays in transit for a later receipt.')}
          </p>
          <div className="rounded-md border overflow-x-auto">
            <table className="w-full min-w-[520px] text-sm">
              <thead>
                <tr className="border-b bg-muted/50">
                  <th scope="col" className="px-2 py-1 text-left font-medium">{tx('Product')}</th>
                  <th scope="col" className="px-2 py-1 text-right font-medium">{tx('In transit')}</th>
                  <th scope="col" className="px-2 py-1 text-right font-medium">{tx('Received')}</th>
                  <th scope="col" className="px-2 py-1 text-right font-medium">{tx('Damaged')}</th>
                  <th scope="col" className="px-2 py-1 text-right font-medium">{tx('Short')}</th>
                </tr>
              </thead>
              <tbody>
                {receiving?.rows.map((r, idx) => {
                  const over = num(r.received) + num(r.damaged) + num(r.short) > r.open + 1e-6;
                  const set = (field: 'received' | 'damaged' | 'short', value: string) =>
                    setReceiving((cur) => cur && { ...cur, rows: cur.rows.map((x, i) => (i === idx ? { ...x, [field]: value } : x)) });
                  return (
                    <tr key={r.itemId} className="border-b">
                      <td className="px-2 py-1">
                        {r.productName}
                        {over && <span role="alert" className="block text-xs text-destructive">{tx('More than is in transit')}</span>}
                      </td>
                      <td className="px-2 py-1 text-right font-mono tabular-nums">{r.open}</td>
                      {(['received', 'damaged', 'short'] as const).map((field) => (
                        <td key={field} className="px-2 py-1">
                          <Input
                            type="number" min="0" step="any" inputMode="decimal"
                            aria-label={tx('{{field}} quantity for {{productName}}', { field, productName: r.productName })}
                            aria-invalid={over}
                            className="h-8 w-24 ml-auto text-right"
                            value={r[field]}
                            onChange={(e) => set(field, e.target.value)}
                          />
                        </td>
                      ))}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div>
            <label className="text-sm font-medium" htmlFor="receive-notes">{tx('Notes')}</label>
            <Textarea id="receive-notes" value={receiveNotes} onChange={(e) => setReceiveNotes(e.target.value)} placeholder={tx('Delivery note, driver, condition…')} rows={2} />
          </div>
          {receiveTransfer.isError && (
            <p role="alert" className="text-sm text-destructive">{describeApiError(receiveTransfer.error, tx('Failed to receive transfer'))}</p>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setReceiving(null)}>{tx('Cancel')}</Button>
            <Button
              disabled={receiveInvalid || receiveEmpty || receiveTransfer.isPending}
              onClick={() => receiving && receiveTransfer.mutate(
                {
                  id: receiving.id,
                  notes: receiveNotes || undefined,
                  lines: receiving.rows.map((r) => ({ itemId: r.itemId, received: num(r.received), damaged: num(r.damaged), short: num(r.short) })),
                },
                { onSuccess: () => setReceiving(null) },
              )}
            >
              <PackageCheck className="mr-2 h-4 w-4" />{receiveTransfer.isPending ? tx('Posting…') : tx('Post receipt')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
