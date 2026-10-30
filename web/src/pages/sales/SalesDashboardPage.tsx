import { useNavigate } from 'react-router-dom';
import { AlertTriangle, CheckSquare, FileText, LayoutDashboard, PauseCircle, Percent, ShoppingCart, Truck } from 'lucide-react';
import type { ReactNode } from 'react';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useSalesSummary } from '@/features/sales/api';

/** Sales dashboard (release R1): pipeline, approvals, open / to-deliver / held orders, conversion, monthly orders. */
export function SalesDashboardPage() {
  const { data, isLoading } = useSalesSummary();
  const navigate = useNavigate();
  if (isLoading || !data) return <Skeleton className="h-96 w-full" />;
  const q = data.quotations;
  const pendingQuotes = (q.draft?.count ?? 0) + (q.pending_approval?.count ?? 0) + (q.approved?.count ?? 0) + (q.sent?.count ?? 0);
  const pendingValue = ['draft', 'pending_approval', 'approved', 'sent'].reduce((s, k) => s + Number(q[k]?.total ?? 0), 0);
  const max = Math.max(1, ...data.monthly.map((m) => Number(m.total)));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={LayoutDashboard} title={tx('Sales Dashboard')} description={tx('Quotations, orders to deliver, approvals and conversion at a glance.')} />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Tile icon={<FileText className="h-5 w-5" />} label={tx('Open quotations')} value={String(pendingQuotes)} sub={money(pendingValue)} onClick={() => navigate('/crm/quotations')} />
        <Tile icon={<ShoppingCart className="h-5 w-5" />} label={tx('Open orders')} value={String(data.openOrders.count)} sub={money(data.openOrders.total)} onClick={() => navigate('/sales/orders?tab=confirmed%2Cin_fulfilment')} />
        <Tile icon={<Truck className="h-5 w-5" />} label={tx('Orders to deliver')} value={String(data.toFulfil.count)} sub={money(data.toFulfil.total)} onClick={() => navigate('/sales/orders?tab=to_fulfil')} />
        <Tile icon={<CheckSquare className="h-5 w-5" />} label={tx('Waiting for approval')} value={String(data.pendingApprovals)} sub={tx('quotes, orders, changes')} />
        <Tile icon={<PauseCircle className="h-5 w-5" />} label={tx('Orders on hold')} value={String(data.onHold)} sub={tx('credit or manual hold')} tone={data.onHold > 0 ? 'warn' : undefined} onClick={() => navigate('/sales/orders?tab=on_hold')} />
        <Tile icon={<Percent className="h-5 w-5" />} label={tx('Quote conversion (90 days)')} value={`${data.conversion.rate}%`} sub={`${data.conversion.converted} / ${data.conversion.quotes}`} />
      </div>
      <div className="rounded-xl border bg-card p-4 shadow-sm">
        <h2 className="mb-3 font-semibold">{tx('Confirmed orders by month')}</h2>
        {data.monthly.length === 0 ? <p className="flex items-center gap-2 text-sm text-muted-foreground"><AlertTriangle className="h-4 w-4" />{tx('No confirmed orders yet.')}</p> : (
          <div className="flex h-48 items-end gap-2">
            {data.monthly.map((m) => (
              <div key={m.month} className="flex flex-1 flex-col items-center gap-1" title={`${m.orders} ${tx('orders')} · ${money(m.total)}`}>
                <div className="w-full rounded-t bg-primary/80" style={{ height: `${(Number(m.total) / max) * 100}%` }} />
                <span className="text-[10px] text-muted-foreground">{m.month}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function Tile({ icon, label, value, sub, onClick, tone }: { icon: ReactNode; label: string; value: string; sub?: string; onClick?: () => void; tone?: 'warn' }) {
  return (
    <button type="button" onClick={onClick} disabled={!onClick} className={`rounded-xl border bg-card p-4 text-left shadow-sm enabled:hover:bg-muted ${tone === 'warn' ? 'border-amber-300' : ''}`}>
      <div className="flex items-center justify-between text-muted-foreground"><span className="text-sm">{label}</span>{icon}</div>
      <p className="mt-2 text-2xl font-semibold tabular-nums">{value}</p>
      {sub && <p className="text-xs text-muted-foreground">{sub}</p>}
    </button>
  );
}
