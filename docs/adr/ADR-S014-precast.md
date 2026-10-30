# ADR-S014: Concrete block / precast manufacturing (Phase 2.4)

- Status: Accepted (autonomous, 2026-10-30) — client production manager to review the defaults under "Open"
- Builds on: ADR-S011 §8 (design sketch), ADR-S011 §1–5 (multi-stage engine, stage gates), ADR-S013 (quality),
  ADR-S006 (BOM versions, ECO, approvals), ADR-S008 (costing)
- Migration: `V29__manufacturing_blocks_precast.sql` (placeholder number, renumbered at merge)

## Context

The client moulds concrete blocks, pavers and kerbs: a mixer batch (cement, sand, aggregate, water, admixture) makes
N blocks, the press stamps them onto pallets, the pallets cure in a chamber or yard for a minimum number of days, the
cured blocks are cubed (counted off the boards — good, second grade, broken) and strength-tested (7 / 28-day
compressive strength) before sale. ADR-S011 §8 sketched this on the 2.1 engine; this ADR fixes the choices that go
beyond that sketch.

## Decision

1. **Inside `manufacturing`, no new module or flag.** Package `manufacturing.application.Precast*`, routes under
   `/api/v1/manufacturing/precast/...` (26), the existing `manufacturing` flag and permissions (no new keys):
   specs / areas / mix designs `bom:read` / `bom:create` / `bom:update` / `bom:activate`; orders, runs and curing
   `production_order:read` / `create` / `complete`; strength gate and final lots `production_order:qc`; reports
   `production:report`.
2. **Mix design = BOM version + `PrecastMixDesign` row (1:1 by `bomId`).** BOM `outputQuantity` = units per mixer
   batch, BOM lines = materials per batch (with scrap %). The row holds what a BOM cannot: cement product and units
   per bag, batch volume (m³), w/c ratio, minimum curing days, strength checkpoint code, default curing area, maturity
   target / datum. Versioning, approvals and the ECO revise flow are the BOM's; `revise` copies the row. Creating a
   mix design without a routing creates a two-operation routing (batching & pressing → curing) with its stage set-up.
3. **Batch scaling** (`BatchScaler`, pure): a requirement in units, batches, cement bags, m³, machine cycles or
   pallets → whole batches (rounded up; cement bags rounded down so the plan never exceeds the bags available) →
   units, expected good units, pallets, cycles, volume and material quantities by the BOM explosion rule. A precast
   order is an ordinary production order for `units`, staged at once (2.1 `initialize`).
4. **Curing is WIP, not stock, until cubing.** The pressing stage is pass-through (no "green block" product): its
   cost moves to the curing stage in the WIP sub-ledger, so blocks in curing can never be sold. Physical position is
   tracked by a **curing batch** (`PrecastCuringBatch`, one per pressing run: pallets, curing area, bay, shift,
   maturity readings, moves). Rejected alternative: a green-block intermediate in a curing location (needs a second
   product per block type and a stock transfer per pallet move).
5. **Minimum curing — two checks.**
   - Precast: a batch can be **cubed** only when `pressed at + minCuringDays` has passed (per batch).
   - 2.1 engine (generic, any gated stage): `RoutingStageSetup.minCuringDays` → `ProductionOrderStage.minCuringDays`;
     a **pass** recorded before `curing start + minCuringDays` (curing start = latest output of the stage before the
     gated one, else of the gated stage itself) does not release: the stage gets `qcStatus = curing` and
     `qcPassDueAt`; the curing job (`PrecastCuringJob`, ShedLock, `PRECAST_CURING_CRON`, default every 15 min) passes
     it when due. A fail is recorded at once. This covers the quality-lot bridge too.
6. **Cubing = curing-stage output.** good + second grade + broken must equal the batch quantity; good blocks go to
   `QC-QUARANTINE` (the gate), second grade is a BOM by-product (`nrv`, default the second-grade product's sales
   price) of the curing stage taken from the block spec, broken blocks are stage scrap with a reason (abnormal loss
   written off). The cubing that leaves no batch curing after the last pressing run completes the stage.
7. **Strength gate = 2.1 gate + 2.6 lot.** The curing stage is always gated with the mix design's checkpoint code; an
   active in-process plan on that code opens a lot (bridge, ADR-S013) — 7-day strength as a critical numeric
   parameter; release passes the gate, reject / downgrade fails it. Without a plan the gate is manual
   (`/curing-batches/{id}/gate-decision`). Batches follow every gate decision through the in-module
   `StageGateListener` (passed → released, failed → failed).
8. **Final (28-day) lots before sale.** After release the curing job (and `POST .../final-inspection`) opens a
   `final` lot (`sourceType = precast_batch`, `stockControl = quality`, hold policy of the plan) when the block has an
   active final plan — the plan's hold policy decides whether released stock is held until the 28-day result;
   second grade after release is `QualityDecisionService.downgrade` on that lot (quality's own route / screen).
9. **Cost per acceptable block** = finished-goods value of the curing stage ÷ good blocks (normal loss absorbed,
   abnormal breakage and second-grade NRV carved out); material usage variance per material = actual − standard,
   standard = quantity per batch × (1 + scrap %) × blocks processed at the press ÷ units per batch. Labour / energy /
   machine cost: 2.1 conversion postings on the stages (by kind) and work-order time; no new GL flow.
10. **Pallet labels**: QR (ZXing → SVG) with `PRECAST|batch|n/N|sku|qty`, one per pallet.

## Consequences

- No new GL rule: every effect goes through the 2.1 stage postings (Dr WIP / Cr stock, Dr stock / Cr WIP, abnormal
  scrap to stock adjustment expense); WIP returns to zero when the order completes.
- 2.1 changes (additive): two columns on the stage tables, `qcStatus = curing`, `StageSetupInput.minCuringDays`
  (null keeps the saved value), the `StageGateListener` hook. Orders without `minCuringDays` behave as before.
- The 2.1 stage gate is per stage, so for one order a later pressing run delays the gate hold of earlier batches; one
  order per production day / shift is the recommended practice (default in the UI).

## Open (owner / client to confirm)

1. Default minimum curing 7 days; strength release on the 7-day result (28-day as a final lot).
2. Second grade valued at NRV = its sales price; broken blocks are an abnormal loss (pressing / curing yield 100 %).
3. Maturity (Nurse-Saul, datum −10 °C) is informational; release is by calendar days only.
4. Reversing only the gate release (2.1 `qc_release` posting) does not put a released batch back to `cubed`.
