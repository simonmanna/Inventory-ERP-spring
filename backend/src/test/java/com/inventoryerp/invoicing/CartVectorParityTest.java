package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.invoicing.application.TaxCalculationService;
import com.inventoryerp.invoicing.application.TaxCalculationService.LineTaxResult;
import com.inventoryerp.invoicing.application.TaxCalculationService.TaxLike;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pricing parity with the Android POS (port of {@code scripts/export-cart-vectors.ts}).
 *
 * <p>The device prices a cart offline and prints the receipt; the server re-prices the same cart on sync. Both sides
 * assert the golden totals in {@code android/app/src/test/resources/cart-vectors.json}: the app's
 * {@code CartVectorParityTest} runs Kotlin's CartEngine, this one runs the real {@link TaxCalculationService} with the
 * transaction-discount folding {@code PosInvoiceService} uses. A pricing change that breaks either side fails here or
 * there, never silently on a customer's receipt.
 */
class CartVectorParityTest {

    private static final Path VECTORS = Path.of("..", "android", "app", "src", "test", "resources",
            "cart-vectors.json");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TaxCalculationService tax = new TaxCalculationService();

    @TestFactory
    Stream<DynamicTest> springPricingMatchesAndroidGoldenVectors() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(VECTORS));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : root.get("vectors")) {
            tests.add(DynamicTest.dynamicTest(v.get("id").asString(), () -> {
                JsonNode expected = v.get("expected");
                BigDecimal[] actual = compute(v);
                assertThat(round2(actual[0])).as("subtotal").isEqualByComparingTo(dec(expected.get("subtotal")));
                assertThat(round2(actual[1])).as("discountTotal")
                        .isEqualByComparingTo(dec(expected.get("discountTotal")));
                assertThat(round2(actual[2])).as("taxTotal").isEqualByComparingTo(dec(expected.get("taxTotal")));
                assertThat(round2(actual[3])).as("total").isEqualByComparingTo(dec(expected.get("total")));
            }));
        }
        assertThat(tests).isNotEmpty();
        return tests.stream();
    }

    /** {@code computeExpected} from the Nest exporter: [subtotal, discountTotal, taxTotal, total]. */
    private BigDecimal[] compute(JsonNode v) {
        BigDecimal txPct = opt(v, "transactionDiscountPercent");
        BigDecimal txFactor = BigDecimal.ONE.subtract(txPct.divide(HUNDRED));
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode l : v.get("lines")) {
            BigDecimal unit = dec(l.get("baseUnitPrice")).add(sum(l.get("modifierDeltas")))
                    .add(sum(l.get("accompanimentImpacts")));
            BigDecimal gross = dec(l.get("quantity")).multiply(unit);
            // PosInvoiceService folds the transaction discount INTO the line percent.
            BigDecimal linePct = opt(l, "discountPercent");
            BigDecimal foldedPct = txPct.signum() > 0
                    ? HUNDRED.multiply(BigDecimal.ONE.subtract(
                            BigDecimal.ONE.subtract(linePct.divide(HUNDRED)).multiply(txFactor)))
                    : linePct;
            BigDecimal discount = gross.multiply(foldedPct.divide(HUNDRED));
            BigDecimal afterDiscount = gross.subtract(discount);
            BigDecimal rate = opt(l, "taxRatePercent");
            List<TaxLike> taxes = rate.signum() > 0
                    ? List.of(new TaxLike("t", rate, l.path("taxInclusive").asBoolean(false), false, null))
                    : List.of();
            LineTaxResult result = tax.computeLine(afterDiscount, taxes, null);
            subtotal = subtotal.add(gross);
            discountTotal = discountTotal.add(discount);
            taxTotal = taxTotal.add(result.taxTotal());
            total = total.add(result.gross());
        }
        return new BigDecimal[] {subtotal, discountTotal, taxTotal, total};
    }

    private static BigDecimal sum(JsonNode arr) {
        BigDecimal s = BigDecimal.ZERO;
        if (arr != null) {
            for (JsonNode d : arr) {
                s = s.add(dec(d));
            }
        }
        return s;
    }

    private static BigDecimal opt(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? BigDecimal.ZERO : dec(v);
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
