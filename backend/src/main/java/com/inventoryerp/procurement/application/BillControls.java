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
        // W6: goods still under incoming inspection are not billed yet (only when an inspection exists). h04-F5: the
        // hold finds them by the bill's orders and also by its lines: a line of the bill's supplier for a product (and
        // variant) still unbilled on a receipt under inspection, so a bill without purchaseOrderIds is held too.
        String pending = jdbc.sql("SELECT string_agg(DISTINCT i.\"inspectionNumber\", ', ') FROM \"IncomingInspection\" i"
                + " WHERE i.\"organizationId\" = ? AND i.status = 'pending' AND (EXISTS (SELECT 1 FROM \"VendorBillLink\" k"
                + " WHERE k.\"vendorBillId\" = ? AND k.\"purchaseOrderId\" = i.\"purchaseOrderId\") OR EXISTS (SELECT 1 FROM"
                + " \"Document\" b JOIN \"DocumentLine\" bl ON bl.\"documentId\" = b.id JOIN \"IncomingInspectionLine\" il ON"
                + " il.\"inspectionId\" = i.id JOIN \"GoodsReceiptLine\" gl ON gl.id = il.\"goodsReceiptLineId\" WHERE"
                + " b.id = ? AND b.\"partnerId\" = i.\"partnerId\" AND bl.\"productId\" = gl.\"productId\" AND"
                + " bl.\"variantId\" IS NOT DISTINCT FROM gl.\"variantId\" AND gl.quantity > gl.\"billedQuantity\"))")
                .params(org(), billId, billId).query(String.class)
                .list().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (pending != null) {
            throw ApiException.badRequest("Incoming inspection " + pending + " is still open for goods on this bill —"
                    + " complete it before posting the bill");
        }
        String mode = policies.text(ProcurementPolicyRegistry.BILL_DUPLICATE_CHECK,
                ProcurementPolicyService.PolicyContext.branch((String) bill.get("branchId")));
        if (mode == null) {
            mode = ProcurementPolicyRegistry.DUPLICATE_BLOCK_REFERENCE;
        }
        if ("off".equals(mode)) {
            return List.of();
        }
        Map<String, Object> self = SqlRows.one(jdbc.sql("SELECT \"partnerId\", reference, \"sourceType\" FROM"
                + " \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(billId, org()));
        String norm = self == null ? null : normalizeReference((String) self.get("reference"));
        if (norm != null && self.get("partnerId") != null) {
            // Purchase readiness H2: two posts of the same supplier invoice serialise, so both cannot pass the check.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("bill-dup:" + org() + ":"
                    + self.get("partnerId") + ":" + norm).query().listOfRows();
        }
        List<Map<String, Object>> sameRef = referenceDuplicates(billId, norm);
        List<Map<String, Object>> sameAmount = amountDuplicates(billId, sameRef);
        if (sameRef.isEmpty() && sameAmount.isEmpty()) {
            return List.of();
        }
        // Opening-balance bills default their reference to the batch label, so the default only flags them.
        boolean blockRef = "block".equals(mode) || ProcurementPolicyRegistry.DUPLICATE_BLOCK_REFERENCE.equals(mode)
                && !"opening_balance".equals(self == null ? null : self.get("sourceType"));
        if (blockRef && !sameRef.isEmpty()) {
            throw ApiException.conflict("Duplicate bill: this supplier already has " + describe(sameRef)
                    + " with the same invoice reference — cancel this bill or correct its reference");
        }
        List<Map<String, Object>> dups = new java.util.ArrayList<>(sameRef);
        dups.addAll(sameAmount);
        String message = "Possible duplicate bill: this supplier already has " + describe(dups)
                + " with the same reference, or the same amount within 7 days";
        if ("block".equals(mode)) {
            throw ApiException.conflict(message + " — cancel this bill or change the policy to post it");
        }
        events.publish("bill.duplicate_suspected", Obj.of("organizationId", org(), "billId", billId, "duplicateOfIds",
                dups.stream().map(d -> d.get("id")).toList()));
        return List.of(message);
    }

    private static String describe(List<Map<String, Object>> dups) {
        return String.join(", ", dups.stream().map(d -> d.get("documentNumber")
                + (d.get("reference") == null ? "" : " (ref " + d.get("reference") + ")")).toList());
    }

    /**
     * Purchase readiness H2: the supplier invoice number as compared for duplicates — lower-case letters and digits
     * only, so INV-001, inv 001 and INV/001 are the same invoice. Null when nothing is left.
     */
    public static String normalizeReference(String reference) {
        if (reference == null) {
            return null;
        }
        String norm = reference.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return norm.isEmpty() ? null : norm;
    }

    /** {@link #normalizeReference} in SQL over {@code column}. */
    private static String sqlNormalized(String column) {
        return "lower(regexp_replace(coalesce(" + column + ", ''), '[^[:alnum:]]', '', 'g'))";
    }

    /** Landed-cost bills are paired with ordinary ones by {@code LandedCostBillGuard} (its own message), not here. */
    private List<Map<String, Object>> referenceDuplicates(String billId, String norm) {
        if (norm == null) {
            return List.of();
        }
        return SqlRows.list(jdbc.sql("SELECT o.id, o.\"documentNumber\", o.reference, o.\"issueDate\", o.\"totalAmount\""
                + " FROM \"Document\" b JOIN \"Document\" o ON o.\"organizationId\" = b.\"organizationId\" AND"
                + " o.\"partnerId\" = b.\"partnerId\" AND o.\"documentType\" = 'vendor_bill' AND o.id <> b.id AND"
                + " o.status::text IN ('posted', 'paid') AND coalesce(o.\"sourceType\", '') <> 'landed_cost' AND"
                + " coalesce(b.\"sourceType\", '') <> 'landed_cost' WHERE b.id = ? AND b.\"organizationId\" = ? AND "
                + sqlNormalized("o.reference") + " = ? ORDER BY o.\"issueDate\" DESC LIMIT 5").params(billId, org(), norm));
    }

    private List<Map<String, Object>> amountDuplicates(String billId, List<Map<String, Object>> exclude) {
        List<Object> ids = exclude.stream().map(d -> d.get("id")).toList();
        return SqlRows.list(jdbc.sql("SELECT o.id, o.\"documentNumber\", o.reference, o.\"issueDate\", o.\"totalAmount\""
                + " FROM \"Document\" b JOIN \"Document\" o ON o.\"organizationId\" = b.\"organizationId\" AND"
                + " o.\"partnerId\" = b.\"partnerId\" AND o.\"documentType\" = 'vendor_bill' AND o.id <> b.id AND"
                + " o.status::text IN ('posted', 'paid') WHERE b.id = ? AND b.\"organizationId\" = ? AND"
                + " o.\"totalAmount\" = b.\"totalAmount\" AND abs(extract(epoch FROM o.\"issueDate\" - b.\"issueDate\"))"
                + " <= 7 * 86400 ORDER BY o.\"issueDate\" DESC LIMIT 5").params(billId, org())).stream()
                .filter(d -> !ids.contains(d.get("id"))).toList();
    }

    /**
     * Posted bills of the same supplier that look like {@code billId}: the same normalised invoice reference (letters
     * and digits only, case-insensitive), or the same total with an issue date at most 7 days apart.
     */
    public List<Map<String, Object>> duplicates(String billId) {
        String ref = jdbc.sql("SELECT reference FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                .params(billId, org()).query(String.class).optional().orElse(null);
        List<Map<String, Object>> sameRef = referenceDuplicates(billId, normalizeReference(ref));
        List<Map<String, Object>> out = new java.util.ArrayList<>(sameRef);
        out.addAll(amountDuplicates(billId, sameRef));
        return out.size() > 5 ? out.subList(0, 5) : out;
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
