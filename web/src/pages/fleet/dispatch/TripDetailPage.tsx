import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, MapPin, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column } from '@/components/data-table';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DISCREPANCY_KIND_LABEL, FREIGHT_BASIS_LABEL, REASON_LABEL, useTrip, type TripLine,
} from '@/features/fleet/dispatch-api';
import { num } from '../fleet-format';
import { DiscrepancyStatusPill, Item, TripStatusPill } from './dispatch-ui';
import { carrierText } from './dispatch-format';
import { TripActions } from './TripActions';
import { WeighTicketsCard } from './WeighTicketsCard';

const DISTANCE_SOURCE: Record<string, string> = {
  telematics: tx('GPS'), odometer: tx('Odometer'), site: tx('Site distance'), manual: tx('Entered'),
};

/** Trip detail (fleet 1.3): assignment, quantities per product, timeline, proof of delivery, discrepancies. */
export function TripDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: t, isLoading, isError, refetch } = useTrip(id);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !t) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Trip not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const o = t.deliveryOrder;
  const site = o?.site;

  const columns: Column<TripLine>[] = [
    { key: 'product', header: tx('Product'), render: (l) => <span className="font-medium">{l.productName}{l.batchNumber ? ` · ${l.batchNumber}` : ''}</span> },
    { key: 'unit', header: tx('Unit'), render: (l) => l.uomCode ?? '—' },
    { key: 'planned', header: tx('Planned'), className: 'text-right', render: (l) => num(l.plannedQty, 3) },
    { key: 'loaded', header: tx('Loaded'), className: 'text-right', render: (l) => num(l.loadedQty, 3) },
    { key: 'dispatched', header: tx('Dispatched'), className: 'text-right', render: (l) => num(l.dispatchedQty, 3) },
    { key: 'delivered', header: tx('Delivered'), className: 'text-right', render: (l) => num(l.deliveredQty, 3) },
    { key: 'returned', header: tx('Returned'), className: 'text-right', render: (l) => num(l.returnedQty, 3) },
    { key: 'billable', header: tx('Billable'), className: 'text-right', render: (l) => num(l.billableQty, 3) },
    { key: 'stock', header: tx('Stock movement'), render: (l) => [l.stockLedgerCode, l.returnLedgerCode].filter(Boolean).join(' / ') || '—' },
  ];

  const timeline: Array<[string, string | null]> = [
    [tx('Planned for'), date(t.plannedDate)],
    [tx('Dispatched'), t.dispatchedAt ? dateTime(t.dispatchedAt) : null],
    [tx('Left the yard'), t.departedAt ? dateTime(t.departedAt) : null],
    [tx('Delivered'), t.deliveredAt ? dateTime(t.deliveredAt) : null],
    [tx('Closed'), t.closedAt ? dateTime(t.closedAt) : null],
    [tx('Cancelled'), t.cancelledAt ? `${dateTime(t.cancelledAt)} · ${t.cancelReason ?? ''}` : null],
    [tx('Returned'), t.returnedAt ? `${dateTime(t.returnedAt)} · ${t.returnReason ?? ''}` : null],
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate(-1)}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{t.code}</h1>
            <TripStatusPill status={t.status} />
          </div>
          <p className="text-sm text-muted-foreground">
            <Link className="text-primary hover:underline" to={`/fleet/delivery-orders/${t.deliveryOrderId}`}>{t.deliveryOrderCode}</Link>
            {' · '}{t.partnerName}{t.siteName ? ` · ${t.siteName}` : ''}
          </p>
        </div>
        <TripActions trip={t} podPath={`/fleet/my-trips/${t.id}`} />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle className="text-base">{tx('Trip')}</CardTitle></CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
              <Item label={tx('Truck / transporter')}>{carrierText(t)}</Item>
              {t.trailerCode && <Item label={tx('Trailer')}>{t.trailerCode}</Item>}
              {t.carrier === 'transporter' && <Item label={tx('Freight')}>{t.freightBasis ? `${FREIGHT_BASIS_LABEL[t.freightBasis]} · ${num(t.freightRate, 2)}` : null}</Item>}
              {t.freightAmount && <Item label={tx('Freight amount')}>{money(t.freightAmount)}</Item>}
              <Item label={tx('Capacity')}>{t.capacityT ? `${num(t.capacityT, 1)} t` : null}{t.capacityM3 ? ` · ${num(t.capacityM3, 1)} m³` : ''}</Item>
              <Item label={tx('Planned load')}>{t.plannedTonnes ? `${num(t.plannedTonnes, 2)} t` : null}</Item>
              <Item label={tx('Loaded')}>{t.loadedTonnes ? `${num(t.loadedTonnes, 2)} t` : null}</Item>
              <Item label={tx('Delivered')}>{t.deliveredTonnes ? `${num(t.deliveredTonnes, 2)} t` : null}</Item>
              <Item label={tx('Weighed by')}>{t.weighSourceType ? `${t.weighSourceType}${t.weighSourceId ? ` ${t.weighSourceId}` : ''}` : null}</Item>
              <Item label={tx('Odometer')}>{t.startOdometer ? `${num(t.startOdometer, 1)} → ${num(t.endOdometer, 1)}` : null}</Item>
              <Item label={tx('Distance')}>{t.distanceKm ? `${num(t.distanceKm, 1)} km (${DISTANCE_SOURCE[t.distanceSource ?? ''] ?? t.distanceSource})` : null}</Item>
              <Item label={tx('Delivery note')}>{t.deliveryNoteNumber}</Item>
              <Item label={tx('Purpose')}>{t.purpose}</Item>
            </dl>
            {t.notes && <p className="mt-3 whitespace-pre-wrap text-sm">{t.notes}</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Site')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {site ? (
              <>
                <div className="font-medium">{site.name}</div>
                <div>{[site.address, site.city].filter(Boolean).join(', ')}</div>
                {site.contactName && <div>{site.contactName} {site.contactPhone ?? ''}</div>}
                {site.accessNotes && <div className="text-muted-foreground">{site.accessNotes}</div>}
                {site.latitude && (
                  <a className="inline-flex items-center gap-1 text-primary hover:underline" target="_blank" rel="noreferrer"
                    href={`https://www.google.com/maps/search/?api=1&query=${site.latitude},${site.longitude}`}>
                    <MapPin className="h-4 w-4" /> {tx('Open in maps')}
                  </a>
                )}
              </>
            ) : <p className="text-muted-foreground">{tx('No site on the delivery order.')}</p>}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Products')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto p-0">
          <DataTable columns={columns} data={t.lines} getRowId={(l) => l.id} cellClassName="py-2 px-4" headerRowClassName="h-10" emptyMessage="—" />
        </CardContent>
      </Card>

      <WeighTicketsCard tickets={t.weighbridgeTickets ?? []} weighbridge={t.weighbridge} />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Timeline')}</CardTitle></CardHeader>
          <CardContent>
            <dl className="space-y-1 text-sm">
              {timeline.filter(([, v]) => v).map(([k, v]) => (
                <div key={k} className="flex justify-between gap-3"><dt className="text-muted-foreground">{k}</dt><dd>{v}</dd></div>
              ))}
            </dl>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Proof of delivery')}</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-sm">
            {!t.pod && <p className="text-muted-foreground">{tx('Not delivered yet.')}</p>}
            {t.pod && (
              <>
                <div>{tx('Received by {{name}} on {{when}}', { name: t.pod.receiverName, when: dateTime(t.pod.receivedAt) })}{t.pod.receiverPhone ? ` · ${t.pod.receiverPhone}` : ''}</div>
                {t.pod.latitude && <div className="text-muted-foreground">GPS {t.pod.latitude}, {t.pod.longitude} ({t.pod.locationSource === 'telematics' ? tx('from telematics') : tx('from the phone')})</div>}
                {t.pod.notes && <div>{t.pod.notes}</div>}
                <div className="flex flex-wrap gap-3">
                  {t.pod.signatureUrl && <img src={t.pod.signatureUrl} alt={tx('Signature')} className="h-24 rounded border bg-white" />}
                  {t.pod.photoUrl && <a href={t.pod.photoUrl} target="_blank" rel="noreferrer"><img src={t.pod.photoUrl} alt={tx('Delivery photo')} className="h-24 rounded border" /></a>}
                </div>
              </>
            )}
          </CardContent>
        </Card>
      </div>

      {t.discrepancies.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Discrepancies')}</CardTitle></CardHeader>
          <CardContent className="space-y-2">
            {t.discrepancies.map((d) => (
              <div key={d.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3 text-sm">
                <span><span className="font-medium">{d.code}</span> · {DISCREPANCY_KIND_LABEL[d.kind]} · {d.productName} · {num(d.differenceQty, 3)} · {REASON_LABEL[d.reason] ?? d.reason}</span>
                <DiscrepancyStatusPill status={d.status} />
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
