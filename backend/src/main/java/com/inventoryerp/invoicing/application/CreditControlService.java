package com.inventoryerp.invoicing.application;

import com.inventoryerp.core.application.PaymentTermService;
import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.credit.application.CreditService.Decision;
import com.inventoryerp.credit.application.CreditService.GrantInput;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SALES-1 credit control routes and checks, served by the central customer credit management ({@link CreditService},
 * ADR-S022). This class owns no rule: limits, exposure, holds, overrides, reservation and decisions all come from the
 * credit module. It keeps the SALES-1 shapes — the {@link Exposure} record, the JSON of {@code /credit-control} and the
 * refusal messages of {@link PaymentTermService#assertCreditOk}.
 */
@Service
public class CreditControlService {

    /** Modes paid on the spot: no credit is extended, so neither limit nor hold applies. */
    private static final Set<String> PREPAID = Set.of("cash", "card", "mobile_money");

    /**
     * One customer's position. {@code overrideAmount} and {@code holdOverridden} apply to the current user;
     * {@code total} is the central exposure (all buckets, credits and deposits included).
     */
    public record Exposure(String partnerId, BigDecimal creditLimit, boolean creditHold, BigDecimal openReceivables,
            BigDecimal overdueReceivables, BigDecimal unbilledOrders, BigDecimal uninvoicedDeliveries,
            BigDecimal overrideAmount, boolean holdOverridden, BigDecimal total) {

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
    private final CreditService credit;

    public CreditControlService(JdbcClient jdbc, CreditService credit) {
        this.jdbc = jdbc;
        this.credit = credit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private Map<String, Object> partner(String partnerId) {
        return SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(partnerId, org()));
    }

    // ------------------------------------------------------------------ enforcement

    /**
     * Refuse a credit sale of {@code amount} at {@code checkpoint} when the central check does not pass.
     *
     * @param documentType / documentId the document, recorded on the decision and left out of the exposure
     * @param paymentMode the document's payment mode; cash, card and mobile money skip the check
     */
    public void assertCanSell(String checkpoint, String partnerId, BigDecimal amount, String documentType,
            String documentId, String paymentMode, String branchId) {
        if (partnerId == null || partnerId.isEmpty() || (paymentMode != null && PREPAID.contains(paymentMode))
                || partner(partnerId) == null) {
            return;
        }
        Decision d = credit.check(checkpoint, partnerId, documentType, documentId, null, amount, branchId, documentId);
        if (d.passed()) {
            return;
        }
        Map<String, Object> e = d.exposure();
        PaymentTermService.assertCreditOk(d.reasons().contains("Customer is on credit hold"),
                Money.dec(e.get("effectiveLimit")), Money.dec(e.get("exposure")), amount);
        throw ApiException.badRequest(String.join("; ", d.reasons()) + ".");
    }

    /** Goods about to leave for a customer on hold are stopped; an override that covers the hold lets them go. */
    public void assertNotOnHold(String partnerId) {
        if (partnerId != null && credit.holdBlocks(partnerId)) {
            throw ApiException.badRequest("Customer is on credit hold.");
        }
    }

    // ------------------------------------------------------------------ exposure

    /** The customer's position; null when the partner does not exist in this organization. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Exposure exposure(String partnerId) {
        if (partner(partnerId) == null) {
            return null;
        }
        Map<String, Object> e = credit.exposure(partnerId);
        Map<String, Object> c = (Map<String, Object>) e.get("components");
        Map<String, Object> g = (Map<String, Object>) e.get("grants");
        return new Exposure(partnerId, Money.dec(e.get("limit")), Boolean.TRUE.equals(e.get("creditHold")),
                Money.dec(c.get("receivables")), Money.dec(((Map<String, Object>) e.get("overdue")).get("amount")),
                Money.dec(c.get("undelivered")), Money.dec(c.get("deliveredUninvoiced")), Money.dec(g.get("amount")),
                Boolean.TRUE.equals(g.get("coversHold")), Money.dec(e.get("exposure")));
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
            Exposure e = exposure((String) r.get("id"));
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

    /** A customer's name and position, for the credit alerts. */
    public record NamedExposure(String partnerName, Exposure exposure) {}

    /** Every customer with a limit, by name. The current user's own overrides do not apply to a scan: it has no user. */
    @Transactional(readOnly = true)
    public List<NamedExposure> limitedCustomers() {
        List<NamedExposure> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE \"organizationId\""
                + " = ? AND \"deletedAt\" IS NULL AND \"creditLimit\" > 0 ORDER BY name").param(org()))) {
            out.add(new NamedExposure((String) r.get("name"), exposure((String) r.get("id"))));
        }
        return out;
    }

    /** One customer's position with every override. */
    @Transactional(readOnly = true)
    public Map<String, Object> customer(String partnerId) {
        Exposure e = exposure(partnerId);
        if (e == null) {
            throw ApiException.notFound("Partner not found");
        }
        Map<String, Object> out = json(e);
        out.put("partnerName", partner(partnerId).get("name"));
        out.put("overrides", credit.customerOverrides(partnerId));
        return out;
    }

    // ------------------------------------------------------------------ profile and overrides

    /** Set the limit and / or hold of the customer credit profile; null leaves a value unchanged. */
    @Transactional
    public Map<String, Object> savePolicy(String partnerId, BigDecimal creditLimit, Boolean creditHold, String reason) {
        credit.updateProfile(partnerId, creditLimit, creditHold, reason);
        return customer(partnerId);
    }

    @Transactional
    public Map<String, Object> grantOverride(String partnerId, OverrideInput in) {
        credit.grantCustomerOverride(partnerId, new GrantInput(in.amount(), in.coversHold(), in.reason(),
                in.expiresAt()));
        return customer(partnerId);
    }

    @Transactional
    public Map<String, Object> revokeOverride(String id) {
        return customer(credit.revokeCustomerOverride(id));
    }
}
