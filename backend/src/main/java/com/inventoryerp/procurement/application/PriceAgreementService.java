package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier price agreements and price history (PROC-2). An agreement is an agreed price per base unit over a date range
 * from a minimum quantity; a new price is a new agreement. History reads purchase order lines in base units and shows
 * what was agreed on the order date beside what was ordered.
 */
@Service
public class PriceAgreementService {

    /** New agreement. */
    public record AgreementInput(String partnerId, String productId, BigDecimal unitPrice, String currencyCode,
            BigDecimal minQuantity, String validFrom, String validTo, String reference, String notes) {}

    /** The editable fields: price, supplier and product never change on an agreement. */
    public record AgreementPatch(String validTo, String reference, String notes) {}

    private static final int HISTORY_LIMIT = 500;
    private static final String SELECT = "SELECT a.id, a.\"partnerId\", pa.name AS \"partnerName\", a.\"productId\","
            + " pr.name AS \"productName\", pr.code AS \"productCode\", a.\"unitPrice\", a.\"currencyCode\","
            + " a.\"minQuantity\", a.\"validFrom\"::text AS \"validFrom\", a.\"validTo\"::text AS \"validTo\", a.reference,"
            + " a.notes, a.status, a.\"createdAt\" FROM \"SupplierPriceAgreement\" a"
            + " LEFT JOIN \"Partner\" pa ON pa.id = a.\"partnerId\" AND pa.\"organizationId\" = a.\"organizationId\""
            + " LEFT JOIN \"Product\" pr ON pr.id = a.\"productId\" AND pr.\"organizationId\" = a.\"organizationId\"";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PriceAgreementService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date: " + raw);
        }
    }

    private static java.sql.Date sql(LocalDate d) {
        return d == null ? null : java.sql.Date.valueOf(d);
    }

    /** Wire shape: money as strings and the effective state, where {@code expired} is derived from today. */
    private static Map<String, Object> render(Map<String, Object> a, LocalDate today) {
        a.put("unitPrice", JsNumbers.dec(SqlRows.dec(a, "unitPrice")));
        a.put("minQuantity", JsNumbers.dec(SqlRows.dec(a, "minQuantity")));
        boolean expired = a.get("validTo") != null && LocalDate.parse((String) a.get("validTo")).isBefore(today);
        a.put("state", "cancelled".equals(a.get("status")) ? "cancelled" : expired ? "expired" : "active");
        return a;
    }

    /** Agreements, newest first; {@code status} is active, expired or cancelled. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery q, String partnerId, String productId, String status) {
        int page = Math.max(1, q.page() == null || q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == null || q.pageSize() == 0 ? 25 : q.pageSize()));
        LocalDate today = today();
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ?");
        if (partnerId != null && !partnerId.isEmpty()) {
            where.append(" AND a.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (productId != null && !productId.isEmpty()) {
            where.append(" AND a.\"productId\" = ?");
            params.add(productId);
        }
        if ("cancelled".equals(status)) {
            where.append(" AND a.status = 'cancelled'");
        } else if ("expired".equals(status)) {
            where.append(" AND a.status = 'active' AND a.\"validTo\" < ?");
            params.add(sql(today));
        } else if ("active".equals(status)) {
            where.append(" AND a.status = 'active' AND (a.\"validTo\" IS NULL OR a.\"validTo\" >= ?)");
            params.add(sql(today));
        }
        if (q.search() != null && !q.search().isEmpty()) {
            String like = "%" + BaseCrudService.escapeLike(q.search()) + "%";
            where.append(" AND (pa.name ILIKE ? OR pr.name ILIKE ? OR a.reference ILIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(SELECT + where + " ORDER BY a.\"createdAt\" DESC, a.id LIMIT "
                + pageSize + " OFFSET " + (page - 1) * pageSize).params(params));
        rows.forEach(r -> render(r, today));
        long total = jdbc.sql("SELECT count(*) FROM \"SupplierPriceAgreement\" a LEFT JOIN \"Partner\" pa ON pa.id ="
                + " a.\"partnerId\" AND pa.\"organizationId\" = a.\"organizationId\" LEFT JOIN \"Product\" pr ON pr.id ="
                + " a.\"productId\" AND pr.\"organizationId\" = a.\"organizationId\"" + where)
                .params(params).query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    /** One agreement. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return load(id);
    }

    private Map<String, Object> load(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql(SELECT + " WHERE a.id = ? AND a.\"organizationId\" = ?")
                .params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Price agreement not found");
        }
        return render(a, today());
    }

    /** Create an agreement; the same price point may not overlap itself. */
    @Transactional
    public Map<String, Object> create(AgreementInput dto) {
        LocalDate from = date(dto.validFrom());
        LocalDate to = date(dto.validTo());
        if (to != null && to.isBefore(from)) {
            throw ApiException.badRequest("validTo cannot be before validFrom");
        }
        // Serializes concurrent agreements of one supplier, so the overlap check below cannot race.
        boolean partner = !jdbc.sql("SELECT id FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(dto.partnerId(), org()).query(String.class).list().isEmpty();
        if (!partner) {
            throw ApiException.notFound("Supplier not found");
        }
        long product = jdbc.sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(dto.productId(), org()).query(Long.class).single();
        if (product == 0) {
            throw ApiException.notFound("Product not found");
        }
        BigDecimal minQuantity = dto.minQuantity() == null ? BigDecimal.ZERO : dto.minQuantity();
        long overlapping = jdbc.sql("SELECT count(*) FROM \"SupplierPriceAgreement\" WHERE \"organizationId\" = ?"
                        + " AND \"partnerId\" = ? AND \"productId\" = ? AND \"currencyCode\" = ? AND \"minQuantity\" = ?"
                        + " AND status = 'active' AND (\"validTo\" IS NULL OR \"validTo\" >= ?)"
                        + " AND (?::date IS NULL OR \"validFrom\" <= ?::date)")
                .params(org(), dto.partnerId(), dto.productId(), dto.currencyCode(), minQuantity, sql(from), sql(to), sql(to))
                .query(Long.class).single();
        if (overlapping > 0) {
            throw ApiException.conflict("An active agreement already covers these dates. End it first.");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SupplierPriceAgreement\" (id, \"organizationId\", \"partnerId\", \"productId\", \"unitPrice\","
                        + " \"currencyCode\", \"minQuantity\", \"validFrom\", \"validTo\", reference, notes, \"createdBy\","
                        + " \"updatedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), dto.partnerId(), dto.productId(), dto.unitPrice(), dto.currencyCode(), minQuantity,
                        sql(from), sql(to), dto.reference(), dto.notes(), user(), user())
                .update();
        audit.recordInTx(AuditInput.of("SupplierPriceAgreement", id, "create", null, Obj.of("partnerId", dto.partnerId(),
                "productId", dto.productId(), "unitPrice", dto.unitPrice())));
        return load(id);
    }

    /** End or annotate an agreement. */
    @Transactional
    public Map<String, Object> update(String id, AgreementPatch dto) {
        Map<String, Object> a = load(id);
        if ("cancelled".equals(a.get("status"))) {
            throw ApiException.badRequest("A cancelled agreement cannot be changed");
        }
        LocalDate to = date(dto.validTo());
        if (to != null && to.isBefore(LocalDate.parse((String) a.get("validFrom")))) {
            throw ApiException.badRequest("validTo cannot be before validFrom");
        }
        jdbc.sql("UPDATE \"SupplierPriceAgreement\" SET \"validTo\" = ?, reference = ?, notes = ?, \"updatedBy\" = ?,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(sql(to), dto.reference(), dto.notes(), user(), id, org()).update();
        audit.recordInTx(AuditInput.of("SupplierPriceAgreement", id, "update", Obj.of("validTo", a.get("validTo")),
                Obj.of("validTo", dto.validTo())));
        return load(id);
    }

    /** Cancel an agreement: it stops applying, including to the history comparison. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> a = load(id);
        if ("cancelled".equals(a.get("status"))) {
            throw ApiException.badRequest("This agreement is already cancelled");
        }
        jdbc.sql("UPDATE \"SupplierPriceAgreement\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(user(), id, org()).update();
        audit.recordInTx(AuditInput.of("SupplierPriceAgreement", id, "update", null, Obj.of("status", "cancelled")));
        return load(id);
    }

    /** The agreed price for this order: the applicable agreement with the highest minimum quantity, or null. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookup(String partnerId, String productId, BigDecimal quantity, String onDate) {
        LocalDate on = date(onDate) != null ? date(onDate) : today();
        Map<String, Object> a = SqlRows.one(jdbc.sql(SELECT + " WHERE a.\"organizationId\" = ? AND a.\"partnerId\" = ?"
                        + " AND a.\"productId\" = ? AND a.status = 'active' AND a.\"validFrom\" <= ?"
                        + " AND (a.\"validTo\" IS NULL OR a.\"validTo\" >= ?) AND a.\"minQuantity\" <= ?"
                        + " ORDER BY a.\"minQuantity\" DESC, a.\"validFrom\" DESC, a.id LIMIT 1")
                .params(org(), partnerId, productId, sql(on), sql(on), quantity == null ? BigDecimal.ZERO : quantity));
        return Obj.of("agreement", a == null ? null : render(a, today()));
    }

    /**
     * Prices paid for one product from purchase order lines, newest first, per base unit in base currency, with the
     * agreed price of that date in the order's currency where one applied.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> priceHistory(String productId, String partnerId, String fromRaw, String toRaw) {
        if (productId == null || productId.isEmpty()) {
            throw ApiException.badRequest("productId is required");
        }
        List<Object> params = new ArrayList<>(List.of(org(), productId));
        StringBuilder where = new StringBuilder(" WHERE po.\"organizationId\" = ? AND l.\"productId\" = ?"
                + " AND po.\"deletedAt\" IS NULL AND po.status::text NOT IN ('draft', 'cancelled')");
        if (partnerId != null && !partnerId.isEmpty()) {
            where.append(" AND po.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (date(fromRaw) != null) {
            where.append(" AND po.\"orderDate\"::date >= ?");
            params.add(sql(date(fromRaw)));
        }
        if (date(toRaw) != null) {
            where.append(" AND po.\"orderDate\"::date <= ?");
            params.add(sql(date(toRaw)));
        }
        List<Map<String, Object>> raw = SqlRows.list(jdbc.sql("SELECT po.id AS \"purchaseOrderId\", po.\"orderNumber\","
                + " po.\"orderDate\", po.\"partnerId\", pa.name AS \"partnerName\", po.\"currencyCode\", po.\"exchangeRate\","
                + " l.quantity, l.\"unitPrice\", COALESCE(l.\"uomFactor\", 1) AS \"uomFactor\", ag.\"unitPrice\" AS \"agreedPrice\""
                + " FROM \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" po ON po.id = l.\"purchaseOrderId\""
                + " LEFT JOIN \"Partner\" pa ON pa.id = po.\"partnerId\" AND pa.\"organizationId\" = po.\"organizationId\""
                + " LEFT JOIN LATERAL (SELECT a.\"unitPrice\" FROM \"SupplierPriceAgreement\" a WHERE a.\"organizationId\" ="
                + " po.\"organizationId\" AND a.\"partnerId\" = po.\"partnerId\" AND a.\"productId\" = l.\"productId\""
                + " AND a.\"currencyCode\" = po.\"currencyCode\" AND a.status = 'active'"
                + " AND a.\"validFrom\" <= po.\"orderDate\"::date AND (a.\"validTo\" IS NULL OR a.\"validTo\" >="
                + " po.\"orderDate\"::date) AND a.\"minQuantity\" <= l.quantity * COALESCE(l.\"uomFactor\", 1)"
                + " ORDER BY a.\"minQuantity\" DESC, a.\"validFrom\" DESC, a.id LIMIT 1) ag ON true"
                + where + " ORDER BY po.\"orderDate\" DESC, l.id LIMIT " + HISTORY_LIMIT).params(params));

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> bySupplier = new LinkedHashMap<>();
        BigDecimal weighted = Money.ZERO;
        BigDecimal quantity = Money.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        for (Map<String, Object> r : raw) {
            BigDecimal factor = SqlRows.dec(r, "uomFactor");
            if (factor.signum() <= 0) {
                factor = BigDecimal.ONE;
            }
            BigDecimal documentPrice = SqlRows.dec(r, "unitPrice").divide(factor, 6, RoundingMode.HALF_UP);
            BigDecimal basePrice = documentPrice.multiply(SqlRows.dec(r, "exchangeRate")).setScale(6, RoundingMode.HALF_UP);
            BigDecimal baseQuantity = SqlRows.dec(r, "quantity").multiply(factor);
            BigDecimal agreed = r.get("agreedPrice") == null ? null : SqlRows.dec(r, "agreedPrice");
            rows.add(Obj.of("purchaseOrderId", r.get("purchaseOrderId"), "orderNumber", r.get("orderNumber"), "orderDate",
                    r.get("orderDate"), "partnerId", r.get("partnerId"), "partnerName", r.get("partnerName"), "currencyCode",
                    r.get("currencyCode"), "quantity", JsNumbers.dec(baseQuantity), "unitPrice", JsNumbers.dec(documentPrice),
                    "baseUnitPrice", JsNumbers.dec(basePrice), "agreedPrice", agreed == null ? null : JsNumbers.dec(agreed),
                    "variance", agreed == null ? null : JsNumbers.dec(documentPrice.subtract(agreed))));
            weighted = weighted.add(basePrice.multiply(baseQuantity));
            quantity = quantity.add(baseQuantity);
            min = min == null ? basePrice : min.min(basePrice);
            max = max == null ? basePrice : max.max(basePrice);
            Map<String, Object> s = bySupplier.computeIfAbsent((String) r.get("partnerId"), id -> Obj.of("partnerId", id,
                    "partnerName", r.get("partnerName"), "orders", 0L, "quantity", Money.ZERO, "value", Money.ZERO,
                    "lastPrice", JsNumbers.dec(basePrice)));
            s.put("orders", (Long) s.get("orders") + 1);
            s.put("quantity", ((BigDecimal) s.get("quantity")).add(baseQuantity));
            s.put("value", ((BigDecimal) s.get("value")).add(basePrice.multiply(baseQuantity)));
        }
        List<Map<String, Object>> suppliers = new ArrayList<>();
        for (Map<String, Object> s : bySupplier.values()) {
            BigDecimal q = (BigDecimal) s.get("quantity");
            BigDecimal value = (BigDecimal) s.get("value");
            suppliers.add(Obj.of("partnerId", s.get("partnerId"), "partnerName", s.get("partnerName"), "orders",
                    s.get("orders"), "quantity", JsNumbers.dec(q), "averagePrice", q.signum() == 0 ? null
                            : JsNumbers.dec(value.divide(q, 6, RoundingMode.HALF_UP)), "lastPrice", s.get("lastPrice")));
        }
        Map<String, Object> summary = Obj.of("orders", rows.size(), "minPrice", min == null ? null : JsNumbers.dec(min),
                "maxPrice", max == null ? null : JsNumbers.dec(max), "averagePrice", quantity.signum() == 0 ? null
                        : JsNumbers.dec(weighted.divide(quantity, 6, RoundingMode.HALF_UP)), "lastPrice",
                rows.isEmpty() ? null : rows.getFirst().get("baseUnitPrice"), "firstPrice",
                rows.isEmpty() ? null : rows.getLast().get("baseUnitPrice"), "truncated", raw.size() == HISTORY_LIMIT);
        return Obj.of("productId", productId, "summary", summary, "suppliers", suppliers, "rows", rows);
    }
}
