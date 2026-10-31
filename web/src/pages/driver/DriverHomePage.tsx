import { Link } from 'react-router-dom';
import { AlertTriangle, ClipboardCheck, Fuel, RefreshCw, Truck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { tx } from '@/lib/i18n/tx';
import { useDriverHome, type DriverUnit } from '@/features/driver/api';
import { num } from '@/pages/fleet/fleet-format';

const unitTitle = (u: DriverUnit) => [u.code, u.registrationPlate].filter(Boolean).join(' · ');

function UnitCard({ unit }: { unit: DriverUnit }) {
  return (
    <div className="space-y-3 rounded-2xl border bg-card p-4 shadow-sm">
      <div>
        <div className="text-lg font-semibold">{unitTitle(unit)}</div>
        <div className="text-sm text-muted-foreground">{unit.categoryName}{unit.name ? ` · ${unit.name}` : ''}</div>
        <div className="text-sm tabular-nums text-muted-foreground">
          {unit.currentOdometer ? `${num(unit.currentOdometer, 0)} km` : ''}
          {unit.currentHours ? ` ${num(unit.currentHours)} h` : ''}
        </div>
      </div>
      {unit.preTripToday === null && <p className="text-sm font-medium text-amber-700 dark:text-amber-400">{tx('Pre-trip check not done today')}</p>}
      {unit.preTripToday === 'fail' && <p className="text-sm font-medium text-rose-600">{tx('Today’s check found defects')}</p>}
      {unit.preTripToday === 'pass' && <p className="text-sm text-emerald-700 dark:text-emerald-400">{tx('Checked today')}</p>}
      {unit.openDefects > 0 && (
        <p className="flex items-center gap-1 text-sm text-rose-600"><AlertTriangle className="h-4 w-4" aria-hidden /> {tx('{{n}} open defect(s)', { n: unit.openDefects })}</p>
      )}
      <div className="grid grid-cols-2 gap-2">
        <Button asChild size="lg" variant="outline" className="h-14"><Link to={`/driver/pre-trip?vehicleId=${unit.id}`}><ClipboardCheck className="mr-2 h-5 w-5" /> {tx('Pre-trip check')}</Link></Button>
        <Button asChild size="lg" variant="outline" className="h-14"><Link to={`/driver/fuel?vehicleId=${unit.id}`}><Fuel className="mr-2 h-5 w-5" /> {tx('Log fuel')}</Link></Button>
      </div>
    </div>
  );
}

/** Driver home (DRIVER-1): my units with today's check, and a way into the trips. */
export function DriverHomePage() {
  const { data, isLoading, isError, refetch } = useDriverHome();
  if (isLoading) return <div className="space-y-3"><Skeleton className="h-24 w-full" /><Skeleton className="h-48 w-full" /></div>;
  if (isError || !data) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('This app is for drivers. Ask the transport office to link your user to a driver profile.')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">{[data.driver.firstName, data.driver.lastName].filter(Boolean).join(' ')}</h1>
        <p className="text-sm text-muted-foreground">{data.driver.code}</p>
      </div>
      <Button asChild size="lg" className="h-16 w-full text-base">
        <Link to="/driver/trips"><Truck className="mr-2 h-6 w-6" /> {tx('My Trips')} ({data.liveTrips})</Link>
      </Button>
      {data.units.length === 0 && <div className="rounded-xl border bg-card p-6 text-center text-muted-foreground">{tx('No vehicle assigned to you.')}</div>}
      {data.units.map((u) => <UnitCard key={u.id} unit={u} />)}
    </div>
  );
}
