import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Droplets, Edit, Fuel, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  useFuelLogs, useFuelTank, useReconcileDip, useRecordDip, useSyncDip, useTankDips, useTankMovements,
  type FuelDip, type FuelLog, type TankMovement,
} from '@/features/fleet/fuel-api';
import { Field } from '../fleet-ui';
import { fromLocalInput, num, toLocalInput } from '../fleet-format';
import { DipStatusPill, FuelLogStatusPill, StatTile } from './fuel-ui';
import { TankDialog, TankGauge } from './FuelTanksPage';

/** Tank detail (fleet 1.5): level and value, dips with reconciliation, issues to vehicles, stock movements. */
export function FuelTankDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: t, isLoading, isError, refetch } = useFuelTank(id);
  const [tab, setTab] = useState('dips');
  const [editing, setEditing] = useState(false);
  const [dipping, setDipping] = useState(false);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !t) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Fuel tank not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canTank = has(PERMISSIONS.fleetFuel.tank);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/fuel/tanks')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-bold">{t.code} · {t.name}</h1>
          <p className="text-sm text-muted-foreground">{t.product.name} · {t.location.code} {t.location.name}{t.bowserVehicle ? ` · ${t.bowserVehicle.code}` : ''}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {canTank && <Button onClick={() => setDipping(true)}><Droplets className="mr-1.5 h-4 w-4" /> {tx('Record dip')}</Button>}
          {has(PERMISSIONS.fleetFuel.create) && t.isActive && (
            <Button variant="outline" onClick={() => navigate(`/fleet/fuel/new?tankId=${t.id}`)}><Fuel className="mr-1.5 h-4 w-4" /> {tx('Issue fuel')}</Button>
          )}
          {canTank && <Button variant="outline" onClick={() => setEditing(true)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
        <Card className="sm:col-span-2"><CardContent className="space-y-2 pt-4"><div className="text-xs text-muted-foreground">{tx('Book level')}</div><TankGauge tank={t} /></CardContent></Card>
        <StatTile label={tx('Book value')} value={money(t.bookValue)} hint={tx('Average cost {{cost}} / L', { cost: money(t.averageCost) })} />
        <StatTile label={tx('Draft issues')} value={t.draftIssues} tone={t.draftIssues > 0 ? 'warning' : undefined} hint={tx('Post them before reconciling a dip')} />
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="flex h-auto flex-wrap justify-start">
          <TabsTrigger value="dips">{tx('Dips & reconciliation')}</TabsTrigger>
          <TabsTrigger value="issues">{tx('Issues to vehicles')}</TabsTrigger>
          <TabsTrigger value="movements">{tx('Stock movements')}</TabsTrigger>
        </TabsList>
        <TabsContent value="dips"><DipsTab tankId={t.id} canTank={canTank} /></TabsContent>
        <TabsContent value="issues"><IssuesTab tankId={t.id} /></TabsContent>
        <TabsContent value="movements"><MovementsTab tankId={t.id} /></TabsContent>
      </Tabs>

      {editing && <TankDialog tank={t} onClose={() => setEditing(false)} />}
      <DipDialog tankId={t.id} open={dipping} onOpenChange={setDipping} />
    </div>
  );
}

function DipsTab({ tankId, canTank }: { tankId: string; canTank: boolean }) {
  const { data, isLoading, isError } = useTankDips(tankId);
  const sync = useSyncDip();
  const [reconciling, setReconciling] = useState<FuelDip | null>(null);

  const applyNow = async (d: FuelDip) => {
    try {
      await sync.mutateAsync(d.id);
      notify.success(tx('Reconciliation updated'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not apply the decision')));
    }
  };

  const columns: Column<FuelDip>[] = [
    { key: 'dippedAt', header: tx('When'), render: (d) => <span className="whitespace-nowrap">{dateTime(d.dippedAt)}</span> },
    { key: 'dip', header: tx('Dip (L)'), className: 'text-right', render: (d) => <span className="tabular-nums">{num(d.dipLitres, 1)}</span> },
    { key: 'book', header: tx('Book (L)'), className: 'text-right', render: (d) => <span className="tabular-nums">{num(d.bookLitres, 1)}</span> },
    {
      key: 'variance', header: tx('Variance (L)'), className: 'text-right',
      render: (d) => <span className={`tabular-nums ${Number(d.varianceLitres) < 0 ? 'text-rose-600' : Number(d.varianceLitres) > 0 ? 'text-emerald-700' : ''}`}>{num(d.varianceLitres, 1)}</span>,
    },
    { key: 'status', header: tx('Status'), render: (d) => <DipStatusPill status={d.status} /> },
    { key: 'reason', header: tx('Reason'), render: (d) => <span className="line-clamp-2 max-w-[16rem] text-xs">{d.reason ?? d.failureReason ?? d.notes ?? ''}</span> },
    { key: 'adj', header: tx('Adjustment'), render: (d) => (d.adjustmentLedgerCode ? `${d.adjustmentLedgerCode} · ${money(d.adjustmentValue)}` : '—') },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => canTank && (
        d.status === 'open' ? <Button size="sm" variant="outline" onClick={() => setReconciling(d)}>{tx('Reconcile')}</Button>
          : d.status === 'pending_approval' ? <Button size="sm" variant="ghost" onClick={() => applyNow(d)}>{tx('Check approval')}</Button> : null
      ),
    },
  ];
  return (
    <Card>
      <CardContent className="pt-4">
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(d) => d.id}
          emptyMessage={isError ? tx('Could not load the dips.') : tx('No dips recorded yet.')} />
      </CardContent>
      <ReconcileDialog dip={reconciling} onClose={() => setReconciling(null)} />
    </Card>
  );
}

function IssuesTab({ tankId }: { tankId: string }) {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const { data, isLoading } = useFuelLogs({ page, pageSize: 20, tankId });
  const columns: Column<FuelLog>[] = [
    { key: 'fuelledAt', header: tx('When'), render: (l) => <span className="whitespace-nowrap">{dateTime(l.fuelledAt)}</span> },
    { key: 'code', header: tx('Log'), render: (l) => <span className="font-medium text-primary">{l.code}</span> },
    { key: 'vehicle', header: tx('Vehicle'), render: (l) => l.vehicle?.code ?? '—' },
    { key: 'litres', header: tx('Litres'), className: 'text-right', render: (l) => <span className="tabular-nums">{num(l.litres, 1)}</span> },
    { key: 'cost', header: tx('Cost'), className: 'text-right', render: (l) => (l.amount ? money(l.amount) : '—') },
    { key: 'status', header: tx('Status'), render: (l) => <FuelLogStatusPill status={l.status} /> },
  ];
  return (
    <Card>
      <CardContent className="pt-4">
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(l) => l.id}
          onRowClick={(l) => navigate(`/fleet/fuel/logs/${l.id}`)} emptyMessage={tx('No fuel issued from this tank yet.')} />
        {data?.meta && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={20} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={setPage} onPageSizeChange={() => undefined} noun="issue" />
        )}
      </CardContent>
    </Card>
  );
}

function MovementsTab({ tankId }: { tankId: string }) {
  const [page, setPage] = useState(1);
  const { data, isLoading } = useTankMovements(tankId, page);
  const columns: Column<TankMovement>[] = [
    { key: 'createdAt', header: tx('When'), render: (m) => <span className="whitespace-nowrap">{dateTime(m.createdAt)}</span> },
    { key: 'code', header: tx('Movement'), render: (m) => <span className="font-mono text-xs">{m.ledgerCode}</span> },
    { key: 'type', header: tx('Type'), render: (m) => m.type.replace(/_/g, ' ') },
    { key: 'ref', header: tx('Reference'), render: (m) => m.fuelLogCode ? `${m.fuelLogCode} · ${m.vehicleCode ?? ''}` : (m.referenceType ?? '').replace(/_/g, ' ') },
    { key: 'qty', header: tx('Litres'), className: 'text-right', render: (m) => <span className={`tabular-nums ${Number(m.quantityChange) < 0 ? 'text-rose-600' : 'text-emerald-700'}`}>{num(m.quantityChange, 1)}</span> },
    { key: 'balance', header: tx('Balance'), className: 'text-right', render: (m) => <span className="tabular-nums">{num(m.balanceAfter, 1)}</span> },
    { key: 'value', header: tx('Value'), className: 'text-right', render: (m) => money(m.totalValue) },
  ];
  return (
    <Card>
      <CardContent className="pt-4">
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(m) => m.id} emptyMessage={tx('No stock movements yet.')} />
        {data?.meta && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={20} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={setPage} onPageSizeChange={() => undefined} noun="movement" />
        )}
      </CardContent>
    </Card>
  );
}

function DipDialog({ tankId, open, onOpenChange }: { tankId: string; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [litres, setLitres] = useState('');
  const [at, setAt] = useState(() => toLocalInput(new Date()));
  const [notes, setNotes] = useState('');
  const record = useRecordDip();
  const submit = async () => {
    try {
      const d = await record.mutateAsync({ tankId, body: { dipLitres: Number(litres), dippedAt: fromLocalInput(at), notes: notes.trim() || undefined } });
      notify.success(tx('Dip recorded: variance {{variance}} L', { variance: num(d.varianceLitres, 1) }));
      setLitres('');
      setNotes('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the dip')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Record dip')}</DialogTitle>
          <DialogDescription>{tx('The measured litres are compared with the book level at that time.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Measured litres *')}><Input type="number" inputMode="decimal" min="0" className="h-12 text-lg" value={litres} onChange={(e) => setLitres(e.target.value)} /></Field>
          <Field label={tx('When')}><Input type="datetime-local" className="h-12" value={at} onChange={(e) => setAt(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={litres === '' || Number(litres) < 0 || record.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ReconcileDialog({ dip, onClose }: { dip: FuelDip | null; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const reconcile = useReconcileDip();
  const variance = dip ? Number(dip.varianceLitres) : 0;
  const submit = async () => {
    if (!dip) return;
    try {
      const out = await reconcile.mutateAsync({ id: dip.id, reason: reason.trim() });
      notify.success(out.status === 'pending_approval' ? tx('Sent for approval') : tx('Reconciled'));
      setReason('');
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not reconcile the dip')));
    }
  };
  return (
    <Dialog open={!!dip} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Reconcile dip')}</DialogTitle>
          <DialogDescription>
            {variance === 0 ? tx('Dip and book agree; nothing to adjust.')
              : tx('Stock will be adjusted by {{variance}} L. Large variances need approval first.', { variance: num(dip?.varianceLitres, 1) })}
          </DialogDescription>
        </DialogHeader>
        {variance !== 0 && <Textarea rows={3} placeholder={tx('Reason (evaporation, leak, theft, unrecorded delivery…) *')} value={reason} onChange={(e) => setReason(e.target.value)} />}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={(variance !== 0 && !reason.trim()) || reconcile.isPending}>{tx('Reconcile')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
