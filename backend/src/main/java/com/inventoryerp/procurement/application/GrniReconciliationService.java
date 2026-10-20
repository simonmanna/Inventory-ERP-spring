package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GRNI reconciliation (port of {@code grni-reconciliation.service.ts}): open (received-not-invoiced) receipt
 * quantity, aged, next to the GRNI GL balance it should tie to. PO-driven receipts were vouchered to AP at receipt,
 * so their open value is reported apart and excluded from the tie-out.
 */
@Service
public class GrniReconciliationService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final JdbcClient jdbc;
    private final AccountDeterminationService determination;

    public GrniReconciliationService(JdbcClient jdbc, AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.determination = determination;
    }

    /** The report for {@code asOf} (default now), optionally one supplier and a minimum age in days. */
    @Transactional(readOnly = true)
    public Map<String, Object> report(String partnerId, String asOfRaw, Double minAgeDaysRaw) {
        String orgId = org();
        Instant asOf = asOfRaw != null && !asOfRaw.isEmpty() ? EntityPatch.toInstant(asOfRaw)
                : Instant.now().truncatedTo(ChronoUnit.MILLIS);
        double minAgeDays = minAgeDaysRaw == null ? 0 : minAgeDaysRaw;

        List<Object> params = new ArrayList<>(List.of(orgId, SqlRows.ts(asOf)));
        String partnerFilter = "";
        if (partnerId != null && !partnerId.isEmpty()) {
            partnerFilter = " AND r.\"partnerId\" = ?";
            params.add(partnerId);
        }
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.*, r.\"receiptNumber\" AS \"r_receiptNumber\","
                + " r.\"receivedAt\" AS \"r_receivedAt\", r.\"partnerId\" AS \"r_partnerId\","
                + " r.\"purchaseOrderId\" AS \"r_purchaseOrderId\" FROM \"GoodsReceiptLine\" l"
                + " JOIN \"GoodsReceiptNote\" r ON r.id = l.\"goodsReceiptId\" WHERE l.\"organizationId\" = ?"
                + " AND r.status = 'posted' AND r.\"receivedAt\" <= ?" + partnerFilter
                + " ORDER BY r.\"receivedAt\" ASC, l.\"lineNumber\" ASC").params(params));

        Set<String> partnerIds = new LinkedHashSet<>();
        Set<String> productIds = new LinkedHashSet<>();
        for (Map<String, Object> l : lines) {
            if (l.get("r_partnerId") != null && !((String) l.get("r_partnerId")).isEmpty()) {
                partnerIds.add((String) l.get("r_partnerId"));
            }
            if (l.get("productId") != null && !((String) l.get("productId")).isEmpty()) {
                productIds.add((String) l.get("productId"));
            }
        }
        Map<String, Map<String, Object>> partners = new HashMap<>();
        if (!partnerIds.isEmpty()) {
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id = ANY(?::text[])"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(partnerIds.toArray(String[]::new),
                    orgId))) {
                partners.put((String) p.get("id"), p);
            }
        }
        Map<String, Map<String, Object>> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Product\" WHERE"
                    + " id = ANY(?::text[]) AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(productIds.toArray(String[]::new), orgId))) {
                products.put((String) p.get("id"), p);
            }
        }

        List<Map<String, Object>> positions = new ArrayList<>();
        BigDecimal openValue = Money.ZERO;
        BigDecimal voucheredValue = Money.ZERO;
        for (Map<String, Object> line : lines) {
            BigDecimal received = Money.dec(line.get("quantity"));
            BigDecimal billed = Money.dec(line.get("billedQuantity"));
            BigDecimal open = received.subtract(billed);
            if (open.signum() <= 0) {
                continue;
            }
            Instant receivedAt = (Instant) line.get("r_receivedAt");
            long ageDays = Math.floorDiv(asOf.toEpochMilli() - receivedAt.toEpochMilli(), 86_400_000L);
            if (ageDays < minAgeDays) {
                continue;
            }
            BigDecimal unitCost = Money.dec(line.get("unitCost"));
            BigDecimal value = unitCost.multiply(open, Money.DECIMAL_JS);
            boolean vouchered = line.get("r_purchaseOrderId") != null && !((String) line.get("r_purchaseOrderId")).isEmpty();
            if (vouchered) {
                voucheredValue = voucheredValue.add(value);
            } else {
                openValue = openValue.add(value);
            }
            Map<String, Object> product = line.get("productId") != null ? products.get((String) line.get("productId"))
                    : null;
            String partner = (String) line.get("r_partnerId");
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("goodsReceiptLineId", line.get("id"));
            pos.put("receiptNumber", line.get("r_receiptNumber"));
            pos.put("receivedAt", receivedAt);
            pos.put("ageDays", ageDays);
            pos.put("partnerId", partner);
            pos.put("partnerName", partner != null && partners.containsKey(partner) ? partners.get(partner).get("name")
                    : null);
            pos.put("purchaseOrderId", line.get("r_purchaseOrderId"));
            pos.put("productId", line.get("productId"));
            pos.put("productCode", product != null ? product.get("code") : null);
            pos.put("productName", product != null ? product.get("name") : line.get("description"));
            pos.put("receivedQuantity", JsNumbers.dec(received));
            pos.put("billedQuantity", JsNumbers.dec(billed));
            pos.put("openQuantity", JsNumbers.dec(open));
            pos.put("unitCost", JsNumbers.dec(unitCost));
            pos.put("openValue", JsNumbers.dec(value));
            pos.put("vouchered", vouchered);
            positions.add(pos);
        }

        BigDecimal glBalance = grniGlBalance(orgId, asOf);
        BigDecimal difference = glBalance.subtract(openValue);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", asOf);
        out.put("subledgerOpenValue", JsNumbers.dec(openValue));
        out.put("voucheredOpenValue", JsNumbers.dec(voucheredValue));
        out.put("glBalance", JsNumbers.dec(glBalance));
        out.put("difference", JsNumbers.dec(difference));
        out.put("reconciled", difference.abs().compareTo(TOLERANCE) <= 0);
        out.put("ageBuckets", bucket(positions));
        out.put("positions", positions);
        return out;
    }

    /** GRNI is a liability: credit − debit, returned positive. Zero when the mapping is missing. */
    private BigDecimal grniGlBalance(String organizationId, Instant asOf) {
        String accountId;
        try {
            accountId = determination.mapped("grni_accrued");
        } catch (RuntimeException e) {
            return Money.ZERO;
        }
        Map<String, Object> agg = SqlRows.one(jdbc.sql("SELECT sum(l.debit) AS debit, sum(l.credit) AS credit FROM"
                + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                + " AND l.\"accountId\" = ? AND e.status IN ('posted', 'reversed') AND e.\"postingDate\" <= ?")
                .params(organizationId, accountId, SqlRows.ts(asOf)));
        return Money.dec(agg.get("credit")).subtract(Money.dec(agg.get("debit")));
    }

    private static List<Map<String, Object>> bucket(List<Map<String, Object>> positions) {
        String[] labels = {"0-30", "31-60", "61-90", "90+"};
        long[][] ranges = {{0, 30}, {31, 60}, {61, 90}, {91, 9_007_199_254_740_991L}};
        BigDecimal[] values = {Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO};
        int[] counts = new int[4];
        for (Map<String, Object> p : positions) {
            if (Boolean.TRUE.equals(p.get("vouchered"))) {
                continue;
            }
            long age = (Long) p.get("ageDays");
            for (int i = 0; i < 4; i++) {
                if (age >= ranges[i][0] && age <= ranges[i][1]) {
                    values[i] = values[i].add(new BigDecimal((String) p.get("openValue")));
                    counts[i]++;
                    break;
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(Obj.of("label", labels[i], "value", JsNumbers.dec(values[i]), "count", counts[i]));
        }
        return out;
    }
}
