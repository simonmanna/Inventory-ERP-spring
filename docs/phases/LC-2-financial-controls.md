# LC-2 — Landed cost financial controls (done-report)

- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) § LC-2 · ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md) § LC-2
- Migration: `V293__lc2_landed_cost_controls.sql`

## Delivered

| Plan item | Where | Test (`LandedCostControlsIT` unless noted) |
|---|---|---|
| Strict draft → submitted → approved, `allowDirectApproval` | `LandedCostVoucherService.approve` | `strictApproval` |
| Approval limits (`landed_cost_voucher`) | `ApprovalLimitService.assertWithin` | `approvalLimit` |
| `approvedHash` over every money driver | `driverHash` | `hashRefusesChangedInputs` |
| Disposition per target, OR tolerance, new / gone targets on amount only | `disposition`, `assertWithinTolerance` | `smallMovementPosts`, `largeMovementNeedsReapproval`, `zeroAmountThreshold` |
| `reopen` | `reopen`, `POST …/{id}/reopen` | `strictApproval`, `largeMovementNeedsReapproval` |
| Approved / posted snapshot + diff in the audit | `post` | `smallMovementPosts` |
| Normalised duplicate check, original reference in the 409 | `normalize`, `registerInvoices` | `normalisedDuplicates`, audit case 9 |
| Two partial unique indexes, override used once, revocation | V293, `createOverride` / `revokeOverride` | `duplicateOverride` (incl. 403) |
| Weight / volume validation naming the products; stored weight / volume / unit | `allocate` | `weightValidation` |
| Basis split on `AllocationCalculator` (LAST) | `allocate` | every existing voucher IT unchanged |

## Deviation (documented in the ADR)

The unique indexes live on `LandedCostInvoiceRef` (one row per voucher and invoice), not on `LandedCostCharge`:
one invoice may span several charge lines of a voucher.

## Changed existing tests

Vouchers are now submitted before approval in the existing ITs (`LandedCostFixtures`, `LandedCostAuditIT`,
`LandedCostVoucherIT`, `ImportLandedScenarioIT`, `ValuationIntegrityIT`) and in the fleet inbound-freight flow; no
assertion changed.
