package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FIN-2: the list filters behind the vendor bill screens and the supplier row of the AP aging screen. */
class VendorBillListIT extends ApiTestSupport {

    String supplier;
    String otherSupplier;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        supplier = supplier();
        otherSupplier = supplier();
    }

    String supplier() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(id, demoOrg, "S-" + UUID.randomUUID().toString().substring(0, 8), "Supplier " + id)
                .update();
        return id;
    }

    JsonNode bill(String partnerId, int price) {
        return ok(200, "POST", "/vendor-bills", Map.of("partnerId", partnerId, "issueDate", today, "lines",
                List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", price))));
    }

    @Test
    @DisplayName("GET /vendor-bills filters by status and supplier; an unknown filter stays rejected")
    void filters() {
        String draft = bill(supplier, 10).get("id").asString();
        String posted = bill(supplier, 20).get("id").asString();
        String other = bill(otherSupplier, 30).get("id").asString();
        ok(200, "POST", "/vendor-bills/" + posted + "/post", Map.of());

        JsonNode mine = ok(200, "GET", "/vendor-bills?partnerId=" + supplier, null);
        assertThat(mine.get("meta").get("total").asInt()).isEqualTo(2);
        assertThat(mine.toString()).contains(draft, posted).doesNotContain(other);

        JsonNode postedOnly = ok(200, "GET", "/vendor-bills?status=posted&partnerId=" + supplier, null);
        assertThat(postedOnly.get("data")).hasSize(1);
        assertThat(postedOnly.get("data").get(0).get("id").asString()).isEqualTo(posted);

        assertThat(as("GET", "/vendor-bills?colour=red", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("issue date: 'today' is the company's calendar day (UTC+14 company), a later day is refused")
    void issueDateInCompanyZone() {
        String tz = jdbc.sql("SELECT timezone FROM \"Organization\" WHERE id = ?").param(demoOrg).query(String.class)
                .single();
        jdbc.sql("UPDATE \"Organization\" SET timezone = 'Pacific/Kiritimati' WHERE id = ?").param(demoOrg).update();
        String own = supplier();
        try {
            LocalDate local = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));
            Res ok = as("POST", "/vendor-bills", Map.of("partnerId", own, "issueDate", local.toString(), "lines",
                    List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", 5))));
            assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
            Res future = as("POST", "/vendor-bills", Map.of("partnerId", own, "issueDate",
                    local.plusDays(1).toString(), "lines",
                    List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", 5))));
            assertThat(future.status()).isEqualTo(400);
            assertThat(future.body().get("message").asString()).isEqualTo("Issue date cannot be in the future");
        } finally {
            jdbc.sql("UPDATE \"Organization\" SET timezone = ? WHERE id = ?").params(tz, demoOrg).update();
        }
    }

    @Test
    @DisplayName("AP aging: a bill 40 days overdue lands in the 31-60 bucket of its supplier")
    void agingBucket() {
        String overdue = supplier();
        String id = bill(overdue, 75).get("id").asString();
        ok(200, "POST", "/vendor-bills/" + id + "/post", Map.of());
        jdbc.sql("UPDATE \"Document\" SET \"dueDate\" = now() - interval '40 days' WHERE id = ?").param(id).update();
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ?").param(demoOrg).update();

        JsonNode aging = ok(200, "GET", "/reports/ap/aging", null);
        assertThat(aging.get("source").asString()).isEqualTo("live");
        JsonNode row = null;
        for (JsonNode p : aging.get("partners")) {
            if (overdue.equals(p.get("partnerId").asString())) {
                row = p;
            }
        }
        assertThat(row).isNotNull();
        assertThat(new BigDecimal(row.get("total").asString())).isEqualByComparingTo("75");
        assertThat(new BigDecimal(row.get("buckets").get("b31_60").asString())).isEqualByComparingTo("75");
        assertThat(new BigDecimal(row.get("buckets").get("current").asString())).isEqualByComparingTo("0");
    }
}
