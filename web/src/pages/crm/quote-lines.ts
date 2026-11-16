import { useLineColumns, type LineColumn } from '@/components/line-columns';
import { tx } from '@/lib/i18n/tx';
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

/** The product Image column (only while the organization shows images on quotations). */
export type ImageCol = 'image';
export const IMAGE_COL: ReadonlyArray<LineColumn<ImageCol>> = [['image', tx('Image')]];

/**
 * Whether this user shows the product Image column, remembered in the browser apart from the other columns so it
 * starts shown when the organization turns images on. The print and the PDF follow the same choice.
 */
export function useQuoteImageColumn() {
  const { show, toggle } = useLineColumns<ImageCol>('quotation-lines.image.v1', IMAGE_COL, ['image']);
  return { show: show('image'), toggle: () => toggle('image') };
}
