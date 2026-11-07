import { useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { tx } from '@/lib/i18n/tx';
import { OUTPUT_ROLE_LABEL, type OutputRole, type TemplateOutput } from '@/features/processing/api';
import { Field, NumberInput, numberOf } from './processing-ui';

export interface OutputDraft { quantity: string; batchNumber: string; grade: string; weight: string; length: string; width: string; thickness: string; area: string; volume: string; density: string }
export const emptyOutput = (): OutputDraft => ({ quantity: '', batchNumber: '', grade: '', weight: '', length: '', width: '', thickness: '', area: '', volume: '', density: '' });

/** Yield-basis quantity of a draft line, in the same order the server applies it. */
export function basisOf(d: OutputDraft, factor: string | null | undefined): number {
  const n = (v: string) => Number(v) || 0;
  if (n(d.weight) > 0) return n(d.weight);
  if (n(d.density) > 0) {
    const volume = n(d.volume) > 0 ? n(d.volume) : n(d.area) > 0 && n(d.thickness) > 0 ? n(d.area) * n(d.thickness) : n(d.length) * n(d.width) * n(d.thickness);
    if (volume > 0) return volume * n(d.density);
  }
  return n(d.quantity) * (factor ? Number(factor) : 1);
}

export function outputBody(templateOutputId: string, d: OutputDraft) {
  return {
    templateOutputId, quantity: Number(d.quantity) || 0, batchNumber: d.batchNumber.trim() || undefined, grade: d.grade.trim() || undefined,
    weight: numberOf(d.weight), length: numberOf(d.length), width: numberOf(d.width), thickness: numberOf(d.thickness), area: numberOf(d.area),
    volume: numberOf(d.volume), density: numberOf(d.density),
  };
}

/** Output lines of a job: quantity first; lot, grade and measurements stay folded until they are needed. */
export function OutputLines({ outputs, drafts, onChange, basisUnit, lots }: {
  outputs: TemplateOutput[]; drafts: Record<string, OutputDraft>; onChange: (id: string, next: OutputDraft) => void; basisUnit: string; lots?: Record<string, string | null>;
}) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  return (
    <div className="divide-y rounded-lg border">
      {outputs.map((o) => {
        const d = drafts[o.id] ?? emptyOutput();
        const set = (key: keyof OutputDraft) => (v: string) => onChange(o.id, { ...d, [key]: v });
        const needs = Array.isArray((o as unknown as { requiredMeasures?: string[] }).requiredMeasures);
        const showing = open[o.id] || needs;
        return (
          <div key={o.id} className="space-y-3 p-3">
            <div className="grid items-end gap-3 sm:grid-cols-[minmax(0,1fr)_10rem_auto]">
              <div className="min-w-0">
                <div className="truncate text-sm font-medium">{o.productName}</div>
                <div className="text-xs text-muted-foreground">{OUTPUT_ROLE_LABEL[o.role as OutputRole]}{lots?.[o.id] ? ` · ${tx('lot')} ${lots[o.id]}` : ''}</div>
              </div>
              <Field label={o.uomName ? tx('Quantity ({{unit}})', { unit: o.uomName }) : tx('Quantity')}><NumberInput value={d.quantity} onChange={set('quantity')} /></Field>
              <button type="button" aria-expanded={showing} onClick={() => setOpen((s) => ({ ...s, [o.id]: !s[o.id] }))} className="inline-flex h-9 items-center gap-1 rounded-md px-2 text-xs font-medium text-primary hover:bg-accent">
                {tx('Lot and measurements')}<ChevronDown className={`h-3.5 w-3.5 transition-transform ${showing ? 'rotate-180' : ''}`} aria-hidden />
              </button>
            </div>
            {showing && (
              <div className="grid grid-cols-2 gap-3 rounded-md bg-muted/40 p-3 md:grid-cols-4">
                <Field label={tx('Lot number')} hint={tx('Blank uses the suggested number')}><Input value={d.batchNumber} onChange={(e) => set('batchNumber')(e.target.value)} /></Field>
                <Field label={tx('Grade')}><Input value={d.grade} onChange={(e) => set('grade')(e.target.value)} /></Field>
                <Field label={tx('Measured weight ({{unit}})', { unit: basisUnit })} hint={tx('Best basis for yield')}><NumberInput value={d.weight} onChange={set('weight')} /></Field>
                <Field label={tx('Density')} hint={tx('With a volume or dimensions')}><NumberInput value={d.density} onChange={set('density')} /></Field>
                <Field label={tx('Length')}><NumberInput value={d.length} onChange={set('length')} /></Field>
                <Field label={tx('Width')}><NumberInput value={d.width} onChange={set('width')} /></Field>
                <Field label={tx('Thickness')}><NumberInput value={d.thickness} onChange={set('thickness')} /></Field>
                <Field label={tx('Area')}><NumberInput value={d.area} onChange={set('area')} /></Field>
                <Field label={tx('Volume')}><NumberInput value={d.volume} onChange={set('volume')} /></Field>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
