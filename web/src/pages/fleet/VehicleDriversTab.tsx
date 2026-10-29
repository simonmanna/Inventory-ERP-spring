import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { LogIn, LogOut, UserPlus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { Vehicle } from '@/features/fleet/api';
import { useAssign, useDriverOptions, useEligibility, useOpenCheckout, driverLabel } from '@/features/fleet/drivers-api';
import { Field, OptionalSelect } from './fleet-ui';
import { fromLocalInput, toLocalInput } from './fleet-format';
import { AssignmentsTable, CheckoutsTable } from './DriverHistoryTables';

/** Vehicle detail tab (fleet 1.2): who drives it, the open check-out, assignment and check-out history. */
export function VehicleDriversTab({ vehicle }: { vehicle: Vehicle }) {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const open = useOpenCheckout(vehicle.id);
  const [assigning, setAssigning] = useState(false);
  const c = open.data;

  return (
    <div className="space-y-4">
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 pt-4">
          <div className="min-w-0 flex-1 text-sm">
            {c ? (
              <span>{tx('Checked out to {{driver}} since {{since}} ({{code}}).', { driver: `${c.driverCode} · ${driverLabel(c)}`, since: dateTime(c.checkedOutAt), code: c.code })}</span>
            ) : <span className="text-muted-foreground">{tx('In the yard — not checked out.')}</span>}
          </div>
          {has(PERMISSIONS.fleetDriver.checkout) && vehicle.status !== 'disposed' && (
            <Button onClick={() => navigate(`/fleet/checkout?vehicleId=${vehicle.id}`)}>
              {c ? <><LogIn className="mr-1.5 h-4 w-4" /> {tx('Check in')}</> : <><LogOut className="mr-1.5 h-4 w-4" /> {tx('Check out')}</>}
            </Button>
          )}
          {has(PERMISSIONS.fleetDriver.assign) && vehicle.status !== 'disposed' && (
            <Button variant="outline" onClick={() => setAssigning(true)}><UserPlus className="mr-1.5 h-4 w-4" /> {tx('Assign driver')}</Button>
          )}
        </CardContent>
      </Card>
      <div>
        <h3 className="mb-2 text-sm font-semibold">{tx('Driver assignments')}</h3>
        <AssignmentsTable vehicleId={vehicle.id} />
      </div>
      <div>
        <h3 className="mb-2 text-sm font-semibold">{tx('Check-outs')}</h3>
        <CheckoutsTable vehicleId={vehicle.id} />
      </div>
      <AssignDialog vehicle={vehicle} open={assigning} onOpenChange={setAssigning} />
    </div>
  );
}

function AssignDialog({ vehicle, open, onOpenChange }: { vehicle: Vehicle; open: boolean; onOpenChange: (o: boolean) => void }) {
  const drivers = useDriverOptions(open);
  const assign = useAssign();
  const [driverId, setDriverId] = useState('');
  const [kind, setKind] = useState<'primary' | 'temporary'>('primary');
  const [startsAt, setStartsAt] = useState(toLocalInput(new Date()));
  const [endsAt, setEndsAt] = useState('');
  const [notes, setNotes] = useState('');
  const eligibility = useEligibility(driverId, vehicle.id, 'assigned');
  const needsEnd = kind === 'temporary' && !endsAt;
  const endBeforeStart = !!endsAt && endsAt <= startsAt;

  const submit = async () => {
    try {
      await assign.mutateAsync({
        vehicleId: vehicle.id, driverId, kind, startsAt: fromLocalInput(startsAt), endsAt: fromLocalInput(endsAt),
        notes: notes.trim() || undefined,
      });
      notify.success(tx('Driver assigned'));
      setDriverId('');
      setEndsAt('');
      setNotes('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not assign the driver')));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Assign a driver to {{code}}', { code: vehicle.code })}</DialogTitle>
          <DialogDescription>{tx('A new primary driver ends the current primary assignment. Temporary covers need an end.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <Field label={tx('Driver *')}>
            <SearchableSelect value={driverId} onValueChange={setDriverId} placeholder={tx('Choose a driver')} searchPlaceholder={tx('Search…')}
              options={(drivers.data ?? []).map((d) => ({ value: d.id, label: d.name ?? d.code, code: d.code, hint: d.qualifications.map((q) => q.name).join(', ') }))} />
          </Field>
          {eligibility.data && !eligibility.data.eligible && (
            <ul className="list-disc space-y-1 rounded-md border border-rose-300 bg-rose-50 py-2 pl-8 pr-3 text-sm text-rose-800 dark:border-rose-500/40 dark:bg-rose-500/10 dark:text-rose-200">
              {eligibility.data.reasons.map((r) => <li key={r}>{r}</li>)}
            </ul>
          )}
          <Field label={tx('Type')}>
            <OptionalSelect value={kind} onChange={(v) => setKind((v || 'primary') as 'primary' | 'temporary')} placeholder={tx('Type')}
              options={[{ value: 'primary', label: tx('Primary') }, { value: 'temporary', label: tx('Temporary') }]} />
          </Field>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label={tx('From')}><Input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} /></Field>
            <Field label={kind === 'temporary' ? tx('To *') : tx('To')} error={endBeforeStart ? tx('The assignment must end after it starts') : undefined}>
              <Input type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
            </Field>
          </div>
          <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!driverId || needsEnd || endBeforeStart || assign.isPending || eligibility.data?.eligible === false}>{tx('Assign')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
