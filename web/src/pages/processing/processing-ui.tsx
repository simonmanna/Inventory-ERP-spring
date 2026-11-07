import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, CornerDownRight } from 'lucide-react';
import { StatusPill, type Tone } from '@/components/list';
import { Input } from '@/components/ui/input';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { JOB_STATUS_LABEL, type GenealogyNode, type JobStatus, type Reconciliation, type TemplateStatus } from '@/features/processing/api';
import { qty } from '@/pages/quarry/quarry-ui';

export { EmptyState, KeyValues, ProductPicker, ReasonDialog, Section, StatTile, qty, toOptions } from '@/pages/quarry/quarry-ui';

const JOB_TONE: Record<JobStatus, Tone> = {
  draft: 'warning', in_progress: 'info', partially_output: 'info', qc_hold: 'warning', completed: 'success', cancelled: 'neutral', reversed: 'neutral',
};
export const JobStatusPill = ({ status }: { status: JobStatus }) => <StatusPill tone={JOB_TONE[status] ?? 'neutral'}>{JOB_STATUS_LABEL[status] ?? status}</StatusPill>;

const TEMPLATE_TONE: Record<TemplateStatus, Tone> = { draft: 'warning', active: 'success', archived: 'neutral' };
export const TemplateStatusPill = ({ status }: { status: TemplateStatus }) => {
  const label: Record<TemplateStatus, string> = { draft: tx('Draft'), active: tx('Active'), archived: tx('Archived') };
  return <StatusPill tone={TEMPLATE_TONE[status] ?? 'neutral'}>{label[status] ?? status}</StatusPill>;
};

export const selectClass = 'h-9 w-full rounded-md border bg-background px-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring';

/** One labelled form control. */
export function Field({ label, hint, children, className }: { label: string; hint?: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block space-y-1 text-sm ${className ?? ''}`}>
      <span className="font-medium">{label}</span>
      {children}
      {hint && <span className="block text-xs text-muted-foreground">{hint}</span>}
    </label>
  );
}

export const NumberInput = ({ value, onChange, placeholder, disabled, label }: { value: string; onChange: (v: string) => void; placeholder?: string; disabled?: boolean; label?: string }) => (
  <Input type="number" inputMode="decimal" min="0" step="any" value={value} placeholder={placeholder} disabled={disabled} aria-label={label} onChange={(e) => onChange(e.target.value)} />
);

export const numberOf = (v: string | undefined | null) => (v == null || v.trim() === '' ? undefined : Number(v));

const pctText = (v: string | number | null | undefined) => (v == null ? '—' : `${Number(v).toLocaleString(undefined, { maximumFractionDigits: 1 })}%`);

/** Where every unit of input went: the same picture before and after posting. */
export function ReconciliationBar({ input, output, loss, unit, estimated }: { input: number; output: number; loss: number; unit: string; estimated?: boolean }) {
  const left = Math.max(0, input - output - loss);
  const over = Math.max(0, output + loss - input);
  const total = Math.max(input, output + loss) || 1;
  const parts = [
    { key: 'output', label: tx('Output'), value: output, className: 'bg-emerald-500' },
    { key: 'loss', label: tx('Loss'), value: loss, className: 'bg-amber-500' },
    { key: 'left', label: tx('Still in process'), value: left, className: 'bg-muted-foreground/30' },
  ];
  return (
    <div className="space-y-2">
      <div className="flex h-2.5 overflow-hidden rounded-full bg-muted" role="img" aria-label={tx('Where the input went')}>
        {parts.map((p) => <div key={p.key} className={p.className} style={{ width: `${(p.value / total) * 100}%` }} />)}
      </div>
      <dl className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-muted-foreground">
        <div><dt className="inline">{tx('Input')}: </dt><dd className="inline font-medium text-foreground">{qty(input, 3)} {unit}</dd></div>
        {parts.map((p) => <div key={p.key} className="flex items-center gap-1.5"><span className={`inline-block h-2 w-2 rounded-full ${p.className}`} /><dt className="inline">{p.label}: </dt><dd className="inline font-medium text-foreground">{qty(p.value, 3)} {unit}</dd></div>)}
      </dl>
      {over > 0.001 && <p className="text-xs font-medium text-amber-700 dark:text-amber-400">{tx('{{qty}} {{unit}} more than the input. Check the measurements, or state why.', { qty: qty(over, 3), unit })}</p>}
      {estimated && <p className="text-xs text-muted-foreground">{tx('Some output is estimated from the template factor, not measured.')}</p>}
    </div>
  );
}

export function YieldSummary({ rec, expected, mainUnit }: { rec: Reconciliation; expected: string; mainUnit?: string | null }) {
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      <div><dt className="text-xs text-muted-foreground">{tx('Mass yield')}</dt><dd className="text-xl font-semibold">{pctText(rec.massYieldPct)}</dd></div>
      <div><dt className="text-xs text-muted-foreground">{tx('Expected')}</dt><dd className="text-xl font-semibold">{pctText(expected)}</dd></div>
      <div><dt className="text-xs text-muted-foreground">{tx('Commercial yield')}</dt><dd className="text-xl font-semibold">{rec.commercialYield ? `${qty(rec.commercialYield)} ${mainUnit ?? ''}/${rec.basisUnit}` : '—'}</dd></div>
    </dl>
  );
}

/** The lot tree, backward to its sources or forward to later lots and customers. */
export function GenealogyTree({ node, depth = 0 }: { node: GenealogyNode; depth?: number }) {
  return (
    <div className={depth > 0 ? 'ml-4 border-l pl-3' : ''}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 py-1 text-sm">
        {depth > 0 && <CornerDownRight className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />}
        <span className="font-medium">{node.productName}</span>
        {node.batchNumber && <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{node.batchNumber}</span>}
        {node.jobId && <Link to={`/manufacturing/processing/jobs/${node.jobId}`} className="text-xs text-primary hover:underline">{node.jobCode}</Link>}
        {node.sharePct && <span className="text-xs text-muted-foreground">{pctText(node.sharePct)}{node.attributedCost ? ` · ${money(node.attributedCost)}` : ''}</span>}
      </div>
      {node.sales?.map((s) => (
        <div key={`${s.referenceType}-${s.referenceId}-${s.createdAt}`} className="ml-4 flex flex-wrap items-center gap-x-2 border-l py-1 pl-3 text-xs">
          <ArrowRight className="h-3 w-3 text-emerald-600" aria-hidden />
          <span className="font-medium">{s.partnerName ?? tx('Sale')}</span>
          {s.documentNumber && <span className="text-muted-foreground">{s.documentNumber}</span>}
          <span className="text-muted-foreground">{qty(s.quantity)} · {tx('cost')} {money(s.cost)}</span>
        </div>
      ))}
      {node.children.map((c, i) => <GenealogyTree key={`${c.productId}-${c.batchNumber}-${i}`} node={c} depth={depth + 1} />)}
    </div>
  );
}
