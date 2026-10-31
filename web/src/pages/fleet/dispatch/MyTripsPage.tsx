import { useNavigate } from 'react-router-dom';
import { MapPin, Truck } from 'lucide-react';
import { ListPageHeader } from '@/components/list';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useTrips } from '@/features/fleet/dispatch-api';
import { num } from '../fleet-format';
import { TripStatusPill } from './dispatch-ui';

/** Driver's phone view (fleet 1.3): my trips, on-the-road ones first, as big cards. */
export function MyTripsPage({ basePath = '/fleet/my-trips' }: { basePath?: string }) {
  const navigate = useNavigate();
  const { data, isLoading, isError } = useTrips({ mine: true, status: 'active', page: 1, pageSize: 50 });
  const rows = data?.data ?? [];
  return (
    <div className="mx-auto max-w-xl space-y-4">
      <ListPageHeader icon={Truck} title={tx('My Trips')} description={tx('Your deliveries: open one to record the proof of delivery.')} />
      {isLoading && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">…</div>}
      {isError && <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground">{tx('Could not load your trips. Try again.')}</div>}
      {!isLoading && !isError && rows.length === 0 && (
        <div className="rounded-xl border bg-card p-6 text-center text-muted-foreground">{tx('No trips assigned to you.')}</div>
      )}
      {rows.map((t) => (
        <button key={t.id} type="button" onClick={() => navigate(`${basePath}/${t.id}`)}
          className="w-full rounded-2xl border bg-card p-4 text-left shadow-sm active:bg-accent">
          <div className="flex items-center justify-between gap-2">
            <span className="text-lg font-semibold">{t.code}</span>
            <TripStatusPill status={t.status} />
          </div>
          <div className="mt-1 text-base">{t.partnerName}</div>
          {t.siteName && <div className="flex items-center gap-1 text-sm text-muted-foreground"><MapPin className="h-4 w-4" /> {t.siteName}</div>}
          <div className="mt-2 flex justify-between text-sm text-muted-foreground">
            <span>{date(t.plannedDate)} · {t.vehicleCode ?? ''} {t.vehiclePlate ?? ''}</span>
            <span className="tabular-nums">{num(t.loadedTonnes ?? t.plannedTonnes, 2)} t</span>
          </div>
        </button>
      ))}
    </div>
  );
}
