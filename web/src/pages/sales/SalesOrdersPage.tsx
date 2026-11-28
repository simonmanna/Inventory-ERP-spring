import { useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, ShoppingCart, Store } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, ListPageHeader, SearchInput } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money, wallDate } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useSalesOrders } from '@/features/sales/api';
import { DELIVERY_LABEL, INVOICE_LABEL, OrderStatusPill, PaymentPill } from './sales-ui';
import { SaleFiscalPill } from './CashSalePanels';

/** Status tabs; `orderOnly` tabs have no meaning for a cash sale (delivered at the counter, never held). */
const TABS: Array<{ key: string; label: string; extra?: Record<string, string>; orderOnly?: boolean }> = [
  { key: '', label: tx('All') },
  { key: 'draft', label: tx('Drafts') },
  { key: 'approval', label: tx('Awaiting approval'), extra: { approvalStatus: 'pending' } },
  { key: 'confirmed', label: tx('Open') },
  { key: 'to_fulfil', label: tx('To deliver'), extra: { toFulfil: 'true' }, orderOnly: true },
  { key: 'on_hold', label: tx('On hold'), extra: { onHold: 'true' }, orderOnly: true },
  { key: 'done', label: tx('Done') },
  { key: 'cancelled', label: tx('Cancelled') },
];

type SaleTypeFilter = 'order' | 'cash' | 'all';

/**
 * The sales register (ADR-S011, ADR-S094 C6): orders and cash sales are one Sale document, filtered by type. `saleType`
 * presets the type (the Cash Sales menu entry); a reader of one type only sees that type.
 */
export function SalesOrdersPage({ saleType }: { saleType?: 'order' | 'cash' } = {}) {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const canWrite = has(PERMISSIONS.sales.orderWrite);
  const canCash = has(PERMISSIONS.sales.cashSaleCreate);
  const readOrders = has(PERMISSIONS.sales.orderRead);
  const readCash = has(PERMISSIONS.sales.cashSaleRead);
  const asked = (params.get('type') as SaleTypeFilter | null) ?? saleType ?? (readOrders ? 'order' : 'cash');
  const type: SaleTypeFilter = asked === 'all' ? (readOrders && readCash ? 'all' : readOrders ? 'order' : 'cash')
    : asked === 'cash' ? (readCash ? 'cash' : 'order') : (readOrders ? 'order' : 'cash');
  const types: Array<[SaleTypeFilter, string]> = [
    ...(readOrders ? [['order', tx('Orders')] as [SaleTypeFilter, string]] : []),
    ...(readCash ? [['cash', tx('Cash sales')] as [SaleTypeFilter, string]] : []),
    ...(readOrders && readCash ? [['all', tx('All')] as [SaleTypeFilter, string]] : []),
  ];
  const tabs = TABS.filter((t) => type !== 'cash' || !t.orderOnly);
  const tab = params.get('tab') ?? '';
  const def = tabs.find((t) => t.key === tab);
  const set = (k: string, v: string) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); if (k !== 'page') n.delete('page'); return n; }, { replace: true });
  // a tab the chosen type does not have (e.g. On hold for cash sales) falls back to All
  const statusFilter = def?.extra || !def ? undefined : tab || undefined;
  const { data, isLoading } = useSalesOrders({
    saleType: type === 'all' ? undefined : type,
    status: statusFilter, ...(def?.extra ?? {}), q: params.get('q') || undefined, from: params.get('from') || undefined,
    to: params.get('to') || undefined, page: Number(params.get('page') ?? 1), pageSize: 50,
  });

  return (
    <div className="space-y-4">
      <ListPageHeader icon={type === 'cash' ? Store : ShoppingCart}
        title={type === 'cash' ? tx('Cash Sales') : type === 'all' ? tx('All sales') : tx('Sales Orders')}
        description={type === 'cash' ? tx('Sales paid and handed over at a branch counter: payment, goods and invoice in one confirm.')
          : tx('Confirmed customer commitments: delivery, invoicing and changes are tracked per order.')}
        actions={<div className="flex flex-wrap gap-2">
          {canCash && type !== 'order' && <Button data-tour="sales.cash.new" onClick={() => navigate('/sales/cash/new')}><Plus className="mr-1 h-4 w-4" />{tx('New cash sale')}</Button>}
          {canWrite && type !== 'cash' && <Button variant={type === 'all' ? 'outline' : 'default'} onClick={() => navigate('/sales/orders/new')}><Plus className="mr-1 h-4 w-4" />{tx('New order')}</Button>}
        </div>} />
      <div className="flex flex-wrap items-center gap-2">
        {types.length > 1 && !saleType && (
          <div className="flex rounded-lg border bg-card p-0.5" role="group" aria-label={tx('Sale type')}>
            {types.map(([k, label]) => (
              <button key={k} onClick={() => set('type', k)} className={cn('rounded-md px-3 py-1.5 text-sm', type === k ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{label}</button>
            ))}
          </div>
        )}
        <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
          {tabs.map((t) => (
            <button key={t.key} onClick={() => set('tab', t.key)} className={cn('rounded-md px-3 py-1.5 text-sm', (def?.key ?? '') === t.key ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{t.label}</button>
          ))}
        </div>
        <SearchInput value={params.get('q') ?? ''} onChange={(v) => set('q', v)} placeholder={tx('Number, customer, customer PO…')} />
        <input type="date" className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('from') ?? ''} onChange={(e) => set('from', e.target.value)} />
        <input type="date" className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('to') ?? ''} onChange={(e) => set('to', e.target.value)} />
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr><th className="px-3 py-2">{tx('Number')}</th><th className="px-3 py-2">{tx('Customer')}</th>
              <th className="px-3 py-2">{type === 'cash' ? tx('Sale date') : tx('Requested')}</th><th className="px-3 py-2">{tx('Status')}</th><th className="px-3 py-2">{tx('Delivery')}</th>
              <th className="px-3 py-2">{tx('Invoicing')}</th><th className="px-3 py-2">{tx('Payment')}</th><th className="px-3 py-2 text-right">{tx('Total')}</th></tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={8} className="p-3"><Skeleton className="h-32 w-full" /></td></tr>}
            {data?.data.map((o) => (
              <tr key={o.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/orders/${o.id}`)}>
                <td className="px-3 py-2 font-medium">{o.number}{o.revision > 0 && <span className="ml-1 text-xs text-muted-foreground">r{o.revision}</span>}
                  {type === 'all' && o.saleType === 'cash' && <span className="ml-2 rounded border px-1.5 py-0.5 text-[11px] font-normal text-muted-foreground">{tx('Cash')}</span>}</td>
                <td className="px-3 py-2">{o.saleType === 'cash' && o.customerName && o.customerName !== o.partnerName ? <>{o.customerName}<span className="block text-xs text-muted-foreground">{o.partnerName}</span></> : o.partnerName}</td>
                <td className="px-3 py-2">{o.saleType === 'cash' ? (o.saleDate ? wallDate(o.saleDate) : date(o.createdAt)) : o.requestedDate ? date(o.requestedDate) : '—'}</td>
                <td className="px-3 py-2"><OrderStatusPill status={o.stage ?? o.status} approval={o.approvalStatus} onHold={o.onHold} />
                  {o.saleType === 'cash' && o.status !== 'draft' && o.fiscalStatus && o.fiscalStatus !== 'issued' && o.fiscalStatus !== 'none' && <span className="ml-1"><SaleFiscalPill status={o.fiscalStatus} /></span>}</td>
                <td className="px-3 py-2 text-xs">{DELIVERY_LABEL[o.deliveryStatus]}</td>
                <td className="px-3 py-2 text-xs">{INVOICE_LABEL[o.invoiceStatus]}</td>
                <td className="px-3 py-2">{o.status !== 'draft' && <PaymentPill status={o.paymentStatus} />}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(o.total, o.currencyCode)}</td>
              </tr>
            ))}
            {data && data.data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{type === 'cash' ? tx('No cash sales.') : tx('No sales orders.')}</td></tr>}
          </tbody>
          {data && data.data.length > 0 && (
            <tfoot><tr className="border-t font-semibold"><td colSpan={7} className="px-3 py-2">{data.meta.total} {type === 'order' ? tx('orders') : tx('sales')}</td><td className="px-3 py-2 text-right tabular-nums">{(data.totals?.byCurrency?.length ?? 0) > 1
              ? data.totals!.byCurrency!.map((c) => <div key={c.currencyCode ?? '-'}>{money(c.total, c.currencyCode ?? undefined)}</div>)
              : money(data.totals?.total ?? 0, data.totals?.byCurrency?.[0]?.currencyCode ?? undefined)}</td></tr></tfoot>
          )}
        </table>
        {data && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={(p) => set('page', String(p))} />
        )}
      </div>
    </div>
  );
}
