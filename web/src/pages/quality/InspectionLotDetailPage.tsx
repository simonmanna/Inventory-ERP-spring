import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AlertOctagon, ArrowLeft, FileDown, RefreshCw, Save } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DECISION_LABEL, KIND_LABEL, STAGE_LABEL, useInspectionLot, useLotDecision, usePrintCertificate, useRecordResults,
  type DecisionAction, type EvaluationParam, type InspectionLot, type LotParameter,
} from '@/features/quality/api';
import { Field, Item, LocationPicker, LotStatusPill, NcrStatusPill, OptionalSelect, ProductPicker, ResultPill, SeverityPill } from './quality-ui';
import { label, num, optNum } from './quality-format';
import { NcrFormDialog } from './NcrsPage';

type Values = Record<string, string>;

const k = (paramId: string, sample: number, sieve?: string | number) => `${paramId}#${sample}${sieve !== undefined ? `#${Number(sieve)}` : ''}`;

function initialValues(lot: InspectionLot): Values {
  const out: Values = {};
  for (const p of lot.parameters ?? []) {
    for (const r of p.results) {
      if (p.kind === 'sieve') {
        for (const s of r.sieveResults ?? []) out[k(p.id, r.sampleNo, s.sieveMm)] = String(Number(s.passingPct));
      } else if (p.kind === 'numeric') {
        out[k(p.id, r.sampleNo)] = r.numericValue != null ? String(Number(r.numericValue)) : '';
      } else {
        out[k(p.id, r.sampleNo)] = r.textValue ?? (r.numericValue != null ? String(Number(r.numericValue)) : '');
      }
    }
  }
  return out;
}

/** Lot detail (ADR-S013): result entry per parameter × sample (sieve-analysis grid), evaluation, decisions, CoA. */
export function InspectionLotDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: lot, isLoading, isError, refetch } = useInspectionLot(id);
  const has = useAuthStore((s) => s.hasPermission);
  const record = useRecordResults();
  const print = usePrintCertificate();
  const [values, setValues] = useState<Values>({});
  const [dirty, setDirty] = useState(false);
  const [decision, setDecision] = useState<DecisionAction | null>(null);
  const [raisingNcr, setRaisingNcr] = useState(false);

  useEffect(() => {
    if (lot) {
      setValues(initialValues(lot));
      setDirty(false);
    }
  }, [lot]);

  const evalById = useMemo(() => {
    const m: Record<string, EvaluationParam> = {};
    for (const p of lot?.evaluation?.parameters ?? []) m[p.parameterId] = p;
    return m;
  }, [lot]);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !lot) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Inspection lot not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const live = lot.status !== 'closed' && lot.status !== 'cancelled';
  const canInspect = has(PERMISSIONS.quality.inspect) && live;
  const decided = Number(lot.releasedQty) + Number(lot.rejectedQty) + Number(lot.downgradedQty);
  const samples = Array.from({ length: lot.sampleSize }, (_, i) => i + 1);
  const set = (key: string, v: string) => { setValues((x) => ({ ...x, [key]: v })); setDirty(true); };

  const save = async () => {
    const results: Record<string, unknown>[] = [];
    for (const p of lot.parameters ?? []) {
      for (const s of samples) {
        if (p.kind === 'sieve') {
          const sieve = p.sieveEnvelope
            .map((e) => ({ sieveMm: Number(e.sieveMm), passingPct: values[k(p.id, s, e.sieveMm)] }))
            .filter((r) => r.passingPct !== undefined && r.passingPct.trim() !== '')
            .map((r) => ({ sieveMm: r.sieveMm, passingPct: Number(r.passingPct) }));
          if (sieve.length) results.push({ parameterId: p.id, sampleNo: s, sieve });
        } else {
          const v = (values[k(p.id, s)] ?? '').trim();
          if (!v) continue;
          results.push(p.kind === 'numeric' ? { parameterId: p.id, sampleNo: s, numericValue: Number(v) } : { parameterId: p.id, sampleNo: s, textValue: v });
        }
      }
    }
    if (!results.length) {
      notify.error(tx('Enter at least one result'));
      return;
    }
    try {
      const out = await record.mutateAsync({ id: lot.id, body: { results } });
      notify.success(tx('Results saved · {{result}}', { result: out.result }));
      setDirty(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the results')));
    }
  };

  const can = {
    hold: has(PERMISSIONS.quality.hold) && lot.status === 'open',
    quarantine: has(PERMISSIONS.quality.hold) && (lot.status === 'open' || lot.status === 'hold'),
    release: has(PERMISSIONS.quality.release) && live,
    reject: has(PERMISSIONS.quality.dispose) && live,
    downgrade: has(PERMISSIONS.quality.dispose) && live,
    cancel: has(PERMISSIONS.quality.dispose) && live && decided === 0,
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/quality/lots')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{lot.lotNumber}</h1>
            <LotStatusPill status={lot.status} />
            <ResultPill result={lot.result} />
          </div>
          <p className="text-sm text-muted-foreground">{lot.productName}{lot.batchNumber ? ` · ${lot.batchNumber}` : ''} · {STAGE_LABEL[lot.stage]}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => print.mutate(lot.id, { onError: (e) => notify.error(apiMessage(e, tx('Could not print the certificate'))) })} disabled={print.isPending}>
            <FileDown className="mr-1.5 h-4 w-4" /> {tx('Certificate of analysis')}
          </Button>
          {has(PERMISSIONS.quality.ncr) && <Button variant="outline" onClick={() => setRaisingNcr(true)}><AlertOctagon className="mr-1.5 h-4 w-4" /> {tx('Raise NCR')}</Button>}
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Item label={tx('Quantity')}>{num(lot.quantity, 3)} {lot.uom ?? ''}</Item>
            <Item label={tx('Undecided')}>{num(lot.openQty, 3)}</Item>
            <Item label={tx('Released')}>{num(lot.releasedQty, 3)}</Item>
            <Item label={tx('Rejected / downgraded')}>{num(lot.rejectedQty, 3)} / {num(lot.downgradedQty, 3)}</Item>
            <Item label={tx('Specification')}>{lot.specificationId ? <Link className="text-primary hover:underline" to={`/quality/specifications/${lot.specificationId}`}>{lot.specificationCode} v{lot.specificationVersion}</Link> : null}</Item>
            <Item label={tx('Inspection plan')}>{lot.planCode}</Item>
            <Item label={tx('Samples')}>{lot.sampleSize}</Item>
            <Item label={tx('Location')}>{lot.locationName}{lot.quarantineLocationId && lot.status === 'quarantined' ? ` → ${tx('QC quarantine')}` : ''}</Item>
            <Item label={tx('Source')}>{lot.sourceRef ?? label(lot.sourceType)}</Item>
            <Item label={tx('Supplier')}>{lot.supplierName}</Item>
            <Item label={tx('Work centre')}>{lot.workCenterName}</Item>
            <Item label={tx('Inspected')}>{lot.inspectedAt ? dateTime(lot.inspectedAt) : null}</Item>
          </dl>
          {lot.stockControl === 'source' && <p className="mt-3 text-xs text-muted-foreground">{tx('Stock of this lot is moved by its source module; decisions here are recorded and notified.')}</p>}
          {lot.cancelReason && <p className="mt-2 text-sm text-muted-foreground">{tx('Cancelled')}: {lot.cancelReason}</p>}
          {lot.notes && <p className="mt-2 whitespace-pre-wrap text-sm">{lot.notes}</p>}
        </CardContent>
      </Card>

      {live && (can.hold || can.quarantine || can.release || can.reject || can.downgrade || can.cancel) && (
        <div className="flex flex-wrap gap-2">
          {can.release && <Button onClick={() => setDecision('release')}>{DECISION_LABEL.release}</Button>}
          {can.hold && <Button variant="outline" onClick={() => setDecision('hold')}>{DECISION_LABEL.hold}</Button>}
          {can.quarantine && <Button variant="outline" onClick={() => setDecision('quarantine')}>{DECISION_LABEL.quarantine}</Button>}
          {can.reject && <Button variant="outline" onClick={() => setDecision('reject')}>{DECISION_LABEL.reject}</Button>}
          {can.downgrade && <Button variant="outline" onClick={() => setDecision('downgrade')}>{DECISION_LABEL.downgrade}</Button>}
          {can.cancel && <Button variant="ghost" onClick={() => setDecision('cancel')}>{tx('Cancel lot')}</Button>}
        </div>
      )}

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{tx('Test results')}</CardTitle>
          {canInspect && <Button size="sm" onClick={save} disabled={!dirty || record.isPending}><Save className="mr-1 h-4 w-4" /> {tx('Save results')}</Button>}
        </CardHeader>
        <CardContent className="space-y-4">
          {(lot.parameters ?? []).length === 0 && <p className="text-sm text-muted-foreground">{tx('The specification has no parameters.')}</p>}
          {(lot.parameters ?? []).map((p) => (
            <ParameterBlock key={p.id} p={p} samples={samples} values={values} set={set} readOnly={!canInspect} evaluation={evalById[p.id]} />
          ))}
        </CardContent>
      </Card>

      {(lot.decisions ?? []).length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Decisions')}</CardTitle></CardHeader>
          <CardContent>
            <ul className="divide-y text-sm">
              {(lot.decisions ?? []).map((d) => (
                <li key={d.id} className="flex flex-wrap items-start justify-between gap-2 py-2">
                  <div>
                    <span className="font-medium">{DECISION_LABEL[d.decision]}</span>
                    {d.quantity && <span className="ml-2 tabular-nums">{num(d.quantity, 3)}</span>}
                    {d.concession && <span className="ml-2 rounded bg-amber-100 px-1.5 text-xs text-amber-800">{tx('Concession')}</span>}
                    {d.disposition && <span className="ml-2 text-muted-foreground">{label(d.disposition)}</span>}
                    {d.downgradeProductName && <span className="ml-2 text-muted-foreground">→ {d.downgradeProductName} {d.downgradeBatchNumber ?? ''}</span>}
                    {(d.fromLocationCode || d.toLocationCode) && <span className="ml-2 text-xs text-muted-foreground">{d.fromLocationCode ?? '—'} → {d.toLocationCode ?? '—'}</span>}
                    {d.reason && <p className="text-muted-foreground">{d.reason}</p>}
                    {d.ledgerCodes && <p className="text-xs text-muted-foreground">{d.ledgerCodes}</p>}
                  </div>
                  <div className="text-right text-xs text-muted-foreground">{dateTime(d.decidedAt)}<br />{d.decidedByName}</div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {(lot.ncrs ?? []).length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Nonconformance reports')}</CardTitle></CardHeader>
          <CardContent>
            <ul className="divide-y text-sm">
              {(lot.ncrs ?? []).map((n) => (
                <li key={n.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <Link className="text-primary hover:underline" to={`/quality/ncrs/${n.id}`}>{n.ncrNumber}</Link>
                  <span className="min-w-0 flex-1 truncate">{n.title}</span>
                  <SeverityPill severity={n.severity} />
                  <NcrStatusPill status={n.status} />
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {decision && <DecisionDialog lot={lot} action={decision} onClose={() => setDecision(null)} />}
      {raisingNcr && <NcrFormDialog lot={lot} onClose={() => setRaisingNcr(false)} onSaved={(n) => navigate(`/quality/ncrs/${n.id}`)} />}
    </div>
  );
}

function ParameterBlock({ p, samples, values, set, readOnly, evaluation }: {
  p: LotParameter; samples: number[]; values: Values; set: (key: string, v: string) => void; readOnly: boolean;
  evaluation?: EvaluationParam;
}) {
  const limits = p.kind === 'numeric'
    ? `${p.minValue != null ? num(p.minValue, 4) : '—'} – ${p.maxValue != null ? num(p.maxValue, 4) : '—'}${p.unit ? ` ${p.unit}` : ''}`
    : p.kind === 'boolean' ? (p.expectedText ?? 'true') : p.kind === 'text' ? (p.expectedText ?? '') : '';
  return (
    <div className="rounded-lg border p-3">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <span className="font-semibold">{p.code}</span>
        <span>{p.name}</span>
        <span className="text-xs text-muted-foreground">{KIND_LABEL[p.kind]}{p.testMethod ? ` · ${p.testMethod}` : ''}{limits ? ` · ${limits}` : ''}</span>
        {!p.isCritical && <span className="rounded bg-muted px-1.5 text-xs">{tx('Non-critical')}</span>}
        <span className="ml-auto">{evaluation ? <ResultPill result={evaluation.status} /> : <ResultPill result="pending" />}</span>
      </div>
      {p.kind === 'sieve' ? (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[22rem] text-sm">
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th className="py-1 pr-2">{tx('Sieve (mm)')}</th>
                <th className="py-1 pr-2">{tx('Envelope %')}</th>
                {samples.map((s) => <th key={s} className="py-1 pr-2">{tx('Sample {{n}}', { n: s })}</th>)}
              </tr>
            </thead>
            <tbody>
              {p.sieveEnvelope.map((e) => (
                <tr key={String(e.sieveMm)} className="border-t">
                  <td className="py-1 pr-2 font-medium">{num(e.sieveMm, 3)}</td>
                  <td className="py-1 pr-2 whitespace-nowrap text-muted-foreground">{e.minPassingPct ?? '—'} – {e.maxPassingPct ?? '—'}</td>
                  {samples.map((s) => {
                    const key = k(p.id, s, e.sieveMm);
                    const v = values[key] ?? '';
                    const out = v !== '' && ((e.minPassingPct != null && Number(v) < Number(e.minPassingPct)) || (e.maxPassingPct != null && Number(v) > Number(e.maxPassingPct)));
                    return (
                      <td key={s} className="py-1 pr-2">
                        <Input inputMode="decimal" className={`h-9 w-20 ${out ? 'border-rose-500 text-rose-700' : ''}`} value={v} disabled={readOnly}
                          aria-label={`${p.code} ${num(e.sieveMm, 3)} mm ${s}`} onChange={(ev) => set(key, ev.target.value)} />
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-6">
          {samples.map((s) => {
            const key = k(p.id, s);
            const v = values[key] ?? '';
            if (p.kind === 'boolean') {
              return (
                <Field key={s} label={tx('Sample {{n}}', { n: s })}>
                  <OptionalSelect value={v} onChange={(x) => set(key, x)} disabled={readOnly} placeholder="—" noneLabel="—"
                    options={[{ value: 'true', label: tx('Yes') }, { value: 'false', label: tx('No') }]} />
                </Field>
              );
            }
            const out = p.kind === 'numeric' && v !== '' && ((p.minValue != null && Number(v) < Number(p.minValue)) || (p.maxValue != null && Number(v) > Number(p.maxValue)));
            return (
              <Field key={s} label={tx('Sample {{n}}', { n: s })}>
                <Input inputMode={p.kind === 'numeric' ? 'decimal' : 'text'} className={out ? 'border-rose-500 text-rose-700' : ''} value={v} disabled={readOnly}
                  onChange={(ev) => set(key, ev.target.value)} />
              </Field>
            );
          })}
        </div>
      )}
      {evaluation && (evaluation.mean || evaluation.messages.length > 0) && (
        <div className="mt-2 space-y-0.5 text-xs text-muted-foreground">
          {evaluation.mean && <p>{tx('Mean')} {num(evaluation.mean, 4)} · {tx('Min')} {num(evaluation.min, 4)} · {tx('Max')} {num(evaluation.max, 4)}</p>}
          {evaluation.messages.map((m, i) => <p key={i} className={evaluation.status === 'fail' ? 'text-rose-600' : ''}>{m}</p>)}
        </div>
      )}
    </div>
  );
}

function DecisionDialog({ lot, action, onClose }: { lot: InspectionLot; action: DecisionAction; onClose: () => void }) {
  const [quantity, setQuantity] = useState(String(Number(lot.openQty)));
  const [reason, setReason] = useState('');
  const [concession, setConcession] = useState('');
  const [toLocationId, setToLocationId] = useState('');
  const [disposition, setDisposition] = useState('scrap');
  const [productId, setProductId] = useState('');
  const [toQuantity, setToQuantity] = useState('');
  const [batchNumber, setBatchNumber] = useState('');
  const [unitCost, setUnitCost] = useState('');
  const decide = useLotDecision();
  const needsConcession = action === 'release' && lot.result !== 'pass';
  const withQty = action === 'release' || action === 'reject' || action === 'downgrade';
  const valid = (!withQty || Number(quantity) > 0)
    && (!needsConcession || concession.trim().length > 0)
    && ((action !== 'reject' && action !== 'downgrade' && action !== 'cancel') || reason.trim().length > 0)
    && (action !== 'downgrade' || !!productId);
  const submit = async () => {
    const body: Record<string, unknown> = { reason: reason.trim() || undefined };
    if (withQty) body.quantity = Number(quantity);
    if (action === 'release') Object.assign(body, { toLocationId: toLocationId || undefined, concessionReason: concession.trim() || undefined });
    if (action === 'reject') body.disposition = disposition;
    if (action === 'downgrade') {
      Object.assign(body, {
        productId, toQuantity: optNum(toQuantity), batchNumber: batchNumber.trim() || undefined, unitCost: optNum(unitCost),
        toLocationId: toLocationId || undefined,
      });
    }
    try {
      await decide.mutateAsync({ id: lot.id, action, body });
      notify.success(tx('{{decision}} recorded', { decision: DECISION_LABEL[action] }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the decision')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{DECISION_LABEL[action]} · {lot.lotNumber}</DialogTitle>
          <DialogDescription>
            {action === 'quarantine' ? tx('Moves the undecided stock into the QC quarantine location.')
              : action === 'release' ? tx('Releases the quantity back to stock (from quarantine when quarantined).')
              : action === 'reject' ? tx('Scraps the quantity or returns it to the supplier (stock issue and GL).')
              : action === 'downgrade' ? tx('Reclassifies the quantity as a second-grade product at its carried value or a lower unit cost.')
              : action === 'cancel' ? tx('Cancels the lot; quarantined stock goes back to its location.')
              : tx('Flags the lot as on hold without moving stock.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {withQty && <Field label={tx('Quantity *')}><Input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} /></Field>}
          {action === 'reject' && (
            <Field label={tx('Disposition')}>
              <OptionalSelect value={disposition} onChange={(v) => setDisposition(v || 'scrap')} placeholder={tx('Disposition')}
                options={[{ value: 'scrap', label: tx('Scrap') }, ...(lot.supplierPartnerId ? [{ value: 'return_to_supplier', label: tx('Return to supplier') }] : [])]} />
            </Field>
          )}
          {action === 'downgrade' && (
            <>
              <Field label={tx('Second-grade product *')} className="sm:col-span-2"><ProductPicker value={productId} onChange={setProductId} /></Field>
              <Field label={tx('Quantity received')}><Input inputMode="decimal" value={toQuantity} placeholder={quantity} onChange={(e) => setToQuantity(e.target.value)} /></Field>
              <Field label={tx('Unit cost')}><Input inputMode="decimal" value={unitCost} placeholder={tx('Carried value')} onChange={(e) => setUnitCost(e.target.value)} /></Field>
              <Field label={tx('Batch number')}><Input value={batchNumber} placeholder={lot.batchNumber ?? lot.lotNumber} onChange={(e) => setBatchNumber(e.target.value)} /></Field>
            </>
          )}
          {(action === 'release' || action === 'downgrade') && lot.stockControl === 'quality' && (
            <Field label={tx('To location')}><LocationPicker value={toLocationId} onChange={setToLocationId} /></Field>
          )}
          {needsConcession && (
            <Field label={tx('Concession reason *')} className="sm:col-span-2">
              <Textarea rows={2} value={concession} onChange={(e) => setConcession(e.target.value)} />
              <p className="text-xs text-amber-700">{tx('The lot has not passed; releasing it needs a recorded concession.')}</p>
            </Field>
          )}
          <Field label={action === 'reject' || action === 'downgrade' || action === 'cancel' ? tx('Reason *') : tx('Reason')} className="sm:col-span-2">
            <Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || decide.isPending}>{DECISION_LABEL[action]}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
