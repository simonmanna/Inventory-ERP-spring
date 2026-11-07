import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Plus, Trash2, Workflow } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list';
import { useLocationOptions } from '@/features/manufacturing/options';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { COST_LABEL, useCreateJob, useLots, usePostJob, useTemplates, type TemplateInput } from '@/features/processing/api';
import { emptyOutput, basisOf, outputBody, OutputLines, type OutputDraft } from './output-form';
import { EmptyState, Field, NumberInput, ReconciliationBar, Section, numberOf, qty, selectClass } from './processing-ui';

interface InputDraft { key: number; templateInputId: string; batchNumber: string; quantity: string; basisQty: string }
interface CostDraft { key: number; category: string; amount: string; description: string }
let seq = 0;
const nextKey = () => { seq += 1; return seq; };
const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 10);

function LotSelect({ line, location, value, onChange }: { line: TemplateInput; location: string; value: string; onChange: (lot: string, available: number) => void }) {
  const lots = useLots({ productId: line.productId }, !!line.productId);
  const here = (lots.data ?? []).filter((l) => !location || l.locationId === location);
  return (
    <select className={selectClass} aria-label={tx('Lot')} value={value} onChange={(e) => {
      const lot = here.find((l) => l.batchNumber === e.target.value);
      onChange(e.target.value, lot ? Math.max(0, Number(lot.quantity)) : 0);
    }}>
      <option value="">{tx('Choose a lot')}</option>
      {here.map((l) => <option key={l.id} value={l.batchNumber}>{l.batchNumber} · {qty(l.quantity)} {l.uomName ?? ''} · {Number(l.unitCost).toLocaleString(undefined, { maximumFractionDigits: 2 })}</option>)}
    </select>
  );
}

/** New job: from a template, or from a lot through Process Further. Post it at once, or save a draft that holds the stock. */
export function ProcessingJobEntryPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const templates = useTemplates({ status: 'active' });
  const locations = useLocationOptions();
  const createJob = useCreateJob();
  const postJob = usePostJob();
  const [templateId, setTemplateId] = useState(params.get('templateId') ?? '');
  const [locationId, setLocationId] = useState(params.get('locationId') ?? '');
  const [outputLocationId, setOutputLocationId] = useState('');
  const [jobDate, setJobDate] = useState(today());
  const [notes, setNotes] = useState('');
  const [inputs, setInputs] = useState<InputDraft[]>([]);
  const [costs, setCosts] = useState<CostDraft[]>([]);
  const [outputs, setOutputs] = useState<Record<string, OutputDraft>>({});
  const [loss, setLoss] = useState('');
  const [lossReason, setLossReason] = useState('');
  const [overYieldReason, setOverYieldReason] = useState('');
  const productParam = params.get('productId');
  const candidates = useMemo(() => (templates.data ?? []).filter((t) => !productParam || t.inputs.some((i) => i.productId === productParam && i.role === 'traceable')), [templates.data, productParam]);
  const template = (templates.data ?? []).find((t) => t.id === templateId);

  useEffect(() => {
    if (!templateId && candidates.length === 1) setTemplateId(candidates[0].id);
  }, [candidates, templateId]);
  useEffect(() => {
    if (!template) return;
    const lot = params.get('lot') ?? '';
    const qtyParam = params.get('quantity') ?? '';
    setInputs(template.inputs.map((i) => ({ key: nextKey(), templateInputId: i.id, batchNumber: i.productId === productParam ? lot : '', quantity: i.productId === productParam ? qtyParam : '', basisQty: '' })));
    setOutputs(Object.fromEntries(template.outputs.map((o) => [o.id, emptyOutput()])));
    if (!locationId && template.defaultInputLocationId) setLocationId(template.defaultInputLocationId);
    if (template.defaultOutputLocationId) setOutputLocationId(template.defaultOutputLocationId);
  }, [template?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const unit = template?.yieldBasisUnit ?? '';
  const inputLine = (id: string) => template?.inputs.find((i) => i.id === id);
  const inputBasis = inputs.reduce((sum, d) => {
    const line = inputLine(d.templateInputId);
    if (line?.role !== 'traceable') return sum;
    return sum + (Number(d.basisQty) || (Number(d.quantity) || 0) * (line.basisFactor ? Number(line.basisFactor) : 1));
  }, 0);
  const outputBasis = (template?.outputs ?? []).reduce((sum, o) => sum + basisOf(outputs[o.id] ?? emptyOutput(), o.basisFactor), 0);
  const lossBasis = Number(loss) || 0;
  const remaining = inputBasis - outputBasis - lossBasis;
  const allowance = (outputBasis + lossBasis) * (1 - Number(template?.expectedYieldPct ?? 100) / 100);
  const abnormal = lossBasis > allowance + 0.001;
  const over = remaining < -0.001;

  const jobBody = () => ({
    templateId, locationId: locationId || undefined, outputLocationId: outputLocationId || undefined, jobDate, notes: notes.trim() || undefined,
    inputs: inputs.filter((d) => Number(d.quantity) > 0).map((d) => ({ templateInputId: d.templateInputId, batchNumber: d.batchNumber || undefined, quantity: Number(d.quantity), basisQty: numberOf(d.basisQty) })),
  });
  const missing = !template ? tx('Choose a step') : !locationId ? tx('Choose the input location') : inputs.every((d) => !(Number(d.quantity) > 0)) ? tx('Enter the input quantity')
    : inputs.some((d) => Number(d.quantity) > 0 && inputLine(d.templateInputId)?.batchTracking && !d.batchNumber) ? tx('Choose a lot for each input') : null;

  const saveDraft = async () => {
    try {
      const job = await createJob.mutateAsync(jobBody());
      notify.success(tx('Draft saved. The input is reserved.'));
      navigate(`/manufacturing/processing/jobs/${job.id}`);
    } catch (err) { notify.error(apiMessage(err, tx('Could not save the job'))); }
  };
  const postNow = async () => {
    try {
      const job = await postJob.mutateAsync({
        job: jobBody(),
        costs: costs.filter((c) => Number(c.amount) > 0).map((c) => ({ category: c.category, amount: Number(c.amount), description: c.description.trim() || undefined, costDate: jobDate })),
        output: {
          lines: (template?.outputs ?? []).filter((o) => Number(outputs[o.id]?.quantity) > 0).map((o) => outputBody(o.id, outputs[o.id])),
          lossBasis: lossBasis || undefined, complete: true, lossReason: lossReason.trim() || undefined, overYieldReason: overYieldReason.trim() || undefined, date: jobDate,
        },
      });
      notify.success(tx('Job {{code}} posted', { code: job.code }));
      navigate(`/manufacturing/processing/jobs/${job.id}`);
    } catch (err) { notify.error(apiMessage(err, tx('Could not post the job'))); }
  };
  const busy = createJob.isPending || postJob.isPending;
  const canPost = !missing && Math.abs(remaining) <= 0.001 && outputBasis > 0 && (!abnormal || lossReason.trim()) && (!over || overYieldReason.trim());

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <Link to="/manufacturing/processing/jobs" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="h-4 w-4" /> {tx('Processing Jobs')}</Link>
      <ListPageHeader icon={Workflow} title={tx('New processing job')} description={tx('Record one step. What it produces can be sold straight away, or processed further.')} />

      <Section title={tx('Step')}>
        {!templates.isLoading && candidates.length === 0 ? (
          <EmptyState>{productParam ? tx('No active step takes this product as input yet.') : tx('No active step yet.')} <Link className="text-primary hover:underline" to="/manufacturing/processing/templates/new">{tx('Create a step')}</Link></EmptyState>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={tx('Step')}>
              <select className={selectClass} value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
                <option value="">{tx('Choose a step')}</option>
                {candidates.map((t) => <option key={t.id} value={t.id}>{t.name} ({t.code} v{t.version})</option>)}
              </select>
            </Field>
            <Field label={tx('Date')}><Input type="date" value={jobDate} onChange={(e) => setJobDate(e.target.value)} /></Field>
            <Field label={tx('Take input from')}>
              <select className={selectClass} value={locationId} onChange={(e) => setLocationId(e.target.value)}>
                <option value="">{tx('Choose a location')}</option>
                {(locations.data ?? []).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </select>
            </Field>
            <Field label={tx('Put output in')} hint={tx('Blank uses the input location')}>
              <select className={selectClass} value={outputLocationId} onChange={(e) => setOutputLocationId(e.target.value)}>
                <option value="">{tx('Same as input')}</option>
                {(locations.data ?? []).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </select>
            </Field>
          </div>
        )}
      </Section>

      {template && (
        <>
          <Section title={tx('Input')} actions={<Button type="button" size="sm" variant="outline" onClick={() => setInputs((rows) => [...rows, { key: nextKey(), templateInputId: template.inputs[0].id, batchNumber: '', quantity: '', basisQty: '' }])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Another lot')}</Button>}>
            <div className="space-y-3">
              {inputs.map((d) => {
                const line = inputLine(d.templateInputId);
                if (!line) return null;
                const set = (patch: Partial<InputDraft>) => setInputs((rows) => rows.map((r) => (r.key === d.key ? { ...r, ...patch } : r)));
                return (
                  <div key={d.key} className="grid items-end gap-3 rounded-lg border p-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)_8rem_auto]">
                    <div className="min-w-0"><div className="truncate text-sm font-medium">{line.productName}</div><div className="text-xs text-muted-foreground">{line.role === 'traceable' ? tx('Traced to its lot') : tx('Consumable')}</div></div>
                    {line.batchTracking ? <Field label={tx('Lot')}><LotSelect line={line} location={locationId} value={d.batchNumber} onChange={(lot) => set({ batchNumber: lot })} /></Field> : <div />}
                    <Field label={line.uomName ? tx('Quantity ({{unit}})', { unit: line.uomName }) : tx('Quantity')}><NumberInput value={d.quantity} onChange={(v) => set({ quantity: v })} /></Field>
                    <Button type="button" variant="ghost" size="icon" className="h-9 w-9" aria-label={tx('Remove input line')} disabled={inputs.length <= 1} onClick={() => setInputs((rows) => rows.filter((r) => r.key !== d.key))}><Trash2 className="h-4 w-4" /></Button>
                  </div>
                );
              })}
            </div>
          </Section>

          <Section title={tx('Costs')} actions={<Button type="button" size="sm" variant="outline" onClick={() => setCosts((rows) => [...rows, { key: nextKey(), category: 'labour', amount: '', description: '' }])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Add cost')}</Button>}>
            {costs.length === 0 ? <p className="text-sm text-muted-foreground">{tx('Labour, machine, fuel and so on. The input cost is already included.')}</p> : (
              <div className="space-y-2">
                {costs.map((c) => {
                  const set = (patch: Partial<CostDraft>) => setCosts((rows) => rows.map((r) => (r.key === c.key ? { ...r, ...patch } : r)));
                  return (
                    <div key={c.key} className="grid items-end gap-2 sm:grid-cols-[10rem_8rem_minmax(0,1fr)_auto]">
                      <Field label={tx('Type')}><select className={selectClass} value={c.category} onChange={(e) => set({ category: e.target.value })}>{Object.entries(COST_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
                      <Field label={tx('Amount')}><NumberInput value={c.amount} onChange={(v) => set({ amount: v })} /></Field>
                      <Field label={tx('Note')}><Input value={c.description} onChange={(e) => set({ description: e.target.value })} /></Field>
                      <Button type="button" variant="ghost" size="icon" className="h-9 w-9" aria-label={tx('Remove cost')} onClick={() => setCosts((rows) => rows.filter((r) => r.key !== c.key))}><Trash2 className="h-4 w-4" /></Button>
                    </div>
                  );
                })}
              </div>
            )}
          </Section>

          <Section title={tx('Output and loss')}>
            <OutputLines outputs={template.outputs} drafts={outputs} basisUnit={unit} onChange={(id, next) => setOutputs((s) => ({ ...s, [id]: next }))} />
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Loss ({{unit}})', { unit })} hint={tx('Expected yield is {{pct}}%. Loss up to that raises unit cost.', { pct: Number(template.expectedYieldPct) })}><NumberInput value={loss} onChange={setLoss} /></Field>
              {abnormal && <Field label={tx('Why is the loss above expected?')} hint={tx('Above expected is written off to expense, and needs a user who can approve losses.')}><Input value={lossReason} onChange={(e) => setLossReason(e.target.value)} /></Field>}
              {over && <Field label={tx('Why is the output above the input?')}><Input value={overYieldReason} onChange={(e) => setOverYieldReason(e.target.value)} /></Field>}
            </div>
            <ReconciliationBar input={inputBasis} output={outputBasis} loss={lossBasis} unit={unit} />
            <Field label={tx('Notes')}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          </Section>

          <div className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-end gap-2 border-t bg-background/95 p-3 backdrop-blur">
            <span className="mr-auto text-xs text-muted-foreground">{missing ?? (Math.abs(remaining) > 0.001 && !over ? tx('{{qty}} {{unit}} not accounted for yet. Post needs all of it; a draft does not.', { qty: qty(remaining, 3), unit }) : '')}</span>
            <Button type="button" variant="outline" disabled={!!missing || busy} onClick={saveDraft}>{tx('Save draft and reserve')}</Button>
            <Button type="button" disabled={!canPost || busy} onClick={postNow}>{tx('Post job')}</Button>
          </div>
        </>
      )}
    </div>
  );
}
