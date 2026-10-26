import { tx } from '@/lib/i18n/tx';
/**
 * Maps a stock-ledger line's resolved source (`kind` + `targetId`, from
 * `GET /inventory/ledger/:id`) onto the app route that shows that transaction.
 *
 * Kinds with no first-class detail page fall back to their list page, and
 * anything unresolved falls back to the generic movement page.
 */
export type LedgerSourceKind =
  | 'pos_invoice'
  | 'invoice'
  | 'credit_note'
  | 'goods_receipt'
  | 'purchase_order'
  | 'debit_note'
  | 'production_order'
  | 'rental_agreement'
  | 'repair_order'
  | 'stock_out'
  | 'waste'
  | 'stock_adjustment'
  | 'stock_transfer'
  | 'none';

export interface LedgerSourceRef {
  referenceType: string;
  referenceId: string;
  kind: LedgerSourceKind;
  targetId: string | null;
  code: string | null;
  status: string | null;
  date: string | null;
  partnerName: string | null;
  label: string;
}

/** Route for a source document, or null when the kind has no page of its own. */
export function sourceRoute(source: LedgerSourceRef | null | undefined): string | null {
  if (!source) return null;
  const id = source.targetId;

  switch (source.kind) {
    case 'pos_invoice':
      return id ? `/pos/receipts/${id}` : null;
    case 'invoice':
      return id ? `/invoices/${id}` : null;
    case 'credit_note':
      return id ? `/credit-notes/${id}` : null;
    case 'goods_receipt':
      return id ? `/procurement/goods-receipts/${id}` : null;
    case 'purchase_order':
      return id ? `/procurement/purchase-orders/${id}` : null;
    case 'production_order':
      return id ? `/manufacturing/orders/${id}` : null;
    case 'rental_agreement':
      return id ? `/rental/agreements/${id}` : null;
    case 'repair_order':
      return id ? `/repair/orders/${id}` : null;
    // List-only modules — no per-document page exists yet.
    case 'debit_note':
      return '/procurement/debit-notes';
    case 'waste':
      return source.code ? `/inventory/waste?code=${encodeURIComponent(source.code)}` : '/inventory/waste';
    case 'stock_out':
      return '/inventory/adjustments';
    case 'stock_adjustment':
      return '/inventory/adjustments';
    case 'stock_transfer':
      return '/inventory/transfers';
    default:
      return null;
  }
}

/** Where the ledger's "View" button should land for a given entry. */
export function ledgerViewRoute(entryId: string, source: LedgerSourceRef | null | undefined): string {
  return sourceRoute(source) ?? `/inventory/ledger/${entryId}`;
}

const REFERENCE_LABELS: Record<string, string> = {
  pos_invoice: tx('POS Sale'),
  pos_invoice_extra: tx('POS Sale (extras)'),
  pos_invoice_writeoff: tx('POS Write-Off'),
  menu_recipe: tx('Recipe Consumption'),
  pos_refund: tx('POS Refund'),
  sales_invoice: tx('Sales Invoice'),
  invoice: tx('Invoice'),
  document: tx('Invoice'),
  credit_note: tx('Credit Note'),
  debit_note: tx('Debit Note'),
  goods_receipt: tx('Goods Receipt'),
  purchase_order: tx('Purchase Order'),
  direct_stock_in: tx('Stock In'),
  direct_stock_out: tx('Stock Out (direct)'),
  stock_out: tx('Stock Out'),
  waste: tx('Waste'),
  expiry_write_off: tx('Expiry Write-Off'),
  stock_adjust: tx('Stock Adjustment'),
  adjustment: tx('Stock Adjustment'),
  stock_transfer: tx('Stock Transfer'),
  production_order: tx('Production Order'),
  production_qc: tx('Production QC'),
  production_reversal: tx('Production Reversal'),
  production_order_cancel: tx('Production Cancelled'),
  work_order: tx('Work Order'),
  agreement: tx('Rental Agreement'),
  rental_checkout: tx('Rental Checkout'),
  rental_return: tx('Rental Return'),
  rental_inspect: tx('Rental Inspection'),
  repair_part_issue: tx('Repair Part Issue'),
  min_stock: tx('Min-Stock Top-Up'),
  manual: tx('Manual Entry'),
};

/** Friendly name for a raw `referenceType`. */
export function referenceLabel(referenceType: string | null | undefined): string {
  if (!referenceType) return 'Manual';
  return (
    REFERENCE_LABELS[referenceType] ??
    referenceType.split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')
  );
}
