package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.InventoryIntegrityService;
import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityCheck;
import com.inventoryerp.inventory.application.InventoryPostingRuleService;
import com.inventoryerp.inventory.application.WipTieOutProvider;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IPR GL-11 / R4: the WIP tie-out per cost object, on the basis of docs/spec/manufacturing-wip-reconciliation.md
 * (WIP spec). Read-only.
 *
 * <p><b>WIP lines</b>: lines of posted or reversed journal entries on the mapped {@code wip} account and on the accounts
 * the organisation's active {@code PRODUCTION_CONSUME} (debit) / {@code PRODUCTION_OUTPUT} (credit) rules resolve to
 * (WIP-4; IPR GL-11 refinement: resolved as the postings resolve them, so a rule that takes its account from a product
 * category or product field contributes the accounts of the categories / products it covers — stock accounts excepted),
 * in base currency ({@code baseDebit − baseCredit}). A {@code reversal} entry counts for the entry it reverses. IPR
 * review 2 (R2-3): a rule account of a known non-asset type counts only for the lines a cost object explains.
 *
 * <p><b>Attribution</b> (WIP spec §5.2): single-stage order — sourceType {@code production_order},
 * {@code production_order_cancel}, {@code production_reversal}, {@code production_variance} with sourceId = its code;
 * multi-stage order (crushing runs, precast, processing jobs) — {@code production_stage},
 * {@code production_stage_reversal} with sourceId = one of its stage postings or their ledger codes; process costing —
 * {@code process_costing}, the process variance entries (posting key {@code inventory:production_variance:<org>:<period>}
 * of a {@code ProcessCostPeriod}) and the entries {@code ProcessCostEntry.journalEntryId} cites; everything else —
 * late costs (lineage shares in WIP and the cost adjustment runs that move them out) and anything unexplained.
 *
 * <p><b>Expected</b> (WIP spec §5.3): single-stage order in progress = {@code materialCost} + overhead its partial
 * outputs absorbed − WIP its partial outputs took (X-9); multi-stage order open (confirmed / in progress / QC hold) = Σ
 * stage WIP; every other order state = 0; process costing = Σ open periods (opening + entries), or the last closed
 * period's closing WIP for a process without an open period; late costs and the rest = Σ open late-cost shares in WIP
 * (lineage line or release, target {@code wip}, adjustment not reversed, no posted cost adjustment run line; any order
 * state — after IPR WIP-3 the run also takes the shares of cancelled and reversed orders). Quarry extraction never posts
 * to WIP; a WIP line of a {@code quarry_extraction*} entry is a failure of its own, reported once (IPR review 2 R2-2).
 * Tolerance 0.01 per object.
 */
@Service
public class WipTieOutService implements WipTieOutProvider {

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private static final Set<String> ORDER_SOURCES = Set.of("production_order", "production_order_cancel",
            "production_reversal", "production_variance");
    private static final Set<String> STAGE_SOURCES = Set.of("production_stage", "production_stage_reversal");
    private static final Set<String> OPEN_STAGED = Set.of("confirmed", "in_progress", "qc_hold");

    private final MfgDb db;
    private final InventoryPostingRuleService rules;

    public WipTieOutService(MfgDb db, InventoryPostingRuleService rules) {
        this.db = db;
        this.rules = rules;
    }

    /** One cost object of the tie-out. */
    public record WipObject(String kind, String orderId, String orderCode, String status, boolean staged,
            BigDecimal gl, BigDecimal expected, BigDecimal pendingLateCost) {

        public BigDecimal difference() {
            return gl.subtract(expected);
        }

        public boolean ok() {
            return difference().abs().compareTo(TOLERANCE) <= 0;
        }
    }

    /**
     * The whole tie-out: every object, the GL WIP total and the accounts read. {@code expensedAccounts}: rule accounts
     * that are not of asset type (IPR review 2 R2-3); {@code expensedOther}: Σ their lines no cost object explains,
     * left out of the totals.
     */
    public record Result(boolean wipMapped, List<String> accounts, List<WipObject> objects, BigDecimal glTotal,
            BigDecimal expectedTotal, BigDecimal quarryWipLines, List<String> expensedAccounts,
            BigDecimal expensedOther) {

        public List<WipObject> failing() {
            return objects.stream().filter(o -> !o.ok()).toList();
        }
    }

    @Transactional(readOnly = true)
    public Result tieOut() {
        String org = MfgDb.org();
        String wip = db.jdbc().sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'wip' AND \"deletedAt\" IS NULL LIMIT 1").param(org).query(String.class).optional().orElse(null);
        Set<String> accounts = new LinkedHashSet<>();
        if (wip != null) {
            accounts.add(wip);
        }
        // IPR GL-11: the rule accounts resolved as the postings resolve them, category / product field rules included.
        accounts.addAll(rules.ruleAccounts("PRODUCTION_CONSUME", "debit"));
        accounts.addAll(rules.ruleAccounts("PRODUCTION_OUTPUT", "credit"));
        // A rule that resolves to a stock account (a field rule falling back to stock valuation) is a misconfiguration
        // the stock tie-outs report; counting every stock movement as WIP would only hide it.
        accounts.removeAll(db.jdbc().sql("""
                SELECT "accountId" FROM "AccountMapping" WHERE "organizationId" = :org AND key = 'stock_valuation'
                   AND "deletedAt" IS NULL AND "accountId" IS NOT NULL
                UNION SELECT "inventoryAccountId" FROM "ProductCategory" WHERE "organizationId" = :org
                   AND "inventoryAccountId" IS NOT NULL
                UNION SELECT "inventoryAccountOverrideId" FROM "Product" WHERE "organizationId" = :org
                   AND "inventoryAccountOverrideId" IS NOT NULL""").param("org", org).query(String.class).list());
        List<WipObject> objects = new ArrayList<>();
        if (accounts.isEmpty()) {
            return new Result(false, List.of(), objects, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                    BigDecimal.ZERO);
        }
        List<String> accountList = List.copyOf(accounts);
        // IPR review 2 (R2-3): a rule account whose type is known and is not an asset (a rule that expenses consumption
        // or output directly) is not WIP. Its lines still count when a cost object explains them, so an order's
        // consumption ties as before; its other lines are left out and reported. The mapped wip account always counts.
        List<String> expensed = db.jdbc().sql("""
                SELECT a.id FROM "Account" a LEFT JOIN "AccountCategory" c ON c.id = a."categoryId"
                 WHERE a."organizationId" = :org AND a.id IN (:accts)
                   AND COALESCE(a."reportSection"::text, c."reportSection"::text) IS NOT NULL
                   AND COALESCE(a."reportSection"::text, c."reportSection"::text)
                       NOT IN ('current_assets', 'non_current_assets')""")
                .param("org", org).param("accts", accountList).query(String.class).list().stream()
                .filter(a -> !a.equals(wip)).toList();

        // ---- the WIP lines, one row per (entry, source) with its base amount
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("""
                SELECT e.id AS je, COALESCE(o."sourceType", e."sourceType") AS st,
                       COALESCE(o."sourceId", e."sourceId") AS sid, COALESCE(o."postingKey", e."postingKey") AS pk,
                       COALESCE(o.id, e.id) AS root, jl."accountId" IN (:expensed) AS expensed,
                       SUM(jl."baseDebit" - jl."baseCredit") AS amt
                  FROM "JournalLine" jl JOIN "JournalEntry" e ON e.id = jl."journalEntryId"
                  LEFT JOIN "JournalEntry" o ON o.id = e."reversalOfId" AND e."sourceType" = 'reversal'
                 WHERE e."organizationId" = :org AND e.status::text IN ('posted', 'reversed')
                   AND jl."accountId" IN (:accts)
                 GROUP BY 1, 2, 3, 4, 5, 6""").param("org", org).param("accts", accountList)
                .param("expensed", expensed.isEmpty() ? List.of("") : expensed));

        // ---- cost objects
        Map<String, Map<String, Object>> orderByCode = new HashMap<>();
        Map<String, Map<String, Object>> orderById = new LinkedHashMap<>();
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql("""
                SELECT o.id, o."orderCode", o.status::text AS status, o."materialCost",
                       EXISTS (SELECT 1 FROM "ProductionOrderStage" s WHERE s."orderId" = o.id) AS staged,
                       COALESCE((SELECT SUM(p."overheadAbsorbed" - p."wipCredit") FROM "ProductionPartialOutput" p
                                  WHERE p."orderId" = o.id AND p."organizationId" = o."organizationId"), 0) AS partial,
                       COALESCE((SELECT SUM("materialCost" + "transferredInCost" + "conversionCost" - "outputCost"
                                            - "scrapCost") FROM "ProductionOrderStage" s WHERE s."orderId" = o.id), 0)
                           AS stage_wip
                  FROM "ProductionOrder" o WHERE o."organizationId" = ?""").param(org))) {
            orderByCode.put(str(o, "orderCode"), o);
            orderById.put(str(o, "id"), o);
        }
        Map<String, String> stageRefToOrder = new HashMap<>();
        db.jdbc().sql("SELECT id, \"orderId\" FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ?").param(org)
                .query().listOfRows().forEach(r -> stageRefToOrder.put(str(r, "id"), str(r, "orderId")));
        db.jdbc().sql("SELECT l.\"ledgerCode\", p.\"orderId\" FROM \"ProductionStagePostingLine\" l JOIN"
                + " \"ProductionStagePosting\" p ON p.id = l.\"postingId\" WHERE l.\"organizationId\" = ? AND"
                + " l.\"ledgerCode\" IS NOT NULL").param(org).query().listOfRows()
                .forEach(r -> stageRefToOrder.putIfAbsent(str(r, "ledgerCode"), str(r, "orderId")));
        Set<String> periods = new HashSet<>(db.jdbc().sql("SELECT id FROM \"ProcessCostPeriod\" WHERE"
                + " \"organizationId\" = ?").param(org).query(String.class).list());
        Set<String> processJes = new HashSet<>(db.jdbc().sql("SELECT DISTINCT \"journalEntryId\" FROM"
                + " \"ProcessCostEntry\" WHERE \"organizationId\" = ? AND \"journalEntryId\" IS NOT NULL").param(org)
                .query(String.class).list());
        String variancePrefix = "inventory:production_variance:" + org + ":";

        Map<String, BigDecimal> glByOrder = new HashMap<>();
        BigDecimal glProcess = BigDecimal.ZERO;
        BigDecimal glOther = BigDecimal.ZERO;
        BigDecimal glTotal = BigDecimal.ZERO;
        BigDecimal quarry = BigDecimal.ZERO;
        BigDecimal expensedOther = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            BigDecimal amt = dec(l, "amt");
            String st = str(l, "st") == null ? "" : str(l, "st");
            String sid = str(l, "sid");
            String pk = str(l, "pk");
            String orderId = null;
            // IPR WIP-7: a process-costing variance entry carries the process code as its sourceId (the same for every
            // period); it is attributed by its posting key first, so a process whose code equals a production order's
            // code never lands its variance on that order.
            boolean processVariance = "production_variance".equals(st) && pk != null && pk.startsWith(variancePrefix)
                    && periods.contains(pk.substring(variancePrefix.length()));
            if (!processVariance && ORDER_SOURCES.contains(st) && sid != null && orderByCode.containsKey(sid)) {
                orderId = str(orderByCode.get(sid), "id");
            } else if (STAGE_SOURCES.contains(st) && sid != null && stageRefToOrder.containsKey(sid)) {
                orderId = stageRefToOrder.get(sid);
            }
            if (orderId != null) {
                glByOrder.merge(orderId, amt, BigDecimal::add);
            } else if ("process_costing".equals(st) || processJes.contains(str(l, "root")) || processVariance) {
                glProcess = glProcess.add(amt);
            } else if (st.startsWith("quarry_extraction")) {
                // IPR review 2 (R2-2): reported once, as its own object (it was also added to late cost and other).
                quarry = quarry.add(amt.abs());
            } else if (Boolean.TRUE.equals(l.get("expensed"))) {
                // IPR review 2 (R2-3): a line on a non-asset rule account that no cost object explains is an
                // ordinary expense posting, not WIP; reported in the note, out of the totals.
                expensedOther = expensedOther.add(amt);
                continue;
            } else {
                glOther = glOther.add(amt);
            }
            glTotal = glTotal.add(amt);
        }

        // ---- open late-cost shares in WIP, per order reference (WIP spec §5.3)
        Map<String, BigDecimal> lateByRef = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT x."targetRef" AS ref, SUM(x.amount - x."clearedAmount") AS amt
                  FROM "CostValueAdjustmentLine" x JOIN "CostValueAdjustment" a ON a.id = x."adjustmentId"
                 WHERE x."organizationId" = :org AND x.target = 'wip' AND a."reversedAt" IS NULL
                   AND NOT EXISTS (SELECT 1 FROM "CostAdjustmentRunLine" r WHERE r."adjustmentLineId" = x.id
                                    AND r.status = 'posted')
                 GROUP BY 1
                UNION ALL
                SELECT x."targetRef", SUM(x.amount - x."clearedAmount")
                  FROM "CostValueAdjustmentRelease" x JOIN "CostValueAdjustment" a ON a.id = x."adjustmentId"
                 WHERE x."organizationId" = :org AND x.target = 'wip' AND a."reversedAt" IS NULL
                   AND NOT EXISTS (SELECT 1 FROM "CostAdjustmentRunLine" r WHERE r."releaseId" = x.id
                                    AND r.status = 'posted')
                 GROUP BY 1""").param("org", org))) {
            lateByRef.merge(str(r, "ref") == null ? "" : str(r, "ref"), dec(r, "amt"), BigDecimal::add);
        }
        BigDecimal lateTotal = lateByRef.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expectedTotal = BigDecimal.ZERO;
        Set<String> ids = new LinkedHashSet<>(orderById.keySet());
        for (String id : ids) {
            Map<String, Object> o = orderById.get(id);
            boolean staged = Boolean.TRUE.equals(o.get("staged"));
            String status = str(o, "status");
            BigDecimal expected;
            if (staged) {
                expected = OPEN_STAGED.contains(status) ? dec(o, "stage_wip") : BigDecimal.ZERO;
            } else {
                expected = "in_progress".equals(status) ? dec(o, "materialCost").add(dec(o, "partial"))
                        : BigDecimal.ZERO;
            }
            BigDecimal gl = glByOrder.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal late = lateByRef.getOrDefault(id, BigDecimal.ZERO)
                    .add(lateByRef.getOrDefault(str(o, "orderCode"), BigDecimal.ZERO));
            if (gl.signum() == 0 && expected.signum() == 0 && late.signum() == 0) {
                continue;
            }
            expectedTotal = expectedTotal.add(expected);
            objects.add(new WipObject("order", id, str(o, "orderCode"), status, staged, gl, expected, late));
        }

        BigDecimal processExpected = db.jdbc().sql("""
                SELECT COALESCE(SUM(CASE WHEN p.status = 'open'
                                         THEN p."openingMaterialCost" + p."openingConversionCost"
                                              + p."openingTransferredInCost"
                                              + COALESCE((SELECT SUM(e.amount) FROM "ProcessCostEntry" e
                                                           WHERE e."periodId" = p.id), 0)
                                         ELSE p."closingWipCost" END), 0)
                  FROM "ProcessCostPeriod" p
                 WHERE p."organizationId" = :org
                   AND (p.status = 'open'
                        OR (NOT EXISTS (SELECT 1 FROM "ProcessCostPeriod" q WHERE q."processId" = p."processId"
                                         AND q.status = 'open')
                            AND p."periodFrom" = (SELECT MAX(q."periodFrom") FROM "ProcessCostPeriod" q
                                                   WHERE q."processId" = p."processId")))""")
                .param("org", org).query(BigDecimal.class).single();
        if (glProcess.signum() != 0 || processExpected.signum() != 0) {
            objects.add(new WipObject("process_costing", null, null, null, false, glProcess, processExpected,
                    BigDecimal.ZERO));
            expectedTotal = expectedTotal.add(processExpected);
        }
        if (glOther.signum() != 0 || lateTotal.signum() != 0) {
            objects.add(new WipObject("late_cost_and_other", null, null, null, false, glOther, lateTotal, lateTotal));
            expectedTotal = expectedTotal.add(lateTotal);
        }
        if (quarry.signum() != 0) {
            objects.add(new WipObject("quarry_extraction", null, null, null, false, quarry, BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }
        objects.sort(Comparator.comparing((WipObject w) -> w.difference().abs()).reversed());
        return new Result(wip != null, accountList, objects, glTotal, expectedTotal, quarry, expensed,
                expensedOther);
    }

    @Override
    public IntegrityCheck wipTieOut() {
        String invariant = "per production order / process / late cost: GL WIP (base currency) = its sub-ledger";
        Result r = tieOut();
        boolean anyMfg = !r.objects().isEmpty() || db.jdbc().sql("SELECT EXISTS (SELECT 1 FROM \"ProductionOrder\""
                + " WHERE \"organizationId\" = ?) OR EXISTS (SELECT 1 FROM \"ProcessCostPeriod\" WHERE"
                + " \"organizationId\" = ?)").params(MfgDb.org(), MfgDb.org()).query(Boolean.class).single();
        if (!anyMfg) {
            return new IntegrityCheck(InventoryIntegrityService.WIP_TIEOUT, true, true, invariant, TOLERANCE, 0, false,
                    List.of(), "No production order or process costing period");
        }
        if (!r.wipMapped()) {
            return new IntegrityCheck(InventoryIntegrityService.WIP_TIEOUT, false, false, invariant, TOLERANCE, 1,
                    false, List.of(Map.of("kind", "wip_unmapped")), "No account is mapped as wip (WIP-5)");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<WipObject> failing = r.failing();
        for (WipObject o : failing) {
            if (rows.size() == InventoryIntegrityService.ROW_LIMIT) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", o.kind());
            row.put("orderId", o.orderId());
            row.put("orderCode", o.orderCode());
            row.put("status", o.status());
            row.put("staged", o.staged());
            row.put("glWip", JsNumbers.dec(o.gl()));
            row.put("expectedWip", JsNumbers.dec(o.expected()));
            row.put("difference", JsNumbers.dec(o.difference()));
            row.put("pendingLateCost", JsNumbers.dec(o.pendingLateCost()));
            rows.add(row);
        }
        String note = "GL WIP " + JsNumbers.dec(r.glTotal()) + ", expected " + JsNumbers.dec(r.expectedTotal()) + " over "
                + r.objects().size() + " cost object(s); accounts " + r.accounts().size()
                + (r.expensedAccounts().isEmpty() ? "" : "; " + r.expensedAccounts().size() + " non-asset rule"
                        + " account(s) (consumption or output expensed directly): their lines no cost object explains ("
                        + JsNumbers.dec(r.expensedOther()) + ") are not WIP and are left out");
        return new IntegrityCheck(InventoryIntegrityService.WIP_TIEOUT, failing.isEmpty(), false, invariant, TOLERANCE,
                failing.size(), failing.size() > rows.size(), rows, note);
    }
}
