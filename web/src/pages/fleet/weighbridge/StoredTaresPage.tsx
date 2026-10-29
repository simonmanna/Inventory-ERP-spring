import { useState } from 'react';
import { Plus, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, type Tone, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { SOURCE_LABEL, useCreateTare, useRevokeTare, useTares, useWeighbridgeLookups, type StoredTare } from '@/features/fleet/weighbridge-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { kg } from './weighbridge-format';

const STATE: Record<string, string> = { current: tx('Current'), valid: tx('Valid'), expired: tx('Expired'), all: tx('All') };
const STATE_TONE: Record<string, Tone> = { valid: 'success', expired: 'danger', superseded: 'neutral', revoked: 'neutral' };
const STATE_LABEL: Record<string, string> = { valid: tx('Valid'), expired: tx('Expired'), superseded: tx('Superseded'), revoked: tx('Revoked') };

/** Stored tares (fleet 1.4): the empty weight of a truck, valid for the station's tare validity, re-tare when expired. */
export function StoredTaresPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState({ state: 'current' });
  const { data, isLoading, isError } = useTares({ page: list.page, pageSize: list.pageSize, plate: list.search || undefined, state: list.filters.state || 'current' });
  const revoke = useRevokeTare();
  const [weighing, setWeighing] = useState(false);
  const columns: Column<StoredTare>[] = [
    { key: 'plate', header: tx('Truck'), render: (r) => <div className="font-medium">{r.registrationPlate}{r.vehicleCode && <div className="text-xs text-muted-foreground">{r.vehicleCode}</div>}</div> },
    { key: 'tare', header: tx('Tare'), className: 'text-right', render: (r) => <span className="tabular-nums">{kg(r.tareKg)}</span> },
    { key: 'weighed', header: tx('Weighed'), render: (r) => <span className="whitespace-nowrap">{dateTime(r.weighedAt)}</span> },
    { key: 'expires', header: tx('Expires'), render: (r) => <span className="whitespace-nowrap">{dateTime(r.expiresAt)}</span> },
    { key: 'source', header: tx('Source'), render: (r) => <div>{SOURCE_LABEL[r.source]}{r.deviceCode ? ` ${r.deviceCode}` : ''}{r.manualReason && <div className="text-xs text-violet-700">{r.manualReason}</div>}</div> },
    { key: 'state', header: tx('Status'), render: (r) => <StatusPill tone={STATE_TONE[r.state] ?? 'neutral'}>{STATE_LABEL[r.state] ?? r.state}</StatusPill> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (r) => r.status === 'active' && has(PERMISSIONS.weighbridge.configure) && (
        <Button size="sm" variant="ghost" onClick={() => {
          const reason = window.prompt(tx('Why is this tare revoked?'));
          if (!reason?.trim()) return;
          revoke.mutateAsync({ id: r.id, reason: reason.trim() }).then(() => notify.success(tx('Tare revoked')))
            .catch((err) => notify.error(apiMessage(err, tx('Could not revoke the tare'))));
        }}>{tx('Revoke')}</Button>
      ),
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Truck} title={tx('Stored Tares')}
        description={tx('Empty weights of trucks. A valid stored tare lets a loaded truck be weighed only once.')}
        actions={has(PERMISSIONS.weighbridge.weigh) && <Button onClick={() => setWeighing(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Weigh tare')}</Button>} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search plate…')} />
        <FilterSelect value={list.filters.state} onChange={(v) => list.setFilter('state', v)} options={Object.entries(STATE).map(([value, label]) => ({ value, label }))} allLabel={tx('Current')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(r) => r.id}
          emptyMessage={isError ? tx('Could not load the tares.') : tx('No stored tares.')} />
      </ListCard>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="tare" />
      )}
      {weighing && <TareDialog onClose={() => setWeighing(false)} />}
    </div>
  );
}

function TareDialog({ onClose }: { onClose: () => void }) {
  const has = useAuthStore((s) => s.hasPermission);
  const lookups = useWeighbridgeLookups();
  const create = useCreateTare();
  const [plate, setPlate] = useState('');
  const [stationId, setStationId] = useState('');
  const [deviceId, setDeviceId] = useState('');
  const [manualKg, setManualKg] = useState('');
  const [reason, setReason] = useState('');
  const devices = (lookups.data?.devices ?? []).filter((d) => d.stationId === stationId);
  const manual = !deviceId && has(PERMISSIONS.weighbridge.manualEntry);
  const valid = plate.trim() && stationId && (deviceId || (manual && Number(manualKg) > 0 && reason.trim()));
  const submit = async () => {
    try {
      const r = await create.mutateAsync({
        registrationPlate: plate.trim(), stationId, deviceId: deviceId || undefined,
        manualKg: !deviceId && manualKg ? Number(manualKg) : undefined, manualReason: !deviceId ? reason.trim() || undefined : undefined,
      });
      notify.success(tx('Tare {{weight}} stored for {{plate}}', { weight: kg(r.tareKg), plate: r.registrationPlate }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not store the tare')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Weigh tare')}</DialogTitle>
          <DialogDescription>{tx('The empty truck stands on the platform. The new tare replaces the previous one.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3">
          <Field label={tx('Truck registration plate *')}><Input className="h-12 uppercase" value={plate} onChange={(e) => setPlate(e.target.value)} /></Field>
          <Field label={tx('Station *')}>
            <OptionalSelect value={stationId} onChange={(v) => { setStationId(v); setDeviceId(''); }} placeholder={tx('Choose a station')}
              options={(lookups.data?.stations ?? []).map((s) => ({ value: s.id, label: `${s.code} — ${s.name}` }))} />
          </Field>
          <Field label={tx('Indicator')}>
            <OptionalSelect value={deviceId} onChange={setDeviceId} placeholder={tx('Indicator')} noneLabel={tx('Manual entry')}
              options={devices.map((d) => ({ value: d.id, label: d.code }))} />
          </Field>
          {manual && (
            <>
              <Field label={tx('Weight (kg) *')}><Input type="number" value={manualKg} onChange={(e) => setManualKg(e.target.value)} /></Field>
              <Field label={tx('Reason *')}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
            </>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending}>{tx('Store tare')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
