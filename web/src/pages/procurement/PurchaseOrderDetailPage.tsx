import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { RecordChatter } from '@/features/communication/record-chatter';
import { useState, type ReactNode } from 'react';
import {
  ChevronDown, CreditCard, FileText, Printer, Receipt, Truck, Wallet, XCircle,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { PoLifecycleCard } from './s2p/PoLifecycleCard';
import { PoChangesCard } from './s2p/PoChangesCard';
import { EmergencyFlag } from './s2p/QualityPages';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { PERMISSIONS } from '@erp/shared';
import { api } from '@/lib/api';
import { notifyApiError } from '@/lib/api-error';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { formatMoney, date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useUomCode } from '@/features/uom/api';
import { usePoLifecycle } from '@/features/procurement/api';
import { useBranchChoices } from '@/features/approval-controls/api';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { InlineField, SmartButton, StatusRibbon } from '@/pages/sales/sales-ui';

interface POLine {
  id: string; productId: string | null; description: string;
  quantity: number; unitPrice: number; receivedQuantity: number;
  lineNumber: number; taxRate: number;
  subtotal: number; notes: string | null;
  /** Unit ordered in (ADR-S003); quantities and price are per this unit. */
  unitOfMeasureId?: string | null;
  variant?: { id: string; name: string; sku: string | null } | null;
}

interface PO {
  id: string; orderNumber: string; partnerId: string;
  status: string; orderDate: string; description: string | null;
  expectedDeliveryDate: string | null; currencyCode: string;
  totalAmount: number; subtotal: number; taxAmount: number;
  totalPaid: number | null; paymentType: string; paymentStatus: string | null;
  notes: string | null; terms: string | null;
  branchId?: string | null;
  lines: POLine[];
  receipts?: any[];
  payments?: any[];
  request?: any;
}

const STATUS_TONE: Record<string, string> = {
  active: 'bg-sky-100 text-sky-800', partially_received: 'bg-indigo-100 text-indigo-800',
  received: 'bg-emerald-100 text-emerald-800', cancelled: 'bg-rose-100 text-rose-800',
};

const STATUS_LABELS: Record<string, string> = {
  active: tx('Active'), partially_received: tx('Partially Received'),
  received: tx('Received'), cancelled: tx('Cancelled'),
};

const PAYMENT_STATUS_LABELS: Record<string, string> = {
  not_paid: tx('Unpaid'), paid: tx('Paid'), partial: tx('Partial'),
};

function PoStatusPill({ status }: { status: string }) {
  return (
    <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium dark:bg-opacity-20', STATUS_TONE[status] ?? 'bg-slate-100 text-slate-700')}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

/** A purchase order, laid out like the sales order form: ribbon, action bar, smart buttons, header fields, tabs. */
export function PurchaseOrderDetailPage() {
  const uomCode = useUomCode();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [rejectReason, setRejectReason] = useState('');
  const [showCancel, setShowCancel] = useState(false);
  const [tab, setTab] = useState('lines');
  // The permissions the receive, pay and cancel routes enforce: actions a user cannot run are not offered.
  const has = useAuthStore((s) => s.hasPermission);
  // Supplier name, supplier reference, promised date and bills come from the lifecycle view of the same order.
  const life = usePoLifecycle(id);
  const branches = useBranchChoices();

  const po = useQuery<PO>({
    queryKey: ['purchase-order', id],
    queryFn: async () => (await api.get<PO>(`/procurement/purchase-orders/${id}`)).data,
    enabled: !!id,
  });

  const cancelMut = useMutation({
    mutationFn: async () =>
      (await api.post(`/procurement/purchase-orders/${id}/cancel`, { reason: rejectReason })).data,
    onSuccess: () => {
      notify.success(tx('PO cancelled'));
      qc.invalidateQueries({ queryKey: ['purchase-order', id] });
      qc.invalidateQueries({ queryKey: ['purchase-orders'] });
      setShowCancel(false);
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed')),
  });

  if (po.isLoading) return <Skeleton className="h-96 w-full" />;
  if (po.error || !po.data) return <p className="text-sm text-destructive">{tx('Purchase not found')}</p>;

  const data = po.data;
  const lc = life.data;
  const currency = data.currencyCode;
  const totalReceived = data.lines.reduce((s, l) => s + Number(l.receivedQuantity), 0);
  const totalOrdered = data.lines.reduce((s, l) => s + Number(l.quantity), 0);
  const totalPaid = Number(data.totalPaid ?? 0);
  const receipts = data.receipts ?? [];
  const payments = data.payments ?? [];
  const bills = lc?.bills ?? [];
  const isCash = data.paymentType === 'cash';
  const paid = data.paymentStatus === 'paid' || (isCash && data.status === 'received');

  const canReceive = (data.status === 'active' || data.status === 'partially_received') && has(PERMISSIONS.procurement.purchaseOrder.receive);
  const canPay = data.status === 'received' && data.paymentType === 'credit' && data.paymentStatus !== 'paid'
    && has(PERMISSIONS.procurement.purchaseOrder.pay);
  const canCancel = !['received', 'cancelled'].includes(data.status) && has(PERMISSIONS.procurement.purchaseOrder.cancel);

  const ribbon = [tx('Purchase order'), tx('Sent'), tx('Received'), tx('Paid')];
  const step = paid ? 3 : data.status === 'received' ? 2 : lc?.sentAt ? 1 : 0;
  const branchName = data.branchId ? branches.data?.find((b) => b.id === data.branchId)?.name ?? '—' : null;
  const dash = <span className="text-muted-foreground">-</span>;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to="/procurement/purchase-orders" className="font-medium text-primary hover:underline">{tx('Purchases')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{data.orderNumber}</span>
          <span className="ml-3 inline-flex items-center gap-1">
            <PoStatusPill status={data.status} />
            <span className={cn('rounded-full border px-2 py-0.5 text-xs font-medium', isCash ? 'border-yellow-400 text-yellow-700' : 'border-blue-400 text-blue-700')}>
              {isCash ? tx('Cash') : tx('Credit')}
            </span>
          </span>
        </div>
        {data.status !== 'cancelled' && <div className="w-full max-w-md"><StatusRibbon steps={ribbon} current={step} /></div>}
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {canReceive && (
            <Button onClick={() => navigate(`/procurement/purchase-orders/${id}/receive`)}>
              <Truck className="mr-1.5 h-4 w-4" />{tx('Receive')}
            </Button>
          )}
          {canPay && (
            <Button variant="secondary" onClick={() => navigate(`/procurement/purchase-orders/${id}/pay`)}>
              <CreditCard className="mr-1.5 h-4 w-4" />{tx('Pay')}
            </Button>
          )}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline">{tx('Actions')}<ChevronDown className="ml-1.5 h-4 w-4" /></Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="min-w-[200px]">
              <DropdownMenuItem onSelect={() => window.open(`/procurement/purchase-orders/${id}/print`, '_blank')}>
                <Printer className="mr-2 h-4 w-4" />{tx('Print')}
              </DropdownMenuItem>
              {canCancel && <>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => setShowCancel(true)} className="text-destructive focus:text-destructive">
                  <XCircle className="mr-2 h-4 w-4" />{tx('Cancel purchase')}
                </DropdownMenuItem>
              </>}
            </DropdownMenuContent>
          </DropdownMenu>
          {id && <EmergencyFlag id={id} />}
        </div>
        <div className="flex flex-wrap gap-2">
          <SmartButton icon={<FileText className="h-4 w-4" />} count={data.request ? 1 : 0} label={tx('Request')} disabled={!data.request}
            onClick={() => data.request && navigate(`/procurement/purchase-requests?highlight=${encodeURIComponent(data.request.id)}`)} />
          <SmartButton icon={<Truck className="h-4 w-4" />} count={receipts.length} label={tx('Receipts')} disabled={!receipts.length}
            onClick={() => (receipts.length === 1 ? navigate(`/procurement/goods-receipts/${receipts[0].id}`) : setTab('receipts'))} />
          <SmartButton icon={<Receipt className="h-4 w-4" />} count={bills.length} label={tx('Bills')} disabled={!bills.length}
            onClick={() => (bills.length === 1 ? navigate(`/vendor-bills/${bills[0].id}`) : setTab('receipts'))} />
          <SmartButton icon={<Wallet className="h-4 w-4" />} count={payments.length} label={tx('Payments')} disabled={!payments.length} onClick={() => setTab('receipts')} />
        </div>
      </div>

      <section className="rounded-xl border bg-card shadow-sm">
        <div className="px-5 pt-5">
          <h1 className="flex items-baseline gap-1.5 text-2xl font-bold">
            <span>{data.orderNumber}</span>
            <span className="text-sm font-medium text-muted-foreground">{tx('Purchase order')}</span>
          </h1>
        </div>
        <div className="grid grid-cols-1 gap-x-12 px-5 pb-5 pt-4 md:grid-cols-2">
          <div>
            <InlineField label={tx('Supplier')}>
              {lc?.partnerName
                ? <Link to={`/procurement/suppliers/${data.partnerId}`} className="font-medium text-primary hover:underline">{lc.partnerName}</Link>
                : dash}
            </InlineField>
            <InlineField label={tx('Supplier ref.')}>{lc?.supplierReference ?? dash}</InlineField>
            <InlineField label={tx('Payment')}>{isCash ? tx('Cash (auto-paid)') : tx('Credit')}</InlineField>
            {!isCash && <InlineField label={tx('Payment Status')}>{PAYMENT_STATUS_LABELS[data.paymentStatus ?? 'not_paid'] ?? data.paymentStatus}</InlineField>}
            <InlineField label={tx('Source Doc')}>
              {data.request
                ? <Link to={`/procurement/purchase-requests?highlight=${encodeURIComponent(data.request.id)}`} className="text-primary hover:underline">{data.request.requestNumber}</Link>
                : dash}
            </InlineField>
            {data.description && <InlineField label={tx('Description')}>{data.description}</InlineField>}
          </div>
          <div>
            <InlineField label={tx('Order Date')}>{date(data.orderDate)}</InlineField>
            <InlineField label={tx('Expected Delivery')}>{data.expectedDeliveryDate ? date(data.expectedDeliveryDate) : dash}</InlineField>
            {lc?.promisedDate && <InlineField label={tx('Promised')}>{date(lc.promisedDate)}</InlineField>}
            {branchName && <InlineField label={tx('Branch')}>{branchName}</InlineField>}
            <InlineField label={tx('Currency')}>{currency}</InlineField>
            {data.terms && <InlineField label={tx('Terms')}>{data.terms}</InlineField>}
          </div>
        </div>

        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="h-auto w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0 px-5 pt-1.5">
            {([['lines', tx('Order lines')], ['receipts', tx('Receipts & payments')], ['progress', tx('Progress & changes')], ['other', tx('Other info')]] as const).map(([v, label]) => (
              <TabsTrigger key={v} value={v} className="-mb-px rounded-none rounded-t-md border border-transparent px-4 py-2 data-[state=active]:border-border data-[state=active]:border-b-card data-[state=active]:bg-card data-[state=active]:shadow-none">{label}</TabsTrigger>
            ))}
          </TabsList>

          <TabsContent value="lines" className="mt-0 space-y-4 p-5">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[720px] text-sm">
                <thead className="border-b text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="w-10 py-2 pr-2">#</th>
                    <th className="py-2 pr-2">{tx('Product')}</th>
                    <th className="w-24 py-2 pr-2 text-right">{tx('Quantity')}</th>
                    <th className="w-20 py-2 pr-2">{tx('UoM')}</th>
                    <th className="w-24 py-2 pr-2 text-right">{tx('Received')}</th>
                    <th className="w-28 py-2 pr-2 text-right">{tx('Unit price')}</th>
                    <th className="w-28 py-2 text-right">{tx('Subtotal')}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.lines.map((ln) => {
                    const full = Number(ln.receivedQuantity) >= Number(ln.quantity);
                    return (
                      <tr key={ln.id} className="border-b align-top">
                        <td className="py-2 pr-2 text-muted-foreground">{ln.lineNumber}</td>
                        <td className="py-2 pr-2">
                          <div className="font-medium">{ln.description}</div>
                          {ln.variant && <div className="text-xs text-muted-foreground">{ln.variant.name}{ln.variant.sku ? ` · ${ln.variant.sku}` : ''}</div>}
                          {ln.notes && <div className="text-xs text-muted-foreground">{ln.notes}</div>}
                        </td>
                        <td className="py-2 pr-2 text-right tabular-nums">{Number(ln.quantity)}</td>
                        <td className="py-2 pr-2 text-muted-foreground">{uomCode(ln.unitOfMeasureId) || '—'}</td>
                        <td className={cn('py-2 pr-2 text-right tabular-nums', full ? 'text-emerald-600' : Number(ln.receivedQuantity) > 0 && 'text-amber-600')}>{Number(ln.receivedQuantity)}</td>
                        <td className="py-2 pr-2 text-right tabular-nums">{formatMoney(ln.unitPrice)}</td>
                        <td className="py-2 text-right tabular-nums">{formatMoney(ln.subtotal, currency)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="grid gap-4 border-t pt-4 md:grid-cols-[minmax(0,1fr)_320px]">
              <div className="text-sm">
                <h2 className="mb-2 font-semibold">{tx('Notes')}</h2>
                {data.notes ? <p className="whitespace-pre-wrap text-muted-foreground">{data.notes}</p> : <p className="text-muted-foreground">-</p>}
              </div>
              <div className="text-sm">
                <h2 className="mb-2 font-semibold">{tx('Totals')}</h2>
                <Row label={tx('Untaxed amount')} value={formatMoney(data.subtotal, currency)} />
                {Number(data.taxAmount) > 0 && <Row label={tx('Taxes')} value={formatMoney(data.taxAmount, currency)} />}
                <div className="mt-2 border-t pt-2"><Row label={tx('Total')} value={<strong className="text-lg">{formatMoney(data.totalAmount, currency)}</strong>} /></div>
                <Row label={tx('Received')} value={<span className={totalReceived >= totalOrdered ? 'text-emerald-600' : 'text-amber-600'}>{totalReceived} / {totalOrdered}</span>} />
                {isCash
                  ? <Row label={tx('Payment')} value={<span className="text-emerald-600">{tx('Auto-paid')}</span>} />
                  : <Row label={tx('Paid')} value={<span className={totalPaid >= Number(data.totalAmount) ? 'text-emerald-600' : 'text-amber-600'}>{formatMoney(totalPaid, currency)}</span>} />}
                {!isCash && totalPaid < Number(data.totalAmount) && <Row label={tx('Amount due')} value={<strong>{formatMoney(Number(data.totalAmount) - totalPaid, currency)}</strong>} />}
              </div>
            </div>
          </TabsContent>

          <TabsContent value="receipts" className="mt-0 p-5">
            <div className="grid items-start gap-4 lg:grid-cols-2">
              <Panel title={tx('Goods Receipts')}>
                {receipts.length === 0 ? <p className="text-muted-foreground">{tx('Nothing received yet.')}</p> : receipts.map((r: any) => (
                  <Link key={r.id} to={`/procurement/goods-receipts/${r.id}`} className="flex flex-col gap-1 rounded-lg border p-2 hover:bg-muted/50">
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-xs font-semibold">{r.receiptNumber}</span>
                      <span className="text-xs text-muted-foreground">{r.status}</span>
                    </div>
                    {r.receivedAt && <div className="text-xs text-muted-foreground">{tx('Received:')} {date(r.receivedAt)}</div>}
                    {r.lines && r.lines.length > 0 && (
                      <div className="text-xs text-muted-foreground">
                        {r.lines.length} {tx('line(s) ·')} {r.lines.reduce((s: number, l: any) => s + Number(l.quantity), 0)} {tx('units')}
                      </div>
                    )}
                  </Link>
                ))}
              </Panel>
              <Panel title={tx('Payments')}>
                {payments.length === 0
                  ? <p className="text-muted-foreground">{isCash ? tx('Paid automatically when the goods are received.') : tx('No payments yet.')}</p>
                  : payments.map((p: any) => (
                    <div key={p.id} className="flex items-center justify-between rounded-lg border p-2">
                      <span className="font-mono text-xs">{p.reference ?? p.id.slice(0, 8)}</span>
                      <span className="font-semibold tabular-nums">{formatMoney(p.amount, currency)}</span>
                    </div>
                  ))}
              </Panel>
              {bills.length > 0 && (
                <Panel title={tx('Bills')}>
                  {bills.map((b) => (
                    <Link key={b.id} to={`/vendor-bills/${b.id}`} className="flex items-center justify-between rounded-lg border p-2 hover:bg-muted/50">
                      <span className="font-mono text-xs font-semibold">{b.documentNumber}</span>
                      <span className="text-xs text-muted-foreground">{b.status} · {formatMoney(Number(b.totalAmount), currency)}</span>
                    </Link>
                  ))}
                </Panel>
              )}
            </div>
          </TabsContent>

          <TabsContent value="progress" className="mt-0 space-y-4 p-5">
            {id && <PoLifecycleCard id={id} />}
            {id && <PoChangesCard id={id} />}
          </TabsContent>

          <TabsContent value="other" className="mt-0 space-y-4 p-5">
            <AttachmentPanel resType="purchase_order" resId={data.id} />
          </TabsContent>
        </Tabs>
      </section>

      <RecordChatter contextType="PurchaseOrder" contextId={data.id} />

      <Dialog open={showCancel} onOpenChange={setShowCancel}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Cancel Purchase')}</DialogTitle></DialogHeader>
          <div className="space-y-2">
            <p className="text-sm text-muted-foreground">{tx('Are you sure you want to cancel')} {data.orderNumber}?</p>
            <Textarea
              placeholder={tx('Reason for cancellation')}
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowCancel(false)}>{tx('Keep')}</Button>
            <Button variant="destructive" onClick={() => cancelMut.mutate()} disabled={cancelMut.isPending}>
              {tx('Cancel PO')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
      <h2 className="mb-2 font-semibold">{title}</h2>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return <div className="flex items-center justify-between py-0.5"><span className="text-muted-foreground">{label}</span><span className="tabular-nums">{value}</span></div>;
}
