package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.dec;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Discount rules shared by quote and settle (port of {@code pricing-policy.ts}). Lines are JS-shaped maps
 * ({@code quantity}, {@code unitPrice}, {@code discountType}, {@code discountAmount}, {@code discountPercent},
 * {@code discountReason}); inputs carry {@code transactionDiscount*}, {@code discountReason}, {@code overrideById},
 * {@code overridePin}.
 */
@Component
public class PricingPolicy {

    /** Fallback when an org has not configured {@code discountApproval.tier1}. */
    public static final double DEFAULT_DISCOUNT_TIER1 = 10;
    public static final double DEFAULT_DISCOUNT_TIER1_AMOUNT = 0;

    /** {@code PricingAuthority}. */
    public record Verdict(double maxDiscountPercent, double threshold, double discountAmount, double thresholdAmount,
            boolean requiresReason, boolean requiresApproval, boolean hasDiscountPermission) {
        public Map<String, Object> toMap() {
            return Obj.of("maxDiscountPercent", PosSql.js(maxDiscountPercent), "threshold", PosSql.js(threshold),
                    "discountAmount", PosSql.js(discountAmount), "thresholdAmount", PosSql.js(thresholdAmount),
                    "requiresReason", requiresReason, "requiresApproval", requiresApproval,
                    "hasDiscountPermission", hasDiscountPermission);
        }
    }

    private final JdbcClient jdbc;
    private final PosOverridesService overrides;

    public PricingPolicy(JdbcClient jdbc, PosOverridesService overrides) {
        this.jdbc = jdbc;
        this.overrides = overrides;
    }

    /** Normalize line and order discounts once; quote and invoice use identical inputs. */
    public static List<Map<String, Object>> discountedLines(List<Map<String, Object>> lines, Map<String, Object> input) {
        String type = input.get("transactionDiscountType") == null ? "percentage"
                : input.get("transactionDiscountType").toString();
        BigDecimal value = dec("fixed_amount".equals(type) ? orZero(input.get("transactionDiscountAmount"))
                : orZero(input.get("transactionDiscountPercent")));
        if (value.signum() < 0 || ("percentage".equals(type) && value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw ApiException.badRequest("Invalid order discount");
        }
        List<BigDecimal[]> amounts = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            BigDecimal gross = dec(line.get("quantity")).multiply(dec(line.get("unitPrice")));
            BigDecimal discount = "fixed_amount".equals(line.get("discountType"))
                    ? dec(orZero(line.get("discountAmount")))
                    : div(gross.multiply(dec(orZero(line.get("discountPercent")))), BigDecimal.valueOf(100));
            if (gross.signum() < 0 || discount.signum() < 0 || discount.compareTo(gross) > 0) {
                throw ApiException.badRequest("Invalid line price or discount");
            }
            amounts.add(new BigDecimal[] {gross, discount, gross.subtract(discount)});
        }
        BigDecimal net = BigDecimal.ZERO;
        for (BigDecimal[] a : amounts) {
            net = net.add(a[2]);
        }
        if ("fixed_amount".equals(type) && value.compareTo(net) > 0) {
            throw ApiException.badRequest("Order discount exceeds the remaining subtotal");
        }
        BigDecimal allocated = BigDecimal.ZERO;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal[] a = amounts.get(i);
            BigDecimal extra;
            if ("percentage".equals(type)) {
                extra = div(a[2].multiply(value), BigDecimal.valueOf(100));
            } else if (net.signum() == 0) {
                extra = BigDecimal.ZERO;
            } else if (i == lines.size() - 1) {
                extra = value.subtract(allocated);
            } else {
                extra = div(value.multiply(a[2]), net).setScale(6, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(extra);
            Map<String, Object> l = new LinkedHashMap<>(lines.get(i));
            l.put("discountType", "fixed_amount");
            l.put("discountAmount", a[1].add(extra).doubleValue());
            l.put("discountPercent", 0);
            out.add(l);
        }
        return out;
    }

    private static Object orZero(Object v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal div(BigDecimal a, BigDecimal b) {
        return a.divide(b, com.inventoryerp.kernel.common.Money.DECIMAL_JS);
    }

    /** Evaluate the discount rules without enforcing them. */
    public Verdict evaluate(List<Map<String, Object>> lines, Map<String, Object> input) {
        List<Map<String, Object>> discounted = discountedLines(lines, input);
        double max = 0;
        for (Map<String, Object> l : discounted) {
            double gross = num(l.get("quantity")) * num(l.get("unitPrice"));
            max = Math.max(max, gross > 0 ? num(l.get("discountAmount")) / gross * 100 : 0);
        }
        if (max == 0) {
            return new Verdict(0, DEFAULT_DISCOUNT_TIER1, 0, 0, false, false, true);
        }
        List<String> permissions = currentPermissions(TenantContext.userId());
        JsonNode approval = discountApproval();
        double threshold = resolveDiscountThreshold(approval == null ? null : approval.get("tier1"));
        double thresholdAmount = resolveDiscountAmountThreshold(approval == null ? null : approval.get("tier1Amount"));
        double discountAmount = 0;
        for (Map<String, Object> l : discounted) {
            discountAmount += num(l.get("discountAmount"));
        }
        boolean hasDiscount = permissions.contains("pos:discount");
        Object orderDiscountRaw = PosSql.truthy(input.get("transactionDiscountPercent"))
                ? input.get("transactionDiscountPercent") : input.get("transactionDiscountAmount");
        boolean orderDiscount = num(orderDiscountRaw) > 0;
        boolean everyLineReasoned = true;
        for (Map<String, Object> l : lines) {
            boolean discountedLine = PosSql.truthy(num(l.get("discountPercent")))
                    || PosSql.truthy(num(l.get("discountAmount")));
            Object reason = l.get("discountReason");
            if (discountedLine && (reason == null || reason.toString().trim().isEmpty())) {
                everyLineReasoned = false;
            }
        }
        Object reason = input.get("discountReason");
        boolean noReason = reason == null || reason.toString().trim().isEmpty();
        boolean requiresReason = noReason && (orderDiscount || !everyLineReasoned);
        boolean requiresApproval = !hasDiscount || max > threshold
                || (thresholdAmount > 0 && discountAmount > thresholdAmount);
        return new Verdict(max, threshold, discountAmount, thresholdAmount, requiresReason, requiresApproval,
                hasDiscount);
    }

    /** Enforce: reason + manager approval when above the threshold. Returns the max discount %. */
    public double assertAuthority(List<Map<String, Object>> lines, Map<String, Object> input) {
        Verdict v = evaluate(lines, input);
        if (v.maxDiscountPercent() == 0) {
            return 0;
        }
        if (v.requiresReason()) {
            throw ApiException.badRequest("A discount reason is required");
        }
        if (v.requiresApproval()) {
            String overrideById = PosSql.str(input, "overrideById");
            if (overrideById == null || overrideById.isEmpty()) {
                throw ApiException.forbidden("This discount requires manager approval and PIN");
            }
            overrides.verifyOperationApproval(overrideById, PosSql.str(input, "overridePin"), "discount");
        }
        return v.maxDiscountPercent();
    }

    /** {@code settings.discountApproval} of the current org. */
    public JsonNode discountApproval() {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT settings FROM \"Organization\" WHERE id = ?").param(org()));
        Object settings = row == null ? null : row.get("settings");
        if (settings instanceof JsonNode n && n.isObject()) {
            JsonNode d = n.get("discountApproval");
            return d != null && d.isObject() ? d : null;
        }
        return null;
    }

    /** The org's tier-1 threshold, clamped and defaulted. */
    public static double resolveDiscountThreshold(JsonNode configured) {
        double n = configured == null || configured.isNull() ? DEFAULT_DISCOUNT_TIER1 : jsNumber(configured);
        return Double.isFinite(n) ? Math.max(0, Math.min(100, n)) : DEFAULT_DISCOUNT_TIER1;
    }

    public static double resolveDiscountAmountThreshold(JsonNode configured) {
        double n = configured == null || configured.isNull() ? DEFAULT_DISCOUNT_TIER1_AMOUNT : jsNumber(configured);
        return Double.isFinite(n) && n > 0 ? n : DEFAULT_DISCOUNT_TIER1_AMOUNT;
    }

    static double jsNumber(JsonNode n) {
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean() ? 1 : 0;
        }
        if (n.isString()) {
            return com.inventoryerp.kernel.common.JsNumbers.number(n.asString());
        }
        return Double.NaN;
    }

    /** A-030: the caller's live permissions from their current roles. */
    public List<String> currentPermissions(String userId) {
        if (userId == null) {
            return List.of();
        }
        return jdbc.sql("SELECT DISTINCT p FROM \"User\" u JOIN \"_UserRoles\" ur ON ur.\"B\" = u.id"
                        + " JOIN \"Role\" r ON r.id = ur.\"A\", unnest(r.permissions) p"
                        + " WHERE u.id = ? AND u.\"isActive\" = true AND u.\"deletedAt\" IS NULL")
                .param(userId).query(String.class).list();
    }
}
