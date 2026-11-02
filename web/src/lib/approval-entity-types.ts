import { tx } from '@/lib/i18n/tx';
/**
 * Human labels for the approval engine's entityTypes — the document types that
 * can be gated behind an approval workflow. Shared by the workflow builder and
 * the legacy policy page.
 */
export const ENTITY_TYPE_LABELS: Record<string, string> = {
  purchase_order: tx('Purchase Orders'),
  purchase_request: tx('Purchase Requests'),
  sales_order: tx('Sales Orders'),
  goods_receipt: tx('Goods Receipts'),
  credit_note: tx('Credit Notes'),
  invoice_cancel: tx('Invoice Cancel'),
  supplier_payment: tx('Supplier Payments'),
  asset_acquisition: tx('Asset Acquisition'),
  asset_disposal: tx('Asset Disposal'),
  asset_transfer: tx('Asset Transfer'),
  asset_revaluation: tx('Asset Revaluation'),
  inventory_count_submit: tx('Inventory Count Submit'),
  vendor_bill: tx('Vendor Bills'),
  // F.5b — newly gated flows
  expense: tx('Expenses'),
  expense_claim: tx('Expense Claims'),
  stock_out: tx('Stock-Out'),
  waste: tx('Waste'),
  debit_note: tx('Debit Notes'),
  depreciation_run: tx('Depreciation'),
  pos_discount: tx('POS Discount Override'),
  pos_refund: tx('POS Refund Override'),
  // Rental — agreement approval (trip when score < min, over credit limit,
  // or the customer has an open overdue agreement).
  rental_agreement: tx('Rental Agreements'),
  // Fleet (ADR-S009) — odometer / hour-meter rollbacks and meter replacements.
  fleet_meter_correction: tx('Fleet Meter Corrections'),
  // Quarry (ADR-S012) — explosive issues from a magazine (controlled stock).
  quarry_explosive_issue: tx('Explosive Issues'),
  // Controls (CTRL-1) — sensitive pairs and held configuration changes.
  stock_adjustment: tx('Stock Adjustments'),
  production_loss_writeoff: tx('Production Loss Write-offs'),
  credit_override: tx('Credit Overrides'),
  supplier_bill_approval: tx('Supplier Bill Approval'),
  expense_payment: tx('Expense Payment'),
  config_change: tx('Configuration Changes'),
};

export function entityTypeLabel(entityType: string): string {
  return ENTITY_TYPE_LABELS[entityType] ?? entityType;
}
