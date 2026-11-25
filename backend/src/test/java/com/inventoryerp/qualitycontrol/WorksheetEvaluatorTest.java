package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.qualitycontrol.application.WorksheetEvaluator;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S087 §10: the backend evaluator is authoritative for worksheet definitions and answers. */
class WorksheetEvaluatorTest {

    static final JsonNode FIELDS = EntityJson.tree("""
            [
              {"key": "operator", "label": "Operator", "type": "text", "required": true, "maxLength": 20},
              {"key": "length_mm", "label": "Length", "type": "number", "required": true, "min": 390, "max": 410, "decimals": 1},
              {"key": "surface", "label": "Surface", "type": "select", "options": ["smooth", "rough", "cracked"],
               "passValues": ["smooth", "rough"], "required": true},
              {"key": "cracks", "label": "Crack depth", "type": "number", "showIf": {"field": "surface", "equals": "cracked"},
               "required": true, "max": 2},
              {"key": "labelled", "label": "Labelled", "type": "checkbox", "expected": true},
              {"key": "tested_on", "label": "Tested on", "type": "date"}
            ]""");

    private static Map<String, Object> answers(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("a complete passing worksheet")
    void passes() {
        var r = WorksheetEvaluator.evaluate(FIELDS, answers("operator", "Ann", "length_mm", 400.5, "surface", "smooth",
                "labelled", true, "tested_on", "2026-11-24"));
        assertThat(r.passed()).isTrue();
        assertThat(r.answers()).containsKeys("operator", "length_mm", "surface", "labelled", "tested_on");
        assertThat(r.fields()).filteredOn(f -> f.key().equals("cracks")).singleElement()
                .satisfies(f -> assertThat(f.visible()).isFalse());
    }

    @Test
    @DisplayName("rule violations fail with per-field reasons")
    void fails() {
        var r = WorksheetEvaluator.evaluate(FIELDS, answers("operator", "Ann", "length_mm", 411, "surface", "rough",
                "labelled", false));
        assertThat(r.passed()).isFalse();
        assertThat(r.fields()).filteredOn(f -> Boolean.FALSE.equals(f.passed())).extracting(f -> f.key())
                .containsExactlyInAnyOrder("length_mm", "labelled");
    }

    @Test
    @DisplayName("showIf: a visible conditional field becomes required and is evaluated")
    void conditional() {
        assertThatThrownBy(() -> WorksheetEvaluator.evaluate(FIELDS, answers("operator", "Ann", "length_mm", 400,
                "surface", "cracked"))).isInstanceOf(ApiException.class)
                .hasMessage("Worksheet field Crack depth is required.");
        var r = WorksheetEvaluator.evaluate(FIELDS, answers("operator", "Ann", "length_mm", 400, "surface", "cracked",
                "cracks", 1.5));
        assertThat(r.passed()).isFalse(); // 'cracked' is not a pass value
        var hidden = WorksheetEvaluator.evaluate(FIELDS, answers("operator", "Ann", "length_mm", 400, "surface",
                "smooth", "cracks", 99));
        assertThat(hidden.passed()).isTrue();
        assertThat(hidden.answers()).doesNotContainKey("cracks");
    }

    @Test
    @DisplayName("structural problems are refused (400)")
    void refused() {
        Map<String, Object> base = answers("operator", "Ann", "length_mm", 400, "surface", "smooth");
        Map<String, Object> unknown = new HashMap<>(base);
        unknown.put("colour", "red");
        assertThatThrownBy(() -> WorksheetEvaluator.evaluate(FIELDS, unknown)).hasMessage("Unknown worksheet field colour.");
        Map<String, Object> decimals = new HashMap<>(base);
        decimals.put("length_mm", 400.25);
        assertThatThrownBy(() -> WorksheetEvaluator.evaluate(FIELDS, decimals))
                .hasMessage("Worksheet field Length has more than 1 decimals.");
        Map<String, Object> option = new HashMap<>(base);
        option.put("surface", "wavy");
        assertThatThrownBy(() -> WorksheetEvaluator.evaluate(FIELDS, option))
                .hasMessage("Worksheet field Surface: wavy is not an option.");
        Map<String, Object> type = new HashMap<>(base);
        type.put("labelled", "yes");
        assertThatThrownBy(() -> WorksheetEvaluator.evaluate(FIELDS, type))
                .hasMessage("Worksheet field Labelled must be true or false.");
        Map<String, Object> text = new HashMap<>(base);
        text.put("operator", "x".repeat(21));
        assertThatThrownBy(() -> WorksheetEvaluator.evaluate(FIELDS, text))
                .hasMessage("Worksheet field Operator is longer than 20 characters.");
        Map<String, Object> missing = new HashMap<>(base);
        missing.remove("length_mm");
        assertThatThrownBy(() -> WorksheetEvaluator.evaluate(FIELDS, missing))
                .hasMessage("Worksheet field Length is required.");
    }

    @Test
    @DisplayName("template definitions are validated on save")
    void definitions() {
        WorksheetEvaluator.validateDefinition(FIELDS);
        assertThatThrownBy(() -> WorksheetEvaluator.validateDefinition(EntityJson.tree("[]")))
                .hasMessage("A worksheet template needs at least one field.");
        assertThatThrownBy(() -> WorksheetEvaluator.validateDefinition(EntityJson.tree(
                "[{\"key\":\"a\",\"label\":\"A\",\"type\":\"text\"},{\"key\":\"a\",\"label\":\"B\",\"type\":\"text\"}]")))
                .hasMessage("Worksheet field key a is used twice.");
        assertThatThrownBy(() -> WorksheetEvaluator.validateDefinition(EntityJson.tree(
                "[{\"key\":\"s\",\"label\":\"S\",\"type\":\"select\",\"options\":[\"x\"],\"passValues\":[\"y\"]}]")))
                .hasMessage("Worksheet field s: pass value y is not an option.");
        assertThatThrownBy(() -> WorksheetEvaluator.validateDefinition(EntityJson.tree(
                "[{\"key\":\"a\",\"label\":\"A\",\"type\":\"text\",\"showIf\":{\"field\":\"b\",\"equals\":1}}]")))
                .hasMessage("Worksheet field a: showIf must refer to an earlier field.");
        assertThatThrownBy(() -> WorksheetEvaluator.validateDefinition(EntityJson.tree(
                "[{\"key\":\"Bad Key\",\"label\":\"A\",\"type\":\"text\"}]"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> WorksheetEvaluator.validateDefinition(EntityJson.tree(
                "[{\"key\":\"a\",\"label\":\"A\",\"type\":\"rating\"}]")))
                .hasMessage("Worksheet field a has an unknown type rating.");
    }
}
