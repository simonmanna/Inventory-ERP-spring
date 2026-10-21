package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.OrderMutationPolicy;
import com.inventoryerp.pos.application.OrderMutationPolicy.FiredLine;
import com.inventoryerp.pos.application.PosLines;
import com.inventoryerp.pos.application.PosOverridesService;
import com.inventoryerp.pos.application.PricingPolicy;
import com.inventoryerp.pos.application.PricingPolicy.Verdict;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Pure POS rules (ports of {@code order-mutation-policy.spec.ts}, {@code pricing-policy.spec.ts},
 * {@code order/line-signature.spec.ts}).
 */
class PosUnitTest {

    static <T> T asUser(List<String> unused, Supplier<T> body) {
        return TenantContext.run(TenantStore.user("org-1", "u-1"), body);
    }

    @Nested
    class MutationPolicy {

        final Map<String, Object> firedLine = row("i1", "Nile Special", 2, 2.0);
        final Map<String, Object> unfiredLine = row("i2", "Still Water", 1, null);

        Map<String, Object> row(String id, String description, double qty, Double printed) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("description", description);
            m.put("quantity", qty);
            m.put("kitchenPrintedQty", printed);
            return m;
        }

        OrderMutationPolicy policy(List<String> permissions, PosOverridesService overrides) {
            PricingPolicy pricing = new PricingPolicy(null, overrides) {
                @Override
                public List<String> currentPermissions(String userId) {
                    return permissions;
                }
            };
            return new OrderMutationPolicy(pricing, overrides);
        }

        JdbcClient jdbcWith(List<Map<String, Object>> rows) {
            JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
            when(jdbc.sql(anyString()).param(any()).query().listOfRows()).thenReturn(rows);
            return jdbc;
        }

        @Test
        void rejectsPlaceholderReasons() {
            assertThat(OrderMutationPolicy.isMeaningfulReason("Order emptied")).isFalse();
            assertThat(OrderMutationPolicy.isMeaningfulReason("  ")).isFalse();
            assertThat(OrderMutationPolicy.isMeaningfulReason("n/a")).isFalse();
            assertThat(OrderMutationPolicy.isMeaningfulReason("ab")).isFalse();
            assertThat(OrderMutationPolicy.isMeaningfulReason("Customer left before service")).isTrue();
        }

        @Test
        void unfiredOrderCancelsWithoutCeremony() {
            PosOverridesService overrides = mock(PosOverridesService.class);
            List<FiredLine> fired = asUser(List.of(), () -> policy(List.of(), overrides)
                    .assertOrderCancellationAllowed(jdbcWith(List.of(unfiredLine)), "o1", null, null, null));
            assertThat(fired).isEmpty();
            verify(overrides, never()).verifyOperationApproval(any(), any(), any());
        }

        @Test
        void firedFoodNeedsVoidPermission() {
            PosOverridesService overrides = mock(PosOverridesService.class);
            assertThatThrownBy(() -> asUser(List.of(), () -> policy(List.of("pos:checkout"), overrides)
                    .assertOrderCancellationAllowed(jdbcWith(List.of(firedLine)), "o1", "Customer left", "mgr-1", "1234")))
                    .isInstanceOf(ApiException.class).hasMessageContaining("void permission");
        }

        @Test
        void placeholderReasonRefused() {
            PosOverridesService overrides = mock(PosOverridesService.class);
            assertThatThrownBy(() -> asUser(List.of(), () -> policy(List.of("pos:void"), overrides)
                    .assertOrderCancellationAllowed(jdbcWith(List.of(firedLine)), "o1", "Order emptied", "mgr-1", "1234")))
                    .hasMessageContaining("Give a reason");
        }

        @Test
        void managerRequired() {
            PosOverridesService overrides = mock(PosOverridesService.class);
            assertThatThrownBy(() -> asUser(List.of(), () -> policy(List.of("pos:void"), overrides)
                    .assertOrderCancellationAllowed(jdbcWith(List.of(firedLine)), "o1", "Customer left before service",
                            null, null)))
                    .hasMessageContaining("manager approval and PIN");
            verify(overrides, never()).verifyOperationApproval(any(), any(), any());
        }

        @Test
        void approvalVerifiedAsVoid() {
            PosOverridesService overrides = mock(PosOverridesService.class);
            List<FiredLine> fired = asUser(List.of(), () -> policy(List.of("pos:void"), overrides)
                    .assertOrderCancellationAllowed(jdbcWith(List.of(firedLine, unfiredLine)), "o1",
                            "Customer left before service", "mgr-1", "1234"));
            verify(overrides).verifyOperationApproval("mgr-1", "1234", "void");
            assertThat(fired).containsExactly(new FiredLine("i1", "Nile Special", 2, 2));
        }

        @Test
        void firedItemLossBlocked() {
            assertThatThrownBy(() -> OrderMutationPolicy.assertNoFiredItemLoss(List.of(firedLine), List.of()))
                    .hasMessage("Already sent to the kitchen: Nile Special. Void the item (reason + manager PIN) instead of"
                            + " removing it.");
            OrderMutationPolicy.assertNoFiredItemLoss(List.of(unfiredLine), List.of());
        }
    }

    @Nested
    class Pricing {

        final PosOverridesService overrides = mock(PosOverridesService.class);
        final List<Integer> lookups = new ArrayList<>();

        PricingPolicy ctx(Object tier1, Object tier1Amount, List<String> permissions) {
            return new PricingPolicy(null, overrides) {
                @Override
                public List<String> currentPermissions(String userId) {
                    lookups.add(1);
                    return permissions;
                }

                @Override
                public JsonNode discountApproval() {
                    lookups.add(2);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tier1", tier1);
                    m.put("tier1Amount", tier1Amount);
                    return JsonMapper.builder().build().valueToTree(m);
                }
            };
        }

        PricingPolicy ctx() {
            return ctx(null, null, List.of("pos:discount"));
        }

        Map<String, Object> line(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>(Map.of("quantity", 1, "unitPrice", 10000, "discountPercent", 0,
                    "discountType", "percentage", "discountAmount", 0));
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], kv[i + 1]);
            }
            return m;
        }

        Map<String, Object> input(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], kv[i + 1]);
            }
            return m;
        }

        Verdict eval(PricingPolicy p, List<Map<String, Object>> lines, Map<String, Object> input) {
            return asUser(List.of(), () -> p.evaluate(lines, input));
        }

        @Test
        void thresholdResolution() {
            JsonNodeFactory f = JsonNodeFactory.instance;
            assertThat(PricingPolicy.resolveDiscountThreshold(null)).isEqualTo(10);
            assertThat(PricingPolicy.resolveDiscountThreshold(f.stringNode("abc"))).isEqualTo(10);
            assertThat(PricingPolicy.resolveDiscountThreshold(f.numberNode(-5))).isEqualTo(0);
            assertThat(PricingPolicy.resolveDiscountThreshold(f.numberNode(140))).isEqualTo(100);
            assertThat(PricingPolicy.resolveDiscountThreshold(f.numberNode(5))).isEqualTo(5);
        }

        @Test
        void undiscountedCartTouchesNothing() {
            Verdict v = eval(ctx(), List.of(line()), input());
            assertThat(v).isEqualTo(new Verdict(0, 10, 0, 0, false, false, true));
            assertThat(lookups).isEmpty();
        }

        @Test
        void amountTier() {
            Map<String, Object> big = line("unitPrice", 5_000_000, "discountPercent", 9);
            Verdict off = eval(ctx(), List.of(big), input("discountReason", "Regular"));
            assertThat(off.thresholdAmount()).isZero();
            assertThat(off.discountAmount()).isCloseTo(450_000, within(0.001));
            assertThat(off.requiresApproval()).isFalse();
            assertThat(eval(ctx(null, 100_000, List.of("pos:discount")), List.of(big), input("discountReason", "Regular"))
                    .requiresApproval()).isTrue();
            Verdict small = eval(ctx(null, 100_000, List.of("pos:discount")), List.of(line("discountPercent", 5)),
                    input("discountReason", "Regular"));
            assertThat(small.discountAmount()).isCloseTo(500, within(0.001));
            assertThat(small.requiresApproval()).isFalse();
            List<Map<String, Object>> many = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                many.add(line("unitPrice", 500_000, "discountPercent", 5));
            }
            Verdict sum = eval(ctx(null, 100_000, List.of("pos:discount")), many, input("discountReason", "Staff party"));
            assertThat(sum.maxDiscountPercent()).isCloseTo(5, within(0.0001));
            assertThat(sum.discountAmount()).isCloseTo(150_000, within(0.001));
            assertThat(sum.requiresApproval()).isTrue();
        }

        @Test
        void reasons() {
            Verdict noReason = eval(ctx(), List.of(line("discountPercent", 5)), input());
            assertThat(noReason.maxDiscountPercent()).isCloseTo(5, within(0.0001));
            assertThat(noReason.requiresReason()).isTrue();
            assertThat(noReason.requiresApproval()).isFalse();
            assertThat(eval(ctx(), List.of(line("discountPercent", 5, "discountReason", "Staff")), input())
                    .requiresReason()).isFalse();
            assertThat(eval(ctx(), List.of(line("discountPercent", 5, "discountReason", "Staff")),
                    input("transactionDiscountPercent", 5)).requiresReason()).isTrue();
        }

        @Test
        void orgThresholdAndPermission() {
            Verdict under = eval(ctx(5, null, List.of("pos:discount")), List.of(line("discountPercent", 4,
                    "discountReason", "r")), input("discountReason", "r"));
            assertThat(under.threshold()).isEqualTo(5);
            assertThat(under.requiresApproval()).isFalse();
            assertThat(eval(ctx(5, null, List.of("pos:discount")), List.of(line("discountPercent", 8,
                    "discountReason", "r")), input("discountReason", "r")).requiresApproval()).isTrue();
            Verdict cashier = eval(ctx(null, null, List.of("pos:checkout")), List.of(line("discountPercent", 1,
                    "discountReason", "r")), input("discountReason", "r"));
            assertThat(cashier.hasDiscountPermission()).isFalse();
            assertThat(cashier.requiresApproval()).isTrue();
            Verdict fixed = eval(ctx(), List.of(line("discountType", "fixed_amount", "discountAmount", 2500,
                    "discountReason", "r")), input("discountReason", "r"));
            assertThat(fixed.maxDiscountPercent()).isCloseTo(25, within(0.0001));
            assertThat(fixed.requiresApproval()).isTrue();
        }

        @Test
        void enforcement() {
            assertThat(asUser(List.of(), () -> ctx().assertAuthority(List.of(line()), input()))).isZero();
            assertThatThrownBy(() -> asUser(List.of(), () -> ctx().assertAuthority(List.of(line("discountPercent", 5)),
                    input()))).hasMessage("A discount reason is required");
            assertThatThrownBy(() -> asUser(List.of(), () -> ctx(5, null, List.of("pos:discount")).assertAuthority(
                    List.of(line("discountPercent", 20, "discountReason", "r")), input("discountReason", "r"))))
                    .hasMessage("This discount requires manager approval and PIN");
            double max = asUser(List.of(), () -> ctx(5, null, List.of("pos:discount")).assertAuthority(
                    List.of(line("discountPercent", 20, "discountReason", "r")), input("discountReason", "r",
                            "overrideById", "mgr-1", "overridePin", "1234")));
            assertThat(max).isCloseTo(20, within(0.0001));
            verify(overrides).verifyOperationApproval("mgr-1", "1234", "discount");
        }

        @Test
        void combinedDiscountChecked() {
            Map<String, Object> l = line("unitPrice", 100, "discountPercent", 6);
            Map<String, Object> in = input("transactionDiscountPercent", 6, "discountReason", "Promotion");
            assertThatThrownBy(() -> asUser(List.of(), () -> ctx(10, null, List.of("pos:discount"))
                    .assertAuthority(List.of(l), in))).hasMessageContaining("manager approval");
        }

        @Test
        void impossibleDiscounts() {
            assertThatThrownBy(() -> PricingPolicy.discountedLines(List.of(line("unitPrice", 10)),
                    input("transactionDiscountType", "fixed_amount", "transactionDiscountAmount", 11)))
                    .hasMessageContaining("exceeds");
            assertThatThrownBy(() -> asUser(List.of(), () -> ctx().assertAuthority(List.of(line("unitPrice", 100,
                    "discountType", "fixed_amount", "discountAmount", 1)), input()))).hasMessageContaining("reason");
        }
    }

    @Nested
    class LineSignature {

        String sig(String note, Object course, List<String> mods, List<String> accs) {
            return PosLines.lineSignature(null, "m-latte", null, "Large", "Latte", note, course, mods, accs);
        }

        @Test
        void customisationsChangeSignature() {
            assertThat(sig(null, null, List.of("oat"), List.of())).isNotEqualTo(sig(null, null, List.of("dairy"), List.of()));
            String plain = sig(null, null, List.of(), List.of());
            assertThat(sig("no sugar", null, List.of(), List.of())).isNotEqualTo(plain);
            assertThat(sig(null, 2, List.of(), List.of())).isNotEqualTo(plain);
            assertThat(sig(null, null, List.of(), List.of("fries"))).isNotEqualTo(plain);
        }

        @Test
        void persistedRowMatchesIncomingLine() {
            Map<String, Object> row = new HashMap<>();
            row.put("menuItemId", "m-latte");
            row.put("variantName", "Large");
            row.put("description", "Latte");
            row.put("note", "hot");
            row.put("accompanimentOptionIds", List.of("fries"));
            row.put("modifiers", List.of(Map.of("modifierId", "oat")));
            assertThat(PosLines.rowSignature(row)).isEqualTo(sig("hot", null, List.of("oat"), List.of("fries")));
        }

        @Test
        void modifierOrderIgnored() {
            assertThat(sig(null, null, List.of("x", "y"), List.of())).isEqualTo(sig(null, null, List.of("y", "x"), List.of()));
        }
    }
}
