package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Accounting on Testcontainers Postgres: seeded chart of accounts and mappings, account rules and tree,
 * maker-checker manual entries, reversal, posting-key idempotency (N-1), period close/reopen/lock,
 * authorization matrix and tenant isolation.
 */
class AccountingIT extends ApiTestSupport {

    String checkerToken;

    @BeforeAll
    void checker() {
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).single();
        String email = "checker-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
    }

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    static List<String> list(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    @DisplayName("seed: categories, chart of accounts, mappings registry; no required mapping missing")
    void seeded() {
        JsonNode cats = ok(200, "GET", "/account-categories", null);
        assertThat(cats.size()).isGreaterThan(15);
        assertThat(ok(200, "GET", "/account-categories/usage", null).get(0).has("accountCount")).isTrue();
        assertThat(list(ok(200, "GET", "/account-mappings/missing", null))).isEmpty();
        JsonNode registry = ok(200, "GET", "/account-mappings/registry", null);
        assertThat(registry.get(0).get("key").asString()).isEqualTo("accounts_receivable");
        JsonNode page = ok(200, "GET", "/accounts", null);
        // Nest's PaginationDto initializer (25) makes the controller's `pageSize ?? 200` dead code.
        assertThat(page.get("meta").get("pageSize").asInt()).isEqualTo(25);
        // Other suites add accounts (register drawers) to the shared org, so check presence, not position.
        assertThat(page.get("data").findValuesAsString("code")).contains("1000");
        JsonNode tree = ok(200, "GET", "/accounts/tree?includeBalances=true", null);
        // POS suites add root-level drawer accounts to the shared org, so find the assets root by code.
        JsonNode assets = null;
        for (JsonNode n : tree.get("nodes")) {
            if ("1000".equals(n.get("code").asString())) {
                assets = n;
            }
        }
        assertThat(assets).isNotNull();
        assertThat(assets.get("children").size()).isGreaterThan(5);
        assertThat(assets.get("subtotal")).isNotNull();
    }

    @Test
    @DisplayName("accounts: create rules, duplicate code 409, group/category invariant, guarded delete")
    void accounts() {
        String cash = jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE key = 'cash'").query(String.class).single();
        String code = "T" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode created = ok(201, "POST", "/accounts", Map.of("code", code, "name", "Till 9", "categoryId", cash,
                "parentAccountId", accountId("1000")));
        assertThat(created.get("normalBalance").asString()).isEqualTo("debit");
        assertThat(created.get("cashFlowCategory").asString()).isEqualTo("operating");
        // Nest's PaginationDto initializer (25) wins over the controller's `pageSize ?? 200`.
        assertThat(ok(200, "GET", "/accounts", null).get("meta").get("pageSize").asInt()).isEqualTo(25);
        assertThat(ok(200, "GET", "/journals", null).get("meta").get("pageSize").asInt()).isEqualTo(25);
        assertThat(ok(200, "GET", "/bank-accounts", null).get("meta").get("pageSize").asInt()).isEqualTo(25);
        // Whitelist and field errors come together, in class-validator order.
        Res unknownAndMissing = as("POST", "/accounts", Map.of("code", code, "name", "X", "categoryKey", "bank"));
        assertThat(validationMessages(unknownAndMissing)).containsExactly(
                "property categoryKey should not exist", "categoryId should not be empty", "categoryId must be a string");
        Res dupe = as("POST", "/accounts", Map.of("code", code, "name", "Again", "categoryId", cash));
        assertThat(dupe.status()).isEqualTo(409);
        assertThat(dupe.body().get("message").asString()).isEqualTo("Account code '" + code + "' is already used by 'Till 9'");
        Res group = as("POST", "/accounts", Map.of("code", code + "G", "name", "G", "categoryId", cash, "isPostable", false));
        assertThat(group.body().get("message").asString())
                .isEqualTo("A group account is a structural node in the reporting tree and must not have a category.");
        Res underLeaf = as("PATCH", "/accounts/" + created.get("id").asString(), Map.of("parentAccountId", accountId("1100")));
        assertThat(underLeaf.body().get("message").asString())
                .isEqualTo("'1100 Cash' is a postable account, so it cannot have children. Only group accounts can.");
        Res system = as("DELETE", "/accounts/" + accountId("1100"), null);
        assertThat(system.body().get("message").asString()).isEqualTo(
                "This is a protected system account and cannot be deleted. Deactivate or deprecate it instead.");
        assertThat(ok(200, "PATCH", "/accounts/" + created.get("id").asString() + "/deprecate", Map.of())
                .get("deprecatedAt").isNull()).isFalse();
        assertThat(as("DELETE", "/accounts/" + created.get("id").asString(), null).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("journal entries: draft → maker-checker post → reverse; rounding; unbalanced; control account")
    void journalEntries() {
        String date = Year.now() + "-03-15";
        Map<String, Object> body = Map.of("journalCode", "GEN", "date", date, "description", "Owner capital",
                "lines", List.of(Map.of("accountId", accountId("1200"), "debit", 500),
                        Map.of("accountId", accountId("3100"), "credit", 500)));
        JsonNode draft = ok(200, "POST", "/journal-entries", body);
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("entryNumber").asString()).startsWith("DRAFT-");
        assertThat(draft.get("lines")).hasSize(2);
        String id = draft.get("id").asString();

        Res self = as("POST", "/journal-entries/" + id + "/post", null);
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().get("message").asString())
                .isEqualTo("The user who created a journal entry cannot post it (maker-checker).");
        Res posted = call("POST", "/journal-entries/" + id + "/post", null, checkerToken);
        assertThat(posted.status()).isEqualTo(200);
        assertThat(posted.body().get("entryNumber").asString()).startsWith("GEN/" + Year.now() + "/");
        assertThat(posted.body().get("status").asString()).isEqualTo("posted");

        JsonNode detail = ok(200, "GET", "/journal-entries/" + id, null);
        assertThat(detail.get("journal").get("code").asString()).isEqualTo("GEN");
        assertThat(detail.get("lines").get(0).get("account").get("code").asString()).isEqualTo("1200");

        JsonNode reversal = ok(200, "POST", "/journal-entries/" + id + "/reverse", Map.of("description", "oops"));
        assertThat(reversal.get("reversalOfId").asString()).isEqualTo(id);
        assertThat(reversal.get("lines").get(0).get("credit").asString()).isEqualTo("500");
        Res again = as("POST", "/journal-entries/" + id + "/reverse", Map.of());
        assertThat(again.body().get("message").asString()).isEqualTo("Only posted entries can be reversed");

        Res unbalanced = as("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", date,
                "lines", List.of(Map.of("accountId", accountId("1200"), "debit", 10),
                        Map.of("accountId", accountId("3100"), "credit", 9))));
        assertThat(unbalanced.body().get("message").asString())
                .isEqualTo("Unbalanced entry: total debit 10 != total credit 9");
        JsonNode rounded = ok(200, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", date,
                "lines", List.of(Map.of("accountId", accountId("1200"), "debit", 10),
                        Map.of("accountId", accountId("3100"), "credit", 9.995))));
        assertThat(rounded.get("lines")).hasSize(3);
        Res group = as("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", date,
                "lines", List.of(Map.of("accountId", accountId("1000"), "debit", 1),
                        Map.of("accountId", accountId("3100"), "credit", 1))));
        assertThat(group.body().get("message").asString())
                .isEqualTo("Account 1000 is a summary/group account and cannot be posted to");
        Res one = as("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", date,
                "lines", List.of(Map.of("accountId", accountId("1200"), "debit", 1))));
        assertThat(one.status()).isEqualTo(400);
        assertThat(list(one.body().get("message"))).containsExactly("lines must contain at least 2 elements");
        JsonNode page = ok(200, "GET", "/journal-entries?search=owner", null);
        assertThat(page.get("data").get(0).get("_count").get("lines").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("period close → reopen → close (N-1) → lock; closed period rejects postings")
    void periodClose() {
        int year = Year.now().getValue() - 3;
        String periodId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status, \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?::timestamp, ?::timestamp, 'open', now())")
                .params(periodId, demoOrg, "FY" + year, year + "-01-01", year + "-12-31").update();
        String date = year + "-06-30";
        Map<String, Object> sale = Map.of("journalCode", "GEN", "date", date, "description", "Sale",
                "lines", List.of(Map.of("accountId", accountId("1100"), "debit", 300),
                        Map.of("accountId", accountId("4100"), "credit", 300)));
        String draft = ok(200, "POST", "/journal-entries", sale).get("id").asString();
        assertThat(call("POST", "/journal-entries/" + draft + "/post", null, checkerToken).status()).isEqualTo(200);

        JsonNode closed = ok(200, "POST", "/fiscal-periods/" + periodId + "/close", null);
        assertThat(closed.get("netIncome").asString()).isEqualTo("300");
        String closing = closed.get("journalEntryId").asString();
        Res blocked = as("POST", "/journal-entries", sale);
        assertThat(blocked.body().get("message").asString())
                .isEqualTo("Fiscal period 'FY" + year + "' is closed; reopen or post to a different period");
        Res twice = as("POST", "/fiscal-periods/" + periodId + "/close", null);
        assertThat(twice.body().get("message").asString())
                .isEqualTo("Fiscal period 'FY" + year + "' is closed; only open periods can be closed");

        assertThat(ok(200, "POST", "/fiscal-periods/" + periodId + "/reopen", null).get("reopened").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(closing)
                .query(String.class).single()).isEqualTo("reversed");
        assertThat(jdbc.sql("SELECT \"postingKey\" FROM \"JournalEntry\" WHERE id = ?").param(closing)
                .query(String.class).single()).startsWith("period_close:" + periodId + ":rev:");
        // Nest: the reversal posts today, so the reversed closing entry still nets the period to zero.
        Res empty = as("POST", "/fiscal-periods/" + periodId + "/close", null);
        assertThat(empty.body().get("message").asString())
                .isEqualTo("Nothing to close for 'FY" + year + "': no posted revenue or expense in this period");
        Map<String, Object> late = Map.of("journalCode", "GEN", "date", date, "description", "Late sale",
                "lines", List.of(Map.of("accountId", accountId("1100"), "debit", 100),
                        Map.of("accountId", accountId("4100"), "credit", 100)));
        String lateDraft = ok(200, "POST", "/journal-entries", late).get("id").asString();
        assertThat(call("POST", "/journal-entries/" + lateDraft + "/post", null, checkerToken).status()).isEqualTo(200);
        JsonNode reclosed = ok(200, "POST", "/fiscal-periods/" + periodId + "/close", null);
        assertThat(reclosed.get("journalEntryId").asString()).isNotEqualTo(closing);
        assertThat(reclosed.get("netIncome").asString()).isEqualTo("100");
        assertThat(jdbc.sql("SELECT \"postingKey\" FROM \"JournalEntry\" WHERE id = ?")
                .param(reclosed.get("journalEntryId").asString()).query(String.class).single())
                .isEqualTo("period_close:" + periodId);
        assertThat(as("POST", "/fiscal-periods/" + periodId + "/lock", null).status()).isEqualTo(200);
        Res reopenLocked = as("POST", "/fiscal-periods/" + periodId + "/reopen", null);
        assertThat(reopenLocked.body().get("message").asString()).isEqualTo("Fiscal period 'FY" + year
                + "' is locked; a locked period cannot be reopened. Unlock requires an explicit year-end unlock operation.");
    }

    @Test
    @DisplayName("journals, cost centers, currencies + FX rates, account mappings PUT validation")
    void masters() {
        String code = "J" + UUID.randomUUID().toString().substring(0, 5);
        JsonNode journal = ok(201, "POST", "/journals", Map.of("code", code, "name", "Petty", "journalType", "cash"));
        assertThat(ok(200, "GET", "/journals/" + journal.get("id").asString(), null).get("journalType").asString())
                .isEqualTo("cash");
        assertThat(ok(200, "GET", "/journals", null).get("meta").get("pageSize").asInt()).isEqualTo(25);
        JsonNode cc = ok(201, "POST", "/cost-centers", Map.of("code", "CC-" + code, "name", "Kitchen"));
        assertThat(cc.get("type").asString()).isEqualTo("cost");
        assertThat(ok(200, "PATCH", "/cost-centers/" + cc.get("id").asString(), Map.of("name", "Bar")).get("count")
                .asInt()).isEqualTo(1);
        assertThat(as("GET", "/cost-centers/" + UUID.randomUUID(), null).body().get("message").asString())
                .isEqualTo("Cost center not found");

        assertThat(as("POST", "/currencies/rates", Map.of("fromCode", "USD", "toCode", "UGX", "asOf", "2026-01-01",
                "rate", 3700)).status()).isEqualTo(201);
        JsonNode rate = ok(200, "GET", "/currencies/rate?fromCode=UGX&toCode=USD&asOf=2026-02-01", null);
        assertThat(rate.get("rate").asString()).startsWith("0.000270270");
        Res none = as("GET", "/currencies/rate?fromCode=EUR&toCode=UGX&asOf=2020-01-01", null);
        assertThat(none.status()).isEqualTo(404);
        assertThat(none.body().get("message").asString()).isEqualTo("No FX rate for EUR→UGX at or before 2020-01-01");

        Res wrong = as("PUT", "/account-mappings/cogs", Map.of("accountId", accountId("4100")));
        assertThat(wrong.body().get("message").asString()).isEqualTo("Mapping 'cogs' expects a cost_of_goods_sold account;"
                + " '4100 Sales Revenue' is revenue. Pass force=true to override.");
        JsonNode set = ok(200, "PUT", "/account-mappings/cogs", Map.of("accountId", accountId("5100")));
        assertThat(set.get("accountId").asString()).isEqualTo(accountId("5100"));
    }

    @Test
    @DisplayName("authorization matrix: every accounting route → 401 without a token, 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.accounting")).isGreaterThan(30);
        assertThat(Permissions.ALL).contains("journal_entry:post");
    }

    @Test
    @DisplayName("tenant isolation: another organization never sees DEMO accounts or entries")
    void tenantIsolation() {
        JsonNode accounts = call("GET", "/accounts", null, otherToken).body();
        accounts.get("data").forEach(a -> assertThat(a.get("organizationId").asString()).isEqualTo(otherOrg));
        assertThat(call("GET", "/accounts/" + accountId("1100"), null, otherToken).status()).isEqualTo(404);
        Res post = call("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", Year.now() + "-01-02",
                "lines", List.of(Map.of("accountId", accountId("1200"), "debit", 1),
                        Map.of("accountId", accountId("3100"), "credit", 1))), otherToken);
        assertThat(post.status()).isEqualTo(400);
    }
}
