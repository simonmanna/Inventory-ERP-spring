import { useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Camera, Edit, RefreshCw, Trash2, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  ANOMALY_LABEL, PAYMENT_LABEL, SOURCE_LABEL, FUEL_TYPE_LABEL, useDeleteFuelLog, useFuelLog, usePostFuelLog,
  useReverseFuelLog, useUploadReceipt, type FuelAnomaly, type JournalLineRow,
} from '@/features/fleet/fuel-api';
import { num } from '../fleet-format';
import { AnomalyStatusPill, FuelLogStatusPill } from './fuel-ui';
import { efficiencyText, meterText } from './fuel-format';
import { AcknowledgeDialog } from './FuelAnomaliesPage';

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium">{children ?? '—'}</dd>
    </div>
  );
}

const lineColumns: Column<JournalLineRow>[] = [
  { key: 'account', header: tx('Account'), render: (l) => `${l.accountCode} · ${l.accountName}` },
  { key: 'cc', header: tx('Cost centre'), render: (l) => l.costCenterCode ?? '—' },
  { key: 'debit', header: tx('Debit'), className: 'text-right', render: (l) => (Number(l.debit) > 0 ? money(l.debit) : '') },
  { key: 'credit', header: tx('Credit'), className: 'text-right', render: (l) => (Number(l.credit) > 0 ? money(l.credit) : '') },
];

/** Fuel log detail (fleet 1.5): figures, efficiency, anomalies, GL entries, expense, receipt; post / reverse / edit. */
export function FuelLogDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: l, isLoading, isError, refetch } = useFuelLog(id);
  const post = usePostFuelLog();
  const remove = useDeleteFuelLog();
  const upload = useUploadReceipt();
  const [reversing, setReversing] = useState(false);
  const [ack, setAck] = useState<FuelAnomaly | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !l) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Fuel log not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }

  const doPost = async () => {
    try {
      const out = await post.mutateAsync(l.id);
      notify.success(tx('Fuel log {{code}} posted', { code: out.code }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not post the fuel log')));
    }
  };

  const doDelete = async () => {
    if (!window.confirm(tx('Delete draft {{code}}?', { code: l.code }))) return;
    try {
      await remove.mutateAsync(l.id);
      notify.success(tx('Draft deleted'));
      navigate('/fleet/fuel');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the draft')));
    }
  };

  const doUpload = async (file: File | undefined) => {
    if (!file) return;
    try {
      await upload.mutateAsync({ id: l.id, file });
      notify.success(tx('Receipt attached'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the receipt')));
    }
  };

  const station = l.source === 'station';
  const anomalies = l.anomalies;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/fuel')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{l.code}</h1>
            <FuelLogStatusPill status={l.status} />
          </div>
          <p className="text-sm text-muted-foreground">
            {dateTime(l.fuelledAt)} · <Link className="text-primary hover:underline" to={`/fleet/vehicles/${l.vehicleId}`}>{[l.vehicle?.code, l.vehicle?.registrationPlate].filter(Boolean).join(' · ')}</Link>
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {l.status === 'draft' && has(PERMISSIONS.fleetFuel.post) && (
            <Button onClick={doPost} disabled={post.isPending}>{tx('Post')}</Button>
          )}
          {l.status === 'draft' && has(PERMISSIONS.fleetFuel.update) && (
            <>
              <Button variant="outline" onClick={() => navigate(`/fleet/fuel/logs/${l.id}/edit`)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>
              <Button variant="ghost" size="icon" aria-label={tx('Delete')} onClick={doDelete}><Trash2 className="h-4 w-4" /></Button>
            </>
          )}
          {l.status === 'posted' && has(PERMISSIONS.fleetFuel.post) && (
            <Button variant="outline" onClick={() => setReversing(true)}><Undo2 className="mr-1.5 h-4 w-4" /> {tx('Reverse')}</Button>
          )}
        </div>
      </div>

      {l.status === 'reversed' && (
        <div className="rounded-lg border bg-muted/40 px-4 py-2 text-sm">
          {tx('Reversed on {{date}}: {{reason}}', { date: l.reversedAt ? dateTime(l.reversedAt) : '—', reason: l.reversalReason ?? '' })}
        </div>
      )}
      {l.meterNote && (
        <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
          {tx('The meter reading was not recorded: {{reason}}', { reason: l.meterNote })}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle className="text-base">{tx('Fuel log')}</CardTitle></CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
              <Item label={tx('Source')}>{station ? SOURCE_LABEL.station : `${SOURCE_LABEL.tank}: ${l.tank?.code ?? ''} ${l.tank?.name ?? ''}`}</Item>
              <Item label={tx('Litres')}>{num(l.litres, 3)}{l.fullTank ? ` · ${tx('full')}` : ` · ${tx('partial')}`}</Item>
              <Item label={tx('Fuel type')}>{l.fuelType ? FUEL_TYPE_LABEL[l.fuelType] ?? l.fuelType : null}</Item>
              <Item label={tx('Price per litre')}>{l.unitPrice ? money(l.unitPrice) : null}</Item>
              <Item label={tx('Cost')}>{l.amount ? money(l.amount) : l.source === 'tank' ? tx('At stock cost when posted') : null}</Item>
              <Item label={tx('Meter')}>{meterText(l.odometer, l.hours)}</Item>
              {station && <Item label={tx('Paid by')}>{l.paymentMethod ? PAYMENT_LABEL[l.paymentMethod] : null}</Item>}
              {station && l.fuelCard && <Item label={tx('Fuel card')}>{l.fuelCard.maskedNumber}</Item>}
              {station && l.paymentAccount && <Item label={tx('Paid from account')}>{l.paymentAccount.name}</Item>}
              {station && <Item label={tx('Supplier')}>{l.supplier?.name}</Item>}
              {station && <Item label={tx('Station')}>{l.stationName}</Item>}
              <Item label={tx('Receipt no.')}>{l.receiptNumber}</Item>
              <Item label={tx('Fuelled by')}>{l.employee?.name}</Item>
              <Item label={tx('Cost centre')}>{l.costCenter ? `${l.costCenter.code ?? ''} ${l.costCenter.name ?? ''}`.trim() : null}</Item>
              {l.stockLedgerCode && <Item label={tx('Stock movement')}>{l.stockLedgerCode}</Item>}
            </dl>
            {l.notes && <p className="mt-4 whitespace-pre-wrap text-sm">{l.notes}</p>}
          </CardContent>
        </Card>
        <div className="space-y-4">
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Efficiency')}</CardTitle></CardHeader>
            <CardContent className="space-y-1 text-sm">
              <div className="text-2xl font-semibold tabular-nums">{efficiencyText(l.lPer100Km, l.lPerHour)}</div>
              {l.previousFullLog ? (
                <p className="text-xs text-muted-foreground">
                  {tx('Since full tank {{code}}: {{litres}} L over {{distance}}', {
                    code: l.previousFullLog.code, litres: num(l.segmentLitres, 1),
                    distance: [l.distanceKm ? `${num(l.distanceKm, 0)} km` : null, l.hoursRun ? `${num(l.hoursRun, 1)} h` : null].filter(Boolean).join(' / ') || '—',
                  })}
                </p>
              ) : (
                <p className="text-xs text-muted-foreground">{l.status === 'posted' ? tx('Measured between two full-tank logs.') : tx('Calculated when posted.')}</p>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0">
              <CardTitle className="text-base">{tx('Receipt')}</CardTitle>
              {has(PERMISSIONS.fleetFuel.create) && (
                <>
                  <input ref={fileRef} type="file" accept="image/*,application/pdf" capture="environment" className="hidden"
                    onChange={(e) => doUpload(e.target.files?.[0])} />
                  <Button size="sm" variant="outline" onClick={() => fileRef.current?.click()} disabled={upload.isPending}>
                    <Camera className="mr-1.5 h-4 w-4" /> {l.receipt ? tx('Replace') : tx('Attach')}
                  </Button>
                </>
              )}
            </CardHeader>
            <CardContent>
              {l.receipt ? (
                l.receipt.kind === 'photo'
                  ? <a href={l.receipt.url} target="_blank" rel="noreferrer"><img src={l.receipt.url} alt={tx('Receipt')} className="max-h-64 rounded-md border" /></a>
                  : <a className="text-sm text-primary hover:underline" href={l.receipt.url} target="_blank" rel="noreferrer">{l.receipt.filename}</a>
              ) : <p className="text-sm text-muted-foreground">{tx('No receipt attached.')}</p>}
            </CardContent>
          </Card>
        </div>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Anomalies')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          {anomalies.length === 0 && <p className="text-sm text-muted-foreground">{tx('No anomalies.')}</p>}
          {anomalies.map((a) => (
            <div key={a.id} className="flex flex-wrap items-start justify-between gap-2 rounded-lg border p-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{ANOMALY_LABEL[a.type] ?? a.type}</span>
                  <AnomalyStatusPill status={a.status} />
                </div>
                <p className="text-sm text-muted-foreground">{a.detail}</p>
                {a.note && <p className="text-xs">{tx('Note')}: {a.note}</p>}
              </div>
              {a.status === 'open' && has(PERMISSIONS.fleetFuel.update) && (
                <Button size="sm" variant="outline" onClick={() => setAck(a)}>{tx('Acknowledge')}</Button>
              )}
            </div>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Accounting')}</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          {l.expense && (
            <p className="text-sm">
              {tx('Expense {{code}}: {{status}} · {{payment}}', { code: l.expense.expenseCode, status: l.expense.status, payment: l.expense.paymentStatus })}
            </p>
          )}
          {l.journal.length === 0 && (
            <p className="text-sm text-muted-foreground">
              {l.status === 'draft' ? tx('Nothing is posted until the log is posted.') : l.expense ? tx('Posted to the ledger when the expense is paid.') : tx('No ledger entry.')}
            </p>
          )}
          {l.journal.map((je) => (
            <div key={je.id} className="space-y-2">
              <div className="text-sm font-medium">{je.entryNumber} · {je.postingType === 'reversal' ? tx('Reversal') : tx('Entry')} · {je.status}</div>
              <DataTable columns={lineColumns} data={je.lines} getRowId={(r) => r.id} />
            </div>
          ))}
        </CardContent>
      </Card>

      <ReverseDialog id={l.id} code={l.code} open={reversing} onOpenChange={setReversing} />
      <AcknowledgeDialog anomaly={ack} onClose={() => setAck(null)} />
    </div>
  );
}

function ReverseDialog({ id, code, open, onOpenChange }: { id: string; code: string; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [reason, setReason] = useState('');
  const reverse = useReverseFuelLog();
  const submit = async () => {
    try {
      await reverse.mutateAsync({ id, reason: reason.trim() });
      notify.success(tx('Fuel log {{code}} reversed', { code }));
      setReason('');
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not reverse the fuel log')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Reverse {{code}}?', { code })}</DialogTitle>
          <DialogDescription>{tx('The ledger entry is mirrored, tank stock returns at its cost and a fuel expense is voided.')}</DialogDescription>
        </DialogHeader>
        <Textarea rows={3} placeholder={tx('Reason *')} value={reason} onChange={(e) => setReason(e.target.value)} />
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!reason.trim() || reverse.isPending}>{tx('Reverse')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
