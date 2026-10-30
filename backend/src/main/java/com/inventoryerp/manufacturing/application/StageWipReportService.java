package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WIP valuation per stage, reconciled to the GL WIP account (ADR-S011 §6).
 *
 * <p>Expected GL WIP = Σ stage WIP of open multi-stage orders + material cost of single-leg orders in progress (their
 * consume leg debited WIP; labour / overhead reach WIP only at completion) + open process-costing periods (opening
 * cost + inputs). Intermediates already received into stage locations are inventory (stock valuation), listed
 * separately and not part of the GL WIP figure.
 */
@Service
public class StageWipReportService {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private final MfgDb db;
    private final StockPostingService stockPosting;

    public StageWipReportService(MfgDb db, StockPostingService stockPosting) {
        this.db = db;
        this.stockPosting = stockPosting;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> report() {
        String org = MfgDb.org();
        String wipAccount = stockPosting.wipAccount();
        BigDecimal gl = db.jdbc().sql("SELECT coalesce(sum(debit), 0) - coalesce(sum(credit), 0) FROM \"JournalLine\""
                + " WHERE \"organizationId\" = ? AND \"accountId\" = ?").params(org, wipAccount)
                .query(BigDecimal.class).single();

        List<Map<String, Object>> stageRows = SqlRows.list(db.jdbc().sql("SELECT s.id AS \"stageId\", s.\"orderId\","
                + " o.\"orderCode\", o.status AS \"orderStatus\", s.sequence, s.name, s.status, s.\"qcStatus\","
                + " s.\"outputProductId\", s.\"plannedOutputQty\", s.\"qtyGood\", s.\"qtyScrap\", s.\"materialCost\","
                + " s.\"transferredInCost\", s.\"conversionCost\", s.\"outputCost\", s.\"scrapCost\" FROM"
                + " \"ProductionOrderStage\" s JOIN \"ProductionOrder\" o ON o.id = s.\"orderId\" WHERE"
                + " s.\"organizationId\" = ? AND o.status IN ('confirmed', 'in_progress', 'qc_hold')"
                + " ORDER BY o.\"orderCode\", s.sequence").param(org));
        BigDecimal stageWip = BigDecimal.ZERO;
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Map<String, Object> s : stageRows) {
            BigDecimal wip = StageExecutionService.wip(s);
            stageWip = stageWip.add(wip);
            Map<String, Object> r = new LinkedHashMap<>(s);
            r.put("wipValue", wip);
            r.put("yieldPct", dec(s, "qtyGood").add(dec(s, "qtyScrap")).signum() > 0
                    ? dec(s, "qtyGood").multiply(BigDecimal.valueOf(100)).divide(dec(s, "qtyGood")
                            .add(dec(s, "qtyScrap")), 2, RoundingMode.HALF_UP) : null);
            stages.add(r);
        }

        List<Map<String, Object>> legacy = SqlRows.list(db.jdbc().sql("SELECT id, \"orderCode\", status,"
                + " \"materialCost\" AS wip, \"overheadCost\" AS \"unabsorbedConversion\" FROM \"ProductionOrder\" o"
                + " WHERE o.\"organizationId\" = ? AND o.status = 'in_progress' AND NOT EXISTS (SELECT 1 FROM"
                + " \"ProductionOrderStage\" s WHERE s.\"orderId\" = o.id) ORDER BY \"orderCode\"").param(org));
        BigDecimal legacyWip = legacy.stream().map(r -> dec(r, "wip")).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal processWip = db.jdbc().sql("SELECT coalesce(sum(p.\"openingMaterialCost\""
                + " + p.\"openingConversionCost\" + p.\"openingTransferredInCost\"), 0) + coalesce((SELECT sum(e.amount)"
                + " FROM \"ProcessCostEntry\" e JOIN \"ProcessCostPeriod\" q ON q.id = e.\"periodId\""
                + " WHERE e.\"organizationId\" = ? AND q.status = 'open'), 0)"
                + " FROM \"ProcessCostPeriod\" p WHERE p.\"organizationId\" = ? AND p.status = 'open'")
                .params(org, org).query(BigDecimal.class).single();

        List<Map<String, Object>> intermediates = SqlRows.list(db.jdbc().sql("SELECT DISTINCT ON (si.\"productId\","
                + " si.\"locationId\") si.\"productId\", p.name AS \"productName\", si.\"locationId\","
                + " l.name AS \"locationName\", si.quantity, si.\"runningAverageCost\" FROM \"ProductionOrderStage\" s"
                + " JOIN \"StockItem\" si ON si.\"organizationId\" = s.\"organizationId\""
                + " AND si.\"productId\" = s.\"outputProductId\" AND si.\"locationId\" = s.\"stageLocationId\""
                + " JOIN \"Product\" p ON p.id = si.\"productId\" JOIN \"InventoryLocation\" l ON l.id = si.\"locationId\""
                + " WHERE s.\"organizationId\" = ? AND s.\"isLast\" = false AND si.quantity <> 0"
                + " ORDER BY si.\"productId\", si.\"locationId\"").param(org));
        BigDecimal intermediateValue = BigDecimal.ZERO;
        for (Map<String, Object> i : intermediates) {
            BigDecimal v = dec(i, "quantity").multiply(dec(i, "runningAverageCost")).setScale(2, RoundingMode.HALF_UP);
            i.put("value", v);
            intermediateValue = intermediateValue.add(v);
        }

        BigDecimal expected = stageWip.add(legacyWip).add(processWip);
        BigDecimal drift = gl.subtract(expected);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("stageWip", JsNumbers.dec(stageWip));
        totals.put("legacyOrdersWip", JsNumbers.dec(legacyWip));
        totals.put("processCostingWip", JsNumbers.dec(processWip));
        totals.put("expectedWip", JsNumbers.dec(expected));
        totals.put("glWipBalance", JsNumbers.dec(gl));
        totals.put("drift", JsNumbers.dec(drift));
        totals.put("balanced", drift.abs().compareTo(CENT) <= 0);
        totals.put("intermediateStockValue", JsNumbers.dec(intermediateValue));
        return Obj.of("wipAccountId", wipAccount, "totals", totals, "stages", stages, "legacyOrders", legacy,
                "intermediates", intermediates, "byOrder", byOrder(stages));
    }

    private static List<Map<String, Object>> byOrder(List<Map<String, Object>> stages) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> s : stages) {
            Map<String, Object> o = out.computeIfAbsent(str(s, "orderId"), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderId", k);
                m.put("orderCode", s.get("orderCode"));
                m.put("orderStatus", s.get("orderStatus"));
                m.put("wipValue", BigDecimal.ZERO);
                return m;
            });
            o.put("wipValue", ((BigDecimal) o.get("wipValue")).add((BigDecimal) s.get("wipValue")));
        }
        return new ArrayList<>(out.values());
    }
}
