package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.ManufacturingCostingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Standard-cost variance analysis (ADR-S030 §2): every booked variance of the period by kind (favourable /
 * unfavourable), product, supplier and month, a GL tie-out of the variance accounts, and the production variances
 * computed on read by manufacturing (ADR-S018) for orders completed in the period.
 */
@Service
public class VarianceAnalysisService {

    /** Mapping keys of the booked variance accounts (ADR-S008). */
    static final List<String> VARIANCE_MAPPINGS = List.of("purchase_price_variance", "material_price_variance",
            "material_usage_variance", "labour_variance", "overhead_variance", "subcontract_variance",
            "production_yield_variance");

    private final JdbcClient jdbc;
    private final ManufacturingCostingService manufacturing;

    public VarianceAnalysisService(JdbcClient jdbc, ManufacturingCostingService manufacturing) {
        this.jdbc = jdbc;
        this.manufacturing = manufacturing;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> analysis(String fromRaw, String toRaw) {
        LocalDate to = toRaw == null || toRaw.isBlank() ? LocalDate.now(ZoneOffset.UTC)
                : MaterialLedgerService.day(toRaw, "to");
        LocalDate from = fromRaw == null || fromRaw.isBlank() ? to.withDayOfMonth(1)
                : MaterialLedgerService.day(fromRaw, "from");
        if (to.isBefore(from)) {
            throw com.inventoryerp.shared.web.ApiException.badRequest("to must not be before from");
        }
        Object start = AccountingDay.startTs(from, AccountingDay.zone(jdbc));
        Object end = AccountingDay.endExclusiveTs(to, AccountingDay.zone(jdbc));
        String range = " v.\"organizationId\" = ? AND v.\"createdAt\" >= ? AND v.\"createdAt\" < ?";

        List<Map<String, Object>> byKind = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal unfavourable = BigDecimal.ZERO;
        BigDecimal favourable = BigDecimal.ZERO;
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.kind, count(*) AS n,"
                + " coalesce(sum(v.amount) FILTER (WHERE v.amount > 0), 0) AS unfav,"
                + " coalesce(sum(v.amount) FILTER (WHERE v.amount < 0), 0) AS fav, coalesce(sum(v.amount), 0) AS net,"
                + " coalesce(sum(v.\"standardCost\"), 0) AS std FROM \"StandardCostVariance\" v WHERE" + range
                + " GROUP BY v.kind ORDER BY v.kind").params(org(), start, end))) {
            BigDecimal net = SqlRows.dec(r, "net");
            BigDecimal std = SqlRows.dec(r, "std");
            total = total.add(net);
            unfavourable = unfavourable.add(SqlRows.dec(r, "unfav"));
            favourable = favourable.add(SqlRows.dec(r, "fav"));
            byKind.add(Obj.of("kind", r.get("kind"), "count", ((Number) r.get("n")).longValue(), "unfavourable",
                    m(SqlRows.dec(r, "unfav")), "favourable", m(SqlRows.dec(r, "fav")), "net", m(net), "percentOfStandard",
                    std.signum() == 0 ? null : net.multiply(new BigDecimal("100")).divide(std, 2, RoundingMode.HALF_UP)
                            .toPlainString()));
        }
        List<Map<String, Object>> byProduct = rows(SqlRows.list(jdbc.sql("SELECT v.\"productId\" AS id, p.name, p.code,"
                + " coalesce(sum(v.amount), 0) AS net, coalesce(sum(v.amount) FILTER (WHERE v.kind = 'purchase_price'), 0)"
                + " AS price, coalesce(sum(v.amount) FILTER (WHERE v.kind <> 'purchase_price'), 0) AS production FROM"
                + " \"StandardCostVariance\" v LEFT JOIN \"Product\" p ON p.id = v.\"productId\" WHERE" + range
                + " GROUP BY 1, 2, 3 ORDER BY abs(coalesce(sum(v.amount), 0)) DESC LIMIT 100").params(org(), start, end)),
                "net", "price", "production");
        List<Map<String, Object>> bySupplier = rows(SqlRows.list(jdbc.sql("SELECT g.\"partnerId\" AS id, pa.name,"
                + " count(*) AS receipts, coalesce(sum(v.amount), 0) AS net FROM \"StandardCostVariance\" v JOIN"
                + " \"GoodsReceiptNote\" g ON g.id = v.\"sourceId\" AND g.\"organizationId\" = v.\"organizationId\" LEFT JOIN"
                + " \"Partner\" pa ON pa.id = g.\"partnerId\" WHERE" + range + " AND v.kind = 'purchase_price' AND"
                + " v.\"sourceType\" = 'goods_receipt' GROUP BY 1, 2 ORDER BY abs(coalesce(sum(v.amount), 0)) DESC LIMIT"
                + " 100").params(org(), start, end)), "net");
        List<Map<String, Object>> byMonth = rows(SqlRows.list(jdbc.sql("SELECT to_char(v.\"createdAt\", 'YYYY-MM') AS"
                + " month, coalesce(sum(v.amount), 0) AS net, coalesce(sum(v.amount) FILTER (WHERE v.kind ="
                + " 'purchase_price'), 0) AS price, coalesce(sum(v.amount) FILTER (WHERE v.kind <> 'purchase_price'), 0)"
                + " AS production FROM \"StandardCostVariance\" v WHERE" + range + " GROUP BY 1 ORDER BY 1")
                .params(org(), start, end)), "net", "price", "production");

        // GL tie-out: the variance accounts' movement in the period vs the booked variance rows.
        List<Map<String, Object>> gl = new ArrayList<>();
        BigDecimal glTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT a.code, a.name, m.key AS mapping,"
                + " coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) AS net FROM \"AccountMapping\" m JOIN \"Account\" a"
                + " ON a.id = m.\"accountId\" LEFT JOIN \"JournalLine\" l ON l.\"accountId\" = a.id AND"
                + " l.\"organizationId\" = a.\"organizationId\" AND EXISTS (SELECT 1 FROM \"JournalEntry\" e WHERE e.id ="
                + " l.\"journalEntryId\" AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND"
                + " e.\"postingDate\" < ?) WHERE m.\"organizationId\" = ? AND m.key = ANY (?) GROUP BY a.code, a.name, m.key"
                + " ORDER BY a.code").params(start, end, org(), VARIANCE_MAPPINGS.toArray(String[]::new)))) {
            BigDecimal net = SqlRows.dec(r, "net");
            glTotal = glTotal.add(net);
            gl.add(Obj.of("code", r.get("code"), "name", r.get("name"), "mapping", r.get("mapping"), "net", m(net)));
        }

        Map<String, Object> production = manufacturing.variances(from.toString(), to.toString(), null);
        return Obj.of("from", from.toString(), "to", to.toString(), "totals", Obj.of("net", m(total), "unfavourable",
                m(unfavourable), "favourable", m(favourable)), "byKind", byKind, "byProduct", byProduct, "bySupplier",
                bySupplier, "byMonth", byMonth, "gl", Obj.of("accounts", gl, "variancesInGl", m(glTotal),
                        "bookedVariances", m(total), "difference", m(glTotal.subtract(total))),
                "production", production);
    }

    private static List<Map<String, Object>> rows(List<Map<String, Object>> in, String... money) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : in) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            for (String k : money) {
                row.put(k, m(SqlRows.dec(r, k)));
            }
            out.add(row);
        }
        return out;
    }

    static String m(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
