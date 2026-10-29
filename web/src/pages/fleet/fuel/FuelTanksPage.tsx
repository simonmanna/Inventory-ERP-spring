import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Cylinder, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  useCreateTank, useFuelLookups, useFuelTanks, useProductPicker, useUpdateTank, type FuelTank,
} from '@/features/fleet/fuel-api';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';
import { num } from '../fleet-format';
import { DipStatusPill } from './fuel-ui';

/** Level bar of a tank: fill % of capacity, amber below the reorder level, red above capacity. */
export function TankGauge({ tank }: { tank: FuelTank }) {
  const pct = Math.max(0, Math.min(100, Number(tank.fillPct ?? 0)));
  const color = tank.overCapacity ? 'bg-rose-500' : tank.belowReorder ? 'bg-amber-500' : 'bg-emerald-500';
  return (
    <div className="space-y-1">
      <div className="h-3 w-full overflow-hidden rounded-full bg-muted">
        <div className={`h-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <div className="flex justify-between text-xs text-muted-foreground">
        <span className="tabular-nums">{num(tank.bookLitres, 0)} / {num(tank.capacityL, 0)} L</span>
        <span className="tabular-nums">{num(tank.fillPct, 1)}%</span>
      </div>
    </div>
  );
}

/** Site fuel tanks and bowsers (fleet 1.5): level, value, last dip; register a tank. */
export function FuelTanksPage() {
  const navigate = useNavigate();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetFuel.tank));
  const [showInactive, setShowInactive] = useState(false);
  const { data, isLoading, isError } = useFuelTanks(showInactive);
  const [editing, setEditing] = useState<FuelTank | 'new' | null>(null);

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Cylinder} title={tx('Fuel Tanks')}
        description={tx('Site tanks and bowsers. Fuel comes in through a goods receipt to the tank location and goes out as fuel logs.')}
        actions={
          <>
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} /> {tx('Show inactive')}
            </label>
            {canManage && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add tank')}</Button>}
          </>
        } />
      <p className="text-sm text-muted-foreground">
        {tx('To fill a tank, receive the fuel with a goods receipt (Procurement → Goods receipts) into the tank\'s location; the book level updates at once.')}{' '}
        <Link className="text-primary hover:underline" to="/procurement/goods-receipts">{tx('Open goods receipts')}</Link>
      </p>
      {isLoading && <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3"><Skeleton className="h-40" /><Skeleton className="h-40" /></div>}
      {isError && <p className="text-sm text-destructive">{tx('Could not load the tanks. Try again.')}</p>}
      {!isLoading && (data ?? []).length === 0 && (
        <div className="rounded-xl border bg-card p-8 text-center text-sm text-muted-foreground">{tx('No fuel tanks registered yet.')}</div>
      )}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {(data ?? []).map((t) => (
          <button key={t.id} type="button" onClick={() => navigate(`/fleet/fuel/tanks/${t.id}`)}
            className="space-y-3 rounded-xl border bg-card p-4 text-left shadow-sm hover:border-primary/50">
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-semibold">{t.code} · {t.name}</div>
                <div className="text-xs text-muted-foreground">{t.product.name} · {t.location.name}</div>
              </div>
              <div className="flex flex-col items-end gap-1">
                {!t.isActive && <StatusPill tone="neutral">{tx('Inactive')}</StatusPill>}
                {t.belowReorder && <StatusPill tone="warning">{tx('Below reorder level')}</StatusPill>}
                {t.overCapacity && <StatusPill tone="danger">{tx('Over capacity')}</StatusPill>}
              </div>
            </div>
            <TankGauge tank={t} />
            <div className="flex flex-wrap justify-between gap-2 text-xs text-muted-foreground">
              <span>{tx('Value')} {money(t.bookValue)}</span>
              {t.lastDip ? (
                <span className="flex items-center gap-1">{tx('Last dip')} {dateTime(t.lastDip.dippedAt)} <DipStatusPill status={t.lastDip.status} /></span>
              ) : <span>{tx('No dips yet')}</span>}
            </div>
            {t.draftIssues > 0 && <div className="text-xs text-amber-700">{tx('{{count}} draft issues not posted', { count: t.draftIssues })}</div>}
          </button>
        ))}
      </div>
      {editing && <TankDialog tank={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

/** Register or edit a tank (location and product are fixed once registered). */
export function TankDialog({ tank, onClose }: { tank: FuelTank | null; onClose: () => void }) {
  const lookups = useFuelLookups();
  const create = useCreateTank();
  const update = useUpdateTank();
  const [code, setCode] = useState(tank?.code ?? '');
  const [name, setName] = useState(tank?.name ?? '');
  const [kind, setKind] = useState<FuelTank['kind']>(tank?.kind ?? 'tank');
  const [locationId, setLocationId] = useState(tank?.locationId ?? '');
  const [productId, setProductId] = useState(tank?.productId ?? '');
  const [productLabel, setProductLabel] = useState(tank?.product.name ?? '');
  const [productSearch, setProductSearch] = useState('');
  const products = useProductPicker(productSearch, productSearch.trim().length > 0);
  const [capacity, setCapacity] = useState(tank?.capacityL ?? '');
  const [reorder, setReorder] = useState(tank?.reorderLevelL ?? '');
  const [bowserVehicleId, setBowserVehicleId] = useState(tank?.bowserVehicleId ?? '');
  const [notes, setNotes] = useState(tank?.notes ?? '');
  const busy = create.isPending || update.isPending;
  const valid = name.trim() && Number(capacity) > 0 && (tank || (locationId && productId));

  const submit = async () => {
    const common: Record<string, unknown> = {
      name: name.trim(), kind, capacityL: Number(capacity), reorderLevelL: reorder ? Number(reorder) : undefined,
      bowserVehicleId: bowserVehicleId || undefined, notes: notes.trim() || undefined,
    };
    try {
      if (tank) {
        const clear = [
          ...(!reorder && tank.reorderLevelL ? ['reorderLevelL'] : []),
          ...(!bowserVehicleId && tank.bowserVehicleId ? ['bowserVehicleId'] : []),
          ...(!notes.trim() && tank.notes ? ['notes'] : []),
        ];
        await update.mutateAsync({ id: tank.id, body: { ...common, code: code.trim() || undefined, clear } });
        notify.success(tx('Tank updated'));
      } else {
        await create.mutateAsync({ ...common, code: code.trim() || undefined, locationId, productId });
        notify.success(tx('Tank registered'));
      }
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the tank')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tank ? tx('Edit tank {{code}}', { code: tank.code }) : tx('Register fuel tank')}</DialogTitle>
          <DialogDescription>{tx('The tank is an inventory location holding one fuel product (in litres).')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Code')}><Input placeholder={tx('Automatic')} value={code} onChange={(e) => setCode(e.target.value)} /></Field>
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Kind')}>
            <OptionalSelect value={kind} onChange={(v) => setKind((v || 'tank') as FuelTank['kind'])} placeholder={tx('Kind')}
              options={[{ value: 'tank', label: tx('Fixed tank') }, { value: 'bowser', label: tx('Mobile bowser') }]} />
          </Field>
          {kind === 'bowser' && (
            <Field label={tx('Bowser vehicle')}>
              <OptionalSelect value={bowserVehicleId} onChange={setBowserVehicleId} placeholder={tx('Not linked')} noneLabel={tx('Not linked')}
                options={(lookups.data?.vehicles ?? []).map((v) => ({ value: v.id, label: [v.code, v.registrationPlate].filter(Boolean).join(' · ') }))} />
            </Field>
          )}
          {!tank && (
            <>
              <Field label={tx('Inventory location *')}>
                <OptionalSelect value={locationId} onChange={setLocationId} placeholder={tx('Choose the location')}
                  options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: `${l.code} · ${l.name}` }))} />
              </Field>
              <Field label={tx('Fuel product *')}>
                <RemotePicker value={productId} selectedLabel={productLabel} search={productSearch} onSearch={setProductSearch}
                  results={(products.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.code }))}
                  loading={products.isFetching} onPick={(v, l) => { setProductId(v); setProductLabel(l); }}
                  placeholder={tx('Type the product name or code…')} clearLabel={tx('Clear')} />
              </Field>
            </>
          )}
          <Field label={tx('Capacity (L) *')}><Input type="number" inputMode="decimal" min="0" value={capacity} onChange={(e) => setCapacity(e.target.value)} /></Field>
          <Field label={tx('Reorder level (L)')}><Input type="number" inputMode="decimal" min="0" value={reorder} onChange={(e) => setReorder(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || busy}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
