package com.inventoryerp.intercompany;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.intercompany.web.IntercompanyController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * PR-8 (ADR-S028): company group with consent, company switching, mirrored sales invoice → vendor bill, intercompany
 * journal in two companies (atomic, idempotent), reconciliation, consolidation with eliminations and NCI.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntercompanyIT extends ApiTestSupport {

    record Books(String org, String code, String token) {}

    @org.springframework.beans.factory.annotation.Autowired
    com.inventoryerp.intercompany.application.IntercompanyMirrorService mirrorService;

    String sfx;
    String email;
    Books parent;
    Books sub;

    @BeforeAll
    void companies() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        email = "group-" + sfx + "@ic.test";
        parent = boot("icp-" + sfx, "Parent Co " + sfx);
        sub = boot("ics-" + sfx, "Sub Co " + sfx);
    }

    Books boot(String code, String name) {
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName", name,
                "adminEmail", email, "adminFirstName", "Owner", "adminPassword", "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        return new Books(boot.body().get("organization").get("id").asString(), code,
                login(code, email, "Owner@12345"));
    }

    JsonNode okAs(Books b, int status, String method, String path, Object body) {
        return okWith(b.token(), status, method, path, body);
    }

    JsonNode okWith(String token, int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    Res money(String token, String method, String path, Object body) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    String accountBySection(String org, String section) {
        return jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.\"reportSection\"::text = ? AND a.\"deletedAt\" IS NULL AND"
                + " a.\"isPostable\" ORDER BY a.code LIMIT 1").params(org, section).query(String.class).single();
    }

    String account(String org, String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(org, code)
                .query(String.class).single();
    }

    String code(String org, String accountId) {
        return jdbc.sql("SELECT code FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?").params(accountId, org)
                .query(String.class).single();
    }

    /** Draft + post a one-line sales invoice; returns its id. */
    String postInvoice(Books b, String partnerId, String revenueAccount, int amount) {
        JsonNode draft = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", partnerId, "issueDate",
                LocalDate.now().toString(), "lines", List.of(Map.of("accountId", revenueAccount, "description",
                        "Group services", "quantity", 1, "unitPrice", amount))));
        String id = draft.get("id").asString();
        Res post = money(b.token(), "POST", "/invoices/" + id + "/post", null);
        assertThat(post.status()).as(post.raw().body()).isIn(200, 201);
        return id;
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @Order(1)
    @DisplayName("group: parent creates, consent rule, members, ownership, outsiders refused")
    void group() {
        assertThat(call("GET", "/intercompany/group", null, parent.token()).raw().body()).isEmpty();
        JsonNode g = okAs(parent, 201, "POST", "/intercompany/group", Map.of("name", "Group " + sfx));
        assertThat(g.get("members")).hasSize(1);
        assertThat(g.get("members").get(0).get("isParent").asBoolean()).isTrue();
        assertThat(call("POST", "/intercompany/group", Map.of("name", "x"), parent.token()).status()).isEqualTo(409);

        // Consent: the DEMO admin has no account in the parent's group companies → cannot add OTHER org.
        Res foreign = call("POST", "/intercompany/group/members", Map.of("organizationCode", otherOrgCode),
                parent.token());
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.body().get("message").asString()).startsWith("You need an active account with"
                + " intercompany:manage in that company");
        assertThat(call("POST", "/intercompany/group/members", Map.of("organizationCode", "nope-" + sfx),
                parent.token()).status()).isEqualTo(404);

        g = okAs(parent, 201, "POST", "/intercompany/group/members", Map.of("organizationCode", sub.code(),
                "ownershipPercent", 80));
        assertThat(g.get("members")).hasSize(2);
        assertThat(g.get("members").get(1).get("ownershipPercent").asString()).isEqualTo("80");
        assertThat(call("POST", "/intercompany/group/members", Map.of("organizationCode", sub.code()), parent.token())
                .status()).isEqualTo(409);
        assertThat(call("POST", "/intercompany/group/members", Map.of("organizationCode", sub.code(),
                "ownershipPercent", 120), parent.token()).status()).isEqualTo(400);

        // The subsidiary sees the group but does not manage it.
        assertThat(okAs(sub, 200, "GET", "/intercompany/group", null).get("id").asString())
                .isEqualTo(g.get("id").asString());
        Res subManage = call("PATCH", "/intercompany/group", Map.of("name", "Mine"), sub.token());
        assertThat(subManage.status()).isEqualTo(403);
        assertThat(subManage.body().get("message").asString())
                .isEqualTo("Only the parent company manages the company group");
        assertThat(call("DELETE", "/intercompany/group/members/" + parent.org(), null, parent.token()).body()
                .get("message").asString()).isEqualTo("The parent company cannot leave its group; delete the group"
                        + " instead");
        assertThat(call("DELETE", "/intercompany/group", null, parent.token()).body().get("message").asString())
                .isEqualTo("Remove every subsidiary before deleting the company group");
        // Tenant isolation: another organization sees no group.
        assertThat(call("GET", "/intercompany/group", null, otherToken).raw().body()).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("switching: list companies, switch to the twin account, refuse outsiders")
    void switching() {
        JsonNode list = okAs(parent, 200, "GET", "/intercompany/companies", null);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("current").asBoolean()).isTrue();
        assertThat(list.get(1).get("organizationId").asString()).isEqualTo(sub.org());

        JsonNode switched = okAs(parent, 200, "POST", "/intercompany/companies/" + sub.org() + "/switch", null);
        assertThat(switched.get("organization").get("id").asString()).isEqualTo(sub.org());
        String subToken = switched.get("accessToken").asString();
        JsonNode mine = okWith(subToken, 200, "GET", "/intercompany/companies", null);
        assertThat(mine.get(0).get("organizationId").asString()).isEqualTo(sub.org());
        assertThat(mine.get(1).get("organizationId").asString()).isEqualTo(parent.org());
        assertThat(jdbc.sql("SELECT count(*) FROM \"LoginAttempt\" WHERE \"organizationId\" = ? AND reason ="
                + " 'company_switch'").param(sub.org()).query(Long.class).single()).isEqualTo(1);

        assertThat(call("POST", "/intercompany/companies/" + parent.org() + "/switch", null, otherToken).status())
                .isEqualTo(403);
        assertThat(call("POST", "/intercompany/companies/" + parent.org() + "/switch", null, parent.token()).body()
                .get("message").asString()).isEqualTo("You are already signed in to this company");
        assertThat(okWith(otherToken, 200, "GET", "/intercompany/companies", null)).hasSize(1);
    }

    @Test
    @Order(3)
    @DisplayName("mirror: posted IC invoice becomes a draft vendor bill in the other company")
    void mirror() {
        JsonNode link = okAs(parent, 200, "PUT", "/intercompany/partners/" + sub.org(), Map.of());
        String parentPartnerForSub = link.get("partnerId").asString();
        assertThat(link.get("partnerCode").asString()).isEqualTo("IC-" + sub.code());
        assertThat(okAs(parent, 200, "GET", "/intercompany/partners", null).get(0).get("partnerId").asString())
                .isEqualTo(parentPartnerForSub);
        String subExpense = accountBySection(sub.org(), "operating_expense");
        okAs(sub, 200, "PUT", "/intercompany/settings", Map.of("mirrorMode", "draft", "billExpenseAccountId",
                subExpense));
        assertThat(call("PUT", "/intercompany/settings", Map.of("mirrorMode", "always"), sub.token()).status())
                .isEqualTo(400);

        String revenue = accountBySection(parent.org(), "revenue");
        String invoice = postInvoice(parent, parentPartnerForSub, revenue, 100);
        drainOutboxFully();

        JsonNode tx = okAs(parent, 200, "GET", "/intercompany/transactions", null).get("data");
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).get("status").asString()).as(tx.toString()).isEqualTo("mirrored");
        assertThat(tx.get(0).get("direction").asString()).isEqualTo("outgoing");
        assertThat(tx.get(0).get("sourceDocumentId").asString()).isEqualTo(invoice);
        String billId = tx.get(0).get("targetDocumentId").asString();
        assertThat(okAs(sub, 200, "GET", "/intercompany/transactions", null).get("data").get(0).get("direction")
                .asString()).isEqualTo("incoming");

        Map<String, Object> bill = jdbc.sql("SELECT status::text AS status, \"totalAmount\", \"partnerId\", reference"
                + " FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(billId, sub.org()).query()
                .singleRow();
        assertThat(bill.get("status")).isEqualTo("draft");
        assertThat((BigDecimal) bill.get("totalAmount")).isEqualByComparingTo("100");
        String subPartnerForParent = jdbc.sql("SELECT \"partnerId\" FROM \"IntercompanyPartner\" WHERE"
                + " \"organizationId\" = ? AND \"counterpartOrganizationId\" = ?").params(sub.org(), parent.org())
                .query(String.class).single();
        assertThat(bill.get("partnerId")).isEqualTo(subPartnerForParent);

        // Re-delivery of the same event does not create a second bill.
        mirrorService.onInvoicePosted(parent.org(), invoice);
        assertThat(jdbc.sql("SELECT count(*) FROM \"IntercompanyTransaction\" WHERE \"organizationId\" = ?")
                .param(parent.org()).query(Long.class).single()).isEqualTo(1);

        Res post = money(sub.token(), "POST", "/vendor-bills/" + billId + "/post", Map.of());
        assertThat(post.status()).as(post.raw().body()).isIn(200, 201);
        assertThat(call("POST", "/intercompany/transactions/" + tx.get(0).get("id").asString() + "/retry", null,
                parent.token()).body().get("message").asString()).isEqualTo("The document is already mirrored");
    }

    @Test
    @Order(4)
    @DisplayName("journal: two entries in one transaction, idempotent, both roll back on a refusal")
    void journal() {
        String parentCash = account(parent.org(), "1100");
        String subCash = account(sub.org(), "1100");
        Map<String, Object> body = Map.of("targetOrganizationId", sub.org(), "date", LocalDate.now().toString(),
                "description", "Cash advance", "amount", 250, "sourceAccountId", parentCash, "targetAccountCode",
                "1100");
        assertThat(call("POST", "/intercompany/journals", body, parent.token()).status()).isEqualTo(400);

        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + parent.token());
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res first = call("POST", "/intercompany/journals", body, h);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = call("POST", "/intercompany/journals", body, h);
        assertThat(replay.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(first.body().get("number").asString()).startsWith("ICJ-");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'intercompany_journal' AND"
                + " \"sourceId\" = ?").param(first.body().get("id").asString()).query(Long.class).single()).isEqualTo(2);
        assertThat(okAs(sub, 200, "GET", "/intercompany/journals", null).get("data").get(0).get("direction")
                .asString()).isEqualTo("incoming");

        // Books locked in the subsidiary: neither company gets an entry.
        long before = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'intercompany_journal'").param(parent.org()).query(Long.class).single();
        jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = now() + interval '2 days' WHERE id = ?")
                .param(sub.org()).update();
        try {
            Res refused = money(parent.token(), "POST", "/intercompany/journals", body);
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.body().get("message").asString()).startsWith("Books are locked through");
        } finally {
            jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = NULL WHERE id = ?").param(sub.org()).update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'intercompany_journal'").param(parent.org()).query(Long.class).single())
                .isEqualTo(before);

        Res outsider = money(parent.token(), "POST", "/intercompany/journals", Map.of("targetOrganizationId",
                otherOrg, "date", LocalDate.now().toString(), "description", "x", "amount", 1, "sourceAccountId",
                parentCash, "targetAccountId", subCash));
        assertThat(outsider.body().get("message").asString())
                .isEqualTo("The company is not another member of your company group");
    }

    @Test
    @Order(5)
    @DisplayName("reconciliation and consolidation: eliminations, matched pairs, NCI")
    void consolidation() {
        // External sale in the subsidiary: 1000 of revenue that stays in the group.
        String customer = okAs(sub, 201, "POST", "/partners", Map.of("code", "EXT-" + sfx, "name", "Outside customer",
                "isCustomer", true)).get("id").asString();
        String subRevenue = accountBySection(sub.org(), "revenue");
        postInvoice(sub, customer, subRevenue, 1000);
        drainOutboxFully();

        JsonNode rec = okAs(parent, 200, "GET", "/intercompany/reconciliation", null);
        JsonNode pair = rec.get("pairs").get(0);
        assertThat(dec(pair.get("balanceA"))).isEqualByComparingTo("350"); // invoice 100 + advance 250
        assertThat(dec(pair.get("balanceB"))).isEqualByComparingTo("-350");
        assertThat(pair.get("matched").asBoolean()).as(pair.toString()).isTrue();

        JsonNode tb = okAs(parent, 200, "GET", "/intercompany/consolidation?statement=tb", null);
        assertThat(dec(tb.get("totals").get("intercompanyDifference"))).isEqualByComparingTo("0");
        assertThat(dec(tb.get("totals").get("net").get("consolidated"))).isEqualByComparingTo("0");

        JsonNode pl = okAs(parent, 200, "GET", "/intercompany/consolidation?statement=pl", null);
        String revenueCode = code(parent.org(), accountBySection(parent.org(), "revenue"));
        JsonNode revenueRow = null;
        for (JsonNode r : pl.get("rows")) {
            if (revenueCode.equals(r.get("code").asString())) {
                revenueRow = r;
            }
        }
        assertThat(revenueRow).isNotNull();
        assertThat(dec(revenueRow.get("byCompany").get(parent.org()))).isEqualByComparingTo("100");
        assertThat(dec(revenueRow.get("byCompany").get("eliminations"))).isEqualByComparingTo("-100");
        JsonNode totals = pl.get("totals");
        assertThat(dec(totals.get("income").get("consolidated"))).isEqualByComparingTo("1000");
        assertThat(dec(totals.get("expense").get("consolidated"))).isEqualByComparingTo("0");
        assertThat(dec(totals.get("netProfit").get("consolidated"))).isEqualByComparingTo("1000");
        assertThat(dec(totals.get("intercompanyDifference"))).isEqualByComparingTo("0");
        // Subsidiary own profit after eliminations 1000 (−100 IC expense eliminated) × 20 % minority.
        assertThat(dec(totals.get("nonControllingInterest"))).isEqualByComparingTo("200");
        assertThat(dec(totals.get("attributableToParent"))).isEqualByComparingTo("800");

        Res subView = call("GET", "/intercompany/consolidation", null, sub.token());
        assertThat(subView.status()).isEqualTo(403);
        assertThat(call("GET", "/intercompany/consolidation?statement=bs", null, parent.token()).status())
                .isEqualTo(400);
    }

    @Test
    @Order(6)
    @DisplayName("authorization: every intercompany route needs a token and its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(IntercompanyController.class)).isEqualTo(19);
    }
}
