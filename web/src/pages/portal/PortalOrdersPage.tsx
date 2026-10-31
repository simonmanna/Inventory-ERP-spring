import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { num } from '@/pages/fleet/fleet-format';
import { usePortalOrder, usePortalOrders } from '@/features/portal/api';
import { Empty, LoadError, Loading, Pager, TripStatus } from './portal-ui';

const ORDER_LABEL: Record<string, string> = {
  confirmed: tx('Confirmed'),
  in_progress: tx('In progress'),
  completed: tx('Completed'),
  closed: tx('Closed'),
  cancelled: tx('Cancelled'),
};

/** My orders, newest first. */
export function PortalOrdersPage() {
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, refetch } = usePortalOrders({ page, pageSize: 20 });
  if (isLoading) return <Loading />;
  if (isError || !data) return <LoadError onRetry={() => refetch()} />;
  return (
    <div className="space-y-3">
      <h1 className="text-xl font-bold">{tx('Orders')}</h1>
      {data.data.length === 0 && <Empty>{tx('No orders yet.')}</Empty>}
      {data.data.map((o) => (
        <Link key={o.id} to={`/portal/orders/${o.id}`} className="block rounded-xl border bg-card p-3 hover:bg-accent">
          <div className="flex justify-between gap-2"><span className="font-medium">{o.orderNumber}</span><span className="tabular-nums">{money(o.totalAmount)}</span></div>
          <div className="flex justify-between gap-2 text-sm text-muted-foreground"><span>{date(o.openedAt)}</span><span>{ORDER_LABEL[o.status] ?? o.status}</span></div>
        </Link>
      ))}
      <Pager page={data.meta.page} totalPages={data.meta.totalPages} onPage={setPage} />
    </div>
  );
}

/** One order: lines and the deliveries against it. */
export function PortalOrderDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data, isLoading, isError, refetch } = usePortalOrder(id);
  if (isLoading) return <Loading />;
  if (isError || !data) return <LoadError onRetry={() => refetch()} />;
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/portal/orders')}><ArrowLeft className="h-5 w-5" /></Button>
        <div>
          <h1 className="text-xl font-bold">{data.orderNumber}</h1>
          <p className="text-sm text-muted-foreground">{date(data.openedAt)} · {ORDER_LABEL[data.status] ?? data.status}</p>
        </div>
      </div>
      <div className="rounded-xl border bg-card p-3 text-sm">
        {data.items.map((i) => (
          <div key={i.id} className="flex justify-between gap-2 py-1">
            <span>{i.description}</span>
            <span className="tabular-nums whitespace-nowrap">{num(i.quantity, 3)} × {money(i.unitPrice)}</span>
          </div>
        ))}
        <div className="mt-2 flex justify-between border-t pt-2 font-medium"><span>{tx('Total')}</span><span className="tabular-nums">{money(data.totalAmount)}</span></div>
      </div>
      <h2 className="text-sm font-medium text-muted-foreground">{tx('Deliveries')}</h2>
      {data.deliveries.length === 0 && <Empty>{tx('No deliveries planned yet.')}</Empty>}
      {data.deliveries.map((d) => (
        <Link key={d.id} to={`/portal/deliveries/${d.id}`} className="flex items-center justify-between gap-2 rounded-xl border bg-card p-3 hover:bg-accent">
          <span>{d.code} · {date(d.plannedDate)}</span>
          <TripStatus status={d.status} />
        </Link>
      ))}
      {data.invoiceId && <Link className="text-sm text-primary hover:underline" to={`/portal/invoices/${data.invoiceId}`}>{tx('View invoice')}</Link>}
    </div>
  );
}
