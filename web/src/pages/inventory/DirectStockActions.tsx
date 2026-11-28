import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Minus, Plus, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { PRODUCT_PICKER_PANEL, SearchableSelect } from '@/components/ui/searchable-select';
import { useStaffOptions } from '@/features/inventory/staff-options';
import { locationOption, useStockLocations } from '@/features/inventory/locations';
import { useProductSearch } from '@/features/products/product-search';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { notifyApiError } from '@/lib/api-error';
import { useIdempotencyKey } from '@/lib/idempotency';
import { formatMoney } from '@/lib/format';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { VariantSelect } from '@/features/products/variant-select';

type InLine = { uid: string; productId: string; variantId?: string | null; name: string; quantity: number; unitCost: string; zeroCostReason?: string; batchNumber: string; expiryDate: string };
type OutLine = { uid: string; productId: string; variantId?: string | null; name: string; quantity: number; distStrategy: string; batchNumber: string };

let lineSeq = 0;
const lineId = () => `line-${++lineSeq}`;
/** A cost typed as 0 (not left blank): the backend needs a reason and the valuation-manage permission. */
const isZeroCost = (v: string) => v.trim() !== '' && Number(v) === 0;
const blankIn = (): InLine => ({ uid: lineId(), productId: '', name: '', quantity: 1, unitCost: '', batchNumber: '', expiryDate: '' });
const blankOut = (): OutLine => ({ uid: lineId(), productId: '', name: '', quantity: 1, distStrategy: 'FEFO', batchNumber: '' });

interface DirectStockActionsProps {
  /** Extra query keys to invalidate after a successful post (page-specific lists). */
  invalidateKeys?: string[];
}

/**
 * The "Direct Stock In" / "Direct Stock Out" buttons and their dialogs, shared
 * by every page that needs to move stock ad hoc (Stock Levels, Stock Ledger).
 * Owns its own queries, form state and posting — the host page only renders it.
 */
export function DirectStockActions({ invalidateKeys = [] }: DirectStockActionsProps) {
  // Both routes need `inventory:move`: without it the buttons are not offered at all.
  const has = useAuthStore((s) => s.hasPermission);
  const canMove = has(PERMISSIONS.inventory.move);
  const [inOpen, setInOpen] = useState(false);
  const [outOpen, setOutOpen] = useState(false);
  const dialogOpen = inOpen || outOpen;

  // Every location (the default page held only the 25 newest), loaded when a dialog opens.
  const locations = useStockLocations({ enabled: canMove && dialogOpen });
  const locationOptions = useMemo(() => (locations.locations ?? []).map(locationOption), [locations.locations]);

  const staffOptionsQuery = useStaffOptions();
  const staffOptions = staffOptionsQuery.data ?? [];

  // Searched on the server: loading one page of products left the rest unpickable.
  const products = useProductSearch<{ id: string; code: string; name: string }>({ enabled: canMove && dialogOpen });
  const productPicker = {
    options: products.options,
    onQueryChange: products.onQueryChange,
    loading: products.loading,
    footer: products.footer,
    placeholder: tx('Select product…'),
    searchPlaceholder: tx('Search by code or name…'),
    emptyText: tx('No products match'),
    contentClassName: PRODUCT_PICKER_PANEL,
    wrap: true,
  };

  const qc = useQueryClient();
  // A named approver other than the signed-in user must prove the approval
  // with their override PIN; the server rejects a bare approver id.
  const myId = useAuthStore((s) => s.user?.id) ?? '';

  const invSettings = useQuery<{ key: string; value: unknown }[]>({
    queryKey: ['settings-effective', 'inventory'],
    queryFn: async () => (await api.get('/settings/effective?group=inventory')).data,
    enabled: canMove,
    staleTime: 60_000,
  });
  const settingOn = (key: string) => Boolean(invSettings.data?.find((s) => s.key === key)?.value);
  const inApprovalNeeded = settingOn('inventory.stockInApprovalNeeded');
  const outApprovalNeeded = settingOn('inventory.stockOutApprovalNeeded');

  /** Refresh every list a stock movement can change, plus the host page's own. */
  const invalidateAll = () => {
    for (const key of ['inventory-product-stock-levels', 'inventory-stats', 'inventory-ledger', ...invalidateKeys]) {
      qc.invalidateQueries({ queryKey: [key] });
    }
  };

  // — Direct Stock In state
  const [inLocId, setInLocId] = useState('');
  const [inNotes, setInNotes] = useState('');
  const [inResponsibleId, setInResponsibleId] = useState('');
  const [inApprovedId, setInApprovedId] = useState('');
  const [inApproverPin, setInApproverPin] = useState('');
  const [inLines, setInLines] = useState<InLine[]>(() => [blankIn()]);

  // Direct stock moves stock without a document: the server requires an Idempotency-Key (one per action).
  const inKey = useIdempotencyKey();
  const outKey = useIdempotencyKey();

  const directIn = useMutation({
    mutationFn: async () => {
      const items = inLines
        .filter((l) => l.productId && l.quantity > 0)
        .map((l) => ({
          productId: l.productId,
          ...(l.variantId ? { variantId: l.variantId } : {}),
          quantity: l.quantity,
          ...(l.unitCost ? { unitCost: Number(l.unitCost) } : {}),
          // IPR D3: an explicit 0 cost needs a reason; a blank cost uses the current cost.
          ...(isZeroCost(l.unitCost) && l.zeroCostReason?.trim() ? { zeroCostReason: l.zeroCostReason.trim() } : {}),
          ...(l.batchNumber ? { batchNumber: l.batchNumber } : {}),
          ...(l.expiryDate ? { expiryDate: l.expiryDate } : {}),
        }));
      const res = await api.post('/inventory/direct-stock/in', {
        locationId: inLocId,
        responsibleById: inResponsibleId,
        approvedById: inApprovedId || undefined,
        ...(inApprovedId && inApprovedId !== myId && inApproverPin ? { approverPin: inApproverPin } : {}),
        items,
        notes: inNotes || undefined,
      }, inKey.config());
      return res.data;
    },
    onSuccess: (data: any) => {
      inKey.settle();
      notify.success(tx('Stock in {{code}} completed', { code: data.code }));
      setInOpen(false);
      setInLocId('');
      setInNotes('');
      setInResponsibleId('');
      setInApprovedId('');
      setInApproverPin('');
      setInLines([blankIn()]);
      invalidateAll();
    },
    onError: (e: unknown) => { inKey.settle(e); notifyApiError(e, tx('Stock in failed')); },
  });

  // — Direct Stock Out state
  const [outLocId, setOutLocId] = useState('');
  const [outNotes, setOutNotes] = useState('');
  const [outResponsibleId, setOutResponsibleId] = useState('');
  const [outApprovedId, setOutApprovedId] = useState('');
  const [outApproverPin, setOutApproverPin] = useState('');
  const [outLines, setOutLines] = useState<OutLine[]>(() => [blankOut()]);

  const directOut = useMutation({
    mutationFn: async () => {
      const items = outLines
        .filter((l) => l.productId && l.quantity > 0)
        .map((l) => ({
          productId: l.productId,
          ...(l.variantId ? { variantId: l.variantId } : {}),
          quantity: l.quantity,
          distStrategy: l.distStrategy,
          ...(l.batchNumber ? { batchNumber: l.batchNumber } : {}),
        }));
      const res = await api.post('/inventory/direct-stock/out', {
        locationId: outLocId,
        responsibleById: outResponsibleId,
        approvedById: outApprovedId || undefined,
        ...(outApprovedId && outApprovedId !== myId && outApproverPin ? { approverPin: outApproverPin } : {}),
        items,
        notes: outNotes || undefined,
      }, outKey.config());
      return res.data;
    },
    onSuccess: (data: any) => {
      outKey.settle();
      notify.success(tx('Stock out {{code}} completed', { code: data.code }));
      setOutOpen(false);
      setOutLocId('');
      setOutNotes('');
      setOutResponsibleId('');
      setOutApprovedId('');
      setOutApproverPin('');
      setOutLines([blankOut()]);
      invalidateAll();
    },
    onError: (e: unknown) => { outKey.settle(e); notifyApiError(e, tx('Stock out failed')); },
  });

  const updateIn = (uid: string, patch: Partial<InLine>) =>
    setInLines((cur) => cur.map((x) => (x.uid === uid ? { ...x, ...patch } : x)));
  const updateOut = (uid: string, patch: Partial<OutLine>) =>
    setOutLines((cur) => cur.map((x) => (x.uid === uid ? { ...x, ...patch } : x)));

  const pickInProduct = (uid: string, productId: string) => {
    const p = products.get(productId);
    if (p) updateIn(uid, { productId, variantId: null, name: p.name });
  };

  const pickOutProduct = (uid: string, productId: string) => {
    const p = products.get(productId);
    if (p) updateOut(uid, { productId, variantId: null, name: p.name });
  };

  // Footer figures and the Manual-batch guard, so each dialog states what it is
  // about to do before the button is pressed.
  const inFilled = inLines.filter((l) => l.productId && l.quantity > 0);
  const inFilledCount = inFilled.length;
  const inTotalQty = inFilled.reduce((n, l) => n + l.quantity, 0);
  const inTotalValue = inFilled.reduce((n, l) => n + (l.unitCost ? Number(l.unitCost) * l.quantity : 0), 0);

  const outFilled = outLines.filter((l) => l.productId && l.quantity > 0);
  const outFilledCount = outFilled.length;
  const outTotalQty = outFilled.reduce((n, l) => n + l.quantity, 0);
  const outMissingBatch = outFilled.filter((l) => l.distStrategy === 'MANUAL' && !l.batchNumber.trim()).length;

  if (!canMove) return null;

  return (
    <>
      <div className="flex items-center gap-2">
        <Button size="sm" onClick={() => setInOpen(true)}>
          <Plus className="mr-1 h-3 w-3" />{tx('Direct Stock In')}
        </Button>
        <Button size="sm" variant="secondary" onClick={() => setOutOpen(true)}>
          <Minus className="mr-1 h-3 w-3" />{tx('Direct Stock Out')}
        </Button>
      </div>

    {/* Direct Stock In Dialog */}
    <Dialog open={inOpen} onOpenChange={setInOpen}>
      <DialogContent className="w-[calc(100vw-2rem)] max-w-[1120px] max-h-[92vh] gap-0 overflow-hidden p-0">
        <div className="bg-[#3c8dbc] px-6 py-4 text-white">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-lg text-white">
              <Plus className="h-5 w-5" /> {tx('Direct Stock In')}
            </DialogTitle>
          </DialogHeader>
          <p className="mt-1 text-sm text-white/80">
            {tx('Receive stock straight into a location. Posts an audited movement, valued at the cost you enter — leave cost blank to use the item\'s current average.')}
          </p>
        </div>

        <div className="max-h-[calc(92vh-16rem)] space-y-4 overflow-y-auto p-6">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Location')} <span className="text-destructive">*</span>
              </label>
              <SearchableSelect
                value={inLocId}
                onValueChange={setInLocId}
                options={locationOptions}
                footer={locations.footer}
                placeholder={tx('Select location…')}
                searchPlaceholder={tx('Search locations…')}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Responsible Person')} <span className="text-destructive">*</span>
              </label>
              <SearchableSelect
                value={inResponsibleId}
                onValueChange={setInResponsibleId}
                options={staffOptions}
                placeholder={tx('Select staff…')}
                searchPlaceholder={tx('Search staff…')}
                emptyText={tx('No staff match')}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Approved By')} <span className="text-destructive">*</span>
              </label>
              <SearchableSelect
                value={inApprovedId}
                onValueChange={setInApprovedId}
                options={staffOptions}
                placeholder={tx('Select staff…')}
                searchPlaceholder={tx('Search staff…')}
                emptyText={tx('No staff match')}
              />
              {inApprovedId && inApprovedId !== myId && (
                <Input type="password" inputMode="numeric" autoComplete="off" placeholder={tx('Approver PIN')} value={inApproverPin} onChange={(e) => setInApproverPin(e.target.value)} />
              )}
            </div>
            <div className="space-y-1.5 md:col-span-3">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Notes')} <span className="font-normal normal-case tracking-normal">{tx('(optional)')}</span>
              </label>
              <Input placeholder={tx('Reference, supplier, reason…')} value={inNotes} onChange={(e) => setInNotes(e.target.value)} />
            </div>
          </div>

          <div className="overflow-hidden rounded-lg border">
            <table className="w-full table-fixed text-sm">
              <colgroup>
                <col className="w-[30%]" />
                <col className="w-[11%]" />
                <col className="w-[15%]" />
                <col className="w-[15%]" />
                <col className="w-[13%]" />
                <col className="w-[16%]" />
                <col className="w-[52px]" />
              </colgroup>
              <thead>
                <tr className="border-b bg-slate-50 text-left text-xs uppercase tracking-wider text-slate-600">
                  <th className="px-3 py-2.5 font-semibold">{tx('Product')}</th>
                  <th className="px-3 py-2.5 text-right font-semibold">{tx('Qty')}</th>
                  <th className="px-3 py-2.5 text-right font-semibold">{tx('Unit Cost')}</th>
                  <th className="px-3 py-2.5 text-right font-semibold">{tx('Line Total')}</th>
                  <th className="px-3 py-2.5 font-semibold">{tx('Batch #')}</th>
                  <th className="px-3 py-2.5 font-semibold">{tx('Expiry')}</th>
                  <th className="px-3 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {inLines.map((line) => (
                  <tr key={line.uid} className="border-b align-middle last:border-0">
                    <td className="px-2 py-2">
                      <SearchableSelect
                        {...productPicker}
                        value={line.productId}
                        onValueChange={(v) => pickInProduct(line.uid, v)}
                        selectedLabel={products.labelOf(line.productId) ?? line.name}
                      />
                      <VariantSelect
                        productId={line.productId || null}
                        value={line.variantId}
                        onChange={(v) => updateIn(line.uid, { variantId: v })}
                      />
                    </td>
                    <td className="px-2 py-2">
                      <Input type="number" min={1} className="text-right tabular-nums" value={line.quantity}
                        onChange={(e) => updateIn(line.uid, { quantity: Number(e.target.value) })} />
                    </td>
                    <td className="px-2 py-2">
                      <Input type="number" step="0.01" min={0} className="text-right tabular-nums" placeholder={tx('Avg cost')} value={line.unitCost}
                        onChange={(e) => updateIn(line.uid, { unitCost: e.target.value })} />
                      {isZeroCost(line.unitCost) && (
                        <Input className="mt-1" placeholder={tx('Reason for zero cost')} maxLength={500}
                          aria-label={tx('Reason for zero cost')} value={line.zeroCostReason ?? ''}
                          onChange={(e) => updateIn(line.uid, { zeroCostReason: e.target.value })} />
                      )}
                    </td>
                    <td className="px-3 py-2 text-right font-mono text-xs tabular-nums text-slate-600">
                      {line.unitCost && line.quantity > 0
                        ? formatMoney(Number(line.unitCost) * line.quantity)
                        : <span className="text-slate-300">—</span>}
                    </td>
                    <td className="px-2 py-2">
                      <Input placeholder={tx('Optional')} value={line.batchNumber} onChange={(e) => updateIn(line.uid, { batchNumber: e.target.value })} />
                    </td>
                    <td className="px-2 py-2">
                      <Input type="date" value={line.expiryDate} onChange={(e) => updateIn(line.uid, { expiryDate: e.target.value })} />
                    </td>
                    <td className="px-2 py-2 text-center">
                      <Button variant="ghost" size="icon" className="h-8 w-8 text-slate-400 hover:text-destructive" disabled={inLines.length <= 1} title={tx('Remove line')} onClick={() => setInLines((prev) => prev.filter((x) => x.uid !== line.uid))}>
                        <XCircle className="h-4 w-4" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="border-t bg-slate-50/60 px-3 py-2">
              <Button variant="outline" size="sm" onClick={() => setInLines((prev) => [...prev, blankIn()])}>
                <Plus className="mr-1 h-3 w-3" />{tx('Add Item')}
              </Button>
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t bg-slate-50 px-6 py-3">
          <div className="text-xs text-muted-foreground">
            {inFilledCount} {tx('item')}{inFilledCount !== 1 ? 's' : ''} · {inTotalQty} {tx('unit')}{inTotalQty !== 1 ? 's' : ''}
            {inTotalValue > 0 && (
              <> {tx('· valued')} <span className="font-semibold text-slate-700">{formatMoney(inTotalValue)}</span></>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setInOpen(false)}>{tx('Cancel')}</Button>
            <Button className="bg-[#3c8dbc] hover:bg-[#367fa9]" disabled={!inLocId || !inResponsibleId || (inApprovalNeeded && !inApprovedId) || (inApprovalNeeded && inApprovedId !== myId && !inApproverPin) || inFilledCount === 0 || directIn.isPending} onClick={() => directIn.mutate()}>
              {directIn.isPending ? tx('Processing…') : tx('Complete Stock In')}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>

    {/* Direct Stock Out Dialog */}
    <Dialog open={outOpen} onOpenChange={setOutOpen}>
      <DialogContent className="w-[calc(100vw-2rem)] max-w-[1000px] max-h-[92vh] gap-0 overflow-hidden p-0">
        <div className="bg-rose-600 px-6 py-4 text-white">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-lg text-white">
              <Minus className="h-5 w-5" /> {tx('Direct Stock Out')}
            </DialogTitle>
          </DialogHeader>
          <p className="mt-1 text-sm text-white/80">
            {tx('Issue stock out of a location. FEFO and FIFO pick the batch for you; Manual needs the batch number. Cost comes from the batch, never from this form.')}
          </p>
        </div>

        <div className="max-h-[calc(92vh-16rem)] space-y-4 overflow-y-auto p-6">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Location')} <span className="text-destructive">*</span>
              </label>
              <SearchableSelect
                value={outLocId}
                onValueChange={setOutLocId}
                options={locationOptions}
                footer={locations.footer}
                placeholder={tx('Select location…')}
                searchPlaceholder={tx('Search locations…')}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Responsible Person')} <span className="text-destructive">*</span>
              </label>
              <SearchableSelect
                value={outResponsibleId}
                onValueChange={setOutResponsibleId}
                options={staffOptions}
                placeholder={tx('Select staff…')}
                searchPlaceholder={tx('Search staff…')}
                emptyText={tx('No staff match')}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Approved By')} <span className="text-destructive">*</span>
              </label>
              <SearchableSelect
                value={outApprovedId}
                onValueChange={setOutApprovedId}
                options={staffOptions}
                placeholder={tx('Select staff…')}
                searchPlaceholder={tx('Search staff…')}
                emptyText={tx('No staff match')}
              />
              {outApprovedId && outApprovedId !== myId && (
                <Input type="password" inputMode="numeric" autoComplete="off" placeholder={tx('Approver PIN')} value={outApproverPin} onChange={(e) => setOutApproverPin(e.target.value)} />
              )}
            </div>
            <div className="space-y-1.5 md:col-span-3">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {tx('Notes')} <span className="font-normal normal-case tracking-normal">{tx('(optional)')}</span>
              </label>
              <Input placeholder={tx('Reason, department, reference…')} value={outNotes} onChange={(e) => setOutNotes(e.target.value)} />
            </div>
          </div>

          <div className="overflow-hidden rounded-lg border">
            <table className="w-full table-fixed text-sm">
              <colgroup>
                <col className="w-[38%]" />
                <col className="w-[13%]" />
                <col className="w-[20%]" />
                <col className="w-[23%]" />
                <col className="w-[52px]" />
              </colgroup>
              <thead>
                <tr className="border-b bg-slate-50 text-left text-xs uppercase tracking-wider text-slate-600">
                  <th className="px-3 py-2.5 font-semibold">{tx('Product')}</th>
                  <th className="px-3 py-2.5 text-right font-semibold">{tx('Qty')}</th>
                  <th className="px-3 py-2.5 font-semibold">{tx('Strategy')}</th>
                  <th className="px-3 py-2.5 font-semibold">{tx('Batch #')}</th>
                  <th className="px-3 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {outLines.map((line) => (
                  <tr key={line.uid} className="border-b align-middle last:border-0">
                    <td className="px-2 py-2">
                      <SearchableSelect
                        {...productPicker}
                        value={line.productId}
                        onValueChange={(v) => pickOutProduct(line.uid, v)}
                        selectedLabel={products.labelOf(line.productId) ?? line.name}
                      />
                      <VariantSelect
                        productId={line.productId || null}
                        value={line.variantId}
                        onChange={(v) => updateOut(line.uid, { variantId: v })}
                      />
                    </td>
                    <td className="px-2 py-2">
                      <Input type="number" min={1} className="text-right tabular-nums" value={line.quantity}
                        onChange={(e) => updateOut(line.uid, { quantity: Number(e.target.value) })} />
                    </td>
                    <td className="px-2 py-2">
                      <Select value={line.distStrategy} onValueChange={(v) => updateOut(line.uid, {
                        distStrategy: v, batchNumber: v !== 'MANUAL' ? '' : line.batchNumber,
                      })}>
                        <SelectTrigger className="min-w-0"><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="FEFO">{tx('FEFO — earliest expiry')}</SelectItem>
                          <SelectItem value="FIFO">{tx('FIFO — oldest first')}</SelectItem>
                          <SelectItem value="MANUAL">{tx('Manual batch')}</SelectItem>
                        </SelectContent>
                      </Select>
                    </td>
                    <td className="px-2 py-2">
                      <Input
                        placeholder={line.distStrategy === 'MANUAL' ? tx('Required') : tx('Auto-picked')}
                        disabled={line.distStrategy !== 'MANUAL'}
                        value={line.batchNumber}
                        onChange={(e) => updateOut(line.uid, { batchNumber: e.target.value })}
                      />
                    </td>
                    <td className="px-2 py-2 text-center">
                      <Button variant="ghost" size="icon" className="h-8 w-8 text-slate-400 hover:text-destructive" disabled={outLines.length <= 1} title={tx('Remove line')} onClick={() => setOutLines((prev) => prev.filter((x) => x.uid !== line.uid))}>
                        <XCircle className="h-4 w-4" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="border-t bg-slate-50/60 px-3 py-2">
              <Button variant="outline" size="sm" onClick={() => setOutLines((prev) => [...prev, blankOut()])}>
                <Plus className="mr-1 h-3 w-3" />{tx('Add Item')}
              </Button>
            </div>
          </div>

          {outMissingBatch > 0 && (
            <p className="text-xs text-destructive">
              {outMissingBatch} {tx('line')}{outMissingBatch !== 1 ? 's' : ''} {tx('set to Manual still need a batch number.')}
            </p>
          )}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t bg-slate-50 px-6 py-3">
          <div className="text-xs text-muted-foreground">
            {outFilledCount} {tx('item')}{outFilledCount !== 1 ? 's' : ''} · {outTotalQty} {tx('unit')}{outTotalQty !== 1 ? 's' : ''} {tx('leaving stock')}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setOutOpen(false)}>{tx('Cancel')}</Button>
            <Button className="bg-rose-600 hover:bg-rose-700" disabled={!outLocId || !outResponsibleId || (outApprovalNeeded && !outApprovedId) || (outApprovalNeeded && outApprovedId !== myId && !outApproverPin) || outFilledCount === 0 || outMissingBatch > 0 || directOut.isPending} onClick={() => directOut.mutate()}>
              {directOut.isPending ? tx('Processing…') : tx('Complete Stock Out')}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  </>
  );
}
