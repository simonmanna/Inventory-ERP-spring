package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Control-account policy (owner decision, V518): manual journal entries, recurring templates, deferral schedules and
 * other user-directed postings (treasury deposits / withdrawals, bank rules) never reach a designated AR / AP control
 * account; sub-ledger (system-context) postings and reversals still do; a control-account adjustment (permission,
 * reason, a partner on every control line, a second approving user, audit trail) is the one manual way in, and keeps
 * the per-partner control reconciliation intact. Each scenario runs in a fresh organization.
 */
class ControlAccountPolicyIT extends AccountingFixtures {

    // ------------------------------------------------------------------ helpers

    String partner(Books b, boolean customer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, b.org(), "CA-" + id.substring(0, 8), (customer ? "Customer " : "Supplier ")
                        + id.substring(0, 4), customer, !customer).update();
        return id;
    }

    /** Another user with the owner's role (Administrator: every permission, journal_entry:control_adjust included). */
    String secondAdmin(Books b) {
        String role = jdbc.sql("SELECT ur.\"A\" FROM \"_UserRoles\" ur WHERE ur.\"B\" = ? LIMIT 1").param(b.ownerId())
                .query(String.class).single();
        String email = "ca-checker-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Checker@12345", role);
        return login(b.code(), email, "Checker@12345");
    }

    /** A user of the books' organization holding only {@code permissions}. */
    String userWith(Books b, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, b.org(), "Limited-" + role, permissions).update();
        String email = "ca-limited-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Limited@12345", role);
        return login(b.code(), email, "Limited@12345");
    }

    static Map<String, Object> line(String accountId, String side, int amount, String partnerId) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("accountId", accountId);
        l.put(side, amount);
        if (partnerId != null) {
            l.put("partnerId", partnerId);
        }
        return l;
    }

    static Map<String, Object> entry(List<Map<String, Object>> lines, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("journalCode", "GEN");
        body.put("date", LocalDate.now().toString());
        body.put("description", "Control-account policy");
        body.put("lines", lines);
        body.putAll(extra);
        return body;
    }

    String controlType(String accountId) {
        return jdbc.sql("SELECT \"controlAccountType\"::text FROM \"Account\" WHERE id = ?").param(accountId)
                .query(String.class).optional().orElse(null);
    }

    long audits(String org, String entity, String entityId, String action) {
        return jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity = ? AND"
                        + " \"entityId\" = ? AND action::text = ?").params(org, entity, entityId, action)
                .query(Long.class).single();
    }

    String status(String entryId) {
        return jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entryId).query(String.class)
                .single();
    }

    // ------------------------------------------------------------------ designation

    @Test
    @DisplayName("[control-accounts] a new organization's AR / AP mapped accounts are designated ar / ap")
    void newOrganizationDesignation() {
        Books b = freshBooks();
        assertThat(controlType(mapped(b.org(), "accounts_receivable"))).isEqualTo("ar");
        assertThat(controlType(mapped(b.org(), "accounts_payable"))).isEqualTo("ap");
    }

    @Test
    @DisplayName("[control-accounts] mapping accounts_receivable designates the account (audited); a draft staged"
            + " before a designation is refused on post")
    void designationByMappingAndAccountEdit() {
        Books b = freshBooks();
        String receivableCategory = jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE key = 'receivable'")
                .query(String.class).single();
        String mappedAr = okAs(b, 201, "POST", "/accounts", Map.of("code", "13" + (int) (Math.random() * 90 + 10)
                + "1", "name", "Second AR", "categoryId", receivableCategory)).get("id").asString();
        assertThat(controlType(mappedAr)).isNull();
        okAs(b, 200, "PUT", "/account-mappings/accounts_receivable", Map.of("accountId", mappedAr));
        assertThat(controlType(mappedAr)).isEqualTo("ar");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'Account' AND \"entityId\" = ? AND"
                + " \"newValues\"->>'controlAccountType' = 'ar' AND \"newValues\"->>'mappingKey' ="
                + " 'accounts_receivable'").param(mappedAr).query(Long.class).single()).isEqualTo(1);

        // An undesignated account takes a manual draft; designating it afterwards (audited) stops the approval.
        String other = okAs(b, 201, "POST", "/accounts", Map.of("code", "13" + (int) (Math.random() * 90 + 10) + "2",
                "name", "Customer clearing", "categoryId", receivableCategory)).get("id").asString();
        String revenue = account(b.org(), accountCodeOfSection(b.org(), "revenue"));
        Res staged = as(b, "POST", "/journal-entries", entry(List.of(line(other, "debit", 50, null),
                line(revenue, "credit", 50, null)), Map.of()));
        assertThat(staged.status()).as(staged.raw().body()).isLessThan(300);
        String draftId = staged.body().get("id").asString();
        okAs(b, 200, "PATCH", "/accounts/" + other, Map.of("controlAccountType", "ar"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'Account' AND \"entityId\" = ? AND"
                + " action::text = 'update' AND \"oldValues\"->>'controlAccountType' IS NULL AND"
                + " \"newValues\"->>'controlAccountType' = 'ar'").param(other).query(Long.class).single()).isEqualTo(1);
        Res post = call("POST", "/journal-entries/" + draftId + "/post", null, secondAdmin(b));
        assertThat(post.status()).isEqualTo(400);
        assertThat(post.body().get("message").asString()).contains("designated accounts receivable control account")
                .contains("control-account adjustment");
        assertThat(status(draftId)).isEqualTo("draft");
    }

    // ------------------------------------------------------------------ enforcement

    @Test
    @DisplayName("[control-accounts] manual entries on AR / AP are refused; other manual entries are unchanged")
    void manualEntriesRefused() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String ap = mapped(b.org(), "accounts_payable");
        String revenue = account(b.org(), accountCodeOfSection(b.org(), "revenue"));
        String expense = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        String bank = mapped(b.org(), "default_bank");
        String customer = partner(b, true);
        String supplier = partner(b, false);

        Res toAr = as(b, "POST", "/journal-entries", entry(List.of(line(ar, "debit", 100, customer),
                line(revenue, "credit", 100, null)), Map.of()));
        assertThat(toAr.status()).isEqualTo(400);
        assertThat(toAr.body().get("message").asString()).contains("designated accounts receivable control account")
                .contains("customer sub-ledger").contains("control-account adjustment");
        Res toAp = as(b, "POST", "/journal-entries", entry(List.of(line(expense, "debit", 40, null),
                line(ap, "credit", 40, supplier)), Map.of()));
        assertThat(toAp.status()).isEqualTo(400);
        assertThat(toAp.body().get("message").asString()).contains("designated accounts payable control account")
                .contains("supplier sub-ledger");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"organizationId\" = ? AND \"accountId\" IN"
                + " (?, ?)").params(b.org(), ar, ap).query(Long.class).single()).isZero();

        Res plain = as(b, "POST", "/journal-entries", entry(List.of(line(expense, "debit", 30, null),
                line(bank, "credit", 30, null)), Map.of()));
        assertThat(plain.status()).as(plain.raw().body()).isLessThan(300);
        assertThat(plain.body().get("controlAdjustment").asBoolean()).isFalse();
        Res approved = call("POST", "/journal-entries/" + plain.body().get("id").asString() + "/post", null,
                secondAdmin(b));
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
    }

    @Test
    @DisplayName("[control-accounts] recurring templates, deferral schedules, treasury and bank rules refuse AR / AP")
    void automationsAndTreasuryRefused() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String ap = mapped(b.org(), "accounts_payable");
        String revenue = account(b.org(), accountCodeOfSection(b.org(), "revenue"));
        String expense = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        String bank = mapped(b.org(), "default_bank");

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", "Monthly AR accrual");
        template.put("frequency", "monthly");
        template.put("startDate", LocalDate.now().toString());
        template.put("mode", "post");
        template.put("lines", List.of(Map.of("accountId", ar, "debit", 25, "description", "Fee"),
                Map.of("accountId", revenue, "credit", 25, "description", "Fee")));
        Res recurring = as(b, "POST", "/recurring-entries", template);
        assertThat(recurring.status()).isEqualTo(400);
        assertThat(recurring.body().get("message").asString()).contains("designated accounts receivable control account");
        // A template can never carry the adjustment flag (unknown property).
        Map<String, Object> flagged = new LinkedHashMap<>(template);
        flagged.put("lines", List.of(Map.of("accountId", expense, "debit", 25), Map.of("accountId", bank, "credit", 25)));
        flagged.put("controlAdjustment", true);
        assertThat(as(b, "POST", "/recurring-entries", flagged).status()).isEqualTo(400);

        Res deferral = as(b, "POST", "/deferrals", Map.of("name", "AR deferral", "kind", "expense", "sourceAccountId",
                ar, "targetAccountId", expense, "totalAmount", 100, "startDate", LocalDate.now().toString(), "months", 2));
        assertThat(deferral.status()).isEqualTo(400);
        assertThat(deferral.body().get("message").asString()).contains("designated accounts receivable control account");

        Res withdraw = moneyAs(b, "POST", "/accounts/cash-flow/withdraw", Map.of("accountId", bank,
                "counterpartAccountId", ap, "operationType", "loan_repayment", "amount", 10, "description", "AP"));
        assertThat(withdraw.status()).isEqualTo(400);
        assertThat(withdraw.body().get("message").asString()).contains("designated accounts payable control account");

        Res rule = as(b, "POST", "/bank-reconciliation/rules", Map.of("bankAccountId", bank, "name", "Customer receipts",
                "pattern", "receipt", "direction", "inbound", "action", "post_entry", "counterAccountId", ar));
        assertThat(rule.status()).isEqualTo(400);
        assertThat(rule.body().get("message").asString()).contains("designated accounts receivable control account");
    }

    @Test
    @DisplayName("[control-accounts] sub-ledger (system) postings and their reversal through the API still reach AR")
    void systemPostingsAndReversalsAllowed() {
        Books b = freshBooks();
        String arCode = mappedCode(b.org(), "accounts_receivable");
        String revenueCode = accountCodeOfSection(b.org(), "revenue");
        String customer = partner(b, true);
        String day = LocalDate.now().toString();
        String entryId = postJe(b, day, List.of(dr(arCode, 70).partner(customer), cr(revenueCode, 70)));
        assertThat(status(entryId)).isEqualTo("posted");
        Res reversed = as(b, "POST", "/journal-entries/" + entryId + "/reverse", null);
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        String reversalId = reversed.body().get("id").asString();
        assertThat(lines(reversalId)).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(arCode);
            assertThat(l.get("partnerId")).isEqualTo(customer);
        });
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);
    }

    // ------------------------------------------------------------------ adjustment workflow

    @Test
    @DisplayName("[control-accounts] adjustment: permission, reason, partner per control line, second user, audit;"
            + " the per-partner AR reconciliation holds afterwards")
    void controlAdjustmentWorkflow() {
        Books b = freshBooks();
        String ar = mapped(b.org(), "accounts_receivable");
        String arCode = mappedCode(b.org(), "accounts_receivable");
        String revenueCode = accountCodeOfSection(b.org(), "revenue");
        String expense = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        String customer = partner(b, true);
        String day = LocalDate.now().toString();

        // A legacy GL-only movement on the customer's AR (no open document behind it): the sub-ledger is out of line.
        postJe(b, day, List.of(dr(arCode, 100).partner(customer), cr(revenueCode, 100)));
        assertThatThrownBy(() -> LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true))
                .isInstanceOf(AssertionError.class);

        String reason = "Write back legacy AR posting without invoice " + UUID.randomUUID().toString().substring(0, 6);
        List<Map<String, Object>> lines = List.of(line(expense, "debit", 100, null), line(ar, "credit", 100, customer));

        Res noReason = as(b, "POST", "/journal-entries", entry(lines, Map.of("controlAdjustment", true)));
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.body().get("message").asString()).contains("requires a reason");
        Res reasonOnly = as(b, "POST", "/journal-entries", entry(List.of(line(expense, "debit", 5, null),
                line(mapped(b.org(), "default_bank"), "credit", 5, null)), Map.of("controlAdjustmentReason", reason)));
        assertThat(reasonOnly.status()).isEqualTo(400);
        Res noPartner = as(b, "POST", "/journal-entries", entry(List.of(line(expense, "debit", 100, null),
                line(ar, "credit", 100, null)), Map.of("controlAdjustment", true, "controlAdjustmentReason", reason)));
        assertThat(noPartner.status()).isEqualTo(400);
        assertThat(noPartner.body().get("message").asString()).contains("needs a partner");
        Res noControlLine = as(b, "POST", "/journal-entries", entry(List.of(line(expense, "debit", 5, null),
                line(mapped(b.org(), "default_bank"), "credit", 5, null)),
                Map.of("controlAdjustment", true, "controlAdjustmentReason", reason)));
        assertThat(noControlLine.status()).isEqualTo(400);
        assertThat(noControlLine.body().get("message").asString()).contains("at least one designated");
        Res unknownPartner = as(b, "POST", "/journal-entries", entry(List.of(line(expense, "debit", 100, null),
                line(ar, "credit", 100, UUID.randomUUID().toString())),
                Map.of("controlAdjustment", true, "controlAdjustmentReason", reason)));
        assertThat(unknownPartner.status()).isEqualTo(400);

        String plainMaker = userWith(b, "journal_entry:read", "journal_entry:create");
        Res forbidden = call("POST", "/journal-entries", entry(lines, Map.of("controlAdjustment", true,
                "controlAdjustmentReason", reason)), plainMaker);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.body().get("message").asString()).contains("journal_entry:control_adjust");

        Res staged = as(b, "POST", "/journal-entries", entry(lines, Map.of("controlAdjustment", true,
                "controlAdjustmentReason", "  " + reason + "  ")));
        assertThat(staged.status()).as(staged.raw().body()).isLessThan(300);
        String id = staged.body().get("id").asString();
        assertThat(staged.body().get("status").asString()).isEqualTo("draft");
        assertThat(staged.body().get("controlAdjustment").asBoolean()).isTrue();
        assertThat(staged.body().get("controlAdjustmentReason").asString()).isEqualTo(reason);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'JournalEntry' AND \"entityId\" = ? AND"
                + " action::text = 'create' AND \"newValues\"->>'reason' = ? AND (\"newValues\"->'partners') @>"
                + " jsonb_build_array(?::text) AND (\"newValues\"->'controlAccounts') @> jsonb_build_array(?::text)")
                .params(id, reason, customer, arCode)
                .query(Long.class).single()).isEqualTo(1);

        Res ownPost = as(b, "POST", "/journal-entries/" + id + "/post", null);
        assertThat(ownPost.status()).isEqualTo(403);
        String postOnly = userWith(b, "journal_entry:read", "journal_entry:post");
        Res noAdjustRight = call("POST", "/journal-entries/" + id + "/post", null, postOnly);
        assertThat(noAdjustRight.status()).isEqualTo(403);
        assertThat(noAdjustRight.body().get("message").asString()).contains("journal_entry:control_adjust");
        assertThat(status(id)).isEqualTo("draft");

        Res posted = call("POST", "/journal-entries/" + id + "/post", null, secondAdmin(b));
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("posted");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'JournalEntry' AND \"entityId\" = ? AND"
                + " action::text = 'post' AND \"newValues\"->>'reason' = ? AND \"newValues\"->>'postedBy' IS NOT NULL"
                + " AND \"newValues\"->>'totalBaseCredit' = '100'").params(id, reason).query(Long.class).single())
                .isEqualTo(1);

        // Every control line carries the partner, so the customer's AR GL equals its open items again.
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), customer, true);
        LedgerInvariants.trialBalanceBalanced(tbAsOf(b.org(), day, TbView.POST_CLOSING));

        JsonNode detail = okAs(b, 200, "GET", "/journal-entries/" + id, null);
        assertThat(detail.get("controlAdjustment").asBoolean()).isTrue();
        assertThat(detail.get("controlAdjustmentReason").asString()).isEqualTo(reason);
        boolean partnerShown = false;
        for (JsonNode l : detail.get("lines")) {
            if (ar.equals(l.get("accountId").asString())) {
                assertThat(l.get("partner").get("id").asString()).isEqualTo(customer);
                partnerShown = true;
            }
        }
        assertThat(partnerShown).isTrue();
        JsonNode list = okAs(b, 200, "GET", "/journal-entries?pageSize=200", null);
        boolean listed = false;
        for (JsonNode row : list.get("data")) {
            if (id.equals(row.get("id").asString())) {
                assertThat(row.get("controlAdjustment").asBoolean()).isTrue();
                listed = true;
            }
        }
        assertThat(listed).isTrue();

        // Reversing the adjustment is allowed (partners mirrored) and audited.
        Res reversed = as(b, "POST", "/journal-entries/" + id + "/reverse", null);
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        String reversalId = reversed.body().get("id").asString();
        assertThat(audits(b.org(), "JournalEntry", reversalId, "post")).isEqualTo(1);
        assertThat(lines(reversalId)).anySatisfy(l -> {
            assertThat(l.get("code")).isEqualTo(arCode);
            assertThat(l.get("partnerId")).isEqualTo(customer);
        });
    }

    @Test
    @DisplayName("[control-accounts] an AP adjustment between two suppliers keeps each supplier's AP reconciliation")
    void payableAdjustmentBetweenPartners() {
        Books b = freshBooks();
        String ap = mapped(b.org(), "accounts_payable");
        String apCode = mappedCode(b.org(), "accounts_payable");
        String expenseCode = accountCodeOfCategory(b.org(), "operating_expense");
        String wrong = partner(b, false);
        String right = partner(b, false);
        String day = LocalDate.now().toString();
        // A legacy GL-only credit booked to the wrong supplier, and the same amount owed by the right one as a debit
        // residual: neither supplier's AP ties to its open items.
        postJe(b, day, List.of(dr(expenseCode, 60), cr(apCode, 60).partner(wrong)));
        postJe(b, day, List.of(dr(apCode, 60).partner(right), cr(expenseCode, 60)));
        assertThatThrownBy(() -> LedgerInvariants.partnerControlReconciles(jdbc, b.org(), wrong, false))
                .isInstanceOf(AssertionError.class);

        Res staged = as(b, "POST", "/journal-entries", entry(List.of(line(ap, "debit", 60, wrong),
                line(ap, "credit", 60, right)), Map.of("controlAdjustment", true, "controlAdjustmentReason",
                "Reallocate supplier balance booked to the wrong supplier")));
        assertThat(staged.status()).as(staged.raw().body()).isLessThan(300);
        Res posted = call("POST", "/journal-entries/" + staged.body().get("id").asString() + "/post", null,
                secondAdmin(b));
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), wrong, false);
        LedgerInvariants.partnerControlReconciles(jdbc, b.org(), right, false);
    }
}
