package com.inventoryerp.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.AbcClassifier.Classified;
import com.inventoryerp.inventory.application.AbcClassifier.Item;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbcClassifierTest {

    private static final BigDecimal A = BigDecimal.valueOf(80);
    private static final BigDecimal B = BigDecimal.valueOf(95);

    private static Item item(String id, long value) {
        return new Item(id, BigDecimal.valueOf(value));
    }

    @Test
    void classifiesByCumulativeShareBeforeTheItem() {
        List<Classified> out = AbcClassifier.classify(List.of(item("c", 3), item("a", 70), item("b1", 15),
                item("b2", 7), item("z", 0), item("c2", 5)), A, B);
        assertThat(out).extracting(Classified::id).containsExactly("a", "b1", "b2", "c2", "c", "z");
        assertThat(out).extracting(Classified::abcClass).containsExactly('A', 'A', 'B', 'B', 'C', 'C');
        assertThat(out.get(0).cumulativeShare()).isEqualByComparingTo("70");
        assertThat(out.get(5).cumulativeShare()).isEqualByComparingTo("100");
    }

    @Test
    void firstItemIsAlwaysAAndZeroTotalIsAllC() {
        assertThat(AbcClassifier.classify(List.of(item("x", 100)), A, B).getFirst().abcClass()).isEqualTo('A');
        assertThat(AbcClassifier.classify(List.of(item("x", 0), item("y", 0)), A, B))
                .extracting(Classified::abcClass).containsOnly('C');
        assertThat(AbcClassifier.classify(List.of(), A, B)).isEmpty();
    }

    @Test
    void nullAndNegativeValuesCountAsZero() {
        List<Classified> out = AbcClassifier.classify(List.of(new Item("n", null), item("neg", -5), item("p", 10)), A,
                B);
        assertThat(out.getFirst().id()).isEqualTo("p");
        assertThat(out.getFirst().abcClass()).isEqualTo('A');
        assertThat(out.subList(1, 3)).extracting(Classified::abcClass).containsOnly('C');
    }
}
