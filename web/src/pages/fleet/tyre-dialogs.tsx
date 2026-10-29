import { useMemo, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups, usePartnerPicker, useVehicles } from '@/features/fleet/api';
import {
  TYRE_TYPE_LABEL, positionLabel, useCreateTyre, useTyreAction, useTyreProducts, useTyres, useUpdateTyre,
  useVehicleTyres, type Tyre,
} from '@/features/fleet/tyres-api';
import { Field, OptionalSelect, RemotePicker } from './fleet-ui';
import { fromLocalInput, toLocalInput, toOptions } from './fleet-format';

/** Dialogs of the tyre screens (fleet 1.7): register / edit, fit, rotate, remove, retread, scrap, tread reading. */

const isNum = (v: string) => v !== '' && Number.isFinite(Number(v)) && Number(v) >= 0;
const numOrUndef = (v: string) => (v === '' ? undefined : Number(v));

export function TyreFormDialog({ tyre, open, onOpenChange, onSaved }: {
  tyre?: Tyre; open: boolean; onOpenChange: (o: boolean) => void; onSaved?: (t: Tyre) => void;
}) {
  const [serial, setSerial] = useState(tyre?.serialNumber ?? '');
  const [brand, setBrand] = useState(tyre?.brand ?? '');
  const [model, setModel] = useState(tyre?.model ?? '');
  const [size, setSize] = useState(tyre?.size ?? '');
  const [type, setType] = useState<string>(tyre?.tyreType ?? 'all_position');
  const [cost, setCost] = useState(tyre?.purchaseCost ?? '');
  const [purchasedOn, setPurchasedOn] = useState(tyre?.purchasedOn ?? '');
  const [invoice, setInvoice] = useState(tyre?.invoiceReference ?? '');
  const [tread, setTread] = useState(tyre?.newTreadDepthMm ?? '');
  const [minTread, setMinTread] = useState(tyre?.minTreadDepthMm ?? '3');
  const [supplier, setSupplier] = useState({ id: tyre?.supplierPartnerId ?? '', label: tyre?.supplier?.name ?? '' });
  const [supplierSearch, setSupplierSearch] = useState('');
  const [product, setProduct] = useState({ id: tyre?.productId ?? '', label: tyre?.product?.name ?? '' });
  const [productSearch, setProductSearch] = useState('');
  const [location, setLocation] = useState(tyre?.stockLocationId ?? '');
  const [notes, setNotes] = useState(tyre?.notes ?? '');
  const partners = usePartnerPicker(supplierSearch, supplierSearch.trim().length > 0);
  const products = useTyreProducts(productSearch, productSearch.trim().length > 0);
  const lookups = useFleetLookups();
  const create = useCreateTyre();
  const update = useUpdateTyre();
  const issued = !!tyre?.stockIssuedAt;
  const valid = serial.trim().length > 0 && (cost === '' || isNum(cost)) && (tread === '' || isNum(tread))
    && (minTread === '' || isNum(minTread));

  const submit = async () => {
    const body: Record<string, unknown> = {
      serialNumber: serial.trim(), brand: brand.trim() || undefined, model: model.trim() || undefined,
      size: size.trim() || undefined, tyreType: type, purchaseCost: numOrUndef(cost), purchasedOn: purchasedOn || undefined,
      invoiceReference: invoice.trim() || undefined, newTreadDepthMm: numOrUndef(tread), minTreadDepthMm: numOrUndef(minTread),
      supplierPartnerId: supplier.id || undefined, productId: product.id || undefined, stockLocationId: location || undefined,
      notes: notes.trim() || undefined,
    };
    try {
      if (tyre) {
        const clear = ['brand', 'model', 'size', 'invoiceReference', 'notes', 'purchasedOn', 'purchaseCost', 'supplierPartnerId',
          'productId', 'stockLocationId', 'newTreadDepthMm']
          .filter((k) => body[k] === undefined && (tyre as unknown as Record<string, unknown>)[k] != null)
          .filter((k) => !(issued && k === 'productId'));
        const saved = await update.mutateAsync({ id: tyre.id, body: { ...body, clear } });
        notify.success(tx('Tyre saved'));
        onSaved?.(saved);
      } else {
        const saved = await create.mutateAsync(body);
        notify.success(tx('Tyre registered'));
        onSaved?.(saved);
      }
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the tyre')));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{tyre ? tx('Edit tyre {{serial}}', { serial: tyre.serialNumber }) : tx('Register tyre')}</DialogTitle>
          <DialogDescription>{tx('Tyres are tracked by serial number. A tyre linked to a stock product is issued from stock on its first fitment.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Serial number *')}><Input value={serial} onChange={(e) => setSerial(e.target.value)} /></Field>
          <Field label={tx('Type')}>
            <OptionalSelect value={type} onChange={(v) => setType(v || 'all_position')} placeholder={tx('Type')} options={toOptions(TYRE_TYPE_LABEL)} />
          </Field>
          <Field label={tx('Brand')}><Input value={brand} onChange={(e) => setBrand(e.target.value)} /></Field>
          <Field label={tx('Model / pattern')}><Input value={model} onChange={(e) => setModel(e.target.value)} /></Field>
          <Field label={tx('Size')}><Input value={size} placeholder="315/80R22.5" onChange={(e) => setSize(e.target.value)} /></Field>
          <Field label={tx('Purchase cost')} error={cost !== '' && !isNum(cost) ? tx('Enter a valid amount') : undefined}>
            <Input inputMode="decimal" value={cost} onChange={(e) => setCost(e.target.value)} />
          </Field>
          <Field label={tx('Purchased on')}><Input type="date" value={purchasedOn} onChange={(e) => setPurchasedOn(e.target.value)} /></Field>
          <Field label={tx('Invoice reference')}><Input value={invoice} onChange={(e) => setInvoice(e.target.value)} /></Field>
          <Field label={tx('New tread depth (mm)')}><Input inputMode="decimal" value={tread} onChange={(e) => setTread(e.target.value)} /></Field>
          <Field label={tx('Minimum tread depth (mm)')}><Input inputMode="decimal" value={minTread} onChange={(e) => setMinTread(e.target.value)} /></Field>
          <Field label={tx('Supplier')} className="sm:col-span-2">
            <RemotePicker value={supplier.id} selectedLabel={supplier.label} search={supplierSearch} onSearch={setSupplierSearch}
              results={(partners.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.code ?? undefined }))}
              loading={partners.isFetching} onPick={(id, label) => setSupplier({ id, label })}
              placeholder={tx('Search suppliers…')} clearLabel={tx('Clear')} />
          </Field>
          <Field label={tx('Stock product (issue from stock on fitment)')} className="sm:col-span-2">
            {issued ? <p className="text-sm">{product.label || product.id}</p> : (
              <RemotePicker value={product.id} selectedLabel={product.label} search={productSearch} onSearch={setProductSearch}
                results={(products.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.sku ?? undefined }))}
                loading={products.isFetching} onPick={(id, label) => setProduct({ id, label })}
                placeholder={tx('Search products…')} clearLabel={tx('Clear')} />
            )}
          </Field>
          {product.id && (
            <Field label={tx('Stock location')} className="sm:col-span-2">
              <OptionalSelect value={location} onChange={setLocation} placeholder={tx("Vehicle's home site")} noneLabel={tx("Vehicle's home site")}
                options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: l.name }))} />
            </Field>
          )}
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Odometer / hours / date fields shared by fit, rotate and remove (blank = the vehicle's current meters, now). */
function MeterFields({ odometer, setOdometer, hours, setHours, at, setAt }: {
  odometer: string; setOdometer: (v: string) => void; hours: string; setHours: (v: string) => void;
  at: string; setAt: (v: string) => void;
}) {
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <Field label={tx('Odometer (km)')}><Input inputMode="decimal" className="h-11" value={odometer} onChange={(e) => setOdometer(e.target.value)} placeholder={tx('Current')} /></Field>
      <Field label={tx('Engine hours')}><Input inputMode="decimal" className="h-11" value={hours} onChange={(e) => setHours(e.target.value)} placeholder={tx('Current')} /></Field>
      <Field label={tx('When (blank = now)')}><Input type="datetime-local" className="h-11" value={at} max={toLocalInput(new Date())} onChange={(e) => setAt(e.target.value)} /></Field>
    </div>
  );
}

export type TyreActionKind = 'fit' | 'rotate' | 'remove' | 'retread' | 'scrap' | 'readings';

const ACTION_TITLE: Record<TyreActionKind, string> = {
  fit: tx('Fit tyre'),
  rotate: tx('Rotate tyre'),
  remove: tx('Remove tyre'),
  retread: tx('Record retread'),
  scrap: tx('Scrap tyre'),
  readings: tx('Tread reading'),
};

/**
 * One lifecycle action. For {@code fit} either the tyre is known (pick vehicle + position) or the position is known
 * (pick an available tyre). Phone-friendly (large inputs).
 */
export function TyreActionDialog({ kind, tyre, vehicleId, position, onClose }: {
  kind: TyreActionKind; tyre?: Tyre; vehicleId?: string; position?: string; onClose: () => void;
}) {
  const [tyreId, setTyreId] = useState(tyre?.id ?? '');
  const [vehicle, setVehicle] = useState(vehicleId ?? tyre?.vehicleId ?? '');
  const [pos, setPos] = useState(position ?? '');
  const [odometer, setOdometer] = useState('');
  const [hours, setHours] = useState('');
  const [at, setAt] = useState('');
  const [tread, setTread] = useState('');
  const [pressure, setPressure] = useState('');
  const [reason, setReason] = useState('');
  const [cost, setCost] = useState('');
  const [scrap, setScrap] = useState(false);
  const [notes, setNotes] = useState('');
  const action = useTyreAction();
  const pickTyre = kind === 'fit' && !tyre;
  const pickVehicle = kind === 'fit' && !vehicleId;
  const available = useTyres({ page: 1, pageSize: 200, sortBy: 'serialNumber' }, pickTyre);
  const vehicles = useVehicles({ page: 1, pageSize: 200, status: 'active', sortBy: 'code' });
  const layout = useVehicleTyres(vehicle, !!vehicle && (kind === 'fit' || kind === 'rotate'));

  const positions = useMemo(() => {
    if (!layout.data) return [];
    const all = [...layout.data.axles.flatMap((a) => a.positions), ...layout.data.spares];
    return all
      .filter((p) => (kind === 'fit' ? !p.tyre : p.code !== tyre?.position))
      .map((p) => ({ value: p.code, label: `${p.code} — ${positionLabel(p.code)}${p.tyre ? ` (${tx('swap with {{serial}}', { serial: p.tyre.serialNumber })})` : ''}` }));
  }, [layout.data, kind, tyre?.position]);

  const tyreOptions = (available.data?.data ?? [])
    .filter((t) => ['in_stock', 'removed', 'retreaded'].includes(t.status))
    .map((t) => ({ value: t.id, label: t.serialNumber, hint: [t.brand, t.size].filter(Boolean).join(' · ') }));

  const meterOk = (odometer === '' || isNum(odometer)) && (hours === '' || isNum(hours));
  const valid = (() => {
    switch (kind) {
      case 'fit': return !!tyreId && !!vehicle && !!pos && meterOk && (tread === '' || isNum(tread));
      case 'rotate': return !!pos && meterOk;
      case 'remove': return reason.trim().length > 0 && meterOk && (tread === '' || isNum(tread));
      case 'retread': return isNum(cost) && isNum(tread) && Number(tread) > 0;
      case 'scrap': return reason.trim().length > 0;
      case 'readings': return isNum(tread) && (pressure === '' || isNum(pressure));
    }
  })();

  const submit = async () => {
    const when = fromLocalInput(at);
    const meters = { odometer: numOrUndef(odometer), hours: numOrUndef(hours), at: when };
    const body: Record<string, unknown> = (() => {
      switch (kind) {
        case 'fit': return { vehicleId: vehicle, position: pos, ...meters, treadDepthMm: numOrUndef(tread), notes: notes.trim() || undefined };
        case 'rotate': return { position: pos, ...meters, notes: notes.trim() || undefined };
        case 'remove': return { reason: reason.trim(), ...meters, treadDepthMm: numOrUndef(tread), scrap, notes: notes.trim() || undefined };
        case 'retread': return { cost: Number(cost), newTreadDepthMm: Number(tread), at: when, notes: notes.trim() || undefined };
        case 'scrap': return { reason: reason.trim(), at: when };
        case 'readings': return { treadDepthMm: Number(tread), pressureKpa: numOrUndef(pressure), readingAt: when, notes: notes.trim() || undefined };
      }
    })();
    try {
      await action.mutateAsync({ id: tyreId, action: kind, body });
      notify.success(tx('Saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the change')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{ACTION_TITLE[kind]}{tyre ? ` · ${tyre.serialNumber}` : ''}</DialogTitle>
          <DialogDescription>
            {kind === 'rotate' ? tx('A tyre already at the new position swaps to the vacated one.')
              : kind === 'remove' ? tx('The distance since fitment is added to the tyre.')
                : kind === 'retread' ? tx('The retread cost is added to the tyre and its tread starts again.')
                  : tx('Meter readings left blank use the vehicle\'s current meters.')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          {pickTyre && (
            <Field label={tx('Tyre *')}>
              <SearchableSelect value={tyreId} onValueChange={setTyreId} options={tyreOptions} placeholder={tx('Choose a tyre')} searchPlaceholder={tx('Search serial…')} />
            </Field>
          )}
          {pickVehicle && (
            <Field label={tx('Vehicle *')}>
              <SearchableSelect value={vehicle} onValueChange={(v) => { setVehicle(v); setPos(''); }} placeholder={tx('Choose a vehicle')} searchPlaceholder={tx('Search code or plate…')}
                options={(vehicles.data?.data ?? []).map((v) => ({ value: v.id, label: v.code, hint: v.registrationPlate ?? undefined }))} />
            </Field>
          )}
          {(kind === 'fit' || kind === 'rotate') && !position && (
            <Field label={tx('Position *')}>
              <OptionalSelect value={pos} onChange={setPos} placeholder={layout.isLoading ? '…' : tx('Choose a position')} options={positions} disabled={!vehicle} />
            </Field>
          )}
          {position && <p className="text-sm">{tx('Position')}: <span className="font-medium">{position} — {positionLabel(position)}</span></p>}
          {(kind === 'fit' || kind === 'rotate' || kind === 'remove') && (
            <MeterFields odometer={odometer} setOdometer={setOdometer} hours={hours} setHours={setHours} at={at} setAt={setAt} />
          )}
          {(kind === 'retread' || kind === 'scrap' || kind === 'readings') && (
            <Field label={tx('When (blank = now)')}><Input type="datetime-local" value={at} max={toLocalInput(new Date())} onChange={(e) => setAt(e.target.value)} /></Field>
          )}
          {(kind === 'fit' || kind === 'remove' || kind === 'readings') && (
            <Field label={kind === 'readings' ? tx('Tread depth (mm) *') : tx('Tread depth (mm)')}>
              <Input inputMode="decimal" className="h-11" value={tread} onChange={(e) => setTread(e.target.value)} />
            </Field>
          )}
          {kind === 'readings' && (
            <Field label={tx('Pressure (kPa)')}><Input inputMode="decimal" className="h-11" value={pressure} onChange={(e) => setPressure(e.target.value)} /></Field>
          )}
          {kind === 'retread' && (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label={tx('Retread cost *')}><Input inputMode="decimal" value={cost} onChange={(e) => setCost(e.target.value)} /></Field>
              <Field label={tx('New tread depth (mm) *')}><Input inputMode="decimal" value={tread} onChange={(e) => setTread(e.target.value)} /></Field>
            </div>
          )}
          {(kind === 'remove' || kind === 'scrap') && (
            <Field label={tx('Reason *')}><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          )}
          {kind === 'remove' && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={scrap} onChange={(e) => setScrap(e.target.checked)} /> {tx('Scrap the tyre')}
            </label>
          )}
          {kind !== 'scrap' && (
            <Field label={tx('Notes')}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button className="h-11" onClick={submit} disabled={!valid || action.isPending}>{ACTION_TITLE[kind]}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
