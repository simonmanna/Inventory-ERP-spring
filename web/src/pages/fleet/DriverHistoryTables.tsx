import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CONDITION_LABEL, DAMAGE_LABEL, useAssignments, useCheckouts, useClaims, useEndAssignment, type Assignment, type Checkout,
  type Claim, driverLabel,
} from '@/features/fleet/drivers-api';
import { num } from './fleet-format';
import { ClaimStatusPill } from './drivers-ui';

/** Assignment, check-out and claim history tables shared by the driver detail and the vehicle detail (fleet 1.2). */

export function AssignmentsTable({ vehicleId, driverId }: { vehicleId?: string; driverId?: string }) {
  const [page, setPage] = useState(1);
  const { data, isLoading, isError } = useAssignments({ vehicleId, driverId, page });
  const canAssign = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetDriver.assign));
  const end = useEndAssignment();
  const finish = async (a: Assignment) => {
    try {
      await end.mutateAsync({ id: a.id });
      notify.success(tx('Assignment ended'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not end the assignment')));
    }
  };
  const columns: Column<Assignment>[] = [
    { key: 'kind', header: tx('Type'), render: (a) => (a.kind === 'primary' ? tx('Primary') : tx('Temporary')) },
    vehicleId
      ? { key: 'driver', header: tx('Driver'), render: (a) => `${a.driverCode} · ${driverLabel(a)}` }
      : { key: 'vehicle', header: tx('Vehicle'), render: (a) => [a.vehicleCode, a.vehicleRegistrationPlate].filter(Boolean).join(' · ') },
    { key: 'from', header: tx('From'), render: (a) => <span className="whitespace-nowrap">{dateTime(a.startsAt)}</span> },
    { key: 'to', header: tx('To'), render: (a) => (a.endsAt ? <span className="whitespace-nowrap">{dateTime(a.endsAt)}</span> : tx('Open-ended')) },
    { key: 'status', header: tx('Status'), render: (a) => <StatusPill tone={a.status === 'active' ? 'success' : 'neutral'}>{a.status === 'active' ? tx('Active') : tx('Ended')}</StatusPill> },
    { key: 'notes', header: tx('Notes'), render: (a) => a.endReason ?? a.notes ?? '—' },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (a) => (canAssign && a.status === 'active'
        ? <Button size="sm" variant="outline" disabled={end.isPending} onClick={(e) => { e.stopPropagation(); void finish(a); }}>{tx('End')}</Button>
        : null),
    },
  ];
  return (
    <Card>
      <CardContent className="pt-4">
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(a) => a.id}
          emptyMessage={isError ? tx('Could not load the assignments.') : tx('No assignments yet.')} />
        {data?.meta && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={20} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={setPage} onPageSizeChange={() => undefined} noun="assignment" />
        )}
      </CardContent>
    </Card>
  );
}

export function CheckoutsTable({ vehicleId, driverId }: { vehicleId?: string; driverId?: string }) {
  const [page, setPage] = useState(1);
  const navigate = useNavigate();
  const { data, isLoading, isError } = useCheckouts({ vehicleId, driverId, page });
  const meters = (c: Checkout, phase: 'out' | 'in') => {
    const odo = phase === 'out' ? c.outOdometer : c.inOdometer;
    const hrs = phase === 'out' ? c.outHours : c.inHours;
    return [odo ? `${num(odo, 0)} km` : null, hrs ? `${num(hrs)} h` : null].filter(Boolean).join(' · ') || '—';
  };
  const columns: Column<Checkout>[] = [
    { key: 'code', header: tx('Check-out'), render: (c) => <span className="font-medium text-primary">{c.code}</span> },
    vehicleId
      ? { key: 'driver', header: tx('Driver'), render: (c) => `${c.driverCode} · ${driverLabel(c)}` }
      : { key: 'vehicle', header: tx('Vehicle'), render: (c) => [c.vehicleCode, c.vehicleRegistrationPlate].filter(Boolean).join(' · ') },
    { key: 'out', header: tx('Out'), render: (c) => <div className="whitespace-nowrap"><div>{dateTime(c.checkedOutAt)}</div><div className="text-xs text-muted-foreground">{meters(c, 'out')}</div></div> },
    { key: 'in', header: tx('In'), render: (c) => (c.checkedInAt ? <div className="whitespace-nowrap"><div>{dateTime(c.checkedInAt)}</div><div className="text-xs text-muted-foreground">{meters(c, 'in')}</div></div> : <StatusPill tone="warning">{tx('Out now')}</StatusPill>) },
    { key: 'used', header: tx('Used'), className: 'text-right', render: (c) => [c.distanceKm ? `${num(c.distanceKm)} km` : null, c.hoursUsed ? `${num(c.hoursUsed)} h` : null].filter(Boolean).join(' · ') || '—' },
    { key: 'condition', header: tx('Condition'), render: (c) => {
      const flags = c.status === 'closed' ? c.inDamageFlags : c.outDamageFlags;
      const cond = c.status === 'closed' ? c.inCondition : c.outCondition;
      return <span>{cond ? CONDITION_LABEL[cond] : '—'}{flags.length ? <span className="text-xs text-amber-700"> · {flags.map((f) => DAMAGE_LABEL[f] ?? f).join(', ')}</span> : null}</span>;
    } },
  ];
  return (
    <Card>
      <CardContent className="pt-4">
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(c) => c.id}
          onRowClick={(c) => navigate(`/fleet/checkout?vehicleId=${c.vehicleId}&checkoutId=${c.id}`)}
          emptyMessage={isError ? tx('Could not load the check-outs.') : tx('No check-outs yet.')} />
        {data?.meta && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={20} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={setPage} onPageSizeChange={() => undefined} noun="check-out" />
        )}
      </CardContent>
    </Card>
  );
}

export function ClaimsTable({ driverId, vehicleId }: { driverId?: string; vehicleId?: string }) {
  const [page, setPage] = useState(1);
  const navigate = useNavigate();
  const { data, isLoading, isError } = useClaims({ driverId, vehicleId, page, pageSize: 20 });
  const columns: Column<Claim>[] = [
    { key: 'code', header: tx('Claim'), render: (c) => <span className="font-medium text-primary">{c.code}</span> },
    { key: 'date', header: tx('Date'), render: (c) => date(c.claimDate) },
    { key: 'description', header: tx('Description'), render: (c) => c.description ?? '—' },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (c) => <span className="tabular-nums">{money(c.totalAmount, c.currencyCode)}</span> },
    { key: 'status', header: tx('Status'), render: (c) => <ClaimStatusPill status={c.status} /> },
  ];
  return (
    <Card>
      <CardContent className="pt-4">
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(c) => c.id}
          onRowClick={(c) => navigate(`/fleet/claims/${c.id}`)}
          emptyMessage={isError ? tx('Could not load the claims.') : tx('No claims yet.')} />
        {data?.meta && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={20} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={setPage} onPageSizeChange={() => undefined} noun="claim" />
        )}
      </CardContent>
    </Card>
  );
}
