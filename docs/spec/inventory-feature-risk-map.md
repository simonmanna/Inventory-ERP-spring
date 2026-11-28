# Inventory feature-risk map (IPR R6)

Status: Phase 0 (task T07), report-only; fixed-P2 status added in Phase 4. Source of truth in code:
`backend/src/main/java/com/inventoryerp/inventory/application/InventoryFeatureUsageService.java`
(`P2_FEATURES` and the feature definitions). Keep this file and that list identical (`InventoryFeatureRiskMapDocTest`).

## Why

The 2026-11-27 inventory audit deferred about 30 findings as P2. A P2 matters only to an organisation that uses the
feature behind it. Requirement R6: when an organisation uses such a feature, that P2 becomes a go-live blocker for that
organisation (gate G6 is judged per organisation).

## How it is evaluated

| Where | What |
|---|---|
| `GET /inventory/reports/feature-usage` (permission `inventory_valuation:read`, refused for branch-restricted users) | Every feature with `inUse` and a capped `count`, plus `promotedP2s` (the open P2s below whose predicate holds) and `fixedP2s` (the P2s fixed in the programme, with their evidence file). |
| Web: Inventory → Reporting → Integrity & Reconciliation (`/inventory/integrity`) | The integrity checks with their rows, the GL tie-out, this feature table and the promoted / fixed P2s, with links to the Posting Monitor findings. |
| `GET /inventory/reports/integrity` → `reportOnlyChecks[feature_risk]` | The same promoted P2s as rows. Fails while any applies. |
| Daily integrity monitor (`InventoryIntegrityMonitor`) | Raises `InventoryException` kind `integrity_feature_risk` (one open at a time). Report-only: it is not in `INTEGRITY_VALUE_KINDS`, and the period-close gates skip every other `integrity_*` kind, so it never holds a close. |
| Release checklist | Re-run the feature-usage report for every organisation before each release (mandatory step). |

A P2 is promoted when **every** feature of **any one** group is in use (groups are separated by "or" below).

Counting is bounded: each feature counts at most 10,001 rows (`COUNT_CAP`), over indexed columns. "In use" means a count
above zero, except `multi_warehouse` (2 or more) and `large_catalogue` (more than 10,000).

## Features

| Key | In use when |
|---|---|
| `inventory_in_use` | any `StockItem` row |
| `serial_products` | a live product with `serialTracking` |
| `serial_lot_products` | a live product with `serialTracking` and `batchTracking` |
| `lot_products` | a live product with `batchTracking` |
| `lot_expiry_products` | a live product with `batchTracking` or `expiryTracking` |
| `variants` | any `ProductVariant` |
| `standard_products` / `fifo_products` / `avco_products` | a live product with that costing method |
| `large_catalogue` | more than 10,000 live products |
| `fleet_delivery_orders` | any `FleetDeliveryOrder` |
| `quality_lots` | any `QualityInspectionLot` |
| `qc_checks` | any `QcCheck` |
| `manufacturing_orders` | any `ProductionOrder` |
| `quarry_extraction` | any `QuarryExtractionLog` |
| `crushing_runs` | any `CrushingRun` |
| `processing_jobs` | any `ProcessingJob` |
| `process_costing` | any `ProcessCostPeriod` |
| `multi_currency_bills` | a vendor bill whose currency code differs from the organisation's |
| `vendor_bills` | any vendor bill (`Document.documentType = vendor_bill`) |
| `vendor_bill_auto_receive` | a ledger row with `referenceType = vendor_bill` |
| `debit_notes` | any debit note document |
| `procurement_receipts` | any live `GoodsReceiptNote` |
| `rtv_claims_stage` | a ledger row of `debit_note`, `supplier_claim` or `production_stage` |
| `offline_devices` | a `PosDevice` not revoked |
| `warehouse_bins` | an active `WarehouseBin` |
| `rental` | any `RentalAgreement` |
| `rental_serial_units` | a live `RentalUnit` bound to an `InventorySerial` |
| `repair` | any `RepairOrder` |
| `count_sessions` | a live `InventoryCountSession` |
| `adjustments` | any `StockAdjustment` |
| `stock_outs` | any `StockOut` |
| `revaluations` | any `InventoryRevaluation` |
| `reservations` | any `StockReservation` |
| `sales_order_reservations` | a `StockReservation` with `sourceType = sales_order_line` |
| `sales_returns` | any `SalesReturn` |
| `pos_orders` | any POS `Order` |
| `pos_refunds` | a ledger row with `referenceType = pos_refund` |
| `pos_kitchen_voids` | a ledger row with `referenceType = pos_kitchen_waste` |
| `branch_restricted_users` | any `UserDataScope` grant |
| `multi_warehouse` | 2 or more active stock locations (not virtual, not transit) |
| `multi_warehouse_branches` | a branch with 2 or more active stock locations |
| `negative_stock_blocked` | `inventory.allowNegativeStock` resolves to false for the organisation, or any setting row sets it false |
| `csv_exports` | no usage trail exists, so assumed whenever `inventory_in_use` |

## P2 → feature predicate

| P2 | Finding (short) | Promoted when | Status | Evidence |
|---|---|---|---|---|
| X-3 | Manual moves out of QC quarantine or rental locations | `qc_checks` or `quality_lots` or `rental` | open | |
| X-4 | Offline sales against negative-stock refusal | `offline_devices` + `negative_stock_blocked` | open | |
| X-6 | POS refund restock not linked to its issue | `pos_refunds` | open | |
| X-7 | Rental serial units | `rental_serial_units` | open | |
| X-8 | POS order stock paths | `pos_orders` | open | |
| X-9 | Manufacturing simple orders | `manufacturing_orders` | open | |
| X-10 | POS kitchen voids | `pos_kitchen_voids` | open | |
| X-11 | Repair parts | `repair` | open | |
| F4 | Sales returns of variant products | `sales_returns` + `variants` | open | |
| F5 | Sales-order reservations | `sales_order_reservations` | open | |
| F6 | Vendor bills with automatic receipt | `vendor_bill_auto_receive` | open | |
| F7 | Debit notes across warehouses | `debit_notes` + `multi_warehouse` | open | |
| F8 | Serial products received through procurement | `serial_products` + `procurement_receipts` | open | |
| F9 | Branches with two or more warehouses | `multi_warehouse_branches` | open | |
| F10 | Branch-restricted users and sales returns | `branch_restricted_users` + `sales_returns` | open | |
| F11 | Vendor bills | `vendor_bills` | open | |
| C-4 | Negative stock refused | `negative_stock_blocked` | open | |
| C-6 | FIFO products and revaluations | `fifo_products` + `revaluations` | open | |
| C-8 | Tracking changes on stocked products | `serial_products` or `lot_expiry_products` (conservative: no audit trail of tracking changes is queried) | open | |
| C-9 | Serial products | `serial_products` | open | |
| S6 | Lot products and adjustments | `lot_products` + `adjustments` | open | |
| S8 | Stock-outs | `stock_outs` | open | |
| S10 | Count sessions | `count_sessions` | open | |
| S11 | Warehouse bins | `warehouse_bins` | open | |
| S12 | Count sessions | `count_sessions` | open | |
| S13 | Any inventory use | `inventory_in_use` | open | |
| S14 | Reservations | `reservations` | fixed | `docs/audits/ipr-evidence/3-security-controls.md` |
| GL-7 | AVCO with returns to supplier, claims or stage postings | `avco_products` + `rtv_claims_stage` | open | |
| GL-9 | Revaluations | `revaluations` | open | |
| S-6 | Any inventory use | `inventory_in_use` | fixed | `docs/audits/ipr-evidence/4-golive-p2.md` |
| S-8 | CSV exports | `csv_exports` | fixed | `docs/audits/ipr-evidence/3-security-controls.md` |
| S-9 | Large catalogue | `large_catalogue` | open | |
| S-10 | Large catalogue | `large_catalogue` | open | |

S13 applies to every organisation that keeps stock, so `integrity_feature_risk` is raised for every such organisation
until S13 is fixed or the owner accepts it. This is intended: the finding is report-only. (S-6 and S-8 were in the
same position until they were fixed in Phases 3 and 4.)

**Status.** `open`: promoted when its predicate holds. `fixed`: the fix and its evidence file are on the branch, so the
P2 is never promoted again, for any organisation. The finding is closed in the register
(`docs/audits/2026-11-27-inventory-readiness.md`) only by the integrator, under the closure rule; a P2 the
independent review reopens goes back to `open` here.

## Changing the map

- **When a P2 is fixed:** in `P2_FEATURES` append `.fixed(<evidence file>)` to its rule (constants `EVIDENCE_PHASE3`,
  `EVIDENCE_PHASE4` exist; add one per evidence file), and in the table above set Status to `fixed` and Evidence to the
  same path. Keep the row: the report lists fixed P2s under `fixedP2s` and the web page shows them.
  `InventoryFeatureRiskMapDocTest` (plain JUnit, seconds) fails when the doc and the code disagree or the evidence
  file is missing.
- **Integrator, after merging the other streams:** tick each P2 whose evidence file is merged (the stream evidence files
  are under `docs/audits/ipr-evidence/`). Candidates by stream: X-3, C-8 (engine stream E2); X-8, F11 (wave 3); X-7,
  X-10, F4–F10, C-6, C-9, S6, S8, S10–S12, GL-9, S-9, S-10 per their streams' evidence.
- A new feature needs a definition in `InventoryFeatureUsageService` (bounded count over indexed columns) and a row in
  the features table. `InventoryIntegrityP0IT.staticMaps` fails when a P2 names a feature that does not exist.
