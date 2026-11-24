package com.inventoryerp.qualitycontrol.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Action;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Cancel;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Create;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Existing;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.ProductInfo;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Recompute;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Requirement;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S087 §3–§4: matching, controlPer, test quantities and the reconciliation table. */
class QcCheckGeneratorTest {

    static final Function<String, ProductInfo> PRODUCTS = id -> switch (id) {
        case "A", "B" -> new ProductInfo("CAT1", 0);
        case "C" -> new ProductInfo("CAT2", 0);
        case "K" -> new ProductInfo("CAT2", 2);
        default -> null;
    };

    static Map<String, Object> point(String per, Object... kv) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", "P1");
        p.put("controlPer", per);
        p.put("productIds", "[]");
        p.put("categoryIds", "[]");
        for (int i = 0; i < kv.length; i += 2) {
            p.put((String) kv[i], kv[i + 1]);
        }
        return p;
    }

    static QcSourceDoc doc(QcSourceDoc.Line... lines) {
        return new QcSourceDoc("production_order", "MO1", "MO-1", "manufacturing", null, "OP1", "WC1", "SUP1", "LOC",
                List.of(lines));
    }

    static QcSourceDoc.Line line(String id, String product, String qty) {
        return new QcSourceDoc.Line(id, product, null, new BigDecimal(qty), null);
    }

    @Test
    @DisplayName("quantity: A=100, B=200, C=300 at 10 % gives three checks testing 10 / 20 / 30")
    void perQuantity() {
        List<Requirement> reqs = QcCheckGenerator.requirements(point("quantity", "testQtyPct", "10"),
                doc(line("l1", "A", "100"), line("l2", "B", "200"), line("l3", "C", "300")), PRODUCTS);
        assertThat(reqs).extracting(Requirement::testQty).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"));
        assertThat(reqs).extracting(Requirement::baseKey).containsExactly("P1|production_order|MO1|-|l1",
                "P1|production_order|MO1|-|l2", "P1|production_order|MO1|-|l3");
        assertThat(reqs.getFirst().scheduleScope()).isEqualTo("A:");
    }

    @Test
    @DisplayName("quantity: the product unit's scale rounds the test quantity up; the minimum sample applies")
    void testQuantityScale() {
        List<Requirement> reqs = QcCheckGenerator.requirements(point("quantity", "testQtyPct", "3", "minSampleQty",
                "2"), doc(line("l1", "K", "12.5"), line("l2", "A", "7")), PRODUCTS);
        assertThat(reqs.get(0).testQty()).isEqualByComparingTo("2");
        assertThat(reqs.get(1).testQty()).isEqualByComparingTo("2");
        List<Requirement> big = QcCheckGenerator.requirements(point("quantity", "testQtyPct", "3"),
                doc(line("l1", "K", "1000.5")), PRODUCTS);
        assertThat(big.getFirst().testQty()).isEqualByComparingTo("30.02");
    }

    @Test
    @DisplayName("product: one check per product and variant covering its total; operation: one per document")
    void perProductAndOperation() {
        QcSourceDoc d = doc(line("l1", "A", "5"), line("l2", "A", "7"), line("l3", "B", "1"));
        List<Requirement> byProduct = QcCheckGenerator.requirements(point("product"), d, PRODUCTS);
        assertThat(byProduct).hasSize(2);
        assertThat(byProduct.getFirst().coveredQty()).isEqualByComparingTo("12");
        assertThat(byProduct.getFirst().testQty()).isNull();

        List<Requirement> op = QcCheckGenerator.requirements(point("operation"), d, PRODUCTS);
        assertThat(op).singleElement().satisfies(r -> {
            assertThat(r.baseKey()).isEqualTo("P1|production_order|MO1|-|-");
            assertThat(r.productId()).as("several products: none").isNull();
            assertThat(r.coveredQty()).isNull();
        });
        List<Requirement> single = QcCheckGenerator.requirements(point("operation"), doc(line("l1", "A", "5"),
                line("l2", "A", "3")), PRODUCTS);
        assertThat(single.getFirst().productId()).isEqualTo("A");
        assertThat(single.getFirst().coveredQty()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("matching: products, categories, supplier and routing operation")
    void matching() {
        QcSourceDoc d = doc(line("l1", "A", "5"), line("l2", "C", "5"), line("l3", "Z", "5"));
        assertThat(QcCheckGenerator.requirements(point("quantity", "testQtyPct", "10", "productIds", "[\"A\"]"), d,
                PRODUCTS)).extracting(Requirement::productId).containsExactly("A");
        assertThat(QcCheckGenerator.requirements(point("quantity", "testQtyPct", "10", "categoryIds", List.of("CAT2")),
                d, PRODUCTS)).extracting(Requirement::productId).containsExactly("C");
        assertThat(QcCheckGenerator.requirements(point("operation", "supplierPartnerId", "SUP2"), d, PRODUCTS))
                .isEmpty();
        assertThat(QcCheckGenerator.requirements(point("operation", "supplierPartnerId", "SUP1"), d, PRODUCTS))
                .hasSize(1);
        assertThat(QcCheckGenerator.requirements(point("operation", "routingOperationId", "OP2"), d, PRODUCTS))
                .isEmpty();
        assertThat(QcCheckGenerator.requirements(point("operation", "productIds", "[\"X\"]"), d, PRODUCTS)).isEmpty();
    }

    static Requirement req(String key, String covered) {
        return new Requirement("P1", key, "A:", "A", null, "l1", null, new BigDecimal(covered), BigDecimal.ONE);
    }

    static Existing existing(String id, String key, int seq, String status, String covered) {
        return new Existing(id, key, seq, status, new BigDecimal(covered));
    }

    @Test
    @DisplayName("reconcile: the §4 table")
    void reconcile() {
        Map<String, Object> p = point("quantity", "testQtyPct", "10");
        // new requirement → create
        assertThat(QcCheckGenerator.reconcile(List.of(req("k", "100")), List.of(), p, PRODUCTS))
                .singleElement().isInstanceOfSatisfying(Create.class, c -> assertThat(c.supplementary()).isFalse());
        // pending, quantity changes → recompute
        List<Action> recompute = QcCheckGenerator.reconcile(List.of(req("k", "150")),
                List.of(existing("c1", "k", 0, "pending", "100")), p, PRODUCTS);
        assertThat(recompute).singleElement().isInstanceOfSatisfying(Recompute.class, r -> {
            assertThat(r.coveredQty()).isEqualByComparingTo("150");
            assertThat(r.testQty()).isEqualByComparingTo("15");
        });
        // pending, unchanged → nothing
        assertThat(QcCheckGenerator.reconcile(List.of(req("k", "100")), List.of(existing("c1", "k", 0, "pending",
                "100")), p, PRODUCTS)).isEmpty();
        // done, quantity up → supplementary for the increase
        assertThat(QcCheckGenerator.reconcile(List.of(req("k", "130")), List.of(existing("c1", "k", 0, "passed",
                "100")), p, PRODUCTS)).singleElement().isInstanceOfSatisfying(Create.class, c -> {
                    assertThat(c.supplementary()).isTrue();
                    assertThat(c.coveredQty()).isEqualByComparingTo("30");
                    assertThat(c.testQty()).isEqualByComparingTo("3");
                });
        // done, quantity down → unchanged
        assertThat(QcCheckGenerator.reconcile(List.of(req("k", "60")), List.of(existing("c1", "k", 0, "failed",
                "100")), p, PRODUCTS)).isEmpty();
        // done + pending supplementary, quantity changes → the supplementary follows the open part
        assertThat(QcCheckGenerator.reconcile(List.of(req("k", "180")), List.of(existing("c1", "k", 0, "passed",
                "100"), existing("c2", "k", 1, "pending", "30")), p, PRODUCTS)).singleElement()
                .isInstanceOfSatisfying(Recompute.class, r -> assertThat(r.coveredQty()).isEqualByComparingTo("80"));
        // line removed: pending cancelled, done kept
        List<Action> removed = QcCheckGenerator.reconcile(List.of(), List.of(existing("c1", "k", 0, "pending", "1"),
                existing("c2", "j", 0, "waived", "1")), p, PRODUCTS);
        assertThat(removed).singleElement().isInstanceOfSatisfying(Cancel.class, c -> {
            assertThat(c.checkId()).isEqualTo("c1");
            assertThat(c.reason()).isEqualTo("source line removed");
        });
        // operation-level checks never follow quantities
        assertThat(QcCheckGenerator.reconcile(List.of(req("k", "500")), List.of(existing("c1", "k", 0, "passed",
                "100")), point("operation"), PRODUCTS)).isEmpty();
    }
}
