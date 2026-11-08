package com.inventoryerp.kernel.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADR-S061 condition trees of the approval rules builder, without a Spring context. */
class ApprovalConditionsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode tree(String json) {
        return MAPPER.readTree(json.replace('\'', '"'));
    }

    private static boolean holds(String json, Map<String, Object> snapshot) {
        return ApprovalConditions.matches(tree(json), f -> ApprovalConditions.path(snapshot, f));
    }

    @Test
    @DisplayName("an absent or empty tree always matches")
    void empty() {
        assertThat(ApprovalConditions.matches(null, f -> null)).isTrue();
        assertThat(holds("{'logic':'and','rules':[]}", Map.of())).isTrue();
    }

    @Test
    @DisplayName("numbers compare as decimals, also when the snapshot holds them as text")
    void numbers() {
        String gt = "{'logic':'and','rules':[{'field':'amount','operator':'gt','value':1000}]}";
        assertThat(holds(gt, Map.of("amount", 1000.01))).isTrue();
        assertThat(holds(gt, Map.of("amount", 1000))).isFalse();
        assertThat(holds(gt, Map.of("amount", "2500.50"))).isTrue();
        assertThat(holds(gt, Map.of())).isFalse();
        assertThat(holds("{'rules':[{'field':'amount','operator':'between','value':[10,20]}]}", Map.of("amount", 20)))
                .isTrue();
        assertThat(holds("{'rules':[{'field':'amount','operator':'eq','value':'5.0'}]}", Map.of("amount", 5))).isTrue();
    }

    @Test
    @DisplayName("a number never orders against text")
    void mixed() {
        assertThat(holds("{'rules':[{'field':'code','operator':'gt','value':10}]}", Map.of("code", "abc"))).isFalse();
    }

    @Test
    @DisplayName("text compares case-insensitively; in / not_in / contains / starts_with")
    void text() {
        Map<String, Object> s = Map.of("category", "Capex", "tags", List.of("it", "urgent"), "ref", "PO-10284");
        assertThat(holds("{'rules':[{'field':'category','operator':'eq','value':'CAPEX'}]}", s)).isTrue();
        assertThat(holds("{'rules':[{'field':'category','operator':'in','value':['opex','capex']}]}", s)).isTrue();
        assertThat(holds("{'rules':[{'field':'category','operator':'not_in','value':['opex']}]}", s)).isTrue();
        assertThat(holds("{'rules':[{'field':'tags','operator':'contains','value':'urgent'}]}", s)).isTrue();
        assertThat(holds("{'rules':[{'field':'ref','operator':'starts_with','value':'po-'}]}", s)).isTrue();
        assertThat(holds("{'rules':[{'field':'ref','operator':'not_contains','value':'999'}]}", s)).isTrue();
    }

    @Test
    @DisplayName("a missing field fails positive tests and passes neq, not_in and is_empty")
    void missing() {
        assertThat(holds("{'rules':[{'field':'x','operator':'eq','value':'a'}]}", Map.of())).isFalse();
        assertThat(holds("{'rules':[{'field':'x','operator':'neq','value':'a'}]}", Map.of())).isTrue();
        assertThat(holds("{'rules':[{'field':'x','operator':'not_in','value':['a']}]}", Map.of())).isTrue();
        assertThat(holds("{'rules':[{'field':'x','operator':'is_empty'}]}", Map.of())).isTrue();
        assertThat(holds("{'rules':[{'field':'x','operator':'is_not_empty'}]}", Map.of("x", " "))).isFalse();
    }

    @Test
    @DisplayName("AND / OR groups nest, and dot paths reach into nested objects")
    void groups() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("amount", 5000);
        s.put("vendor", Map.of("country", "KE"));
        String json = "{'logic':'and','rules':[{'field':'amount','operator':'gte','value':1000},"
                + "{'logic':'or','rules':[{'field':'vendor.country','operator':'eq','value':'UG'},"
                + "{'field':'vendor.country','operator':'eq','value':'KE'}]}]}";
        assertThat(holds(json, s)).isTrue();
        s.put("vendor", Map.of("country", "TZ"));
        assertThat(holds(json, s)).isFalse();
    }

    @Test
    @DisplayName("validation lists every problem in one 400")
    void validation() {
        assertThatThrownBy(() -> ApprovalConditions.validate(tree("{'logic':'xor','rules':[{'field':'','operator':'gt'},"
                + "{'field':'a','operator':'between','value':[1]},{'field':'b','operator':'in','value':'x'}]}"),
                "conditions"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("conditions.logic must be one of the following values: and, or")
                .hasMessageContaining("conditions.rules.0.field must not be empty")
                .hasMessageContaining("conditions.rules.0.value is required for gt")
                .hasMessageContaining("conditions.rules.1.value must be [from, to] for between")
                .hasMessageContaining("conditions.rules.2.value must be an array for in");
        ApprovalConditions.validate(tree("{'logic':'or','rules':[{'field':'a','operator':'is_empty'}]}"), "c");
    }

    @Test
    @DisplayName("hub details: labelled scalars, ids and internal keys hidden")
    void details() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalAmount", 28500);
        s.put("supplierId", "x");
        s.put("makerIds", "a,b");
        s.put("items", List.of(Map.of("q", 1), Map.of("q", 2)));
        s.put("budget_code", "CAPEX-IT-2024");
        List<Map<String, Object>> rows = ApprovalHubService.details(s);
        assertThat(rows).extracting(r -> r.get("label")).containsExactly("Total Amount", "Items", "Budget Code");
        assertThat(rows.get(1).get("value")).isEqualTo("2 items");
    }
}
