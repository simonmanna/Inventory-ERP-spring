package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
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

/**
 * Purchase agreements (ADR-PROCUREMENT-001 wave 4): blanket agreements (committed quantity and / or value over a
 * period) and rate contracts (agreed prices, no commitment). Activated by someone other than the author; releases
 * become ordinary purchase orders at the agreed price, locked so concurrent releases never exceed the commitment. An
 * agreement is {@code exhausted} when fully released and {@code expired} after its end date (daily job).
 */
@Service
public class PurchaseAgreementService {

    public record LineInput(String productId, String description, String uomId, BigDecimal unitPrice,
            BigDecimal taxRate, BigDecimal committedQuantity) {}

    public record CreateInput(String partnerId, String type, String title, String validFrom, String validTo,
            String currencyCode, BigDecimal committedValue, String branchId, String rfqId, String terms, String notes,
            List<LineInput> lines) {}

    public record ReleaseLine(String agreementLineId, BigDecimal quantity) {}

    public record ReleaseInput(String warehouseId, String expectedDeliveryDate, String paymentType, String notes,
            List<ReleaseLine> lines) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final EventBus events;
    private final PurchaseOrdersService orders;
    private final SupplierService suppliers;
    private final TransactionTemplate tx;

    public PurchaseAgreementService(JdbcClient jdbc, SequenceService seq, AuditService audit, EventBus events,
            PurchaseOrdersService orders, SupplierService suppliers, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.events = events;
        this.orders = orders;
        this.suppliers = suppliers;
        this.tx = new TransactionTemplate(txm);
    }

    public Map<String, Object> create(CreateInput in) {
        if (in.lines() == null || in.lines().isEmpty()) {
            throw ApiException.badRequest("Add at least one line");
        }
        if (!List.of("blanket", "rate").contains(in.type() == null ? "blanket" : in.type())) {
            throw ApiException.badRequest("type must be blanket or rate");
        }
        LocalDate from = date(in.validFrom(), "validFrom");
        LocalDate to = date(in.validTo(), "validTo");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("validTo must not be before validFrom");
        }
        long partner = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL").params(in.partnerId(), org()).query(Long.class).single();
        if (partner == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        String currency = in.currencyCode() != null && !in.currencyCode().isBlank() ? in.currencyCode()
                : jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org()).query(String.class)
                        .single();
        String id = UUID.randomUUID().toString();
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        tx.executeWithoutResult(st -> {
            String number = seq.next("pa:" + year, "PA-" + year + "-", 5);
            jdbc.sql("INSERT INTO \"PurchaseAgreement\" (id, \"organizationId\", \"agreementNumber\", \"partnerId\", type,"
                    + " title, \"validFrom\", \"validTo\", \"currencyCode\", \"committedValue\", \"branchId\", \"rfqId\", terms, notes,"
                    + " \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW
                    + ", " + NOW + ")").params(id, org(), number, in.partnerId(), in.type() == null ? "blanket" : in.type(),
                    in.title() == null || in.title().isBlank() ? number : in.title().trim(), from, to, currency,
                    in.committedValue(), in.branchId(), in.rfqId(), in.terms(), in.notes(), user()).update();
            int n = 0;
            for (LineInput l : in.lines()) {
                if (l.description() == null || l.description().isBlank()) {
                    throw ApiException.badRequest("Every line needs a description");
                }
                if (l.unitPrice() == null || l.unitPrice().signum() < 0) {
                    throw ApiException.badRequest("Unit price must not be negative");
                }
                jdbc.sql("INSERT INTO \"PurchaseAgreementLine\" (id, \"organizationId\", \"agreementId\", \"lineNumber\","
                        + " \"productId\", description, \"uomId\", \"unitPrice\", \"taxRate\", \"committedQuantity\") VALUES (?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id, ++n,
                        blank(l.productId()), l.description().trim(), blank(l.uomId()), l.unitPrice(),
                        l.taxRate() == null ? Money.ZERO : l.taxRate(), l.committedQuantity()).update();
            }
            audit.recordInTx(AuditInput.of("PurchaseAgreement", id, "create", null, Obj.of("agreementNumber", number)));
        });
        return get(id);
    }

    /** Draft → active, by someone other than the author; agreed prices enter the price history. */
    public Map<String, Object> activate(String id) {
        Map<String, Object> pa = require(id);
        if (user() != null && user().equals(pa.get("createdBy"))) {
            throw ApiException.forbidden("An agreement must be approved by someone other than its author");
        }
        transition(id, List.of("draft"), "active", "\"approvedBy\" = ?, \"approvedAt\" = " + NOW, user(), null,
                "Only a draft agreement can be activated");
        for (Map<String, Object> l : lines(id)) {
            suppliers.recordPrice((String) pa.get("partnerId"), (String) l.get("productId"), (String) l.get("uomId"),
                    (String) pa.get("currencyCode"), Money.dec(l.get("unitPrice")), (BigDecimal) l.get("committedQuantity"),
                    toLocal(pa.get("validFrom")), toLocal(pa.get("validTo")), "agreement", id);
        }
        events.publish("agreement.activated", Obj.of("organizationId", org(), "agreementId", id));
        return get(id);
    }

    public Map<String, Object> hold(String id, String reason) {
        transition(id, List.of("active"), "on_hold", null, null, reason, "Only an active agreement can be put on hold");
        return get(id);
    }

    public Map<String, Object> resume(String id) {
        transition(id, List.of("on_hold"), "active", null, null, null, "Only an agreement on hold can be resumed");
        return get(id);
    }

    public Map<String, Object> terminate(String id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reason is required to terminate an agreement");
        }
        transition(id, List.of("draft", "active", "on_hold"), "terminated", null, null, reason,
                "This agreement can no longer be terminated");
        return get(id);
    }

    /**
     * Release (call-off) against an active agreement: a purchase order at the agreed prices. Lines and header are locked;
     * a blanket agreement refuses releases beyond its committed quantity or value.
     */
    public Map<String, Object> release(String id, ReleaseInput in) {
        if (in.lines() == null || in.lines().isEmpty()) {
            throw ApiException.badRequest("Release at least one line");
        }
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> pa = SqlRows.one(jdbc.sql("SELECT * FROM \"PurchaseAgreement\" WHERE id = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE").params(id, orgId));
            if (pa == null) {
                throw ApiException.notFound("Agreement not found");
            }
            if (!"active".equals(pa.get("status"))) {
                throw ApiException.badRequest("Releases need an active agreement (status: " + pa.get("status") + ")");
            }
            LocalDate today = LocalDate.now();
            if (today.isBefore(toLocal(pa.get("validFrom"))) || today.isAfter(toLocal(pa.get("validTo")))) {
                throw ApiException.badRequest("The agreement is valid from " + pa.get("validFrom") + " to "
                        + pa.get("validTo"));
            }
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"PurchaseAgreementLine\" WHERE"
                    + " \"agreementId\" = ? AND \"organizationId\" = ? ORDER BY id FOR UPDATE").params(id, orgId))) {
                byId.put((String) l.get("id"), l);
            }
            List<PurchaseOrdersService.LineInput> poLines = new ArrayList<>();
            BigDecimal value = Money.ZERO;
            for (ReleaseLine rl : in.lines()) {
                Map<String, Object> al = byId.get(rl.agreementLineId());
                if (al == null) {
                    throw ApiException.badRequest("Line " + rl.agreementLineId() + " is not on this agreement");
                }
                if (rl.quantity() == null || rl.quantity().signum() <= 0) {
                    throw ApiException.badRequest("Release quantity must be positive");
                }
                if (al.get("committedQuantity") != null) {
                    BigDecimal left = Money.dec(al.get("committedQuantity")).subtract(Money.dec(al.get("releasedQuantity")));
                    if (rl.quantity().compareTo(left) > 0) {
                        throw ApiException.badRequest("Only " + JsNumbers.dec(left) + " of \"" + al.get("description")
                                + "\" is left on " + pa.get("agreementNumber"));
                    }
                }
                value = value.add(rl.quantity().multiply(Money.dec(al.get("unitPrice"))));
                poLines.add(new PurchaseOrdersService.LineInput((String) al.get("productId"), (String) al.get("description"),
                        rl.quantity(), (String) al.get("uomId"), Money.dec(al.get("unitPrice")), null,
                        Money.dec(al.get("taxRate")), null));
            }
            if (pa.get("committedValue") != null) {
                BigDecimal leftValue = Money.dec(pa.get("committedValue")).subtract(Money.dec(pa.get("releasedValue")));
                if (value.compareTo(leftValue) > 0) {
                    throw ApiException.badRequest("This release (" + JsNumbers.dec(value) + ") exceeds the "
                            + JsNumbers.dec(leftValue) + " left on " + pa.get("agreementNumber"));
                }
            }
            Map<String, Object> po = orders.create(new PurchaseOrdersService.CreateInput((String) pa.get("partnerId"), (String) pa.get("branchId"),
                    "Release of " + pa.get("agreementNumber"), in.expectedDeliveryDate(), (String) pa.get("currencyCode"),
                    (BigDecimal) null, in.paymentType() == null ? "credit" : in.paymentType(), in.notes(),
                    (String) pa.get("terms"),
                    in.warehouseId(), null, poLines, null));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> created = (List<Map<String, Object>>) po.get("lines");
            for (int i = 0; i < in.lines().size(); i++) {
                ReleaseLine rl = in.lines().get(i);
                jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"agreementLineId\" = ? WHERE id = ? AND \"organizationId\" = ?")
                        .params(rl.agreementLineId(), created.get(i).get("id"), orgId).update();
                jdbc.sql("UPDATE \"PurchaseAgreementLine\" SET \"releasedQuantity\" = \"releasedQuantity\" + ? WHERE id = ?")
                        .params(rl.quantity(), rl.agreementLineId()).update();
            }
            jdbc.sql("UPDATE \"PurchaseAgreement\" SET \"releasedValue\" = \"releasedValue\" + ?, version = version + 1,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(value, id).update();
            boolean exhausted = pa.get("committedValue") != null && Money.dec(pa.get("releasedValue")).add(value)
                    .compareTo(Money.dec(pa.get("committedValue"))) >= 0
                    || jdbc.sql("SELECT count(*) FROM \"PurchaseAgreementLine\" WHERE \"agreementId\" = ? AND"
                            + " \"committedQuantity\" IS NOT NULL").param(id).query(Long.class).single() > 0
                    && jdbc.sql("SELECT count(*) FROM \"PurchaseAgreementLine\" WHERE \"agreementId\" = ? AND"
                            + " (\"committedQuantity\" IS NULL OR \"releasedQuantity\" < \"committedQuantity\")").param(id)
                            .query(Long.class).single() == 0;
            if (exhausted) {
                jdbc.sql("UPDATE \"PurchaseAgreement\" SET status = 'exhausted', \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .param(id).update();
                events.publish("agreement.exhausted", Obj.of("organizationId", orgId, "agreementId", id));
            }
            audit.recordInTx(AuditInput.of("PurchaseAgreement", id, "update", null, Obj.of("release",
                    po.get("orderNumber"), "value", JsNumbers.dec(value))));
            return po;
        });
    }

    /** Daily job: active / on-hold agreements past their end date expire. */
    public int expireDue() {
        return jdbc.sql("UPDATE \"PurchaseAgreement\" SET status = 'expired', \"updatedAt\" = " + NOW + " WHERE"
                + " \"organizationId\" = ? AND status IN ('active', 'on_hold') AND \"validTo\" < CURRENT_DATE").param(org())
                .update();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String partnerId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" a.\"organizationId\" = ?");
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?");
            params.add(status);
        }
        if (partnerId != null && !partnerId.isBlank()) {
            where.append(" AND a.\"partnerId\" = ?");
            params.add(partnerId);
        }
        return SqlRows.list(jdbc.sql("SELECT a.*, p.name AS \"partnerName\" FROM \"PurchaseAgreement\" a JOIN \"Partner\" p"
                + " ON p.id = a.\"partnerId\" WHERE" + where + " ORDER BY a.\"validTo\" DESC LIMIT 300").params(params));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> pa = require(id);
        pa.put("lines", lines(id));
        pa.put("releases", SqlRows.list(jdbc.sql("SELECT DISTINCT o.id, o.\"orderNumber\", o.status::text AS status,"
                + " o.\"orderDate\", o.\"totalAmount\" FROM \"PurchaseOrderLine\" l JOIN \"PurchaseAgreementLine\" al ON al.id ="
                + " l.\"agreementLineId\" JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\" WHERE al.\"agreementId\" = ?"
                + " AND o.\"organizationId\" = ? ORDER BY o.\"orderDate\" DESC").params(id, org())));
        pa.put("partner", SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(pa.get("partnerId"), org())));
        return pa;
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> pa = SqlRows.one(jdbc.sql("SELECT * FROM \"PurchaseAgreement\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (pa == null) {
            throw ApiException.notFound("Agreement not found");
        }
        return pa;
    }

    private List<Map<String, Object>> lines(String id) {
        return SqlRows.list(jdbc.sql("SELECT l.*, CASE WHEN l.\"committedQuantity\" IS NULL THEN NULL ELSE"
                + " l.\"committedQuantity\" - l.\"releasedQuantity\" END AS \"remainingQuantity\" FROM \"PurchaseAgreementLine\" l"
                + " WHERE l.\"agreementId\" = ? AND l.\"organizationId\" = ? ORDER BY l.\"lineNumber\"").params(id, org()));
    }

    private void transition(String id, List<String> from, String to, String set, Object setParam, String reason,
            String error) {
        List<Object> params = new ArrayList<>();
        params.add(to);
        if (setParam != null) {
            params.add(setParam);
        }
        params.add(reason);
        params.addAll(List.of(id, org(), from.toArray(String[]::new)));
        int n = jdbc.sql("UPDATE \"PurchaseAgreement\" SET status = ?" + (set == null ? "" : ", " + set)
                + ", \"statusReason\" = COALESCE(?, \"statusReason\"), version = version + 1, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ? AND status = ANY(?::text[])").params(params).update();
        if (n == 0) {
            require(id);
            throw ApiException.badRequest(error);
        }
        audit.record(AuditInput.of("PurchaseAgreement", id, "update", null, Obj.of("status", to, "reason", reason)));
    }

    private static LocalDate date(String s, String field) {
        if (s == null || s.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static LocalDate toLocal(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return v == null ? null : LocalDate.parse(String.valueOf(v).substring(0, 10));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
