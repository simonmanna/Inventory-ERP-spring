package com.inventoryerp.invoicing.application;

import com.inventoryerp.core.application.PartnerService;
import com.inventoryerp.core.application.PaymentTermService;
import com.inventoryerp.kernel.approvals.ControlledActionService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit control for non-POS sales (SALES-1). Exposure = open receivables + confirmed unbilled orders + manual delivery
 * orders not yet invoiced. {@link #assertCanSell} runs at order save, manual delivery order, dispatch hold check and
 * invoice post, and ends in {@link PaymentTermService#assertCreditOk}. POS has its own check and never calls this class.
 *
 * <p>A limit of 0 means unlimited, so a customer with no limit and no hold costs one read and is never blocked. The
 * partner row is locked ({@code FOR NO KEY UPDATE}, which does not block foreign-key checks from POS inserts) while the
 * exposure is measured, so two simultaneous sales cannot both use the last headroom. Reads of order and delivery tables
 * are plain SQL queries.
 */
@Service
public class CreditControlService {

    /** Modes paid on the spot: no credit is extended, so neither limit nor hold applies. */
    private static final Set<String> PREPAID = Set.of("cash", "card", "mobile_money");

    /** One customer's position. {@code overrideAmount} and {@code holdOverridden} apply to the current user. */
    public record Exposure(String partnerId, BigDecimal creditLimit, boolean creditHold, BigDecimal openReceivables,
            BigDecimal overdueReceivables, BigDecimal unbilledOrders, BigDecimal uninvoicedDeliveries,
            BigDecimal overrideAmount, boolean holdOverridden) {

        public BigDecimal total() {
            return openReceivables.add(unbilledOrders).add(uninvoicedDeliveries);
        }

        /** 0 = unlimited, whatever the overrides say. */
        public BigDecimal effectiveLimit() {
            return creditLimit.signum() <= 0 ? BigDecimal.ZERO : creditLimit.add(overrideAmount);
        }

        public BigDecimal headroom() {
            return creditLimit.signum() <= 0 ? null : effectiveLimit().subtract(total());
        }

        public boolean exceeded() {
            return creditLimit.signum() > 0 && total().compareTo(effectiveLimit()) > 0;
        }
    }

    /** {@code POST /credit-control/customers/:id/overrides}. */
    public record OverrideInput(BigDecimal amount, Boolean coversHold, String reason, Instant expiresAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PartnerService partners;
    private final ControlledActionService controlled;

    public CreditControlService(JdbcClient jdbc, AuditService audit, PartnerService partners,
            ControlledActionService controlled) {
        this.controlled = controlled;
        this.jdbc = jdbc;
        this.audit = audit;
        this.partners = partners;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private Map<String, Object> partner(String partnerId) {
        return SqlRows.one(jdbc.sql("SELECT id, name, \"creditLimit\", \"creditHold\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(partnerId, org()));
    }

    // ------------------------------------------------------------------ enforcement

    /**
     * Refuse a credit sale of {@code amount} when the customer is on hold or the exposure would pass the limit.
     *
     * @param excludeOrderId the order being saved, so it is not counted twice; null when none
     * @param paymentMode the document's payment mode; cash, card and mobile money skip the check
     */
    public void assertCanSell(String partnerId, BigDecimal amount, String excludeOrderId, String paymentMode) {
        if (partnerId == null || partnerId.isEmpty() || (paymentMode != null && PREPAID.contains(paymentMode))) {
            return;
        }
        Map<String, Object> p = partner(partnerId);
        if (p == null) {
            return;
        }
        boolean hold = Boolean.TRUE.equals(p.get("creditHold"));
        if (!hold && Money.dec(p.get("creditLimit")).signum() <= 0) {
            return;
        }
        jdbc.sql("SELECT id FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? FOR NO KEY UPDATE")
                .params(partnerId, org()).query().listOfRows();
        Exposure e = exposure(partnerId, excludeOrderId);
        PaymentTermService.assertCreditOk(hold && !e.holdOverridden(), e.effectiveLimit(), e.total(), amount);
    }

    /** Goods about to leave for a customer on hold are stopped; an override that covers the hold lets them go. */
    public void assertNotOnHold(String partnerId) {
        Map<String, Object> p = partnerId == null ? null : partner(partnerId);
        if (p != null && Boolean.TRUE.equals(p.get("creditHold")) && !waiverOf(partnerId).holdCovered()) {
            throw ApiException.badRequest("Customer is on credit hold.");
        }
    }

    // ------------------------------------------------------------------ exposure

    private record Waiver(BigDecimal amount, boolean holdCovered) {}

    /** Valid overrides, leaving out those the current user granted: nobody clears their own sale. */
    private Waiver waiverOf(String partnerId) {
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT COALESCE(sum(amount), 0) AS amount, COALESCE(bool_or("
                + "\"coversHold\"), false) AS hold FROM \"PartnerCreditOverride\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND \"revokedAt\" IS NULL AND \"expiresAt\" > now() AND \"createdBy\" IS DISTINCT"
                + " FROM ?").params(org(), partnerId, TenantContext.userId()));
        return new Waiver(Money.dec(o.get("amount")), Boolean.TRUE.equals(o.get("hold")));
    }

    /** The customer's position; null when the partner does not exist in this organization. */
    @Transactional(readOnly = true)
    public Exposure exposure(String partnerId) {
        return exposure(partnerId, null);
    }

    private Exposure exposure(String partnerId, String excludeOrderId) {
        Map<String, Object> p = partner(partnerId);
        if (p == null) {
            return null;
        }
        Map<String, Object> docs = SqlRows.one(jdbc.sql("SELECT COALESCE(sum(\"amountResidual\"), 0) AS open,"
                + " COALESCE(sum(\"amountResidual\") FILTER (WHERE \"dueDate\" < now()), 0) AS overdue FROM \"Document\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\"::text = 'sales_invoice' AND"
                + " status::text IN ('posted', 'paid') AND \"amountResidual\" > 0").params(org(), partnerId));
        Map<String, Object> pos = SqlRows.one(jdbc.sql("SELECT COALESCE(sum(\"amountResidual\"), 0) AS open,"
                + " COALESCE(sum(\"amountResidual\") FILTER (WHERE \"dueDate\" < now()), 0) AS overdue FROM \"Invoice\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND status::text IN ('posted', 'paid') AND"
                + " \"amountResidual\" > 0").params(org(), partnerId));
        BigDecimal orders = jdbc.sql("""
                SELECT COALESCE(sum(o."totalAmount"), 0) FROM "Order" o
                 WHERE o."organizationId" = ? AND o."partnerId" = ? AND o."invoiceId" IS NULL AND o.id <> ?
                   AND o.status::text NOT IN ('draft', 'completed', 'closed', 'cancelled')
                   AND (o."paymentMethod" IS NULL OR o."paymentMethod"::text NOT IN ('cash', 'card', 'mobile_money'))
                   AND NOT EXISTS (SELECT 1 FROM "FleetDeliveryOrder" f WHERE f."organizationId" = o."organizationId"
                                    AND f."sourceType" = 'sales_order' AND f."sourceId" = o.id
                                    AND f."goodsInvoiceId" IS NOT NULL)""")
                .params(org(), partnerId, excludeOrderId == null ? "" : excludeOrderId).query(BigDecimal.class).single();
        BigDecimal deliveries = jdbc.sql("""
                SELECT COALESCE(sum((l."orderedQty" - l."returnedQty") * COALESCE(l."unitPrice", 0)), 0)
                  FROM "FleetDeliveryOrderLine" l JOIN "FleetDeliveryOrder" f ON f.id = l."deliveryOrderId"
                 WHERE f."organizationId" = ? AND f."partnerId" = ? AND f."sourceType" = 'manual'
                   AND f."goodsInvoiceId" IS NULL AND f.status IN ('open', 'planned', 'in_progress', 'delivered')""")
                .params(org(), partnerId).query(BigDecimal.class).single();
        Waiver o = waiverOf(partnerId);
        return new Exposure(partnerId, Money.dec(p.get("creditLimit")), Boolean.TRUE.equals(p.get("creditHold")),
                Money.dec(docs.get("open")).add(Money.dec(pos.get("open"))),
                Money.dec(docs.get("overdue")).add(Money.dec(pos.get("overdue"))), orders, deliveries, o.amount(),
                o.holdCovered());
    }

    private static Map<String, Object> json(Exposure e) {
        return Obj.of("partnerId", e.partnerId(), "creditLimit", JsNumbers.js(e.creditLimit()), "creditHold",
                e.creditHold(), "openReceivables", JsNumbers.js(e.openReceivables()), "overdueReceivables",
                JsNumbers.js(e.overdueReceivables()), "unbilledOrders", JsNumbers.js(e.unbilledOrders()),
                "uninvoicedDeliveries", JsNumbers.js(e.uninvoicedDeliveries()), "exposure", JsNumbers.js(e.total()),
                "overrideAmount", JsNumbers.js(e.overrideAmount()), "headroom",
                e.headroom() == null ? null : JsNumbers.js(e.headroom()), "exceeded", e.exceeded());
    }

    /** Customers with a limit or a hold (with {@code search}: any customer), by name; {@code status}: hold, exceeded. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> customers(String search, String status, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(200, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder("\"organizationId\" = ? AND \"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (search != null && !search.isBlank()) {
            // A search reaches every customer, so a first limit can be set from here.
            where.append(" AND \"isCustomer\" AND name ILIKE ?");
            params.add("%" + search.trim() + "%");
        } else {
            where.append(" AND (\"creditLimit\" > 0 OR \"creditHold\")");
        }
        if ("hold".equals(status)) {
            where.append(" AND \"creditHold\"");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE " + where
                + " ORDER BY name").params(params))) {
            Exposure e = exposure((String) r.get("id"), null);
            if ("exceeded".equals(status) && !e.exceeded()) {
                continue;
            }
            Map<String, Object> row = json(e);
            row.put("partnerName", r.get("name"));
            rows.add(row);
        }
        int start = Math.min(rows.size(), (p - 1) * size);
        return PageResult.of(new ArrayList<>(rows.subList(start, Math.min(rows.size(), start + size))), p, size,
                rows.size());
    }

    /** One customer's position with every override. */
    @Transactional(readOnly = true)
    public Map<String, Object> customer(String partnerId) {
        Exposure e = exposure(partnerId, null);
        if (e == null) {
            throw ApiException.notFound("Partner not found");
        }
        Map<String, Object> out = json(e);
        out.put("partnerName", partner(partnerId).get("name"));
        out.put("overrides", SqlRows.list(jdbc.sql("SELECT id, amount, \"coversHold\", reason, \"expiresAt\","
                + " \"createdBy\", \"createdAt\", \"revokedBy\", \"revokedAt\" FROM \"PartnerCreditOverride\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? ORDER BY \"createdAt\" DESC LIMIT 50")
                .params(org(), partnerId)));
        return out;
    }

    // ------------------------------------------------------------------ policy and overrides

    /** Set the limit and / or hold; null leaves a value unchanged. */
    @Transactional
    public Map<String, Object> savePolicy(String partnerId, BigDecimal creditLimit, Boolean creditHold, String reason) {
        Map<String, Object> before = partner(partnerId);
        if (before == null) {
            throw ApiException.notFound("Partner not found");
        }
        if (creditLimit != null && creditLimit.signum() < 0) {
            throw ApiException.badRequest("creditLimit must not be less than 0");
        }
        partners.updateCreditPolicy(partnerId, creditLimit, creditHold);
        audit.recordInTx(AuditInput.of("Partner", partnerId, "credit_policy", Obj.of("creditLimit",
                before.get("creditLimit"), "creditHold", before.get("creditHold")), Obj.of("creditLimit", creditLimit,
                "creditHold", creditHold, "reason", reason)));
        return customer(partnerId);
    }

    @Transactional
    public Map<String, Object> grantOverride(String partnerId, OverrideInput in) {
        if (partner(partnerId) == null) {
            throw ApiException.notFound("Partner not found");
        }
        BigDecimal amount = in.amount() == null ? BigDecimal.ZERO : in.amount();
        boolean coversHold = Boolean.TRUE.equals(in.coversHold());
        if (amount.signum() < 0) {
            throw ApiException.badRequest("amount must not be less than 0");
        }
        if (amount.signum() == 0 && !coversHold) {
            throw ApiException.badRequest("An override needs an amount or must cover the hold");
        }
        if (in.expiresAt() == null || !in.expiresAt().isAfter(Instant.now())) {
            throw ApiException.badRequest("expiresAt must be in the future");
        }
        // Sensitive pair: an override can need an approval; it covers the customer, so no single sale's maker is known.
        ControlledActionService.Outcome outcome = controlled.authorize(new ControlledActionService.Action(
                SegregationOfDutiesService.CREDIT_OVERRIDE, "Partner", partnerId, amount, null, null, List.of(),
                Obj.of("coversHold", coversHold, "reason", in.reason())));
        if (!outcome.allowed()) {
            throw ApiException.badRequest("Approval required before overriding this customer's credit. Pending"
                    + " request " + outcome.approvalRequestId() + ".");
        }
        if (in.reason() == null || in.reason().isBlank()) {
            throw ApiException.badRequest("reason should not be empty");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PartnerCreditOverride\" (id, \"organizationId\", \"partnerId\", amount, \"coversHold\","
                + " reason, \"expiresAt\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), partnerId, amount, coversHold, in.reason().trim(), SqlRows.ts(in.expiresAt()),
                        TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("PartnerCreditOverride", id, "create", null, Obj.of("partnerId", partnerId,
                "amount", amount, "coversHold", coversHold, "reason", in.reason(), "expiresAt", in.expiresAt())));
        return customer(partnerId);
    }

    @Transactional
    public Map<String, Object> revokeOverride(String id) {
        String partnerId = jdbc.sql("UPDATE \"PartnerCreditOverride\" SET \"revokedAt\" = now(), \"revokedBy\" = ? WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"revokedAt\" IS NULL RETURNING \"partnerId\"")
                .params(TenantContext.userId(), id, org()).query(String.class).optional().orElse(null);
        if (partnerId == null) {
            throw ApiException.notFound("Credit override not found");
        }
        audit.recordInTx(AuditInput.of("PartnerCreditOverride", id, "revoke"));
        return customer(partnerId);
    }
}
