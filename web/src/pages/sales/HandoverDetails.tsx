import { useQuery } from '@tanstack/react-query';
import { MapPin, Store, Truck } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { locationsApi } from '@/lib/api/locations';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { Field } from '@/pages/crm/crm-ui';
import type { HandoverForm } from './handover-draft';

export const SCOPE_OPTIONS = [['full', tx('Full delivery')], ['partial', tx('Partial delivery')]] as const;
export const YES_NO_OPTIONS = [['yes', tx('Yes')], ['no', tx('No')]] as const;

/** Read-only text or a select of fixed options (empty = not set). */
export function OptionField({ label, value, options, locked, onChange }: {
  label: string; value: string; options: ReadonlyArray<readonly [string, string]>; locked: boolean; onChange: (v: string) => void;
}) {
  return (
    <Field label={label}>
      {locked ? <p className="py-2 text-sm">{options.find(([v]) => v === value)?.[1] ?? '—'}</p> : (
        <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">—</option>
          {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
      )}
    </Field>
  );
}

function useStockLocations() {
  return useQuery({ queryKey: ['inventory', 'locations', 'handover'], queryFn: () => locationsApi.list({ pageSize: 200 }), staleTime: 60_000 });
}

/**
 * Delivery details form: method (customer collect / delivery to site), store / warehouse, contact, date, driver,
 * vehicle, receiver and notes. Used by the live Delivery & fulfilment tab and by new orders / invoices.
 */
export function HandoverDetails({ f, onChange, locked = () => false, fallbackLocationName, hideReceipt, hideLocation }: {
  f: HandoverForm; onChange: (f: HandoverForm) => void; locked?: (k: keyof HandoverForm) => boolean;
  fallbackLocationName?: string | null; hideReceipt?: boolean; hideLocation?: boolean;
}) {
  const locations = useStockLocations();
  const isDelivery = f.method === 'delivery';
  const set = (k: keyof HandoverForm, v: string) => onChange({ ...f, [k]: v });
  const locationName = (locations.data?.data ?? []).find((l) => l.id === f.locationId)?.name ?? fallbackLocationName ?? null;
  const text = (k: keyof HandoverForm, label: string, type = 'text') => (
    <Field label={label}>
      {locked(k) ? <p className="py-2 text-sm">{(type === 'date' && f[k] ? date(f[k]) : f[k]) || '—'}</p>
        : <Input type={type} value={f[k]} onChange={(e) => set(k, e.target.value)} />}
    </Field>
  );
  return (
    <section>
      <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><MapPin className="h-4 w-4" />{tx('Delivery details')}</h3>
      <Field label={tx('Delivery method')}>
        <div className="mb-3 flex gap-2">
          {([['pickup', tx('Customer collect'), Store], ['delivery', tx('Delivery to site'), Truck]] as const).map(([v, label, Icon]) => (
            <button key={v} type="button" disabled={locked('method')} onClick={() => set('method', v)}
              className={cn('flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm disabled:cursor-default',
                f.method === v ? 'border-primary bg-primary/10 font-medium text-primary' : 'text-muted-foreground hover:bg-muted', locked('method') && f.method !== v && 'opacity-50')}>
              <Icon className="h-4 w-4" />{label}
            </button>
          ))}
        </div>
      </Field>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {!hideLocation && <Field label={isDelivery ? tx('Dispatch from (store / warehouse)') : tx('Collect from (store / warehouse)')}>
          {locked('locationId') ? <p className="py-2 text-sm">{locationName ?? '—'}</p> : (
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={f.locationId} onChange={(e) => set('locationId', e.target.value)}>
              <option value="">{tx('Select a location')}</option>
              {(locations.data?.data ?? []).filter((l) => l.isActive || l.id === f.locationId).map((l) => <option key={l.id} value={l.id}>{l.name} ({l.code})</option>)}
            </select>
          )}
        </Field>}
        {isDelivery && <div className={hideLocation ? 'sm:col-span-2 lg:col-span-3' : 'sm:col-span-1 lg:col-span-2'}>{text('address', tx('Delivery address / site'))}</div>}
        {isDelivery && text('landmark', tx('GPS / Landmark'))}
        {text('loadingSite', tx('Loading site'))}
        <OptionField label={tx('Delivery scope')} value={f.deliveryScope} options={SCOPE_OPTIONS} locked={locked('deliveryScope')} onChange={(v) => set('deliveryScope', v)} />
        {isDelivery && <OptionField label={tx('Offloading equipment')} value={f.offloadingEquipment} options={YES_NO_OPTIONS} locked={locked('offloadingEquipment')} onChange={(v) => set('offloadingEquipment', v)} />}
        {text('contactName', isDelivery ? tx('Contact person') : tx('Collected by (name)'))}
        {text('contactPhone', tx('Contact phone'))}
        {text('scheduledDate', isDelivery ? tx('Scheduled date') : tx('Pickup date'), 'date')}
        {isDelivery && text('driver', tx('Driver'))}
        {isDelivery && text('vehicle', tx('Vehicle'))}
        {!hideReceipt && text('receivedBy', tx('Received by'))}
        <div className="sm:col-span-2 lg:col-span-3">
          <Field label={tx('Delivery notes')}>
            {locked('notes') ? <p className="py-2 text-sm">{f.notes || '—'}</p>
              : <Textarea rows={2} value={f.notes} onChange={(e) => set('notes', e.target.value)} />}
          </Field>
        </div>
      </div>
    </section>
  );
}

/** Status flow ribbon (Pending › Scheduled › Dispatched › Delivered / Collected). */
export function HandoverSteps({ status, isDelivery }: { status: 'pending' | 'scheduled' | 'dispatched' | 'delivered' | 'failed'; isDelivery: boolean }) {
  const steps = ['pending', 'scheduled', 'dispatched', 'delivered'] as const;
  const label: Record<string, string> = {
    pending: tx('Pending'), scheduled: tx('Scheduled'), dispatched: tx('Dispatched'),
    delivered: isDelivery ? tx('Delivered') : tx('Collected'), failed: tx('Failed'),
  };
  const idx = steps.indexOf(status as (typeof steps)[number]);
  return (
    <ol className="flex flex-wrap items-center gap-1 text-xs">
      {steps.map((st, i) => (
        <li key={st} className="flex items-center gap-1">
          <span className={cn('rounded-full border px-2.5 py-1 font-medium',
            status === 'failed' ? 'text-muted-foreground'
              : i < idx ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:bg-transparent'
                : i === idx ? 'border-primary bg-primary text-primary-foreground' : 'text-muted-foreground')}>
            {label[st]}
          </span>
          {i < steps.length - 1 && <span className="text-muted-foreground">›</span>}
        </li>
      ))}
      {status === 'failed' && <li className="ml-2 rounded-full border border-rose-200 bg-rose-50 px-2.5 py-1 font-medium text-rose-800 dark:bg-transparent">{label.failed}</li>}
    </ol>
  );
}

/** Delivery & fulfilment on a new (unsaved) order or invoice: plan the handover; it is saved with the document. */
export function HandoverDraftPanel({ value, onChange, hint, hideLocation }: { value: HandoverForm; onChange: (f: HandoverForm) => void; hint: string; hideLocation?: boolean }) {
  return (
    <div className="space-y-5">
      <section className="flex flex-wrap items-center justify-between gap-3">
        <HandoverSteps status="pending" isDelivery={value.method === 'delivery'} />
      </section>
      <p className="text-xs text-muted-foreground">{hint}</p>
      <HandoverDetails f={value} onChange={onChange} hideReceipt hideLocation={hideLocation} />
    </div>
  );
}
