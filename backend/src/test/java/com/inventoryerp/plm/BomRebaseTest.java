package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.plm.application.BomRebase;
import com.inventoryerp.plm.application.BomRebase.MergedLine;
import com.inventoryerp.plm.application.BomRebase.Result;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The three-way BOM merge behind ECO rebase (ADR-S086 §3). */
class BomRebaseTest {

    static Map<String, Object> line(String component, String qty) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("componentProductId", component);
        l.put("componentVariantId", null);
        l.put("quantity", new BigDecimal(qty));
        l.put("uomId", null);
        l.put("scrapPct", BigDecimal.ZERO);
        l.put("isOptional", false);
        l.put("notes", null);
        return l;
    }

    static Map<String, Object> header(String name, String outputQty) {
        Map<String, Object> h = new HashMap<>();
        h.put("name", name);
        h.put("outputQuantity", new BigDecimal(outputQty));
        h.put("expectedYieldPct", new BigDecimal("100"));
        h.put("qcRequired", false);
        return h;
    }

    static BigDecimal qty(Result r, String component) {
        return r.lines().stream().filter(l -> component.equals(l.line().get("componentProductId"))).findFirst()
                .map(l -> (BigDecimal) l.line().get("quantity")).orElse(null);
    }

    static List<String> components(Result r) {
        return r.lines().stream().map(l -> (String) l.line().get("componentProductId")).toList();
    }

    @Test
    @DisplayName("an upstream change is carried in; our own change is kept; both apply together")
    void disjointChanges() {
        Result r = BomRebase.merge(header("Bread", "10"), header("Bread", "12"), header("Bread v2", "10"),
                List.of(line("flour", "5"), line("salt", "1")),
                List.of(line("flour", "6"), line("salt", "1")),
                List.of(line("flour", "5"), line("salt", "2")));
        assertThat(qty(r, "flour")).isEqualByComparingTo("6");
        assertThat(qty(r, "salt")).isEqualByComparingTo("2");
        assertThat(r.header().get("outputQuantity")).isEqualTo(new BigDecimal("12"));
        assertThat(r.header().get("name")).isEqualTo("Bread v2");
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.fromTheirs()).contains("outputQuantity", "flour|#1");
        MergedLine flour = r.lines().stream().filter(l -> l.key().startsWith("flour")).findFirst().orElseThrow();
        assertThat(flour.side()).isEqualTo("theirs");
    }

    @Test
    @DisplayName("upstream additions and removals are carried in")
    void addAndRemoveUpstream() {
        Result r = BomRebase.merge(header("B", "1"), header("B", "1"), header("B", "1"),
                List.of(line("flour", "5"), line("salt", "1")),
                List.of(line("flour", "5"), line("yeast", "0.1")),
                List.of(line("flour", "5"), line("salt", "1"), line("sugar", "1")));
        assertThat(components(r)).containsExactly("flour", "sugar", "yeast");
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("the same change on both sides is no conflict")
    void sameChange() {
        Result r = BomRebase.merge(header("B", "1"), header("B", "1"), header("B", "1"),
                List.of(line("flour", "5")), List.of(line("flour", "5.000000")), List.of(line("flour", "5.0")));
        assertThat(r.conflicts()).isEmpty();
        Result both = BomRebase.merge(header("B", "1"), header("B", "1"), header("B", "1"),
                List.of(line("flour", "5")), List.of(line("flour", "7")), List.of(line("flour", "7")));
        assertThat(both.conflicts()).isEmpty();
        assertThat(qty(both, "flour")).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("different changes on both sides keep ours and report a conflict")
    void conflicts() {
        Result r = BomRebase.merge(header("B", "1"), header("B upstream", "1"), header("B ours", "1"),
                List.of(line("flour", "5"), line("salt", "1")),
                List.of(line("flour", "6"), line("salt", "3")),
                List.of(line("flour", "7")));
        assertThat(qty(r, "flour")).isEqualByComparingTo("7");
        // We removed salt, they changed it: our removal stands, reported.
        assertThat(components(r)).containsExactly("flour");
        assertThat(r.conflicts()).extracting(BomRebase.Conflict::key).containsExactly("name", "flour|#1", "salt|#1");
        BomRebase.Conflict salt = r.conflicts().get(2);
        assertThat(salt.kind()).isEqualTo("line");
        assertThat(salt.componentProductId()).isEqualTo("salt");
        assertThat(salt.ours()).isNull();
        assertThat(r.header().get("name")).isEqualTo("B ours");
    }

    @Test
    @DisplayName("a component listed twice is matched occurrence by occurrence")
    void repeatedComponent() {
        Result r = BomRebase.merge(header("B", "1"), header("B", "1"), header("B", "1"),
                List.of(line("water", "1"), line("water", "2")),
                List.of(line("water", "1"), line("water", "3")),
                List.of(line("water", "4"), line("water", "2")));
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.lines()).extracting(l -> ((BigDecimal) l.line().get("quantity")).stripTrailingZeros()
                .toPlainString()).containsExactly("4", "3");
    }

    @Test
    @DisplayName("blank strings equal null; numbers compare by value")
    void values() {
        assertThat(BomRebase.sameValue("", null)).isTrue();
        assertThat(BomRebase.sameValue(2, new BigDecimal("2.000"))).isTrue();
        assertThat(BomRebase.sameValue("a", "b")).isFalse();
        assertThat(BomRebase.lineChanges(line("x", "1"), line("x", "2"))).hasSize(1);
    }
}
