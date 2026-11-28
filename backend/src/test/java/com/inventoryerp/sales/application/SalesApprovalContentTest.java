package com.inventoryerp.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S094 §4.2: what a sale's approval is bound to (pure, no Spring context). */
class SalesApprovalContentTest {

    static Map<String, Object> order(Object... kv) {
        Map<String, Object> o = new HashMap<>(Map.of("partnerId", "p1", "paymentMode", "credit", "currencyCode", "UGX",
                "notes", "Gate 3", "number", "SO-2026-00001"));
        for (int i = 0; i < kv.length; i += 2) {
            o.put((String) kv[i], kv[i + 1]);
        }
        return o;
    }

    static Map<String, Object> line(String id, int sort, String product, Object qty, Object price) {
        Map<String, Object> l = new HashMap<>();
        l.put("id", id);
        l.put("sortOrder", sort);
        l.put("productId", product);
        l.put("quantity", qty);
        l.put("unitPrice", price);
        l.put("description", "Line " + id);
        return l;
    }

    @Test
    @DisplayName("notes, numbers, descriptions and line ids are not approvable content")
    void ignoresNonApprovableFields() {
        String base = SalesApprovalContent.hash(order(), List.of(line("a", 0, "x", 10, 100)));
        Map<String, Object> renamed = line("b", 0, "x", 10, 100);
        renamed.put("description", "Something else");
        assertThat(SalesApprovalContent.hash(order("notes", "Gate 4", "number", "SO-2026-00002", "contactId", "c9"),
                List.of(renamed))).isEqualTo(base);
    }

    @Test
    @DisplayName("customer, terms, quantity, price, discount and tax are approvable content")
    void commercialEditsChangeTheHash() {
        String base = SalesApprovalContent.hash(order(), List.of(line("a", 0, "x", 10, 100)));
        assertThat(SalesApprovalContent.hash(order("partnerId", "p2"), List.of(line("a", 0, "x", 10, 100))))
                .isNotEqualTo(base);
        assertThat(SalesApprovalContent.hash(order("paymentMode", "cash"), List.of(line("a", 0, "x", 10, 100))))
                .isNotEqualTo(base);
        assertThat(SalesApprovalContent.hash(order(), List.of(line("a", 0, "x", 11, 100)))).isNotEqualTo(base);
        assertThat(SalesApprovalContent.hash(order(), List.of(line("a", 0, "x", 10, 99)))).isNotEqualTo(base);
        Map<String, Object> discounted = line("a", 0, "x", 10, 100);
        discounted.put("discountPercent", 5);
        assertThat(SalesApprovalContent.hash(order(), List.of(discounted))).isNotEqualTo(base);
        Map<String, Object> taxed = line("a", 0, "x", 10, 100);
        taxed.put("taxId", "vat18");
        assertThat(SalesApprovalContent.hash(order(), List.of(taxed))).isNotEqualTo(base);
        assertThat(SalesApprovalContent.hash(order("orderDiscountPercent", 2), List.of(line("a", 0, "x", 10, 100))))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("numbers compare by value; line order does not count, a repeated line does")
    void canonicalForm() {
        String base = SalesApprovalContent.hash(order("exchangeRate", new BigDecimal("1.50")), List.of(
                line("a", 0, "x", new BigDecimal("10.000"), new BigDecimal("100.00")), line("b", 1, "y", 2, 5)));
        assertThat(SalesApprovalContent.hash(order("exchangeRate", 1.5), List.of(line("c", 0, "y", 2.0, 5),
                line("d", 1, "x", 10, 100)))).isEqualTo(base);
        assertThat(SalesApprovalContent.hash(order("exchangeRate", 1.5), List.of(line("a", 0, "x", 10, 100),
                line("b", 1, "y", 2, 5), line("e", 2, "y", 2, 5)))).isNotEqualTo(base);
        assertThat(SalesApprovalContent.canonical(BigDecimal.ZERO.setScale(4))).isEqualTo("0");
        assertThat(SalesApprovalContent.canonical(null)).isEmpty();
    }

    @Test
    @DisplayName("an approval covers the current reasons only when it approved each of them")
    void coverage() {
        assertThat(SalesApprovalContent.covered(List.of(), List.of())).isTrue();
        assertThat(SalesApprovalContent.covered(List.of("A"), List.of("A", "B"))).isTrue();
        assertThat(SalesApprovalContent.covered(List.of("A", "C"), List.of("A", "B"))).isFalse();
        assertThat(SalesApprovalContent.covered(List.of("A"), null)).isFalse();
    }

    @Test
    @DisplayName("the rule version follows the approval settings only")
    void ruleVersion() {
        Map<String, Object> rules = new HashMap<>(Map.of("orderApprovalAmount", new BigDecimal("500.00"),
                "orderApprovalAlways", false, "stockCheckPolicy", "warn"));
        String v = SalesApprovalContent.ruleVersion(rules);
        rules.put("stockCheckPolicy", "block");
        rules.put("orderApprovalAmount", 500);
        assertThat(SalesApprovalContent.ruleVersion(rules)).isEqualTo(v);
        rules.put("orderApprovalAlways", true);
        assertThat(SalesApprovalContent.ruleVersion(rules)).isNotEqualTo(v);
    }
}
