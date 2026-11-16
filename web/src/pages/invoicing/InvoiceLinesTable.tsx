import { useQuery } from '@tanstack/react-query';
import { LineColumnsPicker, useLineColumns, type LineColumn } from '@/components/line-columns';
import { api } from '@/lib/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { Handover } from '@/pages/sales/HandoverTab';
import { sizeLabel, useProductSizing } from '@/features/products/sizing';

/** Optional columns of the invoice lines table (Odoo-style column picker). */
type Col = 'account' | 'uom' | 'delivered' | 'invoiced' | 'discount' | 'taxes' | 'taxIncl';
const COLS: Array<LineColumn<Col>> = [
  ['account', tx('Account')], ['uom', tx('UoM')], ['delivered', tx('Delivered')], ['invoiced', tx('Invoiced')],
  ['discount', tx('Disc.%')], ['taxes', tx('Taxes')], ['taxIncl', tx('Tax Incl.')],
];
const DEFAULT_COLS: Col[] = ['uom', 'delivered', 'invoiced', 'taxes', 'taxIncl'];

/** Render a tax rate stored as fraction (0.15) or percent (15). */
function rateLabel(rate: string | number | undefined | null): string {
  const n = Number(rate ?? 0);
  if (!n) return '';
  return `${n <= 1 ? Math.round(n * 1000) / 10 : n}%`;
}

const qty = (v: number | string | null | undefined) => Number(v ?? 0).toFixed(2);

export interface InvoiceLineRow {
  id: string; lineType?: string | null; description: string; discountReason?: string | null;
  account?: { code: string; name: string } | null; quantity: string; uomId?: string | null; unitPrice: string;
  tax?: { id: string; name: string; rate: string | number } | null; taxAmount: string; subtotal: string; total: string;
  discountType?: string | null; discountAmount?: string | null; discountPercent?: string | null;
  /** ADR-S058 / ADR-S066: present only on variant / sized lines. */
  productId?: string | null; variantName?: string | null;
  pieces?: string | number | null; length?: string | number | null; width?: string | number | null;
  height?: string | number | null;
}

/** Variant name (when the description does not already say it) and printable size of a line. */
function LineDetails({ line }: { line: InvoiceLineRow }) {
  const sized = line.pieces !== null && line.pieces !== undefined;
  const { data: cfg } = useProductSizing(sized ? line.productId : null);
  const size = sized ? sizeLabel(line, cfg?.sizingUnit) : null;
  const variant = line.variantName && !line.description?.includes(line.variantName) ? line.variantName : null;
  if (!variant && !size) return null;
  return <span className="block text-xs text-muted-foreground">{[variant, size].filter(Boolean).join(' · ')}</span>;
}

/**
 * Invoice lines with Quantity / Delivered / Invoiced columns. Delivered comes from the invoice's delivery handover
 * (`/sales/invoices/:id/handover`, same query the Delivery tab uses); Invoiced is the line quantity once the invoice
 * is posted (zero on a draft or cancelled invoice).
 */
export function InvoiceLinesTable({ invoiceId, status, lines, withDelivery, uomCode }: {
  invoiceId: string; status: string; lines: InvoiceLineRow[]; withDelivery: boolean;
  uomCode: (id?: string | null) => string;
}) {
  const { cols, toggle } = useLineColumns<Col>('invoice-lines.columns.v2', COLS, DEFAULT_COLS);
  const path = `/sales/invoices/${invoiceId}/handover`;
  const { data: handover } = useQuery({
    queryKey: ['handover', path],
    enabled: withDelivery,
    queryFn: async () => (await api.get<Handover>(path)).data,
  });
  const delivered = new Map((handover?.lines ?? []).map((l) => [l.id, Number(l.handedOverQty ?? 0)]));
  const invoicedFactor = ['posted', 'paid'].includes(status) ? 1 : 0;

  const show = (c: Col) => cols.has(c) && (withDelivery || c !== 'delivered');
  const th = 'py-2 pr-2 text-left text-sm font-semibold';
  const span = 4 + COLS.filter(([c]) => show(c) && c !== 'taxIncl').length;

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[860px] text-sm">
        <thead className="border-b">
          <tr>
            <th className={th}>{tx('Description')}</th>
            {show('account') && <th className={th}>{tx('Account')}</th>}
            <th className={cn(th, 'w-24 text-right')}>{tx('Quantity')}</th>
            {show('uom') && <th className={cn(th, 'w-20 pl-2')}>{tx('UoM')}</th>}
            {show('delivered') && <th className={cn(th, 'w-24 text-right')}>{tx('Delivered')}</th>}
            {show('invoiced') && <th className={cn(th, 'w-24 text-right')}>{tx('Invoiced')}</th>}
            <th className={cn(th, 'w-28 text-right')}>{tx('Unit Price')}</th>
            {show('taxes') && <th className={cn(th, 'w-36 pl-3')}>{tx('Taxes')}</th>}
            {show('discount') && <th className={cn(th, 'w-24 text-right')}>{tx('Disc.%')}</th>}
            <th className={cn(th, 'w-36 text-right')}>{tx('Amount')}</th>
            <th className="w-10 py-2 text-right">
              <LineColumnsPicker columns={COLS.filter(([c]) => withDelivery || c !== 'delivered')} cols={cols} toggle={toggle} />
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.length === 0 ? (
            <tr><td colSpan={span + 1} className="py-10 text-center text-muted-foreground">{tx('No line items found.')}</td></tr>
          ) : lines.map((l) => {
            const kind = l.lineType ?? 'product';
            if (kind === 'section' || kind === 'note') {
              return (
                <tr key={l.id} className={cn('border-b', kind === 'section' && 'bg-muted/40')}>
                  <td colSpan={span + 1} className={kind === 'section' ? 'py-1.5 pr-2 font-semibold' : 'py-1.5 pr-2 italic text-muted-foreground'}>
                    {l.description || (kind === 'section' ? tx('Section') : tx('Note'))}
                  </td>
                </tr>
              );
            }
            const discountLabel = l.discountType === 'fixed_amount'
              ? `-${money(l.discountAmount)}`
              : l.discountPercent && Number(l.discountPercent) > 0 ? `${Number(l.discountPercent).toFixed(1)}%` : '-';
            const hasTax = !!l.tax && Number(l.taxAmount) > 0;
            return (
              <tr key={l.id} className="border-b align-top">
                <td className="py-2.5 pr-2">
                  {l.description}
                  <LineDetails line={l} />
                  {l.discountReason ? <span className="block text-xs italic text-muted-foreground">({l.discountReason})</span> : null}
                </td>
                {show('account') && (
                  <td className="py-2.5 pr-2 text-muted-foreground">
                    {l.account ? <span><span className="font-mono text-xs">{l.account.code}</span> {l.account.name}</span> : '-'}
                  </td>
                )}
                <td className="py-2.5 pr-2 text-right tabular-nums">{qty(l.quantity)}</td>
                {show('uom') && <td className="py-2.5 pl-2 pr-2 text-muted-foreground">{uomCode(l.uomId) || '-'}</td>}
                {show('delivered') && <td className="py-2.5 pr-2 text-right tabular-nums">{qty(delivered.get(l.id))}</td>}
                {show('invoiced') && <td className="py-2.5 pr-2 text-right tabular-nums">{qty(Number(l.quantity) * invoicedFactor)}</td>}
                <td className="py-2.5 pr-2 text-right tabular-nums">{money(l.unitPrice)}</td>
                {show('taxes') && (
                  <td className="py-2 pl-3 pr-2">
                    {hasTax
                      ? <span className="inline-block max-w-[9rem] truncate rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium">{`${l.tax!.name} ${rateLabel(l.tax!.rate)}`.trim()}</span>
                      : <span className="text-muted-foreground">-</span>}
                  </td>
                )}
                {show('discount') && <td className="py-2.5 pr-2 text-right tabular-nums">{discountLabel}</td>}
                <td className="py-2.5 pr-2 text-right tabular-nums">
                  <span className="font-medium">{money(l.subtotal)}</span>
                  {show('taxIncl') && (
                    <span className="mt-1 block text-xs text-muted-foreground">
                      <span className="mr-2">{tx('TI:')}</span><span className="text-sm text-foreground">{money(l.total)}</span>
                    </span>
                  )}
                </td>
                <td />
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
