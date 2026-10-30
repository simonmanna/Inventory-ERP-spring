package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-organization sales settings (ADR-S011): quotation policy (moved from the CRM settings), enabled flows,
 * reservation trigger and the five fulfilment / recognition policy defaults (stored in S0, executed from S5).
 */
@Service
public class SalesSettingsService {

    static final Set<String> FLOWS = Set.of("wholesale", "direct_order", "advance", "contract", "collect",
            "make_to_order");
    private static final Map<String, Set<String>> CHOICES = Map.of(
            "reservationTrigger", Set.of("none", "confirm"),
            "physicalMovement", Set.of("direct", "via_transit"),
            "ownershipTransfer", Set.of("at_dispatch", "at_delivery", "at_acceptance"),
            "cogsRecognition", Set.of("at_ownership_transfer", "at_revenue_recognition"),
            "revenueRecognition", Set.of("at_ownership_transfer", "at_invoice"),
            "billingPolicy", Set.of("ordered", "delivered"));

    private final SalesDb db;
    private final AuditService audit;

    public SalesSettingsService(SalesDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Settings of the current organization (created with defaults on first read). */
    @Transactional
    public Map<String, Object> settings() {
        db.update("INSERT INTO \"SalesSettings\" (\"organizationId\") VALUES (?) ON CONFLICT DO NOTHING", org());
        return db.row("SELECT * FROM \"SalesSettings\" WHERE \"organizationId\" = ?", org());
    }

    /** Partial update; unknown keys are ignored, invalid values and policy combinations rejected. */
    @Transactional
    public Map<String, Object> update(Map<String, Object> in) {
        Map<String, Object> before = settings();
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (in.containsKey("quoteMaxDiscountPercent")) {
            BigDecimal v = dec(in.get("quoteMaxDiscountPercent"));
            if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.badRequest("quoteMaxDiscountPercent is out of range");
            }
            sets.add("\"quoteMaxDiscountPercent\" = ?");
            params.add(v);
        }
        if (in.containsKey("quoteApprovalAmount")) {
            BigDecimal v = dec(in.get("quoteApprovalAmount"));
            if (v.signum() < 0) {
                throw ApiException.badRequest("quoteApprovalAmount is out of range");
            }
            sets.add("\"quoteApprovalAmount\" = ?");
            params.add(v);
        }
        if (in.containsKey("orderMaxDiscountPercent")) {
            BigDecimal v = dec(in.get("orderMaxDiscountPercent"));
            if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.badRequest("orderMaxDiscountPercent is out of range");
            }
            sets.add("\"orderMaxDiscountPercent\" = ?");
            params.add(v);
        }
        if (in.containsKey("orderApprovalAmount")) {
            BigDecimal v = dec(in.get("orderApprovalAmount"));
            if (v.signum() < 0) {
                throw ApiException.badRequest("orderApprovalAmount is out of range");
            }
            sets.add("\"orderApprovalAmount\" = ?");
            params.add(v);
        }
        if (in.containsKey("quoteValidityDays")) {
            int v = in.get("quoteValidityDays") instanceof Number n ? n.intValue() : 0;
            if (v <= 0) {
                throw ApiException.badRequest("quoteValidityDays must be positive");
            }
            sets.add("\"quoteValidityDays\" = ?");
            params.add(v);
        }
        for (String k : List.of("creditCheck", "autoWinOnConversion", "marginCheck")) {
            if (in.containsKey(k)) {
                sets.add("\"" + k + "\" = ?");
                params.add(Boolean.TRUE.equals(in.get(k)));
            }
        }
        if (in.containsKey("defaultQuoteTerms")) {
            sets.add("\"defaultQuoteTerms\" = ?");
            params.add(str(in.get("defaultQuoteTerms")));
        }
        if (in.containsKey("flows")) {
            List<String> flows = SalesDb.strings(in.get("flows"));
            for (String f : flows) {
                if (!FLOWS.contains(f)) {
                    throw ApiException.badRequest("flows must be any of: " + String.join(", ", FLOWS.stream().sorted()
                            .toList()));
                }
            }
            sets.add("flows = ?");
            params.add(flows.toArray(String[]::new));
        }
        String movement = str(in.getOrDefault("physicalMovement", before.get("physicalMovement")));
        String transfer = str(in.getOrDefault("ownershipTransfer", before.get("ownershipTransfer")));
        for (Map.Entry<String, Set<String>> c : CHOICES.entrySet()) {
            if (in.containsKey(c.getKey())) {
                String v = str(in.get(c.getKey()));
                if (v == null || !c.getValue().contains(v)) {
                    throw ApiException.badRequest(c.getKey() + " must be one of: " + String.join(", ", c.getValue()
                            .stream().sorted().toList()));
                }
                sets.add("\"" + c.getKey() + "\" = ?");
                params.add(v);
            }
        }
        // Executable today: goods issued at the gate (direct / at dispatch). Transit-location ownership policies need the
        // accountant-validated postings of sales-postings.md §3 (rows C-E) before they can be switched on.
        if ("via_transit".equals(movement) || !"at_dispatch".equals(transfer)
                || "at_ownership_transfer".equals(in.get("revenueRecognition"))) {
            throw ApiException.badRequest("Only the default policy (goods issued at dispatch, revenue at invoice) is"
                    + " available until the transit postings are approved by the accountant");
        }
        if ("direct".equals(movement) && !"at_dispatch".equals(transfer)) {
            throw ApiException.badRequest("Ownership can only pass after dispatch when goods move via a transit location");
        }
        if (in.containsKey("policyEffectiveFrom")) {
            sets.add("\"policyEffectiveFrom\" = ?");
            params.add(SalesDb.date(str(in.get("policyEffectiveFrom"))));
        }
        if (!sets.isEmpty()) {
            sets.add("\"updatedById\" = ?");
            params.add(user());
            params.add(org());
            db.jdbc().sql("UPDATE \"SalesSettings\" SET " + String.join(", ", sets) + ", \"updatedAt\" = " + NOW
                    + " WHERE \"organizationId\" = ?").params(params.stream().map(SalesDb::param).toList()).update();
            audit.record(AuditInput.of("SalesSettings", org(), "update", before, in));
        }
        return settings();
    }
}
