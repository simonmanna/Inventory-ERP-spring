import { useState } from 'react';
import { Plus, Settings2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  useCreateDevice, useCreateStation, useDevices, useProductOptions, useProductSettings, usePutProductSetting,
  useSimulatorWeight, useStations, useTestRead, useUpdateDevice, useUpdateStation, useWeighbridgeLookups,
  type Device, type ProductSetting, type Station,
} from '@/features/fleet/weighbridge-api';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';
import { kg } from './weighbridge-format';
import { DispatchRequirementsTab } from './DispatchRequirementsTab';

const ADAPTER_LABEL: Record<string, string> = {
  simulator: tx('Simulator (demo / tests)'),
  'ascii-tcp': tx('ASCII indicator over TCP'),
  'ascii-serial': tx('ASCII indicator on a serial port'),
};

/** Weighbridge settings (fleet 1.4): stations, indicator devices (test read, simulator weight), product settings. */
export function WeighbridgeSettingsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Weighbridge Settings')}
        description={tx('Stations with tolerances and tare validity, the indicators connected to them, and per-product weighing settings.')} />
      <Tabs defaultValue="stations">
        <TabsList>
          <TabsTrigger value="stations">{tx('Stations')}</TabsTrigger>
          <TabsTrigger value="devices">{tx('Indicators')}</TabsTrigger>
          <TabsTrigger value="products">{tx('Products')}</TabsTrigger>
          {has(PERMISSIONS.fleetDispatch.read) && <TabsTrigger value="dispatch">{tx('Dispatch rule')}</TabsTrigger>}
        </TabsList>
        <TabsContent value="stations"><StationsTab /></TabsContent>
        <TabsContent value="devices"><DevicesTab /></TabsContent>
        <TabsContent value="products"><ProductsTab /></TabsContent>
        {has(PERMISSIONS.fleetDispatch.read) && <TabsContent value="dispatch"><DispatchRequirementsTab /></TabsContent>}
      </Tabs>
    </div>
  );
}

function StationsTab() {
  const stations = useStations(true);
  const [editing, setEditing] = useState<Station | 'new' | null>(null);
  const columns: Column<Station>[] = [
    { key: 'code', header: tx('Code'), render: (s) => <div className="font-medium">{s.code}<div className="text-xs text-muted-foreground">{s.name}</div></div> },
    { key: 'location', header: tx('Receiving store'), render: (s) => s.location?.name ?? '—' },
    { key: 'tol', header: tx('Tolerance'), render: (s) => `${Number(s.tolerancePct)} % / ${kg(s.toleranceKg)}` },
    { key: 'tare', header: tx('Tare validity'), render: (s) => tx('{{days}} days', { days: s.tareValidityDays }) },
    { key: 'range', header: tx('Weight range'), render: (s) => `${kg(s.minWeightKg)} – ${kg(s.maxWeightKg)}` },
    { key: 'devices', header: tx('Indicators'), render: (s) => s.deviceCount },
    { key: 'active', header: tx('Active'), render: (s) => (s.isActive ? tx('Yes') : tx('No')) },
    { key: 'actions', header: '', className: 'text-right', render: (s) => <Button size="sm" variant="ghost" onClick={() => setEditing(s)}>{tx('Edit')}</Button> },
  ];
  return (
    <div className="space-y-3 pt-3">
      <div className="flex justify-end"><Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add station')}</Button></div>
      <ListCard>
        <DataTable columns={columns} data={stations.data ?? []} loading={stations.isLoading} getRowId={(s) => s.id}
          emptyMessage={stations.isError ? tx('Could not load the stations.') : tx('No weighbridge stations yet.')} />
      </ListCard>
      {editing && <StationDialog station={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function StationDialog({ station, onClose }: { station?: Station; onClose: () => void }) {
  const lookups = useWeighbridgeLookups();
  const create = useCreateStation();
  const update = useUpdateStation();
  const [code, setCode] = useState(station?.code ?? '');
  const [name, setName] = useState(station?.name ?? '');
  const [locationId, setLocationId] = useState(station?.locationId ?? '');
  const [pct, setPct] = useState(station?.tolerancePct ?? '0.5');
  const [abs, setAbs] = useState(station?.toleranceKg ?? '20');
  const [days, setDays] = useState(String(station?.tareValidityDays ?? 30));
  const [min, setMin] = useState(station?.minWeightKg ?? '200');
  const [max, setMax] = useState(station?.maxWeightKg ?? '100000');
  const [active, setActive] = useState(station?.isActive ?? true);
  const valid = code.trim() && name.trim() && Number(days) >= 1 && Number(max) > Number(min);
  const submit = async () => {
    const body: Record<string, unknown> = {
      code: code.trim(), name: name.trim(), locationId: locationId || undefined, tolerancePct: Number(pct), toleranceKg: Number(abs),
      tareValidityDays: Number(days), minWeightKg: Number(min), maxWeightKg: Number(max),
    };
    try {
      if (station) {
        await update.mutateAsync({ id: station.id, body: { ...body, isActive: active, clear: !locationId && station.locationId ? ['locationId'] : undefined } });
      } else {
        await create.mutateAsync(body);
      }
      notify.success(tx('Station saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the station')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{station ? tx('Edit station') : tx('Add station')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value)} /></Field>
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Receiving store')} className="sm:col-span-2">
            <OptionalSelect value={locationId} onChange={setLocationId} placeholder={tx('Choose a store')} noneLabel={tx('None')}
              options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: `${l.code} — ${l.name}` }))} />
          </Field>
          <Field label={tx('Tolerance %')}><Input type="number" value={pct} onChange={(e) => setPct(e.target.value)} /></Field>
          <Field label={tx('Tolerance kg')}><Input type="number" value={abs} onChange={(e) => setAbs(e.target.value)} /></Field>
          <Field label={tx('Tare validity (days)')}><Input type="number" value={days} onChange={(e) => setDays(e.target.value)} /></Field>
          <Field label={tx('Minimum weight (kg)')}><Input type="number" value={min} onChange={(e) => setMin(e.target.value)} /></Field>
          <Field label={tx('Maximum weight (kg)')}><Input type="number" value={max} onChange={(e) => setMax(e.target.value)} /></Field>
          {station && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {tx('Active')}</label>}
        </div>
        <p className="text-xs text-muted-foreground">{tx('A ticket is out of tolerance when the difference exceeds the larger of the percentage and the absolute kg.')}</p>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DevicesTab() {
  const devices = useDevices(true);
  const test = useTestRead();
  const sim = useSimulatorWeight();
  const [editing, setEditing] = useState<Device | 'new' | null>(null);
  const testRead = async (d: Device) => {
    try {
      const r = await test.mutateAsync(d.id);
      if (r.ok) notify.success(tx('{{device}}: stable {{weight}} ({{frame}})', { device: d.code, weight: kg(r.weightKg), frame: r.rawFrame ?? '' }));
      else notify.error(`${d.code}: ${r.error ?? r.detail}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not read the indicator')));
    }
  };
  const setWeight = (d: Device) => {
    const v = window.prompt(tx('Weight on the simulated platform (kg)'), String(d.config.weightKg ?? '0'));
    if (v === null || !Number.isFinite(Number(v))) return;
    sim.mutateAsync({ id: d.id, weightKg: Number(v) }).then(() => notify.success(tx('Simulator weight set')))
      .catch((err) => notify.error(apiMessage(err, tx('Could not set the weight'))));
  };
  const columns: Column<Device>[] = [
    { key: 'code', header: tx('Code'), render: (d) => <div className="font-medium">{d.code}<div className="text-xs text-muted-foreground">{d.name ?? ''}</div></div> },
    { key: 'station', header: tx('Station'), render: (d) => d.station.code },
    { key: 'type', header: tx('Adapter'), render: (d) => ADAPTER_LABEL[d.adapterType] ?? d.adapterType },
    { key: 'conn', header: tx('Connection'), render: (d) => <span className="font-mono text-xs">{d.adapterType === 'simulator' ? kg(String(d.config.weightKg ?? 0)) : `${String(d.config.host ?? d.config.port ?? '')}${d.config.host ? `:${String(d.config.port ?? '')}` : ''}`}</span> },
    {
      key: 'last', header: tx('Last read'),
      render: (d) => d.lastReadAt ? (
        <div className="space-y-0.5">
          <StatusPill tone={d.lastStatus === 'ok' ? 'success' : 'danger'}>{d.lastStatus === 'ok' ? kg(d.lastReadKg) : tx('Error')}</StatusPill>
          <div className="text-xs text-muted-foreground">{dateTime(d.lastReadAt)}</div>
          {d.lastError && <div className="line-clamp-2 max-w-[16rem] text-xs text-rose-600">{d.lastError}</div>}
        </div>
      ) : '—',
    },
    { key: 'active', header: tx('Active'), render: (d) => (d.isActive ? tx('Yes') : tx('No')) },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="outline" onClick={() => testRead(d)} disabled={test.isPending}>{tx('Test read')}</Button>
          {d.adapterType === 'simulator' && <Button size="sm" variant="ghost" onClick={() => setWeight(d)}>{tx('Set weight')}</Button>}
          <Button size="sm" variant="ghost" onClick={() => setEditing(d)}>{tx('Edit')}</Button>
        </div>
      ),
    },
  ];
  return (
    <div className="space-y-3 pt-3">
      <div className="flex justify-end"><Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add indicator')}</Button></div>
      <ListCard>
        <DataTable columns={columns} data={devices.data ?? []} loading={devices.isLoading} getRowId={(d) => d.id}
          emptyMessage={devices.isError ? tx('Could not load the indicators.') : tx('No indicators yet.')} />
      </ListCard>
      {editing && <DeviceDialog device={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

const CONFIG_FIELDS: Record<string, Array<{ key: string; label: string; numeric?: boolean }>> = {
  simulator: [
    { key: 'weightKg', label: tx('Weight on the platform (kg)'), numeric: true },
    { key: 'motionFrames', label: tx('Motion frames before stable'), numeric: true },
    { key: 'mode', label: tx('Mode (ok, unstable, overload, silent, garbage, offline)') },
  ],
  'ascii-tcp': [
    { key: 'host', label: tx('Host *') },
    { key: 'port', label: tx('Port *'), numeric: true },
    { key: 'requestCommand', label: tx('Poll command (e.g. W\\r\\n)') },
    { key: 'frameRegex', label: tx('Frame regex (groups weight, status, sign, unit)') },
    { key: 'stableFrames', label: tx('Equal stable frames needed'), numeric: true },
    { key: 'unit', label: tx('Default unit (kg, t, lb)') },
    { key: 'decimals', label: tx('Implied decimals'), numeric: true },
  ],
  'ascii-serial': [
    { key: 'port', label: tx('Serial device (e.g. /dev/ttyUSB0) *') },
    { key: 'baudRate', label: tx('Baud rate'), numeric: true },
    { key: 'frameRegex', label: tx('Frame regex (groups weight, status, sign, unit)') },
    { key: 'stableFrames', label: tx('Equal stable frames needed'), numeric: true },
    { key: 'unit', label: tx('Default unit (kg, t, lb)') },
    { key: 'decimals', label: tx('Implied decimals'), numeric: true },
  ],
};

function DeviceDialog({ device, onClose }: { device?: Device; onClose: () => void }) {
  const lookups = useWeighbridgeLookups();
  const create = useCreateDevice();
  const update = useUpdateDevice();
  const [stationId, setStationId] = useState(device?.stationId ?? '');
  const [code, setCode] = useState(device?.code ?? '');
  const [name, setName] = useState(device?.name ?? '');
  const [adapterType, setAdapterType] = useState(device?.adapterType ?? 'simulator');
  const [timeout, setTimeoutMs] = useState(String(device?.readTimeoutMs ?? 5000));
  const [active, setActive] = useState(device?.isActive ?? true);
  const [config, setConfig] = useState<Record<string, string>>(() =>
    Object.fromEntries(Object.entries(device?.config ?? {}).map(([k, v]) => [k, v == null ? '' : String(v)])));
  const fields = CONFIG_FIELDS[adapterType] ?? [];
  const valid = stationId && code.trim() && Number(timeout) >= 500;
  const submit = async () => {
    const cfg: Record<string, unknown> = {};
    for (const f of fields) {
      const v = (config[f.key] ?? '').trim();
      if (v) cfg[f.key] = f.numeric ? Number(v) : v;
    }
    try {
      if (device) {
        await update.mutateAsync({ id: device.id, body: { stationId, code: code.trim(), name: name.trim() || undefined, config: cfg, readTimeoutMs: Number(timeout), isActive: active } });
      } else {
        await create.mutateAsync({ stationId, code: code.trim(), name: name.trim() || undefined, adapterType, config: cfg, readTimeoutMs: Number(timeout) });
      }
      notify.success(tx('Indicator saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the indicator')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{device ? tx('Edit indicator') : tx('Add indicator')}</DialogTitle>
          <DialogDescription>{tx('Continuous-output ASCII indicators are read until the weight is stable, within the timeout.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Station *')}>
            <OptionalSelect value={stationId} onChange={setStationId} placeholder={tx('Choose a station')}
              options={(lookups.data?.stations ?? []).map((s) => ({ value: s.id, label: s.code }))} />
          </Field>
          <Field label={tx('Adapter')}>
            <OptionalSelect value={adapterType} onChange={(v) => setAdapterType(v || 'simulator')} placeholder={tx('Adapter')} disabled={!!device}
              options={(lookups.data?.adapters ?? Object.keys(ADAPTER_LABEL)).map((a) => ({ value: a, label: ADAPTER_LABEL[a] ?? a }))} />
          </Field>
          <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value)} /></Field>
          <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Read timeout (ms)')}><Input type="number" value={timeout} onChange={(e) => setTimeoutMs(e.target.value)} /></Field>
          {device && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {tx('Active')}</label>}
          {fields.map((f) => (
            <Field key={f.key} label={f.label} className={f.key === 'frameRegex' ? 'sm:col-span-2' : undefined}>
              <Input className={f.key === 'frameRegex' ? 'font-mono text-xs' : undefined} type={f.numeric ? 'number' : 'text'}
                value={config[f.key] ?? ''} onChange={(e) => setConfig((c) => ({ ...c, [f.key]: e.target.value }))} />
            </Field>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ProductsTab() {
  const settings = useProductSettings();
  const [editing, setEditing] = useState<ProductSetting | 'new' | null>(null);
  const columns: Column<ProductSetting>[] = [
    { key: 'product', header: tx('Product'), render: (s) => <div className="font-medium">{s.product.name}<div className="text-xs text-muted-foreground">{s.product.code} · {s.product.baseUnit ?? ''}</div></div> },
    { key: 'kg', header: tx('kg per base unit'), className: 'text-right', render: (s) => (s.kgPerUnit ? Number(s.kgPerUnit).toLocaleString() : '—') },
    { key: 'pct', header: tx('Tolerance %'), className: 'text-right', render: (s) => (s.tolerancePct ? Number(s.tolerancePct) : tx('Station')) },
    { key: 'abs', header: tx('Tolerance kg'), className: 'text-right', render: (s) => (s.toleranceKg ? kg(s.toleranceKg) : tx('Station')) },
    { key: 'actions', header: '', className: 'text-right', render: (s) => <Button size="sm" variant="ghost" onClick={() => setEditing(s)}>{tx('Edit')}</Button> },
  ];
  return (
    <div className="space-y-3 pt-3">
      <p className="text-sm text-muted-foreground">{tx('Set kg per unit for products sold in m³, bags or pieces whose units have no weight conversion (e.g. 1 m³ of sand = 1,600 kg).')}</p>
      <div className="flex justify-end"><Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add product')}</Button></div>
      <ListCard>
        <DataTable columns={columns} data={settings.data ?? []} loading={settings.isLoading} getRowId={(s) => s.id}
          emptyMessage={settings.isError ? tx('Could not load the product settings.') : tx('No product settings yet.')} />
      </ListCard>
      {editing && <ProductDialog setting={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ProductDialog({ setting, onClose }: { setting?: ProductSetting; onClose: () => void }) {
  const [product, setProduct] = useState({ id: setting?.productId ?? '', label: setting?.product.name ?? '' });
  const [search, setSearch] = useState('');
  const products = useProductOptions(search, search.trim().length > 0);
  const [kgPerUnit, setKgPerUnit] = useState(setting?.kgPerUnit ?? '');
  const [pct, setPct] = useState(setting?.tolerancePct ?? '');
  const [abs, setAbs] = useState(setting?.toleranceKg ?? '');
  const put = usePutProductSetting();
  const submit = async () => {
    try {
      await put.mutateAsync({ productId: product.id, body: {
        kgPerUnit: kgPerUnit ? Number(kgPerUnit) : undefined, tolerancePct: pct ? Number(pct) : undefined, toleranceKg: abs ? Number(abs) : undefined,
      } });
      notify.success(tx('Product settings saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the product settings')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Product weighing settings')}</DialogTitle><DialogDescription>{tx('Leave a tolerance empty to use the station value. Clear everything to remove the settings.')}</DialogDescription></DialogHeader>
        <div className="grid grid-cols-1 gap-3">
          <Field label={tx('Product *')}>
            {setting ? <div className="text-sm font-medium">{setting.product.name}</div> : (
              <RemotePicker value={product.id} selectedLabel={product.label} search={search} onSearch={setSearch}
                results={(products.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.uomCode ?? undefined }))}
                loading={products.isFetching} onPick={(id, label) => setProduct({ id, label })} placeholder={tx('Search products…')} clearLabel={tx('Clear')} />
            )}
          </Field>
          <Field label={tx('kg per base unit')}><Input type="number" value={kgPerUnit} onChange={(e) => setKgPerUnit(e.target.value)} /></Field>
          <Field label={tx('Tolerance %')}><Input type="number" value={pct} onChange={(e) => setPct(e.target.value)} /></Field>
          <Field label={tx('Tolerance kg')}><Input type="number" value={abs} onChange={(e) => setAbs(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!product.id || put.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
