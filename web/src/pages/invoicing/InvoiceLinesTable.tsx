import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { SlidersHorizontal } from 'lucide-react';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { api } from '@/lib/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import type { Handover } from '@/pages/sales/HandoverTab';

/** Optional columns of the invoice lines table (Odoo-style column picker). */
type Col = 'account' | 'delivered' | 'invoiced' | 'discount' | 'taxes' | 'taxIncl';
const COLS: Array<[Col, string]> = [
  ['account', tx('Account')], ['delivered', tx('Delivered')], ['invoiced', tx('Invoiced')],
  ['discount', tx('Discount')], ['taxes', tx('Taxes')], ['taxIncl', tx('Tax Incl.')],
];
const DEFAULT_COLS: Col[] = ['delivered', 'invoiced', 'taxes', 'taxIncl'];
const STORE_KEY = 'invoice-lines.columns';

function loadCols(): Set<Col> {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) return new Set((JSON.parse(raw) as Col[]).filter((c) => COLS.some(([k]) => k === c)));
  } catch { /* storage unavailable */ }
  return new Set(DEFAULT_COLS);
}

function saveCols(cols: Set<Col>) {
  try { localStorage.setItem(STORE_KEY, JSON.stringify([...cols])); } catch { /* storage unavailable */ }
}

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
  const [cols, setCols] = useState<Set<Col>>(loadCols);
  const path = `/sales/invoices/${invoiceId}/handover`;
  const { data: handover } = useQuery({
    queryKey: ['handover', path],
    enabled: withDelivery,
    queryFn: async () => (await api.get<Handover>(path)).data,
  });
  const delivered = new Map((handover?.lines ?? []).map((l) => [l.id, Number(l.handedOverQty ?? 0)]));
  const invoicedFactor = ['posted', 'paid'].includes(status) ? 1 : 0;

  const show = (c: Col) => cols.has(c) && (withDelivery || c !== 'delivered');
  const toggle = (c: Col) => setCols((prev) => {
    const next = new Set(prev);
    if (next.has(c)) next.delete(c); else next.add(c);
    saveCols(next);
    return next;
  });
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
            {show('delivered') && <th className={cn(th, 'w-24 text-right')}>{tx('Delivered')}</th>}
            {show('invoiced') && <th className={cn(th, 'w-24 text-right')}>{tx('Invoiced')}</th>}
            <th className={cn(th, 'w-28 text-right')}>{tx('Unit Price')}</th>
            {show('taxes') && <th className={cn(th, 'w-36 pl-3')}>{tx('Taxes')}</th>}
            {show('discount') && <th className={cn(th, 'w-24 text-right')}>{tx('Discount')}</th>}
            <th className={cn(th, 'w-36 text-right')}>{tx('Amount')}</th>
            <th className="w-10 py-2 text-right">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button type="button" className="rounded border p-1 text-muted-foreground hover:text-foreground" aria-label={tx('Optional columns')}>
                    <SlidersHorizontal className="h-4 w-4" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="min-w-[180px]">
                  <DropdownMenuLabel className="text-xs text-muted-foreground">{tx('Optional columns')}</DropdownMenuLabel>
                  {COLS.filter(([c]) => withDelivery || c !== 'delivered').map(([c, label]) => (
                    <DropdownMenuItem key={c} onSelect={(e) => { e.preventDefault(); toggle(c); }} className="gap-2">
                      <input type="checkbox" readOnly checked={cols.has(c)} className="h-4 w-4 accent-primary" tabIndex={-1} />
                      {label}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
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
                  {l.discountReason ? <span className="block text-xs italic text-muted-foreground">({l.discountReason})</span> : null}
                </td>
                {show('account') && (
                  <td className="py-2.5 pr-2 text-muted-foreground">
                    {l.account ? <span><span className="font-mono text-xs">{l.account.code}</span> {l.account.name}</span> : '-'}
                  </td>
                )}
                <td className="py-2.5 pr-2 text-right tabular-nums">{qty(l.quantity)} <span className="text-xs text-muted-foreground">{uomCode(l.uomId)}</span></td>
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
