import { useState } from 'react';
import { CheckCircle2, ChevronDown, Crosshair, MapPin, Split, X } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { PlanMethod } from '@/features/sales/delivery-api';
import type { HandoverForm } from '../handover-draft';
import { Labeled, useStockLocations } from './DeliveryEditorDialog';
import { METHOD_META, PLAN_TO_METHOD } from './delivery-ui';

export const PLAN_METHODS: PlanMethod[] = ['pickup', 'delivery', 'transfer', 'courier'];

/**
 * Order-level delivery plan (Sections C and D): how the goods reach the customer, from where, to where, and whether
 * the order may be split into several deliveries. These are the defaults every new delivery starts from. Invoices and
 * branch cash sales reuse it: {@code methods} limits the method cards to what the document supports and {@code hide}
 * drops fields it does not store (a hidden {@code locationId} shows the document's fixed stock location read-only).
 */
export function DeliveryPlanPanel({ value, onChange, locked, fallbackLocationName, methods = PLAN_METHODS, hide = [], defaultOpen = false }: {
  value: HandoverForm; onChange: (f: HandoverForm) => void; locked: boolean; fallbackLocationName?: string | null;
  methods?: PlanMethod[]; hide?: Array<keyof HandoverForm>; defaultOpen?: boolean;
}) {
  const locations = useStockLocations();
  const [more, setMore] = useState(defaultOpen);
  const shown = (k: keyof HandoverForm) => !hide.includes(k);
  const set = <K extends keyof HandoverForm>(k: K, v: HandoverForm[K]) => onChange({ ...value, [k]: v });
  const m = value.method;
  const multiple = value.deliveryScope !== 'full';
  const locs = (locations.data?.data ?? []).filter((l) => l.isActive || l.id === value.locationId || l.id === value.destinationLocationId);
  const locName = locs.find((l) => l.id === value.locationId)?.name ?? fallbackLocationName ?? null;

  const locate = () => {
    if (!navigator.geolocation) { toast.error(tx('Location is not available on this device')); return; }
    navigator.geolocation.getCurrentPosition(
      (p) => set('gpsCoordinates', `${p.coords.latitude.toFixed(6)}, ${p.coords.longitude.toFixed(6)}`),
      () => toast.error(tx('Could not read your location')),
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  };

  const text = (k: keyof HandoverForm, label: string, placeholder?: string, type = 'text') => shown(k) && (
    <Labeled label={label}>
      {locked ? <p className="py-2 text-sm">{(value[k] as string) || '—'}</p>
        : <Input type={type} value={(value[k] as string) ?? ''} placeholder={placeholder} onChange={(e) => set(k, e.target.value as never)} />}
    </Labeled>
  );
  const warehouse = (k: 'locationId' | 'destinationLocationId', label: string) => (
    <Labeled label={label}>
      {!shown(k) ? <p className="py-2 text-sm">{k === 'locationId' ? fallbackLocationName ?? '—' : '—'}</p> : locked ? <p className="py-2 text-sm">{k === 'locationId' ? locName ?? '—' : locs.find((l) => l.id === value[k])?.name ?? '—'}</p> : (
        <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={value[k] ?? ''} onChange={(e) => set(k, e.target.value)}>
          <option value="">{tx('Select a warehouse')}</option>
          {locs.map((l) => <option key={l.id} value={l.id}>{l.name} ({l.code})</option>)}
        </select>
      )}
    </Labeled>
  );

  return (
    <div className="space-y-5">
      <div>
        <h3 className="mb-3 flex items-center gap-2 text-sm font-semibold"><MapPin className="h-4 w-4 text-primary" />{tx('Delivery method')}</h3>
        <div className={cn('grid grid-cols-2 gap-3', methods.length > 2 && 'lg:grid-cols-4')} role="radiogroup" aria-label={tx('Delivery method')}>
          {methods.map((pm) => {
            const meta = METHOD_META[PLAN_TO_METHOD[pm]];
            const Icon = meta.icon;
            const on = m === pm;
            return (
              <button key={pm} type="button" role="radio" aria-checked={on} disabled={locked} onClick={() => set('method', pm)}
                className={cn('relative flex flex-col items-center gap-1.5 rounded-xl border bg-card px-3 py-4 text-center shadow-sm transition-all disabled:cursor-default',
                  on ? 'border-primary ring-2 ring-primary/30' : 'hover:border-primary/40 hover:bg-muted/40', locked && !on && 'opacity-50')}>
                {on && <CheckCircle2 className="absolute right-2 top-2 h-5 w-5 fill-primary text-primary-foreground" />}
                <Icon className={cn('h-7 w-7', on ? 'text-primary' : 'text-muted-foreground')} />
                <span className="text-sm font-semibold">{meta.label}</span>
                <span className="text-xs text-muted-foreground">{meta.hint}</span>
              </button>
            );
          })}
        </div>
      </div>

      <div className="grid items-end gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        {m === 'transfer' ? warehouse('destinationLocationId', tx('Destination warehouse')) : m === 'pickup' ? warehouse('locationId', tx('Collect from (store / warehouse)')) : (
          <Labeled label={tx('Delivery address / site')}>
            {locked ? <p className="py-2 text-sm">{value.address || '—'}</p> : (
              <div className="relative">
                <MapPin className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input className="pl-9 pr-9" value={value.address} placeholder={tx('e.g. Plot 12, Industrial Area, Kampala')} onChange={(e) => set('address', e.target.value)} />
                {value.address && (
                  <button type="button" aria-label={tx('Clear')} className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-muted"
                    onClick={() => set('address', '')}><X className="h-4 w-4" /></button>
                )}
              </div>
            )}
          </Labeled>
        )}
        <div className="flex items-center justify-between gap-3 rounded-lg border px-3 py-2">
          <div>
            <p className="flex items-center gap-1.5 text-sm font-medium"><Split className="h-4 w-4 text-primary" />{tx('Allow multiple deliveries')}</p>
            <p className="text-xs text-muted-foreground">{multiple ? tx('Partial delivery allowed — split into several shipments') : tx('Full delivery in one shipment')}</p>
          </div>
          <button type="button" role="switch" aria-checked={multiple} disabled={locked} onClick={() => set('deliveryScope', multiple ? 'full' : 'partial')}
            className={cn('relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors disabled:opacity-60', multiple ? 'bg-primary' : 'bg-muted-foreground/30')}>
            <span className={cn('inline-block h-5 w-5 rounded-full bg-white shadow transition-transform', multiple ? 'translate-x-5' : 'translate-x-0.5')} />
          </button>
        </div>
      </div>

      <div>
        <button type="button" className="flex items-center gap-1 text-sm font-medium text-primary hover:underline" onClick={() => setMore((x) => !x)} aria-expanded={more}>
          <ChevronDown className={cn('h-4 w-4 transition-transform', more && 'rotate-180')} />{more ? tx('Hide location & site details') : tx('Location & site details')}
        </button>
        {more && (
          <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {m !== 'pickup' && warehouse('locationId', tx('Dispatch warehouse'))}
            {m === 'courier' && text('courierName', tx('Courier / logistics company'))}
            {m === 'transfer' && text('address', tx('Destination address'))}
            {m !== 'pickup' && shown('gpsCoordinates') && (
              <Labeled label={tx('GPS coordinates')}>
                {locked ? <p className="py-2 text-sm">{value.gpsCoordinates || '—'}</p> : (
                  <div className="flex gap-1.5">
                    <Input value={value.gpsCoordinates ?? ''} placeholder="0.3476, 32.5825" onChange={(e) => set('gpsCoordinates', e.target.value)} />
                    <Button type="button" variant="outline" size="icon" title={tx('Use my location')} aria-label={tx('Use my location')} onClick={locate}><Crosshair className="h-4 w-4" /></Button>
                  </div>
                )}
              </Labeled>
            )}
            {m !== 'pickup' && text('landmark', tx('Landmark'), tx('e.g. Opposite Total petrol station'))}
            {text('loadingSite', tx('Loading location'))}
            {m !== 'pickup' && text('offloadingSite', tx('Offloading location'))}
            {text('contactName', m === 'pickup' ? tx('Collected by') : tx('Contact person'))}
            {text('contactPhone', tx('Contact telephone'), undefined, 'tel')}
            {m === 'delivery' && shown('offloadingEquipment') && (
              <Labeled label={tx('Offloading equipment')}>
                {locked ? <p className="py-2 text-sm">{value.offloadingEquipment === 'yes' ? tx('Required') : value.offloadingEquipment === 'no' ? tx('Not required') : '—'}</p> : (
                  <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={value.offloadingEquipment} onChange={(e) => set('offloadingEquipment', e.target.value as HandoverForm['offloadingEquipment'])}>
                    <option value="">—</option><option value="yes">{tx('Required')}</option><option value="no">{tx('Not required')}</option>
                  </select>
                )}
              </Labeled>
            )}
            {text('scheduledDate', tx('Planned date'), undefined, 'date')}
            {text('deadline', tx('Deadline'), undefined, 'date')}
          </div>
        )}
      </div>
    </div>
  );
}
