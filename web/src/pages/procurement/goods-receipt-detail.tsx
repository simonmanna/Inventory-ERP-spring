import { useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { CheckCircle2, ChevronDown, Layers, ShoppingBag, Undo2 } from 'lucide-react';
import { idempotentPatch } from '@/lib/idempotent-request';
import { notifyApiError } from '@/lib/api-error';
import { notify } from '@/lib/notify';
import { ConfirmDialog } from '@/components/confirm-dialog';
import { useAuthStore } from '@/stores/auth.store';
import { ReasonDialog } from '@/features/inventory/reason-dialog';
import { LandedCostPanel } from '@/features/procurement/landed-cost-panel';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { InlineField, SmartButton, StatusRibbon } from '@/pages/sales/sales-ui';
import { GrnStatusPill } from './po-ui';
import { api } from '@/lib/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { QualityChecksPanel } from '@/pages/quality-control/QualityChecksPanel';
import { useUomCode } from '@/features/uom/api';

interface GRNProduct {
  id: string;
  code: string;
  name: string;
}

interface GRNLine {
  id: string;
  productId: string | null;
  description: string;
  quantity: number;
  unitCost: number;
  /** Unit of quantity and cost (ADR-S003); null = the product's base unit. */
  uomId?: string | null;
  batchNumber: string | null;
  expiryDate: string | null;
  notes: string | null;
  lineNumber: number;
  product: GRNProduct | null;
}

interface GRNWarehouse {
  id: string;
  code: string;
  name: string;
}

interface GRNPartner {
  id: string;
  name: string;
}

interface GRNDetail {
  id: string;
  receiptNumber: string;
  status: 'draft' | 'posted' | 'cancelled' | 'reversed';
  reversedAt?: string | null;
  reversalReason?: string | null;
  receivedAt: string;
  notes: string | null;
  postedAt: string | null;
  postedById: string | null;
  createdAt: string;
  updatedAt: string;
  lines: GRNLine[];
  purchaseOrderId?: string | null;
  order: { orderNumber: string } | null;
  warehouse: GRNWarehouse | null;
  partner: GRNPartner | null;
}

export default function GoodsReceiptDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const qc = useQueryClient();
  const has = useAuthStore((s) => s.hasPermission);
  const uomCode = useUomCode();
  const [reversing, setReversing] = useState(false);
  const [confirmPost, setConfirmPost] = useState(false);
  const [tab, setTab] = useState('lines');

  const { data: grn, isLoading } = useQuery<GRNDetail>({
    queryKey: ['goods-receipt', id],
    queryFn: async () => (await api.get<GRNDetail>(`/procurement/goods-receipts/${id}`)).data,
    enabled: !!id,
  });

  const refresh = () => {
    for (const key of ['goods-receipt', 'goods-receipts', 'inventory-product-stock-levels', 'inventory-ledger', 'purchase-orders']) {
      qc.invalidateQueries({ queryKey: [key] });
    }
  };
  const post = useMutation({
    mutationFn: async () => await idempotentPatch<GRNDetail>(`/procurement/goods-receipts/${id}/post`),
    onSuccess: () => { notify.success(tx('Goods receipt posted — stock received')); setConfirmPost(false); refresh(); },
    onError: (e: unknown) => notifyApiError(e, tx('Posting failed')),
  });

  const reverse = useMutation({
    mutationFn: async (reason: string) =>
      await idempotentPatch<GRNDetail>(`/procurement/goods-receipts/${id}/reverse`, { reason }),
    onSuccess: () => { notify.success(tx('Goods receipt reversed — stock returned and journals reversed')); setReversing(false); refresh(); },
    onError: (e: unknown) => notifyApiError(e, tx('Reversal failed')),
  });

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!grn) {
    return (
      <div className="space-y-2 text-sm">
        <Link to="/procurement/goods-receipts" className="font-medium text-primary hover:underline">{tx('Goods Receipts')}</Link>
        <p className="text-destructive">{tx('Goods receipt not found or you don\'t have permission to view it.')}</p>
      </div>
    );
  }

  // Quantities only add up when every line is in the same unit (a line without one is in its product's base unit).
  const unitKeys = new Set(grn.lines.map((l) => l.uomId ?? `base:${l.productId ?? ''}`));
  const totalQty = unitKeys.size === 1 ? grn.lines.reduce((sum, l) => sum + Number(l.quantity), 0) : null;
  const totalCost = grn.lines.reduce((sum, l) => sum + Number(l.quantity) * Number(l.unitCost), 0);

  const canPost = grn.status === 'draft' && has('goods_receipt:post');
  const canReverse = grn.status === 'posted' && has('goods_receipt:cancel');
  const posted = grn.status === 'posted';
  const unit0 = uomCode(grn.lines[0]?.uomId);
  const dash = <span className="text-muted-foreground">-</span>;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to="/procurement/goods-receipts" className="font-medium text-primary hover:underline">{tx('Goods Receipts')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{grn.receiptNumber}</span>
          <span className="ml-3"><GrnStatusPill status={grn.status} /></span>
        </div>
        {(grn.status === 'draft' || posted) && (
          <div className="w-full max-w-xs"><StatusRibbon steps={[tx('Draft'), tx('Posted')]} current={posted ? 1 : 0} /></div>
        )}
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {canPost && (
            <Button disabled={post.isPending} onClick={() => setConfirmPost(true)}>
              <CheckCircle2 className="mr-1.5 h-4 w-4" />{post.isPending ? tx('Posting…') : tx('Post receipt')}
            </Button>
          )}
          {canReverse && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline">{tx('Actions')}<ChevronDown className="ml-1.5 h-4 w-4" /></Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="min-w-[200px]">
                <DropdownMenuItem disabled={reverse.isPending} onSelect={() => setReversing(true)} className="text-destructive focus:text-destructive">
                  <Undo2 className="mr-2 h-4 w-4" />{tx('Reverse receipt')}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          <SmartButton icon={<ShoppingBag className="h-4 w-4" />} count={grn.purchaseOrderId ? 1 : 0} label={tx('Purchase order')} disabled={!grn.purchaseOrderId}
            onClick={() => grn.purchaseOrderId && navigate(`/procurement/purchase-orders/${grn.purchaseOrderId}`)} />
          <SmartButton icon={<Layers className="h-4 w-4" />} count={grn.lines.length} label={tx('Lines')} onClick={() => setTab('lines')} />
        </div>
      </div>

      {grn.reversedAt && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-transparent">
          {tx('Reversed')} {date(grn.reversedAt)}{grn.reversalReason ? ` — ${grn.reversalReason}` : ''}{tx('. Stock was returned and the receipt\'s journals were reversed.')}
        </div>
      )}

      <section className="rounded-xl border bg-card shadow-sm">
        <div className="px-5 pt-5">
          <h1 className="flex items-baseline gap-1.5 text-2xl font-bold">
            <span>{grn.receiptNumber}</span>
            <span className="text-sm font-medium text-muted-foreground">{tx('Goods receipt')}</span>
          </h1>
        </div>
        <div className="grid grid-cols-1 gap-x-12 px-5 pb-5 pt-4 md:grid-cols-2">
          <div>
            <InlineField label={tx('Supplier')}>
              {grn.partner
                ? <Link to={`/procurement/suppliers/${grn.partner.id}`} className="font-medium text-primary hover:underline">{grn.partner.name}</Link>
                : dash}
            </InlineField>
            <InlineField label={tx('Warehouse')}>{grn.warehouse ? `${grn.warehouse.code} — ${grn.warehouse.name}` : dash}</InlineField>
            <InlineField label={tx('Source Doc')}>
              {grn.order?.orderNumber && grn.purchaseOrderId
                ? <Link to={`/procurement/purchase-orders/${grn.purchaseOrderId}`} className="text-primary hover:underline">{grn.order.orderNumber}</Link>
                : dash}
            </InlineField>
          </div>
          <div>
            <InlineField label={tx('Received Date')}>{date(grn.receivedAt)}</InlineField>
            <InlineField label={tx('Posted At')}>{grn.postedAt ? date(grn.postedAt) : dash}</InlineField>
            <InlineField label={tx('Created At')}>{date(grn.createdAt)}</InlineField>
          </div>
        </div>

        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0 px-5 pt-1.5">
            {([['lines', tx('Received lines')], ...(posted ? [['landed', tx('Landed costs')]] : [])] as Array<[string, string]>).map(([v, label]) => (
              <TabsTrigger key={v} value={v} className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{label}</TabsTrigger>
            ))}
          </TabsList>

          <TabsContent value="lines" className="mt-0 space-y-4 p-5">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[900px] text-sm">
                <thead className="border-b text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="w-10 py-2 pr-2">#</th>
                    <th className="py-2 pr-2">{tx('Product')}</th>
                    <th className="py-2 pr-2">{tx('Description')}</th>
                    <th className="w-24 py-2 pr-2 text-right">{tx('Quantity')}</th>
                    <th className="w-20 py-2 pr-2">{tx('UoM')}</th>
                    <th className="w-28 py-2 pr-2 text-right">{tx('Unit Cost')}</th>
                    <th className="w-24 py-2 pr-2">{tx('Batch #')}</th>
                    <th className="w-28 py-2 pr-2">{tx('Expiry')}</th>
                    <th className="w-28 py-2 text-right">{tx('Subtotal')}</th>
                  </tr>
                </thead>
                <tbody>
                  {grn.lines.length === 0 ? (
                    <tr><td colSpan={9} className="py-8 text-center text-muted-foreground">{tx('No line items found.')}</td></tr>
                  ) : grn.lines.map((l, index) => (
                    <tr key={l.id} className="border-b align-top">
                      <td className="py-2 pr-2 text-muted-foreground">{index + 1}</td>
                      <td className="py-2 pr-2">
                        <div className="font-medium">{l.product?.name ?? '—'}</div>
                        {l.product?.code && <div className="font-mono text-xs text-muted-foreground">{l.product.code}</div>}
                      </td>
                      <td className="py-2 pr-2">
                        {l.description}
                        {l.notes && <div className="text-xs text-muted-foreground">{l.notes}</div>}
                      </td>
                      <td className="py-2 pr-2 text-right tabular-nums">{Number(l.quantity).toLocaleString()}</td>
                      <td className="py-2 pr-2 text-muted-foreground">{uomCode(l.uomId) || '—'}</td>
                      <td className="py-2 pr-2 text-right tabular-nums">{money(l.unitCost)}</td>
                      <td className="py-2 pr-2 font-mono text-xs">{l.batchNumber ?? '—'}</td>
                      <td className="py-2 pr-2 text-xs">{l.expiryDate ? date(l.expiryDate) : '—'}</td>
                      <td className="py-2 text-right tabular-nums">{money(Number(l.quantity) * Number(l.unitCost))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="grid gap-4 border-t pt-4 md:grid-cols-[minmax(0,1fr)_320px]">
              <div className="text-sm">
                <h2 className="mb-2 font-semibold">{tx('Notes')}</h2>
                <p className="whitespace-pre-wrap text-muted-foreground">{grn.notes || '-'}</p>
              </div>
              <div className="text-sm">
                <h2 className="mb-2 font-semibold">{tx('Totals')}</h2>
                <Row label={tx('Total Items')} value={grn.lines.length} />
                <Row label={tx('Total Quantity')} value={totalQty === null ? '—' : `${totalQty.toLocaleString()}${unit0 ? ` ${unit0}` : ''}`} />
                <div className="mt-2 border-t pt-2"><Row label={tx('Total Cost')} value={<strong className="text-lg">{money(totalCost)}</strong>} /></div>
              </div>
            </div>
            <QualityChecksPanel sources={[{ sourceType: 'goods_receipt', sourceId: grn.id }]} />
          </TabsContent>

          {posted && (
            <TabsContent value="landed" className="mt-0 space-y-4 p-5">
              <LandedCostPanel goodsReceiptId={grn.id} canCreate={has('goods_receipt:create')} canPost={has('goods_receipt:post')} canCancel={has('goods_receipt:cancel')} />
            </TabsContent>
          )}
        </Tabs>
      </section>

      <ReasonDialog
        open={reversing}
        onOpenChange={setReversing}
        title={tx('Reverse {{receiptNumber}}', { receiptNumber: grn.receiptNumber })}
        description={
          <>
            <p>{tx('Returns the received quantities out of stock, reverses the receipt\'s journal entries (inventory, GRNI and any supplier payable) today, and rolls the purchase order back.')}</p>
            <p>{tx('Not possible once a vendor bill or payment exists — use a debit note for supplier returns.')}</p>
          </>
        }
        confirmLabel={tx('Reverse receipt')}
        pendingLabel={tx('Reversing…')}
        destructive
        pending={reverse.isPending}
        onConfirm={(reason) => reverse.mutate(reason)}
      />

      <ConfirmDialog
        open={confirmPost}
        onOpenChange={setConfirmPost}
        title={tx('Post {{receiptNumber}}?', { receiptNumber: grn.receiptNumber })}
        description={<p>{tx('Posting moves the received quantities into stock and books the receipt to the ledger. Undoing it later needs a reversal.')}</p>}
        confirmLabel={tx('Post receipt')}
        pendingLabel={tx('Posting…')}
        pending={post.isPending}
        onConfirm={() => post.mutate()}
      />
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return <div className="flex items-center justify-between py-0.5"><span className="text-muted-foreground">{label}</span><span className="tabular-nums">{value}</span></div>;
}
