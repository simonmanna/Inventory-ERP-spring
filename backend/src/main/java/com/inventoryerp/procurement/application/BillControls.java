package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.invoicing.application.VendorBillPostGuard;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.threewaymatch.MatchToleranceProvider;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Purchasing controls on supplier bills (ADR-PROCUREMENT-001 wave 5): the {@code bill.duplicateCheck} gate before a bill
 * posts, and the {@code match.*} tolerances the three-way match uses for an order. Both resolve the policy for the
 * document's branch; with no policy configured the behaviour is unchanged.
 */
@Component
public class BillControls implements VendorBillPostGuard, MatchToleranceProvider {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final ProcurementPolicyService policies;
    private final EventBus events;

    public BillControls(JdbcClient jdbc, ProcurementPolicyService policies, EventBus events) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.events = events;
    }

    @Override
    public List<String> beforePost(String billId, Map<String, Object> bill) {
        String mode = policies.text(ProcurementPolicyRegistry.BILL_DUPLICATE_CHECK,
                ProcurementPolicyService.PolicyContext.branch((String) bill.get("branchId")));
        if (mode == null || "off".equals(mode)) {
            return List.of();
        }
        List<Map<String, Object>> dups = duplicates(billId);
        if (dups.isEmpty()) {
            return List.of();
        }
        String list = String.join(", ", dups.stream().map(d -> d.get("documentNumber")
                + (d.get("reference") == null ? "" : " (ref " + d.get("reference") + ")")).toList());
        String message = "Possible duplicate bill: this supplier already has " + list
                + " with the same reference, or the same amount within 7 days";
        if ("block".equals(mode)) {
            throw ApiException.conflict(message + " — cancel this bill or change the policy to post it");
        }
        events.publish("bill.duplicate_suspected", Obj.of("organizationId", org(), "billId", billId, "duplicateOfIds",
                dups.stream().map(d -> d.get("id")).toList()));
        return List.of(message);
    }

    /**
     * Posted bills of the same supplier that look like {@code billId}: same reference (case- and space-insensitive), or
     * the same total with an issue date at most 7 days apart.
     */
    public List<Map<String, Object>> duplicates(String billId) {
        return SqlRows.list(jdbc.sql("SELECT o.id, o.\"documentNumber\", o.reference, o.\"issueDate\", o.\"totalAmount\""
                + " FROM \"Document\" b JOIN \"Document\" o ON o.\"organizationId\" = b.\"organizationId\" AND"
                + " o.\"partnerId\" = b.\"partnerId\" AND o.\"documentType\" = 'vendor_bill' AND o.id <> b.id AND"
                + " o.status::text IN ('posted', 'paid') WHERE b.id = ? AND"
                + " b.\"organizationId\" = ? AND ((b.reference IS NOT NULL AND btrim(b.reference) <> '' AND"
                + " lower(regexp_replace(o.reference, '\\s', '', 'g')) = lower(regexp_replace(b.reference, '\\s', '', 'g')))"
                + " OR (o.\"totalAmount\" = b.\"totalAmount\" AND abs(extract(epoch FROM o.\"issueDate\" - b.\"issueDate\"))"
                + " <= 7 * 86400)) ORDER BY o.\"issueDate\" DESC LIMIT 5").params(billId, org()));
    }

    @Override
    public Tolerance forOrder(String purchaseOrderId) {
        String branchId = jdbc.sql("SELECT \"branchId\" FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ?")
                .params(purchaseOrderId, org()).query(String.class).optional().orElse(null);
        ProcurementPolicyService.PolicyContext ctx = ProcurementPolicyService.PolicyContext.branch(branchId);
        String mode = policies.text(ProcurementPolicyRegistry.MATCH_MODE, ctx);
        BigDecimal price = policies.number(ProcurementPolicyRegistry.MATCH_PRICE_TOLERANCE, ctx);
        BigDecimal qty = policies.number(ProcurementPolicyRegistry.MATCH_QTY_TOLERANCE, ctx);
        return new Tolerance(mode == null ? "three_way" : mode,
                price == null ? null : price.divide(HUNDRED, 6, java.math.RoundingMode.HALF_UP),
                qty == null ? BigDecimal.ZERO : qty.divide(HUNDRED, 6, java.math.RoundingMode.HALF_UP));
    }
}
