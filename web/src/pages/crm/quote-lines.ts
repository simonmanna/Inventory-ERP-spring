import type { QuoteLine } from '@/features/crm/suite-api';

export type TaxOption = { id: string; name: string; rate: number | string };
export const isLayout = (l: QuoteLine) => l.displayType === 'line_section' || l.displayType === 'line_note';

/** Untaxed, tax and total of one priced line (server figures when saved, otherwise a client estimate). */
export function lineAmounts(l: QuoteLine, rate: number, useServer: boolean) {
  if (useServer && l.lineSubtotal != null && l.lineTotal != null) {
    return { sub: Number(l.lineSubtotal), tax: Number(l.lineTax ?? 0), total: Number(l.lineTotal) };
  }
  const gross = Number(l.quantity || 0) * Number(l.unitPrice || 0) * (1 - Number(l.discountPercent || 0) / 100);
  if (l.taxInclusive) {
    const sub = gross / (1 + rate / 100);
    return { sub, tax: gross - sub, total: gross };
  }
  return { sub: gross, tax: (gross * rate) / 100, total: gross * (1 + rate / 100) };
}
