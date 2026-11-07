import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRightCircle, Play, Plus, Undo2, Workflow, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { apiMessage, notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  COST_LABEL, OUTPUT_ROLE_LABEL, useAddCost, useCancelJob, useJob, useRecordOutput, useReverseJob, useStartJob, useTemplate, type Job,
} from '@/features/processing/api';
import { basisOf, emptyOutput, outputBody, OutputLines, type OutputDraft } from './output-form';
import { EmptyState, Field, JobStatusPill, NumberInput, ReasonDialog, ReconciliationBar, Section, StatTile, YieldSummary, qty, selectClass } from './processing-ui';

function CostDialog({ job, onClose }: { job: Job; onClose: () => void }) {
  const add = useAddCost();
  const [category, setCategory] = useState('labour');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const save = async () => {
    try {
      await add.mutateAsync({ id: job.id, body: { category, amount: Number(amount), description: description.trim() || undefined } });
      notify.success(tx('Cost added'));
      onClose();
    } catch (err) { notify.error(apiMessage(err, tx('Could not add the cost'))); }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Add cost to {{code}}', { code: job.code })}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Type')}><select className={selectClass} value={category} onChange={(e) => setCategory(e.target.value)}>{Object.entries(COST_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
          <Field label={tx('Amount')}><NumberInput value={amount} onChange={setAmount} /></Field>
          <Field label={tx('Note')} className="sm:col-span-2"><Input value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button disabled={!(Number(amount) > 0) || add.isPending} onClick={save}>{tx('Add cost')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function OutputDialog({ job, onClose }: { job: Job; onClose: () => void }) {
  const record = useRecordOutput();
  const template = useTemplate(job.templateId);
  const [drafts, setDrafts] = useState<Record<string, OutputDraft>>({});
  const [loss, setLoss] = useState('');
  const [lossReason, setLossReason] = useState('');
  const [overYieldReason, setOverYieldReason] = useState('');
  const rec = job.reconciliation;
  const outputs = template.data?.outputs ?? [];
  const lots = Object.fromEntries(job.outputs.map((o) => [o.templateOutputId, o.batchNumber]));
  const thisOutput = outputs.reduce((s, o) => s + basisOf(drafts[o.id] ?? emptyOutput(), o.basisFactor), 0);
  const thisLoss = Number(loss) || 0;
  const input = Number(rec.inputBasis);
  const totalOutput = Number(rec.outputBasis) + thisOutput;
  const totalLoss = Number(rec.lossBasis) + thisLoss;
  const remaining = input - totalOutput - totalLoss;
  const processedNow = thisOutput + thisLoss;
  const allowance = processedNow * (1 - Number(job.expectedYieldPct) / 100);
  const abnormal = thisLoss > allowance + 0.001;
  const over = remaining < -0.001;
  const send = async (complete: boolean) => {
    try {
      await record.mutateAsync({ id: job.id, body: {
        lines: outputs.filter((o) => Number(drafts[o.id]?.quantity) > 0).map((o) => outputBody(o.id, drafts[o.id])),
        lossBasis: thisLoss || undefined, complete, lossReason: lossReason.trim() || undefined, overYieldReason: overYieldReason.trim() || undefined,
      } });
      notify.success(complete ? tx('Job completed') : tx('Output recorded. The rest stays in process.'));
      onClose();
    } catch (err) { notify.error(apiMessage(err, tx('Could not record the output'))); }
  };
  const needsReason = (abnormal && !lossReason.trim()) || (over && !overYieldReason.trim());
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader><DialogTitle>{tx('Record output of {{code}}', { code: job.code })}</DialogTitle></DialogHeader>
        <OutputLines outputs={outputs} drafts={drafts} lots={lots} basisUnit={job.yieldBasisUnit} onChange={(id, next) => setDrafts((s) => ({ ...s, [id]: next }))} />
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Loss in this entry ({{unit}})', { unit: job.yieldBasisUnit })}><NumberInput value={loss} onChange={setLoss} /></Field>
          {abnormal && <Field label={tx('Why is the loss above expected?')} hint={tx('Above expected is written off to expense, and needs a user who can approve losses.')}><Input value={lossReason} onChange={(e) => setLossReason(e.target.value)} /></Field>}
          {over && <Field label={tx('Why is the output above the input?')}><Input value={overYieldReason} onChange={(e) => setOverYieldReason(e.target.value)} /></Field>}
        </div>
        <ReconciliationBar input={input} output={totalOutput} loss={totalLoss} unit={job.yieldBasisUnit} />
        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button variant="outline" disabled={record.isPending || processedNow <= 0 || needsReason} onClick={() => send(false)}>{tx('Save, keep the rest in process')}</Button>
          <Button disabled={record.isPending || Math.abs(remaining) > 0.001 && !over || needsReason} title={remaining > 0.001 ? tx('{{qty}} {{unit}} not accounted for yet', { qty: qty(remaining, 3), unit: job.yieldBasisUnit }) : undefined} onClick={() => send(true)}>{tx('Complete job')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** One job: where the input went, what it cost, which lots it made, and what to do next. */
export function ProcessingJobDetailPage() {
  const { id } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: job, isLoading, isError } = useJob(id);
  const start = useStartJob();
  const cancel = useCancelJob();
  const reverse = useReverseJob();
  const [dialog, setDialog] = useState<'cost' | 'output' | 'cancel' | 'reverse' | null>(null);
  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-72" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !job) return <EmptyState>{tx('Could not load this job.')}</EmptyState>;
  const canPost = has(PERMISSIONS.processing.post);
  const open = job.status === 'in_progress' || job.status === 'partially_output';
  const started = job.orderCode != null && job.status !== 'reversed';
  const rec = job.reconciliation;
  const main = job.outputs.find((o) => o.role === 'main');
  const costTotal = job.costs.filter((c) => !c.reversedAt).reduce((s, c) => s + Number(c.baseAmount), 0);
  const inputCost = job.inputs.reduce((s, i) => s + Number(i.issuedCost), 0);
  const startJob = async () => {
    try { await start.mutateAsync(job.id); notify.success(tx('Job started. The input is now in process.')); } catch (err) { notify.error(apiMessage(err, tx('Could not start the job'))); }
  };
  return (
    <div className="space-y-4">
      <Link to="/manufacturing/processing/jobs" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="h-4 w-4" /> {tx('Processing Jobs')}</Link>
      <ListPageHeader icon={Workflow} title={`${job.code} · ${job.templateName}`} description={`${date(job.jobDate)} · v${job.templateVersion}${job.orderCode ? ` · ${job.orderCode}` : ''}`}
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            <JobStatusPill status={job.status} />
            {job.status === 'draft' && canPost && <Button variant="outline" onClick={() => setDialog('cancel')}><X className="mr-1.5 h-4 w-4" /> {tx('Cancel draft')}</Button>}
            {job.status === 'draft' && canPost && <Button disabled={start.isPending} onClick={startJob}><Play className="mr-1.5 h-4 w-4" /> {tx('Start job')}</Button>}
            {open && canPost && <Button variant="outline" onClick={() => setDialog('cost')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add cost')}</Button>}
            {open && canPost && <Button onClick={() => setDialog('output')}>{tx('Record output')}</Button>}
            {started && has(PERMISSIONS.processing.reverse) && <Button variant="outline" onClick={() => setDialog('reverse')}><Undo2 className="mr-1.5 h-4 w-4" /> {tx('Reverse')}</Button>}
          </div>
        )} />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label={tx('Input cost')} value={money(inputCost)} />
        <StatTile label={tx('Added cost')} value={money(costTotal)} />
        <StatTile label={tx('Still in process')} value={money(job.wipValue ?? 0)} />
        <StatTile label={main?.unitCost ? tx('Cost per {{unit}}', { unit: main.uomName ?? tx('unit') }) : tx('Output value')} value={main?.unitCost ? money(main.unitCost) : money(job.outputs.reduce((s, o) => s + Number(o.value), 0))} hint={main?.productName} />
      </div>

      <Section title={tx('Where the input went')}>
        <ReconciliationBar input={Number(rec.inputBasis)} output={Number(rec.outputBasis)} loss={Number(rec.lossBasis)} unit={rec.basisUnit} estimated={rec.estimated} />
        <YieldSummary rec={rec} expected={job.expectedYieldPct} mainUnit={main?.uomName} />
      </Section>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section title={tx('Input')}>
          <div className="overflow-x-auto"><table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground"><tr><th className="py-1 pr-3 font-medium">{tx('Product')}</th><th className="pr-3 font-medium">{tx('Lot')}</th><th className="pr-3 text-right font-medium">{tx('Quantity')}</th><th className="text-right font-medium">{tx('Cost')}</th></tr></thead>
            <tbody className="divide-y">{job.inputs.map((i) => <tr key={i.id}><td className="py-1.5 pr-3">{i.productName}</td><td className="pr-3 font-mono text-xs">{i.batchNumber ?? '—'}</td><td className="whitespace-nowrap pr-3 text-right">{qty(i.issuedQty || i.plannedQty)} {i.uomName ?? ''}</td><td className="text-right">{money(i.issuedCost)}</td></tr>)}</tbody>
          </table></div>
        </Section>
        <Section title={tx('Output')}>
          <div className="overflow-x-auto"><table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground"><tr><th className="py-1 pr-3 font-medium">{tx('Product')}</th><th className="pr-3 font-medium">{tx('Lot')}</th><th className="pr-3 text-right font-medium">{tx('Quantity')}</th><th className="pr-3 text-right font-medium">{tx('Unit cost')}</th><th /></tr></thead>
            <tbody className="divide-y">{job.outputs.map((o) => (
              <tr key={o.id}>
                <td className="py-1.5 pr-3">{o.productName}<div className="text-xs text-muted-foreground">{OUTPUT_ROLE_LABEL[o.role]}{o.grade ? ` · ${o.grade}` : ''}</div></td>
                <td className="pr-3 font-mono text-xs">{o.batchNumber ?? '—'}</td>
                <td className="whitespace-nowrap pr-3 text-right">{qty(o.quantity)} {o.uomName ?? ''}</td>
                <td className="pr-3 text-right">{o.unitCost ? money(o.unitCost) : '—'}</td>
                <td className="text-right">{Number(o.quantity) > 0 && o.batchNumber && canPost && job.status === 'completed' && (
                  <Link className="inline-flex items-center gap-1 whitespace-nowrap text-xs font-medium text-primary hover:underline" to={`/manufacturing/processing/jobs/new?productId=${o.productId}&lot=${encodeURIComponent(o.batchNumber)}&locationId=${job.outputLocationId}`}><ArrowRightCircle className="h-3.5 w-3.5" /> {tx('Process further')}</Link>
                )}</td>
              </tr>
            ))}</tbody>
          </table></div>
        </Section>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section title={tx('Costs')}>
          {job.costs.length === 0 ? <EmptyState>{tx('No added cost.')}</EmptyState> : (
            <ul className="divide-y text-sm">{job.costs.map((c) => (
              <li key={c.id} className="flex items-baseline justify-between gap-3 py-1.5"><span className={c.reversedAt ? 'line-through' : ''}>{COST_LABEL[c.category] ?? c.category}{c.description ? ` · ${c.description}` : ''}{c.sourceType ? <span className="text-xs text-muted-foreground"> · {c.sourceType}</span> : null}</span><span className="whitespace-nowrap font-medium">{money(c.baseAmount)}</span></li>
            ))}</ul>
          )}
        </Section>
        <Section title={tx('Losses')}>
          {job.losses.length === 0 ? <EmptyState>{tx('No loss recorded.')}</EmptyState> : (
            <ul className="divide-y text-sm">{job.losses.map((l) => {
              const label: Record<string, string> = { normal: tx('Expected loss, absorbed in unit cost'), abnormal: tx('Above expected, written off'), write_off: tx('Write-off') };
              return <li key={l.id} className="flex items-baseline justify-between gap-3 py-1.5"><span className={l.reversedAt ? 'line-through' : ''}>{label[l.kind] ?? l.kind}{l.reason ? ` · ${l.reason}` : ''}</span><span className="whitespace-nowrap font-medium">{qty(l.basisQty, 3)} {rec.basisUnit}{Number(l.value) ? ` · ${money(l.value)}` : ''}</span></li>;
            })}</ul>
          )}
        </Section>
      </div>

      {job.genealogy.length > 0 && (
        <Section title={tx('Lot trace')}>
          <div className="overflow-x-auto"><table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground"><tr><th className="py-1 pr-3 font-medium">{tx('From lot')}</th><th className="pr-3 font-medium">{tx('To lot')}</th><th className="pr-3 text-right font-medium">{tx('Quantity')}</th><th className="pr-3 text-right font-medium">{tx('Share')}</th><th className="text-right font-medium">{tx('Cost')}</th></tr></thead>
            <tbody className="divide-y">{job.genealogy.map((g) => (
              <tr key={g.id} className={g.reversedAt ? 'text-muted-foreground line-through' : ''}>
                <td className="py-1.5 pr-3">{g.sourceProductName} <span className="font-mono text-xs">{g.sourceBatchNumber}</span></td>
                <td className="pr-3">{g.outputProductName} <span className="font-mono text-xs">{g.outputBatchNumber}</span></td>
                <td className="pr-3 text-right">{qty(g.attributedQty)}</td>
                <td className="pr-3 text-right">{Number(g.sharePct).toLocaleString(undefined, { maximumFractionDigits: 1 })}%</td>
                <td className="text-right">{money(g.attributedCost)}</td>
              </tr>
            ))}</tbody>
          </table></div>
        </Section>
      )}

      {dialog === 'cost' && <CostDialog job={job} onClose={() => setDialog(null)} />}
      {dialog === 'output' && <OutputDialog job={job} onClose={() => setDialog(null)} />}
      {dialog === 'cancel' && <ReasonDialog title={tx('Cancel this draft?')} description={tx('The reserved input is released.')} confirmLabel={tx('Cancel draft')} busy={cancel.isPending} onClose={() => setDialog(null)}
        onConfirm={async (reason) => { await cancel.mutateAsync({ id: job.id, reason }); notify.success(tx('Draft cancelled')); }} />}
      {dialog === 'reverse' && <ReasonDialog title={tx('Reverse this job?')} description={tx('Stock and journals go back. It is refused while any output lot has been sold or moved; the message names what to undo first.')} confirmLabel={tx('Reverse job')} busy={reverse.isPending} onClose={() => setDialog(null)}
        onConfirm={async (reason) => { await reverse.mutateAsync({ id: job.id, reason }); notify.success(tx('Reversed')); }} />}
    </div>
  );
}
