package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.WeighbridgeCommands.ReportQuery;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weighbridge discrepancy report (fleet 1.4): completed tickets of a period grouped by product, customer / supplier,
 * station, truck, subject, day or month — tickets, compared tickets, expected vs net kg, signed and absolute
 * variance, out-of-tolerance (approved exceptions), manual weighings and stored-tare tickets. Voided tickets are left
 * out (their reissue counts). Period on the completion day (business time zone).
 */
@Service
public class WeighbridgeReportService {

    private static final Map<String, String[]> GROUPS = Map.of(
            "product", new String[] {"t.\"productId\"", "COALESCE(p.name, '(no product)')"},
            "partner", new String[] {"t.\"partnerId\"", "COALESCE(pa.name, '(no customer / supplier)')"},
            "station", new String[] {"t.\"stationId\"", "s.code || ' — ' || s.name"},
            "vehicle", new String[] {"t.\"registrationPlate\"", "t.\"registrationPlate\""},
            "subject", new String[] {"t.\"subjectType\"", "t.\"subjectType\""},
            "day", new String[] {"to_char(t.\"completedAt\", 'YYYY-MM-DD')", "to_char(t.\"completedAt\", 'YYYY-MM-DD')"},
            "month", new String[] {"to_char(t.\"completedAt\", 'YYYY-MM')", "to_char(t.\"completedAt\", 'YYYY-MM')"});

    private final FleetDb db;

    public WeighbridgeReportService(FleetDb db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> discrepancies(ReportQuery q) {
        String groupBy = q.groupBy() == null || q.groupBy().isBlank() ? "product" : q.groupBy();
        String[] g = GROUPS.get(groupBy);
        if (g == null) {
            throw ApiException.badRequest("Group by one of: product, partner, station, vehicle, subject, day, month");
        }
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.status = 'completed'");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "t.\"stationId\"", q.stationId());
        FleetIncidentService.eq(where, params, "t.\"subjectType\"", q.subjectType());
        FleetIncidentService.eq(where, params, "t.\"partnerId\"", q.partnerId());
        FleetIncidentService.eq(where, params, "t.\"productId\"", q.productId());
        FleetIncidentService.range(where, params, "t.\"completedAt\"", q.from(), q.to());
        String from = " FROM \"WeighbridgeTicket\" t JOIN \"WeighbridgeStation\" s ON s.id = t.\"stationId\""
                + " LEFT JOIN \"Product\" p ON p.id = t.\"productId\" AND p.\"organizationId\" = t.\"organizationId\""
                + " LEFT JOIN \"Partner\" pa ON pa.id = t.\"partnerId\" AND pa.\"organizationId\" = t.\"organizationId\"";
        String measures = "count(*) AS tickets, count(t.\"expectedKg\") AS compared,"
                + " COALESCE(sum(t.\"expectedKg\"), 0) AS \"expectedKg\", COALESCE(sum(t.\"netKg\"), 0) AS \"netKg\","
                + " COALESCE(sum(t.\"netKg\") FILTER (WHERE t.\"expectedKg\" IS NOT NULL), 0) AS \"comparedNetKg\","
                + " COALESCE(sum(t.\"varianceKg\"), 0) AS \"varianceKg\", COALESCE(sum(abs(t.\"varianceKg\")), 0) AS"
                + " \"absVarianceKg\", count(*) FILTER (WHERE t.\"withinTolerance\" = false) AS \"outOfTolerance\","
                + " count(*) FILTER (WHERE t.\"isManual\") AS manual, count(*) FILTER (WHERE t.\"usedStoredTare\") AS"
                + " \"storedTare\"";
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT " + g[0] + " AS key, " + g[1] + " AS label, "
                + measures + from + where + " GROUP BY 1, 2 ORDER BY \"absVarianceKg\" DESC, tickets DESC, 2")
                .params(params))) {
            groups.add(row(r));
        }
        Map<String, Object> totals = row(SqlRows.one(db.jdbc().sql("SELECT NULL AS key, 'Total' AS label, " + measures
                + from + where).params(params)));
        return Obj.of("groupBy", groupBy, "from", q.from(), "to", q.to(), "groups", groups, "totals", totals);
    }

    private static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", r.get("key"));
        m.put("label", r.get("label"));
        for (String k : List.of("tickets", "compared", "outOfTolerance", "manual", "storedTare")) {
            m.put(k, ((Number) r.get(k)).longValue());
        }
        BigDecimal expected = SqlRows.dec(r, "expectedKg");
        BigDecimal variance = SqlRows.dec(r, "varianceKg");
        m.put("expectedKg", expected);
        m.put("netKg", SqlRows.dec(r, "netKg"));
        m.put("comparedNetKg", SqlRows.dec(r, "comparedNetKg"));
        m.put("varianceKg", variance);
        m.put("absVarianceKg", SqlRows.dec(r, "absVarianceKg"));
        m.put("variancePct", expected.signum() == 0 ? null
                : variance.multiply(new BigDecimal("100")).divide(expected, 3, RoundingMode.HALF_UP));
        return m;
    }
}
