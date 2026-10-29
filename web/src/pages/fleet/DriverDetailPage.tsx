import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Edit, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Skeleton } from '@/components/ui/skeleton';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { DRIVER_DOC_LABEL, DRIVER_TYPE_LABEL, useDeleteDriver, useDriver, type Driver, type DriverDocument } from '@/features/fleet/drivers-api';
import { DocStatePill, DriverStatusPill } from './drivers-ui';
import { AssignmentsTable, CheckoutsTable, ClaimsTable } from './DriverHistoryTables';

/** Driver / operator detail (fleet 1.2): licence & qualifications, assignments, check-out history, claims. */
export function DriverDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: d, isLoading, isError, refetch } = useDriver(id);
  const has = useAuthStore((s) => s.hasPermission);
  const [tab, setTab] = useState('overview');
  const [deleting, setDeleting] = useState(false);
  const remove = useDeleteDriver();

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !d) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Driver not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }

  const confirmDelete = async () => {
    try {
      await remove.mutateAsync(d.id);
      notify.success(tx('Driver deleted'));
      navigate('/fleet/drivers');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the driver')));
      setDeleting(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/drivers')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="truncate text-2xl font-bold">{d.name ?? d.code}</h1>
            <DriverStatusPill status={d.status} />
            {d.documentState !== 'valid' && <DocStatePill state={d.documentState} />}
          </div>
          <p className="text-sm text-muted-foreground">{[d.code, d.employee?.employeeCode, DRIVER_TYPE_LABEL[d.driverType]].filter(Boolean).join(' · ')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {has(PERMISSIONS.fleetDriver.allowance) && (
            <Button variant="outline" onClick={() => navigate(`/fleet/claims/new?driverId=${d.id}`)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New claim')}</Button>
          )}
          {has(PERMISSIONS.fleetDriver.update) && (
            <Button variant="outline" onClick={() => navigate(`/fleet/drivers/${d.id}/edit`)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>
          )}
          {has(PERMISSIONS.fleetDriver.delete) && (
            <Button variant="ghost" size="icon" aria-label={tx('Delete')} onClick={() => setDeleting(true)}><Trash2 className="h-4 w-4" /></Button>
          )}
        </div>
      </div>

      {d.status !== 'active' && d.statusReason && (
        <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
          {tx('Status reason')}: {d.statusReason}
        </div>
      )}

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="flex h-auto flex-wrap justify-start">
          <TabsTrigger value="overview">{tx('Licence & qualifications')}</TabsTrigger>
          <TabsTrigger value="assignments">{tx('Assignments')}</TabsTrigger>
          <TabsTrigger value="checkouts">{tx('Check-outs')}</TabsTrigger>
          <TabsTrigger value="claims">{tx('Claims')}</TabsTrigger>
        </TabsList>
        <TabsContent value="overview"><Overview d={d} /></TabsContent>
        <TabsContent value="assignments">{tab === 'assignments' && <AssignmentsTable driverId={d.id} />}</TabsContent>
        <TabsContent value="checkouts">{tab === 'checkouts' && <CheckoutsTable driverId={d.id} />}</TabsContent>
        <TabsContent value="claims">{tab === 'claims' && <ClaimsTable driverId={d.id} />}</TabsContent>
      </Tabs>

      <AlertDialog open={deleting} onOpenChange={setDeleting}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{tx('Delete driver {{code}}?', { code: d.code })}</AlertDialogTitle>
            <AlertDialogDescription>{tx('Only a profile without assignments, check-outs or claims can be deleted; otherwise mark it inactive.')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tx('Cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDelete}>{tx('Delete')}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium">{children ?? '—'}</dd>
    </div>
  );
}

function DocRow({ label, doc }: { label: string; doc: DriverDocument | null }) {
  return (
    <div className="flex items-center justify-between gap-2 border-b py-2 text-sm last:border-0">
      <span>{label}</span>
      {doc ? (
        <span className="flex items-center gap-2">
          <span className="text-muted-foreground">{doc.expiresOn ? date(doc.expiresOn) : tx('No expiry')}</span>
          <DocStatePill state={doc.state} />
        </span>
      ) : <span className="text-muted-foreground">{tx('Not on file')}</span>}
    </div>
  );
}

function Overview({ d }: { d: Driver }) {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <Card className="lg:col-span-2">
        <CardHeader><CardTitle className="text-base">{tx('Driving licence')}</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            <Item label={tx('Licence number')}>{d.licenceNumber}</Item>
            <Item label={tx('Licence classes')}>{d.licenceClasses.length ? d.licenceClasses.join(', ') : null}</Item>
            <Item label={tx('Issued by')}>{d.licenceIssuer}</Item>
            <Item label={tx('Issued on')}>{d.licenceIssuedOn ? date(d.licenceIssuedOn) : null}</Item>
            <Item label={tx('Expires on')}>{d.licenceExpiresOn ? date(d.licenceExpiresOn) : null}</Item>
            <Item label={tx('Operator permit number')}>{d.permitNumber}</Item>
            <Item label={tx('Phone')}>{d.employee?.phone}</Item>
            <Item label={tx('Reminder lead (days)')}>{d.reminderLeadDays}</Item>
          </dl>
          <div className="mt-4">
            <p className="mb-2 text-xs text-muted-foreground">{tx('Qualified vehicle classes')}</p>
            <div className="flex flex-wrap gap-1.5">
              {d.qualifications.length === 0 && <span className="text-sm text-muted-foreground">{tx('None — the driver cannot take any vehicle yet.')}</span>}
              {d.qualifications.map((q) => <span key={q.id} className="rounded-full border px-2.5 py-0.5 text-xs">{q.name}</span>)}
            </div>
          </div>
          {d.notes && <p className="mt-4 whitespace-pre-wrap text-sm">{d.notes}</p>}
        </CardContent>
      </Card>
      <div className="space-y-4">
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Documents')}</CardTitle></CardHeader>
          <CardContent>
            <DocRow label={DRIVER_DOC_LABEL.licence} doc={d.documents.licence} />
            <DocRow label={DRIVER_DOC_LABEL.medical} doc={d.documents.medical} />
            <DocRow label={DRIVER_DOC_LABEL.permit} doc={d.documents.permit} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Now')}</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-sm">
            {d.openCheckout ? (
              <p>
                {tx('Has {{vehicle}} checked out since {{since}}.', { vehicle: d.openCheckout.vehicleRegistrationPlate ?? d.openCheckout.vehicleCode, since: dateTime(d.openCheckout.checkedOutAt) })}{' '}
                <Link className="text-primary hover:underline" to={`/fleet/checkout?vehicleId=${d.openCheckout.vehicleId}`}>{tx('Check in')}</Link>
              </p>
            ) : <p className="text-muted-foreground">{tx('No vehicle checked out.')}</p>}
            {(d.assignments ?? []).map((a) => (
              <p key={a.id}>
                {a.kind === 'primary' ? tx('Primary driver of') : tx('Covering')}{' '}
                <Link className="text-primary hover:underline" to={`/fleet/vehicles/${a.vehicleId}`}>{a.vehicleRegistrationPlate ?? a.vehicleCode}</Link>
                {a.endsAt ? ` · ${tx('until')} ${dateTime(a.endsAt)}` : ''}
              </p>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
