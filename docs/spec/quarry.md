# Quarry & extraction — `quarry`

New module of the construction-materials build (Track C Phase 2.2, no NestJS counterpart). Design:
[ADR-S012](../adr/ADR-S012-quarry-and-extraction.md). Requirements: [§3.1](../requirements/construction-materials-erp.md).
Feature flag `app.modules.quarry` (`ENABLE_QUARRY`), licence feature `quarry`, module manifest `quarry`.

## Purpose

Run the pit: register quarries and their zones / benches / faces and material grades; plan extraction targets; log
each shift's extraction (loads × truck capacity or weighbridge tonnage, mobile plant and operators, labour, fuel,
losses) and post it into a **ROM stockpile** (inventory location) at its **cost per tonne**; record drilling & blasting
with **explosives as controlled stock** (approval-gated issues from licensed magazines, full audit); record haulage from
the face to the ROM pad / crusher; report extraction vs target, cost per tonne, losses and royalties.

## Functional areas

| Area | What |
|---|---|
| Master data | Sites (mining licence), zone → bench → face, materials (rock type, grade, density, stock product), stockpiles (ROM / product, inventory locations) |
| Plans | Extraction plans and targets per site / zone (+ children) / material and period; actual, pace, cost per tonne vs budget |
| Extraction | Daily logs by shift; quantity lines (loads, weighbridge, haulage, survey); equipment / labour lines; losses; cost preview; post → ROM stock + GL; reverse |
| Haulage | Face → ROM stockpile / crusher / waste dump; loads × capacity or weighbridge ticket; optional fleet trip reference |
| Blasting | Pattern, drilling, compliance; planned → fired → closed / cancelled; cost pool absorbed per tonne |
| Explosives | Magazines (licence, keeper), controlled-explosive registry, issue request → approval → issue → reconcile |
| Reports | Extraction vs target, cost per tonne by site / period, losses, royalties & levies; dashboard overview |
| Settings | Allocation rule (net / gross tonnes), fallback fuel / labour / equipment rates, explosive SoD, plant rates, royalty rates |

## Routes (80, all under `/api/v1/quarry`, `@RequiresModule("quarry")`)

Master-data `PATCH` routes take the same body as their `POST` and replace every editable field. Money / stock routes
(marked **I**) require an `Idempotency-Key`.

| Verb | Path | Permission |
|---|---|---|
| GET | `/overview` | quarry_extraction:read |
| GET | `/lookups`, `/lookups/employees`, `/lookups/products`, `/lookups/partners`, `/lookups/weighbridge-tickets` | quarry_site:read |
| GET | `/rom-stock` | quarry_extraction:read |
| GET / PUT | `/settings` | quarry:settings |
| GET / POST | `/equipment-rates` | quarry:settings |
| PATCH / DELETE | `/equipment-rates/{id}` | quarry:settings |
| GET / POST | `/royalty-rates` | quarry:settings |
| PATCH / DELETE | `/royalty-rates/{id}` | quarry:settings |
| GET | `/sites`, `/sites/{id}`, `/sites/{id}/zones` | quarry_site:read |
| POST / PATCH / DELETE | `/sites`, `/sites/{id}` | quarry_site:create / update / delete |
| POST / PATCH / DELETE | `/zones`, `/zones/{id}` | quarry_site:create / update / delete |
| GET | `/materials` | quarry_site:read |
| POST / PATCH / DELETE | `/materials`, `/materials/{id}` | quarry_site:create / update / delete |
| GET | `/stockpiles` | quarry_site:read |
| GET | `/stockpiles/{id}` (stock + movements) | quarry_extraction:read |
| POST / PATCH | `/stockpiles`, `/stockpiles/{id}` | quarry_site:create / update |
| GET | `/extraction-plans`, `/extraction-plans/{id}` | quarry_extraction:read |
| POST / PATCH / DELETE | `/extraction-plans`, `/extraction-plans/{id}` | quarry_extraction:plan |
| GET | `/extraction-logs`, `/extraction-logs/{id}`, `/extraction-logs/{id}/cost-preview` | quarry_extraction:read |
| POST **I** | `/extraction-logs` (`post: true` also needs quarry_extraction:post) | quarry_extraction:create |
| PATCH / DELETE | `/extraction-logs/{id}` (drafts) | quarry_extraction:update |
| POST **I** | `/extraction-logs/{id}/post`, `/extraction-logs/{id}/reverse` | quarry_extraction:post |
| GET | `/losses` | quarry_extraction:read |
| POST | `/losses` (standalone) | quarry_extraction:create |
| DELETE | `/losses/{id}` (standalone) | quarry_extraction:update |
| GET | `/haulage-trips`, `/haulage-trips/{id}` | quarry_extraction:read |
| POST / PATCH / DELETE | `/haulage-trips`, `/haulage-trips/{id}` | quarry_extraction:haul |
| GET | `/blasts`, `/blasts/{id}` | quarry_blasting:read |
| POST | `/blasts` | quarry_blasting:create |
| PATCH | `/blasts/{id}`; POST `/blasts/{id}/fire`, `/close`, `/cancel` | quarry_blasting:update |
| GET | `/magazines`, `/magazines/{id}`, `/explosives` | quarry_blasting:read |
| POST / PATCH | `/magazines`, `/magazines/{id}`, `/explosives`, `/explosives/{id}` | quarry_blasting:magazine |
| GET | `/explosive-issues`, `/explosive-issues/{id}` | quarry_blasting:read |
| POST | `/explosive-issues`, `/explosive-issues/{id}/sync`, `/explosive-issues/{id}/cancel` | quarry_blasting:request_explosive |
| POST **I** | `/explosive-issues/{id}/issue`, `/explosive-issues/{id}/reconcile` | quarry_blasting:issue_explosive |
| GET | `/reports/extraction-vs-target`, `/reports/cost-per-tonne`, `/reports/losses`, `/reports/royalties` | quarry:report |

The explosive request is decided in the generic approval inbox (`POST /approvals/{id}/decide`, approver permission
`quarry_blasting:approve_explosive`).

## Permissions

`quarry:report`, `quarry:settings`, `quarry_site:{read,create,update,delete}`,
`quarry_extraction:{read,create,update,post,plan,haul}`,
`quarry_blasting:{read,create,update,request_explosive,approve_explosive,issue_explosive,magazine}` (19, granted to the
Administrator roles by the migration). Every quarry role also needs `quarry_site:read`.

## Data model (V28)

`QuarrySettings` (one per org), `QuarrySite`, `QuarryZone` (kind zone / bench / face, self parent), `QuarryMaterial`
(→ inventory product), `QuarryStockpile` (→ inventory location, kind rom / product, default per site),
`QuarryEquipmentRate` (vehicle or fleet class), `QuarryRoyaltyRate` (site / material optional, net or gross basis,
effective-dated, authority partner), `QuarryExtractionPlan`, `QuarryMagazine` (→ inventory location, licence, keeper),
`QuarryExplosive` (→ inventory product, controlled), `QuarryBlast`, `QuarryExplosiveIssue` + `QuarryExplosiveIssueLine`,
`QuarryHaulage`, `QuarryExtractionLog` (cost snapshot when posted) + `QuarryExtractionLoad` (`active` flag; partial
unique indexes on weighbridge ticket and haulage) + `QuarryExtractionResource` + `QuarryExtractionCost` (build-up),
`QuarryLoss`. All org-scoped with the `tenant_isolation` RLS policy.

## State machines

- Extraction log: draft → posted → reversed (drafts editable / deletable).
- Blast: planned → fired → closed; planned → cancelled (no explosives issued; open requests cancelled).
- Explosive issue: pending_approval → approved | rejected; approved → issued → reconciled; pending_approval / approved →
  cancelled.

## Business rules

- Equipment lines: fleet vehicles of a `mobile_plant` class, not disposed. Trucks: capacity entered or the vehicle's
  payload. Weighbridge tickets: completed, subject `internal_haulage` / `other`, net weight > 0, used by one active
  haulage record or quantity line only; re-checked at posting (a voided ticket blocks it).
- Losses < gross tonnes; net = gross − losses. Rates: line override → vehicle rate → class rate → settings.
- Blast share = pool / estimated tonnes × net tonnes, capped at the pool's remainder; only a fired blast absorbs.
- Firing needs shotfirer, shotfirer licence, blast permit (valid on the firing day), exclusion zone and estimated tonnes;
  no request still pending / approved. Closing needs every issue reconciled.
- Explosive requests need an active approval workflow (default created on first use); the magazine must be active and
  licensed on the day; issue needs `quarry_blasting:issue_explosive` and (setting, default on) a person other than the
  requester; stock refused when short; returns ≤ issued.
- Materials must be AVCO / FIFO products (not standard-costed), stock-tracked and not serial-tracked.

## Events

`quarry.extraction.posted` (organizationId, extractionLogId, siteId, stockpileId, locationId, materialId, productId,
netTonnes, unitCost, totalCost). Consumes `approval.decided` (entity `quarry_explosive_issue`).

## GL postings (posting engine, posting key per source, debits = credits, site cost centre)

| Event | Entry | Key |
|---|---|---|
| Extraction posted | Dr ROM stock (capitalised) [+ Dr 5363 losses] / Cr 5361 absorbed (fuel, labour, equipment, blasting, other) + Cr 2177 royalties & levies payable (per rate, authority partner) | `quarry:extraction:<id>` |
| Extraction reversed | posting-engine reversal of the above; stock out at its receipt cost | — |
| Explosives issued | Dr 6470 blasting & explosives (blast dimension) / Cr explosive stock | `quarry:explosive-issue:<id>` |
| Unused explosives returned | Dr explosive stock / Cr 6470 | `quarry:explosive-return:<id>` |

## Public API for crushing (2.3)

`QuarryStockpileService` — `stockpile`, `locationOf`, `romStock(siteId, kind)`, `onHand(stockpileId, productId)`,
`consume(ConsumeRequest)` (quantity-only issue, caller posts its GL). Event `quarry.extraction.posted`.

## Web

`/quarry` (overview), `/quarry/sites` + `/:id` (zones, stockpiles, magazines, plans), `/quarry/materials`,
`/quarry/plans`, `/quarry/extraction` + `/new` + `/:id` + `/:id/edit` (phone / tablet entry with sticky totals),
`/quarry/haulage`, `/quarry/losses`, `/quarry/stockpiles` + `/:id`, `/quarry/blasts` + `/:id` (fire / close / cancel,
request explosives), `/quarry/explosive-issues` + `/:id` (issue, reconcile, cancel, audit trail),
`/quarry/magazines` + `/:id`, `/quarry/reports`, `/quarry/settings`. Sidebar section "Quarry" (module feature
`quarry`), permission-aware entries; i18n for ti / lg / sw.

## Tests

`QuarryCalculatorsTest` (cost build-up, net / gross allocation and rounding, blast absorption cap, loads × capacity,
kg → t, target variance), `QuarryModuleGateTest` (module switched off → 403; licence feature), `QuarryIT` (extraction
→ ROM stock with cost, GL balanced, idempotent replay, reports; reversal releases evidence; gross-tonne allocation;
validation; explosive request → approval (no self-approval) → issue (authorisation, SoD, idempotent) → reconcile,
audit, blast absorption; concurrent issues on scarce stock; tenant isolation; authorization matrix of every route),
`LicensingIT.theQuarryModuleIsGatedByItsLicenceFeature` (402 without the `quarry` feature).

## Deviations

None (new module). Master-data `PATCH` replaces the editable fields (whole form) instead of the fleet `clear` list.

## Status

2.2 done (2026-10-30): all areas above built and tested. Not built: stockpile surveys / adjustments (2.3), actual
payroll labour cost and fleet-tank fuel actually issued to plant (rates × hours / litres are used), demo seed data.
