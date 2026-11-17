package com.inventoryerp.accounting.verification;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.kernel.common.Savepoints;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Accounting controls review 2026-11-17: fiscal periods change only through the close / reopen routes and never
 * overlap (R1, V512), the database refuses lines added to a posted entry and unbalanced posted entries (R2, V513), the
 * API role cannot switch the evidence guards off (R7, V513), and account queries are scoped to the organization (R6).
 */
class Acc0ControlsIT extends AccountingFixtures {

    private String status(String periodId) {
        return jdbc.sql("SELECT status::text FROM \"FiscalPeriod\" WHERE id = ?").param(periodId).query(String.class)
                .single();
    }

    private String message(Res r) {
        return r.body().get("message").asString();
    }

    private void insertLine(String org, String entryId, String accountId, String debit, String credit, int lineNumber) {
        BigDecimal d = new BigDecimal(debit);
        BigDecimal c = new BigDecimal(credit);
        jdbc.sql("INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", debit,"
                        + " credit, \"baseDebit\", \"baseCredit\", \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org, entryId, accountId, d, c, d, c, lineNumber).update();
    }

    private void insertEntry(String org, String id, String status) {
        String journal = jdbc.sql("SELECT id FROM \"Journal\" WHERE \"organizationId\" = ? AND code = 'GEN'")
                .param(org).query(String.class).single();
        jdbc.sql("INSERT INTO \"JournalEntry\" (id, \"organizationId\", \"journalId\", \"entryNumber\","
                        + " \"postingDate\", status, \"postedAt\", description, \"updatedAt\") VALUES (?, ?, ?, ?, now(),"
                        + " ?::\"JournalEntryStatus\", now(), 'controls test', now())")
                .params(id, org, journal, "CTL-" + id.substring(0, 8), status).update();
    }

    private long entries(String id) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE id = ?").param(id).query(Long.class).single();
    }

    // ------------------------------------------------------------------ R1 fiscal periods

    @Test
    @DisplayName("[R1][owner:accounting] PATCH cannot change a period's status; the close / reopen routes can")
    void statusOnlyThroughCloseRoutes() {
        Books b = freshBooks();
        String period = periodFor(b.org(), LocalDate.now().withDayOfMonth(1).toString());
        for (String target : List.of("closed", "locked", "open")) {
            Res patch = as(b, "PATCH", "/fiscal-periods/" + period, Map.of("status", target));
            assertThat(patch.status()).as(patch.raw().body()).isEqualTo(400);
            assertThat(message(patch)).contains("/fiscal-periods/:id/close").contains("/fiscal-periods/:id/reopen");
        }
        assertThat(status(period)).isEqualTo("open");
        JsonNode renamed = okAs(b, 200, "PATCH", "/fiscal-periods/" + period, Map.of("name", "Renamed " + b.code()));
        assertThat(renamed.get("name").asString()).isEqualTo("Renamed " + b.code());
    }

    @Test
    @DisplayName("[R1][owner:accounting] dates move and periods are deleted only while open and free of postings")
    void datesAndDeleteGuarded() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        List<String> ids = monthlyPeriods(b, months);
        String last = ids.get(0);
        String current = ids.get(1);
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");

        // An open period without postings may be re-dated (and back), and an unused one deleted.
        String lastEnd = months[0].plusMonths(1).minusDays(1).toString();
        okAs(b, 200, "PATCH", "/fiscal-periods/" + last, Map.of("endDate", months[0].plusDays(19).toString()));
        okAs(b, 200, "PATCH", "/fiscal-periods/" + last, Map.of("endDate", lastEnd));
        LocalDate next = months[1].plusMonths(1);
        String spare = okAs(b, 201, "POST", "/fiscal-periods", Map.of("name", "Spare " + b.code(), "startDate",
                next.toString(), "endDate", next.plusMonths(1).minusDays(1).toString())).get("id").asString();
        okAs(b, 204, "DELETE", "/fiscal-periods/" + spare, null);

        // Overlapping or inverted periods are refused, by the service and by the database.
        Res overlap = as(b, "POST", "/fiscal-periods", Map.of("name", "Overlap " + b.code(), "startDate",
                months[1].plusDays(10).toString(), "endDate", next.plusDays(10).toString()));
        assertThat(overlap.status()).as(overlap.raw().body()).isEqualTo(400);
        assertThat(message(overlap)).contains("overlap");
        Res inverted = as(b, "POST", "/fiscal-periods", Map.of("name", "Inverted " + b.code(), "startDate",
                next.plusMonths(3).toString(), "endDate", next.plusMonths(2).toString()));
        assertThat(inverted.status()).as(inverted.raw().body()).isEqualTo(400);
        Res moveOnto = as(b, "PATCH", "/fiscal-periods/" + last, Map.of("endDate", months[1].plusDays(3).toString()));
        assertThat(moveOnto.status()).as(moveOnto.raw().body()).isEqualTo(400);
        assertThat(message(moveOnto)).contains("overlap");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\","
                        + " \"endDate\", status, \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'open', now())")
                .params(UUID.randomUUID().toString(), b.org(), "Raw overlap " + b.code(),
                        Timestamp.valueOf(months[1].plusDays(2).atStartOfDay()),
                        Timestamp.valueOf(months[1].plusDays(4).atStartOfDay()))
                .update()).hasStackTraceContaining("overlaps fiscal period");

        // A posted entry in the current or the new range pins the dates and blocks the delete.
        postJe(b, months[1].plusDays(1).toString(), List.of(dr(cash, 30), cr(revenue, 30)));
        Res shrink = as(b, "PATCH", "/fiscal-periods/" + current, Map.of("startDate", months[1].plusDays(5).toString()));
        assertThat(shrink.status()).as(shrink.raw().body()).isEqualTo(400);
        assertThat(message(shrink)).contains("posted journal entries");
        Res delete = as(b, "DELETE", "/fiscal-periods/" + current, null);
        assertThat(delete.status()).as(delete.raw().body()).isEqualTo(400);
        assertThat(message(delete)).contains("posted journal entries");
        LocalDate gap = months[0].minusMonths(1).plusDays(4);
        postJe(b, gap.toString(), List.of(dr(cash, 5), cr(revenue, 5)));
        Res swallow = as(b, "PATCH", "/fiscal-periods/" + last, Map.of("startDate", gap.minusDays(2).toString()));
        assertThat(swallow.status()).as(swallow.raw().body()).isEqualTo(400);
        assertThat(message(swallow)).contains("posted journal entries");

        // A closed period keeps its dates and cannot be deleted; its name may still change.
        String day = months[0].plusDays(3).toString();
        postJe(b, day, List.of(dr(cash, 40), cr(revenue, 40)));
        closePeriod(b, day);
        Res closedMove = as(b, "PATCH", "/fiscal-periods/" + last, Map.of("endDate", months[0].plusDays(9).toString()));
        assertThat(closedMove.status()).as(closedMove.raw().body()).isEqualTo(400);
        assertThat(message(closedMove)).contains("is closed");
        Res closedDelete = as(b, "DELETE", "/fiscal-periods/" + last, null);
        assertThat(closedDelete.status()).as(closedDelete.raw().body()).isEqualTo(400);
        assertThat(message(closedDelete)).contains("only an open period can be deleted");
        okAs(b, 200, "PATCH", "/fiscal-periods/" + last, Map.of("name", "Closed " + b.code()));
        assertThat(status(last)).isEqualTo("closed");
        assertThat(jdbc.sql("SELECT \"deletedAt\" IS NULL FROM \"FiscalPeriod\" WHERE id = ?").param(last)
                .query(Boolean.class).single()).isTrue();
    }

    // ------------------------------------------------------------------ R2 / R7 ledger integrity

    @Test
    @DisplayName("[R2][R7][owner:accounting] no line can be added to a posted entry, even with the purge setting on")
    void noLinesIntoPostedEntry() {
        Books b = freshBooks();
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String entry = postJe(b, LocalDate.now().minusDays(1).toString(), List.of(dr(cash, 25), cr(revenue, 25)));
        TransactionTemplate tx = new TransactionTemplate(txm);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertLine(b.org(), entry, account(b.org(), cash), "10", "0", 3);
            insertLine(b.org(), entry, account(b.org(), revenue), "0", "10", 4);
        })).hasStackTraceContaining("is already posted");
        // R7: the API role is no erp_evidence_purger, so the bypass stays off for it.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL app.evidence_purge = 'on'").update();
            insertLine(b.org(), entry, account(b.org(), cash), "10", "0", 3);
        })).hasStackTraceContaining("is already posted");
        assertThat(lines(entry)).hasSize(2);
    }

    @Test
    @DisplayName("[R2][owner:accounting] a posted entry must balance exactly at commit; a draft only when posted")
    void postedEntriesBalance() {
        Books b = freshBooks();
        String cash = account(b.org(), mappedCode(b.org(), "default_cash"));
        String revenue = account(b.org(), accountCodeOfSection(b.org(), "revenue"));
        TransactionTemplate tx = new TransactionTemplate(txm);

        String unbalanced = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(b.org(), unbalanced, "posted");
            insertLine(b.org(), unbalanced, cash, "100", "0", 1);
            insertLine(b.org(), unbalanced, revenue, "0", "99.99", 2);
        })).hasStackTraceContaining("is unbalanced");
        assertThat(entries(unbalanced)).isZero();

        // Entry inserted as posted, then its lines, in one transaction (PostingService, the POS seed): accepted.
        String balanced = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            insertEntry(b.org(), balanced, "posted");
            insertLine(b.org(), balanced, cash, "100", "0", 1);
            insertLine(b.org(), balanced, revenue, "0", "100", 2);
        });
        assertThat(lines(balanced)).hasSize(2);

        // A draft may be unbalanced while it is a draft; posting it is refused at commit.
        String draft = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            insertEntry(b.org(), draft, "draft");
            insertLine(b.org(), draft, cash, "50", "0", 1);
            insertLine(b.org(), draft, revenue, "0", "49", 2);
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("UPDATE \"JournalEntry\" SET status = 'posted'"
                + " WHERE id = ?").param(draft).update())).hasStackTraceContaining("is unbalanced");
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(draft).query(String.class)
                .single()).isEqualTo("draft");
    }

    @Test
    @DisplayName("[R2][owner:accounting] PostingService still posts inside a savepoint, and reversals still balance")
    void postingInsideSavepoint() {
        Books b = freshBooks();
        String cash = account(b.org(), mappedCode(b.org(), "default_cash"));
        String revenue = account(b.org(), accountCodeOfSection(b.org(), "revenue"));
        AtomicReference<String> id = new AtomicReference<>();
        inTenant(b.org(), b.ownerId(), () -> Savepoints.run(jdbc, () -> id.set((String) posting.post(PostingRequest.of(
                "GEN", Instant.now().minusSeconds(3600), "Savepoint posting", "acc0", UUID.randomUUID().toString(), null,
                List.of(PostingRequest.Line.debit(cash, new BigDecimal("12.5"), "Cash"),
                        PostingRequest.Line.credit(revenue, new BigDecimal("12.5"), "Sale")))).get("id"))));
        assertThat(lines(id.get())).hasSize(2);
        Map<String, Object> reversal = inTenant(b.org(), b.ownerId(), () -> posting.reverse(id.get(), null, null));
        assertThat(lines((String) reversal.get("id"))).hasSize(2);
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(id.get())
                .query(String.class).single()).isEqualTo("reversed");
    }

    // ------------------------------------------------------------------ R6 account tenancy

    @Test
    @DisplayName("[R6][iso][owner:accounting] account code clashes, deletes and mappings stay inside the organization")
    void accountQueriesScopedToOrganization() {
        Books a = freshBooks();
        Books other = freshBooks();
        String category = jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE key = 'cash'").query(String.class).single();
        String code = "T" + UUID.randomUUID().toString().substring(0, 6);
        String mine = okAs(a, 201, "POST", "/accounts", Map.of("code", code, "name", "Till A", "categoryId", category))
                .get("id").asString();
        okAs(other, 201, "POST", "/accounts", Map.of("code", code, "name", "Till B", "categoryId", category));
        Res clash = as(a, "POST", "/accounts", Map.of("code", code, "name", "Till A2", "categoryId", category));
        assertThat(clash.status()).as(clash.raw().body()).isEqualTo(409);
        assertThat(message(clash)).contains("Till A").doesNotContain("Till B");
        assertThat(as(other, "DELETE", "/accounts/" + mine, null).status()).isEqualTo(404);
        String mappedCash = mapped(a.org(), "default_cash");
        JsonNode otherMappings = okAs(other, 200, "GET", "/account-mappings", null);
        assertThat(otherMappings.toString()).doesNotContain(mappedCash);
    }
}
