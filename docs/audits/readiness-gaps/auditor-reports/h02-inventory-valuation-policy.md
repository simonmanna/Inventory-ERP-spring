# Auditor report: inventory valuation policy (read-only audit of main 46281ee2, 2026-11-17)

Line numbers refer to 46281ee2. Findings fixed since then are listed in [HANDOFF.md](../HANDOFF.md).

VERDICT (inventory valuation config & value-only changes): Ready after fixes. State machines, idempotency, unique active keys, fiscal/costing locks and job isolation are sound. But tracking flags bypass the method lock (P0), and activations and revaluation reversals can leave quants out of line with the GL.

FINDINGS (Java paths are under backend/src/main/java/com/inventoryerp/)

F1 [P0] Lot/serial/expiry tracking can be switched while stock is on hand
  where: core/application/ProductService.java:55-56, 212-216, 414-439, 489-501; inventory/application/StockService.java:526, 628-636, 678-682, 715; web/src/pages/products/ProductEditPage.tsx:904-912
  what: Only costingMethod is guarded by hasStockOnHand. The batch, expiry and serial flags change through PATCH /products/{id} (PRODUCT__UPDATE, plain checkboxes) with no re-costing, and the engine switches costing path at once. No other code writes these fields: the variant and sizing services touch other columns.
  scenario: An AVCO item has 100 on hand at avg 8, costPrice 5. Tick expiry, which forces batch. No lots exist, so every issue takes the layer path and overflows at costPrice (680-681). COGS is 5 instead of 8, or there is no GL entry at all when costPrice is 0 (line 715). Valuation still drops at 8, so the tie-out exception blocks close. With stockPolicy=block, or allowNegativeStock=false for non-POS issues, issues are refused with "on hand 0". Unticking batch on an AVCO lot item silently re-values from Σ lot cost to qty × avg, with no JE, because avg is not re-derived after lot issues. Turning serial on does the same through the serial overflow at costPrice (628-636).
  confidence: confirmed; test covering it: none (InventoryHardeningIT.costingMethodGuard covers costingMethod only); known: new

F2 [P1] Policy/standard activation doesn't serialize with in-flight stock moves
  where: inventory/application/StockService.java:233-265 vs 290, 480/526 vs 523; InventoryCostPolicyService.java:244, 315-319; StandardCostService.java:403-405, 452-454
  what: Lock order today:
    - Policy activation: policy row FOR UPDATE, then `cost-policy:<org>`, then StockItem FOR UPDATE (unordered).
    - Standard activation: standard row, then Product, then StockItem (ordered).
    - Engine: `avco:` advisory lock, then StockItem.
    The engine reads method, batchTracking and the active standard with plain SELECTs before taking its locks. Activation never takes the `avco:` keys. So a move that read first waits on the StockItem row, then runs with the old method.
  scenario:
    - AVCO→FIFO with a POS drain in flight: the drain issues at avg and leaves the new OPEN lot whole, so lots exceed on-hand.
    - FIFO→AVCO: the resumed issue finds only retired lots and overflows at costPrice.
    - Standard 10→12 with a receipt in flight: GL at 10, valuation at 12.
    - Receipts that create a new quant don't wait at all.
    The exposure lasts the whole activation transaction; a category or org policy spans every product in scope. The unordered FOR UPDATE can also deadlock with transfers (409).
  confidence: likely (path confirmed, timing-dependent); test covering it: none (StockConcurrencyIT has no activation case); known: new

F3 [P1] Duplicate pool lines in one revaluation/NRV document
  where: inventory/application/InventoryRevaluationService.java:136-164, 209-215, 223-224, 247-254, 272-273, 302-309; backend/src/main/resources/db/migration/V224__cm1_valuation_integrity.sql:92-106
  what: Lines for the same (product, variant, location) are accepted: there is no check and no unique key. NRV lines all read the same allowanceBefore, because the value-only rows are written after the loop. Revaluation post applies the lines in sequence, but reverse restores unitCostBefore in unordered heap order.
  scenario: An NRV document lists one pool twice (carrying 10, NRV 6, qty 100). That gives two −400 lines and an allowance of −800, so stock is carried at 2. Separately, a revaluation with lines →12 and →15 on one pool is reversed. If the first-posted line is restored first, the quant ends at 12 while the GL and ledger are back at 10.
  confidence: confirmed; test covering it: none; known: new

F4 [P1] Revaluation reverse overwrites later value-only changes
  where: inventory/application/InventoryRevaluationService.java:283-294, 302-309; post (203-221) skips create's checks at 143-150
  what: Reverse refuses only if quantity moved since postedAt, then sets quant and lots to the absolute unitCostBefore. Later value-only changes vanish from the quant while their GL and ledger rows stay: another revaluation, a landed-cost raiseAverage, or a re-costing. Post also doesn't re-check STANDARD/serial.
  scenario: REV-1 takes 10→12, landed cost adds 1 (avg 13), then REV-1 is reversed. The quant is 10 but the GL is 11, so the tie-out is off by qty. Or the item is converted to STANDARD between create and post: the GL moves and the valuation doesn't.
  confidence: confirmed; test covering it: ValuationIntegrityIT covers only post→reverse with nothing in between; known: new

F5 [P1] No SoD on valuation changes; offset accounts bypass the control-account policy
  where: kernel/approvals/SegregationOfDutiesService.java:51-66; inventory/application/InventoryRevaluationService.java:124-127; LineageExceptionService.java:162-165; accounting/application/PostingService.java:136-138; AccountResolverService.java:261-269; inventory/application/InventoryCostPolicyService.java:184-201
  what:
    - There is no SoD pair for revaluation, standard cost, cost policy or lineage write-off, and revaluation has no approval step.
    - The user-chosen offset account is only checked for existence, then posted in system context. System context accepts AR/AP control accounts and allowManualPosting=false accounts, which the V518 control-account policy otherwise blocks.
    - Policy update checks status, then UPDATEs with no status predicate or row lock, so an edit can land after approval.
  scenario: One user with INVENTORY_VALUATION__MANAGE+POST revalues stock by +10,000 against AR control or bank. The subledger or bank no longer ties, and nobody else signs off.
  confidence: confirmed; test covering it: none; known: new

F6 [P2] Standard activation and policy rounding post to the mapped stock_valuation
  where: inventory/application/StandardCostService.java:470; InventoryCostPolicyService.java:449; vs StockPostingService.java:343-353
  what: Moves, revaluation and landed cost post to the item's STOCK_IN rule account. Standard and policy re-costing always post to the mapping.
  scenario: A category has inventory account 1310, so receipts debit 1310, while standard 10→12 debits 1300. Per-account balances drift, and 1300 can go negative. The all-accounts tie-out (accounting/application/InventoryValuationService.java:132-159) hides this.
  confidence: confirmed; test covering it: none; known: new

F7 [P2] Activation jobs retry permanent failures silently
  where: inventory/application/InventoryCostPolicyService.java:265-284, 440-446, 483-484; StandardCostService.java:360-370, 407-410, 522-542
  what:
    - Failures are only logged with log.warn; other jobs also call metrics.jobFailed.
    - Standard approve doesn't check convertMethod against the item's current method.
    - Policy effectiveFrom accepts any past date, and the rounding JE is dated at it. The Javadoc's "never earlier than today's lock" is not implemented.
  scenario: Approve a standard for an AVCO item without convertMethod. The job then fails every hour until someone cancels it, with no alert, and the item keeps its old cost. A policy dated in a closed period never activates.
  confidence: confirmed; test covering it: none; known: new

F8 [P2] Posting-rule structure isn't validated; rule edits shift the tie-out
  where: inventory/application/PostingRuleAdminService.java:136-149; InventoryPostingRuleService.java:73-77, 162-181, 187-188; StockPostingService.java:651-661; accounting/application/InventoryValuationService.java:137-150
  what:
    - account_mapping rules accept a null key. Literal accounts are checked at save and again at post.
    - Overrides are deduplicated per side, so a product or category rule with no account shadows the default and produces a one-sided entry. PostingService refuses it, and every move of that type for the item fails, POS drains included.
    - The mapping fallback applies only when no rule exists at all.
    - The tie-out reads only active STOCK_IN debit rules, so repointing that rule drops the old account's balance from it.
    - Suspected: moving the STOCK_IN credit off grni_accrued strands GRNI, because bills clear the mapped account (StockPostingService.java:204).
  scenario: Add a product rule debit:0 with accountSource=account_mapping and no key. All sales of that item now fail to post.
  confidence: confirmed (GRNI suspected); test covering it: InventoryIT posting-rule tests cover literal-account checks only; known: new

F9 [P2] Ledger value rows don't match the quant change
  where: inventory/application/StandardCostService.java:466-487; InventoryCostPolicyService.java:382-393; InventoryRevaluationService.java:217-218
  what: Per-quant value-only rows are written only when the product's net difference is at least 0.01. Revaluation books amounts rounded to 2 dp while the quant moves exactly.
  scenario: A standard activation raises location A by 50 and lowers B by 50. No ledger rows are written, so the per-location ledger and CostExplanation disagree with the quants. Rounding residues accumulate with each revaluation.
  confidence: confirmed; test covering it: none; known: new

F10 [P2] Lineage exception assign: wrong account and no release record
  where: inventory/application/LineageExceptionService.java:105-152, 185-196; CostLineageService.java:104-110; accounting/application/CostingDimensionGuard.java:26-31
  what: Assign releases scrap shares to COGS, while the automatic path uses stock_adjustment_expense. Assign also records no CostValueAdjustmentRelease. lineage_exception journals are outside COSTING_SOURCES (dated today, so low impact). Double assign and double write-off are blocked by FOR UPDATE + status.
  scenario: A parked transfer share is assigned to a destination that later scrapped stock. The scrap share lands in COGS, and the adjustment's open amount drops with no release record.
  confidence: confirmed; test covering it: LandedCostLineageIT (basic cases only); known: ACR-14

RE-CHECKED EARLIER ITEMS:
- ACR-14: still open. Assign (LineageExceptionService.java:105-152) records no release; write-off does (173-177).
- ACR-6 / CM-10 (costing reversals): fixed here.
  - InventoryRevaluationService.java:297-300 and StandardCostService.java:239 use CostingReversals.
  - CostingDimensionGuard.java:26-31 covers inventory_revaluation, inventory_nrv, standard_cost and cost_policy.
  - lineage_exception is still missing from that list.
- V518 control accounts: holds for manual journals, but is bypassed through system-context valuation postings (F5).
- Spec inventory item 17 (method lock): holds for costingMethod only (ProductService.java:489-501); the tracking flags are open (F1).
- Spec item 27 (activation on the org calendar): holds. StandardCostService.java:531-533; InventoryCostPolicyService.java:275.

STRENGTHS:
- Every transition is a FOR UPDATE + status compare-and-set. Post, reverse, release, assign and write-off are @Idempotent, with deterministic posting keys where it matters.
- Partial unique indexes (V12__costing_and_cost_centers.sql:121, V13__standard_and_process_costing.sql:37) prevent double activation even without ShedLock.
- The jobs use ShedLock and one TransactionTemplate per item under TenantContext.run, so RLS is correct and one failure doesn't stop the run.
- Fiscal and costing locks apply to revaluation, NRV, standard and policy postings. Revaluation and landed cost take the engine's `avco:` key.
- Lineage distribution is exact (Σ shares = amount). Shares it cannot resolve are parked as exceptions that gate period close (accounting/application/PeriodCloseChecklistService.java:125).

TEST GAPS:
- Tracking-flag toggles with stock on hand, followed by an issue and glTieOut.
- Policy or standard activation racing an issue, receipt or transfer.
- Revaluation/NRV with two lines for one pool; reverse after a later landed cost or revaluation.
- Job failure paths (unconvertible approved standard, policy dated in a closed period); per-account tie-out with category inventory accounts.
- Posting rules with a null key or a one-sided override; repointing the STOCK_IN rule versus the tie-out and GRNI.
