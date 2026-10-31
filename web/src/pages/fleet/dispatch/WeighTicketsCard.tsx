import { Link } from 'react-router-dom';
import { Scale } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusPill } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import type { LinkedWeighTicket, TripWeighbridge } from '@/features/fleet/dispatch-api';
import { TICKET_STATUS_LABEL, TRIP_FEED_LABEL } from '@/features/fleet/weighbridge-api';
import { kg, qty } from '../weighbridge/weighbridge-format';
import { ManualFlag, TicketStatusPill } from '../weighbridge/weighbridge-ui';

/**
 * Weighbridge tickets linked to a trip or a delivery order (fleet 1.4b): number (link to the weighing screen), status,
 * net weight / quantity, variance, manual flag and how the ticket fed the trip. On a trip it also says whether the
 * weighbridge is required for its dispatch.
 */
export function WeighTicketsCard({ tickets, showTrip = false, weighbridge }: {
  tickets: LinkedWeighTicket[];
  showTrip?: boolean;
  weighbridge?: TripWeighbridge;
}) {
  if (tickets.length === 0 && !weighbridge?.required) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base"><Scale className="h-4 w-4" /> {tx('Weighbridge tickets')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {weighbridge?.required && (
          <p className={weighbridge.fedByTicket ? 'text-emerald-700' : 'text-amber-700'}>
            {weighbridge.fedByTicket
              ? tx('Weighbridge required for dispatch — the loaded quantity comes from a completed ticket.')
              : tx('Weighbridge required for dispatch: {{reason}}', { reason: weighbridge.reason ?? '' })}
          </p>
        )}
        {tickets.length === 0 && <p className="text-muted-foreground">{tx('No weighbridge ticket yet.')}</p>}
        {tickets.map((w) => (
          <div key={w.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3">
            <div className="min-w-0 space-y-0.5">
              <div className="flex flex-wrap items-center gap-2">
                <Link className="font-medium text-primary hover:underline" to={`/fleet/weighbridge/tickets/${w.id}`}>{w.ticketNumber}</Link>
                <TicketStatusPill status={w.status} />
                {w.isManual && <ManualFlag />}
                {w.withinTolerance === false && <StatusPill tone="warning" dot={false}>{tx('Out of tolerance')}</StatusPill>}
              </div>
              <div className="text-muted-foreground">
                {showTrip && w.tripCode ? `${w.tripCode} · ` : ''}{w.registrationPlate}
                {w.tripFeedStatus ? ` · ${TRIP_FEED_LABEL[w.tripFeedStatus]}` : ''}
              </div>
              {w.tripFeedStatus === 'failed' && w.tripFeedMessage && <div className="text-xs text-rose-600">{w.tripFeedMessage}</div>}
            </div>
            <div className="text-right tabular-nums">
              <div className="font-medium">{kg(w.netKg)}{w.netQty ? ` · ${qty(w.netQty, w.netUomCode)}` : ''}</div>
              <div className="text-xs text-muted-foreground">
                {w.varianceKg ? tx('Variance {{variance}}', { variance: kg(w.varianceKg) }) : TICKET_STATUS_LABEL[w.status]}
              </div>
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
