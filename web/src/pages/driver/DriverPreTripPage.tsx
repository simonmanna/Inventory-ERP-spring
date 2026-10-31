import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Camera, Check, CircleSlash, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { Field } from '@/pages/fleet/fleet-ui';
import { useDriverHome, usePreTrip, useRecordPreTrip } from '@/features/driver/api';
import { DriverUnitPicker } from './DriverUnitPicker';

type Answer = { result: 'pass' | 'fail' | 'na'; notes: string; photo?: File };

/** Pre-trip check on the phone (DRIVER-1): big pass / fail / n.a. targets; failed items raise defects. */
export function DriverPreTripPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const home = useDriverHome();
  const units = home.data?.units ?? [];
  const [vehicleId, setVehicleId] = useState(params.get('vehicleId') ?? '');
  useEffect(() => {
    if (!vehicleId && units.length === 1) setVehicleId(units[0].id);
  }, [vehicleId, units]);
  const pre = usePreTrip(vehicleId);
  const record = useRecordPreTrip();
  const [answers, setAnswers] = useState<Record<string, Answer>>({});
  const [meter, setMeter] = useState('');
  const [notes, setNotes] = useState('');
  const photoFor = useRef<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const items = pre.data?.template?.items ?? [];
  const usesHours = pre.data?.unit.meterType === 'hours';
  const hasMeter = pre.data?.unit.meterType !== 'none';
  const answered = items.filter((i) => answers[i.code]).length;
  const failed = items.filter((i) => answers[i.code]?.result === 'fail');
  const answer = (code: string, result: Answer['result']) =>
    setAnswers((p) => ({ ...p, [code]: { ...p[code], result, notes: p[code]?.notes ?? '' } }));
  const allPass = () =>
    setAnswers((p) => {
      const next = { ...p };
      items.forEach((i) => { if (!next[i.code]) next[i.code] = { result: 'pass', notes: '' }; });
      return next;
    });

  const submit = async () => {
    if (!pre.data?.template) return;
    const body: Record<string, unknown> = {
      vehicleId,
      templateId: pre.data.template.id,
      notes: notes || undefined,
      items: items.map((i) => ({ code: i.code, result: answers[i.code].result, notes: answers[i.code].notes || undefined })),
    };
    if (meter) body[usesHours ? 'hours' : 'odometer'] = Number(meter);
    const photos = items.flatMap((i) => {
      const photo = answers[i.code].photo;
      return photo ? [{ itemCode: i.code, file: photo }] : [];
    });
    try {
      const r = await record.mutateAsync({ body, photos });
      notify.success(r.result === 'pass' ? tx('Check {{code}} passed', { code: r.code }) : tx('Check {{code}} recorded with {{n}} defect(s)', { code: r.code, n: r.defectCount }));
      navigate('/driver');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the check')));
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">{tx('Pre-trip check')}</h1>
      <Card>
        <CardContent className="space-y-4 pt-4">
          <DriverUnitPicker units={units} value={vehicleId} onChange={(v) => { setVehicleId(v); setAnswers({}); }} />
          {vehicleId && hasMeter && (
            <Field label={usesHours ? tx('Engine hours') : tx('Odometer (km)')}>
              <Input type="number" inputMode="decimal" className="h-12 text-lg" value={meter} onChange={(e) => setMeter(e.target.value)} />
            </Field>
          )}
        </CardContent>
      </Card>
      {pre.isError && <p className="text-center text-sm text-muted-foreground">{tx('Could not load the checklist.')}</p>}
      {vehicleId && pre.data && !pre.data.template && <p className="text-center text-sm text-muted-foreground">{tx('No checklist for this vehicle class yet.')}</p>}
      {items.length > 0 && (
        <>
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">{tx('{{done}} of {{total}} checked', { done: answered, total: items.length })}</span>
            <Button variant="outline" size="sm" onClick={allPass}>{tx('Mark the rest OK')}</Button>
          </div>
          <input ref={fileRef} type="file" accept="image/*" capture="environment" className="hidden" aria-label={tx('Add a photo')}
            onChange={(e) => {
              const file = e.target.files?.[0];
              const code = photoFor.current;
              if (file && code) setAnswers((p) => ({ ...p, [code]: { ...p[code], photo: file } }));
              e.target.value = '';
            }} />
          {items.map((i) => {
            const a = answers[i.code];
            return (
              <Card key={i.code} className={cn(a?.result === 'fail' && 'border-rose-400')}>
                <CardContent className="space-y-3 pt-4">
                  <div>
                    <div className="font-medium">{i.label}</div>
                    {i.section && <div className="text-xs text-muted-foreground">{i.section}</div>}
                  </div>
                  <div className="grid grid-cols-3 gap-2">
                    <Button type="button" className="h-12" variant={a?.result === 'pass' ? 'default' : 'outline'} aria-label={tx('OK')} onClick={() => answer(i.code, 'pass')}><Check className="h-5 w-5" /></Button>
                    <Button type="button" className={cn('h-12', a?.result === 'fail' && 'bg-rose-600 text-white hover:bg-rose-700')} variant={a?.result === 'fail' ? 'default' : 'outline'} aria-label={tx('Fail')} onClick={() => answer(i.code, 'fail')}><X className="h-5 w-5" /></Button>
                    <Button type="button" className="h-12" variant={a?.result === 'na' ? 'secondary' : 'outline'} aria-label={tx('Not applicable')} onClick={() => answer(i.code, 'na')}><CircleSlash className="h-5 w-5" /></Button>
                  </div>
                  {a?.result === 'fail' && (
                    <>
                      <Textarea rows={2} placeholder={tx('What is wrong?')} value={a.notes}
                        onChange={(e) => setAnswers((p) => ({ ...p, [i.code]: { ...p[i.code], notes: e.target.value } }))} />
                      <Button type="button" variant="outline" className="h-11 w-full" onClick={() => { photoFor.current = i.code; fileRef.current?.click(); }}>
                        <Camera className="mr-2 h-5 w-5" /> {a.photo ? a.photo.name : tx('Add a photo')}
                      </Button>
                    </>
                  )}
                </CardContent>
              </Card>
            );
          })}
          <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          <Button size="lg" className="h-14 w-full text-base" onClick={submit} disabled={answered < items.length || record.isPending}>
            {failed.length ? tx('Submit with {{n}} defect(s)', { n: failed.length }) : tx('Submit inspection')}
          </Button>
        </>
      )}
    </div>
  );
}
