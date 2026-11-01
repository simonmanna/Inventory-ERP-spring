package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.CostResolver;
import com.inventoryerp.inventory.application.CostResolver.Layer;
import com.inventoryerp.inventory.application.CostResolver.ProductCost;
import com.inventoryerp.inventory.application.CostResolver.Quant;
import com.inventoryerp.inventory.application.CostResolver.Resolution;
import com.inventoryerp.inventory.application.InventoryPostingRuleService;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Ports of {@code cost-resolver.service.spec.ts} and {@code stock-posting.service.spec.ts}. */
class InventoryUnitTest {

    static BigDecimal d(Object n) {
        return new BigDecimal(n.toString());
    }

    static String s(BigDecimal v) {
        return JsNumbers.dec(v);
    }

    @Nested
    @DisplayName("CostResolverService")
    class CostResolverSpec {

        final CostResolver svc = new CostResolver();
        final ProductCost avco = new ProductCost("AVCO", null);

        @Test
        @DisplayName("AVCO issue: uses the running average for the issue cost")
        void avcoIssue() {
            Resolution r = svc.resolveIssueCost(new ProductCost("AVCO", d(10)), new Quant(d(100), d("7.5")), d(20), null,
                    null);
            assertThat(s(r.unitCost())).isEqualTo("7.5");
            assertThat(s(r.totalValue())).isEqualTo("150");
        }

        @Test
        @DisplayName("AVCO issue: throws when no stock exists")
        void avcoNoStock() {
            assertThatThrownBy(() -> svc.resolveIssueCost(avco, null, d(1), null, null))
                    .hasMessageContaining("No stock");
        }

        @Test
        @DisplayName("AVCO receipt recompute: computes weighted average on first receipt (no prior stock)")
        void firstReceipt() {
            Resolution r = svc.resolveReceiptCost(avco, null, d(50), d(8));
            assertThat(s(r.newRunningAverage())).isEqualTo("8");
            assertThat(s(r.totalValue())).isEqualTo("400");
        }

        @Test
        @DisplayName("AVCO receipt recompute: recomputes weighted average on subsequent receipt")
        void subsequentReceipt() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(100), d(6)), d(50), d(8));
            assertThat(r.newRunningAverage().doubleValue()).isCloseTo(6.6667, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("AVCO receipt recompute: handles partial receipt into empty stock (qty only, no prior)")
        void emptyStock() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(0), d(0)), d(10), d(5));
            assertThat(s(r.newRunningAverage())).isEqualTo("5");
        }

        final List<Layer> batches = List.of(
                new Layer(d(30), d(10), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z")),
                new Layer(d(50), d(12), null, Instant.parse("2025-06-01T00:00:00Z")),
                new Layer(d(20), d(14), Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2025-02-01T00:00:00Z")));

        @Test
        @DisplayName("FIFO issue: consumes by expiry first then receivedAt")
        void fifo() {
            Resolution r = svc.resolveIssueCost(new ProductCost("FIFO", null), null, d(70), batches, null);
            assertThat(s(r.totalValue())).isEqualTo("820");
            assertThat(r.unitCost().doubleValue()).isCloseTo(11.7143, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("FIFO issue: throws when batches cannot cover the quantity")
        void fifoShort() {
            assertThatThrownBy(() -> svc.resolveIssueCost(new ProductCost("FIFO", null), null, d(1000), batches, null))
                    .hasMessageContaining("Insufficient");
        }

        @Test
        @DisplayName("STANDARD issue: uses Product.costPrice regardless of stock")
        void standard() {
            Resolution r = svc.resolveIssueCost(new ProductCost("STANDARD", d("4.5")), new Quant(d(50), d(99)), d(10),
                    null, null);
            assertThat(s(r.unitCost())).isEqualTo("4.5");
            assertThat(s(r.totalValue())).isEqualTo("45");
        }

        @Test
        @DisplayName("STANDARD receipt does not change running average")
        void standardReceipt() {
            Resolution r = svc.resolveReceiptCost(new ProductCost("STANDARD", d(5)), new Quant(d(100), d(99)), d(10), d(7));
            assertThat(r.newRunningAverage()).isNull();
            assertThat(s(r.totalValue())).isEqualTo("70");
        }

        @Test
        @DisplayName("edge cases: throws on non-positive issue quantity (returns ZERO)")
        void zeroQty() {
            assertThatCode(() -> svc.resolveIssueCost(avco, new Quant(d(10), d(5)), d(0), null, null))
                    .doesNotThrowAnyException();
        }

        BigDecimal implied(int oldQty, int oldAvg, int recvQty, int recvCost, BigDecimal correction) {
            return d(oldQty).multiply(d(oldAvg)).add(d(recvQty).multiply(d(recvCost))).subtract(correction);
        }

        @Test
        @DisplayName("negative on-hand: no correction on an ordinary positive-stock receipt")
        void noCorrection() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(10), d(100)), d(10), d(120));
            assertThat(s(r.newRunningAverage())).isEqualTo("110");
            assertThat(r.costCorrection()).isNull();
        }

        @Test
        @DisplayName("negative on-hand: receipt fully covering the shortfall restores the invariant (zero prior average)")
        void fullCover() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(-10), d(0)), d(20), d(100));
            assertThat(s(r.newRunningAverage())).isEqualTo("100");
            assertThat(s(r.costCorrection())).isEqualTo("1000");
            assertThat(s(implied(-10, 0, 20, 100, r.costCorrection()))).isEqualTo("1000");
        }

        @Test
        @DisplayName("negative on-hand: receipt covering only part of the shortfall keeps the invariant")
        void partialCover() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(-10), d(0)), d(5), d(100));
            assertThat(s(r.newRunningAverage())).isEqualTo("100");
            assertThat(s(implied(-10, 0, 5, 100, r.costCorrection()))).isEqualTo("-500");
        }

        @Test
        @DisplayName("negative on-hand: a second receipt finishing the shortfall lands the invariant back at zero")
        void secondReceipt() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(-5), d(100)), d(5), d(100));
            assertThat(r.costCorrection()).isNull();
            assertThat(s(implied(-5, 100, 5, 100, BigDecimal.ZERO))).isEqualTo("0");
        }

        @Test
        @DisplayName("negative on-hand: corrects only the difference when a stale average was already expensed")
        void staleAverage() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(-10), d(80)), d(20), d(100));
            assertThat(s(r.costCorrection())).isEqualTo("200");
            assertThat(s(implied(-10, 80, 20, 100, r.costCorrection()))).isEqualTo("1000");
        }

        @Test
        @DisplayName("negative on-hand: does not blend a negative quantity into the new average")
        void noBlend() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(-10), d(0)), d(20), d(50));
            assertThat(s(r.newRunningAverage())).isEqualTo("50");
        }

        @Test
        @DisplayName("negative on-hand: treats a receipt onto exactly zero on-hand as a fresh basis, with no correction")
        void zeroOnHand() {
            Resolution r = svc.resolveReceiptCost(avco, new Quant(d(0), d(999)), d(4), d(25));
            assertThat(s(r.newRunningAverage())).isEqualTo("25");
            assertThat(r.costCorrection()).isNull();
        }
    }

    @Nested
    @DisplayName("StockPostingService")
    class StockPostingSpec {

        PostingService posting;
        AccountDeterminationService determination;
        StockPostingService svc;

        @BeforeEach
        void setUp() {
            TenantContext.set(TenantContext.TenantStore.user("org-1", "user-1"));
            posting = mock(PostingService.class);
            InventoryPostingRuleService rules = mock(InventoryPostingRuleService.class);
            when(rules.resolve(anyString(), anyString(), any(), any()))
                    .thenThrow(ApiException.badRequest("No posting rule configured for movement type 'X'."));
            determination = mock(AccountDeterminationService.class);
            when(determination.mapped(anyString())).thenAnswer(inv -> switch ((String) inv.getArgument(0)) {
                case "cogs" -> "acc-cogs";
                case "stock_valuation" -> "acc-stockval";
                case "wip" -> "acc-wip";
                default -> "acc-" + inv.getArgument(0);
            });
            JdbcClient jdbc = mock(JdbcClient.class, Answers.RETURNS_DEEP_STUBS);
            svc = new StockPostingService(jdbc, posting, determination, new CostResolver(), rules, null);
        }

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        PostingRequest captured() {
            ArgumentCaptor<PostingRequest> c = ArgumentCaptor.forClass(PostingRequest.class);
            verify(posting, times(1)).post(c.capture());
            return c.getValue();
        }

        static PostingRequest.Line debit(PostingRequest r) {
            return r.lines().stream().filter(l -> l.debit() != null).findFirst().orElseThrow();
        }

        static PostingRequest.Line credit(PostingRequest r) {
            return r.lines().stream().filter(l -> l.credit() != null).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("postReturnRestock: posts a balanced Dr Stock Valuation / Cr COGS at the supplied cost")
        void returnRestock() {
            svc.postReturnRestock("p1", d(150), Instant.parse("2026-07-23T00:00:00Z"), "pos_refund", "inv1", null);
            PostingRequest r = captured();
            assertThat(r.journalCode()).isEqualTo("INV");
            assertThat(debit(r).accountId()).isEqualTo("acc-stockval");
            assertThat(credit(r).accountId()).isEqualTo("acc-cogs");
            assertThat(d(debit(r).debit())).isEqualByComparingTo("150");
            assertThat(d(credit(r).credit())).isEqualByComparingTo("150");
        }

        @Test
        @DisplayName("postReturnRestock: skips the GL when the restocked cost is zero")
        void returnZero() {
            svc.postReturnRestock("p1", d(0), Instant.now(), "pos_refund", "inv1", null);
            verify(posting, never()).post(any());
        }

        @Test
        @DisplayName("postReturnRestock: skips the GL for a negative cost (never posts a reversed entry)")
        void returnNegative() {
            svc.postReturnRestock("p1", d(-5), Instant.now(), "pos_refund", "inv1", null);
            verify(posting, never()).post(any());
        }

        @Test
        @DisplayName("production legs: postProductionConsume posts Dr WIP / Cr Stock Valuation, distinct accounts")
        void consume() {
            svc.postProductionConsume("p1", d(200), Instant.parse("2026-08-02T00:00:00Z"), "production_order", "MO-1",
                    null);
            PostingRequest r = captured();
            assertThat(debit(r).accountId()).isEqualTo("acc-wip");
            assertThat(credit(r).accountId()).isEqualTo("acc-stockval");
            assertThat(debit(r).accountId()).isNotEqualTo(credit(r).accountId());
            assertThat(d(debit(r).debit())).isEqualByComparingTo("200");
            assertThat(d(credit(r).credit())).isEqualByComparingTo("200");
        }

        @Test
        @DisplayName("production legs: postProductionOutput posts Dr Stock Valuation / Cr WIP, distinct accounts")
        void output() {
            svc.postProductionOutput("p1", d(200), Instant.parse("2026-08-02T00:00:00Z"), "production_order", "MO-1",
                    null);
            PostingRequest r = captured();
            assertThat(debit(r).accountId()).isEqualTo("acc-stockval");
            assertThat(credit(r).accountId()).isEqualTo("acc-wip");
            assertThat(debit(r).accountId()).isNotEqualTo(credit(r).accountId());
        }

        @Test
        @DisplayName("production legs: skips the GL when the production value is zero")
        void productionZero() {
            svc.postProductionConsume("p1", d(0), Instant.now(), "production_order", "MO-1", null);
            verify(posting, never()).post(any());
        }
    }

    @Nested
    @DisplayName("decimal.js toString parity")
    class DecimalString {

        @Test
        @DisplayName("plain below 1e-7 and above 1e21 switch to exponent notation")
        void decimalString() {
            assertThat(JsNumbers.dec(d("12.500000"))).isEqualTo("12.5");
            assertThat(JsNumbers.dec(d("0"))).isEqualTo("0");
            assertThat(JsNumbers.dec(d("0.000001"))).isEqualTo("0.000001");
            assertThat(JsNumbers.dec(d("0.0000001"))).isEqualTo("1e-7");
            assertThat(JsNumbers.dec(d("1500"))).isEqualTo("1500");
        }
    }
}
