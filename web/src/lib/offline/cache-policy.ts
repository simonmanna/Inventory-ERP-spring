/**
 * ADR-S083 S3 / O3 — which React Query results may be kept on this device for the read-only offline view.
 *
 * Every query-key family (the first element of the key) is classified; anything unclassified is `never` and is
 * not written to disk. `cache-policy.test.ts` scans the source and fails when a literal query key is not classified,
 * so a new screen has to make the choice explicitly.
 *
 * - `cache`               catalogue and setup data — 7 days
 * - `cache-permissioned`  business records (customers, suppliers, stock, documents) — 3 days, dropped when the
 *                         user's permissions change
 * - `cache-strict`        financial and management reports — 24 hours, dropped when permissions change
 * - `never`               auth, licence, secrets, device/admin state, and live state that is wrong when stale
 *                         (open shifts, tabs, holds, kitchen tickets, approvals)
 *
 * Persisted entries are isolated per organization and user (see query-cache.ts).
 */

export type CacheClass = 'cache' | 'cache-permissioned' | 'cache-strict' | 'never';

const HOUR = 3_600_000;

export const RETENTION_MS: Record<CacheClass, number> = {
  cache: 7 * 24 * HOUR,
  'cache-permissioned': 3 * 24 * HOUR,
  'cache-strict': 24 * HOUR,
  never: 0,
};

/** Rules are checked in order; the first match wins. Strings match the whole family name. */
type Rule = readonly [CacheClass, ReadonlyArray<string | RegExp>];

export const RULES: readonly Rule[] = [
  ['never', [
    // auth, licence, secrets, admin
    'license', 'api-clients', 'webhooks', 'webhooks-deliveries', 'settings-developer', 'pos-devices', 'user-data-scope',
    'hr-access', 'hr-workforce-access', 'sod-rules', 'audit-logs', 'config-changes', 'organization-users',
    'hr-linkable-users', 'crm-users', 'staff',
    /^backup-/, /^sync-dead-letter/, 'sync-status', 'sync-reconciliation', /^auth/, /token/, /password/, /secret/,
    // live state that must never be shown stale as current
    'cash-session', 'cash-register-drawer-balance', 'pos-holds', 'pos-split', 'pos-tab', 'pos-order', 'pos-orders',
    'pos-sale-quote', 'pos-kds', 'pos-kds-live', 'pos-kds-tickets', 'approvals', 'handover', 'posting-monitor',
    'budget-check', 'pos-store-credit', 'pos-loyalty-balance', 'pos-credit', 'credit', 'sync-stock-discrepancies',
    'sync-sales-reviews', 'sync-payment-reconciliation',
    // previews and pickers (transient, query-specific)
    /-preview$/, 'search-picker', 'product-picker', 'report-filter', 'mrp-run', 'process-period',
    // files are fetched through signed URLs that expire
    'files', 'dms', 'hr-documents', 'hr-documents-expiring',
    // personal messages, alerts, payment-provider state, external portal
    'communication', 'alerts', 'mobile-money', 'portal',
  ]],
  ['cache-strict', [
    /report/, /^balance-sheet/, 'pnl', 'trial-balance', 'general-ledger', /^cash-flow(?!-operation-types)/, 'ap-aging', 'ar-aging',
    'budget-vs-actual', 'consolidated', 'cost-center-pnl', 'cost-center-matrix', 'profitability', 'tax-summary',
    'tax-pack', /^fx-/, 'inventory-valuation', 'inventory-gl-tieout', 'tieout', /^journal/, 'account-ledger',
    'supplier-ledger', 'money-overview', 'money-activity', /^dashboard-/, 'sales-analysis', 'sales', 'bi',
    'bi-datasets', 'analytics', 'crm-analytics', 'procurement-analytics', /reconciliation$/, 'year-closing-balances',
    'opening-balances', 'cash-account-transactions', 'statement', 'customer-statement', 'branch-comparison',
    'ops-dashboard', 'fixed-assets-dashboard', 'grni-reconciliation', 'three-way-match', 'job-costing',
    'standard-variances', 'mfg-variances', 'cost-explanation', 'lineage-exceptions', 'loyalty-earned',
    'inventory-stats', 'partners-stats', 'hr-me-payslips', 'hr-workforce-sales', 'hr-pos-activity', 'cash-sales',
    'settlement-history', 'supplier-performance', 'pos-modifier-sales', 'close-checklist', 'year-close-checklist',
    'loss-prevention', 'commitments', 'stage-wip', 'where-used', 'bom-compare', 'bom-cost', 'cost-close',
    'advanced-costing', 'mfg-costing',
  ]],
  ['cache', [
    // catalogue
    'products', 'products-lite', 'all-products', 'product-categories', /^menu-/, 'pos-categories', 'pos-products',
    'pos-variants', 'pos-combos', 'pos-modifier-groups', 'pos-accompaniment-groups', /^pos-menu-item/,
    'pos-group-menu-items', 'pos-product-bundle', 'pos-tables', 'pos-lookup', 'pos-kitchen-stations',
    'pos-payment-methods', 'pos-payment-method-config', 'pos-payment-accounts', 'pos-settings', 'receipt-settings',
    'taxes', 'uoms', 'uom-categories', 'currencies', 'currency-rates', 'price-lists', 'partner-categories',
    'payment-terms', 'labels',
    // setup and structure
    'warehouses', /^inventory-locations/, 'locations-for-registers', 'branches', 'branch-options', 'branches-switch',
    'header-options', 'document-types', 'document-numbering', 'print-templates', 'modules', 'org-modules',
    'org-modules-catalog', 'organization-me', 'company-settings', 'settings', 'settings-company', 'settings-effective',
    'custom-fields', 'custom-objects', 'cash-registers', 'cash-registers-crud', 'account-categories', 'accounts',
    'account-mappings', 'account-dimension-rules', 'cost-centers', 'cost-centres-flat', 'cost-center-defaults',
    'analytic-dimensions', 'fiscal-positions', 'fiscal-periods', 'fiscal-years', 'work-centers', 'wc-calendar',
    'routings', 'routing-stage-setup', 'boms', 'bom', 'bom-stage-setup', 'bom-standards', 'bom-substitutes',
    'asset-categories', 'cash-flow-operation-types', 'inventory-defaults', 'mrp-settings', 'planning-params',
    'cost-components', 'cost-policies', 'cost-processes', 'standard-costs', 'cost-alloc-rules',
    'cost-allocation-rules', 'approval-controls', 'approval-limits', 'approval-policies', 'approval-workflows',
    'procurement-policies', 'procurement-delegations', 'landed-cost-accrual-rules', 'customs-tariffs',
    'cash-accounts', 'bank-accounts', 'pos-accompaniment-groups', 'resources', 'order-stages', 'automation',
    'intercompany-companies', 'hr-training-programs', 'settlement-sources',
    'guide-progress', 'notification-routing', 'org-features', 'product-sizing', 'product-specs', 'product-variants',
  ]],
  ['cache-permissioned', [
    // people
    'partners', 'partner', /^crm-/, 'pos-customers', 'customer-deposits', /^procurement-suppliers/, 'supplier-portal',
    /^hr/,
    // stock
    /^inventory/, 'stock-adjustments', 'replenishment', 'mfg-availability', 'shipments', 'returns', 'return',
    // documents and work
    'invoice', 'invoices', 'invoice-cash-sale', 'invoice-duplicate-overrides', 'credit-note', 'credit-notes', 'debit-notes', 'receipt',
    'receipts', 'payment', 'payments', 'payment-schedule', 'supplier-payments', 'supplier-claims', 'vendor-bills',
    'purchase-order', 'purchase-orders', 'purchase-requests', 'purchase-request-approval', 'po-changes', 'po-for-grn',
    'goods-receipt', 'goods-receipts', 'rfqs', 'sourcing', 'emergency-purchases', 'procurement-budgets', 'expense',
    'expenses', 'recurring', 'budget', 'budgets', 'agreements', 'contract', 'credit-control',
    /^landed-cost/, 'customs-calculations', 'withholding-certificates', 'fixed-asset', 'fixed-assets', 'fleet',
    'rental', 'repair', 'manufacturing', /^production-/, 'work-orders', 'time-logs', 'mrp-runs', 'mrp-capacity',
    'mfg-schedule', 'order-qc-plan', 'order-tree', 'inspections', 'ecos', 'pm', 'cost-ops', 'cost-management',
    'cost-alloc-runs', 'beverage-counts', 'pos-reservations',
    // industry and operations modules
    'intercompany', 'warehouse', 'orders', 'driver-app', 'helpdesk', 'crushing', 'quarry', 'precast', 'processing',
    'construction', 'quality', 'shop-floor', 'planning', 'plm',
  ]],
];

/** The family name of a query key: its first element. */
export function family(queryKey: readonly unknown[]): string {
  return typeof queryKey[0] === 'string' ? queryKey[0] : '';
}

/** The class of a query-key family, or null when no rule covers it (treated as `never`). */
export function ruleFor(name: string): CacheClass | null {
  for (const [cls, patterns] of RULES) {
    for (const p of patterns) {
      if (typeof p === 'string' ? p === name : p.test(name)) return cls;
    }
  }
  return null;
}

export function classify(queryKey: readonly unknown[]): CacheClass {
  return ruleFor(family(queryKey)) ?? 'never';
}

/** Whether a stored result of this class is still within its retention at `now`. */
export function withinRetention(cls: CacheClass, dataUpdatedAt: number, now: number): boolean {
  return cls !== 'never' && dataUpdatedAt > 0 && now - dataUpdatedAt <= RETENTION_MS[cls];
}
