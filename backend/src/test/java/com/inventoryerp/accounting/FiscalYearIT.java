package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.web.FiscalYearController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** PR-2 (roadmap 1.7): fiscal years, the year-end checklist and close, closing balances, reopen and lock. */
class FiscalYearIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    record Books(String org, String owner, String ownerId) {}

    Books freshBooks() {
        String code = "fy-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName", "FY Co",
                "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword", "Owner@12345"),
                (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String ownerId = boot.body().get("adminUser").get("id").asString();
        return new Books(org, login(code, "owner@" + code + ".test", "Owner@12345"), ownerId);
    }

    String account(String org, String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(org, code)
                .query(String.class).single();
    }

    void post(Books b, String date, String debitCode, String creditCode, int amount) {
        TenantContext.run(TenantStore.user(b.org(), b.ownerId()), () -> new TransactionTemplate(txm).execute(s ->
                posting.post(PostingRequest.of("GEN", Instant.parse(date + "T10:00:00Z"), "Test " + date, "manual",
                        UUID.randomUUID().toString(), null, List.of(
                                new PostingRequest.Line(account(b.org(), debitCode), amount, 0, null, "Dr", null, null, null),
                                new PostingRequest.Line(account(b.org(), creditCode), 0, amount, null, "Cr", null, null,
                                        null))))));
    }

    Res as(Books b, String method, String path, Object body) {
        return call(method, path, body, b.owner());
    }

    JsonNode okAs(Books b, int status, String method, String path, Object body) {
        Res r = as(b, method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    @Test
    @DisplayName("year-end: checklist blocks, period close rolls P&L, sign-offs, close, balances, next year, reopen, lock")
    void yearEnd() {
        Books b = freshBooks();
        if (jdbc.sql("SELECT count(*) FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = 'retained_earnings'")
                .param(b.org()).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\") VALUES"
                    + " (?, ?, 'retained_earnings', ?, now())").params(UUID.randomUUID().toString(), b.org(),
                    account(b.org(), "3100")).update();
        }
        int y = Year.now().getValue();
        JsonNode fy = okAs(b, 201, "POST", "/fiscal-years", Map.of("startDate", y + "-01-01", "periodicity", "monthly"));
        String fyId = fy.get("id").asString();
        assertThat(fy.get("name").asString()).isEqualTo("FY" + y);
        assertThat(fy.get("endDate").asString()).isEqualTo(y + "-12-31");
        // The year-long period the bootstrap made joins the year; no monthly period is cut out of it.
        assertThat(fy.get("periods")).hasSize(1);
        String periodId = fy.get("periods").get(0).get("id").asString();
        assertThat(as(b, "POST", "/fiscal-years", Map.of("startDate", y + "-06-01")).body().get("message").asString())
                .isEqualTo("The dates overlap fiscal year FY" + y);
        assertThat(as(b, "POST", "/fiscal-years", Map.of("startDate", "bad")).status()).isEqualTo(400);

        post(b, y + "-03-15", "1100", "4100", 1000);
        JsonNode list = okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/close-checklist", null);
        assertThat(list.get("readyToClose").asBoolean()).isFalse();
        Res blocked = as(b, "POST", "/fiscal-years/" + fyId + "/close", Map.of());
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.body().get("message").asString()).startsWith("Cannot close FY" + y
                + ": Every period with postings closed; Profit and loss closed to retained earnings");

        okAs(b, 200, "POST", "/fiscal-periods/" + periodId + "/close", null);
        list = okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/close-checklist", null);
        for (JsonNode c : list.get("checks")) {
            if (c.get("blocking").asBoolean()) {
                assertThat(c.get("ok").asBoolean()).as(c.toString()).isTrue();
            }
        }
        assertThat(as(b, "POST", "/fiscal-years/" + fyId + "/close", Map.of()).body().get("message").asString())
                .contains("Sign-off: Year-end stock count done and posted");
        assertThat(as(b, "PUT", "/fiscal-years/" + fyId + "/close-checklist/nope", Map.of("signed", true)).status())
                .isEqualTo(400);
        for (JsonNode s : list.get("signOffs")) {
            okAs(b, 200, "PUT", "/fiscal-years/" + fyId + "/close-checklist/" + s.get("key").asString(),
                    Map.of("signed", true, "note", "ok"));
        }
        assertThat(okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/close-checklist", null).get("readyToClose")
                .asBoolean()).isTrue();

        JsonNode closed = okAs(b, 200, "POST", "/fiscal-years/" + fyId + "/close", Map.of("lockPeriods", false));
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(new BigDecimal(closed.get("netIncome").asString())).isEqualByComparingTo("1000");
        String nextId = closed.get("nextFiscalYearId").asString();
        JsonNode next = okAs(b, 200, "GET", "/fiscal-years/" + nextId, null);
        assertThat(next.get("name").asString()).isEqualTo("FY" + (y + 1));
        assertThat(next.get("periods")).hasSize(12);
        assertThat(next.get("periods").get(0).get("name").asString()).isEqualTo("Jan " + (y + 1));

        JsonNode balances = okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/closing-balances", null);
        Map<String, BigDecimal> byCode = new java.util.HashMap<>();
        for (JsonNode a : balances.get("accounts")) {
            byCode.put(a.get("code").asString(), new BigDecimal(a.get("balance").asString()));
        }
        assertThat(byCode.get("1100")).isEqualByComparingTo("1000");
        assertThat(byCode.get("3100")).isEqualByComparingTo("1000");
        assertThat(byCode).doesNotContainKey("4100");

        assertThat(as(b, "POST", "/fiscal-years/" + fyId + "/close", Map.of()).body().get("message").asString())
                .isEqualTo("Fiscal year FY" + y + " is already closed");
        assertThat(okAs(b, 200, "POST", "/fiscal-years/" + fyId + "/reopen", null).get("status").asString())
                .isEqualTo("open");
        assertThat(okAs(b, 200, "GET", "/fiscal-years/" + fyId + "/closing-balances", null).get("accounts")).isEmpty();

        JsonNode lockedYear = okAs(b, 200, "POST", "/fiscal-years/" + fyId + "/close", null);
        assertThat(lockedYear.get("lockedPeriods").asBoolean()).isTrue();
        assertThat(lockedYear.get("nextFiscalYearId").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT status::text FROM \"FiscalPeriod\" WHERE id = ?").param(periodId)
                .query(String.class).single()).isEqualTo("locked");
        assertThat(as(b, "POST", "/fiscal-years/" + fyId + "/reopen", null).body().get("message").asString())
                .isEqualTo("Fiscal year FY" + y + " locked its periods when it closed; it cannot be reopened");

        // Tenancy: another organization sees nothing.
        assertThat(call("GET", "/fiscal-years/" + fyId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fiscal-years", null, otherToken).body()).isEmpty();
    }

    @Test
    @DisplayName("a year with no postings closes its empty periods itself")
    void emptyYear() {
        Books b = freshBooks();
        if (jdbc.sql("SELECT count(*) FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = 'retained_earnings'")
                .param(b.org()).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\") VALUES"
                    + " (?, ?, 'retained_earnings', ?, now())").params(UUID.randomUUID().toString(), b.org(),
                    account(b.org(), "3100")).update();
        }
        String id = okAs(b, 201, "POST", "/fiscal-years", Map.of("name", "Old year", "startDate", "2019-07-01",
                "periodicity", "quarterly")).get("id").asString();
        JsonNode y = okAs(b, 200, "GET", "/fiscal-years/" + id, null);
        assertThat(y.get("endDate").asString()).isEqualTo("2020-06-30");
        assertThat(y.get("periods")).hasSize(4);
        assertThat(y.get("periods").get(0).get("name").asString()).isEqualTo("Q3 2019");
        for (JsonNode s : okAs(b, 200, "GET", "/fiscal-years/" + id + "/close-checklist", null).get("signOffs")) {
            okAs(b, 200, "PUT", "/fiscal-years/" + id + "/close-checklist/" + s.get("key").asString(),
                    Map.of("signed", true));
        }
        JsonNode closed = okAs(b, 200, "POST", "/fiscal-years/" + id + "/close", Map.of("lockPeriods", false));
        assertThat(closed.get("emptyPeriodsClosed").asInt()).isEqualTo(4);
        assertThat(new BigDecimal(closed.get("netIncome").asString())).isEqualByComparingTo("0");
        assertThat(closed.get("nextFiscalYearId").isNull()).isFalse();
    }

    @Test
    @DisplayName("fiscal year routes: 401 without a session, 403 without their permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(FiscalYearController.class)).isEqualTo(8);
    }
}
