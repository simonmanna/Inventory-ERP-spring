package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;

/**
 * Finance readiness audit A4 (P2-9, P2-10; release gate G3): the permissions of the general-ledger controls.
 *
 * <p>The authorization matrix reads the permission of a route from its annotation, so it cannot notice a control that
 * was moved to a weaker permission. {@link #CONTROLS} states the permission of each control here: changing one changes
 * this table, in review.
 */
class GlControlPermissionsIT extends ApiTestSupport {

    /** Route to the permissions it must declare (all of them). */
    private static final Map<String, List<String>> CONTROLS = controls();

    private static Map<String, List<String>> controls() {
        Map<String, List<String>> c = new LinkedHashMap<>();
        // manual journals: maker, checker, reversal
        c.put("POST /journal-entries", List.of("journal_entry:create"));
        c.put("POST /journal-entries/{id}/post", List.of("journal_entry:post"));
        c.put("POST /journal-entries/{id}/reverse", List.of("journal_entry:reverse"));
        c.put("POST /recurring-entries/{id}/approve", List.of("journal_entry:post"));
        c.put("POST /deferrals/{id}/approve", List.of("journal_entry:post"));
        // period and year end
        c.put("POST /fiscal-periods/{id}/close", List.of("fiscal_period:update"));
        c.put("POST /fiscal-periods/{id}/lock", List.of("fiscal_period:update"));
        c.put("POST /fiscal-periods/{id}/reopen", List.of("fiscal_period:reopen"));
        c.put("POST /fiscal-years/{id}/close", List.of("fiscal_period:update"));
        c.put("POST /fiscal-years/{id}/reopen", List.of("fiscal_period:reopen"));
        // VAT settlement and its undo (A4: were journal_entry:create)
        c.put("POST /tax-settlements", List.of("tax:settle"));
        c.put("POST /tax-settlements/{id}/reverse", List.of("tax:settle"));
        // reports (A4: the rebuild needed only report:accounting; the dashboard needed no permission)
        c.put("POST /reports/accounting/rebuild-snapshots", List.of("report:accounting", "fiscal_period:update"));
        c.put("GET /reports/dashboard-kpi", List.of("report:accounting"));
        c.put("GET /reports/dashboard-activity", List.of("report:accounting"));
        // treasury, FX and allocations
        c.put("POST /treasury/transfer", List.of("treasury:transfer"));
        c.put("POST /accounts/cash-flow/deposit", List.of("treasury:transfer"));
        c.put("POST /accounts/cash-flow/withdraw", List.of("treasury:transfer"));
        c.put("POST /fx-revaluations", List.of("currency:update"));
        c.put("POST /currencies/rates/{id}/approve", List.of("currency:approve"));
        c.put("POST /cost-centers/allocation-rules/{id}/run", List.of("cost_center:allocate"));
        c.put("POST /cost-centers/allocation-runs/{id}/reverse", List.of("cost_center:allocate"));
        c.put("POST /opening-balances", List.of("opening_balance:post"));
        return c;
    }

    @Test
    @DisplayName("A4: each general-ledger control declares the permission stated here, and no other permission opens it")
    void controlsKeepTheirPermissions() {
        Map<String, RequirePermissions> declared = new LinkedHashMap<>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            RequirePermissions perms = AnnotatedElementUtils.findMergedAnnotation(entry.getValue().getMethod(),
                    RequirePermissions.class);
            if (perms == null) {
                perms = AnnotatedElementUtils.findMergedAnnotation(entry.getValue().getBeanType(),
                        RequirePermissions.class);
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                for (var m : entry.getKey().getMethodsCondition().getMethods()) {
                    declared.put(m.name() + " " + pattern.replace("/api/v1", ""), perms);
                }
            }
        }
        List<String> failures = new ArrayList<>();
        for (var control : CONTROLS.entrySet()) {
            String route = control.getKey();
            if (!declared.containsKey(route)) {
                failures.add(route + ": no such route");
                continue;
            }
            RequirePermissions perms = declared.get(route);
            if (perms == null || perms.anyOf() || !List.of(perms.value()).equals(control.getValue())) {
                failures.add(route + ": declares " + (perms == null ? "nothing"
                        : Arrays.toString(perms.value()) + (perms.anyOf() ? " (any of)" : "")) + ", expected "
                        + control.getValue());
                continue;
            }
            // a user holding every permission of the catalogue but the control's own: refused at the guard
            String[] verbAndPath = route.split(" ");
            String path = verbAndPath[1].replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            Res denied = call(verbAndPath[0], path, "GET".equals(verbAndPath[0]) ? null : Map.of(),
                    tokenHolding(everyPermissionBut(control.getValue())));
            String expected = "Missing required permission(s): " + String.join(", ", control.getValue());
            if (denied.status() != 403 || !expected.equals(denied.body().get("message").asString())) {
                failures.add(route + ": every other permission → " + denied.status() + " " + denied.raw().body());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("A4 (P2-10): the maker of journal entries cannot settle VAT or undo a settlement; tax:settle can")
    void vatSettlementNeedsItsOwnPermission() {
        String maker = tokenHolding("journal_entry:read", "journal_entry:create", "journal_entry:post",
                "journal_entry:reverse", "report:accounting", "tax:read", "tax:update");
        Map<String, Object> period = Map.of("from", "2019-01-01", "to", "2019-01-31");
        Res settle = call("POST", "/tax-settlements", period, maker);
        assertThat(settle.status()).as(settle.raw().body()).isEqualTo(403);
        assertThat(settle.body().get("message").asString()).isEqualTo("Missing required permission(s): tax:settle");
        Res undo = call("POST", "/tax-settlements/" + UUID.randomUUID() + "/reverse", Map.of(), maker);
        assertThat(undo.status()).as(undo.raw().body()).isEqualTo(403);
        assertThat(undo.body().get("message").asString()).isEqualTo("Missing required permission(s): tax:settle");
        // the maker still reads the settlements and the preview
        assertThat(call("GET", "/tax-settlements", null, maker).status()).isEqualTo(200);

        // tax:settle alone passes the guard: the request reaches the service (unknown settlement, invalid period)
        String settler = tokenHolding("tax:settle");
        Res unknown = call("POST", "/tax-settlements/" + UUID.randomUUID() + "/reverse", Map.of(), settler);
        assertThat(unknown.status()).as(unknown.raw().body()).isEqualTo(404);
        assertThat(unknown.body().get("message").asString()).isEqualTo("VAT settlement not found");
        assertThat(call("POST", "/tax-settlements", Map.of(), settler).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("A4 (P2-9): the dashboard figures need report:accounting; a cashier gets 403, not the cash position")
    void dashboardNeedsReportAccounting() {
        String cashier = tokenHolding("pos:read", "pos:checkout", "cash_session:open", "cash_session:read",
                "product:read", "partner:read", "invoice:read", "payment:read");
        for (String path : List.of("/reports/dashboard-kpi", "/reports/dashboard-activity?limit=3")) {
            Res denied = call("GET", path, null, cashier);
            assertThat(denied.status()).as(path + " " + denied.raw().body()).isEqualTo(403);
            assertThat(denied.body().get("message").asString())
                    .isEqualTo("Missing required permission(s): report:accounting");
        }
        String accountant = tokenHolding("report:accounting");
        Res kpi = call("GET", "/reports/dashboard-kpi", null, accountant);
        assertThat(kpi.status()).as(kpi.raw().body()).isEqualTo(200);
        assertThat(kpi.body().has("cashPosition")).isTrue();
        Res activity = call("GET", "/reports/dashboard-activity?limit=3", null, accountant);
        assertThat(activity.status()).as(activity.raw().body()).isEqualTo(200);
        assertThat(activity.body().get("data").isArray()).isTrue();
    }

    @Test
    @DisplayName("A4: rebuilding the report snapshots needs fiscal_period:update on top of report:accounting")
    void snapshotRebuildNeedsThePeriodEndPermission() {
        String expected = "Missing required permission(s): report:accounting, fiscal_period:update";
        for (String token : List.of(tokenHolding("report:accounting"), tokenHolding("fiscal_period:update"))) {
            Res denied = call("POST", "/reports/accounting/rebuild-snapshots", null, token);
            assertThat(denied.status()).as(denied.raw().body()).isEqualTo(403);
            assertThat(denied.body().get("message").asString()).isEqualTo(expected);
        }
        // the reader of the reports still reads them
        assertThat(call("GET", "/reports/accounting/trial-balance", null, tokenHolding("report:accounting")).status())
                .isEqualTo(200);
        Res rebuilt = call("POST", "/reports/accounting/rebuild-snapshots", null,
                tokenHolding("report:accounting", "fiscal_period:update"));
        assertThat(rebuilt.status()).as(rebuilt.raw().body()).isEqualTo(201);
        assertThat(rebuilt.body().has("rebuiltAt")).isTrue();
    }

    @Test
    @DisplayName("A4: V652 gives tax:settle to Administrator and to roles that post journals, once, and to no other role")
    void migrationGrantsTaxSettleToRolesThatPostJournals() throws IOException {
        String poster = role("A4-poster", false, "journal_entry:create", "journal_entry:post");
        String maker = role("A4-maker", false, "journal_entry:create", "report:accounting");
        String deleted = role("A4-deleted", true, "journal_entry:post");
        String none = role("A4-none", false);
        // an Administrator role created before the permission existed
        String admin = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(otherOrg).query(String.class).single();
        jdbc.sql("UPDATE \"Role\" SET permissions = array_remove(permissions, 'tax:settle') WHERE id = ?").param(admin)
                .update();
        assertThat(permissions(admin)).doesNotContain("tax:settle");
        try {
            runV652();
            runV652(); // again: nothing is granted twice

            assertThat(permissions(poster)).containsExactly("journal_entry:create", "journal_entry:post", "tax:settle");
            assertThat(permissions(maker)).containsExactly("journal_entry:create", "report:accounting");
            assertThat(permissions(deleted)).containsExactly("journal_entry:post");
            assertThat(permissions(none)).isEmpty();
            assertThat(permissions(admin)).containsOnlyOnce("tax:settle");
            assertThat(jdbc.sql("SELECT count(*) FROM \"Permission\" WHERE key = 'tax:settle'").query(Long.class)
                    .single()).isEqualTo(1L);
        } finally {
            jdbc.sql("UPDATE \"Role\" SET permissions = permissions || ARRAY['tax:settle'] WHERE id = ?"
                    + " AND NOT (permissions @> ARRAY['tax:settle'])").param(admin).update();
            permissionService.evictAll();
        }
    }

    private String role(String name, boolean deleted, String... permissions) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\","
                        + " \"deletedAt\") VALUES (?, ?, ?, false, ?, now(), CASE WHEN ? THEN now() END)")
                .params(id, demoOrg, name + "-" + id, permissions, deleted).update();
        return id;
    }

    private List<String> permissions(String roleId) {
        return jdbc.sql("SELECT unnest(permissions) FROM \"Role\" WHERE id = ?").param(roleId).query(String.class)
                .list();
    }

    /** The statements of the migration, as Flyway runs them (they are written to be repeatable). */
    private void runV652() throws IOException {
        String script = new ClassPathResource("db/migration/V652__tax_settle_permission.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        StringBuilder sql = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.stripLeading().startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        for (String statement : sql.toString().split(";")) {
            if (!statement.isBlank()) {
                jdbc.sql(statement).update();
            }
        }
    }
}
