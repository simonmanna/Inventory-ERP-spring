import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, useListState, type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  ANOMALY_LABEL, ANOMALY_STATUS_LABEL, useAcknowledgeAnomaly, useFuelAnomalies, useFuelLookups, type FuelAnomaly,
} from '@/features/fleet/fuel-api';
import { num, toOptions } from '../fleet-format';
import { AnomalyStatusPill } from './fuel-ui';

const FILTERS = { status: 'open', type: '', vehicleId: '' };
const STATUS_FILTER: Record<string, string> = { ...ANOMALY_STATUS_LABEL, all: tx('All') };

/** Fuel anomaly review (fleet 1.5): open flags first; acknowledge each with a note (audited). */
export function FuelAnomaliesPage() {
  const navigate = useNavigate();
  const canReview = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetFuel.update));
  const list = useListState(FILTERS);
  const status = list.filters.status || 'open';
  const { type, vehicleId } = list.filters;
  const lookups = useFuelLookups();
  const vehicles = lookups.data?.vehicles ?? [];
  const { data, isLoading, isError } = useFuelAnomalies({
    page: list.page, pageSize: list.pageSize, status, type: type || undefined, vehicleId: vehicleId || undefined,
  });
  const [ack, setAck] = useState<FuelAnomaly | null>(null);

  const chips: ActiveChip[] = [
    ...(type ? [{ key: 't', label: ANOMALY_LABEL[type as keyof typeof ANOMALY_LABEL] ?? type, onRemove: () => list.setFilter('type', '') }] : []),
    ...(vehicleId ? [{ key: 'v', label: vehicles.find((v) => v.id === vehicleId)?.code ?? vehicleId, onRemove: () => list.setFilter('vehicleId', '') }] : []),
  ];

  const columns: Column<FuelAnomaly>[] = [
    { key: 'createdAt', header: tx('Raised'), render: (a) => <span className="whitespace-nowrap">{dateTime(a.createdAt)}</span> },
    { key: 'type', header: tx('Anomaly'), render: (a) => <span className="font-medium">{ANOMALY_LABEL[a.type] ?? a.type}</span> },
    { key: 'detail', header: tx('Detail'), render: (a) => <span className="line-clamp-2 max-w-[22rem] text-sm">{a.detail}</span> },
    { key: 'vehicle', header: tx('Vehicle'), render: (a) => [a.vehicle?.code, a.vehicle?.registrationPlate].filter(Boolean).join(' · ') },
    { key: 'log', header: tx('Fuel log'), render: (a) => (a.fuelLog ? `${a.fuelLog.code} · ${num(a.fuelLog.litres, 1)} L` : '—') },
    { key: 'status', header: tx('Status'), render: (a) => <AnomalyStatusPill status={a.status} /> },
    { key: 'note', header: tx('Review note'), render: (a) => <span className="line-clamp-2 max-w-[16rem] text-xs text-muted-foreground">{a.note ? `${a.note}${a.acknowledgedByName ? ` — ${a.acknowledgedByName}` : ''}` : ''}</span> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (a) => canReview && a.status === 'open' && (
        <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); setAck(a); }}>{tx('Acknowledge')}</Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={AlertTriangle} title={tx('Fuel Anomalies')}
        description={tx('Overfills, meters that did not move, consumption outside the class band and fuel-card warnings.')} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={() => list.setFilters({ type: '', vehicleId: '' })} />}>
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v || 'all')} options={toOptions(STATUS_FILTER)} allLabel={tx('All')} />
        <FilterSelect value={type} onChange={(v) => list.setFilter('type', v)} options={toOptions(ANOMALY_LABEL)} allLabel={tx('All anomalies')} />
        <FilterSelect value={vehicleId} onChange={(v) => list.setFilter('vehicleId', v)} options={vehicles.map((v) => ({ value: v.id, label: v.code }))} allLabel={tx('All vehicles')} />
      </ListToolbar>
      <ListCard>
        <DataTable
          columns={columns}
          data={data?.data ?? []}
          loading={isLoading}
          getRowId={(a) => a.id}
          onRowClick={(a) => navigate(`/fleet/fuel/logs/${a.fuelLogId}`)}
          cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the anomalies. Try again.') : tx('Nothing to review.')}
        />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="anomaly" />
        )}
      </ListCard>
      <AcknowledgeDialog anomaly={ack} onClose={() => setAck(null)} />
    </div>
  );
}

/** Acknowledge an anomaly with a review note (shared by the review list and the fuel log detail). */
export function AcknowledgeDialog({ anomaly, onClose }: { anomaly: FuelAnomaly | null; onClose: () => void }) {
  const [note, setNote] = useState('');
  const acknowledge = useAcknowledgeAnomaly();
  const submit = async () => {
    if (!anomaly) return;
    try {
      await acknowledge.mutateAsync({ id: anomaly.id, note: note.trim() });
      notify.success(tx('Anomaly acknowledged'));
      setNote('');
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not acknowledge the anomaly')));
    }
  };
  return (
    <Dialog open={!!anomaly} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Acknowledge anomaly')}</DialogTitle>
          <DialogDescription>{anomaly ? `${ANOMALY_LABEL[anomaly.type] ?? anomaly.type}: ${anomaly.detail}` : ''}</DialogDescription>
        </DialogHeader>
        <Textarea rows={3} placeholder={tx('What did you find? *')} value={note} onChange={(e) => setNote(e.target.value)} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!note.trim() || acknowledge.isPending}>{tx('Acknowledge')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
