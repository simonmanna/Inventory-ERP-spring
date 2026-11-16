package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.StockMove;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Audit S-2: two documents over the same items feed the engine in one order whatever order they were entered in. */
class StockLockOrderTest {

    static Map<String, Object> line(String product, String variant, String id) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", product);
        m.put("variantId", variant);
        m.put("id", id);
        return m;
    }

    @Test
    void oppositeEntryOrdersLockInTheSameSequence() {
        List<Map<String, Object>> a = List.of(line("p2", null, "a1"), line("p1", "v2", "a2"), line("p1", null, "a3"),
                line("p1", "v1", "a4"));
        List<Map<String, Object>> b = List.of(line("p1", "v1", "b1"), line("p1", null, "b2"), line("p1", "v2", "b3"),
                line("p2", null, "b4"));
        List<String> orderA = StockMove.inLockOrder(a).stream().map(l -> l.get("productId") + "/" + l.get("variantId"))
                .toList();
        List<String> orderB = StockMove.inLockOrder(b).stream().map(l -> l.get("productId") + "/" + l.get("variantId"))
                .toList();
        assertThat(orderA).containsExactly("p1/null", "p1/v1", "p1/v2", "p2/null").isEqualTo(orderB);
        assertThat(a.get(0).get("id")).as("the document's own list is untouched").isEqualTo("a1");
    }
}
