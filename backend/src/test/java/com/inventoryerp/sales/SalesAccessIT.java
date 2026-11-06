package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SG-1: cost and margin need sales:cost:read; a branch-scoped user sees only their branches. */
class SalesAccessIT extends ApiTestSupport {

    static final String[] CLERK = {"sales:order:read", "sales:order:read_all", "sales:order:write",
        "sales:order:confirm", "sales:quotation:read", "sales:quotation:write", "sales:pricing:read",
        "sales:report:read", "sales:collections:read"};

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String branchA;
    String branchB;
    String product;
    String customerA;
    String customerB;

    String customer(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, name, name).update();
        return id;
    }

    record Clerk(String id, String token) {}

    Clerk clerk(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "SG1 " + role, permissions).update();
        String email = "sg1-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String id = createUser(demoOrg, email, "Scope@12345", role);
        return new Clerk(id, login("DEMO", email, "Scope@12345"));
    }

    Clerk branchClerk(String branch, String... permissions) {
        Clerk c = clerk(permissions);
        jdbc.sql("INSERT INTO \"UserDataScope\" (id, \"organizationId\", \"userId\", \"branchId\") VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, c.id(), branch).update();
        return c;
    }

    String order(String customer, String branch) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                product, "quantity", 2))));
        if (branch != null) {
            b.put("branchId", branch);
        }
        return ok(201, "POST", "/sales/orders", b).get("id").asString();
    }

    @BeforeAll
    void fixtures() {
        branchA = ok(201, "POST", "/branches", Map.of("code", "G1A-" + sfx, "name", "SG1 A")).get("id").asString();
        branchB = ok(201, "POST", "/branches", Map.of("code", "G1B-" + sfx, "name", "SG1 B")).get("id").asString();
        product = ok(201, "POST", "/products", Map.of("code", "G1P-" + sfx, "name", "SG1 item " + sfx, "productType",
                "service", "salesPrice", 100, "costPrice", 40)).get("id").asString();
        customerA = customer("ZZ-G1A-" + sfx);
        customerB = customer("ZZ-G1B-" + sfx);
    }

    String rawGet(String path, String token) {
        try {
            return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Authorization", "Bearer " + token).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("without sales:cost:read the raw order, resolve and report JSON hold no cost or margin")
    void costStripped() {
        Clerk c = clerk(CLERK);
        String id = order(customerA, branchA);
        String order = call("GET", "/sales/orders/" + id, null, c.token()).raw().body();
        assertThat(order).contains("unitPrice").doesNotContain("costSnapshot");
        assertThat(ok(200, "GET", "/sales/orders/" + id, null).get("lines").get(0).has("costSnapshot")).isTrue();

        Res resolved = call("POST", "/sales/pricing/resolve", Map.of("partnerId", customerA, "lines", List.of(Map.of(
                "productId", product, "quantity", 1))), c.token());
        assertThat(resolved.status()).isLessThan(300);
        assertThat(resolved.raw().body()).doesNotContain("\"cost\"").doesNotContain("marginPercent")
                .doesNotContain("floorPrice");

        String period = "from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now();
        Res lines = call("GET", "/sales/report-center/run/invoice-lines?" + period, null, c.token());
        assertThat(lines.status()).isEqualTo(200);
        assertThat(lines.raw().body().toLowerCase()).doesNotContain("\"cost").doesNotContain("\"margin");
        Res dash = call("GET", "/sales/report-center/dashboard?" + period, null, c.token());
        assertThat(dash.raw().body().toLowerCase()).doesNotContain("\"margin");
        assertThat(call("GET", "/sales/report-center/run/gross-profit?" + period, null, c.token()).status()).isEqualTo(403);
        assertThat(call("GET", "/sales/analytics/profitability", null, c.token()).status()).isEqualTo(403);
        assertThat(rawGet("/sales/report-center/export/invoice-lines?format=csv&" + period, c.token())).contains("Net")
                .doesNotContain("Margin");
        assertThat(ok(200, "GET", "/sales/report-center/run/gross-profit?" + period, null).get("columns").toString())
                .as("admin keeps it").containsIgnoringCase("margin");
    }

    @Test
    @DisplayName("a branch A user gets nothing from branch B: lists, direct reads, every report")
    void branchIsolation() {
        String inA = order(customerA, branchA);
        String inB = order(customerB, branchB);
        Clerk a = branchClerk(branchA, CLERK);
        JsonNode list = call("GET", "/sales/orders?pageSize=200", null, a.token()).body().get("data");
        List<String> ids = new ArrayList<>();
        list.forEach(r -> ids.add(r.get("id").asString()));
        assertThat(ids).contains(inA).doesNotContain(inB);
        assertThat(call("GET", "/sales/orders/" + inB, null, a.token()).status()).isEqualTo(404);
        assertThat(call("POST", "/sales/orders/" + inB + "/confirm", null, a.token()).status()).isEqualTo(404);

        Res other = call("POST", "/sales/orders", Map.of("partnerId", customerA, "branchId", branchB, "lines", List.of()),
                a.token());
        assertThat(other.status()).isEqualTo(403);
        JsonNode stamped = call("POST", "/sales/orders", Map.of("partnerId", customerA, "lines", List.of()), a.token())
                .body();
        assertThat(stamped.get("branchId").asString()).as("branch comes from the user's scope").isEqualTo(branchA);

        String invoice = ok(200, "POST", "/invoices", Map.of("partnerId", customerB, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/invoices/" + invoice + "/post", null);
        jdbc.sql("UPDATE \"Document\" SET \"branchId\" = ? WHERE id = ?").params(branchB, invoice).update();

        assertThat(call("GET", "/sales/collections/worklist", null, a.token()).raw().body()).doesNotContain(customerB);
        assertThat(call("GET", "/sales/analytics/dashboard", null, a.token()).status()).isEqualTo(403);
        assertThat(call("GET", "/sales/reports/summary", null, a.token()).status()).isEqualTo(403);
        JsonNode lookups = call("GET", "/sales/report-center/lookups", null, a.token()).body().get("branches");
        assertThat(lookups.size()).isEqualTo(1);

        String period = "from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now();
        JsonNode catalog = call("GET", "/sales/report-center/catalog", null, a.token()).body().get("categories");
        List<String> leaks = new ArrayList<>();
        int ran = 0;
        for (JsonNode cat : catalog) {
            for (JsonNode rep : cat.get("reports")) {
                String key = rep.get("key").asString();
                Res r = call("GET", "/sales/report-center/run/" + key + "?" + period, null, a.token());
                ran++;
                if (r.status() >= 500 || r.raw().body().contains("ZZ-G1B-" + sfx)) {
                    leaks.add(key + ":" + r.status());
                }
            }
        }
        assertThat(ran).isGreaterThan(50);
        assertThat(leaks).as("reports that leak branch B or fail").isEmpty();
        assertThat(ok(200, "GET", "/sales/report-center/run/invoice-register?" + period, null).toString())
                .as("admin sees it").contains("ZZ-G1B-" + sfx);
    }

    @Test
    @DisplayName("null-branch rule: a restricted user sees a branchless order only when they own or created it")
    void nullBranch() {
        Clerk owner = branchClerk(branchA, CLERK);
        Clerk other = branchClerk(branchA, CLERK);
        String id = order(customerA, null);
        jdbc.sql("UPDATE \"SalesOrder\" SET \"branchId\" = NULL, \"ownerId\" = ? WHERE id = ?").params(owner.id(), id)
                .update();
        assertThat(call("GET", "/sales/orders/" + id, null, owner.token()).status()).isEqualTo(200);
        assertThat(call("GET", "/sales/orders/" + id, null, other.token()).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/sales/orders/" + id, null).get("id").asString()).isEqualTo(id);
    }

    @Test
    @DisplayName("sales:branch:override lifts the scope")
    void overridePermission() {
        String inB = order(customerB, branchB);
        String[] perms = java.util.Arrays.copyOf(CLERK, CLERK.length + 1);
        perms[CLERK.length] = "sales:branch:override";
        Clerk c = branchClerk(branchA, perms);
        assertThat(call("GET", "/sales/orders/" + inB, null, c.token()).status()).isEqualTo(200);
    }
}
