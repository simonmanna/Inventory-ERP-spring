import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, MapPin } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { date, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { num } from '@/pages/fleet/fleet-format';
import { usePortalDeliveries, usePortalDelivery, type PortalDeliveryDetail } from '@/features/portal/api';
import { Empty, LoadError, Loading, Pager, TripStatus } from './portal-ui';

/** My deliveries: in progress or finished; refreshes on its own. */
export function PortalDeliveriesPage() {
  const [scope, setScope] = useState('active');
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, refetch } = usePortalDeliveries({ scope, page, pageSize: 20 });
  const choose = (s: string) => { setScope(s); setPage(1); };
  return (
    <div className="space-y-3">
      <h1 className="text-xl font-bold">{tx('Deliveries')}</h1>
      <div className="flex gap-2">
        <Button variant={scope === 'active' ? 'default' : 'outline'} onClick={() => choose('active')}>{tx('In progress')}</Button>
        <Button variant={scope === 'done' ? 'default' : 'outline'} onClick={() => choose('done')}>{tx('Delivered')}</Button>
      </div>
      {isLoading && <Loading />}
      {isError && <LoadError onRetry={() => refetch()} />}
      {data?.data.length === 0 && <Empty>{tx('No deliveries here.')}</Empty>}
      {data?.data.map((d) => (
        <Link key={d.id} to={`/portal/deliveries/${d.id}`} className="block rounded-xl border bg-card p-3 hover:bg-accent">
          <div className="flex items-center justify-between gap-2"><span className="font-medium">{d.code}</span><TripStatus status={d.status} /></div>
          <div className="text-sm text-muted-foreground">{date(d.plannedDate)}{d.siteName ? ` · ${d.siteName}` : ''}</div>
          <div className="text-sm tabular-nums text-muted-foreground">{num(d.deliveredTonnes ?? d.loadedTonnes ?? d.plannedTonnes, 2)} t</div>
        </Link>
      ))}
      {data && <Pager page={data.meta.page} totalPages={data.meta.totalPages} onPage={setPage} />}
    </div>
  );
}

function Timeline({ d }: { d: PortalDeliveryDetail }) {
  const steps = [
    { label: tx('Planned'), at: d.plannedDate, dayOnly: true },
    { label: tx('Loading'), at: d.loadingStartedAt },
    { label: tx('Weighed'), at: d.weighedAt },
    { label: tx('Dispatched'), at: d.dispatchedAt },
    { label: tx('On the way'), at: d.departedAt },
    { label: tx('Delivered'), at: d.deliveredAt },
  ];
  return (
    <ol className="space-y-2 rounded-xl border bg-card p-3 text-sm">
      {steps.map((s) => (
        <li key={s.label} className="flex justify-between gap-2">
          <span className={s.at ? 'font-medium' : 'text-muted-foreground'}>{s.label}</span>
          <span className="text-muted-foreground">{s.at ? (s.dayOnly ? date(s.at) : dateTime(s.at)) : '—'}</span>
        </li>
      ))}
    </ol>
  );
}

/** One delivery with live trip status: refreshed every 30 seconds while open. */
export function PortalDeliveryDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data, isLoading, isError, refetch } = usePortalDelivery(id);
  if (isLoading) return <Loading />;
  if (isError || !data) return <LoadError onRetry={() => refetch()} />;
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/portal/deliveries')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2"><h1 className="text-xl font-bold">{data.code}</h1><TripStatus status={data.status} /></div>
          <p className="text-sm text-muted-foreground">
            {[data.siteName, data.deliveryNoteNumber, data.vehiclePlate, data.driverFirstName].filter(Boolean).join(' · ')}
          </p>
        </div>
      </div>
      {data.position && (
        <a className="flex items-center gap-2 rounded-xl border bg-card p-3 text-sm text-primary" target="_blank" rel="noreferrer"
          href={`https://www.google.com/maps/search/?api=1&query=${data.position.latitude},${data.position.longitude}`}>
          <MapPin className="h-5 w-5" aria-hidden />
          <span>{tx('Truck last seen {{time}}', { time: dateTime(data.position.recordedAt) })}</span>
        </a>
      )}
      <Timeline d={data} />
      <div className="rounded-xl border bg-card p-3 text-sm">
        {data.lines.map((l) => (
          <div key={l.id} className="flex justify-between gap-2 py-1">
            <span>{l.productName}</span>
            <span className="tabular-nums whitespace-nowrap">{num(l.deliveredQty ?? l.dispatchedQty ?? l.loadedQty ?? l.plannedQty, 3)} {l.uomCode ?? ''}</span>
          </div>
        ))}
      </div>
      {data.proofOfDelivery && (
        <div className="rounded-xl border bg-card p-3 text-sm">
          <div className="font-medium">{tx('Proof of delivery')}</div>
          <div>{tx('Received by {{name}}', { name: data.proofOfDelivery.receiverName })} · {dateTime(data.proofOfDelivery.receivedAt)}</div>
          {data.proofOfDelivery.notes && <div className="text-muted-foreground">{data.proofOfDelivery.notes}</div>}
        </div>
      )}
    </div>
  );
}
