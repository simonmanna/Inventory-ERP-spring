package com.inventoryerp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PR-1 part 2: every GET route of a module, called as the DEMO admin (lists and reports with their defaults, detail
 * routes with an unknown id), answers without a server error. Catches 500s on screens the browser click-through
 * reaches and runs the read paths of each module's application services.
 */
class GetRouteSweepIT extends ApiTestSupport {

    /** 500s that Nest gives too and that Spring keeps on purpose (route pattern → where it is documented). */
    private static final Map<String, String> NEST_500 = Map.of(
            "/api/v1/pos/menu/accompaniments/report/sales", "Nest bug kept: unknown Prisma field",
            "/api/v1/reports/accounting/balance-sheet", "no asOf = invalid date, accounting Deviation 3",
            "/api/v1/reports/accounting/balance-sheet/detailed", "no asOf = invalid date, accounting Deviation 3",
            "/api/v1/currencies/rate", "missing codes, accounting Deviation 3 / parity D3",
            "/api/v1/rental/scores/{partnerId}", "always fails in Nest, docs/spec/rental.md");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pos", "hr", "backup", "sync", "analytics", "quarry", "crm", "expenses", "inventory",
        "accounting", "invoicing", "procurement", "sales", "core", "kernel", "documents", "fleet", "manufacturing",
        "rental", "repair", "communication", "task", "beverage", "fixedasset", "orders", "quality", "alerts", "credit",
        "portal", "biexport", "intercompany", "customization"})
    void noServerErrors(String module) {
        Set<String> expected = NEST_500.keySet();
        assertThat(sweepGetRoutes("com.inventoryerp." + module + ".").stream()
                .filter(f -> expected.stream().noneMatch(p -> f.startsWith("GET " + p + " → "))).toList()).isEmpty();
    }
}
