import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Check, ClipboardCheck, Landmark, ListChecks, MessageSquareWarning, Plus, Trash2, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { ConfirmDialog } from '@/components/confirm-dialog';
import { ListPageHeader, StatusPill } from '@/components/list';
import { DataTablePagination } from '@/components/list/data-table-pagination';
import { useAuthStore } from '@/stores/auth.store';
import {
  CLAIM_STATUS_TONE,
  claimApi,
  poChangeApi,
  useClaimMutation,
  useClaimReceiptLines,
  useClaims,
  useCommitments,
  usePendingPoChanges,
  usePoChangeMutation,
  type ClaimClassification,
  type ClaimStatus,
  type ClaimStockAction,
  type ClaimType,
  type SupplierClaim,
} from '@/features/procurement/api';
import { useSupplierPicker } from '@/features/procurement/supplier-picker';
import { notifyApiError } from '@/lib/api-error';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const CLAIM_TYPES: Array<{ value: ClaimType; label: string }> = [
  { value: 'shortage', label: tx('Short delivery') },
  { value: 'damage', label: tx('Damaged goods') },
  { value: 'quality', label: tx('Quality reject') },
  { value: 'price', label: tx('Price difference') },
  { value: 'late_delivery', label: tx('Late delivery') },
  { value: 'other', label: tx('Other') },
];
const TYPE_LABEL = Object.fromEntries(CLAIM_TYPES.map((t) => [t.value, t.label])) as Record<ClaimType, string>;
const CLASSIFICATION_LABEL: Record<ClaimClassification, string> = {
  price_variance: tx('Price variance'),
  expense_correction: tx('Expense correction'),
  vat_adjustment: tx('VAT adjustment'),
  stock_adjustment: tx('Stock adjustment (verified quantities)'),
};
const CLASSIFICATION_HINT: Record<ClaimClassification, string> = {
  price_variance: tx('Credits purchase price variance on stock lines and the expense on non-stock lines. Stock quantities do not change.'),
  expense_correction: tx('Credits the expense account of the non-stock lines (services, consumables).'),
  vat_adjustment: tx('Reverses over-charged input VAT only. The amount is the VAT, at most the VAT on the linked bill.'),
  stock_adjustment: tx('Uses the verified quantity lines: goods leave stock at receipt cost (or reverse a write-off), against the payable or, for an unbilled receipt, GRNI.'),
};
/** Claim types that carry verified quantities (stock adjustment). */
const QUANTITY_TYPES: ClaimType[] = ['shortage', 'damage', 'quality'];
/** Classifications the server accepts per claim type. */
function classificationsFor(type: ClaimType): ClaimClassification[] {
  if (QUANTITY_TYPES.includes(type)) return ['price_variance', 'expense_correction', 'stock_adjustment'];
  if (type === 'price' || type === 'other') return ['price_variance', 'expense_correction', 'vat_adjustment'];
  return ['price_variance', 'expense_correction'];
}
const LINE_EDITABLE: ClaimStatus[] = ['open', 'submitted', 'accepted'];

const STATUS_LABEL: Record<ClaimStatus, string> = {
  open: tx('Open'),
  submitted: tx('Sent to supplier'),
  accepted: tx('Accepted'),
  rejected: tx('Rejected'),
  settled: tx('Settled'),
  cancelled: tx('Cancelled'),
};

/**
 * Supplier claims (ADR-PROCUREMENT-001 wave 5): raise a claim for a short, damaged, rejected, mispriced or late
 * delivery, send it, record the supplier's answer and settle it with a supplier credit (Dr AP / Cr expense or variance).
 */
export function SupplierClaimsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const userId = useAuthStore((s) => s.user?.id);
  const canManage = has(PERMISSIONS.supplierClaim.manage);
  const canVerify = has(PERMISSIONS.inspection.manage);
  const [status, setStatus] = useState('');
  // The list is paged on the server (25 a page by default); without a pager only page 1 was ever shown.
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const { data } = useClaims({ status, page, limit: pageSize });
  const suppliers = useSupplierPicker();
  const mutate = useClaimMutation((fn: () => Promise<unknown>) => fn());
  /** Every claim action shares one mutation: while one runs, the buttons that start another are disabled. */
  const busy = mutate.isPending;
  const [cancelling, setCancelling] = useState<SupplierClaim | null>(null);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ partnerId: '', type: 'shortage' as ClaimType, amount: '', description: '' });
  const [accepting, setAccepting] = useState<SupplierClaim | null>(null);
  const [agreed, setAgreed] = useState({ amount: '', response: '' });
  const [settling, setSettling] = useState<SupplierClaim | null>(null);
  const [classification, setClassification] = useState<ClaimClassification | ''>('');
  const [linesForId, setLinesForId] = useState<string | null>(null);
  const [lineForm, setLineForm] = useState({ goodsReceiptLineId: '', quantity: '', stockAction: 'issue' as ClaimStockAction, notes: '' });
  const rows = data?.data ?? [];
  const linesFor = rows.find((c) => c.id === linesForId) ?? null;
  const { data: receiptLines = [] } = useClaimReceiptLines(linesFor && LINE_EDITABLE.includes(linesFor.status) ? linesFor.id : null);

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await mutate.mutateAsync(fn);
      notify.success(ok);
      return true;
    } catch (e) {
      notifyApiError(e, tx('Action failed'));
      return false;
    }
  };

  const create = async () => {
    if (await run(() => claimApi.create({ ...form, amount: Number(form.amount) }), tx('Claim raised'))) {
      setOpen(false);
      setForm({ partnerId: '', type: 'shortage', amount: '', description: '' });
    }
  };

  const addLine = async () => {
    if (!linesFor) return;
    const body = { goodsReceiptLineId: lineForm.goodsReceiptLineId, quantity: Number(lineForm.quantity), stockAction: lineForm.stockAction, notes: lineForm.notes.trim() || undefined };
    if (await run(() => claimApi.addLine(linesFor.id, body), tx('Quantity recorded — a second user must verify it'))) {
      setLineForm({ goodsReceiptLineId: '', quantity: '', stockAction: 'issue', notes: '' });
    }
  };

  const settle = async () => {
    if (settling && await run(() => claimApi.settle(settling.id, classification || undefined), tx('Claim settled — supplier credit posted'))) {
      setSettling(null);
    }
  };

  const cancelClaim = async () => {
    if (cancelling && await run(() => claimApi.action(cancelling.id, 'cancel'), tx('Claim cancelled'))) {
      setCancelling(null);
    }
  };

  const reject = async (c: SupplierClaim) => {
    const response = window.prompt(tx("What did the supplier say?")) ?? '';
    if (response.trim()) await run(() => claimApi.reject(c.id, response), tx('Claim rejected'));
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Supplier claims')} icon={MessageSquareWarning}
        description={tx('Short, damaged, rejected or mispriced deliveries claimed back from suppliers and settled by a supplier credit that reduces what you owe.')}
        actions={canManage && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" />{tx('New claim')}</Button>} />
      <div className="flex gap-2">
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          <option value="">{tx('All statuses')}</option>
          {(Object.keys(STATUS_LABEL) as ClaimStatus[]).map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
        </select>
      </div>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Claim')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Type')}</TableHead>
              <TableHead>{tx('Order')}</TableHead><TableHead className="text-right">{tx('Amount')}</TableHead>
              <TableHead>{tx('Status')}</TableHead><TableHead>{tx('Credit entry')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={8} className="py-8 text-center text-muted-foreground">{tx('No claims.')}</TableCell></TableRow>}
              {rows.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    <div className="font-mono text-xs">{c.claimNumber}</div>
                    <div className="text-xs text-muted-foreground">{c.description}</div>
                  </TableCell>
                  <TableCell>{c.partnerName}</TableCell>
                  <TableCell>
                    <div>{TYPE_LABEL[c.type]}</div>
                    {c.classification && <div className="text-xs text-muted-foreground">{CLASSIFICATION_LABEL[c.classification]}</div>}
                  </TableCell>
                  <TableCell>{c.purchaseOrderId ? <Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${c.purchaseOrderId}`}>{c.orderNumber}</Link> : '—'}</TableCell>
                  <TableCell className="text-right">{money(c.amount)}</TableCell>
                  <TableCell><StatusPill tone={CLAIM_STATUS_TONE[c.status]}>{STATUS_LABEL[c.status]}</StatusPill></TableCell>
                  <TableCell className="font-mono text-xs">{c.journalEntryNumber ?? c.debitNoteNumber ?? '—'}</TableCell>
                  <TableCell className="space-x-1 whitespace-nowrap text-right">
                    {canManage && c.status === 'open' && (
                      <>
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => claimApi.action(c.id, 'submit'), tx('Claim sent'))}>{tx('Send')}</Button>
                        <Button size="sm" variant="ghost" disabled={busy} onClick={() => setCancelling(c)}>{tx('Cancel')}</Button>
                      </>
                    )}
                    {canManage && (c.status === 'open' || c.status === 'submitted') && (
                      <>
                        <Button size="sm" onClick={() => { setAccepting(c); setAgreed({ amount: String(Number(c.amount)), response: '' }); }}><Check className="mr-1 h-4 w-4" />{tx('Accepted')}</Button>
                        <Button size="sm" variant="ghost" disabled={busy} onClick={() => reject(c)}><X className="mr-1 h-4 w-4" />{tx('Rejected')}</Button>
                      </>
                    )}
                    {QUANTITY_TYPES.includes(c.type) && (LINE_EDITABLE.includes(c.status) || (c.quantityLines?.length ?? 0) > 0) && (
                      <Button size="sm" variant="outline" onClick={() => setLinesForId(c.id)}>
                        <ListChecks className="mr-1 h-4 w-4" />{tx('Quantities')}{c.quantityLines?.length ? ` (${c.quantityLines.length})` : ''}
                      </Button>
                    )}
                    {canManage && c.status === 'accepted' && (
                      <Button size="sm" onClick={() => { setSettling(c); setClassification(''); }}>{tx('Settle')}</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {data?.meta && data.meta.total > 0 && (
        <DataTablePagination
          page={data.meta.page} pageSize={pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(1); }}
          pageSizeOptions={[25, 50, 100]} noun={tx('claim')}
        />
      )}

      <ConfirmDialog
        open={!!cancelling}
        onOpenChange={(v) => { if (!v) setCancelling(null); }}
        title={tx('Cancel {{claimNumber}}?', { claimNumber: cancelling?.claimNumber ?? '' })}
        description={<p>{tx('The claim is closed without asking the supplier for anything. A cancelled claim cannot be reopened.')}</p>}
        confirmLabel={tx('Cancel claim')}
        pendingLabel={tx('Cancelling…')}
        destructive
        pending={busy}
        onConfirm={cancelClaim}
      />

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('New claim')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>{tx('Supplier')}</Label>
              <SearchableSelect
                value={form.partnerId}
                onValueChange={(v) => setForm({ ...form, partnerId: v })}
                options={suppliers.options}
                onQueryChange={suppliers.onQueryChange}
                loading={suppliers.loading}
                footer={suppliers.footer}
                selectedLabel={suppliers.labelOf(form.partnerId)}
                placeholder={tx('Select…')}
                searchPlaceholder={tx('Search suppliers…')}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <Label>{tx('Type')}</Label>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as ClaimType })}>
                  {CLAIM_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
              </div>
              <div>
                <Label>{tx('Amount')}</Label>
                <Input type="number" min={0} value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
              </div>
            </div>
            <div>
              <Label>{tx('What happened')}</Label>
              <Textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={create} disabled={busy || !form.partnerId || form.amount === '' || !form.description.trim()}>
              {busy ? tx('Saving…') : tx('Raise claim')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!accepting} onOpenChange={(v) => !v && setAccepting(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Supplier accepted the claim')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>{tx('Agreed amount')}</Label>
              <Input type="number" min={0} value={agreed.amount} onChange={(e) => setAgreed({ ...agreed, amount: e.target.value })} />
            </div>
            <div>
              <Label>{tx('Supplier response')}</Label>
              <Textarea value={agreed.response} onChange={(e) => setAgreed({ ...agreed, response: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setAccepting(null)}>{tx('Cancel')}</Button>
            <Button disabled={busy} onClick={async () => {
              if (accepting && await run(() => claimApi.accept(accepting.id, Number(agreed.amount), agreed.response || undefined), tx('Claim accepted'))) setAccepting(null);
            }}>{busy ? tx('Saving…') : tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!settling} onOpenChange={(v) => !v && setSettling(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Settle claim')} {settling?.claimNumber}</DialogTitle></DialogHeader>
          {settling && (
            <div className="space-y-3">
              <div className="text-sm">{tx('Agreed amount')}: <span className="font-medium">{money(settling.amount)}</span></div>
              <div>
                <Label>{tx('Settle as')}</Label>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={classification} onChange={(e) => setClassification(e.target.value as ClaimClassification | '')}>
                  <option value="">{tx('Automatic (from the claim type and its quantities)')}</option>
                  {classificationsFor(settling.type).map((k) => <option key={k} value={k}>{CLASSIFICATION_LABEL[k]}</option>)}
                </select>
                {classification && <p className="mt-1 text-xs text-muted-foreground">{CLASSIFICATION_HINT[classification]}</p>}
              </div>
              {classification === 'stock_adjustment' && (settling.quantityLines ?? []).some((l) => !l.verified) && (
                <p className="text-xs text-destructive">{tx('Every quantity line must be verified by a second user first.')}</p>
              )}
              {classification === 'stock_adjustment' && !(settling.quantityLines ?? []).length && (
                <p className="text-xs text-destructive">{tx('Record the received quantities on the claim first.')}</p>
              )}
              <p className="text-xs text-muted-foreground">{tx('Settling posts the supplier credit to the ledger. It cannot be undone from this screen.')}</p>
            </div>
          )}
          <DialogFooter>
            <Button variant="ghost" onClick={() => setSettling(null)}>{tx('Cancel')}</Button>
            <Button onClick={settle} disabled={busy}>{busy ? tx('Settling…') : tx('Settle')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!linesFor} onOpenChange={(v) => !v && setLinesForId(null)}>
        <DialogContent className="max-w-3xl">
          <DialogHeader><DialogTitle>{tx('Claim quantities')} {linesFor?.claimNumber}</DialogTitle></DialogHeader>
          {linesFor && (
            <div className="space-y-4">
              <p className="text-xs text-muted-foreground">{tx('Only verified quantities can settle a claim as a stock adjustment. A second user — not the one who raised the claim or recorded the quantity — verifies each line.')}</p>
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Receipt')}</TableHead><TableHead>{tx('Product')}</TableHead>
                  <TableHead className="text-right">{tx('Quantity')}</TableHead><TableHead className="text-right">{tx('Receipt cost')}</TableHead>
                  <TableHead className="text-right">{tx('Value')}</TableHead><TableHead>{tx('Goods')}</TableHead><TableHead>{tx('Verified')}</TableHead><TableHead />
                </TableRow></TableHeader>
                <TableBody>
                  {!(linesFor.quantityLines ?? []).length && <TableRow><TableCell colSpan={8} className="py-6 text-center text-muted-foreground">{tx('No quantities recorded.')}</TableCell></TableRow>}
                  {(linesFor.quantityLines ?? []).map((l) => (
                    <TableRow key={l.id}>
                      <TableCell className="font-mono text-xs">{l.receiptNumber ?? '—'}</TableCell>
                      <TableCell>{l.productName ?? l.productId}</TableCell>
                      <TableCell className="text-right">{Number(l.quantity)}</TableCell>
                      <TableCell className="text-right">{money(l.unitCost)}</TableCell>
                      <TableCell className="text-right">{money(l.value)}</TableCell>
                      <TableCell className="text-xs">{l.stockAction === 'issue' ? tx('Leave stock') : tx('Already written off')}</TableCell>
                      <TableCell className="text-xs">{l.verified ? <>{l.verifiedByName ?? tx('Verified')} · {date(l.verifiedAt)}</> : <span className="text-muted-foreground">{tx('Not verified')}</span>}</TableCell>
                      <TableCell className="space-x-1 whitespace-nowrap text-right">
                        {canVerify && !l.verified && LINE_EDITABLE.includes(linesFor.status) && userId !== l.createdBy && userId !== linesFor.createdBy && (
                          <Button size="sm" onClick={() => run(() => claimApi.verifyLine(linesFor.id, l.id), tx('Quantity verified'))}><Check className="mr-1 h-4 w-4" />{tx('Verify')}</Button>
                        )}
                        {canManage && LINE_EDITABLE.includes(linesFor.status) && (
                          <Button size="sm" variant="ghost" aria-label={tx('Remove')} onClick={() => run(() => claimApi.removeLine(linesFor.id, l.id), tx('Quantity removed'))}><Trash2 className="h-4 w-4" /></Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {canManage && LINE_EDITABLE.includes(linesFor.status) && (
                <div className="grid gap-3 rounded-md border p-3 sm:grid-cols-4">
                  <div className="sm:col-span-2">
                    <Label>{tx('Received line')}</Label>
                    <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={lineForm.goodsReceiptLineId} onChange={(e) => setLineForm({ ...lineForm, goodsReceiptLineId: e.target.value })}>
                      <option value="">{tx('Select…')}</option>
                      {receiptLines.map((r) => (
                        <option key={r.goodsReceiptLineId} value={r.goodsReceiptLineId} disabled={Number(r.claimable) <= 0}>
                          {r.receiptNumber} · {r.productName ?? r.description} · {tx('unclaimed')} {Number(r.claimable)} / {Number(r.quantity)} @ {money(r.unitCost)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <Label>{tx('Quantity')}</Label>
                    <Input type="number" min={0} value={lineForm.quantity} onChange={(e) => setLineForm({ ...lineForm, quantity: e.target.value })} />
                  </div>
                  <div>
                    <Label>{tx('Goods')}</Label>
                    <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={lineForm.stockAction} onChange={(e) => setLineForm({ ...lineForm, stockAction: e.target.value as ClaimStockAction })}>
                      <option value="issue">{tx('Leave stock (return / not delivered)')}</option>
                      <option value="written_off">{tx('Already written off')}</option>
                    </select>
                  </div>
                  <div className="sm:col-span-3">
                    <Label>{tx('Notes')}</Label>
                    <Input value={lineForm.notes} onChange={(e) => setLineForm({ ...lineForm, notes: e.target.value })} />
                  </div>
                  <div className="flex items-end">
                    <Button className="w-full" onClick={addLine} disabled={!lineForm.goodsReceiptLineId || !(Number(lineForm.quantity) > 0)}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>
                  </div>
                </div>
              )}
            </div>
          )}
          <DialogFooter>
            <Button variant="ghost" onClick={() => setLinesForId(null)}>{tx('Close')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** Order changes waiting for a second approver (ADR-PROCUREMENT-001 wave 5). */
export function PoChangeApprovalsPage() {
  const userId = useAuthStore((s) => s.user?.id);
  const { data: rows = [] } = usePendingPoChanges();
  const mutate = usePoChangeMutation((fn: () => Promise<unknown>) => fn());

  const decide = async (id: string, approve: boolean) => {
    try {
      if (approve) {
        await mutate.mutateAsync(() => poChangeApi.approve(id));
        notify.success(tx('Change approved and applied'));
      } else {
        const note = window.prompt(tx('Why is the change rejected?')) ?? '';
        if (!note.trim()) return;
        await mutate.mutateAsync(() => poChangeApi.reject(id, note));
        notify.success(tx('Change rejected'));
      }
    } catch (e) {
      notifyApiError(e, tx('Action failed'));
    }
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Order changes to approve')} icon={ClipboardCheck}
        description={tx('Changes that raise an order above the allowed limit wait here for someone other than the requester.')} />
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Reason')}</TableHead>
              <TableHead className="text-right">{tx('Was')}</TableHead><TableHead className="text-right">{tx('Becomes')}</TableHead>
              <TableHead>{tx('Asked')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={7} className="py-8 text-center text-muted-foreground">{tx('Nothing waiting for approval.')}</TableCell></TableRow>}
              {rows.map((c) => (
                <TableRow key={c.id}>
                  <TableCell><Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${c.purchaseOrderId}`}>{c.orderNumber}</Link></TableCell>
                  <TableCell>{c.partnerName}</TableCell>
                  <TableCell>{c.reason}</TableCell>
                  <TableCell className="text-right">{money(c.oldTotal)}</TableCell>
                  <TableCell className="text-right font-medium">{money(c.newTotal)}</TableCell>
                  <TableCell>{date(c.createdAt)}</TableCell>
                  <TableCell className="space-x-1 whitespace-nowrap text-right">
                    {c.requestedBy !== userId ? (
                      <>
                        <Button size="sm" onClick={() => decide(c.id, true)}><Check className="mr-1 h-4 w-4" />{tx('Approve')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => decide(c.id, false)}><X className="mr-1 h-4 w-4" />{tx('Reject')}</Button>
                      </>
                    ) : <span className="text-xs text-muted-foreground">{tx('Your request')}</span>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

/** Open purchase commitments against budget accounts (ADR-PROCUREMENT-001 wave 5). */
export function CommitmentsPage() {
  const [status, setStatus] = useState('open');
  const [page, setPage] = useState(1);
  const { data } = useCommitments({ status, page });
  const rows = data?.data ?? [];
  const total = data?.meta?.total ?? 0;
  const open = rows.reduce((s, r) => s + Number(r.openAmount || 0), 0);

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Budget commitments')} icon={Landmark}
        description={tx('Money promised to suppliers on approved orders but not yet received. It counts against budgets before the bill arrives.')} />
      <div className="flex items-center gap-3">
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          <option value="open">{tx('Open')}</option>
          <option value="closed">{tx('Closed')}</option>
          <option value="">{tx('All')}</option>
        </select>
        <span className="text-sm text-muted-foreground">{tx('Open on this page')}: <span className="font-semibold text-foreground">{money(open)}</span></span>
      </div>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Order')}</TableHead><TableHead>{tx('Account')}</TableHead><TableHead>{tx('Month')}</TableHead>
              <TableHead className="text-right">{tx('Committed')}</TableHead><TableHead className="text-right">{tx('Still open')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={5} className="py-8 text-center text-muted-foreground">{tx('No commitments.')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell><Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${r.sourceId}`}>{r.sourceNumber ?? r.sourceId}</Link></TableCell>
                  <TableCell>{r.accountCode} · {r.accountName}</TableCell>
                  <TableCell>{r.period}</TableCell>
                  <TableCell className="text-right">{money(r.amount)}</TableCell>
                  <TableCell className="text-right font-medium">{money(r.openAmount)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {total > rows.length && (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
          <Button size="sm" variant="outline" disabled={page * 50 >= total} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
        </div>
      )}
    </div>
  );
}
