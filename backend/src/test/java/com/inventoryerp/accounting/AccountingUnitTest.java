package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.AccountClassification;
import com.inventoryerp.accounting.application.AccountingCatalogs;
import com.inventoryerp.accounting.application.MoneyActivityTaxonomy;
import com.inventoryerp.accounting.application.MoneyActivityTaxonomy.MoneyLine;
import com.inventoryerp.accounting.application.OrgDates;
import com.inventoryerp.accounting.application.PostingMath;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure accounting rules: ports of {@code posting.math.spec.ts}, {@code money-activity.taxonomy.spec.ts},
 * {@code org-dates.spec.ts} and {@code coa-template.spec.ts} (catalog consistency).
 */
class AccountingUnitTest {

    static PostingRequest.Line l(String account, Object debit, Object credit) {
        return new PostingRequest.Line(account, debit, credit, null, null, null, null, null);
    }

    @Nested
    @DisplayName("posting.math (double-entry rules)")
    class Math {
        @Test
        void baseAmountsUseTheExchangeRate() {
            var lines = PostingMath.normalizeLines(List.of(l("a", 100, null), l("b", null, 100)), new BigDecimal("2"));
            assertThat(lines.get(0).baseDebit()).isEqualByComparingTo("200");
            assertThat(lines.get(1).baseCredit()).isEqualByComparingTo("200");
        }

        @Test
        void balancedAndUnbalanced() {
            var ok = PostingMath.normalizeLines(List.of(l("a", 100, null), l("b", null, 60), l("c", null, 40)), BigDecimal.ONE);
            assertThat(PostingMath.isBalanced(ok)).isTrue();
            assertThat(PostingMath.totals(ok).debit()).isEqualByComparingTo("100");
            var bad = PostingMath.normalizeLines(List.of(l("a", 100, null), l("b", null, 90)), BigDecimal.ONE);
            assertThat(PostingMath.isBalanced(bad)).isFalse();
        }

        @Test
        void lineValidation() {
            assertThat(PostingMath.validateLines(PostingMath.normalizeLines(List.of(l("a", 100, 50)), BigDecimal.ONE)))
                    .extracting(PostingMath.LineValidationError::message)
                    .contains("A line cannot have both a debit and a credit");
            assertThat(PostingMath.validateLines(PostingMath.normalizeLines(List.of(l("a", null, null)), BigDecimal.ONE)))
                    .extracting(PostingMath.LineValidationError::message)
                    .contains("A line must have either a debit or a credit");
        }

        @Test
        void subEpsilonImbalanceIsApproximatelyBalanced() {
            var lines = PostingMath.normalizeLines(List.of(l("a", "100.00005", null), l("b", null, 100)), BigDecimal.ONE);
            assertThat(PostingMath.isBalanced(lines)).isTrue();
        }
    }

    static MoneyLine line(String account, double debit, double credit) {
        return new MoneyLine(account, account, null, debit, credit);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> legs(Map<String, Object> a) {
        return (List<Map<String, Object>>) a.get("legs");
    }

    @Nested
    @DisplayName("money activity taxonomy")
    class Taxonomy {
        @Test
        void cashPosSaleIsMoneyIn() {
            assertThat(MoneyActivityTaxonomy.classify("pos_invoice", List.of(line("drawer", 35000, 0))))
                    .containsEntry("category", "pos_sales").containsEntry("direction", "in")
                    .containsEntry("externalIn", "35000.00").containsEntry("externalOut", "0.00").containsEntry("internalMoved", "0.00");
        }

        @Test
        void splitTenderSaleHasThreeLegs() {
            var a = MoneyActivityTaxonomy.classify("pos_invoice", List.of(line("drawer", 30000, 0), line("mtn", 40000, 0),
                    line("card", 30000, 0)));
            assertThat(a).containsEntry("direction", "in").containsEntry("grossAmount", "100000.00")
                    .containsEntry("externalIn", "100000.00");
            assertThat(legs(a)).extracting(m -> List.of(m.get("accountId"), m.get("side"), m.get("amount")))
                    .containsExactly(List.of("mtn", "in", "40000.00"), List.of("drawer", "in", "30000.00"),
                            List.of("card", "in", "30000.00"));
        }

        @Test
        void directions() {
            assertThat(MoneyActivityTaxonomy.classify("pos_refund", List.of(line("drawer", 0, 5000))))
                    .containsEntry("category", "refunds").containsEntry("direction", "out").containsEntry("externalOut", "5000.00");
            assertThat(MoneyActivityTaxonomy.classify("payment", List.of(line("bank", 80000, 0))))
                    .containsEntry("category", "customer_receipts").containsEntry("direction", "in");
            for (String[] c : new String[][] {{"purchase_payment", "supplier_payments"}, {"expense_payment", "expenses"},
                    {"payroll_run", "payroll"}, {"cash_flow_withdrawal", "withdrawals"}}) {
                assertThat(MoneyActivityTaxonomy.classify(c[0], List.of(line("bank", 0, 500000))))
                        .containsEntry("category", c[1]).containsEntry("direction", "out")
                        .containsEntry("externalOut", "500000.00").containsEntry("externalIn", "0.00");
            }
            assertThat(MoneyActivityTaxonomy.classify("cash_flow_deposit", List.of(line("bank", 1000000, 0))))
                    .containsEntry("category", "deposits").containsEntry("direction", "in");
        }

        @Test
        void transfersAndSettlementsAreInternal() {
            var t = MoneyActivityTaxonomy.classify("treasury_transfer", List.of(line("mtn", 0, 300000), line("stanbic", 300000, 0)));
            assertThat(t).containsEntry("category", "transfers").containsEntry("direction", "internal")
                    .containsEntry("internalMoved", "300000.00").containsEntry("externalIn", "0.00").containsEntry("externalOut", "0.00");
            assertThat(legs(t)).hasSize(2);
            assertThat(MoneyActivityTaxonomy.classify("tender_settlement", List.of(line("card_clearing", 0, 100000),
                    line("bank", 98000, 0))))
                    .containsEntry("category", "tender_settlement").containsEntry("direction", "internal")
                    .containsEntry("grossAmount", "100000.00").containsEntry("internalMoved", "98000.00")
                    .containsEntry("externalOut", "2000.00").containsEntry("externalIn", "0.00");
            assertThat(MoneyActivityTaxonomy.classify("cash_session_banking", List.of(line("drawer", 0, 400000),
                    line("bank", 400000, 0))))
                    .containsEntry("category", "cash_drawer").containsEntry("direction", "internal")
                    .containsEntry("internalMoved", "400000.00");
        }

        @Test
        void adjustmentsAndOther() {
            assertThat(MoneyActivityTaxonomy.classify("cash_movement", List.of(line("drawer", 0, 20000))))
                    .containsEntry("category", "cash_drawer").containsEntry("direction", "out");
            assertThat(MoneyActivityTaxonomy.classify("cash_session_variance", List.of(line("drawer", 0, 15000))))
                    .containsEntry("category", "cash_drawer").containsEntry("direction", "adjustment")
                    .containsEntry("externalOut", "15000.00");
            assertThat(MoneyActivityTaxonomy.classify("reversal", List.of(line("bank", 500000, 0))))
                    .containsEntry("category", "adjustments").containsEntry("direction", "adjustment")
                    .containsEntry("externalIn", "500000.00");
            assertThat(MoneyActivityTaxonomy.classify("something_new", List.of(line("bank", 0, 700))))
                    .containsEntry("category", "other").containsEntry("categoryLabel", "Other").containsEntry("direction", "out");
            assertThat(MoneyActivityTaxonomy.categoryOf(null)).isEqualTo("other");
        }

        @Test
        void netsOneAccountIntoOneLeg() {
            var a = MoneyActivityTaxonomy.classify("manual", List.of(line("bank", 1000, 0), line("bank", 0, 400)));
            assertThat(legs(a)).hasSize(1);
            assertThat(legs(a).getFirst()).containsEntry("accountId", "bank").containsEntry("side", "in").containsEntry("amount", "600.00");
        }

        @Test
        void refinesGenericPayments() {
            assertThat(MoneyActivityTaxonomy.categoryOf(MoneyActivityTaxonomy.effectiveSourceType("payment", "s1", "inbound", true)))
                    .isEqualTo("pos_sales");
            assertThat(MoneyActivityTaxonomy.categoryOf(MoneyActivityTaxonomy.effectiveSourceType("payment", "s1", "outbound", true)))
                    .isEqualTo("refunds");
            assertThat(MoneyActivityTaxonomy.categoryOf(MoneyActivityTaxonomy.effectiveSourceType("payment", null, "outbound", true)))
                    .isEqualTo("supplier_payments");
            assertThat(MoneyActivityTaxonomy.categoryOf(MoneyActivityTaxonomy.effectiveSourceType("payment", null, "inbound", true)))
                    .isEqualTo("customer_receipts");
            assertThat(MoneyActivityTaxonomy.categoryOf(MoneyActivityTaxonomy.effectiveSourceType("payment", null, null, false)))
                    .isEqualTo("customer_receipts");
            assertThat(MoneyActivityTaxonomy.effectiveSourceType("treasury_transfer", "s1", "inbound", true))
                    .isEqualTo("treasury_transfer");
        }
    }

    @Nested
    @DisplayName("org-dates")
    class Dates {
        final ZoneId tz = ZoneId.of("Africa/Kampala");

        @Test
        void boundsALocalCalendarDay() {
            assertThat(OrgDates.orgDateBound("2026-09-15", "from", tz, true)).isEqualTo(Instant.parse("2026-09-14T21:00:00.000Z"));
            assertThat(OrgDates.orgDateBound("2026-09-15", "to", tz, false)).isEqualTo(Instant.parse("2026-09-15T20:59:59.999Z"));
        }

        @Test
        void keepsAKampalaNightSaleInsideItsLocalDay() {
            Instant sale = Instant.parse("2026-09-14T22:30:00.000Z");
            assertThat(OrgDates.orgToday(tz, sale)).isEqualTo("2026-09-15");
            Instant start = OrgDates.orgDateBound("2026-09-15", "from", tz, true);
            Instant end = OrgDates.orgDateBound("2026-09-15", "to", tz, false);
            assertThat(!sale.isBefore(start) && !sale.isAfter(end)).isTrue();
        }

        @Test
        void timestampsPassThroughAndGarbageIsRejected() {
            assertThat(OrgDates.orgDateBound("2026-09-15T10:00:00Z", "from", tz, true)).isEqualTo(Instant.parse("2026-09-15T10:00:00Z"));
            assertThatThrownBy(() -> OrgDates.orgDateBound("nope", "from", tz, true)).isInstanceOf(ApiException.class)
                    .hasMessage("Invalid `from` date: nope");
        }

        @Test
        void postingInstants() {
            Instant now = Instant.parse("2026-09-14T22:30:00.000Z");
            assertThat(OrgDates.orgPostingInstant("2026-09-15", tz, now)).isEqualTo(now);
            assertThat(OrgDates.orgPostingInstant("2026-09-10", tz, now)).isEqualTo(Instant.parse("2026-09-10T09:00:00.000Z"));
            assertThatThrownBy(() -> OrgDates.orgPostingInstant("2026-09-16", tz, now)).hasMessage("The date cannot be in the future");
            assertThat(OrgDates.orgPostingInstant(null, tz, now)).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("COA template and catalogs")
    class Catalogs {
        @Test
        void everyRequiredMappingIsCoveredByTheTemplate() {
            Set<String> mapped = AccountingCatalogs.COA_MAPPINGS.keySet();
            for (AccountingCatalogs.MappingDef def : AccountingCatalogs.MAPPING_REGISTRY) {
                if (def.required()) {
                    assertThat(mapped).as("required mapping " + def.key()).contains(def.key());
                }
            }
        }

        @Test
        void templateCodesAndCategoriesResolve() {
            Set<String> codes = new HashSet<>();
            for (AccountingCatalogs.CoaAccount a : AccountingCatalogs.COA_ACCOUNTS) {
                assertThat(codes.add(a.code())).as("duplicate code " + a.code()).isTrue();
                assertThat(a.categoryKey() == null).as("group invariant " + a.code())
                        .isEqualTo(a.isPostable() != null && !a.isPostable());
                if (a.categoryKey() != null) {
                    assertThat(AccountingCatalogs.CATEGORY_BY_KEY).containsKey(a.categoryKey());
                }
            }
            for (AccountingCatalogs.CoaAccount a : AccountingCatalogs.COA_ACCOUNTS) {
                if (a.parentCode() != null) {
                    assertThat(codes).contains(a.parentCode());
                }
            }
            AccountingCatalogs.COA_MAPPINGS.values().forEach(code -> assertThat(codes).contains(code));
        }

        @Test
        void mappedAccountsMatchTheirExpectedCategories() {
            Map<String, String> categoryOf = new java.util.HashMap<>();
            AccountingCatalogs.COA_ACCOUNTS.forEach(a -> categoryOf.put(a.code(), a.categoryKey()));
            AccountingCatalogs.COA_MAPPINGS.forEach((key, code) -> {
                AccountingCatalogs.MappingDef def = AccountingCatalogs.MAPPING_BY_KEY.get(key);
                if (def != null && !def.expectedCategories().isEmpty()) {
                    assertThat(def.expectedCategories()).as(key + " → " + code).contains(categoryOf.get(code));
                }
            });
        }

        @Test
        void reportSectionSides() {
            assertThat(AccountClassification.balanceSheetSideOf("current_assets")).isEqualTo("asset");
            assertThat(AccountClassification.balanceSheetSideOf("revenue")).isNull();
            assertThat(AccountClassification.cashFlowSectionOf("revenue", "investing")).isEqualTo("operating");
            assertThat(AccountClassification.cashFlowSectionOf("asset", "investing")).isEqualTo("investing");
        }
    }
}
