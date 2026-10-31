# Gap analysis — construction materials requirements

One row per list item of [`construction-materials-erp.md`](construction-materials-erp.md) sections 2.1–10
(263 rows: every `-` bullet, the 8 numbered weighbridge steps of 5.1 and the 10 KPI table rows of section 10).
"Line" is the line number in that file. Based on main `59e607f`.

> **Nothing was compiled or run for this analysis.** No Gradle, g.py, web check or test was executed, and no test was
> added. Evidence comes from reading routes, services, screens and existing tests. `present-verified` therefore means
> "a route, a service, a screen where one applies, and an existing test that exercises the behaviour were all found in
> the code", not "seen passing in this pass". A failing test would not show up here.

| Status | Meaning |
|---|---|
| `present-verified` | Route + service + screen found, and a named existing test covers the behaviour |
| `present-partial` | Real functionality, but part of the bullet, the screen or a test is absent ("needs test" = code exists, no test covers it) |
| `missing` | Not built |

## Summary

| Section | Rows | Verified | Partial | Missing |
|---|---:|---:|---:|---:|
| 2.1 Financial Management and Accounting | 15 | 10 | 5 | 0 |
| 2.2 Procurement and Supplier Management | 12 | 5 | 4 | 3 |
| 2.3 Inventory and Warehouse Management | 14 | 11 | 3 | 0 |
| 2.4 Sales, Invoicing, and Customer Management | 14 | 7 | 6 | 1 |
| 2.5 Human Resources and Payroll | 12 | 7 | 4 | 1 |
| 2.6 Customer Relationship Management | 9 | 5 | 3 | 1 |
| 2.7 Reporting, Analytics, and Business Intelligence | 11 | 4 | 7 | 0 |
| 3.1 Quarry and Raw Material Extraction Management | 12 | 10 | 2 | 0 |
| 3.2 Aggregate Crushing, Screening, and Processing | 12 | 10 | 2 | 0 |
| 3.3 Concrete Block Manufacturing | 14 | 12 | 2 | 0 |
| 3.4 Production Planning and Scheduling | 10 | 4 | 5 | 1 |
| 3.5 Quality Control and Product Specifications | 9 | 9 | 0 | 0 |
| 4.1 Multiple Units of Measurement | 5 | 3 | 2 | 0 |
| 4.2 Inventory Costing | 9 | 6 | 3 | 0 |
| 4.3 Landed Cost and Procurement Cost Allocation | 6 | 4 | 2 | 0 |
| 5 Fleet, Transport, and Delivery Management | 14 | 14 | 0 | 0 |
| 5.1 Weighbridge Integration | 8 | 8 | 0 | 0 |
| 6 Branch, Warehouse, and Multi-Location Management | 11 | 4 | 4 | 3 |
| 7 Maintenance and Asset Management | 12 | 9 | 3 | 0 |
| 8.1 Configurable Business Rules | 14 | 8 | 6 | 0 |
| 8.2 Custom Modules and Workflow Extensions | 10 | 5 | 3 | 2 |
| 8.3 Integration and Automation | 9 | 3 | 5 | 1 |
| 9 Security, Auditability, and Operational Control | 11 | 8 | 2 | 1 |
| 10 Management Dashboards and Industry-Specific KPIs | 10 | 0 | 9 | 1 |
| **Total** | **263** | **166** | **82** | **15** |

## Not built from Phase 2

- **2.5 advanced planning & scheduling** and **2.8 manufacturing costing & analytics** were never built, and there is
  no `manufacturing-advanced-complete` tag. Bullets that rest on them are `present-partial` where older manufacturing
  code covers part of the bullet, and `missing` where it covers none.
- **Phase 2 exit ITs (MFG-3, written, not run)**: (a) `Phase2ExitIT.quarryToSizedStockpiles`, (b)
  `PrecastManufacturingIT.actualCostPerAcceptableBlock`, (c) `MultiStageProductionIT.multiLevelTraceability`. The blast in
  (a) stays planned because firing needs the explosives flow `QuarryIT` covers. Before MFG-3: end-to-end (a) blast to cost per tonne did not exist: no test touches both
  `/quarry/extraction-logs` and `/manufacturing/crushing/runs`. (b) `PrecastManufacturingIT.endToEnd` covers most of the
  block flow; check it against the brief. (c) `MultiStageProductionIT.multiLevel` covers the multi-level order; the
  traceability report step needs checking against the brief.
- **Demo seed (MFG-4, built, unverified)**: `SEED_MANUFACTURING_DEMO` (`ManufacturingDemoSeedContributor`,
  `ManufacturingDemoSeedIT`): one quarry, one crusher line, one block plant, recipes and a month of runs. Not run.

## Phase 4 items

Sizes: S up to a day, M a few days, L a week or more of agent work. These are rough.

| Item | Module | Work | Size |
|---|---|---|---:|
| MFG-1 | manufacturing | 2.5 planning: demand from orders, delivery schedule and forecast, pegging, priorities, capacity and bottleneck view, backward scheduling, Gantt, shift plans, plan adherence, production vs target | L |
| MFG-2 | manufacturing | 2.8 costing: actual labour, energy and equipment cost, absorption rates, full variances, cost by shift, margin by product | L |
| MFG-3 | manufacturing | Phase 2 end-to-end ITs (a), (b), (c) | M |
| MFG-4 | manufacturing, quarry | Demo seed: quarry, crusher line, block plant, recipes, month of runs | M |
| UOM-1 | core | m³, bag and truckload units, product density used in sales, stock, weighbridge and invoicing | M |
| BR-1 | core, inventory, kernel | Location to branch link; wire data-level branch and warehouse scope into services | L |
| BR-2 | accounting, reports | Branch comparison, operational consolidation, common report filter, branch budget test | M |
| SALES-1 | core, invoicing, orders | Credit limit and hold enforcement, exposure incl. undelivered orders | M |
| SALES-2 | orders | Order approval through the approvals engine | S |
| SALES-3 | crm, orders, invoicing | Named price lists, tiers, branch, site and UoM prices, applied to orders and invoices | L |
| SALES-4 | new or crm, hr | Salesperson commissions | M |
| SALES-5 | invoicing, reports | Sales analysis by product and branch | S |
| PROC-1 | procurement | RFQ and supplier quotation comparison | M |
| PROC-2 | procurement | Price agreements and price history | M |
| PROC-3 | procurement | Procurement budgets, spend control, delegated purchasing | M |
| PROC-4 | procurement, invoicing | Supplier performance, payment schedule, purchases report test | M |
| PROC-5 | procurement | Purchase requests through the approvals engine | S |
| PROC-6 | procurement | Landed cost test for the HANDLING component | S |
| FIN-1 | accounting | Bank reconciliation screen and test | M |
| FIN-2 | invoicing, web | Routed vendor bill screens, AP aging screen | M |
| FIN-3 | accounting | P&L and cash flow value tests, `/taxes` test, tax pack, close checklist | M |
| INV-1 | inventory, web | Reservation screen | S |
| INV-2 | core | Product attributes, specifications and grades, category test | M |
| INV-3 | inventory | Replenishment rules and alerts | M |
| HR-1 | hr | Recruitment and onboarding | M |
| HR-2 | hr | Contract records | S |
| HR-3 | hr | Configurable overtime | S |
| HR-4 | hr, web | Performance review screen | S |
| HR-5 | hr | Payroll allocation by department and cost centre | M |
| CRM-1 | crm | Complaints and service requests, tied to delivery discrepancies | M |
| CRM-2 | crm, core | Tests for contacts, reminders and saved filters; segment decision | S |
| CMMS-1 | fleet, fixedasset | Fixed plant register, classes, schedules, work orders, cost, proven by tests | M |
| CTRL-1 | kernel/approvals | Department and branch conditions, SoD checks, controlled config changes, config route tests | M |
| CTRL-2 | kernel | Document numbering configuration | S |
| INT-1 | accounting | Mobile money provider SPI and mock | M |
| INT-2 | fleet | Real telematics vendor adapter, once chosen | M |
| INT-3 | accounting, web | Bank import formats, label printing | M |
| PORTAL-1 | new | Customer portal | L |
| DRIVER-1 | fleet, web | Driver web app: fuel log, pre-trip checklist | M |

Dashboards, KPI tiles, alerts and BI rows in sections 2.7, 8 and 10 belong to Phase 5.

## Merge-safe order

Nearly every item touches `permissions.ts`, `App.tsx`, `app-shell.tsx` and the locale files, so keep those edits small.
Beyond that, these sets share real code and must not run in parallel:

1. **Manufacturing, in sequence**: MFG-1, MFG-2, MFG-3, MFG-4. MFG-3 asserts the cost figures MFG-2 produces, and the
   seed needs both.
2. **Approvals engine**: CTRL-1 first, then PROC-3, PROC-5 and SALES-2, which depend on its conditions.
3. **Sales documents**: UOM-1, then SALES-1, then SALES-3. All three touch quantity and price on order and invoice lines.
4. **BR-1 runs alone.** It changes a core table and reaches into many services. Run it before BR-2, and before SALES-3
   so branch pricing has a branch model to use.
5. **Invoicing**: FIN-2 and PROC-4 both touch vendor bills and supplier payments.
6. **Fleet**: CMMS-1 and DRIVER-1 both touch fleet screens; INT-2 can follow.

Safe to run alongside those: FIN-1, FIN-3, INV-1, INV-2, INV-3, PROC-1, PROC-2, PROC-6, HR-1 to HR-5, CRM-1, CRM-2,
CTRL-2, INT-1, INT-3, SALES-4, SALES-5. HR items share `HrController` and should run one after another.

## Matrix


### 2.1 Financial Management and Accounting

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 27 | General ledger and chart of accounts management. | `present-verified` | accounting · `/accounts`, `/journal-entries` · JournalEntryService · `/accounts`, `/journal-entries` · AccountingIT.seeded/journalEntries | — |
| 28 | Accounts payable and supplier payment management. | `present-verified` | invoicing · `/vendor-bills`, `/supplier-payments`, `/reports/ap/aging` · VendorBillService, PaymentService · `/supplier-payments`, `/suppliers/:id/ledger` · InvoicingIT.vendorBills (AP aging), ProcurementIT.payments | AP aging has no screen: see row 2.7 outstanding balances (FIN-2) |
| 29 | Accounts receivable and customer debt management. | `present-verified` | invoicing · `/invoices`, `/payments`, `/reports/ar/*` · InvoiceService, PaymentService · `/invoices`, `/payments`, `/ar-aging` · InvoicingIT.invoices/receipts/arReports | — |
| 30 | Sales invoicing, purchase invoices, credit notes, and debit notes. | `present-partial (built, unverified)` | Sales invoices, credit notes (`/invoices`, `/credit-notes`) and supplier debit notes (`/procurement/debit-notes`) have screens and tests (InvoicingIT, ProcurementIT.debitNotes). Purchase invoices: `/vendor-bills` + VendorBillService tested (InvoicingIT.vendorBills, ProcurementIT.billMatching) but **no routed screen**: `pages/purchasing/expense-*.tsx` use the hooks and are imported nowhere in App.tsx. Phase 4: routed vendor bill list, create, edit and detail screens (`/vendor-bills`), list filters status + supplier, VendorBillListIT | Unverified: nothing compiled or run |
| 31 | Cash and bank account management. | `present-verified` | accounting · `/bank-accounts`, `/accounts/cash-flow/*`, `/treasury` · CashFlowService · `/accounts/cash-accounts*` · TreasuryIT.depositAndTransfer, AccountingIT.accounts | — |
| 32 | Bank reconciliation and cashbook management. | `present-partial (built, unverified)` | Cashbook verified (TreasuryIT, `/accounts/cash-accounts/activity`). Bank reconciliation: `/bank-reconciliation/{import,match,unmatch,status}` + BankReconciliationService exist, **no screen and no test** (grep over `web/src` and `backend/src/test` empty). Phase 4: screen `/bank-reconciliation` (CSV import, auto-match, manual match, exclude, undo, summary), new routes lines, runs, summary, candidates, lines/{id}/match, lines/{id}/exclude, migration V71, BankReconciliationIT | Unverified: nothing compiled or run. Bank-specific import formats stay with INT-3 |
| 33 | Expense management and departmental expense allocation. | `present-verified` | expenses, accounting · `/expenses`, `/cost-centers/allocation-rules`, allocation runs · ExpensesService, cost-centre allocation · `/expenses`, `/cost-center-accounting` · InvoicingIT.expenses, CostCenterAccountingIT.periodicAllocationAndRollup | — |
| 34 | Budget preparation, monitoring, and variance analysis. | `present-verified` | accounting · `/budgets`, `/budgets/{id}/vs-actual` · BudgetService · `/budgets`, `/budgets/:id` · AccountingExtensionsIT.budgets (asserts actual, variance, favourable) | — |
| 35 | Fixed asset registration, depreciation, and disposal. | `present-verified` | fixedasset · `/fixed-assets/*`, `/depreciation/run`, `/disposal` · AssetService · `/fixed-assets*` · FixedAssetIT.depreciation/register, DepreciationCalculatorTest | — |
| 36 | Tax configuration and tax reporting according to applicable requirements. | `present-partial (built, unverified)` | `/taxes`, `/reports/tax/summary` · TaxCalculationService · `/taxes`, `/tax-report` · AccountingExtensionsIT.taxAndInvoiceDelivery, TaxCalculationServiceTest. `/taxes` CRUD is not called by any test; no jurisdiction return pack. Phase 4: `/taxes` CRUD test TaxesIT; generic return pack `GET /reports/tax/pack` on the Tax Report screen with CSV download, TaxPackIT | Unverified: nothing compiled or run. No country form; boxes are generic |
| 37 | Financial period management and period-end closing. | `present-verified` | accounting · `/fiscal-periods/{id}/{close,reopen,lock}` · PeriodCloseService · `/fiscal-periods`, `/year-end-close` · AccountingIT.periodClose | Close checklist built in Phase 4 (`/fiscal-periods/:id/checklist`, V72, PeriodCloseChecklistIT), unverified: nothing compiled or run |
| 38 | Profit and loss statements, balance sheets, and cash flow reports. | `present-partial (built, unverified)` | `/reports/accounting/{profit-and-loss,balance-sheet,cash-flow}` · screens `/profit-and-loss`, `/balance-sheet`, `/cash-flow/report`. ReportingIT.statements asserts `balanced` on the balance sheet, but only `isObject()` on P&L and cash flow. Phase 4: FinancialStatementsIT asserts P&L and cash flow figures on dated journals | Unverified: the test was not run |
| 39 | Cost centre, branch, department, and project-level financial reporting. | `present-verified` | accounting · `/reports/accounting/consolidated`, `/cost-centers/reports/matrix` (cost centre categories department, branch, project) · `/consolidated-report`, `/cost-center-accounting` · AccountingExtensionsIT.consolidated, CostCenterAccountingIT | — |
| 40 | Configurable approval workflows and audit trails. | `present-partial (built, unverified)` | kernel/approvals · `/approvals`, `/approval-workflows`, `/approval-policies`, `/audit-logs` · ApprovalsService, AuditService · `/approvals`, `/approval-workflows`, `/approval-policies`, `/audit-log`. Engine tested (FleetWeighbridgeIT.exceptionThroughInbox, FleetRegistryIT.correctionApproved); audit in KernelIT. **No test calls the workflow / policy config routes** | CTRL-1 built: conditions, hierarchy, limits, config route tests in ApprovalControlsIT; nothing compiled or run |
| 41 | Integration between operational transactions and the general ledger. | `present-verified` | accounting PostingService used by every module · GL balance asserted in QuarryIT, CrushingIT, FleetCostingIT, ProcurementIT, InvoicingIT | — |

### 2.2 Procurement and Supplier Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 51 | Supplier registration and management. | `present-verified` | core · `/partners` · PartnerService · `/suppliers`, `/suppliers/:partnerId` · CoreIT.partners | — |
| 52 | Supplier quotations and price comparisons. | `present-partial (built, unverified)` | No RFQ or supplier quotation entity, route or screen (grep `quotation|rfq` in procurement: empty). Phase 4: RFQ with invited suppliers, recorded quotations, comparison in base currency and award to a PO: `/procurement/rfqs` (9 routes), screens `/procurement/rfqs`, `/new`, `/:id`, `/:id/edit`, migration V73, RfqIT | Unverified: nothing compiled or run. No supplier portal or RFQ e-mail |
| 53 | Purchase requisitions and approval workflows. | `present-partial` | `/procurement/purchase-requests` submit/approve/reject · `/procurement/purchase-requests` · ProcurementIT.purchaseRequests. Approval is a status change: PurchaseRequestsService does not call ApprovalsService | Route PR approval through the approvals engine (PROC-5) |
| 52 | Supplier quotations and price comparisons. | `missing` | No RFQ or supplier quotation entity, route or screen (grep `quotation|rfq` in procurement: empty) | Build RFQ and quote comparison (PROC-1) |
| 53 | Purchase requisitions and approval workflows. | `present-partial (built, unverified)` | `/procurement/purchase-requests` submit/approve/reject · `/procurement/purchase-requests` · ProcurementIT.purchaseRequests. Approval is a status change: PurchaseRequestsService does not call ApprovalsService | PROC-5 built: submit raises a `purchase_request` approval, approve, reject and the inbox go through the engine; `/procurement/purchase-requests/new` screen added. Nothing compiled or run |
| 54 | Purchase order creation and tracking. | `present-verified` | procurement · `/procurement/purchase-orders` · PurchaseOrdersService · `/procurement/purchase-orders*` · ProcurementIT.creditPurchase/purchaseOrderAdmin | — |
| 55 | Goods received notes and receipt verification. | `present-verified` | procurement · `/procurement/goods-receipts` · GoodsReceiptsService · `/procurement/goods-receipts*` · ProcurementIT.goodsReceipts/receiptApprovalGate | — |
| 56 | Supplier invoice matching against purchase orders and goods receipts. | `present-verified` | procurement · `/procurement/three-way-match` · kernel/threewaymatch · `/procurement/three-way-match` · ProcurementIT.threeWayMatch/billMatching | Bill entry screen tracked in FIN-2 |
| 57 | Supplier payment scheduling and outstanding balance monitoring. | `present-partial (built, unverified)` | Outstanding balance: `/reports/ap/aging`, `/vendor-bills/suppliers/*` ledger · `/suppliers/:id/ledger` · InvoicingIT.vendorBills. **No payment scheduling** route or screen. Phase 4: `GET /vendor-bills/payment-schedule`, `PUT /vendor-bills/{id}/payment-schedule`, screen `/payment-schedule`, migration V70, SupplierPaymentScheduleIT | Unverified: nothing compiled or run |
| 58 | Purchase returns and supplier credit notes. | `present-verified` | procurement · `/procurement/debit-notes` · DebitNotesService · `/procurement/debit-notes*` · ProcurementIT.debitNotes/returnToVendor | — |
| 59 | Supplier pricing agreements and historical price analysis. | `present-partial (built, unverified)` | No price agreement or price history entity, route or screen. Phase 4: `/procurement/price-agreements` (create, end, cancel, lookup by quantity), `/procurement/reports/price-history`, screens `/procurement/price-agreements`, `/procurement/price-history`, migration V74, PriceAgreementIT | Unverified: nothing compiled or run. The PO form does not prefill the agreed price yet |
| 60 | Procurement budgets and spending controls. | `missing` | `/budgets` is GL-level; procurement never reads a budget (grep `budget` in procurement: empty) | Budget check on PR/PO (PROC-3) |
| 61 | Purchase approval limits based on amount, department, or organizational structure. | `present-partial` | Amount bands work: `purchase_order` workflow steps use `minAmount`/`maxAmount` (ApprovalsService). Department and organisational conditions do not exist (ApprovalAdminService has no such field) | Add department/branch conditions (CTRL-1, PROC-3) |
| 62 | Supplier performance and purchasing reports. | `present-partial (built, unverified)` | Purchases tab in `/reports` (`usePurchasesReport`), quality report `groupBy=supplier` (QualityNcrTraceIT.reportsByLine). No on-time, price-variance or supplier scorecard; purchases tab has no test found. Phase 4: `GET /procurement/reports/supplier-performance`, screen `/procurement/supplier-performance`, SupplierPerformanceIT.scorecard, SupplierPerformanceServiceTest | Unverified: nothing compiled or run |
| 59 | Supplier pricing agreements and historical price analysis. | `missing` | No price agreement or price history entity, route or screen | Build agreements + price history (PROC-2) |
| 60 | Procurement budgets and spending controls. | `present-partial (built, unverified)` | `/budgets` is GL-level; procurement never reads a budget (grep `budget` in procurement: empty) | PROC-3 built: `/procurement/budgets`, checked on PR submit and PO create; nothing compiled or run |
| 61 | Purchase approval limits based on amount, department, or organizational structure. | `present-partial (built, unverified)` | Amount bands work: `purchase_order` workflow steps use `minAmount`/`maxAmount` (ApprovalsService). Department and organisational conditions do not exist (ApprovalAdminService has no such field) | CTRL-1 and PROC-3 built: branch and department conditions, `/approval-controls/limits`, department on PR and PO; nothing compiled or run |
| 62 | Supplier performance and purchasing reports. | `present-partial` | Purchases tab in `/reports` (`usePurchasesReport`), quality report `groupBy=supplier` (QualityNcrTraceIT.reportsByLine). No on-time, price-variance or supplier scorecard; purchases tab has no test found | Supplier performance report (PROC-4) |

### 2.3 Inventory and Warehouse Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 72 | Multiple warehouses, stockyards, branches, and storage locations. | `present-partial (built, unverified)` | inventory · `/inventory/locations` with `branchId` (V62, BR-1) · LocationService · `/inventory/locations` · InventoryIT.locations, UserDataScopeIT (not run) | Verify by running; nothing was compiled |
| 73 | Product categories, attributes, specifications, and grades. | `present-partial` | `/product-categories` (no test calls it), ProductVariant attributes, grades only on QuarryMaterial and quality specifications; precast `product-specs` (PrecastManufacturingIT.setupRules). No generic grade/specification on Product | Product attributes and grades; needs test (INV-2) |
| 72 | Multiple warehouses, stockyards, branches, and storage locations. | `present-verified` | inventory · `/inventory/locations` · LocationService · `/inventory/locations` · InventoryIT.locations | Location has no Branch link: see section 6 (BR-1) |
| 73 | Product categories, attributes, specifications, and grades. | `present-partial (built, unverified)` | Built: `/product-grades`, `/product-attributes`, `/product-specifications` (V96, ProductSpecService), screen `/products/specifications`; ProductSpecIT written; `/product-categories` covered by CrmCustomerDataIT.productCategories. Variants, QuarryMaterial grades and precast `product-specs` unchanged. Nothing compiled or run | Product attributes and grades; needs test (INV-2) |
| 74 | Stock receipts, issues, transfers, and adjustments. | `present-verified` | inventory · `/inventory/direct-stock`, `/stock-outs`, `/transfers`, `/adjustments` · StockDocService · `/inventory/adjustments`, `/inventory/transfers` · InventoryIT.documents/transit | — |
| 75 | Batch, lot, and serial number tracking where applicable. | `present-verified` | inventory · batches/serials on stock moves · `/inventory/items/:productId` · InventoryIT.batches/serials | — |
| 76 | Minimum and maximum stock levels. | `present-verified` | Product.minQuantity/maxQuantity · `/inventory/product-stock-levels` · `/inventory/items` · InventoryIT.reports | — |
| 77 | Reorder points and replenishment alerts. | `present-partial (built, unverified)` | `/inventory/reports/reorder` (InventoryIT.reports), `/manufacturing/mrp/scan-min-stock` (ManufacturingIT). Built: `/inventory/replenishment` rules and alerts, hourly scan, notification, requisition (V97, ReplenishmentService, ReplenishmentScanJob, ProcurementReplenishmentService); ReplenishmentIT and ReplenishmentCalculatorTest written; screen `/inventory/replenishment`. Nothing compiled or run | Replenishment alerts (INV-3, Phase 5 alerts) |
| 78 | Stock reservations and availability checking. | `present-partial (built, unverified)` | `/inventory/reservations`, `/atp` · StockReservationService · InventoryIT.reservations. Screen `/inventory/reservations` and `GET /inventory/reservations/overview` built; StockReservationOverviewIT written. Not compiled or run | Reservation screen (INV-1) |
| 79 | Physical stocktaking and inventory reconciliation. | `present-verified` | inventory · `/inventory/counts` · InventoryCountService · `/inventory/count` · InventoryIT.counts | — |
| 80 | Damaged, rejected, and quarantined stock management. | `present-verified` | inventory · `/inventory/waste` · quality quarantine `QC-QUARANTINE` · `/inventory/waste`, `/quality/lots` · InventoryIT.documents, QualityLotsIT.incomingFailRejectDowngrade | — |
| 81 | Inventory valuation using supported costing methods. | `present-verified` | inventory · `/inventory/reports/valuation`, `/inventory/cost-policies` · `/inventory-valuation`, `/inventory/cost-policies` · InventoryIT.avco, InventoryCostPolicyIT, ReportingIT.inventory | — |
| 82 | Stock movement history and audit trails. | `present-verified` | inventory · `/inventory/ledger` · LedgerDetailService · `/inventory/ledger*` · InventoryIT.avco | — |
| 83 | Inventory aging and slow-moving stock reports. | `present-verified` | inventory · `/inventory/reports/stock-health` (aging, slow/dead) · InventoryReportsService · `/inventory/reports` · InventoryIT.reports | — |
| 84 | Unit-of-measure configuration and conversions. | `present-verified` | core · `/uoms`, `/uom-categories`, ProductUom · UomConversionService · `/uom` · CoreIT.uoms, ProductUnitsIT, UomConversionServiceTest | — |
| 85 | Inventory availability across branches and warehouses. | `present-partial (built, unverified)` | inventory · `/inventory/items?locationId`, `/product-stock-levels`, `/reservations/atp` · `/inventory/items` · InventoryIT.reports/reservations. BR-1 links locations to branches; a branch-grouped stock figure comes with BR-2 | Verify by running; nothing was compiled |

### 2.4 Sales, Invoicing, and Customer Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 95 | Customer registration and account management. | `present-verified` | core, crm · `/partners`, `/crm/customers` · `/customers`, `/crm/customers` · CoreIT.partners, CrmSuiteIT | — |
| 96 | Customer categories, credit limits, and payment terms. | `present-partial (built, unverified)` | SALES-1: `CreditControlService.assertCanSell` ends in `PaymentTermService.assertCreditOk`, wired into `/orders` save, `/invoices/:id/post`, manual delivery orders and dispatch (hold); limit and hold set at `/credit-control/customers/:id/policy` · `/credit-control` · CreditControlIT (not run). POS unchanged | Verify by running; nothing was compiled |
| 97 | Sales quotations and quotation revisions. | `present-verified` | crm · `/crm/quotations` incl. revise/convert · CrmQuotationService · `/crm/quotations*` · CrmSuiteIT.quotations | — |
| 98 | Sales orders and order approval workflows. | `present-partial (built, unverified)` | `/orders` · `/orders*` · OrdersIT. **No approval gate**: orders module has no ApprovalsService call | SALES-2 built: `sales_order` workflow gates `/orders/{id}/invoice`, higher totals need a new approval. POS billing routes are not gated. Nothing compiled or run |
| 99 | Configurable price lists and customer-specific pricing. | `present-partial` | CrmPriceRule · `/crm/pricing/rules`, `/resolve` · CrmPricingService · `/crm/pricing` · CrmSuiteIT.pricing. Resolver used only by CrmQuotationService, not by orders or invoices; no named price list | Price lists applied to orders and invoices (SALES-3) |
| 100 | Wholesale, retail, contract, and negotiated pricing. | `present-partial` | Rules by partner, partner category, product, min quantity only. No wholesale/retail/contract tiers, no per-site or per-UoM price | Tiers on price lists (SALES-3) |
| 98 | Sales orders and order approval workflows. | `present-partial` | `/orders` · `/orders*` · OrdersIT. **No approval gate**: orders module has no ApprovalsService call | Order approval (SALES-2) |
| 99 | Configurable price lists and customer-specific pricing. | `present-partial (built, unverified)` | SALES-3: `/price-lists` named lists in `core` (PriceListService), applied to `/orders`, `/invoices`, manual delivery orders and quotations when a line has no price · `/price-lists` · PriceListIT (not run). CrmPriceRule stays quotation-level | Verify by running; nothing was compiled |
| 100 | Wholesale, retail, contract, and negotiated pricing. | `present-partial (built, unverified)` | SALES-3: tiers retail, wholesale, contract, negotiated; customer, category, branch, site, zone, unit and quantity breaks on a price list · `/price-lists` · PriceListIT (not run) | Verify by running; nothing was compiled |
| 101 | Sales invoicing and credit note management. | `present-verified` | invoicing · `/invoices`, `/credit-notes` · InvoiceService, CreditNoteService · `/invoices*`, `/credit-notes*` · InvoicingIT.invoices/creditNotes | — |
| 102 | Cash, bank, mobile money, and other supported payment methods. | `present-verified` | accounting · `/accounts/pos-payment-methods` (cash, mobile_money, card, bank) · PosPaymentMethodService · `/settings/payment-methods`, `/payments` · TreasurySpecsIT, AccountingSpecsTest | Recorded as a tender only; provider link is row 8.3 (INT-1) |
| 103 | Customer receipts and allocation of payments to invoices. | `present-verified` | invoicing · `/payments` allocations · PaymentService · `/payments*` · InvoicingIT.receipts (allocation, residual, over-allocation, replay) | — |
| 104 | Outstanding debt and credit exposure monitoring. | `present-partial (built, unverified)` | SALES-1: `/credit-control/customers` and CrmQuotationService.credit count open receivables (documents and POS invoices), unbilled orders and manual deliveries not yet invoiced · `/credit-control` · CreditControlIT (not run) | Verify by running; nothing was compiled |
| 105 | Sales returns and refunds. | `present-verified` | pos, invoicing · `/returns`, `/credit-notes` · sales returns · `/returns*` · SalesReturnsIT.returnLifecycle, FleetDispatchIT.returnsPutStockBack | — |
| 106 | Salesperson commissions where required. | `present-partial (built, unverified)` | SALES-4: `/commissions` rules, calculation from posted invoices, approval, export to payroll inputs · `/commissions` · CrmCommissionTest, CrmCommissionIT (not run) | Verify by running; nothing was compiled |
| 107 | Customer statements and account reconciliation. | `present-verified` | invoicing · `/reports/ar/customers/{id}/statement(.pdf)`, `/reports/accounting/tieout` · StatementService · `/statements`, `/tieout` · AccountingExtensionsIT.openingBalances, ReportingIT | — |
| 108 | Sales analysis by product, branch, customer, and period. | `present-partial (built, unverified)` | SALES-5: `/reports/ar/sales-analysis` by product, category, branch, customer, salesperson, month with date, branch, product, customer, department filters · `/reports/sales-analysis` · SalesAnalysisIT (not run) | Verify by running; nothing was compiled |

### 2.5 Human Resources and Payroll

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 118 | Employee records and organizational structures. | `present-verified` | hr · `/hr/employees`, `/departments`, `/positions` · `/hr/employees*`, `/hr/departments`, `/hr/positions` · HrIT.orgMasters/identityAndLifecycle | — |
| 119 | Recruitment and onboarding. | `present-partial (built, unverified)` | Built: `/hr/recruitment/*`, `/hr/onboarding/*` (V90, HrRecruitmentService, HrRecruitmentController), HrRecruitmentIT written, screens `/hr/recruitment`, `/hr/onboarding`. Nothing compiled or run | Build recruitment and onboarding (HR-1) |
| 120 | Contracts, job roles, and departmental assignments. | `present-partial (built, unverified)` | Positions, departments, transfers verified (HrIT). Contract records built: `/hr/contracts` (V91, HrContractService, HrContractIT written, screen `/hr/contracts`); `HrEmployee.contractEndDate` follows the active contract. Nothing compiled or run | Contract records (HR-2) |
| 121 | Attendance, shifts, and working schedules. | `present-verified` | hr · `/hr/attendance*`, `/hr/shifts`, `/hr/assignments` · `/hr/attendance`, `/hr/shifts` · HrIT.attendance | — |
| 122 | Leave and absence management. | `present-verified` | hr · `/hr/leave/*` · `/hr/leave` · HrIT.leave | — |
| 123 | Payroll processing and payslip generation. | `present-verified` | hr · `/hr/payroll/runs`, `/hr/payslips` · HrPayrollService · `/hr/payroll*`, `/hr/payslips` · HrIT.payroll | — |
| 124 | Configurable allowances, deductions, and overtime. | `present-partial (built, unverified)` | Components configurable (`/hr/payroll/components`, HrIT.payroll). Overtime rules built: `/hr/payroll/overtime-rules` (V92, HrOvertimeService, HrOvertimeCalculator, HrPayrollService hook, HrOvertimeCalculatorTest and HrPayrollExtensionsIT written, tab in `/hr/payroll/settings`); no rule keeps the 1.5 multiplier. Nothing compiled or run | Configurable overtime (HR-3) |
| 125 | Applicable statutory deductions and payroll reporting. | `present-verified` | hr · `/hr/payroll/tax-tables`, pension, `/hr/reports/payroll-register` · `/hr/payroll/settings`, `/hr/reports` · HrIT.payroll, HrRulesTest | — |
| 126 | Employee loans and salary advances. | `present-verified` | hr · `/hr/loans`, `/hr/advances` · `/hr/advances-loans` · HrIT.payroll | — |
| 127 | Performance management and training records. | `present-partial (built, unverified)` | Training verified (`/hr/training`, HrIT.documentsAndTraining). `/hr/reviews` tested in HrIT; screen `/hr/reviews` built on `useHrReviews`. Screen not run | Performance review screen (HR-4) |
| 128 | Employee cost allocation by department, branch, or cost centre. | `present-partial (built, unverified)` | Built: `/hr/payroll/cost-allocation-rules`, `/hr/payroll/runs/{id}/cost-allocation`, `/hr/payroll/cost-report` (V93, HrPayrollCostService, HrPayrollCostAllocator); approved runs debit salaries by cost centre and branch when rules exist. HrPayrollCostAllocatorTest and HrPayrollExtensionsIT written; tab in `/hr/payroll/settings`. Nothing compiled or run | Allocate payroll by department and cost centre (HR-5) |
| 129 | Payroll approval and audit history. | `present-verified` | hr · `/hr/payroll/runs/{id}/approve`, `/reverse`, audit via `HrPayrollRun` entries · `/hr/payroll/runs/:id` · HrIT.payroll | — |

### 2.6 Customer Relationship Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 139 | Customer and contact databases. | `present-partial (built, unverified)` | `/crm/customers` tested (CrmSuiteIT). `/contacts` covered by CrmCustomerDataIT.contacts, written, not run | Needs test (CRM-2) |
| 140 | Sales leads and opportunity tracking. | `present-verified` | crm · `/crm/leads`, `/crm/opportunities` · CrmPipelineService · `/crm/leads`, `/crm/pipeline` · CrmSuiteIT.leadLifecycle/outcomes | — |
| 141 | Follow-up activities and reminders. | `present-partial (built, unverified)` | `/crm/activities`, `/crm/planner` · CrmIT.activities, CrmSuiteIT.plannedActivities. CrmReminderJob covered by CrmCustomerDataIT.reminders, written, not run | Needs test for reminders (CRM-2) |
| 142 | Customer communication history. | `present-verified` | crm, communication · `/crm/opportunities/{id}/timeline`, `/communication/conversations` · `/crm/opportunities/:id`, `/communication` · CrmSuiteIT, CommunicationIT | — |
| 143 | Quotation follow-up and conversion tracking. | `present-verified` | crm · `/crm/quotations/{id}/convert`, `/crm/reports` (leadConversion, conversion) · `/crm/quotations`, `/crm/reports` · CrmSuiteIT.quotations/reports | — |
| 144 | Customer segmentation. | `present-partial (built, unverified)` | Segments are saved filters: scope `customers` (V95) over `/crm/customers` category, credit hold and search, shared with the organisation; `/crm/customers` screen has the category filter and Save segment. CrmCustomerDataIT.segments and savedFilters written. Nothing compiled or run | Needs test; decide on segments (CRM-2) |
| 145 | Sales pipeline monitoring. | `present-verified` | crm · `/crm/opportunities/kanban`, `/crm/analytics/pipeline`, `/crm/dashboard` · `/crm/pipeline`, `/crm` · CrmIT.analytics, CrmSuiteIT.configurableStages | — |
| 146 | Customer complaints and service requests. | `present-partial (built, unverified)` | Built: `/crm/cases` (V94, CrmCaseService, CrmCaseRules, CrmCaseDiscrepancySubscriber), `from-discrepancy` ties a case to a fleet delivery discrepancy. CrmCaseIT and CrmCaseRulesTest written; screens `/crm/cases`, `/crm/cases/:id`, "Open case" on delivery discrepancies. Nothing compiled or run | Build complaints, tie to delivery discrepancies (CRM-1) |
| 147 | Customer purchasing history and sales analysis. | `present-verified` | crm · `/crm/analytics/partner/{id}`, `/crm/customers/{id}` · `/crm/customers/:id` · CrmIT.analytics | — |

### 2.7 Reporting, Analytics, and Business Intelligence

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 157 | Financial statements and management accounts. | `present-partial (built, unverified)` | Same reports as 2.1 financial statements; P&L and cash flow assertions are smoke only. Phase 4: FinancialStatementsIT value assertions | Unverified: the test was not run |
| 158 | Sales, purchases, and inventory reports. | `present-partial (built, unverified)` | Sales and inventory reports tested (InvoicingIT.arReports, InventoryIT.reports). Purchases tab in `/reports` has no test found. Phase 4: purchases data test SupplierPerformanceIT.purchasesReport | Unverified: nothing compiled or run |
| 159 | Profitability analysis. | `present-partial` | Delivery profitability (FleetCostingIT.profitability), P&L. No gross margin by product or customer | Margin report, depends on 2.8 (MFG-2) |
| 160 | Outstanding customer and supplier balances. | `present-partial (built, unverified)` | AR verified (`/ar-aging`). `/reports/ap/aging` tested but **no screen uses it**; supplier ledger is per supplier. Phase 4: `/ap-aging` screen on `/reports/ap/aging`, VendorBillListIT.agingBucket | Unverified: nothing compiled or run |
| 157 | Financial statements and management accounts. | `present-partial` | Same reports as 2.1 financial statements; P&L and cash flow assertions are smoke only | Needs test (FIN-3) |
| 158 | Sales, purchases, and inventory reports. | `present-partial` | Sales and inventory reports tested (InvoicingIT.arReports, InventoryIT.reports). Purchases tab in `/reports` has no test found | Needs test (PROC-4) |
| 159 | Profitability analysis. | `present-partial (built, unverified)` | `/manufacturing/costing/reports/margin` by product or customer, delivery profitability, P&L · `/manufacturing/costing` | Built by MFG-2, not compiled or run |
| 160 | Outstanding customer and supplier balances. | `present-partial` | AR verified (`/ar-aging`). `/reports/ap/aging` tested but **no screen uses it**; supplier ledger is per supplier | AP aging screen (FIN-2) |
| 161 | Stock valuation and stock movement reports. | `present-verified` | inventory · `/inventory/reports/valuation`, `/movements` · `/inventory-valuation`, `/inventory/reports` · InventoryIT.reports, ReportingIT.inventory | — |
| 162 | Branch and departmental performance reports. | `present-partial (built, unverified)` | BR-2: `/reports/branches/comparison` adds operational rows to Branch P&L and TB · `/reports/branches` · BranchPerformanceIT (not run). Department performance stays cost-centre based | Verify by running; nothing was compiled |
| 163 | Budget versus actual analysis. | `present-verified` | accounting · `/budgets/{id}/vs-actual` · `/budgets/:id` · AccountingExtensionsIT.budgets | — |
| 164 | Configurable dashboards and key performance indicators. | `present-partial (built, unverified)` | `/analytics` overview and ten dashboards from every KPI of §10, with filters, drill-down and a permission each. Which tiles appear is fixed per dashboard; only the filters are configurable. Nothing compiled or run | Verify by running. Editable layouts are not built |
| 165 | Filtering by date, branch, warehouse, product, customer, and department. | `present-partial (built, unverified)` | BR-2 and SALES-5: `ReportFilterBar` (date, branch, warehouse, department) on the new branch and sales analysis screens; older report tabs keep their own filters · `/reports/branches`, `/reports/sales-analysis` | Verify by running; nothing was compiled |
| 166 | Exportable reports, subject to the supported formats. | `present-verified` | `/reports/export/*` CSV and PDF, `/fleet/reports/{report}/export` · exportCSV, exportPDF in report pages · ReportingIT.exportsAndDashboard, FleetCostingIT.exports | — |
| 167 | Role-based access to reports and sensitive business information. | `present-verified` | kernel/reports · ReportAccessController · `RequireReport`, `/settings/report-permissions` · ReportAccessServiceTest | — |

### 3.1 Quarry and Raw Material Extraction Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 181 | Quarry and extraction-site registration. | `present-verified` | quarry · `/quarry/sites` · QuarrySiteService · `/quarry/sites*` · QuarryIT.masterData | — |
| 182 | Management of multiple quarries and extraction zones. | `present-verified` | quarry · `/quarry/sites`, `/zones` · `/quarry/sites/:id` · QuarryIT.masterData | — |
| 183 | Extraction plans and production targets. | `present-verified` | quarry · `/quarry/extraction-plans` · QuarryPlanService · `/quarry/plans` · QuarryIT.extractionIntoRomStock | — |
| 184 | Recording daily extraction volumes. | `present-verified` | quarry · `/quarry/extraction-logs` · QuarryExtractionService · `/quarry/extraction*` · QuarryIT.extractionIntoRomStock | — |
| 185 | Tracking raw stone by source, type, grade, or location. | `present-verified` | quarry · `/quarry/materials` (rockType, grade), `/stockpiles`, `/rom-stock` · `/quarry/materials`, `/quarry/stockpiles` · QuarryIT.masterData/extractionIntoRomStock | — |
| 186 | Recording excavator and loader usage. | `present-verified` | quarry · extraction resource `kind=equipment`, `vehicleId`, `/quarry/equipment-rates` · ExtractionEntryPage · QuarryIT.extractionIntoRomStock | — |
| 187 | Tracking extraction activities by shift and operator. | `present-verified` | quarry · ExtractionLogInput shift + resource `employeeId`, list filter `shift` · `/quarry/extraction` · QuarryIT.extractionIntoRomStock | — |
| 188 | Monitoring material transferred from extraction areas to crushing plants. | `present-partial (built, unverified)` | `/quarry/haulage-trips` → extraction log → `/manufacturing/crushing/runs` · Phase2ExitIT.quarryToSizedStockpiles | Test written by MFG-3, not run |
| 189 | Recording extraction-related fuel, labour, and equipment costs. | `present-partial (built, unverified)` | Actual clock hours, meter energy and fleet cost per hour via `/manufacturing/costing/rates` and `/orders/{id}/absorb` · ManufacturingCostingIT. Payroll actuals not linked (HR-5) | Built by MFG-2, not compiled or run |
| 190 | Monitoring extraction performance against planned targets. | `present-verified` | quarry · `/quarry/reports/extraction-vs-target` · TargetVariance · `/quarry/reports` · QuarryIT, QuarryCalculatorsTest | — |
| 191 | Recording material losses and unusable rock. | `present-verified` | quarry · `/quarry/losses`, log loss lines · `/quarry/losses` · QuarryIT.extractionIntoRomStock (overburden, lossCost) | — |
| 192 | Reporting extraction volumes and costs by site and period. | `present-verified` | quarry · `/quarry/reports/cost-per-tonne`, `/losses` · QuarryReportService · `/quarry/reports` · QuarryIT.extractionIntoRomStock | — |

### 3.2 Aggregate Crushing, Screening, and Processing

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 202 | Production orders for different aggregate products. | `present-verified` | manufacturing · `/manufacturing/crushing/runs` (each posted run books an `MO-` order) · CrushingRunService · `/manufacturing/crushing*` · CrushingIT.runLifecycle | — |
| 203 | Configurable crushing and screening processes. | `present-verified` | manufacturing · `/manufacturing/crushing/plants` stages and units · CrushingPlantService · `/manufacturing/crushing/plants*` · CrushingIT.plantSetup | — |
| 204 | Definition of raw materials and expected output products. | `present-verified` | manufacturing · `/manufacturing/crushing/grading-profiles` · CrushingProfileService · `/manufacturing/crushing/profiles/*` · CrushingIT.profileRules | — |
| 205 | Production quantities by aggregate grade and size. | `present-verified` | manufacturing · run outputs by fraction · `/manufacturing/crushing/runs/:id` · CrushingIT.runLifecycle, CrushingCalculatorTest | — |
| 206 | Recording input quantities and actual output. | `present-verified` | manufacturing · run feed and actual outputs · CrushingRunEntryPage · CrushingIT.runLifecycle/runValidation | — |
| 207 | Tracking production losses, waste, and rejected material. | `present-verified` | manufacturing · `lossTonnes`, `abnormalLossValue`, `reports/yield` · `/manufacturing/crushing/reports` · CrushingIT.runLifecycle | — |
| 208 | Recording fuel, electricity, labour, and maintenance consumption. | `present-verified` | manufacturing · run meters (diesel, kWh), crew hours, cost lines · CrushingRunService · CrushingRunEntryPage · CrushingIT.runLifecycle (conversionCost 620) | — |
| 209 | Monitoring production targets and actual output. | `present-partial (built, unverified)` | `/manufacturing/planning/targets`, `/reports/production-vs-target` · PlanningIT.shiftPlansAndTargets | Built by MFG-1, not compiled or run |
| 210 | Recording transfers from production plants to finished-goods stockyards. | `present-verified` | manufacturing · outputs posted to stockpile locations · `/manufacturing/crushing/stockpiles` · CrushingIT.runLifecycle (onHand per pile) | — |
| 211 | Tracking production costs by batch, shift, plant, or product. | `present-partial (built, unverified)` | `/manufacturing/costing/reports/summary?groupBy=shift|plant|product|month` · `/manufacturing/costing` · ManufacturingCostingIT.plainOrder | Built by MFG-2, not compiled or run |
| 212 | Comparing expected yield against actual yield. | `present-verified` | manufacturing · `reports/yield`, `yieldVariancePct` · `/manufacturing/crushing/reports` · CrushingIT.runLifecycle, CrushingCalculatorTest | — |
| 213 | Generating production performance and efficiency reports. | `present-verified` | manufacturing · `reports/yield`, `reports/plant-hours`, `/shop-floor/oee` · `/manufacturing/crushing/reports`, `/manufacturing/oee` · CrushingIT, OeeCalculatorTest | — |

### 3.3 Concrete Block Manufacturing

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 225 | Product specifications for different block types and dimensions. | `present-verified` | manufacturing · `/manufacturing/precast/product-specs` · `/manufacturing/precast/setup` · PrecastManufacturingIT.setupRules | — |
| 226 | Bill of materials (BOM) configuration. | `present-verified` | manufacturing · `/manufacturing/boms` · BomService · `/manufacturing/boms*` · ManufacturingIT.bomRules, BomExplodeTest | — |
| 227 | Production recipes for cement, sand, aggregates, water, and additives. | `present-verified` | manufacturing · `/manufacturing/precast/mix-designs`, `/scale` · `/manufacturing/precast/mix-designs*` · PrecastManufacturingIT.endToEnd, BatchScalerTest | — |
| 228 | Production order creation and scheduling. | `present-verified` | manufacturing · `/manufacturing/precast/orders`, `/manufacturing/schedule` · `/manufacturing/precast/orders*`, `/manufacturing/planning` · PrecastManufacturingIT.endToEnd, ShopFloorIT.scheduling | Forward scheduling only (MFG-1) |
| 229 | Raw material availability checks. | `present-verified` | manufacturing · `/manufacturing/production-orders/{id}/availability` · CostingEngineeringIT.substitutesAndEco | — |
| 230 | Material issue and consumption tracking. | `present-verified` | manufacturing · `/manufacturing/production-orders/{id}/issue`, pressing runs · `/manufacturing/orders/:id` · PrecastManufacturingIT.endToEnd, MultiStageProductionIT.concurrentIssue | — |
| 231 | Recording planned and actual production quantities. | `present-verified` | manufacturing · pressing runs, order `costing` · `/manufacturing/precast/orders/:id` · PrecastManufacturingIT.endToEnd | — |
| 232 | Tracking rejected blocks, breakages, and production waste. | `present-verified` | manufacturing · `/precast/reports/breakage`, `rejectionRatePct` · `/manufacturing/precast/reports` · PrecastManufacturingIT.endToEnd | — |
| 233 | Recording curing or quality inspection stages where applicable. | `present-verified` | manufacturing · `/precast/curing-batches` cube, gate, readings · `/manufacturing/precast/curing*` · PrecastManufacturingIT.curingHoldAndDeferredGate | — |
| 234 | Finished-product registration and stock transfer. | `present-verified` | manufacturing · `/curing-batches/{id}/final-inspection`, `/manufacturing/production-orders/finished-goods` · PrecastManufacturingIT.endToEnd, ManufacturingIT | — |
| 235 | Production labour, energy, and machine cost allocation. | `present-partial (built, unverified)` | `/manufacturing/costing/rates`, `/orders/{id}/absorb` · CostAbsorptionCalculatorTest, ManufacturingCostingIT | Built by MFG-2, not compiled or run |
| 236 | Manufacturing cost calculation per block or production batch. | `present-verified` | manufacturing · `/precast/reports/cost-per-block` · `/manufacturing/precast/reports` · PrecastManufacturingIT.endToEnd (costPerAcceptableBlock) | — |
| 237 | Production variance analysis. | `present-partial (built, unverified)` | `/manufacturing/costing/orders/{id}/variances`, `/variances` · VarianceCalculatorTest, ManufacturingCostingIT.plainOrder | Built by MFG-2, not compiled or run |
| 238 | Quality inspection and batch traceability. | `present-verified` | quality, manufacturing · `/quality/traceability`, `/manufacturing/reports/genealogy` · `/quality/traceability` · QualityNcrTraceIT.traceability | — |

### 3.4 Production Planning and Scheduling

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 248 | Production plans based on expected demand and available stock. | `present-partial (built, unverified)` | `/manufacturing/planning/demand`, `/plans/from-demand`, `/pegs` · `/manufacturing/planning-board` · PlanningIT.planFromDemand | Built by MFG-1, not compiled or run |
| 249 | Production orders and work schedules. | `present-partial (built, unverified)` | `/manufacturing/planning/orders/{id}/schedule-backward`, `/gantt`, `/shift-plans` · `/manufacturing/gantt`, `/manufacturing/shift-plans` · PlanningIT.backwardWithCuring | Built by MFG-1, not compiled or run |
| 250 | Planned production quantities and target dates. | `present-verified` | manufacturing · `/manufacturing/plans` · `/manufacturing/planning` · ManufacturingIT.requestsPlansMrp | — |
| 251 | Availability checks for raw materials and production inputs. | `present-verified` | manufacturing · `/production-orders/{id}/availability`, MRP · `/manufacturing/mrp` · CostingEngineeringIT.substitutesAndEco, ManufacturingIT.requestsPlansMrp | — |
| 252 | Capacity planning for production lines and equipment. | `present-partial (built, unverified)` | `/manufacturing/planning/capacity` (hours, t/h, bottleneck) · `/manufacturing/capacity` · PlanningIT.capacity | Built by MFG-1, not compiled or run |
| 253 | Work-in-progress monitoring. | `present-verified` | manufacturing · `/reports/stage-wip`, `/reports/wip-reconciliation` · `/manufacturing/stage-wip` · MultiStageProductionIT, ManufacturingIT | — |
| 254 | Production completion and stock receipt. | `present-verified` | manufacturing · `/production-orders/{id}/complete` · `/manufacturing/orders/:id` · ManufacturingIT.bake | — |
| 255 | Shortfall and production variance reporting. | `present-partial (built, unverified)` | `/manufacturing/planning/reports/shortfall`, `/reports/production-vs-plan` · `/manufacturing/plan-performance` · PlanningIT.reports | Built by MFG-1, not compiled or run |
| 256 | Production priorities based on confirmed customer orders. | `present-partial (built, unverified)` | Demand rank and order priority, `/manufacturing/planning/orders`, `/schedule-open-orders` · PlanningIT.priorities, DemandNettingTest | Built by MFG-1, not compiled or run |
| 257 | Production performance analysis. | `present-partial (built, unverified)` | `/manufacturing/planning/reports/plan-adherence` · `/manufacturing/plan-performance` · PlanningIT.reports | Built by MFG-1, not compiled or run |

### 3.5 Quality Control and Product Specifications

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 267 | Product specifications and quality standards. | `present-verified` | quality · `/quality/specifications` · `/quality/specifications*` · QualityLotsIT.specificationVersions, SpecEvaluatorTest | — |
| 268 | Inspection records for incoming materials. | `present-verified` | quality · `/quality/inspection-lots` from GRN · QualityIncomingService · `/quality/lots*` · QualityLotsIT.incomingPassRelease | — |
| 269 | Quality checks during production. | `present-verified` | quality · `/quality/gate`, stage lots · QualityGateService · `/quality/lots` · QualityLotsIT.stageGateAndConcession, MultiStageProductionIT.stageGateWithQualityLot | — |
| 270 | Finished-product inspection and approval. | `present-verified` | quality · final lots, `/precast/curing-batches/{id}/final-inspection` · PrecastManufacturingIT.curingHoldAndDeferredGate | — |
| 271 | Rejected and quarantined stock. | `present-verified` | quality · hold, reject, `QC-QUARANTINE` · QualityDecisionService · `/quality/lots/:id` · QualityLotsIT.incomingFailRejectDowngrade/contendedReject | — |
| 272 | Batch identification and traceability. | `present-verified` | quality · `/quality/traceability` · `/quality/traceability` · QualityNcrTraceIT.traceability | — |
| 273 | Recording test results and inspection outcomes. | `present-verified` | quality · lot results, `/quality/lab-imports` · `/quality/lots/:id`, `/quality/lab` · QualityLotsIT.labCsvImport, SpecEvaluatorTest | — |
| 274 | Nonconformance records and corrective actions. | `present-verified` | quality · `/quality/ncrs`, `/quality/capas` · `/quality/ncrs*`, `/quality/capas*` · QualityNcrTraceIT.ncrCapaLifecycle | — |
| 275 | Quality reports by product, batch, production line, or supplier. | `present-verified` | quality · `/quality/reports/summary` groupBy product, batch, work_center, supplier · `/quality/reports` · QualityNcrTraceIT.reportsByLine | — |

### 4.1 Multiple Units of Measurement

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 287 | Tonnes and kilograms. | `present-verified` | core · UomSeed TON, KG · `/uom` · UomConversionServiceTest, FleetWeighbridgeIT | — |
| 288 | Cubic metres and other volume measurements. | `present-partial (built, unverified)` | UOM-1: M3 seeded (V60, UomSeed); density is the ProductUomConversion bridge set by `PUT /products/:id/unit-profile`; bridges connect whole categories and chain (UomConversionService.bridgeRate); read by invoices, orders, delivery lines, WeighbridgeUnits, FleetLoadFactors · product edit · ProductDensityIT (not run) | Verify by running; nothing was compiled |
| 289 | Pieces and individual blocks. | `present-verified` | core · UomSeed UNIT · ProductUnitsIT.perProductPackSizes | — |
| 290 | Bags and other packaging units. | `present-partial (built, unverified)` | core · ProductUom per-product pack sizes · product edit · ProductUnitsIT, UomConversionServiceTest; UOM-1 adds seeded BAG and `kgPerBag` · ProductDensityIT (not run) | Verify by running; nothing was compiled |
| 291 | Truckloads and delivery-specific quantities. | `present-partial (built, unverified)` | UOM-1: seeded TRUCK with per-product `tonnesPerTruckload` (ProductUom), so it is a sales, invoice and delivery line unit; `/orders` lines accept `uomId` and keep the typed unit in OrderLineEntryUnit · ProductDensityIT, OrdersUnitsIT (not run). Stock documents stay in the base unit (ADR-S003) | Verify by running; nothing was compiled |

### 4.2 Inventory Costing

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 301 | Weighted-average costing. | `present-verified` | inventory · AVCO · InventoryIT.avco, `/inventory/cost-policies` | — |
| 302 | FIFO costing. | `present-verified` | inventory · FIFO · InventoryCostPolicyIT.avcoToFifo, ProcurementIT.landedCostFifoLot | — |
| 303 | Standard costing for planning and variance analysis. | `present-verified` | inventory, manufacturing · `/inventory/standard-costs` · `/manufacturing/standard-costs` · StandardProcessCostingIT.standardReceiptsAndRevaluation | — |
| 304 | Production batch costing. | `present-verified` | manufacturing · `/manufacturing/process-costing`, crushing run cost · `/manufacturing/process-costing` · StandardProcessCostingIT.processCosting, CrushingIT.runLifecycle | — |
| 305 | Actual production cost allocation. | `present-partial (built, unverified)` | `/manufacturing/costing/orders/{id}` and `/absorb` · `/manufacturing/costing/orders/:id` · ManufacturingCostingIT | Built by MFG-2, not compiled or run |
| 306 | Landed cost allocation for purchased materials. | `present-verified` | procurement · `/procurement/landed-cost-vouchers` · LandedCostVoucherService · `/procurement/landed-cost-vouchers*` · LandedCostVoucherIT | — |
| 307 | Allocation of eligible production overheads. | `present-partial (built, unverified)` | `/manufacturing/costing/rates` per work centre, `/orders/{id}/absorb` · ManufacturingCostingIT | Built by MFG-2, not compiled or run |
| 308 | Work-in-progress and finished-goods valuation. | `present-verified` | manufacturing, inventory · `/reports/wip-reconciliation`, `/stage-wip`, valuation · `/manufacturing/stage-wip`, `/inventory-valuation` · MultiStageProductionIT, ManufacturingIT | — |
| 309 | Inventory adjustments and production variance reporting. | `present-partial (built, unverified)` | Inventory adjustments plus `/manufacturing/costing/variances` · VarianceCalculatorTest | Built by MFG-2, not compiled or run |

### 4.3 Landed Cost and Procurement Cost Allocation

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 319 | Freight and transport charges. | `present-verified` | procurement · CostComponentService FREIGHT · `/procurement/landed-cost-vouchers*` · LandedCostVoucherIT.laptopImport | — |
| 320 | Loading and unloading. | `present-partial (built, unverified)` | HANDLING component "Loading & handling" seeded with route and screen; no test uses it. Phase 4: LandedCostVoucherIT.handlingByQuantity covers the HANDLING component | Unverified: the test was not run |
| 321 | Handling fees. | `present-partial (built, unverified)` | Same HANDLING component; no test uses it. Phase 4: LandedCostVoucherIT.handlingByQuantity covers the HANDLING component | Unverified: the test was not run |
| 322 | Insurance. | `present-verified` | procurement · INSURANCE component · LandedCostVoucherIT.laptopImport | — |
| 323 | Customs charges and other eligible import costs. | `present-verified` | procurement · DUTY, CLEARING components · LandedCostVoucherIT.laptopImport | — |
| 324 | Other directly attributable acquisition expenses. | `present-verified` | procurement · `/procurement/cost-components` · LandedCostVoucherIT.chargeTreatments | — |

### 5 Fleet, Transport, and Delivery Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 336 | Fleet and vehicle registration. | `present-verified` | fleet · `/fleet/vehicles` · `/fleet/vehicles*` · FleetRegistryIT.vehicleCrud | — |
| 337 | Truck capacity and vehicle classification. | `present-verified` | fleet · payload, body volume, `/fleet/categories` · `/fleet/settings/classes` · FleetRegistryIT.categories | — |
| 338 | Driver and operator assignments. | `present-verified` | fleet · `/fleet/assignments`, `/fleet/drivers` · `/fleet/drivers*` · FleetDriversIT.assignmentHistory | — |
| 339 | Delivery planning and dispatch. | `present-verified` | fleet · `/fleet/delivery-orders`, `/fleet/dispatch-board`, `/fleet/trips` · `/fleet/dispatch`, `/fleet/trips` · FleetDispatchIT.salesOrderToInvoice | — |
| 340 | Linking deliveries to sales orders and invoices. | `present-verified` | fleet · `/fleet/delivery-orders/from-sales-order` · `/fleet/delivery-orders*` · FleetDispatchIT.salesOrderToInvoice, FleetPhase1EndToEndIT | — |
| 341 | Delivery notes and customer acknowledgements. | `present-verified` | fleet · `/fleet/trips/{id}/delivery-note`, proof of delivery, `/pod-photo` · PodCapturePage · FleetDispatchIT.salesOrderToInvoice (signature) | — |
| 342 | Recording dispatched and delivered quantities. | `present-verified` | fleet · trip lines, POD lines · `/fleet/trips/:id` · FleetDispatchIT.salesOrderToInvoice | — |
| 343 | Delivery scheduling by customer and construction site. | `present-verified` | fleet · `/fleet/customer-sites`, delivery orders · `/fleet/customer-sites`, `/fleet/dispatch` · FleetDispatchIT.sitesSettingsAndValidation | — |
| 344 | Fuel consumption and transport expense tracking. | `present-verified` | fleet · `/fleet/fuel-logs`, `/fuel-tanks` · `/fleet/fuel*` · FleetFuelIT, FleetCostingIT.vehicleCosts | — |
| 345 | Vehicle maintenance and service schedules. | `present-verified` | fleet · `/fleet/service-schedules`, `/work-orders` · `/fleet/maintenance*` · FleetMaintenanceIT.scheduleToWorkOrder | — |
| 346 | Vehicle operating cost analysis. | `present-verified` | fleet · `/fleet/reports/vehicle-costs` · `/fleet/reports` · FleetCostingIT.vehicleCosts | — |
| 347 | Driver allowances and trip expenses. | `present-verified` | fleet · `/fleet/allowance-rules`, `/fleet/claims` · `/fleet/claims*` · FleetClaimsIT | — |
| 348 | Delivery profitability analysis. | `present-verified` | fleet · `/fleet/reports/delivery-profitability` · `/fleet/reports` · FleetCostingIT.profitability | — |
| 349 | Recording delivery discrepancies and shortages. | `present-verified` | fleet · `/fleet/delivery-discrepancies` · `/fleet/delivery-discrepancies` · FleetDispatchIT, FleetWeighbridgeIT.discrepancyReport | — |

### 5.1 Weighbridge Integration

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 359 | Step 1: Register the truck, driver, customer, and relevant delivery order. | `present-verified` | fleet · `/fleet/weighbridge/tickets` · WeighbridgeTicketService · `/fleet/weighbridge/tickets/new` · FleetWeighbridgeIT.registrationRules, FleetWeighbridgeDispatchIT.prefillAndLinks | — |
| 360 | Step 2: Record the loaded vehicle's gross weight. | `present-verified` | fleet · gross capture · FleetWeighbridgeIT.outboundWithinTolerance, concurrentCapture | — |
| 361 | Step 3: Record the empty vehicle's tare weight. | `present-verified` | fleet · tare capture, stored tare · `/fleet/weighbridge/tares` · FleetWeighbridgeIT.storedTare | — |
| 362 | Step 4: Calculate the net material weight. | `present-verified` | fleet · net calculation · WeighbridgePoliciesTest, FleetWeighbridgeIT.outboundWithinTolerance | — |
| 363 | Step 5: Compare the net weight with the ordered and invoiced quantities. | `present-verified` | fleet · tolerance check, WeighbridgeUnits · WeighbridgePoliciesTest, FleetPhase1EndToEndIT.outOfToleranceNeedsASecondUser | — |
| 364 | Step 6: Generate a weighbridge ticket and associate it with the delivery. | `present-verified` | fleet · ticket PDF and delivery link · `/fleet/weighbridge/tickets/:id` · FleetWeighbridgeIT.immutabilityVoidReissue, FleetWeighbridgeDispatchIT.prefillAndLinks | — |
| 365 | Step 7: Record discrepancies and route exceptions for approval. | `present-verified` | fleet · `/fleet/weighbridge/exceptions` · `/fleet/weighbridge/exceptions` · FleetWeighbridgeIT.exceptionSeparationOfDuties/exceptionThroughInbox | — |
| 366 | Step 8: Update delivery records and relevant inventory transactions. | `present-verified` | fleet · trip feed into dispatch and stock · WeighbridgeTripFeed · FleetWeighbridgeDispatchIT.feedFailureAndRetry, FleetPhase1EndToEndIT.salesOrderToInvoiceThroughTheWeighbridge | — |

### 6 Branch, Warehouse, and Multi-Location Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 376 | Centralized management of organizational units. | `present-verified` | core · `/branches`, `/organizations` · `/settings/company` · CoreIT.branches | — |
| 377 | Branch-specific warehouses and stockyards. | `present-partial (built, unverified)` | BR-1: `InventoryLocation.branchId` (V62), set on the location form · `/inventory/locations` · UserDataScopeIT (not run) | Verify by running; nothing was compiled |
| 378 | Inter-branch inventory transfers. | `present-verified` | inventory · `/inventory/transfers` transit mode · StockDocService · `/inventory/transfers` · InventoryIT.transit | Between locations until BR-1 |
| 379 | Branch-specific sales and pricing policies. | `missing` | CrmPriceRule has no branch column; CrmPricingService has no branch logic | Branch pricing (SALES-3) |
| 380 | Centralized or delegated procurement. | `present-partial (built, unverified)` | PO carries `branchId` (PurchaseOrdersService). No delegated or central purchasing rules | PROC-3 built: `/procurement/delegations`, enforced on PO create; nothing compiled or run |
| 381 | Branch-level budgets and expense tracking. | `present-partial` | BudgetInput.branchId and expense posting tags exist; AccountingExtensionsIT.budgets only exercises cost centre scope | Needs test (BR-2) |
| 382 | Consolidated financial and operational reporting. | `present-partial` | Financial: `/reports/accounting/consolidated` (AccountingExtensionsIT.consolidated). Operational consolidation not built | Operational consolidation (BR-2) |
| 383 | User access restrictions by branch or location. | `missing` | **BranchScopeService has no callers**: nothing outside its own file references it, so no query is filtered by user branch | Enforce data-level branch scope (BR-1) |
| 379 | Branch-specific sales and pricing policies. | `present-partial (built, unverified)` | SALES-3: price list `branchId`; list applies by the document branch · `/price-lists` · PriceListIT (not run) | Verify by running; nothing was compiled |
| 380 | Centralized or delegated procurement. | `present-partial` | PO carries `branchId` (PurchaseOrdersService). No delegated or central purchasing rules | Delegation rules (PROC-3) |
| 381 | Branch-level budgets and expense tracking. | `present-partial (built, unverified)` | BudgetInput.branchId and expense posting tags; `AccountingExtensionsIT.budgetByBranch` added (not run) | Verify by running; nothing was compiled |
| 382 | Consolidated financial and operational reporting. | `present-partial (built, unverified)` | Financial `/reports/accounting/consolidated`; operational total on `/reports/branches/comparison` · BranchPerformanceIT (not run) | Verify by running; nothing was compiled |
| 383 | User access restrictions by branch or location. | `present-partial (built, unverified)` | BR-1: explicit grants in `UserDataScope`, screen on Staff → Data access; BranchScopeService.scope enforced in inventory reads, stock documents, counts, `/orders`, `/invoices`, delivery orders · UserDataScopeIT (not run). Not yet scoped: accounting reports, procurement, HR, CRM. POS and sync never consult it | Verify by running; nothing was compiled |
| 384 | Approval workflows for inter-branch transactions. | `present-verified` | inventory · `stock_transfer` approval gate, `/inventory/transfers/{id}/approve` · StockDocService · InventoryIT.transit | — |
| 385 | Stock reconciliation at individual locations. | `present-verified` | inventory · `/inventory/counts`, `/inventory/reports/reconciliation` · `/inventory/count` · InventoryIT.counts/reports | — |
| 386 | Performance comparison across branches and facilities. | `present-partial (built, unverified)` | BR-2: stock, delivery and production side by side on `/reports/branches` · BranchPerformanceIT (not run) | Verify by running; nothing was compiled |

### 7 Maintenance and Asset Management

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 396 | Fixed asset registration and asset classification. | `present-verified` | fixedasset · `/fixed-assets`, `/asset-categories` · `/fixed-assets*` · FixedAssetIT.categories/register | — |
| 397 | Equipment registers and assigned locations. | `present-partial (built, unverified)` | FleetPlantService, `/fleet/plant`, `/fleet/plant/classes`, PlantRegisterPage (by location), FleetPlantIT | Verify by running it |
| 398 | Preventive maintenance schedules. | `present-partial (built, unverified)` | Fleet 1.6 schedules on plant units (hours / calendar), FleetPlantIT.scheduleWorkOrderCost | Verify by running it |
| 399 | Maintenance work orders. | `present-partial (built, unverified)` | Fleet 1.6 work orders on plant units, FleetPlantIT.scheduleWorkOrderCost | Verify by running it |
| 400 | Breakdown and repair records. | `present-verified` | fleet, fixedasset · `/fleet/defects`, `/fixed-assets/{id}/repairs` · `/fleet/defects`, `/fixed-assets/:id` · FleetMaintenanceIT, FixedAssetIT | — |
| 401 | Spare parts inventory and consumption. | `present-verified` | fleet · work order parts · `/fleet/maintenance/work-orders/:id` · FleetMaintenanceIT.partsIssueReturnReverse | — |
| 402 | Maintenance labour and service costs. | `present-verified` | fleet · work order labour and bills · FleetMaintenanceIT.labourAndBills | — |
| 403 | Equipment downtime tracking. | `present-verified` | fleet, manufacturing · `/fleet/downtime`, `/manufacturing/shop-floor/downtime` · `/fleet/maintenance/downtime` · FleetMaintenanceIT.downtimeAndReliability, ShopFloorExecutionIT | — |
| 404 | Maintenance history by asset. | `present-verified` | fleet, fixedasset · work orders by vehicle, asset `maintenanceLogs` · `/fleet/vehicles/:id`, `/fixed-assets/:id` · FleetMaintenanceIT, FixedAssetIT.register | — |
| 405 | Service reminders and maintenance reporting. | `present-verified` | fleet · `/fleet/reminders`, `/fleet/maintenance/reliability` · `/fleet/maintenance/reliability` · FleetRemindersPayrollIT, FleetMaintenanceIT.downtimeAndReliability | — |
| 406 | Equipment depreciation and asset disposal. | `present-verified` | fixedasset · depreciation, disposal · `/fixed-assets/:id` · FixedAssetIT.depreciation/register | — |
| 407 | Equipment operating cost analysis. | `present-verified` | fleet · `/fleet/reports/vehicle-costs`, VehicleCostTab · FleetCostingIT.vehicleCosts | Fixed plant cost with CMMS-1 |

### 8.1 Configurable Business Rules

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 421 | Product categories and attributes. | `present-partial (built, unverified)` | As line 73: grades, attributes and specifications built, tests written, nothing run | INV-2 |
| 422 | Units of measurement and conversion rules. | `present-verified` | core · `/uoms`, `/uom-categories` · `/uom` · CoreIT.uoms, ProductUnitsIT | Conversion rules extended by UOM-1 |
| 423 | Inventory costing policies. | `present-verified` | inventory · `/inventory/cost-policies` · `/inventory/cost-policies` · InventoryCostPolicyIT | — |
| 424 | Approval hierarchies and transaction limits. | `present-partial (built, unverified)` | Amount-band workflows only; config routes untested | CTRL-1 built: `/approval-controls/limits`, department-manager steps, branch and department conditions; nothing compiled or run |
| 425 | Branches, departments, warehouses, and cost centres. | `present-verified` | core, accounting, inventory · `/branches`, `/cost-centers`, `/inventory/locations`, `/hr/departments` · screens above · CoreIT.branches, CostCenterAccountingIT.hierarchy, InventoryIT.locations, HrIT.orgMasters | — |
| 426 | Customer pricing and discount policies. | `present-partial (built, unverified)` | SALES-3: `/price-lists` with tiers and discounts, plus CRM price rules and the quote discount policy · `/price-lists`, `/crm/pricing` · PriceListIT, CrmSuiteIT.quotations | Verify by running; nothing was compiled |
| 427 | Supplier and customer payment terms. | `present-verified` | core · `/payment-terms` · `/accounts/payment-terms` · CoreIT.paymentTerms | — |
| 428 | Production recipes and bills of materials. | `present-verified` | manufacturing · `/manufacturing/boms`, `/precast/mix-designs`, grading profiles · ManufacturingIT.bomRules, PrecastManufacturingIT, CrushingIT.profileRules | — |
| 429 | Production workflows and quality checkpoints. | `present-verified` | manufacturing, quality · `/manufacturing/routings`, BOM stages, `/quality/inspection-plans` · `/manufacturing/engineering`, `/quality/plans` · MultiStageProductionIT.stageGate, QualityLotsIT | — |
| 430 | Inventory thresholds and replenishment rules. | `present-partial (built, unverified)` | Min, max and reorder quantity per product remain. Rules per product and location now raise alerts and draft purchase requests (`/inventory/replenishment`, V97). Tests written, nothing run | INV-3 |
| 431 | Accounting mappings and expense allocation. | `present-verified` | accounting · `/account-mappings`, `/inventory/posting-rules`, allocation rules · `/accounts/mappings`, `/accounts/posting-rules`, `/cost-center-accounting` · AccountingIT.masters, CostCenterAccountingIT, InventoryIT.postingRules | — |
| 432 | Document numbering and transaction references. | `present-partial (built, unverified)` | SequenceService takes prefix and padding from code; CompanySettingsPage shows sequences; no route found to configure numbering and no test of configuration | CTRL-2 built: `/document-numbering`, `/settings/document-numbering`, 25 sequences; POS keys and journals excluded. Nothing compiled or run |
| 433 | User roles and permissions. | `present-verified` | kernel · `/roles`, `/users` · `/staff/roles*` · KernelIT, RolePolicyTest | — |
| 434 | Operational reports and management dashboards. | `present-partial (built, unverified)` | `/analytics/*` management dashboards (AnalyticsIT written). Nothing compiled or run | Verify by running |

### 8.2 Custom Modules and Workflow Extensions

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 444 | Quarry extraction and production tracking. | `present-verified` | See 3.1 | — |
| 445 | Advanced weighbridge management. | `present-verified` | See 5.1 (42 weighbridge routes) · FleetWeighbridgeIT, FleetWeighbridgeDispatchIT | — |
| 446 | Specialized aggregate production planning. | `present-partial (built, unverified)` | `/manufacturing/planning/aggregates` (feed, hours, ROM stock per grading profile) · `/manufacturing/capacity` · CapacityCalculatorTest | Built by MFG-1, not compiled or run |
| 447 | Advanced block manufacturing and quality management. | `present-verified` | See 3.3 and 3.5 | — |
| 448 | Fleet GPS tracking and telematics integration. | `present-partial (built, unverified)` | TelematicsProvider SPI + MockTelematicsProvider + RestTelematicsProvider (contract adapter) · `/fleet/telematics`, `/fleet/live` · FleetTelematicsIT, RestTelematicsProviderTest. Vendor not chosen | Verify by running it |
| 449 | Laboratory testing integrations. | `present-verified` | quality · `/quality/lab-imports`, `/lab-adapters` · `/quality/lab` · QualityLotsIT.labCsvImport, LabAdaptersTest, CsvLabResultsTest | — |
| 450 | Automated production data capture. | `present-verified` | manufacturing · `/shop-floor/machine-data`, `/machine-events` · `/manufacturing/floor-setup` · ShopFloorExecutionIT.multiStageAndMachineData, MachineAdaptersTest | — |
| 451 | Industry-specific dashboards and profitability reports. | `present-partial (built, unverified)` | `/analytics` industry dashboards: quarry, aggregates, blocks, fleet, plus margin by product. Nothing compiled or run | Verify by running |
| 452 | Customer portals for orders and delivery tracking. | `present-partial (built, unverified)` | portal module · `/portal/*`, `/portal-accounts` · PortalShell, `/portal/deliveries/:id` (live status, last position) · PortalIT · ADR-S050 | Verify by running it |
| 453 | Mobile applications for drivers, supervisors, and field operators. | `present-partial (built, unverified)` | `/driver` PWA shell (DriverHomePage, trips and POD on `/driver/trips`, DriverFuelPage, DriverPreTripPage), FleetDriverAppService, `/fleet/driver-app/*`, `public/driver.webmanifest`, FleetDriverAppIT; supervisors keep `/manufacturing/terminal` and ExtractionEntryPage | Verify by running it |

### 8.3 Integration and Automation

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 463 | Weighbridge hardware. | `present-verified` | fleet · `/fleet/weighbridge/devices` · FleetWeighbridgeIT.tcpDevice | Simulated device only; test on the client indicator |
| 464 | Barcode scanners and label printers. | `present-partial (built, unverified)` | `/products/resolve-barcode`, `scanner-debounce.ts`, precast labels; `/labels/*`, LabelService, LabelRenderer (ZXing QR and Code 128 as PDF, ZPL file) · `/labels` · LabelRendererTest, LabelsAndBankFilesIT | Verify by running it |
| 465 | Accounting and banking interfaces. | `present-partial (built, unverified)` | BankStatementParser (CSV, MT940, camt.053), `/bank-reconciliation/import-file` · `/accounts/bank-import` · BankStatementParserTest, LabelsAndBankFilesIT. No live bank feed | Verify by running it |
| 466 | Mobile money payment providers. | `present-partial (built, unverified)` | MobileMoneyProvider SPI + MockMobileMoneyProvider · `/mobile-money/*`, MobileMoneyService (receipt through PaymentService) · `/payments/mobile-money` · MobileMoneyIT, MobileMoneyProviderTest. No real provider | Verify by running it |
| 467 | GPS and fleet tracking systems. | `present-partial (built, unverified)` | As fleet telematics above, RestTelematicsProvider | Verify by running it |
| 468 | Production machinery and industrial data systems. | `present-verified` | manufacturing · machine adapters · ShopFloorExecutionIT, MachineAdaptersTest, MachineEventParserTest | — |
| 469 | Business intelligence tools. | `present-partial (built, unverified)` | `reporting` views (V111), `/bi/datasets` CSV, `/reports/bi-exports`, plus `/metrics`, `/webhooks`. BiExportIT · docs/integrations.md | Verify by running it |
| 470 | Customer-facing websites and applications. | `present-partial (built, unverified)` | Customer portal (452); webhooks and REST API | Verify by running it |
| 471 | Notification and communication services. | `present-verified` | communication · `/communication/*`, MessagingProvider · `/communication*` · CommunicationIT, ProvidersTest | — |

### 9 Security, Auditability, and Operational Control

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 481 | Role-based access control. | `present-verified` | kernel · `@RequirePermissions`, `/roles` · `/staff/roles*` · KernelIT, ArchitectureTests, authorization matrix ITs | — |
| 482 | Permission restrictions by module and transaction type. | `present-verified` | kernel · permission per route · `authorizationMatrix` in AccountingIT, InventoryIT, InvoicingIT, FleetCostingIT, QuarryIT | — |
| 483 | Branch and warehouse access restrictions where supported. | `present-partial (built, unverified)` | BR-1: same as line 383; `user-data-scopes` routes, audited, no self-widening · Staff → Data access · UserDataScopeIT (not run). Not yet scoped: accounting reports, procurement, HR, CRM | Verify by running; nothing was compiled |
| 484 | Approval requirements for sensitive transactions. | `present-verified` | kernel/approvals gates on bills, receipts, stock, weighbridge, explosives · `/approvals` · FleetWeighbridgeIT.exceptionThroughInbox, QuarryIT, ProcurementIT.receiptApprovalGate | — |
| 485 | Audit trails for relevant business activities. | `present-verified` | kernel/audit · `/audit-logs` · `/audit-log` · KernelIT | — |
| 486 | Controlled posting and reversal of financial transactions. | `present-verified` | accounting · PostingService, reversal routes · ReportingIT, AccountingIT.journalEntries, QuarryIT.reverseReleasesEvidence, CrushingIT.runLifecycle | — |
| 487 | User accountability for operational changes. | `present-verified` | kernel/audit · createdBy on records, AuditService · KernelIT.tenantIsolation (audit-logs) | — |
| 488 | Validation of transaction states and required approvals. | `present-verified` | State checks asserted across ITs: ProcurementIT.receiptStatusAndGuards, InventoryIT.transit, FleetRegistryIT.statusMachine | — |
| 489 | Backup and recovery procedures. | `present-verified` | backup · `/admin/backups/*` · `/settings/backup` · BackupRestoreIT, BackupServiceTest; `scripts/dr-drill.mjs` | Restoration drill on real data remains an owner step |
| 490 | Controlled configuration changes. | `present-partial (built, unverified)` | SettingsService records audit; cost policies need approval (InventoryCostPolicyIT). No change approval for other settings | CTRL-1 built for approval config and numbering (`config_change` workflow); general settings are not held. Nothing compiled or run |
| 491 | Separation of duties for critical business processes. | `present-partial (built, unverified)` | "You cannot approve a request you created" (QuarryIT, FleetRegistryIT), journal maker-checker (AccountingIT). No configurable SoD matrix over the brief's sensitive pairs | CTRL-1 built: five pairs in `/approval-controls/sod-rules`, off per tenant until switched on (weighbridge always on), `SodCheck` audit rows. Credit override and supplier bill are a hook only. Nothing compiled or run |

### 10 Management Dashboards and Industry-Specific KPIs

| Line | Requirement | Status | Evidence | Action |
|---:|---|---|---|---|
| 503 | Quarry operations: Extraction volume, extraction cost per tonne, target achievement | `present-partial (built, unverified)` | `/analytics/quarry` from `QuarryReportService`; formulas in docs/spec/analytics-kpis.md. Nothing compiled or run | Verify by running |
| 504 | Aggregate processing: Input-to-output yield, production volume, material loss | `present-partial (built, unverified)` | `/analytics/aggregates` from `CrushingReportService`. Nothing compiled or run | Verify by running |
| 505 | Block manufacturing: Blocks produced, rejection rate, cost per acceptable block | `present-partial (built, unverified)` | `/analytics/blocks` from `PrecastReportService`. Nothing compiled or run | Verify by running |
| 506 | Inventory: Stock availability, stock value, stock aging, stock discrepancies | `present-partial (built, unverified)` | `/analytics/inventory`: value and availability read directly, aging and drift from `stockHealth` and reconciliation, count and survey variances. Nothing compiled or run | Verify by running |
| 507 | Procurement: Purchase price trends, supplier performance, procurement spending | `present-partial (built, unverified)` | `/analytics/procurement`: spend and price trend from purchase order lines, scorecard from `SupplierPerformanceService`. Nothing compiled or run | Verify by running |
| 508 | Sales: Revenue, sales volume, gross margin, customer debt | `present-partial (built, unverified)` | `/analytics/sales`: revenue from `SalesAnalysisService`, gross margin from `ManufacturingCostingService`, aged customer debt. Nothing compiled or run | Verify by running |
| 509 | Fleet and delivery: Deliveries completed, fuel cost, transport cost per tonne | `present-partial (built, unverified)` | `/analytics/fleet` from `FleetReportService`, with trips narrowed by branch and product. Nothing compiled or run | Verify by running |
| 510 | Maintenance: Equipment downtime, maintenance expenditure, breakdown frequency | `present-partial (built, unverified)` | `/analytics/maintenance`: reliability, plant hours, OEE and work order spend. Nothing compiled or run | Verify by running |
| 511 | Finance: Cash flow, operating expenses, receivables, payables, profitability | `present-partial (built, unverified)` | `/analytics/finance`: profit from the consolidated report, cash flow in unlimited views, aged receivables and payables. Nothing compiled or run | Verify by running; cash flow and P&L value tests remain with FIN-3 |
| 512 | Branch performance: Revenue, expenses, stock position, and profitability by location | `present-partial (built, unverified)` | BR-2 report with revenue, expenses, stock position and profit by branch · `/reports/branches`; `/analytics/branches` dashboard on top of it. Nothing compiled or run | Verify by running; nothing was compiled |
