package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.application.RequisitionToOrderService.OrderInput;
import com.inventoryerp.procurement.application.RequisitionToOrderService.OrderLine;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Competitive sourcing (ADR-PROCUREMENT-001 wave 4): requests for quotation raised from approved request lines (or
 * free lines), supplier quotations with revisions, a comparison on total delivered cost and a configurable weighted
 * score (price, quality, delivery, payment terms, past performance), per-line split awards (a justification is
 * required when the cheapest offer is not chosen; an {@code rfq_award} approval workflow applies when configured), and
 * conversion of the awards into one purchase order per supplier.
 */
@Service
public class RfqService {

    public record LineInput(String requestLineId, String productId, String description, BigDecimal quantity,
            String uomId, String requiredDate, String notes) {}

    public record CreateInput(String title, String deadline, String branchId, String deliveryLocationId,
            String deliveryTerms, String notes, Map<String, Integer> weights, List<String> requestLineIds,
            List<LineInput> lines, List<String> supplierIds) {}

    public record QuoteLine(String rfqLineId, BigDecimal unitPrice, BigDecimal quantity, Integer leadTimeDays,
            BigDecimal taxRate, String alternative, String notes) {}

    public record QuoteInput(String partnerId, String supplierReference, String validUntil, String currencyCode,
            BigDecimal exchangeRate, Integer leadTimeDays, Integer paymentTermsDays, BigDecimal freightAmount,
            BigDecimal qualityScore, String notes, List<QuoteLine> lines) {}

    public record AwardLine(String rfqLineId, String partnerId, BigDecimal quantity, String justification) {}

    private static final List<String> CRITERIA = List.of("price", "quality", "delivery", "terms", "performance");

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final EventBus events;
    private final ApprovalsService approvals;
    private final SupplierService suppliers;
    private final RequisitionToOrderService orders;
    private final TransactionTemplate tx;

    public RfqService(JdbcClient jdbc, SequenceService seq, AuditService audit, EventBus events,
            ApprovalsService approvals, SupplierService suppliers, RequisitionToOrderService orders,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.events = events;
        this.approvals = approvals;
        this.suppliers = suppliers;
        this.orders = orders;
        this.tx = new TransactionTemplate(txm);
    }

    // ── create / edit ──────────────────────────────────────────────────────────────────────────────────────────

    /** New draft RFQ from approved request lines and / or free lines, with the suppliers to invite. */
    public Map<String, Object> create(CreateInput in) {
        if (in.title() == null || in.title().isBlank()) {
            throw ApiException.badRequest("title is required");
        }
        List<String> reqIds = in.requestLineIds() == null ? List.of() : in.requestLineIds();
        List<LineInput> free = in.lines() == null ? List.of() : in.lines();
        if (reqIds.isEmpty() && free.isEmpty()) {
            throw ApiException.badRequest("Add at least one line");
        }
        String weights = weightsJson(in.weights());
        String orgId = org();
        String id = UUID.randomUUID().toString();
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        tx.executeWithoutResult(st -> {
            String number = seq.next("rfq:" + year, "RFQ-" + year + "-", 5);
            jdbc.sql("INSERT INTO \"Rfq\" (id, \"organizationId\", \"rfqNumber\", title, deadline, \"branchId\","
                    + " \"deliveryLocationId\", \"deliveryTerms\", notes, weights, \"createdBy\", \"createdAt\", \"updatedAt\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, " + NOW + ", " + NOW + ")")
                    .params(id, orgId, number, in.title().trim(), ts(in.deadline()), in.branchId(), in.deliveryLocationId(),
                            in.deliveryTerms(), in.notes(), weights, user()).update();
            int n = 0;
            if (!reqIds.isEmpty()) {
                List<Map<String, Object>> rls = SqlRows.list(jdbc.sql("SELECT l.*, r.status::text AS \"requestStatus\","
                        + " r.\"requestNumber\" FROM \"PurchaseRequestLine\" l JOIN \"PurchaseRequest\" r ON r.id ="
                        + " l.\"purchaseRequestId\" WHERE l.\"organizationId\" = ? AND l.id = ANY(?::text[]) ORDER BY"
                        + " r.\"requestNumber\", l.\"lineNumber\" FOR UPDATE OF l").params(orgId,
                        reqIds.toArray(String[]::new)));
                if (rls.size() != reqIds.stream().distinct().count()) {
                    throw ApiException.notFound("One or more request lines were not found");
                }
                for (Map<String, Object> rl : rls) {
                    if (!"approved".equals(rl.get("requestStatus"))) {
                        throw ApiException.badRequest("Request " + rl.get("requestNumber") + " is " + rl.get("requestStatus")
                                + " — only approved requests can be sourced");
                    }
                    BigDecimal open = Money.dec(rl.get("quantity")).subtract(Money.dec(rl.get("orderedQuantity")));
                    if (open.signum() <= 0) {
                        throw ApiException.badRequest("\"" + rl.get("description") + "\" is already fully ordered");
                    }
                    if (onOpenRfq((String) rl.get("id"))) {
                        throw ApiException.badRequest("\"" + rl.get("description") + "\" is already on an open RFQ");
                    }
                    jdbc.sql("INSERT INTO \"RfqLine\" (id, \"organizationId\", \"rfqId\", \"lineNumber\", \"productId\","
                            + " description, quantity, \"uomId\", \"requiredDate\", \"requestLineId\", notes) VALUES (?, ?, ?, ?,"
                            + " ?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), orgId, id, ++n,
                            rl.get("productId"), rl.get("description"), open, rl.get("unitOfMeasureId"),
                            rl.get("requiredDate"), rl.get("id"), rl.get("notes")).update();
                }
            }
            for (LineInput l : free) {
                if (l.description() == null || l.description().isBlank() || l.quantity() == null
                        || l.quantity().signum() <= 0) {
                    throw ApiException.badRequest("Every line needs a description and a positive quantity");
                }
                jdbc.sql("INSERT INTO \"RfqLine\" (id, \"organizationId\", \"rfqId\", \"lineNumber\", \"productId\","
                        + " description, quantity, \"uomId\", \"requiredDate\", notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?)")
                        .params(UUID.randomUUID().toString(), orgId, id, ++n, blank(l.productId()), l.description().trim(),
                                l.quantity(), blank(l.uomId()), blank(l.requiredDate()), l.notes()).update();
            }
            for (String partner : in.supplierIds() == null ? List.<String>of() : in.supplierIds()) {
                invite(id, partner);
            }
            audit.recordInTx(AuditInput.of("Rfq", id, "create", null, Obj.of("rfqNumber", number, "lines", n)));
        });
        return get(id);
    }

    /** Invite (or re-invite) a supplier to quote. */
    public Map<String, Object> addSupplier(String rfqId, String partnerId) {
        Map<String, Object> rfq = require(rfqId);
        if (!List.of("draft", "sent").contains(rfq.get("status"))) {
            throw ApiException.badRequest("Suppliers can only be invited while the RFQ is draft or sent");
        }
        invite(rfqId, partnerId);
        return get(rfqId);
    }

    private void invite(String rfqId, String partnerId) {
        long ok = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(partnerId, org()).query(Long.class).single();
        if (ok == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        SupplierService.Eligibility e = suppliers.eligibility(partnerId, ProcurementPolicyService.PolicyContext.ORG,
                LocalDate.now());
        if ("blacklisted".equals(e.lifecycleStatus()) || "suspended".equals(e.lifecycleStatus())) {
            throw ApiException.badRequest("Cannot invite a supplier that is " + e.lifecycleStatus());
        }
        jdbc.sql("INSERT INTO \"RfqSupplier\" (id, \"organizationId\", \"rfqId\", \"partnerId\") VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (\"rfqId\", \"partnerId\") DO NOTHING").params(UUID.randomUUID().toString(), org(), rfqId,
                partnerId).update();
    }

    public Map<String, Object> removeSupplier(String rfqId, String partnerId) {
        Map<String, Object> rfq = require(rfqId);
        if (!"draft".equals(rfq.get("status"))) {
            throw ApiException.badRequest("Suppliers can only be removed from a draft RFQ");
        }
        jdbc.sql("DELETE FROM \"RfqSupplier\" WHERE \"rfqId\" = ? AND \"partnerId\" = ? AND \"organizationId\" = ?")
                .params(rfqId, partnerId, org()).update();
        return get(rfqId);
    }

    /** Draft → sent: the suppliers can now quote (printed / e-mailed / portal link in wave 7). */
    public Map<String, Object> send(String rfqId) {
        long suppliersCount = jdbc.sql("SELECT count(*) FROM \"RfqSupplier\" WHERE \"rfqId\" = ? AND \"organizationId\" = ?")
                .params(rfqId, org()).query(Long.class).single();
        if (suppliersCount == 0) {
            require(rfqId);
            throw ApiException.badRequest("Invite at least one supplier before sending the RFQ");
        }
        transition(rfqId, List.of("draft"), "sent", "\"sentAt\" = " + NOW, "Only a draft RFQ can be sent");
        events.publish("rfq.sent", Obj.of("organizationId", org(), "rfqId", rfqId));
        return get(rfqId);
    }

    /** Stop accepting quotations. */
    public Map<String, Object> close(String rfqId) {
        transition(rfqId, List.of("sent"), "closed", "\"closedAt\" = " + NOW, "Only a sent RFQ can be closed");
        return get(rfqId);
    }

    public Map<String, Object> cancel(String rfqId) {
        transition(rfqId, List.of("draft", "sent", "closed"), "cancelled", null, "This RFQ can no longer be cancelled");
        return get(rfqId);
    }

    // ── quotations ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Record a supplier's quotation (manual entry; the supplier portal uses the same path). A newer quotation from the
     * same supplier supersedes the previous one (revision + 1); every quoted price enters the price history.
     */
    public Map<String, Object> recordQuotation(String rfqId, QuoteInput in, String via) {
        Map<String, Object> rfq = require(rfqId);
        if (!"sent".equals(rfq.get("status"))) {
            throw ApiException.badRequest("Quotations can only be recorded while the RFQ is open (sent)");
        }
        long invited = jdbc.sql("SELECT count(*) FROM \"RfqSupplier\" WHERE \"rfqId\" = ? AND \"partnerId\" = ? AND"
                + " \"organizationId\" = ?").params(rfqId, in.partnerId(), org()).query(Long.class).single();
        if (invited == 0) {
            throw ApiException.badRequest("This supplier was not invited to the RFQ");
        }
        if (in.lines() == null || in.lines().isEmpty()) {
            throw ApiException.badRequest("Quote at least one line");
        }
        Map<String, Map<String, Object>> rfqLines = new LinkedHashMap<>();
        for (Map<String, Object> l : lines(rfqId)) {
            rfqLines.put((String) l.get("id"), l);
        }
        String currency = in.currencyCode() != null && !in.currencyCode().isBlank() ? in.currencyCode()
                : functionalCurrency();
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(st -> {
            int prev = jdbc.sql("SELECT COALESCE(MAX(revision), 0) FROM \"SupplierQuotation\" WHERE \"rfqId\" = ? AND"
                    + " \"partnerId\" = ?").params(rfqId, in.partnerId()).query(Integer.class).single();
            jdbc.sql("UPDATE \"SupplierQuotation\" SET status = 'superseded', \"updatedAt\" = " + NOW + " WHERE \"rfqId\" = ?"
                    + " AND \"partnerId\" = ? AND status = 'received'").params(rfqId, in.partnerId()).update();
            jdbc.sql("INSERT INTO \"SupplierQuotation\" (id, \"organizationId\", \"rfqId\", \"partnerId\", revision,"
                    + " \"supplierReference\", \"validUntil\", \"currencyCode\", \"exchangeRate\", \"leadTimeDays\","
                    + " \"paymentTermsDays\", \"freightAmount\", \"qualityScore\", notes, \"submittedVia\", \"createdBy\","
                    + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", "
                    + NOW + ")").params(id, org(), rfqId, in.partnerId(), prev + 1,
                    in.supplierReference(), blank(in.validUntil()), currency,
                    in.exchangeRate() == null ? BigDecimal.ONE : in.exchangeRate(), in.leadTimeDays(),
                    in.paymentTermsDays(), in.freightAmount() == null ? Money.ZERO : in.freightAmount(),
                    in.qualityScore(), in.notes(), via, user()).update();
            for (QuoteLine q : in.lines()) {
                Map<String, Object> rl = rfqLines.get(q.rfqLineId());
                if (rl == null) {
                    throw ApiException.badRequest("Line " + q.rfqLineId() + " is not on this RFQ");
                }
                if (q.unitPrice() == null || q.unitPrice().signum() < 0) {
                    throw ApiException.badRequest("Unit price must not be negative");
                }
                jdbc.sql("INSERT INTO \"SupplierQuotationLine\" (id, \"organizationId\", \"quotationId\", \"rfqLineId\","
                        + " \"unitPrice\", quantity, \"leadTimeDays\", \"taxRate\", alternative, notes) VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id, q.rfqLineId(), q.unitPrice(),
                        q.quantity(), q.leadTimeDays(), q.taxRate() == null ? Money.ZERO : q.taxRate(), q.alternative(),
                        q.notes()).update();
                suppliers.recordPrice(in.partnerId(), (String) rl.get("productId"), (String) rl.get("uomId"), currency,
                        q.unitPrice(), q.quantity() != null ? q.quantity() : Money.dec(rl.get("quantity")), LocalDate.now(),
                        in.validUntil() == null || in.validUntil().isBlank() ? null
                                : LocalDate.parse(in.validUntil().substring(0, 10)), "quotation", id);
            }
            jdbc.sql("UPDATE \"RfqSupplier\" SET status = 'responded', \"respondedAt\" = " + NOW + " WHERE \"rfqId\" = ?"
                    + " AND \"partnerId\" = ?").params(rfqId, in.partnerId()).update();
            audit.recordInTx(AuditInput.of("SupplierQuotation", id, "create", null, Obj.of("rfqId", rfqId, "partnerId",
                    in.partnerId(), "via", via)));
        });
        return get(rfqId);
    }

    /** A supplier declines to quote. */
    public Map<String, Object> decline(String rfqId, String partnerId, String reason) {
        require(rfqId);
        int n = jdbc.sql("UPDATE \"RfqSupplier\" SET status = 'declined', \"declineReason\" = ?, \"respondedAt\" = " + NOW
                + " WHERE \"rfqId\" = ? AND \"partnerId\" = ? AND \"organizationId\" = ?").params(reason, rfqId, partnerId,
                org()).update();
        if (n == 0) {
            throw ApiException.notFound("This supplier was not invited to the RFQ");
        }
        return get(rfqId);
    }

    // ── comparison ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Side-by-side comparison of the current quotations: per line the delivered unit cost in functional currency (price
     * plus the quotation's freight spread by value, converted with its rate) and the cheapest offer; per supplier the
     * total delivered cost, coverage and the weighted score with its components.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> comparison(String rfqId) {
        Map<String, Object> rfq = require(rfqId);
        Map<String, Integer> weights = weights(rfq.get("weights"));
        List<Map<String, Object>> rfqLines = lines(rfqId);
        List<Map<String, Object>> quotes = SqlRows.list(jdbc.sql("SELECT q.*, p.name AS \"partnerName\","
                + " sp.\"riskClass\" FROM \"SupplierQuotation\" q JOIN \"Partner\" p ON p.id = q.\"partnerId\" LEFT JOIN"
                + " \"SupplierProfile\" sp ON sp.\"partnerId\" = q.\"partnerId\" AND sp.\"organizationId\" = q.\"organizationId\""
                + " WHERE q.\"rfqId\" = ? AND q.\"organizationId\" = ? AND q.status IN ('received', 'accepted') ORDER BY"
                + " p.name").params(rfqId, org()));
        String functional = functionalCurrency();
        // delivered[quoteId][rfqLineId] = functional delivered unit cost
        Map<String, Map<String, Map<String, Object>>> offer = new LinkedHashMap<>();
        for (Map<String, Object> q : quotes) {
            BigDecimal rate = functional.equalsIgnoreCase(String.valueOf(q.get("currencyCode"))) ? BigDecimal.ONE
                    : Money.dec(q.get("exchangeRate"));
            List<Map<String, Object>> qLines = SqlRows.list(jdbc.sql("SELECT * FROM \"SupplierQuotationLine\" WHERE"
                    + " \"quotationId\" = ?").param(q.get("id")));
            BigDecimal goods = Money.ZERO;
            Map<String, BigDecimal> qtyOf = new LinkedHashMap<>();
            for (Map<String, Object> ql : qLines) {
                Map<String, Object> rl = rfqLines.stream().filter(l -> l.get("id").equals(ql.get("rfqLineId"))).findFirst()
                        .orElseThrow();
                BigDecimal qty = ql.get("quantity") != null ? Money.dec(ql.get("quantity")) : Money.dec(rl.get("quantity"));
                qtyOf.put((String) ql.get("rfqLineId"), qty);
                goods = goods.add(Money.dec(ql.get("unitPrice")).multiply(qty));
            }
            BigDecimal freight = Money.dec(q.get("freightAmount"));
            Map<String, Map<String, Object>> byLine = new LinkedHashMap<>();
            for (Map<String, Object> ql : qLines) {
                BigDecimal qty = qtyOf.get((String) ql.get("rfqLineId"));
                BigDecimal unit = Money.dec(ql.get("unitPrice"));
                BigDecimal share = goods.signum() > 0 ? freight.multiply(unit.multiply(qty)).divide(goods, 6,
                        RoundingMode.HALF_UP) : Money.ZERO;
                BigDecimal deliveredUnit = qty.signum() > 0 ? unit.multiply(qty).add(share).divide(qty, 6,
                        RoundingMode.HALF_UP).multiply(rate).setScale(6, RoundingMode.HALF_UP) : Money.ZERO;
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("quotationLineId", ql.get("id"));
                cell.put("unitPrice", unit);
                cell.put("quantity", qty);
                cell.put("leadTimeDays", ql.get("leadTimeDays") != null ? ql.get("leadTimeDays") : q.get("leadTimeDays"));
                cell.put("deliveredUnitCost", deliveredUnit);
                cell.put("deliveredTotal", deliveredUnit.multiply(qty).setScale(2, RoundingMode.HALF_UP));
                cell.put("alternative", ql.get("alternative"));
                byLine.put((String) ql.get("rfqLineId"), cell);
            }
            offer.put((String) q.get("id"), byLine);
        }
        // Cheapest delivered offer per line.
        List<Map<String, Object>> lineRows = new ArrayList<>();
        Map<String, BigDecimal> bestUnit = new LinkedHashMap<>();
        for (Map<String, Object> rl : rfqLines) {
            String lid = (String) rl.get("id");
            String bestQuote = null;
            BigDecimal best = null;
            List<Map<String, Object>> cells = new ArrayList<>();
            for (Map<String, Object> q : quotes) {
                Map<String, Object> cell = offer.get((String) q.get("id")).get(lid);
                if (cell == null) {
                    continue;
                }
                BigDecimal d = (BigDecimal) cell.get("deliveredUnitCost");
                if (best == null || d.compareTo(best) < 0) {
                    best = d;
                    bestQuote = (String) q.get("id");
                }
                Map<String, Object> c = new LinkedHashMap<>(cell);
                c.put("quotationId", q.get("id"));
                c.put("partnerId", q.get("partnerId"));
                cells.add(c);
            }
            if (best != null) {
                bestUnit.put(lid, best);
            }
            for (Map<String, Object> c : cells) {
                c.put("lowest", c.get("quotationId").equals(bestQuote));
            }
            Map<String, Object> row = new LinkedHashMap<>(rl);
            row.put("offers", cells);
            String winner = bestQuote;
            row.put("bestPartnerId", winner == null ? null : quotes.stream().filter(q -> q.get("id").equals(winner))
                    .findFirst().map(q -> q.get("partnerId")).orElse(null));
            lineRows.add(row);
        }
        // Weighted score per supplier.
        int maxTerms = quotes.stream().mapToInt(q -> q.get("paymentTermsDays") == null ? 0
                : ((Number) q.get("paymentTermsDays")).intValue()).max().orElse(0);
        int minLead = quotes.stream().mapToInt(this::leadOf).min().orElse(0);
        List<Map<String, Object>> supplierRows = new ArrayList<>();
        Map<String, Object> bestOverall = null;
        for (Map<String, Object> q : quotes) {
            Map<String, Map<String, Object>> byLine = offer.get((String) q.get("id"));
            BigDecimal total = Money.ZERO;
            BigDecimal priceSum = Money.ZERO;
            for (Map.Entry<String, Map<String, Object>> e : byLine.entrySet()) {
                total = total.add((BigDecimal) e.getValue().get("deliveredTotal"));
                BigDecimal d = (BigDecimal) e.getValue().get("deliveredUnitCost");
                BigDecimal b = bestUnit.get(e.getKey());
                priceSum = priceSum.add(d.signum() > 0 ? b.multiply(BigDecimal.valueOf(100)).divide(d, 4,
                        RoundingMode.HALF_UP) : BigDecimal.valueOf(100));
            }
            double coverage = rfqLines.isEmpty() ? 0 : (double) byLine.size() / rfqLines.size();
            double price = byLine.isEmpty() ? 0 : priceSum.doubleValue() / byLine.size() * coverage;
            int lead = leadOf(q);
            double delivery = lead <= 0 ? 100 : (minLead <= 0 ? 0 : 100.0 * minLead / lead);
            double terms = maxTerms <= 0 ? 100 : 100.0 * (q.get("paymentTermsDays") == null ? 0
                    : ((Number) q.get("paymentTermsDays")).intValue()) / maxTerms;
            double quality = q.get("qualityScore") != null ? Money.dec(q.get("qualityScore")).doubleValue()
                    : switch (String.valueOf(q.get("riskClass"))) {
                        case "low" -> 90;
                        case "medium" -> 70;
                        case "high" -> 40;
                        default -> 60;
                    };
            double performance = onTimeRate((String) q.get("partnerId"));
            Map<String, Double> parts = Map.of("price", price, "quality", quality, "delivery", delivery, "terms", terms,
                    "performance", performance);
            double wsum = 0;
            double score = 0;
            for (String c : CRITERIA) {
                int w = weights.getOrDefault(c, 0);
                wsum += w;
                score += w * parts.get(c);
            }
            score = wsum > 0 ? score / wsum : 0;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("quotationId", q.get("id"));
            s.put("partnerId", q.get("partnerId"));
            s.put("partnerName", q.get("partnerName"));
            s.put("revision", q.get("revision"));
            s.put("currencyCode", q.get("currencyCode"));
            s.put("validUntil", q.get("validUntil"));
            s.put("leadTimeDays", lead);
            s.put("paymentTermsDays", q.get("paymentTermsDays"));
            s.put("freightAmount", q.get("freightAmount"));
            s.put("deliveredTotal", total.setScale(2, RoundingMode.HALF_UP));
            s.put("linesQuoted", byLine.size());
            s.put("coveragePct", round(coverage * 100));
            Map<String, Object> scores = new LinkedHashMap<>();
            for (String c : CRITERIA) {
                scores.put(c, round(parts.get(c)));
            }
            s.put("scores", scores);
            s.put("weightedScore", round(score));
            supplierRows.add(s);
            if (bestOverall == null || score > ((Number) bestOverall.get("weightedScore")).doubleValue()) {
                bestOverall = s;
            }
        }
        return Obj.of("rfq", rfq, "weights", weights, "functionalCurrency", functional, "lines", lineRows,
                "suppliers", supplierRows, "recommendedPartnerId", bestOverall == null ? null : bestOverall.get("partnerId"));
    }

    private int leadOf(Map<String, Object> q) {
        return q.get("leadTimeDays") == null ? 0 : ((Number) q.get("leadTimeDays")).intValue();
    }

    /** Share of the supplier's order lines (last 12 months) received in full by their due date; 70 without history. */
    private double onTimeRate(String partnerId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT count(*) AS total, count(*) FILTER (WHERE l.\"receivedQuantity\""
                + " >= l.quantity AND (SELECT MAX(g.\"receivedAt\") FROM \"GoodsReceiptLine\" gl JOIN \"GoodsReceiptNote\" g ON"
                + " g.id = gl.\"goodsReceiptId\" WHERE gl.\"purchaseOrderLineId\" = l.id AND g.status = 'posted')::date <="
                + " COALESCE(o.\"promisedDate\", o.\"expectedDeliveryDate\"::date)) AS \"onTime\" FROM \"PurchaseOrderLine\" l"
                + " JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\" WHERE o.\"organizationId\" = ? AND o.\"partnerId\""
                + " = ? AND COALESCE(o.\"promisedDate\", o.\"expectedDeliveryDate\"::date) IS NOT NULL AND"
                + " COALESCE(o.\"promisedDate\", o.\"expectedDeliveryDate\"::date) < CURRENT_DATE AND o.\"orderDate\" >="
                + " CURRENT_DATE - 365 AND o.status <> 'cancelled'").params(org(), partnerId));
        long total = ((Number) r.get("total")).longValue();
        return total == 0 ? 70 : 100.0 * ((Number) r.get("onTime")).longValue() / total;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // ── award & orders ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Award lines (split awards allowed). Choosing anything but the cheapest delivered offer needs a justification. When
     * an {@code rfq_award} approval workflow is active the RFQ waits for approval before orders can be created.
     */
    public Map<String, Object> award(String rfqId, List<AwardLine> awards) {
        Map<String, Object> rfq = require(rfqId);
        if (!List.of("sent", "closed").contains(rfq.get("status"))) {
            throw ApiException.badRequest("Only a sent or closed RFQ can be awarded");
        }
        if (awards == null || awards.isEmpty()) {
            throw ApiException.badRequest("Award at least one line");
        }
        Map<String, Object> cmp = comparison(rfqId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) cmp.get("lines");
        BigDecimal total = Money.ZERO;
        List<Object[]> rows = new ArrayList<>();
        for (AwardLine a : awards) {
            Map<String, Object> line = lines.stream().filter(l -> l.get("id").equals(a.rfqLineId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Line " + a.rfqLineId() + " is not on this RFQ"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> offers = (List<Map<String, Object>>) line.get("offers");
            Map<String, Object> chosen = offers.stream().filter(o -> o.get("partnerId").equals(a.partnerId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("No quotation from this supplier for \""
                            + line.get("description") + "\""));
            boolean lowest = Boolean.TRUE.equals(chosen.get("lowest"));
            if (!lowest && (a.justification() == null || a.justification().isBlank())) {
                throw ApiException.badRequest("A justification is required when the lowest offer is not chosen (\""
                        + line.get("description") + "\")");
            }
            BigDecimal qty = a.quantity() != null ? a.quantity() : Money.dec(chosen.get("quantity"));
            if (qty.signum() <= 0 || qty.compareTo(Money.dec(line.get("quantity"))) > 0) {
                throw ApiException.badRequest("Award quantity for \"" + line.get("description") + "\" must be between 0"
                        + " and " + JsNumbers.dec(Money.dec(line.get("quantity"))));
            }
            total = total.add(Money.dec(chosen.get("unitPrice")).multiply(qty));
            rows.add(new Object[] {a.rfqLineId(), chosen.get("quotationLineId"), a.partnerId(), qty,
                    chosen.get("unitPrice"), lowest, a.justification()});
        }
        // Split awards may not exceed the line quantity in total.
        Map<String, BigDecimal> perLine = new LinkedHashMap<>();
        for (Object[] r : rows) {
            perLine.merge((String) r[0], (BigDecimal) r[3], BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : perLine.entrySet()) {
            Map<String, Object> line = lines.stream().filter(l -> l.get("id").equals(e.getKey())).findFirst().orElseThrow();
            if (e.getValue().compareTo(Money.dec(line.get("quantity"))) > 0) {
                throw ApiException.badRequest("Awards for \"" + line.get("description") + "\" exceed the RFQ quantity");
            }
        }
        BigDecimal amount = total;
        Map<String, Object> gate = approvals.checkOrRequestApproval("rfq_award", rfqId, Obj.of("amount",
                JsNumbers.js(amount), "rfqNumber", rfq.get("rfqNumber"), "createdBy", user()));
        boolean pending = gate != null && Boolean.TRUE.equals(gate.get("needsApproval"));
        tx.executeWithoutResult(st -> {
            jdbc.sql("DELETE FROM \"RfqAward\" WHERE \"rfqId\" = ? AND \"organizationId\" = ? AND \"purchaseOrderId\" IS NULL")
                    .params(rfqId, org()).update();
            for (Object[] r : rows) {
                jdbc.sql("INSERT INTO \"RfqAward\" (id, \"organizationId\", \"rfqId\", \"rfqLineId\", \"quotationLineId\","
                        + " \"partnerId\", quantity, \"unitPrice\", \"lowestPrice\", justification, \"createdBy\") VALUES (?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), rfqId, r[0], r[1], r[2], r[3],
                        r[4], r[5], r[6], user()).update();
            }
            jdbc.sql("UPDATE \"Rfq\" SET status = ?, \"awardedAt\" = " + NOW + ", \"awardedBy\" = ?, \"closedAt\" ="
                    + " COALESCE(\"closedAt\", " + NOW + "), version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(pending ? "closed" : "awarded", user(), rfqId, org()).update();
            List<String> winners = rows.stream().map(r -> (String) r[2]).distinct().toList();
            jdbc.sql("UPDATE \"SupplierQuotation\" SET status = CASE WHEN \"partnerId\" = ANY(?::text[]) THEN 'accepted' ELSE"
                    + " 'rejected' END, \"updatedAt\" = " + NOW + " WHERE \"rfqId\" = ? AND status = 'received'")
                    .params(winners.toArray(String[]::new), rfqId).update();
            audit.recordInTx(AuditInput.of("Rfq", rfqId, "update", null, Obj.of("awarded", rows.size(), "amount",
                    JsNumbers.js(amount), "pendingApproval", pending)));
        });
        if (!pending) {
            events.publish("rfq.awarded", Obj.of("organizationId", org(), "rfqId", rfqId));
        }
        Map<String, Object> out = get(rfqId);
        out.put("approvalRequired", pending);
        out.put("approvalRequestId", pending ? gate.get("requestId") : null);
        return out;
    }

    /** One purchase order per awarded supplier (request lines stay linked; nothing is ordered twice). */
    public Map<String, Object> createOrders(String rfqId, String warehouseId, String paymentType, String expectedDate) {
        Map<String, Object> rfq = require(rfqId);
        if ("closed".equals(rfq.get("status"))) {
            Map<String, Object> gate = approvals.checkOrRequestApproval("rfq_award", rfqId, Obj.of("amount", 0));
            if (gate == null || !Boolean.TRUE.equals(gate.get("needsApproval"))) {
                transition(rfqId, List.of("closed"), "awarded", null, "RFQ is not awarded");
            } else {
                throw ApiException.badRequest("The award is awaiting approval (request " + gate.get("requestId") + ")");
            }
        } else if (!"awarded".equals(require(rfqId).get("status"))) {
            throw ApiException.badRequest("Award the RFQ before creating orders");
        }
        if (warehouseId == null || warehouseId.isBlank()) {
            throw ApiException.badRequest("warehouseId is required");
        }
        List<Map<String, Object>> open = SqlRows.list(jdbc.sql("SELECT a.*, l.\"requestLineId\", l.\"productId\","
                + " l.description, l.\"uomId\", ql.\"taxRate\", q.\"currencyCode\", q.\"exchangeRate\" FROM \"RfqAward\" a JOIN"
                + " \"RfqLine\" l ON l.id = a.\"rfqLineId\" JOIN \"SupplierQuotationLine\" ql ON ql.id = a.\"quotationLineId\""
                + " JOIN \"SupplierQuotation\" q ON q.id = ql.\"quotationId\" WHERE a.\"rfqId\" = ? AND a.\"organizationId\" = ?"
                + " AND a.\"purchaseOrderId\" IS NULL ORDER BY a.\"partnerId\", l.\"lineNumber\"").params(rfqId, org()));
        if (open.isEmpty()) {
            throw ApiException.badRequest("Every award already has a purchase order");
        }
        Map<String, List<Map<String, Object>>> bySupplier = new LinkedHashMap<>();
        for (Map<String, Object> a : open) {
            bySupplier.computeIfAbsent((String) a.get("partnerId"), k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : bySupplier.entrySet()) {
            Map<String, Object> first = e.getValue().getFirst();
            List<OrderLine> lines = e.getValue().stream().map(a -> new OrderLine((String) a.get("requestLineId"),
                    Money.dec(a.get("quantity")), Money.dec(a.get("unitPrice")), Money.dec(a.get("taxRate")), null,
                    (String) a.get("productId"), (String) a.get("description"), (String) a.get("uomId"))).toList();
            Map<String, Object> po = tx.execute(st -> {
                Map<String, Object> order = orders.createOrder(new OrderInput(e.getKey(), warehouseId,
                        (String) rfq.get("branchId"), paymentType, (String) first.get("currencyCode"),
                        Money.dec(first.get("exchangeRate")), expectedDate, "From " + rfq.get("rfqNumber"), null, lines));
                jdbc.sql("UPDATE \"RfqAward\" SET \"purchaseOrderId\" = ? WHERE id = ANY(?::text[])").params(order.get("id"),
                        e.getValue().stream().map(a -> a.get("id")).toArray(String[]::new)).update();
                return order;
            });
            created.add(Obj.of("id", po.get("id"), "orderNumber", po.get("orderNumber"), "partnerId", e.getKey(),
                    "status", po.get("status")));
        }
        return Obj.of("rfqId", rfqId, "orders", created);
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String extra = "";
        if (status != null && !status.isBlank()) {
            extra = " AND r.status = ?";
            params.add(status);
        }
        return SqlRows.list(jdbc.sql("SELECT r.id, r.\"rfqNumber\", r.title, r.status, r.deadline, r.\"createdAt\","
                + " (SELECT count(*) FROM \"RfqLine\" l WHERE l.\"rfqId\" = r.id) AS lines, (SELECT count(*) FROM"
                + " \"RfqSupplier\" s WHERE s.\"rfqId\" = r.id) AS invited, (SELECT count(*) FROM \"RfqSupplier\" s WHERE"
                + " s.\"rfqId\" = r.id AND s.status = 'responded') AS responded FROM \"Rfq\" r WHERE r.\"organizationId\" = ?"
                + extra + " ORDER BY r.\"createdAt\" DESC LIMIT 300").params(params));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String rfqId) {
        Map<String, Object> rfq = require(rfqId);
        rfq.put("lines", lines(rfqId));
        rfq.put("suppliers", SqlRows.list(jdbc.sql("SELECT s.\"partnerId\", p.name AS \"partnerName\", s.status,"
                + " s.\"invitedAt\", s.\"respondedAt\", s.\"declineReason\", (SELECT q.revision FROM \"SupplierQuotation\" q"
                + " WHERE q.\"rfqId\" = s.\"rfqId\" AND q.\"partnerId\" = s.\"partnerId\" ORDER BY q.revision DESC LIMIT 1) AS"
                + " \"lastRevision\" FROM \"RfqSupplier\" s JOIN \"Partner\" p ON p.id = s.\"partnerId\" WHERE s.\"rfqId\" = ?"
                + " AND s.\"organizationId\" = ? ORDER BY p.name").params(rfqId, org())));
        rfq.put("awards", SqlRows.list(jdbc.sql("SELECT a.*, p.name AS \"partnerName\", o.\"orderNumber\" FROM"
                + " \"RfqAward\" a JOIN \"Partner\" p ON p.id = a.\"partnerId\" LEFT JOIN \"PurchaseOrder\" o ON o.id ="
                + " a.\"purchaseOrderId\" WHERE a.\"rfqId\" = ? AND a.\"organizationId\" = ?").params(rfqId, org())));
        return rfq;
    }

    private Map<String, Object> require(String rfqId) {
        Map<String, Object> rfq = SqlRows.one(jdbc.sql("SELECT * FROM \"Rfq\" WHERE id = ? AND \"organizationId\" = ?")
                .params(rfqId, org()));
        if (rfq == null) {
            throw ApiException.notFound("RFQ not found");
        }
        rfq.put("weights", weights(rfq.get("weights")));
        return rfq;
    }

    private List<Map<String, Object>> lines(String rfqId) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"RfqLine\" WHERE \"rfqId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " \"lineNumber\"").params(rfqId, org()));
    }

    /** Is the request line on an RFQ that is still running? (it then waits for the award instead of being ordered) */
    boolean onOpenRfq(String requestLineId) {
        return jdbc.sql("SELECT count(*) FROM \"RfqLine\" l JOIN \"Rfq\" r ON r.id = l.\"rfqId\" WHERE l.\"requestLineId\" = ?"
                + " AND r.status IN ('draft', 'sent', 'closed')").param(requestLineId).query(Long.class).single() > 0;
    }

    private void transition(String rfqId, List<String> from, String to, String set, String error) {
        int n = jdbc.sql("UPDATE \"Rfq\" SET status = ?" + (set == null ? "" : ", " + set) + ", version = version + 1,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = ANY(?::text[])")
                .params(to, rfqId, org(), from.toArray(String[]::new)).update();
        if (n == 0) {
            require(rfqId);
            throw ApiException.badRequest(error);
        }
        audit.record(AuditInput.of("Rfq", rfqId, "update", null, Obj.of("status", to)));
    }

    private String functionalCurrency() {
        return jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org()).query(String.class)
                .single();
    }

    private static String weightsJson(Map<String, Integer> in) {
        Map<String, Integer> w = new LinkedHashMap<>(Map.of("price", 40, "quality", 25, "delivery", 20, "terms", 10,
                "performance", 5));
        if (in != null) {
            for (Map.Entry<String, Integer> e : in.entrySet()) {
                if (!CRITERIA.contains(e.getKey())) {
                    throw ApiException.badRequest("Unknown evaluation criterion: " + e.getKey());
                }
                if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 100) {
                    throw ApiException.badRequest("Weights must be between 0 and 100");
                }
                w.put(e.getKey(), e.getValue());
            }
        }
        if (w.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw ApiException.badRequest("Evaluation weights must add up to 100");
        }
        return EntityJson.toJson(w);
    }

    private static Map<String, Integer> weights(Object raw) {
        Object parsed = EntityJson.parseJson(raw);
        Map<String, Integer> w = new LinkedHashMap<>();
        if (parsed instanceof JsonNode n && n.isObject()) {
            n.properties().forEach(e -> w.put(e.getKey(), e.getValue().asInt()));
        } else if (parsed instanceof Map<?, ?> m) {
            m.forEach((k, v) -> w.put(String.valueOf(k), ((Number) v).intValue()));
        }
        return w;
    }

    private static Object ts(String iso) {
        return iso == null || iso.isBlank() ? null : SqlRows.ts(Instant.parse(iso.length() == 10 ? iso + "T23:59:59Z"
                : iso));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
