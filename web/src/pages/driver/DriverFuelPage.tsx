import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Camera } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { dateTime, money } from '@/lib/format';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import { num } from '@/pages/fleet/fleet-format';
import { useCreateDriverFuelLog, useDriverHome, useFuelOptions, useMyFuelLogs } from '@/features/driver/api';
import { DriverUnitPicker } from './DriverUnitPicker';

type Pay = 'cash' | 'fuel_card' | 'credit';

/** Fuel log on the phone (DRIVER-1): a draft with an optional receipt photo; the office reviews and posts it. */
export function DriverFuelPage() {
  const [params] = useSearchParams();
  const home = useDriverHome();
  const units = home.data?.units ?? [];
  const [vehicleId, setVehicleId] = useState(params.get('vehicleId') ?? '');
  useEffect(() => {
    if (!vehicleId && units.length === 1) setVehicleId(units[0].id);
  }, [vehicleId, units]);
  const unit = units.find((u) => u.id === vehicleId);
  const options = useFuelOptions(vehicleId);
  const logs = useMyFuelLogs();
  const create = useCreateDriverFuelLog();

  const [source, setSource] = useState<'station' | 'tank'>('station');
  const [litres, setLitres] = useState('');
  const [amount, setAmount] = useState('');
  const [meter, setMeter] = useState('');
  const [fullTank, setFullTank] = useState(true);
  const [station, setStation] = useState('');
  const [receiptNumber, setReceiptNumber] = useState('');
  const [method, setMethod] = useState<Pay>('fuel_card');
  const [accountId, setAccountId] = useState('');
  const [cardId, setCardId] = useState('');
  const [supplierId, setSupplierId] = useState('');
  const [tankId, setTankId] = useState('');
  const [receipt, setReceipt] = useState<File | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const usesHours = unit?.meterType === 'hours';
  const payChosen = method === 'cash' ? !!accountId : method === 'fuel_card' ? !!cardId : !!supplierId;
  const ready = !!vehicleId && Number(litres) > 0 && (unit?.meterType === 'none' || !!meter)
    && (source === 'tank' ? !!tankId : Number(amount) > 0 && payChosen);

  const submit = async () => {
    const body: Record<string, unknown> = { vehicleId, source, litres: Number(litres), fullTank };
    if (meter) body[usesHours ? 'hours' : 'odometer'] = Number(meter);
    if (source === 'tank') {
      body.tankId = tankId;
    } else {
      body.amount = Number(amount);
      body.paymentMethod = method;
      body.stationName = station || undefined;
      body.receiptNumber = receiptNumber || undefined;
      if (method === 'cash') body.paymentAccountId = accountId;
      if (method === 'fuel_card') body.fuelCardId = cardId;
      if (method === 'credit') body.supplierId = supplierId;
    }
    try {
      const log = await create.mutateAsync({ body, receipt });
      notify.success(tx('Fuel log {{code}} saved', { code: log.code }));
      setLitres(''); setAmount(''); setMeter(''); setStation(''); setReceiptNumber(''); setReceipt(null);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the fuel log')));
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">{tx('Log fuel')}</h1>
      <Card>
        <CardContent className="space-y-4 pt-4">
          <DriverUnitPicker units={units} value={vehicleId} onChange={setVehicleId} />
          <div className="grid grid-cols-2 gap-2">
            <Button type="button" className="h-12" variant={source === 'station' ? 'default' : 'outline'} onClick={() => setSource('station')}>{tx('Fuel station')}</Button>
            <Button type="button" className="h-12" variant={source === 'tank' ? 'default' : 'outline'} onClick={() => setSource('tank')}>{tx('Site tank')}</Button>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Litres *')}><Input type="number" inputMode="decimal" className="h-12 text-lg" value={litres} onChange={(e) => setLitres(e.target.value)} /></Field>
            <Field label={usesHours ? tx('Engine hours') : tx('Odometer (km)')}><Input type="number" inputMode="decimal" className="h-12 text-lg" value={meter} onChange={(e) => setMeter(e.target.value)} /></Field>
          </div>
          <div className="flex items-center gap-2">
            <input id="full-tank" type="checkbox" className="h-5 w-5" checked={fullTank} onChange={(e) => setFullTank(e.target.checked)} />
            <Label htmlFor="full-tank">{tx('Full tank')}</Label>
          </div>
          {source === 'tank' ? (
            <Field label={tx('Site tank')}>
              <OptionalSelect value={tankId} onChange={setTankId} placeholder={tx('Choose a tank')}
                options={(options.data?.tanks ?? []).map((t) => ({ value: t.id, label: t.name }))} />
            </Field>
          ) : (
            <>
              <Field label={tx('Amount paid *')}><Input type="number" inputMode="decimal" className="h-12 text-lg" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
              <div className="grid grid-cols-3 gap-2">
                {(['fuel_card', 'cash', 'credit'] as Pay[]).map((m) => (
                  <Button key={m} type="button" className="h-12" variant={method === m ? 'default' : 'outline'} onClick={() => setMethod(m)}>
                    {m === 'fuel_card' ? tx('Fuel card') : m === 'cash' ? tx('Cash') : tx('Credit')}
                  </Button>
                ))}
              </div>
              {method === 'fuel_card' && (
                <Field label={tx('Fuel card')}>
                  <OptionalSelect value={cardId} onChange={setCardId} placeholder={tx('Choose a card')}
                    options={(options.data?.cards ?? []).map((c) => ({ value: c.id, label: c.label ?? c.maskedNumber }))} />
                </Field>
              )}
              {method === 'cash' && (
                <Field label={tx('Paid from')}>
                  <OptionalSelect value={accountId} onChange={setAccountId} placeholder={tx('Choose an account')}
                    options={(options.data?.paymentAccounts ?? []).map((a) => ({ value: a.id, label: a.name }))} />
                </Field>
              )}
              {method === 'credit' && (
                <Field label={tx('Supplier')}>
                  <OptionalSelect value={supplierId} onChange={setSupplierId} placeholder={tx('Choose a supplier')}
                    options={(options.data?.suppliers ?? []).map((a) => ({ value: a.id, label: a.name }))} />
                </Field>
              )}
              <Field label={tx('Station')}><Input className="h-12" value={station} onChange={(e) => setStation(e.target.value)} /></Field>
              <Field label={tx('Receipt number')}><Input className="h-12" value={receiptNumber} onChange={(e) => setReceiptNumber(e.target.value)} /></Field>
              <input ref={fileRef} type="file" accept="image/*" capture="environment" className="hidden" aria-label={tx('Receipt photo')}
                onChange={(e) => setReceipt(e.target.files?.[0] ?? null)} />
              <Button type="button" variant="outline" className="h-12 w-full" onClick={() => fileRef.current?.click()}>
                <Camera className="mr-2 h-5 w-5" /> {receipt ? receipt.name : tx('Receipt photo')}
              </Button>
            </>
          )}
          <Button size="lg" className="h-14 w-full text-base" disabled={!ready || create.isPending} onClick={submit}>{tx('Save fuel log')}</Button>
        </CardContent>
      </Card>

      <h2 className="text-sm font-medium text-muted-foreground">{tx('Recent fuel logs')}</h2>
      {logs.isError && <p className="text-sm text-muted-foreground">{tx('Could not load your fuel logs.')}</p>}
      {logs.data?.length === 0 && <p className="text-sm text-muted-foreground">{tx('No fuel logs yet.')}</p>}
      {logs.data?.map((l) => (
        <div key={l.id} className="rounded-xl border bg-card p-3 text-sm">
          <div className="flex justify-between gap-2"><span className="font-medium">{l.code}</span><span className="tabular-nums">{num(l.litres, 1)} L</span></div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <span>{dateTime(l.fuelledAt)} · {l.vehicleCode}</span>
            <span>{l.amount ? money(l.amount) : ''}</span>
          </div>
        </div>
      ))}
    </div>
  );
}
