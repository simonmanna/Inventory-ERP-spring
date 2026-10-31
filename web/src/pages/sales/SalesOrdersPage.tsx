import { useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, ShoppingCart } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, ListPageHeader, SearchInput } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useSalesOrders } from '@/features/sales/api';
import { DELIVERY_LABEL, INVOICE_LABEL, OrderStatusPill } from './sales-ui';

const TABS: Array<{ key: string; label: string; extra?: Record<string, string> }> = [
  { key: '', label: tx('All') },
  { key: 'draft', label: tx('Drafts') },
  { key: 'confirmed,in_fulfilment', label: tx('Open') },
  { key: 'to_fulfil', label: tx('To deliver'), extra: { toFulfil: 'true' } },
  { key: 'on_hold', label: tx('On hold'), extra: { onHold: 'true' } },
  { key: 'done', label: tx('Done') },
  { key: 'cancelled', label: tx('Cancelled') },
];

/** Sales order register (ADR-S011). */
export function SalesOrdersPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.orderWrite));
  const tab = params.get('tab') ?? '';
  const def = TABS.find((t) => t.key === tab);
  const set = (k: string, v: string) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); if (k !== 'page') n.delete('page'); return n; }, { replace: true });
  const statusFilter = def?.extra ? undefined : tab || undefined;
  const { data, isLoading } = useSalesOrders({
    status: statusFilter, ...(def?.extra ?? {}), q: params.get('q') || undefined, from: params.get('from') || undefined,
    to: params.get('to') || undefined, page: Number(params.get('page') ?? 1), pageSize: 50,
  });

  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShoppingCart} title={tx('Sales Orders')} description={tx('Confirmed customer commitments: delivery, invoicing and changes are tracked per order.')}
        actions={canWrite && <Button onClick={() => navigate('/sales/orders/new')}><Plus className="mr-1 h-4 w-4" />{tx('New order')}</Button>} />
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
          {TABS.map((t) => (
            <button key={t.key} onClick={() => set('tab', t.key)} className={cn('rounded-md px-3 py-1.5 text-sm', tab === t.key ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{t.label}</button>
          ))}
        </div>
        <SearchInput value={params.get('q') ?? ''} onChange={(v) => set('q', v)} placeholder={tx('Number, customer, customer PO…')} />
        <input type="date" className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('from') ?? ''} onChange={(e) => set('from', e.target.value)} />
        <input type="date" className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('to') ?? ''} onChange={(e) => set('to', e.target.value)} />
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr><th className="px-3 py-2">{tx('Number')}</th><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Customer PO')}</th>
              <th className="px-3 py-2">{tx('Requested')}</th><th className="px-3 py-2">{tx('Status')}</th><th className="px-3 py-2">{tx('Delivery')}</th>
              <th className="px-3 py-2">{tx('Invoicing')}</th><th className="px-3 py-2 text-right">{tx('Total')}</th></tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={8} className="p-3"><Skeleton className="h-32 w-full" /></td></tr>}
            {data?.data.map((o) => (
              <tr key={o.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/orders/${o.id}`)}>
                <td className="px-3 py-2 font-medium">{o.number}{o.revision > 0 && <span className="ml-1 text-xs text-muted-foreground">r{o.revision}</span>}</td>
                <td className="px-3 py-2">{o.partnerName}</td>
                <td className="px-3 py-2 text-muted-foreground">{o.customerRef ?? '—'}</td>
                <td className="px-3 py-2">{o.requestedDate ? date(o.requestedDate) : '—'}</td>
                <td className="px-3 py-2"><OrderStatusPill status={o.status} onHold={o.onHold} /></td>
                <td className="px-3 py-2 text-xs">{DELIVERY_LABEL[o.deliveryStatus]}</td>
                <td className="px-3 py-2 text-xs">{INVOICE_LABEL[o.invoiceStatus]}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(o.total, o.currencyCode)}</td>
              </tr>
            ))}
            {data && data.data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No sales orders.')}</td></tr>}
          </tbody>
          {data && data.data.length > 0 && (
            <tfoot><tr className="border-t font-semibold"><td colSpan={7} className="px-3 py-2">{data.meta.total} {tx('orders')}</td><td className="px-3 py-2 text-right tabular-nums">{money(data.totals?.total ?? 0)}</td></tr></tfoot>
          )}
        </table>
        {data && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={(p) => set('page', String(p))} />
        )}
      </div>
    </div>
  );
}
