package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.InventoryFeatureUsageService;
import com.inventoryerp.inventory.application.InventoryFeatureUsageService.P2Rule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IPR R6: the P2 table of {@code docs/spec/inventory-feature-risk-map.md} and {@code P2_FEATURES} stay identical — the
 * same P2s, each fixed P2 with Status {@code fixed} and its evidence file, every evidence file present. Plain JUnit (no
 * Spring), so ticking a P2 is a two-line change checked in seconds.
 */
class InventoryFeatureRiskMapDocTest {

    private static final Path DOC = Path.of("..", "docs", "spec", "inventory-feature-risk-map.md");

    /** P2 id → {status, evidence} from the doc's "P2 → feature predicate" table. */
    static Map<String, String[]> docRows() throws Exception {
        List<String> lines = Files.readAllLines(DOC);
        Map<String, String[]> rows = new LinkedHashMap<>();
        boolean inTable = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inTable = line.startsWith("## P2 → feature predicate");
                continue;
            }
            if (!inTable || !line.startsWith("| ") || line.startsWith("| P2 ") || line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
            String id = cells[0].trim();
            String status = cells.length > 3 ? cells[3].trim() : "";
            String evidence = cells.length > 4 ? cells[4].trim().replace("`", "") : "";
            rows.put(id, new String[] {status, evidence});
        }
        return rows;
    }

    @Test
    @DisplayName("R6: the feature-risk map doc lists the same P2s as the code, with the same fixed status and evidence")
    void docMatchesCode() throws Exception {
        assertThat(Files.exists(DOC)).as(DOC.toAbsolutePath().toString()).isTrue();
        Map<String, String[]> doc = docRows();
        assertThat(doc.keySet()).containsExactlyElementsOf(
                InventoryFeatureUsageService.P2_FEATURES.stream().map(P2Rule::id).toList());
        for (P2Rule rule : InventoryFeatureUsageService.P2_FEATURES) {
            String[] row = doc.get(rule.id());
            if (rule.open()) {
                assertThat(row[0]).as(rule.id() + " status").isEqualTo("open");
            } else {
                assertThat(row[0]).as(rule.id() + " status").isEqualTo("fixed");
                assertThat(row[1]).as(rule.id() + " evidence").isEqualTo(rule.fixedBy());
                assertThat(Files.exists(Path.of("..").resolve(rule.fixedBy()))).as(rule.fixedBy()).isTrue();
            }
        }
    }
}
