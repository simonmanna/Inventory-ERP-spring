import { useState } from 'react';
import { Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { FilterSelect, ListCard, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { useProductSearch } from '@/features/products/product-search';
import { relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import { useLpAgents, useLpBranchChoices } from '@/features/loss-prevention/vision-api';
import {
  METER_KINDS, useCreateLpMeter, useLpMeters, useRetireLpMeter, useUpdateLpMeter, type MeasurementDevice,
  type MeterKind,
} from '@/features/loss-prevention/measurement-api';
import { SELECT, TABLE, labelOf } from './vision-ui';

const KIND_LABELS: Record<string, string> = {
  pour_spout: tx('Pour spout'), flow_meter: tx('Flow meter'), dispenser: tx('Dispenser'),
};

/**
 * Pour meters (LP3): spouts, flow meters and dispensers. Their readings are compared with what sales drew (B04), with
 * the standard measure (B05) and, where a bar camera is set up, with the pours the camera saw (V-B2). Needs
 * `lp:configure`. The edge agent list is offered only with the camera licence; without it a meter is posted by an
 * outside system.
 */
export function MetersTab({ vision }: { vision: boolean }) {
  const can = useAuthStore((s) => s.hasPermission);
  const canBranches = can(PERMISSIONS.branch.read);
  const branches = useLpBranchChoices(canBranches);
  const [branchId, setBranchId] = useState('');
  const [retired, setRetired] = useState(false);
  const [editing, setEditing] = useState<MeasurementDevice | 'new' | null>(null);
  const { data, isLoading, isError } = useLpMeters({ branchId, includeRetired: retired });
  const columns: Column<MeasurementDevice>[] = [
    { key: 'name', header: tx('Meter'), render: (d) => (
      <div>
        <div className="font-medium">{d.name}</div>
        <div className="text-xs text-muted-foreground">{labelOf(KIND_LABELS, d.kind)} · {d.externalId}</div>
      </div>
    ) },
    { key: 'branchName', header: tx('Branch'), render: (d) => d.branchName ?? d.branchId },
    { key: 'station', header: tx('Station'), render: (d) => d.station ?? '—' },
    { key: 'productName', header: tx('Product'), render: (d) => d.productName ?? tx('Sent with each reading') },
    { key: 'standardPourMl', header: tx('Measure (ml)'), render: (d) => d.standardPourMl ?? '—' },
    { key: 'agentName', header: tx('Read by'), render: (d) => d.agentName ?? tx('Outside system') },
    { key: 'lastReadingAt', header: tx('Last reading'), render: (d) => (d.lastReadingAt ? relativeTime(d.lastReadingAt) : tx('None yet')) },
    { key: 'readings7d', header: tx('Readings, 7 days'), render: (d) => d.readings7d },
    { key: 'enabled', header: tx('State'), render: (d) => (d.retiredAt ? <Badge variant="outline">{tx('Retired')}</Badge>
      : d.enabled ? <Badge>{tx('In use')}</Badge> : <Badge variant="secondary">{tx('Paused')}</Badge>) },
  ];
  return (
    <>
      <p className="text-sm text-muted-foreground">
        {tx('Pour meters report the volume of each pour. Loss prevention compares it with what the sales drew through their recipes and with the standard measure. A meter reports volume only: bottles weighed on a scale stay in the bar counts.')}
      </p>
      <ListCard>
        <ListToolbar>
          {canBranches && (
            <FilterSelect value={branchId} onChange={setBranchId} allLabel={tx('All branches')}
              options={(branches.data ?? []).map((b) => ({ value: b.id, label: b.name }))} />
          )}
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={retired} onChange={(e) => setRetired(e.target.checked)} />
            {tx('Show retired')}
          </label>
          <Button size="sm" className="ml-auto" onClick={() => setEditing('new')}>
            <Plus className="mr-1 h-4 w-4" />{tx('Add meter')}
          </Button>
        </ListToolbar>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} loadingRows={3} getRowId={(d) => d.id}
          {...TABLE} onRowClick={(d) => setEditing(d)}
          emptyMessage={isError ? tx('Could not load the meters. Try again.') : tx('No pour meters yet.')} />
      </ListCard>
      {editing && <MeterDialog meter={editing === 'new' ? null : editing} vision={vision} onClose={() => setEditing(null)} />}
    </>
  );
}

function MeterDialog({ meter, vision, onClose }: { meter: MeasurementDevice | null; vision: boolean; onClose: () => void }) {
  const can = useAuthStore((s) => s.hasPermission);
  const canBranches = can(PERMISSIONS.branch.read);
  const branches = useLpBranchChoices(canBranches && !meter);
  const agents = useLpAgents(vision);
  const products = useProductSearch<{ id: string; code?: string | null; name: string }>();
  const create = useCreateLpMeter();
  const update = useUpdateLpMeter();
  const retire = useRetireLpMeter();
  const [branchId, setBranchId] = useState(meter?.branchId ?? '');
  const [name, setName] = useState(meter?.name ?? '');
  const [kind, setKind] = useState<MeterKind>(meter?.kind ?? 'pour_spout');
  const [externalId, setExternalId] = useState(meter?.externalId ?? '');
  const [vendor, setVendor] = useState(meter?.vendor ?? '');
  const [agentId, setAgentId] = useState(meter?.agentId ?? '');
  const [productId, setProductId] = useState(meter?.productId ?? '');
  const [station, setStation] = useState(meter?.station ?? '');
  const [measure, setMeasure] = useState(meter?.standardPourMl == null ? '' : String(meter.standardPourMl));
  const [enabled, setEnabled] = useState(meter?.enabled ?? true);
  const readOnly = !!meter?.retiredAt;
  const branchAgents = (agents.data ?? []).filter((a) => a.branchId === branchId && a.status !== 'revoked');
  const body = {
    name: name.trim(), vendor: vendor.trim() || null, agentId: agentId || null, productId: productId || null,
    station: station.trim() || null, standardPourMl: measure.trim() ? Number(measure) : null, enabled,
  };
  const fail = (e: unknown) => notify.error(apiMessage(e, tx('Could not save the meter')));
  const onSave = () => (meter
    ? update.mutate({ id: meter.id, ...body }, { onSuccess: onClose, onError: fail })
    : create.mutate({ ...body, branchId, kind, externalId: externalId.trim() }, { onSuccess: onClose, onError: fail }));
  const onRetire = () => meter && retire.mutate(meter.id, {
    onSuccess: onClose,
    onError: (e) => notify.error(apiMessage(e, tx('Could not retire the meter'))),
  });
  const pending = create.isPending || update.isPending || retire.isPending;
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{meter ? meter.name : tx('Add a pour meter')}</DialogTitle>
          <DialogDescription>
            {tx('The meter id is the one its own software uses. The station is written as on the bar camera zone, so a pour the camera sees can be matched with this meter.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          {!meter && (
            <div className="space-y-1 sm:col-span-2">
              <Label>{tx('Branch')}</Label>
              {canBranches && !branches.isError ? (
                <select className={SELECT} value={branchId} onChange={(e) => { setBranchId(e.target.value); setAgentId(''); }}>
                  <option value="">{tx('Choose…')}</option>
                  {(branches.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              ) : <Input value={branchId} onChange={(e) => setBranchId(e.target.value)} placeholder={tx('Branch id')} />}
            </div>
          )}
          <div className="space-y-1 sm:col-span-2">
            <Label>{tx('Name')}</Label>
            <Input value={name} maxLength={100} disabled={readOnly} onChange={(e) => setName(e.target.value)} placeholder={tx('e.g. Vodka spout, main bar')} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Kind')}</Label>
            <select className={SELECT} value={kind} disabled={!!meter} onChange={(e) => setKind(e.target.value as MeterKind)}>
              {METER_KINDS.map((k) => <option key={k} value={k}>{KIND_LABELS[k]}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <Label>{tx('Meter id')}</Label>
            <Input value={externalId} maxLength={200} disabled={!!meter} onChange={(e) => setExternalId(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Vendor')}</Label>
            <Input value={vendor} maxLength={100} disabled={readOnly} onChange={(e) => setVendor(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Station')}</Label>
            <Input value={station} maxLength={200} disabled={readOnly} onChange={(e) => setStation(e.target.value)} placeholder={tx('e.g. Main bar')} />
          </div>
          <div className="space-y-1 sm:col-span-2">
            <Label>{tx('Product')}</Label>
            <SearchableSelect value={productId} onValueChange={setProductId} options={products.options}
              onQueryChange={products.onQueryChange} loading={products.loading} footer={products.footer}
              selectedLabel={products.labelOf(productId) ?? meter?.productName ?? null} disabled={readOnly}
              clearLabel={tx('Sent with each reading')} placeholder={tx('Sent with each reading')}
              searchPlaceholder={tx('Search products…')} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Standard measure (ml)')}</Label>
            <Input type="number" min={0} max={5000} value={measure} disabled={readOnly} onChange={(e) => setMeasure(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Read by')}</Label>
            {vision ? (
              <select className={SELECT} value={agentId} disabled={readOnly || !branchId} onChange={(e) => setAgentId(e.target.value)}>
                <option value="">{tx('Outside system')}</option>
                {branchAgents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            ) : <Input value={tx('Outside system')} disabled />}
          </div>
          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <input type="checkbox" checked={enabled} disabled={readOnly} onChange={(e) => setEnabled(e.target.checked)} />
            {tx('In use')}
          </label>
        </div>
        <DialogFooter className="gap-2">
          {meter && !readOnly && (
            <Button variant="outline" className="mr-auto" disabled={pending} onClick={onRetire}>{tx('Retire')}</Button>
          )}
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          {!readOnly && (
            <Button disabled={pending || !name.trim() || (!meter && (!branchId || !externalId.trim()))} onClick={onSave}>
              {tx('Save')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
