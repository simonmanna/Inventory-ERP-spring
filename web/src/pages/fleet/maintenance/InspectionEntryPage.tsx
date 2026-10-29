import { useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Camera, Check, CircleSlash, ShieldAlert, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useEmployeePicker, useInspectionPhoto, useInspectionTemplates, useRecordInspection, type Inspection } from '@/features/fleet/maintenance-api';
import { cn } from '@/lib/utils';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';
import { SeverityPill, toNumber, useUnitOptions } from './maint-ui';

type Answer = { result: 'pass' | 'fail' | 'na'; notes: string };

/**
 * Pre-trip / periodic inspection on a phone (fleet 1.6): pick the unit and checklist, tap pass / fail / n.a. per item
 * (big targets), add notes and photos (camera). Failed items raise defects; a failed critical item blocks dispatch.
 */
export function InspectionEntryPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const units = useUnitOptions();
  const [vehicleId, setVehicleId] = useState(params.get('vehicleId') ?? '');
  const categoryId = units.find((u) => u.value === vehicleId)?.categoryId;
  const templates = useInspectionTemplates({ categoryId });
  const [templateId, setTemplateId] = useState('');
  const [answers, setAnswers] = useState<Record<string, Answer>>({});
  const [odometer, setOdometer] = useState('');
  const [hours, setHours] = useState('');
  const [notes, setNotes] = useState('');
  const [empSearch, setEmpSearch] = useState('');
  const [inspector, setInspector] = useState<{ id: string; label: string } | null>(null);
  const employees = useEmployeePicker(empSearch);
  const record = useRecordInspection();
  const photo = useInspectionPhoto();
  const [done, setDone] = useState<Inspection | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const template = useMemo(() => {
    const list = templates.data ?? [];
    return list.find((t) => t.id === templateId) ?? (templateId ? undefined : list.find((t) => t.categoryId === categoryId) ?? list[0]);
  }, [templates.data, templateId, categoryId]);
  const items = template?.items ?? [];
  const answered = items.filter((i) => answers[i.code]).length;
  const failed = items.filter((i) => answers[i.code]?.result === 'fail');

  const answer = (code: string, result: Answer['result']) => setAnswers((p) => ({ ...p, [code]: { result, notes: p[code]?.notes ?? '' } }));
  const allPass = () => setAnswers(Object.fromEntries(items.map((i) => [i.code, answers[i.code] ?? { result: 'pass', notes: '' }])));

  const submit = async () => {
    if (!template) return;
    try {
      const ins = await record.mutateAsync({
        vehicleId, templateId: template.id, inspectorEmployeeId: inspector?.id, odometer: toNumber(odometer), hours: toNumber(hours),
        notes: notes.trim() || undefined,
        items: items.map((i) => ({ code: i.code, result: answers[i.code].result, notes: answers[i.code].notes.trim() || undefined })),
      });
      setDone(ins);
      notify.success(ins.result === 'pass' ? tx('Inspection passed') : tx('Inspection recorded with {{n}} defect(s)', { n: ins.defectCount }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the inspection')));
    }
  };

  const onPhoto = async (file: File | undefined) => {
    if (!file || !done) return;
    try {
      const ins = await photo.mutateAsync({ id: done.id, file });
      setDone(ins);
      notify.success(tx('Photo added'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the photo')));
    } finally {
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  if (done) {
    const blocking = (done.defects ?? []).some((d) => d.severity === 'critical');
    return (
      <div className="mx-auto max-w-xl space-y-4">
        <Card>
          <CardContent className="space-y-3 pt-6 text-center">
            <div className={cn('text-2xl font-bold', done.result === 'pass' ? 'text-emerald-600' : 'text-rose-600')}>
              {done.result === 'pass' ? tx('Passed') : tx('Failed')} · {done.code}
            </div>
            {blocking && <p className="flex items-center justify-center gap-2 font-medium text-rose-700"><ShieldAlert className="h-5 w-5" />{tx('Critical defect: do not dispatch this vehicle.')}</p>}
            {(done.defects ?? []).map((d) => (
              <div key={d.id} className="flex items-center justify-center gap-2 text-sm"><span>{d.code} · {d.title}</span><SeverityPill severity={d.severity} /></div>
            ))}
            <p className="text-sm text-muted-foreground">{tx('{{n}} photo(s) attached', { n: done.photos?.length ?? 0 })}</p>
            <input ref={fileInput} type="file" accept="image/*" capture="environment" className="hidden" onChange={(e) => onPhoto(e.target.files?.[0])} />
            <div className="flex flex-col gap-2 sm:flex-row sm:justify-center">
              <Button size="lg" variant="outline" onClick={() => fileInput.current?.click()} disabled={photo.isPending}><Camera className="mr-2 h-5 w-5" />{tx('Add photo')}</Button>
              <Button size="lg" onClick={() => { setDone(null); setAnswers({}); setOdometer(''); setHours(''); setNotes(''); }}>{tx('New inspection')}</Button>
              <Button size="lg" variant="ghost" onClick={() => navigate(`/fleet/vehicles/${vehicleId}`)}>{tx('Open vehicle')}</Button>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-xl space-y-4 pb-24">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/inspections')}><ArrowLeft className="h-5 w-5" /></Button>
        <h1 className="text-xl font-bold">{tx('Vehicle inspection')}</h1>
      </div>
      <Card>
        <CardContent className="space-y-4 pt-4">
          <Field label={tx('Vehicle / equipment *')}>
            <OptionalSelect value={vehicleId} onChange={(v) => { setVehicleId(v); setTemplateId(''); setAnswers({}); }} options={units} placeholder={tx('Choose a vehicle')} />
          </Field>
          <Field label={tx('Checklist')}>
            <OptionalSelect value={template?.id ?? ''} onChange={(v) => { setTemplateId(v); setAnswers({}); }}
              options={(templates.data ?? []).map((t) => ({ value: t.id, label: t.name }))} placeholder={tx('Choose a checklist')} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Odometer (km)')}><Input type="number" inputMode="decimal" className="h-12 text-lg" value={odometer} onChange={(e) => setOdometer(e.target.value)} /></Field>
            <Field label={tx('Engine hours')}><Input type="number" inputMode="decimal" className="h-12 text-lg" value={hours} onChange={(e) => setHours(e.target.value)} /></Field>
          </div>
          <Field label={tx('Driver / inspector')}>
            <RemotePicker value={inspector?.id ?? ''} selectedLabel={inspector?.label} search={empSearch} onSearch={setEmpSearch}
              results={(employees.data ?? []).map((e) => ({ value: e.id, label: `${e.firstName} ${e.lastName ?? ''}`.trim(), hint: e.employeeCode }))}
              loading={employees.isFetching} onPick={(value, label) => setInspector(value ? { id: value, label } : null)}
              placeholder={tx('Search employees…')} clearLabel={tx('Clear')} />
          </Field>
        </CardContent>
      </Card>

      {vehicleId && template && (
        <>
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">{tx('{{done}} of {{total}} checked', { done: answered, total: items.length })}</span>
            <Button variant="outline" size="sm" onClick={allPass}>{tx('Mark the rest OK')}</Button>
          </div>
          {items.map((i) => {
            const a = answers[i.code];
            return (
              <Card key={i.code} className={cn(a?.result === 'fail' && 'border-rose-400')}>
                <CardContent className="space-y-3 pt-4">
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <div className="font-medium">{i.label}</div>
                      {i.section && <div className="text-xs text-muted-foreground">{i.section}</div>}
                    </div>
                    {i.severity !== 'minor' && <SeverityPill severity={i.severity} />}
                  </div>
                  <div className="grid grid-cols-3 gap-2">
                    <Button type="button" className="h-12" variant={a?.result === 'pass' ? 'default' : 'outline'} onClick={() => answer(i.code, 'pass')}><Check className="mr-1 h-5 w-5" />{tx('OK')}</Button>
                    <Button type="button" className={cn('h-12', a?.result === 'fail' && 'bg-rose-600 text-white hover:bg-rose-700')} variant={a?.result === 'fail' ? 'default' : 'outline'} onClick={() => answer(i.code, 'fail')}><X className="mr-1 h-5 w-5" />{tx('Fail')}</Button>
                    <Button type="button" className="h-12" variant={a?.result === 'na' ? 'secondary' : 'outline'} onClick={() => answer(i.code, 'na')}><CircleSlash className="mr-1 h-5 w-5" />{tx('N/A')}</Button>
                  </div>
                  {a?.result === 'fail' && (
                    <Textarea rows={2} placeholder={tx('What is wrong?')} value={a.notes}
                      onChange={(e) => setAnswers((p) => ({ ...p, [i.code]: { ...p[i.code], notes: e.target.value } }))} />
                  )}
                </CardContent>
              </Card>
            );
          })}
          <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          <div className="fixed inset-x-0 bottom-0 z-10 border-t bg-background/95 p-3 backdrop-blur">
            <div className="mx-auto max-w-xl">
              <Button size="lg" className="h-14 w-full text-base" onClick={submit} disabled={answered < items.length || record.isPending}>
                {failed.length ? tx('Submit with {{n}} defect(s)', { n: failed.length }) : tx('Submit inspection')}
              </Button>
            </div>
          </div>
        </>
      )}
      {vehicleId && !templates.isLoading && (templates.data ?? []).length === 0 && (
        <p className="text-center text-sm text-muted-foreground">{tx('No checklist for this vehicle class yet.')}</p>
      )}
    </div>
  );
}
