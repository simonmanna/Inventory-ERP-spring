import { forwardRef, useEffect, useImperativeHandle, useRef, useState, type PointerEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Camera, Eraser, LocateFixed, MapPin, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { REASON_LABEL, toNum, usePodPhoto, useProofOfDelivery, useTrip, type TripDetail } from '@/features/fleet/dispatch-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';
import { TripStatusPill } from './dispatch-ui';
import { TripActions } from './TripActions';

/** Phone-first trip screen for the driver (fleet 1.3): actions, then proof of delivery with signature, GPS and photo. */
export function PodCapturePage({ basePath = '/fleet/my-trips' }: { basePath?: string }) {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: t, isLoading, isError, refetch } = useTrip(id);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !t) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Trip not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const site = t.deliveryOrder?.site;
  const canPod = has(PERMISSIONS.fleetTrip.deliver) && ['dispatched', 'in_transit'].includes(t.status);

  return (
    <div className="mx-auto max-w-xl space-y-4">
      <div className="flex items-start gap-2">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate(basePath)}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2"><h1 className="text-xl font-bold">{t.code}</h1><TripStatusPill status={t.status} /></div>
          <p className="text-sm text-muted-foreground">{t.partnerName} · {t.deliveryNoteNumber ?? t.deliveryOrderCode}</p>
        </div>
        {has(PERMISSIONS.fleetTrip.read) && <Link to={`/fleet/trips/${t.id}`} className="text-sm text-primary hover:underline">{tx('Details')}</Link>}
      </div>

      {site && (
        <div className="rounded-xl border bg-card p-3 text-sm">
          <div className="font-medium">{site.name}</div>
          <div>{[site.address, site.city].filter(Boolean).join(', ')}</div>
          {site.contactName && <div>{site.contactName} {site.contactPhone && <a className="text-primary" href={`tel:${site.contactPhone}`}>{site.contactPhone}</a>}</div>}
          {site.accessNotes && <div className="text-muted-foreground">{site.accessNotes}</div>}
          {site.latitude && (
            <a className="mt-1 inline-flex items-center gap-1 text-primary" target="_blank" rel="noreferrer"
              href={`https://www.google.com/maps/dir/?api=1&destination=${site.latitude},${site.longitude}`}>
              <MapPin className="h-4 w-4" /> {tx('Navigate')}
            </a>
          )}
        </div>
      )}

      <div className="rounded-xl border bg-card p-3 text-sm">
        {t.lines.map((l) => (
          <div key={l.id} className="flex justify-between gap-2">
            <span>{l.productName}</span>
            <span className="tabular-nums">{num(l.dispatchedQty ?? l.loadedQty ?? l.plannedQty, 3)} {l.uomCode ?? ''}</span>
          </div>
        ))}
      </div>

      <TripActions trip={t} big podPath={`${basePath}/${t.id}#pod`} />

      {canPod && <PodForm trip={t} />}
      {t.pod && <PodDone trip={t} canPhoto={has(PERMISSIONS.fleetTrip.deliver)} />}
    </div>
  );
}

interface LineState { delivered: string; returned: string; reason: string }

function PodForm({ trip }: { trip: TripDetail }) {
  const pod = useProofOfDelivery();
  const [lines, setLines] = useState<Record<string, LineState>>(() =>
    Object.fromEntries(trip.lines.map((l) => [l.id, { delivered: l.dispatchedQty ?? l.plannedQty, returned: '', reason: '' }])));
  const [receiverName, setReceiverName] = useState('');
  const [receiverPhone, setReceiverPhone] = useState('');
  const [notes, setNotes] = useState('');
  const [endOdometer, setEndOdometer] = useState('');
  const [gps, setGps] = useState<{ lat: number; lon: number } | null>(null);
  const [gpsState, setGpsState] = useState<'idle' | 'busy' | 'denied'>('idle');
  const signature = useRef<SignaturePadHandle>(null);

  const locate = () => {
    if (!('geolocation' in navigator)) { setGpsState('denied'); return; }
    setGpsState('busy');
    navigator.geolocation.getCurrentPosition(
      (p) => { setGps({ lat: Number(p.coords.latitude.toFixed(6)), lon: Number(p.coords.longitude.toFixed(6)) }); setGpsState('idle'); },
      () => setGpsState('denied'),
      { enableHighAccuracy: true, timeout: 15_000 },
    );
  };
  useEffect(locate, []);

  const diff = (l: TripDetail['lines'][number]) => {
    const s = lines[l.id];
    const dispatched = Number(l.dispatchedQty ?? 0);
    return Math.abs(dispatched - Number(s.delivered || 0) - Number(s.returned || 0)) > 0.0005 || Number(s.returned || 0) > 0;
  };
  const missingReason = trip.lines.some((l) => diff(l) && !lines[l.id].reason);
  const invalid = !receiverName.trim() || missingReason || trip.lines.some((l) => lines[l.id].delivered === '');

  const submit = async () => {
    try {
      await pod.mutateAsync({ id: trip.id, body: {
        receiverName: receiverName.trim(), receiverPhone: receiverPhone || undefined, notes: notes || undefined,
        latitude: gps?.lat, longitude: gps?.lon, endOdometer: toNum(endOdometer),
        signature: signature.current?.dataUrl() ?? undefined,
        lines: trip.lines.map((l) => ({
          loadLineId: l.id, deliveredQty: toNum(lines[l.id].delivered), returnedQty: toNum(lines[l.id].returned),
          reason: lines[l.id].reason || undefined,
        })),
      } });
      notify.success(tx('Delivery recorded'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the delivery')));
    }
  };

  return (
    <div id="pod" className="space-y-4 rounded-2xl border bg-card p-4">
      <h2 className="text-lg font-semibold">{tx('Proof of delivery')}</h2>
      {trip.lines.map((l) => (
        <div key={l.id} className="space-y-2 rounded-xl border p-3">
          <div className="font-medium">{l.productName} · {tx('dispatched')} {num(l.dispatchedQty, 3)} {l.uomCode ?? ''}</div>
          <div className="grid grid-cols-2 gap-2">
            <Field label={tx('Received')}>
              <Input type="number" inputMode="decimal" min={0} step="0.001" className="h-12 text-lg" value={lines[l.id].delivered}
                onChange={(e) => setLines({ ...lines, [l.id]: { ...lines[l.id], delivered: e.target.value } })} />
            </Field>
            <Field label={tx('Brought back')}>
              <Input type="number" inputMode="decimal" min={0} step="0.001" className="h-12 text-lg" value={lines[l.id].returned}
                onChange={(e) => setLines({ ...lines, [l.id]: { ...lines[l.id], returned: e.target.value } })} />
            </Field>
          </div>
          {diff(l) && (
            <Field label={tx('Reason for the difference')} error={!lines[l.id].reason ? tx('Required') : undefined}>
              <OptionalSelect value={lines[l.id].reason} onChange={(v) => setLines({ ...lines, [l.id]: { ...lines[l.id], reason: v } })}
                options={toOptions(REASON_LABEL)} placeholder={tx('Choose…')} />
            </Field>
          )}
        </div>
      ))}
      <Field label={tx('Receiver name')} error={!receiverName.trim() ? tx('Required') : undefined}>
        <Input className="h-12 text-lg" value={receiverName} onChange={(e) => setReceiverName(e.target.value)} />
      </Field>
      <Field label={tx('Receiver phone')}>
        <Input type="tel" className="h-12 text-lg" value={receiverPhone} onChange={(e) => setReceiverPhone(e.target.value)} />
      </Field>
      <Field label={tx('Signature')}>
        <SignaturePad ref={signature} />
      </Field>
      <div className="flex items-center justify-between gap-2 text-sm">
        <span className="text-muted-foreground">
          {gps ? `GPS ${gps.lat}, ${gps.lon}` : gpsState === 'busy' ? tx('Locating…') : tx('No GPS fix (the truck position is used if available)')}
        </span>
        <Button type="button" variant="outline" size="sm" onClick={locate}><LocateFixed className="mr-1 h-4 w-4" /> {tx('Locate')}</Button>
      </div>
      <Field label={tx('Odometer at the site (km, optional)')}>
        <Input type="number" inputMode="decimal" min={0} step="0.1" className="h-12 text-lg" value={endOdometer} onChange={(e) => setEndOdometer(e.target.value)} />
      </Field>
      <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
      <Button className="h-14 w-full text-lg" disabled={invalid || pod.isPending} onClick={submit}>{tx('Confirm delivery')}</Button>
    </div>
  );
}

function PodDone({ trip, canPhoto }: { trip: TripDetail; canPhoto: boolean }) {
  const photo = usePodPhoto();
  const fileRef = useRef<HTMLInputElement>(null);
  const p = trip.pod!;
  const upload = async (file: File | undefined) => {
    if (!file) return;
    try {
      await photo.mutateAsync({ id: trip.id, file });
      notify.success(tx('Photo attached'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the photo')));
    }
  };
  return (
    <div className="space-y-3 rounded-2xl border bg-card p-4 text-sm">
      <div className="font-medium">{tx('Received by {{name}} on {{when}}', { name: p.receiverName, when: dateTime(p.receivedAt) })}</div>
      <div className="flex flex-wrap gap-3">
        {p.signatureUrl && <img src={p.signatureUrl} alt={tx('Signature')} className="h-24 rounded border bg-white" />}
        {p.photoUrl && <img src={p.photoUrl} alt={tx('Delivery photo')} className="h-24 rounded border" />}
      </div>
      {canPhoto && (
        <>
          <input ref={fileRef} type="file" accept="image/*" capture="environment" className="hidden" onChange={(e) => upload(e.target.files?.[0])} />
          <Button variant="outline" className="h-12 w-full" disabled={photo.isPending} onClick={() => fileRef.current?.click()}>
            <Camera className="mr-1.5 h-5 w-5" /> {p.photoUrl ? tx('Replace photo') : tx('Take a photo')}
          </Button>
        </>
      )}
    </div>
  );
}

interface SignaturePadHandle { dataUrl: () => string | null }

/** Finger / mouse signature on a canvas (pointer events); `dataUrl()` = PNG data URL, or null when empty. */
const SignaturePad = forwardRef<SignaturePadHandle>(function SignaturePad(_props, ref) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);
  const drawn = useRef(false);

  useEffect(() => {
    const c = canvas.current;
    if (!c) return;
    const ratio = window.devicePixelRatio || 1;
    c.width = c.offsetWidth * ratio;
    c.height = c.offsetHeight * ratio;
    const ctx = c.getContext('2d');
    if (ctx) {
      ctx.scale(ratio, ratio);
      ctx.lineWidth = 2.2;
      ctx.lineCap = 'round';
      ctx.strokeStyle = '#111827';
    }
  }, []);

  const point = (e: PointerEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };

  useImperativeHandle(ref, () => ({
    dataUrl: () => (drawn.current && canvas.current ? canvas.current.toDataURL('image/png') : null),
  }), []);

  const clear = () => {
    const c = canvas.current;
    const ctx = c?.getContext('2d');
    if (c && ctx) ctx.clearRect(0, 0, c.width, c.height);
    drawn.current = false;
  };

  return (
    <div className="space-y-1">
      <canvas
        ref={canvas}
        className="h-40 w-full touch-none rounded-lg border bg-white"
        onPointerDown={(e) => {
          drawing.current = true;
          e.currentTarget.setPointerCapture(e.pointerId);
          const ctx = e.currentTarget.getContext('2d');
          const p = point(e);
          ctx?.beginPath();
          ctx?.moveTo(p.x, p.y);
        }}
        onPointerMove={(e) => {
          if (!drawing.current) return;
          const ctx = e.currentTarget.getContext('2d');
          const p = point(e);
          ctx?.lineTo(p.x, p.y);
          ctx?.stroke();
          drawn.current = true;
        }}
        onPointerUp={() => { drawing.current = false; }}
        onPointerLeave={() => { drawing.current = false; }}
      />
      <Button type="button" variant="ghost" size="sm" onClick={clear}><Eraser className="mr-1 h-4 w-4" /> {tx('Clear signature')}</Button>
    </div>
  );
});
