import { FileText, ListTree, Package, Route as RouteIcon, type LucideIcon } from 'lucide-react';
import type { Tone } from '@/components/list/list-page';
import { tx } from '@/lib/i18n/tx';
import type {
  ChangeStatus, ChangeType, DocStatus, DocType, LifecycleState, Priority, ReasonCode, RequestStatus, StockDisposition,
  WipDisposition,
} from '@/features/manufacturing/api-plm';

/** Labels, tones and colours of the PLM vocabularies (ADR-S063). */

export const LIFECYCLE_ORDER: LifecycleState[] = ['concept', 'design', 'prototype', 'released', 'phase_out', 'obsolete'];

export const LIFECYCLE: Record<LifecycleState, { label: string; tone: Tone; bar: string; hint: string }> = {
  concept: { label: tx('Concept'), tone: 'neutral', bar: 'bg-slate-400', hint: tx('An idea being explored') },
  design: { label: tx('Design'), tone: 'info', bar: 'bg-sky-500', hint: tx('Being engineered') },
  prototype: { label: tx('Prototype'), tone: 'accent', bar: 'bg-violet-500', hint: tx('Built and tested in small runs') },
  released: { label: tx('Released'), tone: 'success', bar: 'bg-emerald-500', hint: tx('Approved for production') },
  phase_out: { label: tx('Phase out'), tone: 'warning', bar: 'bg-amber-500', hint: tx('Use up stock, no new designs') },
  obsolete: { label: tx('Obsolete'), tone: 'danger', bar: 'bg-rose-500', hint: tx('No longer made or sold') },
};

/** Allowed lifecycle moves (mirrors PlmLifecycleService.TRANSITIONS; the server has the final word). */
export const TRANSITIONS: Record<LifecycleState, LifecycleState[]> = {
  concept: ['design', 'obsolete'],
  design: ['concept', 'prototype', 'released', 'obsolete'],
  prototype: ['design', 'released', 'obsolete'],
  released: ['phase_out', 'obsolete'],
  phase_out: ['released', 'obsolete'],
  obsolete: ['design'],
};

export const REGISTER_STATES: Array<'concept' | 'design' | 'prototype' | 'released'> = ['concept', 'design', 'prototype', 'released'];

export const CHANGE_STATUS: Record<ChangeStatus, { label: string; tone: Tone }> = {
  draft: { label: tx('In progress'), tone: 'info' },
  approved: { label: tx('Approved'), tone: 'accent' },
  applied: { label: tx('Applied'), tone: 'success' },
  rejected: { label: tx('Cancelled'), tone: 'danger' },
};

export const CHANGE_TYPE: Record<ChangeType, { label: string; icon: LucideIcon; className: string; hint: string }> = {
  bom: { label: tx('BOM'), icon: ListTree, className: 'text-sky-600 bg-sky-50 dark:bg-sky-500/15 dark:text-sky-300', hint: tx('New BOM version: components, quantities, header') },
  routing: { label: tx('Routing'), icon: RouteIcon, className: 'text-teal-600 bg-teal-50 dark:bg-teal-500/15 dark:text-teal-300', hint: tx('New routing: operations, work centres, times') },
  product: { label: tx('Lifecycle'), icon: Package, className: 'text-violet-600 bg-violet-50 dark:bg-violet-500/15 dark:text-violet-300', hint: tx('Move the product to another lifecycle state') },
  document: { label: tx('Documents'), icon: FileText, className: 'text-amber-700 bg-amber-50 dark:bg-amber-500/15 dark:text-amber-300', hint: tx('Release new drawings, specifications, instructions') },
};
export const CHANGE_TYPES: ChangeType[] = ['bom', 'routing', 'product', 'document'];

export const PRIORITY: Record<Priority, { label: string; tone: Tone }> = {
  low: { label: tx('Low'), tone: 'neutral' },
  normal: { label: tx('Normal'), tone: 'info' },
  high: { label: tx('High'), tone: 'warning' },
  urgent: { label: tx('Urgent'), tone: 'danger' },
};
export const PRIORITIES: Priority[] = ['low', 'normal', 'high', 'urgent'];

export const REQUEST_STATUS: Record<RequestStatus, { label: string; tone: Tone }> = {
  new: { label: tx('New'), tone: 'info' },
  accepted: { label: tx('Accepted'), tone: 'accent' },
  rejected: { label: tx('Rejected'), tone: 'danger' },
  converted: { label: tx('Converted'), tone: 'success' },
};

export const REASONS: Record<ReasonCode, string> = {
  cost_reduction: tx('Cost reduction'),
  quality: tx('Quality'),
  customer: tx('Customer request'),
  supplier: tx('Supplier change'),
  regulatory: tx('Regulatory'),
  safety: tx('Safety'),
  manufacturability: tx('Manufacturability'),
  other: tx('Other'),
};

export const DOC_STATUS: Record<DocStatus, { label: string; tone: Tone }> = {
  draft: { label: tx('Draft'), tone: 'info' },
  released: { label: tx('Released'), tone: 'success' },
  superseded: { label: tx('Superseded'), tone: 'neutral' },
  obsolete: { label: tx('Obsolete'), tone: 'danger' },
};

export const DOC_TYPES: Record<DocType, string> = {
  drawing: tx('Drawing'),
  specification: tx('Specification'),
  cad: tx('CAD model'),
  work_instruction: tx('Work instruction'),
  test_procedure: tx('Test procedure'),
  datasheet: tx('Datasheet'),
  certificate: tx('Certificate'),
  other: tx('Other'),
};

export const STOCK_DISPOSITION: Record<StockDisposition, string> = {
  use_up: tx('Use up existing stock'),
  scrap: tx('Scrap existing stock'),
  rework: tx('Rework existing stock'),
  return_to_supplier: tx('Return to supplier'),
  none: tx('No action'),
};

export const WIP_DISPOSITION: Record<WipDisposition, string> = {
  continue: tx('Finish open orders as they are'),
  update: tx('Update open orders'),
  rework: tx('Rework open orders'),
};

export const BOM_STATUS_TONE: Record<string, Tone> = { draft: 'info', active: 'success', archived: 'neutral' };

export const selectClass =
  'h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60';

export const human = (s?: string | null) => (s ? s.replace(/_/g, ' ') : '');

/** `YYYY-MM-DD` (or ISO) → localised day, parsed as a local date so it never shifts a day. */
export function day(v?: string | null): string {
  if (!v) return '—';
  const d = v.length === 10 ? new Date(`${v}T00:00:00`) : new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function dayTime(v?: string | null): string {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

export function ago(v?: string | null): string {
  if (!v) return '';
  const diff = Date.now() - new Date(v).getTime();
  const min = Math.round(diff / 60000);
  if (min < 1) return tx('just now');
  if (min < 60) return tx('{{n}} min ago', { n: min });
  const h = Math.round(min / 60);
  if (h < 24) return tx('{{n}} h ago', { n: h });
  const d = Math.round(h / 24);
  if (d < 30) return tx('{{n}} d ago', { n: d });
  return day(v);
}

/** Value of a comparison cell (numbers without trailing zeros, booleans as yes / no). */
export function show(v: unknown): string {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? tx('yes') : tx('no');
  if (typeof v === 'number') return String(v);
  if (typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v)) return String(Number(v));
  return String(v);
}

export function qty(v: unknown): string {
  if (v == null || v === '') return '—';
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString(undefined, { maximumFractionDigits: 4 });
}
