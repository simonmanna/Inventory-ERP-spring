import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Scale, Truck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import { ListPageHeader } from '@/components/list';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  SUBJECT_LABEL, useCreateTicket, usePartnerOptions, useProductOptions, usePurchaseOrderOptions, useTripPrefill,
  useWeighbridgeLookups, useWeighTripOptions, type SubjectType,
} from '@/features/fleet/weighbridge-api';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';
import { toOptions } from '../fleet-format';

const STATION_KEY = 'weighbridge.station';

function rememberedStation(): string {
  try {
    return localStorage.getItem(STATION_KEY) ?? '';
  } catch {
    return '';
  }
}

/**
 * Register a truck at the weighbridge (fleet 1.4): subject, truck, driver, customer / supplier, documents, product.
 * 1.4b: pick a planned / loading trip (or arrive from the trip's "Weigh" button, {@code ?tripId=}) to pre-fill the truck,
 * driver, customer, delivery order, product and planned quantity; the completed ticket then feeds the trip.
 */
export function TicketEntryPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const lookups = useWeighbridgeLookups();
  const create = useCreateTicket();
  const [stationId, setStationId] = useState(rememberedStation);
  const [subject, setSubject] = useState<SubjectType>('sale_delivery');
  const [plate, setPlate] = useState('');
  const [trailer, setTrailer] = useState('');
  const [driverName, setDriverName] = useState('');
  const [partner, setPartner] = useState({ id: '', label: '' });
  const [partnerSearch, setPartnerSearch] = useState('');
  const [product, setProduct] = useState({ id: '', label: '' });
  const [productSearch, setProductSearch] = useState('');
  const [poId, setPoId] = useState('');
  const [poLineId, setPoLineId] = useState('');
  const [orderedQty, setOrderedQty] = useState('');
  const [uomId, setUomId] = useState('');
  const [reference, setReference] = useState('');
  const [tripId, setTripId] = useState(params.get('tripId') ?? '');
  const [tripSearch, setTripSearch] = useState('');
  const [vehicleId, setVehicleId] = useState('');
  const [driverId, setDriverId] = useState('');
  const [notes, setNotes] = useState('');
  const trips = useWeighTripOptions(tripSearch, subject === 'sale_delivery' && tripSearch.trim().length > 0);
  const prefill = useTripPrefill(tripId || undefined);
  const [appliedTrip, setAppliedTrip] = useState('');
  useEffect(() => {
    const p = prefill.data;
    if (!p || p.tripId !== tripId || appliedTrip === tripId) return;
    setAppliedTrip(tripId);
    setSubject('sale_delivery');
    setVehicleId(p.vehicleId ?? '');
    setPlate(p.vehiclePlate ?? p.registrationPlate ?? '');
    setTrailer(p.trailerPlate ?? '');
    setDriverId(p.driverId ?? '');
    setDriverName(p.driverDisplayName ?? p.driverName ?? '');
    setPartner({ id: p.partnerId ?? '', label: p.partnerName ?? '' });
    setProduct({ id: p.productId ?? '', label: p.productName ?? '' });
    setOrderedQty(p.orderedQty ?? '');
    setUomId(p.orderedUomId ?? '');
    setReference(p.referenceNumber ?? '');
  }, [prefill.data, tripId, appliedTrip]);
  const pickTrip = (id: string) => {
    setTripId(id);
    setTripSearch('');
    if (!id) {
      setAppliedTrip('');
      setVehicleId('');
      setDriverId('');
    }
  };
  const inbound = subject === 'purchase_receipt';
  const partners = usePartnerOptions(partnerSearch, inbound ? 'supplier' : 'customer', partnerSearch.trim().length > 0);
  const products = useProductOptions(productSearch, productSearch.trim().length > 0);
  const pos = usePurchaseOrderOptions('', inbound);
  const stations = useMemo(() => lookups.data?.stations ?? [], [lookups.data]);
  useEffect(() => {
    if (!stationId && stations.length === 1) setStationId(stations[0].id);
  }, [stationId, stations]);
  const po = useMemo(() => (pos.data ?? []).find((p) => p.id === poId), [pos.data, poId]);
  const tripBlocked = subject === 'sale_delivery' && !!tripId && !!prefill.data && (!prefill.data.open || !!prefill.data.liveTicket);
  const valid = !!stationId && plate.trim().length > 1 && (!inbound || !!poId) && (!orderedQty || Number(orderedQty) > 0)
    && !tripBlocked;

  const submit = async () => {
    try {
      localStorage.setItem(STATION_KEY, stationId);
    } catch {
      // private mode: no remembered station
    }
    try {
      const t = await create.mutateAsync({
        stationId,
        subjectType: subject,
        vehicleId: vehicleId || undefined,
        registrationPlate: vehicleId ? undefined : plate.trim(),
        trailerPlate: trailer.trim() || undefined,
        driverId: driverId || undefined,
        driverName: driverId ? undefined : driverName.trim() || undefined,
        partnerId: (!inbound && partner.id) || undefined,
        productId: (!inbound && product.id) || undefined,
        purchaseOrderId: (inbound && poId) || undefined,
        purchaseOrderLineId: (inbound && poLineId) || undefined,
        orderedQty: orderedQty ? Number(orderedQty) : undefined,
        orderedUomId: uomId || undefined,
        referenceNumber: reference.trim() || undefined,
        tripId: (subject === 'sale_delivery' && tripId) || undefined,
        notes: notes.trim() || undefined,
      });
      notify.success(tx('Ticket {{number}} registered', { number: t.ticketNumber }));
      navigate(`/fleet/weighbridge/tickets/${t.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not register the ticket')));
    }
  };

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <ListPageHeader icon={Scale} title={tx('New weighbridge ticket')} description={tx('Register the truck before the first weighing.')} />
      <Card>
        <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
          <Field label={tx('Station *')}>
            <OptionalSelect value={stationId} onChange={setStationId} placeholder={tx('Choose a station')}
              options={stations.map((s) => ({ value: s.id, label: `${s.code} — ${s.name}` }))} />
          </Field>
          <Field label={tx('Subject *')}>
            <OptionalSelect value={subject} onChange={(v) => setSubject((v || 'sale_delivery') as SubjectType)} placeholder={tx('Subject')}
              options={toOptions(SUBJECT_LABEL)} />
          </Field>
          {subject === 'sale_delivery' && (
            <Field label={tx('Trip (delivery)')} className="sm:col-span-2">
              <RemotePicker value={tripId} selectedLabel={prefill.data ? `${prefill.data.tripCode} — ${prefill.data.deliveryOrderCode ?? ''} · ${prefill.data.partnerName ?? ''}` : tripId}
                search={tripSearch} onSearch={setTripSearch}
                results={(trips.data ?? []).map((t) => ({
                  value: t.id,
                  label: `${t.code} — ${t.deliveryOrderCode} · ${t.partnerName ?? ''}`,
                  hint: [t.vehiclePlate ?? t.transporterPlate, t.plannedTonnes ? `${t.plannedTonnes} t` : null, t.liveTicket?.ticketNumber].filter(Boolean).join(' · ') || undefined,
                }))}
                loading={trips.isFetching} onPick={(id) => pickTrip(id)}
                placeholder={tx('Search planned / loading trips…')} clearLabel={tx('Clear')} />
              {prefill.data && (
                <p className="mt-1 flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
                  <Truck className="h-3.5 w-3.5" />
                  {prefill.data.weighbridge.required ? tx('Weighbridge required for dispatch') : tx('Weighbridge optional for this trip')}
                  {!prefill.data.open && <span className="text-rose-600">· {tx('This trip has left the yard and cannot be weighed')}</span>}
                  {prefill.data.liveTicket && <span className="text-rose-600">· {tx('Already on ticket {{number}}', { number: prefill.data.liveTicket.ticketNumber })}</span>}
                </p>
              )}
            </Field>
          )}
          <Field label={tx('Truck registration plate *')}>
            <Input className="h-12 text-lg uppercase" value={plate} onChange={(e) => { setPlate(e.target.value); setVehicleId(''); }} placeholder="UAX 123A" />
          </Field>
          <Field label={tx('Trailer plate')}>
            <Input className="h-12 uppercase" value={trailer} onChange={(e) => setTrailer(e.target.value)} />
          </Field>
          <Field label={tx('Driver')}>
            <Input className="h-12" value={driverName} onChange={(e) => { setDriverName(e.target.value); setDriverId(''); }} />
          </Field>
          <Field label={tx('Reference (delivery note no.)')}>
            <Input className="h-12" value={reference} onChange={(e) => setReference(e.target.value)} />
          </Field>
          {inbound ? (
            <>
              <Field label={tx('Purchase order *')}>
                <OptionalSelect value={poId} onChange={(v) => { setPoId(v); setPoLineId(''); }} placeholder={tx('Choose a purchase order')}
                  options={(pos.data ?? []).map((p) => ({ value: p.id, label: `${p.orderNumber} — ${p.partnerName ?? ''}` }))} />
              </Field>
              <Field label={tx('Purchase order line')}>
                <OptionalSelect value={poLineId} onChange={setPoLineId} placeholder={tx('Only line / by product')} noneLabel={tx('Automatic')}
                  options={(po?.lines ?? []).map((l) => ({ value: l.id, label: `${l.productName ?? l.description} · ${l.quantity} ${l.uomCode ?? ''} (${tx('received')} ${l.receivedQuantity})` }))} />
              </Field>
            </>
          ) : (
            <>
              <Field label={subject === 'sale_delivery' ? tx('Customer') : tx('Customer / supplier')}>
                <RemotePicker value={partner.id} selectedLabel={partner.label} search={partnerSearch} onSearch={setPartnerSearch}
                  results={(partners.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.code ?? undefined }))}
                  loading={partners.isFetching} onPick={(id, label) => setPartner({ id, label })}
                  placeholder={tx('Search customers…')} clearLabel={tx('Clear')} />
              </Field>
              <Field label={tx('Product')}>
                <RemotePicker value={product.id} selectedLabel={product.label} search={productSearch} onSearch={setProductSearch}
                  results={(products.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.uomCode ?? undefined }))}
                  loading={products.isFetching} onPick={(id, label) => setProduct({ id, label })}
                  placeholder={tx('Search products…')} clearLabel={tx('Clear')} />
              </Field>
            </>
          )}
          <Field label={tx('Ordered / expected quantity')}>
            <Input className="h-12" type="number" inputMode="decimal" min={0} value={orderedQty} onChange={(e) => setOrderedQty(e.target.value)} />
          </Field>
          <Field label={tx('Unit')}>
            <OptionalSelect value={uomId} onChange={setUomId} placeholder={tx('Document unit')} noneLabel={tx('Product default')}
              options={(lookups.data?.units ?? []).map((u) => ({ value: u.id, label: `${u.code} — ${u.name}` }))} />
          </Field>
          {subject === 'sale_delivery' && prefill.data && (
            <Field label={tx('Delivery order')}>
              <Input className="h-12" value={prefill.data.deliveryOrderCode ?? ''} readOnly />
            </Field>
          )}
          <Field label={tx('Notes')} className="sm:col-span-2">
            <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} />
          </Field>
        </CardContent>
      </Card>
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="lg" onClick={() => navigate('/fleet/weighbridge')}>{tx('Cancel')}</Button>
        <Button size="lg" onClick={submit} disabled={!valid || create.isPending}>{tx('Register truck')}</Button>
      </div>
    </div>
  );
}
