import type { QuoteLine } from '@/features/crm/suite-api';

const lineKey = (l: QuoteLine) => [l.productId ?? '', l.variantId ?? '', l.uomId ?? '', l.productId ? '' : (l.description ?? '')].join('|');
export const num = (v: unknown) => Number(v ?? 0);

/** Lines of both sides matched by product / variant / unit (or description for a charge), in display order. */
export function diffLines(a: QuoteLine[], b: QuoteLine[]) {
  const priced = (ls: QuoteLine[]) => ls.filter((l) => !l.displayType);
  const left = new Map<string, QuoteLine[]>();
  for (const l of priced(a)) left.set(lineKey(l), [...(left.get(lineKey(l)) ?? []), l]);
  const rows: Array<{ a?: QuoteLine; b?: QuoteLine; change: 'same' | 'changed' | 'added' | 'removed' }> = [];
  for (const l of priced(b)) {
    const match = left.get(lineKey(l))?.shift();
    if (!match) { rows.push({ b: l, change: 'added' }); continue; }
    const same = num(match.quantity) === num(l.quantity) && num(match.unitPrice) === num(l.unitPrice)
      && num(match.discountPercent) === num(l.discountPercent) && !!match.isOptional === !!l.isOptional && match.description === l.description;
    rows.push({ a: match, b: l, change: same ? 'same' : 'changed' });
  }
  for (const rest of left.values()) for (const l of rest) rows.push({ a: l, change: 'removed' });
  return rows;
}

/** Fill the email template placeholders. */
export function fillTemplate(t: string, v: Record<string, string>) {
  return t.replace(/\{(number|customer|validUntil|total|company|link)\}/g, (_, k: string) => v[k] ?? '');
}

