package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Receiving gates (ADR-PROCUREMENT-001 wave 6), resolved per branch and product category:
 * <ul>
 *   <li>{@code receiving.weighbridgeRequired}: bulk material (aggregates, cement, sand) is received only with a
 *       completed weighbridge ticket on the receipt line ({@link ReceiptEvidenceProvider}, implemented by fleet). A
 *       holder of {@code receiving:override_evidence} may post without one; the override is audited.</li>
 *   <li>{@code receiving.inspectionRequired}: posting the receipt opens an incoming inspection, and the supplier bill
 *       waits until it is completed.</li>
 * </ul>
 */
@Component
public class ReceivingControls {

    private final JdbcClient jdbc;
    private final ProcurementPolicyService policies;
    private final ObjectProvider<ReceiptEvidenceProvider> evidence;
    private final IncomingInspectionService inspections;
    private final AuditService audit;

    public ReceivingControls(JdbcClient jdbc, ProcurementPolicyService policies,
            ObjectProvider<ReceiptEvidenceProvider> evidence, IncomingInspectionService inspections, AuditService audit) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.evidence = evidence;
        this.inspections = inspections;
        this.audit = audit;
    }

    /** A receipt line for the gates: its id (null before it exists), product and description. */
    public record Line(String id, String productId, String description) {}

    /**
     * Refuse the post when a line needs a weighbridge ticket and has none (direct receipts never have one: they must
     * come through the weighbridge). {@code goodsReceiptId} may be null for a receipt created in the same call.
     */
    public void requireEvidence(String goodsReceiptId, String branchId, List<Line> lines) {
        List<String> missing = new ArrayList<>();
        Set<String> weighed = null;
        for (Line l : lines) {
            if (l.productId() == null || !policyFlag(ProcurementPolicyRegistry.RECEIVING_WEIGHBRIDGE_REQUIRED, branchId,
                    l.productId())) {
                continue;
            }
            if (weighed == null) {
                ReceiptEvidenceProvider p = evidence.getIfAvailable();
                weighed = p == null || goodsReceiptId == null ? Set.of() : p.weighedLines(goodsReceiptId);
            }
            if (l.id() == null || !weighed.contains(l.id())) {
                missing.add(l.description());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (TenantContext.permissions().contains(Permissions.RECEIVING__OVERRIDE_EVIDENCE)) {
            audit.record(AuditInput.of("GoodsReceiptNote", goodsReceiptId == null ? "direct" : goodsReceiptId, "update",
                    null, Obj.of("weighbridgeOverride", true, "lines", missing)));
            return;
        }
        throw ApiException.badRequest("A completed weighbridge ticket is required before receiving "
                + String.join(", ", missing) + " — weigh the truck at the weighbridge, or ask a user with"
                + " receiving:override_evidence");
    }

    /** After a receipt posted: open an inspection when any of its lines falls under {@code receiving.inspectionRequired}. */
    public void afterPost(String goodsReceiptId) {
        Map<String, Object> grn = SqlRows.one(jdbc.sql("SELECT \"branchId\" FROM \"GoodsReceiptNote\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(goodsReceiptId, org()));
        if (grn == null) {
            return;
        }
        for (String productId : jdbc.sql("SELECT DISTINCT \"productId\" FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\""
                + " = ? AND \"productId\" IS NOT NULL").param(goodsReceiptId).query(String.class).list()) {
            if (policyFlag(ProcurementPolicyRegistry.RECEIVING_INSPECTION_REQUIRED, (String) grn.get("branchId"),
                    productId)) {
                inspections.open(goodsReceiptId);
                return;
            }
        }
    }

    private boolean policyFlag(String key, String branchId, String productId) {
        String category = jdbc.sql("SELECT \"categoryId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, org()).query(String.class).optional().orElse(null);
        ProcurementPolicyService.PolicyContext ctx = ProcurementPolicyService.PolicyContext.branch(branchId)
                .withCategory(category);
        tools.jackson.databind.JsonNode v = policies.resolve(key, ctx).value();
        return v != null && v.isBoolean() && v.booleanValue();
    }
}
