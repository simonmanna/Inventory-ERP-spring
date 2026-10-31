import { Link } from 'react-router-dom';
import { Card, CardContent } from '@/components/ui/card';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { usePortalHome } from '@/features/portal/api';
import { LoadError, Loading } from './portal-ui';

/** Customer home: what I owe, what is on its way. */
export function PortalHomePage() {
  const { data, isLoading, isError, refetch } = usePortalHome();
  if (isLoading) return <Loading />;
  if (isError || !data) return <LoadError onRetry={() => refetch()} />;
  const tiles = [
    { to: '/portal/invoices', label: tx('Balance due'), value: money(data.openBalance) },
    { to: '/portal/deliveries', label: tx('Deliveries in progress'), value: String(data.liveDeliveries) },
    { to: '/portal/orders', label: tx('Open orders'), value: String(data.openOrders) },
  ];
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">{data.customer.name}</h1>
        <p className="text-sm text-muted-foreground">{data.organization.name}</p>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {tiles.map((t) => (
          <Link key={t.to} to={t.to}>
            <Card className="hover:bg-accent"><CardContent className="space-y-1 pt-4">
              <div className="text-xs text-muted-foreground">{t.label}</div>
              <div className="text-2xl font-semibold tabular-nums">{t.value}</div>
            </CardContent></Card>
          </Link>
        ))}
      </div>
    </div>
  );
}
