package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SG-5: customer profile, suspension, duplicates, merge and import. */
class SalesCustomerIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "G5P-" + sfx, "name", "SG5 item " + sfx, "productType", "service",
                "salesPrice", 100)).get("id").asString();
    }

    String customer(String name, String phone) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, phone, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, true, now())").params(id, demoOrg, "G5-" + UUID.randomUUID(), name, phone).update();
        return id;
    }

    String postedInvoice(String partner, int price) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", LocalDate.now().toString(), "lines",
                List.of(Map.of("productId", product, "quantity", 1, "unitPrice", price)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        return inv;
    }

    @Test
    @DisplayName("profile saves; home branch and currency default onto a new order; suspension blocks new documents")
    void profileDefaultsAndSuspend() {
        String c = customer("SG5 profile " + sfx, null);
        String branch = ok(201, "POST", "/branches", Map.of("code", "G5B-" + sfx, "name", "SG5 B")).get("id").asString();
        JsonNode p = ok(200, "PUT", "/sales/customers/" + c + "/profile", Map.of("customerType", "contractor", "homeBranchId",
                branch, "altPhone", "0700111222", "district", "Kampala"));
        assertThat(p.get("customerType").asString()).isEqualTo("contractor");
        assertThat(as("PUT", "/sales/customers/" + c + "/profile", Map.of("customerType", "nope")).status()).isEqualTo(400);
        JsonNode o = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of()));
        assertThat(o.get("branchId").asString()).isEqualTo(branch);

        ok(200, "POST", "/sales/customers/" + c + "/suspend", Map.of("reason", "Bounced cheque"));
        Res r = as("POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of()));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("suspended").contains("Bounced cheque");
        ok(200, "POST", "/sales/customers/" + c + "/reinstate", null);
        assertThat(as("POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of())).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("duplicates match on name and phone digits")
    void duplicates() {
        String name = "SG5 Dup " + sfx;
        String c = customer(name, "+256 700 123 456");
        assertThat(ok(200, "GET", "/sales/customers/duplicates?name=" + name.toLowerCase().replace(" ", "%20"), null).toString())
                .contains(c);
        assertThat(ok(200, "GET", "/sales/customers/duplicates?phone=0700123456", null).toString()).contains(c);
        assertThat(ok(200, "GET", "/sales/customers/duplicates?phone=0700123456&excludeId=" + c, null).toString())
                .doesNotContain(c);
    }

    @Test
    @DisplayName("merge keeps balances and posted documents intact, rolls up, and blocks the old record")
    void merge() {
        String old = customer("SG5 old " + sfx, null);
        String keep = customer("SG5 keep " + sfx, null);
        String inv = postedInvoice(old, 100);
        postedInvoice(keep, 50);
        String draftOrder = ok(201, "POST", "/sales/orders", Map.of("partnerId", old, "lines", List.of())).get("id").asString();
        String path = "/sales/customers/" + old + "/merge";
        assertThat(as("POST", path, Map.of("intoPartnerId", keep, "reason", "dup")).status()).as("open order").isEqualTo(400);
        ok(200, "DELETE", "/sales/orders/" + draftOrder, null);
        assertThat(as("POST", path, Map.of("intoPartnerId", old, "reason", "dup")).status()).isEqualTo(400);
        JsonNode survivor = ok(200, "POST", path, Map.of("intoPartnerId", keep, "reason", "dup"));
        assertThat(survivor.get("kpis").get("outstanding").asDouble()).as("rolled up").isEqualTo(150);
        assertThat(survivor.get("mergedFrom").size()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"Document\" WHERE id = ?").param(inv).query(String.class).single())
                .as("posted document untouched").isEqualTo(old);
        assertThat(ok(200, "GET", "/reports/ar/customers/" + old + "/statement", null).get("closingBalance").asDouble())
                .isEqualTo(100);
        Res r = as("POST", "/sales/orders", Map.of("partnerId", old, "lines", List.of()));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("merged");
        assertThat(as("POST", path, Map.of("intoPartnerId", keep, "reason", "again")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("import rejects bad rows, writes only good ones, and a dry run writes nothing")
    void importRows() {
        List<Map<String, Object>> rows = List.of(Map.of("name", "SG5 imp ok " + sfx, "email", "a" + sfx + "@x.test",
                "customerType", "retail", "creditLimit", 1000), Map.of("email", "no-name@x.test"), Map.of("name", "SG5 imp bad " + sfx,
                        "email", "not-an-email"), Map.of("name", "SG5 imp ok " + sfx));
        JsonNode dry = ok(200, "POST", "/sales/customers/import", Map.of("rows", rows));
        assertThat(dry.get("valid").asInt()).isEqualTo(1);
        assertThat(dry.get("rejected").asInt()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE name = ?").param("SG5 imp ok " + sfx).query(Long.class).single())
                .isZero();
        JsonNode real = ok(200, "POST", "/sales/customers/import", Map.of("rows", rows, "dryRun", false));
        assertThat(real.get("imported").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE name = ? AND \"isCustomer\"").param("SG5 imp ok " + sfx)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(real.get("errors").get(0).get("errors").toString()).contains("name is required");
    }

    @Test
    @DisplayName("every customer route refuses a user without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(com.inventoryerp.sales.web.SalesCustomerController.class)).isGreaterThan(6);
    }
}
