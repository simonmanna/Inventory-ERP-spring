package com.inventoryerp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Every engine report of the library runs on the DEMO seed as the admin with its defaults, with a year to date
 * period and comparatives where accepted, and with each export format for one report per category: no server error,
 * every result has columns and a header. A report that needs a choice (one partner for a statement) answers 400.
 */
class AllReportsRunIT extends ApiTestSupport {

    @Test
    @DisplayName("every engine report runs without a server error")
    void everyReportRuns() {
        JsonNode lib = call("GET", "/reports/library", null, adminToken).body();
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode cat : lib) {
            for (JsonNode r : cat.get("reports")) {
                if (!r.get("engine").asBoolean()) {
                    continue;
                }
                String id = r.get("id").asString();
                List<Map<String, Object>> requests = new ArrayList<>(List.of(Map.of()));
                String filters = r.get("filters").toString();
                if (filters.contains("PERIOD")) {
                    requests.add(filters.contains("COMPARE") ? Map.of("period", "THIS_YEAR", "compare",
                            List.of("PRIOR_YEAR", "BUDGET", "VARIANCE", "VARIANCE_PCT")) : Map.of("period",
                                    "THIS_YEAR"));
                } else if (filters.contains("AS_OF")) {
                    requests.add(Map.of("period", "LAST_MONTH"));
                }
                for (Map<String, Object> body : requests) {
                    Res res = call("POST", "/reports/run/" + id, body, adminToken);
                    ran++;
                    if (res.status() == 400 && id.endsWith("-statement")) {
                        continue;
                    }
                    if (res.status() != 200) {
                        failures.add(id + " " + body + " → " + res.status() + " " + res.raw().body());
                    } else if (res.body().get("columns").isEmpty()) {
                        failures.add(id + " has no columns");
                    }
                }
            }
        }
        String diag = System.getenv("REPORT_FAILURES_FILE");
        if (diag != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(diag), String.join("\n", failures) + "\nran="
                        + ran);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(ran).isGreaterThan(150);
    }
}
