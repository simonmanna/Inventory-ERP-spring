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
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.application.PurchaseOrdersService.CreateInput;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests for quotation (PROC-1): draft → sent → awarded | cancelled. Quotes are recorded per invited supplier and
 * compared in base currency; award raises a purchase order through {@link PurchaseOrdersService}, so the PO approval
 * gate applies unchanged.
 */
@Service
public class RfqService {

    /** One requested item. */
    public record LineInput(String productId, String description, BigDecimal quantity, String unitOfMeasureId) {}

    /** Draft body. */
    public record RfqInput(String title, String purchaseRequestId, String dueDate, String currencyCode, String notes,
            List<LineInput> lines, List<String> supplierIds) {}

    /** One quoted price. */
    public record QuoteLineInput(String rfqLineId, BigDecimal unitPrice, BigDecimal taxRate) {}

    /** A supplier's quotation. */
    public record QuotationInput(String partnerId, String reference, String currencyCode, BigDecimal exchangeRate,
            String validUntil, Integer leadTimeDays, String paymentTerms, String notes, List<QuoteLineInput> lines) {}

    /** Award one quotation to a purchase order. */
    public record AwardInput(String quotationId, String warehouseId, String paymentType) {}

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final AuditService audit;
    private final PurchaseOrdersService orders;

    public RfqService(JdbcClient jdbc, SequenceService sequence, AuditService audit, PurchaseOrdersService orders) {
        this.jdbc = jdbc;
        this.sequence = sequence;
        this.audit = audit;
        this.orders = orders;
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

    private static java.sql.Date sqlDate(String raw) {
        LocalDate d = date(raw);
        return d == null ? null : java.sql.Date.valueOf(d);
    }

    private Map<String, Object> rfq(String id, boolean lock) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT id, \"rfqNumber\", title, status, \"purchaseRequestId\","
                + " \"dueDate\"::text AS \"dueDate\", \"currencyCode\", notes, \"sentAt\", \"awardedAt\", \"awardedBy\","
                + " \"awardedQuotationId\", \"purchaseOrderId\", \"createdBy\", \"createdAt\", \"updatedAt\""
                + " FROM \"PurchaseRfq\" WHERE id = ? AND \"organizationId\" = ?" + (lock ? " FOR UPDATE" : ""))
                .params(id, org()));
        if (r == null) {
            throw ApiException.notFound("RFQ not found");
        }
        return r;
    }

    private static void requireStatus(Map<String, Object> rfq, String status, String message) {
        if (!status.equals(rfq.get("status"))) {
            throw ApiException.badRequest(message);
        }
    }

    /** RFQs, newest first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery q, String status) {
        int page = Math.max(1, q.page() == null || q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == null || q.pageSize() == 0 ? 25 : q.pageSize()));
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        if (status != null && !status.isEmpty()) {
            where.append(" AND r.status = ?");
            params.add(status);
        }
        if (q.search() != null && !q.search().isEmpty()) {
            String like = "%" + BaseCrudService.escapeLike(q.search()) + "%";
            where.append(" AND (r.\"rfqNumber\" ILIKE ? OR r.title ILIKE ?)");
            params.add(like);
            params.add(like);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.id, r.\"rfqNumber\", r.title, r.status,"
                + " r.\"dueDate\"::text AS \"dueDate\", r.\"currencyCode\", r.\"purchaseOrderId\", r.\"createdAt\","
                + " (SELECT count(*) FROM \"PurchaseRfqSupplier\" s WHERE s.\"rfqId\" = r.id) AS suppliers,"
                + " (SELECT count(*) FROM \"PurchaseQuotation\" q WHERE q.\"rfqId\" = r.id) AS quotations"
                + " FROM \"PurchaseRfq\" r" + where + " ORDER BY r.\"createdAt\" DESC, r.id LIMIT " + pageSize
                + " OFFSET " + (page - 1) * pageSize).params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"PurchaseRfq\" r" + where).params(params).query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    /** RFQ with its lines, invited suppliers and quotations. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return detail(id, rfq(id, false));
    }

    private Map<String, Object> detail(String id, Map<String, Object> r) {
        r.put("lines", lines(id));
        r.put("suppliers", SqlRows.list(jdbc.sql("SELECT s.\"partnerId\", p.name AS \"partnerName\" FROM"
                + " \"PurchaseRfqSupplier\" s LEFT JOIN \"Partner\" p ON p.id = s.\"partnerId\" AND p.\"organizationId\" ="
                + " s.\"organizationId\" WHERE s.\"rfqId\" = ? AND s.\"organizationId\" = ? ORDER BY p.name")
                .params(id, org())));
        List<Map<String, Object>> quotes = quotations(id);
        for (Map<String, Object> q : quotes) {
            q.put("lines", quotationLines((String) q.get("id")));
        }
        r.put("quotations", quotes);
        return r;
    }

    private List<Map<String, Object>> lines(String rfqId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, \"productId\", description, quantity,"
                + " \"unitOfMeasureId\", \"lineNumber\" FROM \"PurchaseRfqLine\" WHERE \"rfqId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"lineNumber\"").params(rfqId, org()));
        rows.forEach(l -> l.put("quantity", JsNumbers.dec(SqlRows.dec(l, "quantity"))));
        return rows;
    }

    private List<Map<String, Object>> quotations(String rfqId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT q.id, q.\"partnerId\", p.name AS \"partnerName\","
                + " q.reference, q.\"currencyCode\", q.\"exchangeRate\", q.\"validUntil\"::text AS \"validUntil\","
                + " q.\"leadTimeDays\", q.\"paymentTerms\", q.notes, q.\"createdAt\" FROM \"PurchaseQuotation\" q"
                + " LEFT JOIN \"Partner\" p ON p.id = q.\"partnerId\" AND p.\"organizationId\" = q.\"organizationId\""
                + " WHERE q.\"rfqId\" = ? AND q.\"organizationId\" = ? ORDER BY p.name").params(rfqId, org()));
        rows.forEach(q -> q.put("exchangeRate", JsNumbers.dec(SqlRows.dec(q, "exchangeRate"))));
        return rows;
    }

    private List<Map<String, Object>> quotationLines(String quotationId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT \"rfqLineId\", \"unitPrice\", \"taxRate\" FROM"
                + " \"PurchaseQuotationLine\" WHERE \"quotationId\" = ? AND \"organizationId\" = ?")
                .params(quotationId, org()));
        rows.forEach(l -> {
            l.put("unitPrice", JsNumbers.dec(SqlRows.dec(l, "unitPrice")));
            l.put("taxRate", JsNumbers.dec(SqlRows.dec(l, "taxRate")));
        });
        return rows;
    }

    private void writeLinesAndSuppliers(String id, RfqInput dto) {
        int n = 0;
        for (LineInput l : dto.lines()) {
            jdbc.sql("INSERT INTO \"PurchaseRfqLine\" (id, \"organizationId\", \"rfqId\", \"productId\", description,"
                            + " quantity, \"unitOfMeasureId\", \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, l.productId(), l.description(), l.quantity(),
                            l.unitOfMeasureId(), ++n)
                    .update();
        }
        Set<String> suppliers = new LinkedHashSet<>(dto.supplierIds() == null ? List.of() : dto.supplierIds());
        for (String partnerId : suppliers) {
            long found = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(partnerId, org()).query(Long.class).single();
            if (found == 0) {
                throw ApiException.notFound("Supplier not found");
            }
            jdbc.sql("INSERT INTO \"PurchaseRfqSupplier\" (id, \"organizationId\", \"rfqId\", \"partnerId\")"
                    + " VALUES (?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id, partnerId).update();
        }
    }

    /** Create a draft RFQ. */
    @Transactional
    public Map<String, Object> create(RfqInput dto) {
        String id = UUID.randomUUID().toString();
        int year = today().getYear();
        String number = sequence.next("rfq:" + year, "RFQ-" + year + "-", 5);
        jdbc.sql("INSERT INTO \"PurchaseRfq\" (id, \"organizationId\", \"rfqNumber\", title, \"purchaseRequestId\","
                        + " \"dueDate\", \"currencyCode\", notes, \"createdBy\", \"updatedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, dto.title(), dto.purchaseRequestId(), sqlDate(dto.dueDate()),
                        dto.currencyCode(), dto.notes(), user(), user())
                .update();
        writeLinesAndSuppliers(id, dto);
        audit.recordInTx(AuditInput.of("PurchaseRfq", id, "create", null, Obj.of("rfqNumber", number)));
        return detail(id, rfq(id, false));
    }

    /** Replace a draft RFQ. */
    @Transactional
    public Map<String, Object> update(String id, RfqInput dto) {
        requireStatus(rfq(id, true), "draft", "Only a draft RFQ can be edited");
        jdbc.sql("UPDATE \"PurchaseRfq\" SET title = ?, \"purchaseRequestId\" = ?, \"dueDate\" = ?, \"currencyCode\" = ?,"
                        + " notes = ?, \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(dto.title(), dto.purchaseRequestId(), sqlDate(dto.dueDate()), dto.currencyCode(), dto.notes(),
                        user(), id, org())
                .update();
        jdbc.sql("DELETE FROM \"PurchaseRfqLine\" WHERE \"rfqId\" = ? AND \"organizationId\" = ?").params(id, org()).update();
        jdbc.sql("DELETE FROM \"PurchaseRfqSupplier\" WHERE \"rfqId\" = ? AND \"organizationId\" = ?").params(id, org())
                .update();
        writeLinesAndSuppliers(id, dto);
        return detail(id, rfq(id, false));
    }

    /** Send: from here on quotes can be recorded and the request itself is frozen. */
    @Transactional
    public Map<String, Object> send(String id) {
        requireStatus(rfq(id, true), "draft", "Only a draft RFQ can be sent");
        long suppliers = jdbc.sql("SELECT count(*) FROM \"PurchaseRfqSupplier\" WHERE \"rfqId\" = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Long.class).single();
        if (suppliers == 0) {
            throw ApiException.badRequest("Invite at least one supplier before sending");
        }
        jdbc.sql("UPDATE \"PurchaseRfq\" SET status = 'sent', \"sentAt\" = " + NOW + ", \"updatedBy\" = ?, \"updatedAt\" = "
                + NOW + " WHERE id = ? AND \"organizationId\" = ?").params(user(), id, org()).update();
        audit.recordInTx(AuditInput.of("PurchaseRfq", id, "update", null, Obj.of("status", "sent")));
        return detail(id, rfq(id, false));
    }

    /** Cancel an RFQ that was not awarded. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> r = rfq(id, true);
        if ("awarded".equals(r.get("status")) || "cancelled".equals(r.get("status"))) {
            throw ApiException.badRequest("This RFQ can no longer be cancelled");
        }
        jdbc.sql("UPDATE \"PurchaseRfq\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(user(), id, org()).update();
        audit.recordInTx(AuditInput.of("PurchaseRfq", id, "update", null, Obj.of("status", "cancelled")));
        return detail(id, rfq(id, false));
    }

    /** Record an invited supplier's quotation; a second one from the same supplier replaces the first. */
    @Transactional
    public Map<String, Object> recordQuotation(String id, QuotationInput dto) {
        Map<String, Object> r = rfq(id, true);
        requireStatus(r, "sent", "Quotations can only be recorded on a sent RFQ");
        long invited = jdbc.sql("SELECT count(*) FROM \"PurchaseRfqSupplier\" WHERE \"rfqId\" = ? AND \"organizationId\" = ?"
                + " AND \"partnerId\" = ?").params(id, org(), dto.partnerId()).query(Long.class).single();
        if (invited == 0) {
            throw ApiException.badRequest("This supplier was not invited to the RFQ");
        }
        Set<String> rfqLines = new LinkedHashSet<>();
        lines(id).forEach(l -> rfqLines.add((String) l.get("id")));
        Set<String> seen = new LinkedHashSet<>();
        for (QuoteLineInput l : dto.lines()) {
            if (!rfqLines.contains(l.rfqLineId()) || !seen.add(l.rfqLineId())) {
                throw ApiException.badRequest("Each quoted line must be a different line of this RFQ");
            }
        }
        jdbc.sql("DELETE FROM \"PurchaseQuotation\" WHERE \"rfqId\" = ? AND \"organizationId\" = ? AND \"partnerId\" = ?")
                .params(id, org(), dto.partnerId()).update();
        String quotationId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PurchaseQuotation\" (id, \"organizationId\", \"rfqId\", \"partnerId\", reference,"
                        + " \"currencyCode\", \"exchangeRate\", \"validUntil\", \"leadTimeDays\", \"paymentTerms\", notes,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(quotationId, org(), id, dto.partnerId(), dto.reference(),
                        dto.currencyCode() != null ? dto.currencyCode() : r.get("currencyCode"),
                        dto.exchangeRate() != null ? dto.exchangeRate() : BigDecimal.ONE, sqlDate(dto.validUntil()),
                        dto.leadTimeDays(), dto.paymentTerms(), dto.notes(), user())
                .update();
        for (QuoteLineInput l : dto.lines()) {
            jdbc.sql("INSERT INTO \"PurchaseQuotationLine\" (id, \"organizationId\", \"quotationId\", \"rfqLineId\","
                            + " \"unitPrice\", \"taxRate\") VALUES (?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), quotationId, l.rfqLineId(), l.unitPrice(),
                            l.taxRate() != null ? l.taxRate() : BigDecimal.ZERO)
                    .update();
        }
        audit.recordInTx(AuditInput.of("PurchaseRfq", id, "update", null, Obj.of("quotationId", quotationId, "partnerId",
                dto.partnerId())));
        return detail(id, rfq(id, false));
    }

    /**
     * Side-by-side comparison in base currency. Per line the cheapest quote is flagged; per quotation the net total,
     * coverage and expiry; the recommendation is the cheapest quotation that covers every line and has not expired.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> comparison(String id) {
        Map<String, Object> r = rfq(id, false);
        List<Map<String, Object>> rfqLines = lines(id);
        List<Map<String, Object>> quotes = quotations(id);
        Map<String, Map<String, BigDecimal>> baseByQuote = new HashMap<>();
        for (Map<String, Object> q : quotes) {
            BigDecimal rate = new BigDecimal((String) q.get("exchangeRate"));
            Map<String, BigDecimal> prices = new HashMap<>();
            for (Map<String, Object> l : quotationLines((String) q.get("id"))) {
                prices.put((String) l.get("rfqLineId"), new BigDecimal((String) l.get("unitPrice")).multiply(rate));
            }
            baseByQuote.put((String) q.get("id"), prices);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> line : rfqLines) {
            String lineId = (String) line.get("id");
            BigDecimal qty = new BigDecimal((String) line.get("quantity"));
            BigDecimal best = null;
            for (Map<String, BigDecimal> prices : baseByQuote.values()) {
                BigDecimal p = prices.get(lineId);
                if (p != null && (best == null || p.compareTo(best) < 0)) {
                    best = p;
                }
            }
            List<Map<String, Object>> cells = new ArrayList<>();
            for (Map<String, Object> q : quotes) {
                BigDecimal p = baseByQuote.get((String) q.get("id")).get(lineId);
                cells.add(Obj.of("quotationId", q.get("id"), "baseUnitPrice", p == null ? null : JsNumbers.dec(p),
                        "lineTotal", p == null ? null : JsNumbers.dec(p.multiply(qty)), "cheapest",
                        p != null && p.compareTo(best) == 0));
            }
            rows.add(Obj.of("rfqLineId", lineId, "description", line.get("description"), "quantity", line.get("quantity"),
                    "quotes", cells));
        }
        LocalDate today = today();
        List<Map<String, Object>> totals = new ArrayList<>();
        String recommended = null;
        BigDecimal lowest = null;
        for (Map<String, Object> q : quotes) {
            String quoteId = (String) q.get("id");
            Map<String, BigDecimal> prices = baseByQuote.get(quoteId);
            BigDecimal total = Money.ZERO;
            for (Map<String, Object> line : rfqLines) {
                BigDecimal p = prices.get((String) line.get("id"));
                if (p != null) {
                    total = total.add(p.multiply(new BigDecimal((String) line.get("quantity"))));
                }
            }
            boolean complete = prices.size() == rfqLines.size();
            boolean expired = q.get("validUntil") != null && LocalDate.parse((String) q.get("validUntil")).isBefore(today);
            if (complete && !expired && (lowest == null || total.compareTo(lowest) < 0)) {
                lowest = total;
                recommended = quoteId;
            }
            totals.add(Obj.of("quotationId", quoteId, "partnerId", q.get("partnerId"), "partnerName", q.get("partnerName"),
                    "currencyCode", q.get("currencyCode"), "baseTotal", JsNumbers.dec(total), "linesQuoted", prices.size(),
                    "complete", complete, "expired", expired, "validUntil", q.get("validUntil"), "leadTimeDays",
                    q.get("leadTimeDays"), "paymentTerms", q.get("paymentTerms")));
        }
        return Obj.of("rfqId", id, "rfqNumber", r.get("rfqNumber"), "status", r.get("status"), "lines", rows, "quotations",
                totals, "recommendedQuotationId", recommended);
    }

    /** Award one quotation: raises the purchase order for the lines it quoted and closes the RFQ. */
    @Transactional
    public Map<String, Object> award(String id, AwardInput dto) {
        Map<String, Object> r = rfq(id, true);
        requireStatus(r, "sent", "Only a sent RFQ can be awarded");
        Map<String, Object> quote = null;
        for (Map<String, Object> q : quotations(id)) {
            if (q.get("id").equals(dto.quotationId())) {
                quote = q;
            }
        }
        if (quote == null) {
            throw ApiException.notFound("Quotation not found");
        }
        if (quote.get("validUntil") != null && LocalDate.parse((String) quote.get("validUntil")).isBefore(today())) {
            throw ApiException.badRequest("This quotation has expired");
        }
        Map<String, Map<String, Object>> quoted = new HashMap<>();
        quotationLines(dto.quotationId()).forEach(l -> quoted.put((String) l.get("rfqLineId"), l));
        List<PurchaseOrdersService.LineInput> poLines = new ArrayList<>();
        for (Map<String, Object> line : lines(id)) {
            Map<String, Object> price = quoted.get((String) line.get("id"));
            if (price != null) {
                poLines.add(new PurchaseOrdersService.LineInput((String) line.get("productId"),
                        (String) line.get("description"), new BigDecimal((String) line.get("quantity")),
                        (String) line.get("unitOfMeasureId"), new BigDecimal((String) price.get("unitPrice")), null,
                        new BigDecimal((String) price.get("taxRate")), null));
            }
        }
        if (poLines.isEmpty()) {
            throw ApiException.badRequest("This quotation has no priced lines");
        }
        Object lead = quote.get("leadTimeDays");
        String expected = lead == null ? null : today().plusDays(((Number) lead).longValue()).toString();
        Map<String, Object> po = orders.create(new CreateInput((String) quote.get("partnerId"), null,
                "From " + r.get("rfqNumber"), expected, (String) quote.get("currencyCode"),
                new BigDecimal((String) quote.get("exchangeRate")), dto.paymentType() != null ? dto.paymentType() : "credit",
                null, (String) quote.get("paymentTerms"), dto.warehouseId(), (String) r.get("purchaseRequestId"), poLines));
        jdbc.sql("UPDATE \"PurchaseRfq\" SET status = 'awarded', \"awardedAt\" = " + NOW + ", \"awardedBy\" = ?,"
                        + " \"awardedQuotationId\" = ?, \"purchaseOrderId\" = ?, \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(user(), dto.quotationId(), po.get("id"), user(), id, org()).update();
        audit.recordInTx(AuditInput.of("PurchaseRfq", id, "update", null, Obj.of("status", "awarded", "quotationId",
                dto.quotationId(), "purchaseOrderId", po.get("id"))));
        Map<String, Object> out = detail(id, rfq(id, false));
        out.put("purchaseOrder", Obj.of("id", po.get("id"), "orderNumber", po.get("orderNumber"), "status", po.get("status")));
        return out;
    }
}
