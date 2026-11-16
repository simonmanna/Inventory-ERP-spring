import { useState } from 'react';
import { AlertTriangle, CheckCircle2, FileUp, Scale, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  idem, readDataUrl, useDeliveryAction, type CustomerWeight, type CustomerWeightBody, type CustomerWeightResult,
  type SalesDelivery, type WeightOutcome, type WeightSplitLine,
} from '@/features/sales/delivery-api';
import { Labeled } from './DeliveryEditorDialog';
import { fromLocalInput, n, qty, toLocalInput } from './delivery-ui';

/**
 * Phase A A2: the customer's own weighing of the truck. It is reconciliation evidence only — the quarry ticket,
 * stock and documents never change. Lighter by more than the tolerance disputes the full variance (tolerance is an
 * investigation trigger, not a deduction); heavier is only flagged.
 */

const kg = (v: unknown) => `${qty(n(v))} kg`;

export const OUTCOME_META: Record<WeightOutcome, { label: string; className: string }> = {
  within_tolerance: { label: tx('Within tolerance'), className: 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-200' },
  heavier: { label: tx('Customer heavier — flagged'), className: 'border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900 dark:bg-sky-950/30 dark:text-sky-200' },
  disputed: { label: tx('Disputed — weight claim raised'), className: 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200' },
  awaiting_split: { label: tx('Disputed — confirm the split'), className: 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200' },
  no_comparison: { label: tx('No quarry weight to compare'), className: 'border-zinc-200 bg-zinc-50 text-zinc-700 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-300' },
};

export function OutcomeBadge({ outcome }: { outcome: WeightOutcome | null | undefined }) {
  if (!outcome) return null;
  const m = OUTCOME_META[outcome];
  return <span className={cn('inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium', m.className)}>{m.label}</span>;
}

export interface WeightForm {
  gross: string; tare: string; net: string; ref: string; weighedAt: string; notes: string;
  files: Array<{ name: string; dataUrl: string }>;
}

export const emptyWeightForm = (w?: CustomerWeight | null): WeightForm => ({
  gross: w?.customerGrossKg != null ? String(n(w.customerGrossKg)) : '',
  tare: w?.customerTareKg != null ? String(n(w.customerTareKg)) : '',
  net: w?.customerNetKg != null && w.customerGrossKg == null ? String(n(w.customerNetKg)) : '',
  ref: w?.customerTicketRef ?? '', weighedAt: toLocalInput(w?.customerWeighedAt ?? null), notes: w?.customerWeightNotes ?? '', files: [],
});

/** Whether anything was entered, the body to send, and what is wrong with it. */
export function weightBody(f: WeightForm): { entered: boolean; body: CustomerWeightBody | null; problems: string[]; netKg: number | null } {
  const has = (v: string) => v.trim() !== '';
  const entered = has(f.gross) || has(f.tare) || has(f.net);
  const problems: string[] = [];
  if (!entered) return { entered, body: null, problems, netKg: null };
  const gross = has(f.gross) ? Number(f.gross) : undefined;
  const tare = has(f.tare) ? Number(f.tare) : undefined;
  const net = has(f.net) ? Number(f.net) : undefined;
  if ([gross, tare, net].some((v) => v !== undefined && (Number.isNaN(v) || v < 0))) problems.push(tx('Weights must not be less than 0'));
  if ((gross === undefined) !== (tare === undefined)) problems.push(tx('Give both the customer gross and tare weights, or only the net'));
  if (gross !== undefined && tare !== undefined && gross <= tare) problems.push(tx('The customer gross weight must be heavier than the tare'));
  const netKg = gross !== undefined && tare !== undefined ? gross - tare : net ?? null;
  return {
    entered, problems, netKg,
    body: {
      grossKg: gross, tareKg: tare, netKg: gross !== undefined && tare !== undefined ? undefined : net,
      ticketRef: f.ref.trim() || undefined, weighedAt: fromLocalInput(f.weighedAt) ?? undefined, notes: f.notes.trim() || undefined,
    },
  };
}

/** Customer weight entry: gross / tare (or net), the customer's ticket reference, time, remarks and evidence files. */
export function CustomerWeightFields({ form, onChange }: { form: WeightForm; onChange: (f: WeightForm) => void }) {
  const set = (p: Partial<WeightForm>) => onChange({ ...form, ...p });
  const { netKg } = weightBody(form);
  const now = toLocalInput(new Date().toISOString());
  return (
    <div className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Labeled label={tx('Customer gross (kg)')}><Input type="number" min={0} step="any" value={form.gross} onChange={(e) => set({ gross: e.target.value })} /></Labeled>
        <Labeled label={tx('Customer tare (kg)')}><Input type="number" min={0} step="any" value={form.tare} onChange={(e) => set({ tare: e.target.value })} /></Labeled>
        <Labeled label={tx('…or net only (kg)')}>
          <Input type="number" min={0} step="any" value={form.gross || form.tare ? (netKg ?? '') : form.net} disabled={!!(form.gross || form.tare)}
            onChange={(e) => set({ net: e.target.value })} />
        </Labeled>
        <Labeled label={tx('Customer ticket no.')}><Input maxLength={80} value={form.ref} onChange={(e) => set({ ref: e.target.value })} /></Labeled>
        <Labeled label={tx('Weighed at')}><Input type="datetime-local" max={now} value={form.weighedAt} onChange={(e) => set({ weighedAt: e.target.value })} /></Labeled>
        <Labeled label={tx('Remarks')} className="sm:col-span-1 lg:col-span-3">
          <Textarea rows={1} maxLength={2000} value={form.notes} onChange={(e) => set({ notes: e.target.value })} />
        </Labeled>
      </div>
      <div>
        <span className="text-xs font-medium text-muted-foreground">{tx('Customer ticket photo / scan')}</span>
        <div className="mt-1 flex flex-wrap items-center gap-2">
          {form.files.map((f, i) => (
            <span key={i} className="inline-flex items-center gap-1 rounded-md border px-2 py-1 text-xs">
              {f.name}
              <button type="button" aria-label={tx('Remove')} onClick={() => set({ files: form.files.filter((_, j) => j !== i) })}><X className="h-3 w-3" /></button>
            </span>
          ))}
          <label className="inline-flex cursor-pointer items-center gap-1 rounded-md border border-dashed px-2 py-1 text-xs text-muted-foreground hover:bg-muted">
            <FileUp className="h-3.5 w-3.5" />{tx('Add file')}
            <input type="file" accept="image/*,application/pdf" multiple className="hidden" onChange={async (e) => {
              const files = Array.from(e.target.files ?? []);
              e.target.value = '';
              const read = await Promise.all(files.map(async (f) => ({ name: f.name, dataUrl: await readDataUrl(f) })));
              set({ files: [...form.files, ...read] });
            }} />
          </label>
        </div>
      </div>
    </div>
  );
}

/** Uploads the evidence files of {@code form} to the delivery (after the weighing was recorded). */
export async function uploadWeightEvidence(deliveryId: string, form: WeightForm) {
  for (const f of form.files) await api.post(`/sales/deliveries/${deliveryId}/customer-weight/evidence`, { dataUrl: f.dataUrl });
}

/**
 * Record or correct the customer weight after the proof of delivery. A disputed variance on a multi-product truck
 * (or a unit without a weight factor) is only proposed first; the user confirms or edits the split before any claim.
 */
export function CustomerWeightDialog({ delivery, onClose }: { delivery: SalesDelivery; onClose: () => void }) {
  const [form, setForm] = useState<WeightForm>(() => emptyWeightForm(delivery.customerWeight));
  const [result, setResult] = useState<CustomerWeightResult | null>(null);
  const [split, setSplit] = useState<Record<string, { qty: string; kg: string }>>({});
  const { body, problems } = weightBody(form);
  const lines = delivery.lines ?? [];
  const lineName = (id: string) => {
    const l = lines.find((x) => x.id === id);
    return l ? `${l.productName ?? ''} (${l.uomCode ?? ''})` : id;
  };

  const record = useDeliveryAction(async (allocations?: CustomerWeightBody['allocations']) => {
    const res = await api.post<SalesDelivery & { customerWeightResult: CustomerWeightResult }>(
      `/sales/deliveries/${delivery.id}/customer-weight`, { ...body, allocations }, idem());
    if (form.files.length) {
      await uploadWeightEvidence(delivery.id, form);
      setForm((f) => ({ ...f, files: [] }));
    }
    return res.data.customerWeightResult;
  });

  const submit = (allocations?: CustomerWeightBody['allocations']) => record.mutate(allocations, {
    onSuccess: (r) => {
      const out = r as CustomerWeightResult;
      if (out.outcome === 'awaiting_split') {
        setResult(out);
        setSplit(Object.fromEntries(out.lines.map((l) => [l.loadLineId, { qty: l.proposedQty, kg: '' }])));
      } else {
        onClose();
      }
    },
  });

  const splitRows = (result?.lines ?? []).map((l: WeightSplitLine) => {
    const e = split[l.loadLineId] ?? { qty: '0', kg: '' };
    const lineKg = l.kgPerUnit != null ? n(e.qty) * n(l.kgPerUnit) : n(e.kg);
    const overCap = n(e.qty) > n(l.capQty) + 1e-9;
    const missingKg = l.kgPerUnit == null && n(e.qty) > 0 && e.kg.trim() === '';
    return { l, e, lineKg, overCap, missingKg };
  });
  const allocatedKg = splitRows.reduce((s, r) => s + r.lineKg, 0);
  const varianceKg = n(result?.varianceKg);
  const splitProblems: string[] = [];
  if (splitRows.some((r) => r.overCap)) splitProblems.push(tx('A line cannot take more than is still eligible on it'));
  if (splitRows.some((r) => r.missingKg)) splitProblems.push(tx('Give the kg for a line whose unit has no weight conversion'));
  if (allocatedKg > varianceKg + 0.0005) splitProblems.push(tx('The split is more than the variance'));

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Scale className="h-5 w-5 text-primary" />{tx('Customer weight')} {delivery.code}</DialogTitle>
          <DialogDescription>{tx('The customer’s weighing is evidence only: the quarry ticket, stock and invoices do not change. Lighter by more than the tolerance raises a weight claim for the full difference; heavier is only flagged.')}</DialogDescription>
        </DialogHeader>

        {!result ? (
          <CustomerWeightFields form={form} onChange={setForm} />
        ) : (
          <div className="space-y-3">
            <ComparisonFacts quarry={result.quarryNetKg} customer={result.customerNetKg} variance={result.varianceKg}
              allowed={result.allowedVarianceKg} outcome={result.outcome} source={result.quarryWeightSource} />
            <p className="text-sm">{tx('A truck-level difference does not show which product is short. Confirm or edit the proposed split; each line is capped at what is still eligible on it (accepted, less returned, damaged, short and other weight claims).')}</p>
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full min-w-[680px] text-sm">
                <thead className="bg-muted/40 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2">{tx('Product')}</th><th className="px-3 py-2 text-right">{tx('Eligible')}</th>
                    <th className="px-3 py-2 text-right">{tx('Cap')}</th><th className="px-3 py-2 text-right">{tx('Proposed')}</th>
                    <th className="px-3 py-2 text-right">{tx('Claim qty')}</th><th className="px-3 py-2 text-right">{tx('Claim kg')}</th>
                  </tr>
                </thead>
                <tbody>
                  {splitRows.map(({ l, e, lineKg, overCap, missingKg }) => (
                    <tr key={l.loadLineId} className="border-t">
                      <td className="px-3 py-2">{lineName(l.loadLineId)}{l.needsManual && <span className="block text-xs text-amber-600">{tx('No weight conversion — enter quantity and kg')}</span>}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{qty(l.eligibleQty)}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{qty(l.capQty)}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{qty(l.proposedQty)}</td>
                      <td className="px-2 py-1.5">
                        <Input type="number" min={0} step="any" className={cn('ml-auto h-8 w-24 text-right', overCap && 'border-rose-500')} value={e.qty}
                          aria-label={`${tx('Claim qty')} ${lineName(l.loadLineId)}`}
                          onChange={(ev) => setSplit((s) => ({ ...s, [l.loadLineId]: { ...e, qty: ev.target.value } }))} />
                      </td>
                      <td className="px-2 py-1.5 text-right tabular-nums">
                        {l.kgPerUnit != null ? qty(lineKg) : (
                          <Input type="number" min={0} step="any" className={cn('ml-auto h-8 w-24 text-right', missingKg && 'border-amber-500')} value={e.kg}
                            aria-label={`${tx('Claim kg')} ${lineName(l.loadLineId)}`}
                            onChange={(ev) => setSplit((s) => ({ ...s, [l.loadLineId]: { ...e, kg: ev.target.value } }))} />
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-xs text-muted-foreground">
              {tx('Allocated')} {kg(allocatedKg)} · {tx('Unallocated (flagged on the proof of delivery)')} {kg(Math.max(0, varianceKg - allocatedKg))}
            </p>
          </div>
        )}

        {(result ? splitProblems : problems).length > 0 && (
          <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" /><span>{(result ? splitProblems : problems).join(' · ')}</span>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          {!result ? (
            <Button disabled={!body || problems.length > 0 || record.isPending} onClick={() => submit()}>
              {record.isPending ? tx('Saving…') : tx('Record and compare')}
            </Button>
          ) : (
            <Button disabled={splitProblems.length > 0 || record.isPending} onClick={() => submit(splitRows.map(({ l, e }) => ({
              loadLineId: l.loadLineId, quantity: n(e.qty), kg: l.kgPerUnit == null && e.kg.trim() !== '' ? n(e.kg) : undefined,
            })))}>
              {record.isPending ? tx('Saving…') : tx('Confirm split and raise claims')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ComparisonFacts({ quarry, customer, variance, allowed, outcome, source }: {
  quarry: unknown; customer: unknown; variance: unknown; allowed: unknown; outcome: WeightOutcome | null; source: string | null;
}) {
  const facts: Array<[string, string]> = [
    [source === 'manual' ? tx('Quarry weight (manual)') : tx('Quarry ticket net'), quarry == null ? '—' : kg(quarry)],
    [tx('Customer net'), customer == null ? '—' : kg(customer)],
    [tx('Variance (quarry − customer)'), variance == null ? '—' : kg(variance)],
    [tx('Allowed variance'), allowed == null ? '—' : kg(allowed)],
  ];
  return (
    <div className="space-y-2">
      <dl className="grid gap-3 rounded-lg border p-3 text-sm sm:grid-cols-4">
        {facts.map(([k, v]) => <div key={k}><dt className="text-[11px] uppercase tracking-wide text-muted-foreground">{k}</dt><dd className="tabular-nums font-medium">{v}</dd></div>)}
      </dl>
      <OutcomeBadge outcome={outcome} />
    </div>
  );
}

const CLAIM_STATUS: Record<string, string> = { pending: tx('Pending'), approved: tx('Confirmed'), rejected: tx('Dismissed') };

/** Comparison panel on the proof of delivery: frozen quarry vs customer weight, outcome, claims and evidence. */
export function CustomerWeightPanel({ d, canRecord, onRecord }: { d: SalesDelivery; canRecord: boolean; onRecord: () => void }) {
  const w = d.customerWeight;
  const decided = (w?.claims ?? []).some((c) => c.status !== 'pending' && !c.withdrawnAt);
  const lineName = (id: string) => {
    const l = (d.lines ?? []).find((x) => x.id === id);
    return l ? `${l.productName ?? ''}` : id;
  };
  const unit = (id: string) => (d.lines ?? []).find((x) => x.id === id)?.uomCode ?? '';
  return (
    <div className="space-y-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-1.5 text-sm font-semibold"><Scale className="h-4 w-4" />{tx('Customer weight')}</p>
        {canRecord && !decided && (
          <Button size="sm" variant={w?.weightOutcome === 'awaiting_split' ? 'default' : 'outline'} onClick={onRecord}>
            {w?.weightOutcome === 'awaiting_split' ? tx('Confirm the split') : w ? tx('Correct customer weight') : tx('Record customer weight')}
          </Button>
        )}
      </div>
      {!w ? <p className="text-sm text-muted-foreground">{tx('No customer weighing recorded.')}</p> : (
        <>
          <ComparisonFacts quarry={w.quarryNetKg} customer={w.customerNetKg} variance={w.weightVarianceKg} allowed={w.allowedVarianceKg}
            outcome={w.weightOutcome} source={w.quarryWeightSource} />
          <p className="text-xs text-muted-foreground">
            {[w.customerTicketRef && `${tx('Customer ticket')} ${w.customerTicketRef}`,
              w.customerGrossKg != null && `${tx('Gross')} ${kg(w.customerGrossKg)} · ${tx('Tare')} ${kg(w.customerTareKg)}`,
              w.customerWeighedAt && `${tx('Weighed')} ${dateTime(w.customerWeighedAt)}`,
              w.customerWeightAt && `${tx('Recorded')} ${dateTime(w.customerWeightAt)}`].filter(Boolean).join(' · ')}
          </p>
          {n(w.unallocatedVarianceKg) > 0 && (
            <p className="flex items-center gap-1.5 text-xs text-amber-700 dark:text-amber-300"><AlertTriangle className="h-3.5 w-3.5" />
              {tx('Unallocated variance')} {kg(w.unallocatedVarianceKg)}</p>
          )}
          {w.customerWeightNotes && <p className="text-sm">{w.customerWeightNotes}</p>}
          {w.claims.length > 0 && (
            <table className="w-full text-sm">
              <thead className="text-left text-[11px] uppercase tracking-wide text-muted-foreground">
                <tr><th className="py-1">{tx('Claim')}</th><th className="py-1">{tx('Product')}</th><th className="py-1 text-right">{tx('Quantity')}</th>
                  <th className="py-1 text-right">{tx('Kg')}</th><th className="py-1">{tx('Status')}</th></tr>
              </thead>
              <tbody>
                {w.claims.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="py-1.5 font-mono text-xs">{c.code}</td>
                    <td className="py-1.5">{lineName(c.loadLineId)}</td>
                    <td className="py-1.5 text-right tabular-nums">{qty(c.quantity)} {unit(c.loadLineId)}</td>
                    <td className="py-1.5 text-right tabular-nums">{c.claimKg == null ? '—' : qty(c.claimKg)}</td>
                    <td className="py-1.5">
                      <span className="inline-flex items-center gap-1">{c.status === 'approved' ? <CheckCircle2 className="h-3.5 w-3.5 text-rose-600" /> : null}{c.withdrawnAt ? tx('Withdrawn') : CLAIM_STATUS[c.status] ?? c.status}</span>
                      {c.decisionNotes && <span className="block text-xs text-muted-foreground">{c.decisionNotes}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {w.evidenceUrls.filter(Boolean).length > 0 && (
            <div className="flex flex-wrap gap-2 text-xs">
              {w.evidenceUrls.filter(Boolean).map((u, i) => (
                <a key={i} href={u ?? undefined} target="_blank" rel="noreferrer" className="rounded border px-2 py-1 text-primary hover:underline">
                  {tx('Evidence')} {i + 1}
                </a>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** One-line summary for the delivery note preview (null without a customer weighing). */
export function customerWeightNoteLine(w: CustomerWeight | null | undefined): string | null {
  if (!w || w.customerNetKg == null) return null;
  const outcome = w.weightOutcome ? OUTCOME_META[w.weightOutcome].label.toLowerCase() : '';
  return `${tx('Customer weight')} ${kg(w.customerNetKg)}${w.customerTicketRef ? ` (${tx('ticket')} ${w.customerTicketRef})` : ''}`
    + (w.quarryNetKg != null ? ` · ${tx('quarry')} ${kg(w.quarryNetKg)} · ${tx('variance')} ${kg(w.weightVarianceKg)}` : '')
    + (outcome ? ` — ${outcome}` : '');
}
