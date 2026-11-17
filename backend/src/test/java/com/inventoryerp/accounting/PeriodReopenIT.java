package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17: reopening a period reverses the closing entry still in force (ACR-1), however many times the
 * period was closed and reopened; and only stock documents raised up to the period end hold its close (ACR-17).
 */
class PeriodReopenIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String org;
    String ownerId;
    String owner;

    void freshBooks() {
        String code = "pr-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Reopen Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        ownerId = boot.body().get("adminUser").get("id").asString();
        owner = login(code, "owner@" + code + ".test", "Owner@12345");
        if (jdbc.sql("SELECT count(*) FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = 'retained_earnings'")
                .param(org).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\") VALUES"
                    + " (?, ?, 'retained_earnings', ?, now())").params(UUID.randomUUID().toString(), org,
                    account("3100")).update();
        }
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(org, code)
                .query(String.class).single();
    }

    void sale(int amount) {
        TenantContext.run(TenantStore.user(org, ownerId), () -> new TransactionTemplate(txm).execute(s ->
                posting.post(PostingRequest.of("GEN", Instant.now(), "Sale", "manual", UUID.randomUUID().toString(),
                        null, List.of(PostingRequest.Line.debit(account("1100"), amount, "Dr"),
                                PostingRequest.Line.credit(account("4100"), amount, "Cr"))))));
    }

    JsonNode ok(int status, String method, String path) {
        Res r = call(method, path, null, owner);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    BigDecimal revenue() {
        return jdbc.sql("SELECT coalesce(sum(credit - debit), 0) FROM \"JournalLine\" WHERE \"organizationId\" = ? AND"
                + " \"accountId\" = ?").params(org, account("4100")).query(BigDecimal.class).single();
    }

    long postedClosings() {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'period_close' AND status = 'posted'").param(org).query(Long.class).single();
    }

    /** A draft stock adjustment raised {@code daysAfterEnd} days after the period's end (negative: inside it). */
    String adjustment(String location, String period, int daysAfterEnd) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"StockAdjustment\" (id, \"organizationId\", \"adjCode\", \"locationId\", status,"
                + " \"createdAt\", \"updatedAt\") SELECT ?, ?, ?, ?, 'draft', p.\"endDate\" + make_interval(days => ?),"
                + " now() FROM \"FiscalPeriod\" p WHERE p.id = ?").params(id, org, "ADJ-" + id.substring(0, 6), location,
                daysAfterEnd, period).update();
        return id;
    }

    @Test
    @DisplayName("close → reopen → close → reopen reverses the second closing entry too; stock drafts raised after the"
            + " period end do not hold its close")
    void reopenTwice() {
        freshBooks();
        String period = jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"startDate\" <= now() AND \"endDate\" >= now() LIMIT 1").param(org).query(String.class).single();
        sale(1000);
        String location = call("POST", "/inventory/locations", Map.of("code", "PR-1", "name", "Store", "type",
                "warehouse"), owner).body().get("id").asString();

        // ACR-17: a draft raised in the period blocks; one raised after its end does not
        String inPeriod = adjustment(location, period, -1);
        adjustment(location, period, 5);
        Res held = call("POST", "/fiscal-periods/" + period + "/close", null, owner);
        assertThat(held.status()).isEqualTo(400);
        assertThat(held.body().get("message").asString()).contains("1 pending inventory mutation(s)");
        jdbc.sql("DELETE FROM \"StockAdjustment\" WHERE id = ?").param(inPeriod).update();

        ok(200, "POST", "/fiscal-periods/" + period + "/close");
        assertThat(revenue()).isEqualByComparingTo("0");
        ok(200, "POST", "/fiscal-periods/" + period + "/reopen");
        assertThat(revenue()).isEqualByComparingTo("1000");
        assertThat(postedClosings()).isZero();

        ok(200, "POST", "/fiscal-periods/" + period + "/close");
        assertThat(revenue()).isEqualByComparingTo("0");
        assertThat(postedClosings()).isEqualTo(1);
        // ACR-1: the first closing entry (reversed, same period-end date) must not be the one picked
        ok(200, "POST", "/fiscal-periods/" + period + "/reopen");
        assertThat(postedClosings()).as("the second closing entry is reversed as well").isZero();
        assertThat(revenue()).isEqualByComparingTo("1000");

        // and a third close still nets the period once
        ok(200, "POST", "/fiscal-periods/" + period + "/close");
        assertThat(revenue()).isEqualByComparingTo("0");
        assertThat(postedClosings()).isEqualTo(1);
    }
}
