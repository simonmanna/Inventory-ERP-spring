# Inventory feature-risk map (IPR R6)

Status: Phase 0 (task T07), report-only. Source of truth in code:
`backend/src/main/java/com/inventoryerp/inventory/application/InventoryFeatureUsageService.java`
(`P2_FEATURES` and the feature definitions). Keep this file and that list identical.

## Why

The 2026-11-27 inventory audit deferred about 30 findings as P2. A P2 matters only to an organisation that uses the
feature behind it. Requirement R6: when an organisation uses such a feature, that P2 becomes a go-live blocker for that
organisation (gate G6 is judged per organisation).

## How it is evaluated

| Where | What |
|---|---|
| `GET /inventory/reports/feature-usage` (permission `inventory_valuation:read`, refused for branch-restricted users) | Every feature with `inUse` and a capped `count`, plus `promotedP2s` (the P2s below whose predicate holds). |
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

| P2 | Finding (short) | Promoted when |
|---|---|---|
| X-3 | Manual moves out of QC quarantine or rental locations | `qc_checks` or `quality_lots` or `rental` |
| X-4 | Offline sales against negative-stock refusal | `offline_devices` + `negative_stock_blocked` |
| X-6 | POS refund restock not linked to its issue | `pos_refunds` |
| X-7 | Rental serial units | `rental_serial_units` |
| X-8 | POS order stock paths | `pos_orders` |
| X-9 | Manufacturing simple orders | `manufacturing_orders` |
| X-10 | POS kitchen voids | `pos_kitchen_voids` |
| X-11 | Repair parts | `repair` |
| F4 | Sales returns of variant products | `sales_returns` + `variants` |
| F5 | Sales-order reservations | `sales_order_reservations` |
| F6 | Vendor bills with automatic receipt | `vendor_bill_auto_receive` |
| F7 | Debit notes across warehouses | `debit_notes` + `multi_warehouse` |
| F8 | Serial products received through procurement | `serial_products` + `procurement_receipts` |
| F9 | Branches with two or more warehouses | `multi_warehouse_branches` |
| F10 | Branch-restricted users and sales returns | `branch_restricted_users` + `sales_returns` |
| F11 | Vendor bills | `vendor_bills` |
| C-4 | Negative stock refused | `negative_stock_blocked` |
| C-6 | FIFO products and revaluations | `fifo_products` + `revaluations` |
| C-8 | Tracking changes on stocked products | `serial_products` or `lot_expiry_products` (conservative: no audit trail of tracking changes is queried) |
| C-9 | Serial products | `serial_products` |
| S6 | Lot products and adjustments | `lot_products` + `adjustments` |
| S8 | Stock-outs | `stock_outs` |
| S10 | Count sessions | `count_sessions` |
| S11 | Warehouse bins | `warehouse_bins` |
| S12 | Count sessions | `count_sessions` |
| S13 | Any inventory use | `inventory_in_use` |
| S14 | Reservations | `reservations` |
| GL-7 | AVCO with returns to supplier, claims or stage postings | `avco_products` + `rtv_claims_stage` |
| GL-9 | Revaluations | `revaluations` |
| S-6 | Any inventory use | `inventory_in_use` |
| S-8 | CSV exports | `csv_exports` |
| S-9 | Large catalogue | `large_catalogue` |
| S-10 | Large catalogue | `large_catalogue` |

S13, S-6 and S-8 apply to every organisation that keeps stock, so `integrity_feature_risk` is raised for every such
organisation until those P2s are closed or the owner accepts them. This is intended: the finding is report-only.

## Changing the map

- When a P2 is fixed (closure rule in `docs/audits/2026-11-27-inventory-readiness.md`), remove its row here and in
  `P2_FEATURES` in the same PR.
- A new feature needs a definition in `InventoryFeatureUsageService` (bounded count over indexed columns) and a row in
  the features table. `InventoryIntegrityP0IT.staticMaps` fails when a P2 names a feature that does not exist.
