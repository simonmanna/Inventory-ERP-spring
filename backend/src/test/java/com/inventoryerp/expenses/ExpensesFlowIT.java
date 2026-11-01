package com.inventoryerp.expenses;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Editing an unpaid expense, the filtered lists and the category edit. */
class ExpensesFlowIT extends ApiTestSupport {

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        return r.body();
    }

    @Test
    @DisplayName("an unpaid expense can be edited; a paid one cannot")
    void editUnpaid() {
        String sfx = UUID.randomUUID().toString().substring(0, 4);
        String acc = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '5200'")
                .param(demoOrg).query(String.class).single();
        String cat = ok(201, "POST", "/expense-categories", Map.of("name", "Repairs " + sfx, "ledgerAccountId", acc))
                .get("id").asString();
        assertThat(ok(200, "PATCH", "/expense-categories/" + cat, Map.of("name", "Repairs and parts " + sfx))
                .get("name").asString()).isEqualTo("Repairs and parts " + sfx);
        String supplier = ok(201, "POST", "/partners", Map.of("code", "EXS-" + sfx, "name", "Plumber " + sfx,
                "isSupplier", true)).get("id").asString();
        JsonNode exp = money("/expenses", Map.of("title", "Sink " + sfx, "amount", 120, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CREDIT", "categoryId", cat, "supplierId", supplier));
        String id = exp.get("id").asString();
        JsonNode edited = ok(200, "PATCH", "/expenses/" + id, Map.of("title", "Kitchen sink " + sfx, "amount", 150,
                "notes", "Two visits", "description", "Leak under the sink"));
        assertThat(edited.get("title").asString()).isEqualTo("Kitchen sink " + sfx);
        assertThat(edited.get("amount").asInt()).isEqualTo(150);

        for (String q : new String[] {"?status=APPROVED", "?search=sink", "?categoryId=" + cat,
            "?supplierId=" + supplier,
            "?fromDate=" + LocalDate.now().minusDays(1) + "&toDate=" + LocalDate.now().plusDays(1),
            "?paymentStatus=UNPAID&page=1&pageSize=5"}) {
            assertThat(ok(200, "GET", "/expenses" + q, null).toString()).as(q).contains(id);
        }
        Res voided = call("POST", "/expenses/" + id + "/void", Map.of("voidReason", "Duplicate"),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(voided.status()).as(voided.raw().body()).isLessThan(300);
        Res late = as("PATCH", "/expenses/" + id, Map.of("title", "Too late"));
        assertThat(late.status()).isEqualTo(400);
        assertThat(late.body().get("message").asString())
                .isEqualTo("Only unpaid draft/approved expenses can be edited");
    }
}
