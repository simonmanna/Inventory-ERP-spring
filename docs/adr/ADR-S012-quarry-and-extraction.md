# ADR-S012: Quarry & extraction management (`quarry` module)

- Status: Accepted (autonomous, 2026-10-30)
- Brief: [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](../AUTONOMOUS_BUILD_CONSTRUCTION.md) Phase 2.2; requirements
  [§3.1](../requirements/construction-materials-erp.md)
- Spec: [`docs/spec/quarry.md`](../spec/quarry.md) — routes with permissions, data model, GL, status
- Migration: `V28__quarry.sql` (placeholder number, renumbered at merge); licence server `V4__quarry_feature.sql`
- Related: ADR-S009 (fleet — vehicles, weighbridge), ADR-S007 (AVCO / FIFO costing), ADR-S008 (standard / process
  costing), ADR-S011 (multi-stage manufacturing, 2.1 — crushing 2.3 is built on its stage engine)

## Context

The client quarries granite and other rock, blasts benches, hauls run-of-mine (ROM) stone from the face to a ROM pad /
the primary crusher, and needs the extraction cost per tonne (fuel, labour, plant hours, blasting, royalties) so the
crushed aggregates (2.3) carry a true cost. Explosives are legally controlled: every kilogram must be issued against an
authorised request, from a licensed magazine, with a full audit trail. The ERP had nothing for quarrying; manufacturing
is order-centric (BOM → work order) and does not model sites, benches, blasts or explosive control.

## Decision

### 1. A new Spring Modulith module `com.inventoryerp.quarry` (coordinator decision)

- `web → application → domain`, displayName "Quarry"; `domain` (hand-written JPA entities: column catalogue, tenancy
  filters, schema validation) and `application` are `@NamedInterface`s. Services are JdbcClient SQL like fleet
  (`QuarryDb` helper, explicit `"organizationId" = ?`, `"deletedAt" IS NULL`), every table has the `tenant_isolation`
  RLS policy.
- Feature flag **`app.modules.quarry`** (env `ENABLE_QUARRY`, default `false`); controllers are
  `@ConditionalOnProperty` + `@RequiresModule("quarry")`; `AccessGuardInterceptor` maps `quarry → app.modules.quarry`;
  licence feature **`quarry`** (`LicenseFeatures.QUARRY`, in `ALL`; licence server `KNOWN_FEATURES` + V4 adds it to the
  enterprise-yearly and perpetual-onprem plans); manifest `QuarryModule` registers module `quarry` for the Modules
  screen. Web: sidebar section "Quarry" (`MODULE_FEATURES` key `quarry`), Modules screen entry, `nav.quarry`.
- Why a module, not part of manufacturing: quarry has its own master data (sites, benches, magazines), its own control
  regime (explosives) and is used by a different team (pit, blasting crew). Manufacturing stays the transformation
  engine; 2.3 crushing consumes the ROM stockpile through quarry's public API (§7) on the 2.1 stage engine.
- Dependencies: fleet (`FleetVehicleService.get`, `WeighbridgeTicketService.completedTicket` — read-only),
  inventory (`StockService`, `StockReversalService`, `StockPostingService.stockAccount`, `LocationService`),
  accounting (`PostingService`, `AccountResolverService`), kernel (approvals, audit, sequence, events). Quarry never
  writes fleet, hr or inventory tables directly.

### 2. Master data

Site (mining licence, branch, cost centre) → zones → benches → faces (one self-referencing table, three levels);
material type / grade (rock type, density) mapped to the **inventory product** the stone is stocked as (AVCO or FIFO;
standard-costed products are refused because ROM enters stock at actual cost); stockpiles (kind `rom` / `product`) and
magazines are **inventory locations** (created through `LocationService` when none is given, one quarry row per
location).

### 3. Extraction logs, haulage, losses

A daily extraction log = one shift at a site / zone of one material into one ROM stockpile. Quantity lines: loads ×
truck capacity (fleet payload or entered), a **completed weighbridge ticket** of subject `internal_haulage` / `other`
(net kg → t), a **haulage record**, or a survey figure. Equipment lines must be fleet vehicles of a `mobile_plant`
class (hours or hour-meter start / end, fuel litres, operator = hr employee); labour lines are hr employees. A ticket
or haulage record evidences one active line only (partial unique indexes; released when a draft is deleted or a log
reversed). Haulage records (face → ROM / crusher / waste dump) have no stock effect of their own — the log that pulls
them in receives the stone — so nothing is counted twice. Losses (overburden, unusable rock, waste, spillage) live on a
log (reduce net tonnes) or stand alone (stripping); fleet trips are referenced read-only.

### 4. Costing and GL

Cost of a log = equipment hours × rate (vehicle rate → class rate → setting) + fuel litres × fuel cost/L + labour hours
× rate + blasting share + royalties / levies (rate per net or gross tonne, effective-dated, per site / material) +
other direct cost. Pure `QuarryCostCalculator`, `BigDecimal` 2 dp amounts / 6 dp unit cost, HALF_UP.
**Allocation rule (org setting)**: `net_tonnes` (default — the good stone carries every cost, losses absorbed) or
`gross_tonnes` (cost per gross tonne; the loss share is written off to 5363). Posting receives the net tonnes with
`StockService.receive` at the unit cost (move type `production_output`, source `quarry_extraction`) and posts one
entry through the posting engine, key `quarry:extraction:<id>`:
Dr ROM stock account (capitalised) [+ Dr 5363 Quarry Extraction Losses] / Cr 5361 Quarry Extraction Cost Absorbed per
component + Cr 2177 Quarry Royalties & Levies Payable per rate (authority as partner). Fuel, payroll and plant costs
stay expensed where they arise (fleet fuel, payroll); quarry capitalises its share against the absorbed contra account,
as manufacturing does with overhead absorption. Reversal: stone out at the cost it came in with
(`StockReversalService.reverseLedgerSource`), entry reversed, blast share and evidence released. Accounts are created
on first use with mapping keys `quarry_extraction_absorbed`, `quarry_royalty_payable`, `quarry_blasting_expense`,
`quarry_extraction_loss` (remappable under Accounting → Account Mapping).

### 5. Drilling, blasting and explosives as controlled stock

- Blast record: pattern (holes, depth, burden × spacing, sub-drill, stemming → drilled metres, volume, estimated tonnes
  via material density), drill rig, compliance (shotfirer + licence, blast permit + expiry, exclusion zone, warning /
  all-clear, misfires, PPV, airblast, weather). planned → **fired** (compliance fields required, permit valid, no open
  request) → **closed** (all issues reconciled). Its cost pool (explosives net of returns + drilling + other) is
  absorbed by extraction logs that reference it at pool / estimated tonnes × net tonnes, capped at what is left.
- Explosives = inventory products registered in `QuarryExplosive` (controlled, UN number, hazard class, NEQ kg per
  unit) held in magazine locations (licence, keeper). Received with the normal procurement GRN.
- Issue flow: **request** (`quarry_blasting:request_explosive`) → kernel **approval engine** (entity
  `quarry_explosive_issue`, default workflow one step, approver permission `quarry_blasting:approve_explosive`,
  distinct approvers; the engine already refuses self-approval) → **issue** (`quarry_blasting:issue_explosive` —
  "explosive issue authorisation"; setting `distinctExplosiveIssuer`, default on: never the requester; Idempotency-Key)
  → **reconcile** the unused return. A request is refused when the workflow is switched off — explosives are never
  issued without an approval. Issuing locks the issue row and takes stock with `StockService.issue`
  (`requireAvailable`: refused when the magazine is short; concurrent issues serialise on the stock row) and posts Dr
  6470 Quarry Blasting & Explosives / Cr stock (key `quarry:explosive-issue:<id>`); returns come back at the issue unit
  cost (key `quarry:explosive-return:<id>`). Every step is in the audit log (create / approve / reject / issue /
  reconcile / cancel); the issue screen shows it with the approval request's own trail.
- Why not a flag on `Product`: the `Product` entity is shared with POS and inventory; a quarry-owned registry keeps the
  change additive and carries the explosive-specific fields.

### 6. Permissions (granular)

`quarry:report`, `quarry:settings`; `quarry_site:{read,create,update,delete}`;
`quarry_extraction:{read,create,update,post,plan,haul}`;
`quarry_blasting:{read,create,update,request_explosive,approve_explosive,issue_explosive,magazine}`. Every quarry role
needs `quarry_site:read` (form lookups). The migration grants them to the Administrator roles (as V15 did).

### 7. Public API for crushing (2.3)

`QuarryStockpileService`: `stockpile(id)`, `locationOf(id)`, `romStock(siteId, kind)` (stockpile × product on-hand at
average cost), `onHand(stockpileId, productId)`, `consume(ConsumeRequest)` (quantity-only `StockService.issue`,
`production_consume`, refused when short; the caller posts Dr WIP / Cr stock, e.g.
`StockPostingService.postProductionConsume`). Event `quarry.extraction.posted` (site, stockpile, location, material,
product, net tonnes, unit cost). A ROM stockpile is an ordinary inventory location, so the 2.1 stage engine may also
consume it directly. Stockpile kind `product` is reserved for 2.3's sized stockpiles.

## Consequences

- One more licensed, switchable module; `AccessGuardInterceptor.MODULE_ENV_FLAGS` is now at `Map.of`'s 10-pair limit —
  the next module must switch it to `Map.ofEntries`.
- ROM stone carries a real cost per tonne into crushing; blasting cost spreads over the tonnes it broke.
- Not built (later phases): stockpile survey adjustments (2.3), payroll-sourced actual labour cost (rates are used),
  telemetry-fed hour meters, the fuel actually issued to plant from fleet tanks (litres × rate is used), demo seed.
