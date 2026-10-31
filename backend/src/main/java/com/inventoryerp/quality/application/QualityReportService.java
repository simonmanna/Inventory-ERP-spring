package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.quality.application.QualityCommands.ReportQuery;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quality reports (ADR-S013 §3): lots opened in a period (business dates), grouped by product, batch, work centre
 * (line) or supplier — lots, evaluated, passed, failed, pass rate (passed / evaluated, %), rejected and downgraded
 * quantities, and the NCRs raised for those lots.
 */
@Service
public class QualityReportService {

    private static final Map<String, String[]> GROUPS = Map.of(
            "product", new String[] {"l.\"productId\"", "COALESCE(p.name, l.\"productId\")"},
            "batch", new String[] {"CONCAT(l.\"productId\", '|', COALESCE(l.\"batchNumber\", ''))",
                    "CONCAT(COALESCE(p.name, ''), ' · ', COALESCE(l.\"batchNumber\", '—'))"},
            "work_center", new String[] {"COALESCE(l.\"workCenterId\", '')", "COALESCE(wc.name, '—')"},
            "supplier", new String[] {"COALESCE(l.\"supplierPartnerId\", '')", "COALESCE(pa.name, '—')"});

    private final QualityDb db;

    public QualityReportService(QualityDb db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(ReportQuery q) {
        String groupBy = Q.has(q.groupBy()) ? q.groupBy() : "product";
        String[] g = GROUPS.get(groupBy);
        if (g == null) {
            throw ApiException.badRequest("groupBy must be one of product, batch, work_center, supplier");
        }
        LocalDate to = q.to() != null ? q.to() : LocalDate.now(BusinessZone.get());
        LocalDate from = q.from() != null ? q.from() : to.minusDays(30);
        if (from.isAfter(to)) {
            throw ApiException.badRequest("from must not be after to");
        }
        var zone = BusinessZone.get();
        java.sql.Timestamp start = SqlRows.ts(from.atStartOfDay(zone).toInstant());
        java.sql.Timestamp end = SqlRows.ts(to.plusDays(1).atStartOfDay(zone).toInstant());
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.status <> 'cancelled'"
                + " AND l.\"createdAt\" >= ? AND l.\"createdAt\" < ?");
        // The NCR count sub-select binds the org first, then the WHERE clause.
        List<Object> params = new ArrayList<>(List.of(QualityDb.org(), QualityDb.org(), start, end));
        Q.eq(where, params, "l.stage", q.stage());
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT " + g[0] + " AS key, " + g[1] + " AS label,"
                + " count(*) AS lots, count(*) FILTER (WHERE l.result <> 'pending') AS evaluated,"
                + " count(*) FILTER (WHERE l.result = 'pass') AS passed, count(*) FILTER (WHERE l.result = 'fail') AS failed,"
                + " COALESCE(SUM(l.quantity), 0) AS quantity, COALESCE(SUM(l.\"rejectedQty\"), 0) AS \"rejectedQty\","
                + " COALESCE(SUM(l.\"downgradedQty\"), 0) AS \"downgradedQty\", COALESCE(SUM(l.\"releasedQty\"), 0)"
                + " AS \"releasedQty\", COALESCE(SUM(nc.c), 0) AS \"ncrCount\""
                + " FROM \"QualityInspectionLot\" l"
                + " LEFT JOIN \"Product\" p ON p.id = l.\"productId\" AND p.\"organizationId\" = l.\"organizationId\""
                + " LEFT JOIN \"WorkCenter\" wc ON wc.id = l.\"workCenterId\" AND wc.\"organizationId\" = l.\"organizationId\""
                + " LEFT JOIN \"Partner\" pa ON pa.id = l.\"supplierPartnerId\" AND pa.\"organizationId\" = l.\"organizationId\""
                + " LEFT JOIN (SELECT \"lotId\", count(*) AS c FROM \"QualityNcr\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND"
                + " \"lotId\" IS NOT NULL GROUP BY \"lotId\") nc ON nc.\"lotId\" = l.id"
                + where + " GROUP BY 1, 2 ORDER BY 2").params(params));
        long lots = 0;
        long evaluated = 0;
        long passed = 0;
        long failed = 0;
        long ncrs = 0;
        for (Map<String, Object> r : rows) {
            long ev = ((Number) r.get("evaluated")).longValue();
            long ps = ((Number) r.get("passed")).longValue();
            r.put("passRate", rate(ps, ev));
            r.put("defectQty", Q.dec(r, "rejectedQty").add(Q.dec(r, "downgradedQty")));
            lots += ((Number) r.get("lots")).longValue();
            evaluated += ev;
            passed += ps;
            failed += ((Number) r.get("failed")).longValue();
            ncrs += ((Number) r.get("ncrCount")).longValue();
        }
        return Obj.of("groupBy", groupBy, "stage", q.stage(), "from", from, "to", to, "rows", rows, "totals",
                Obj.of("lots", lots, "evaluated", evaluated, "passed", passed, "failed", failed, "passRate",
                        rate(passed, evaluated), "ncrCount", ncrs), "zone", zone.getId());
    }

    private static BigDecimal rate(long passed, long evaluated) {
        return evaluated == 0 ? null : BigDecimal.valueOf(passed * 100L).divide(BigDecimal.valueOf(evaluated), 1,
                RoundingMode.HALF_UP);
    }
}
