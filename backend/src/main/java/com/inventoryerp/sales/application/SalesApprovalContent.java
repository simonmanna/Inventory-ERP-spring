package com.inventoryerp.sales.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-S094 §4.2: what a sale's approval is bound to. The content hash covers the approvable fields only — customer,
 * payment terms, pricelist, currency and rate, agreement, order discount, and per line the product, variant, quantity,
 * unit, price, discounts and tax — so editing notes or contacts never invalidates an approval, and any commercial edit
 * does. Line order does not count (reordering lines is not a commercial change); a repeated line does. The exchange
 * rate counts only as entered on the draft (it is locked at confirmation). The rule version is a hash of the approval
 * settings in force. Pure functions, no Spring context.
 */
final class SalesApprovalContent {

    /** Order header fields an approver approves. */
    static final List<String> HEADER = List.of("partnerId", "paymentMode", "paymentTermId", "pricelistId",
            "currencyCode", "exchangeRate", "agreementId", "orderDiscountPercent", "orderDiscountAmount");

    /** Line fields an approver approves. */
    static final List<String> LINE = List.of("productId", "variantId", "quantity", "uomId", "uomFactor", "unitPrice",
            "discountPercent", "lineDiscountPercent", "taxId", "taxInclusive");

    /** Settings that decide which sales need approval. */
    static final List<String> RULES = List.of("orderMaxDiscountPercent", "marginCheck", "orderApprovalAlways",
            "orderApprovalAmount");

    private SalesApprovalContent() {}

    /** SHA-256 of the approvable content of an order and its lines (independent of line order and line ids). */
    static String hash(Map<String, Object> order, List<Map<String, Object>> lines) {
        StringBuilder sb = new StringBuilder();
        HEADER.forEach(k -> sb.append(k).append('=').append(canonical(order.get(k))).append('\n'));
        lines.stream().map(SalesApprovalContent::line).sorted().forEach(l -> sb.append(l).append('\n'));
        return sha256(sb.toString());
    }

    private static String line(Map<String, Object> l) {
        StringBuilder sb = new StringBuilder("line");
        LINE.forEach(k -> sb.append('|').append(canonical(l.get(k))));
        return sb.toString();
    }

    /** SHA-256 of the approval settings in force. */
    static String ruleVersion(Map<String, Object> settings) {
        StringBuilder sb = new StringBuilder();
        RULES.forEach(k -> sb.append(k).append('=').append(canonical(settings.get(k))).append('\n'));
        return sha256(sb.toString());
    }

    /** True when every current reason was approved ({@code approved} ⊇ {@code reasons}). */
    static boolean covered(Collection<String> reasons, Collection<String> approved) {
        return approved != null && Set.copyOf(approved).containsAll(reasons);
    }

    /** Numbers compare by value (1.50 = 1.5), everything else by its text; null is empty. */
    static String canonical(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal d) {
            return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Number n) {
            return canonical(new BigDecimal(n.toString()));
        }
        return v.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
