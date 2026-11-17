package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.BooksGuard;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17 (ACR-11): the process-costing sub-ledger moves only with the GL — a period with cost and no units to
 * carry it cannot close, an opening carried forward from a close cannot be edited, and a cost recorded without a journal
 * must name the posted entry that already put it in WIP (and cannot claim more than that entry put there).
 */
@ExtendWith(BooksGuard.class)
class ProcessCostingGuardsIT extends MfgTestSupport {

    @Autowired
    PostingService posting;

    String today = LocalDate.now().toString();

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res post(String path, Object body) {
        return call("POST", path, body, key());
    }

    String message(Res r) {
        return r.body().get("message").asString();
    }

    /** A manual journal: Dr WIP / Cr overhead applied — wages someone put in WIP outside process costing. */
    Map<String, Object> wagesToWip(String amount) {
        String applied = mapped("overhead_absorbed");
        return TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).execute(s ->
                posting.post(PostingRequest.of("GEN", Instant.now(), "Wages to WIP", "manual", UUID.randomUUID()
                        .toString(), null, List.of(PostingRequest.Line.debit(wipAccount, amount, "Wages"),
                                PostingRequest.Line.credit(applied, amount, "Wages"))))));
    }

    @Test
    @DisplayName("stranded cost refused; carried opening locked; recorded-only cost needs the entry that put it in WIP")
    void subLedgerMovesOnlyWithTheGl() {
        String applied = mapped("overhead_absorbed");
        String sfx = suffix();
        String processId = ok(201, "POST", "/manufacturing/process-costing/processes", Map.of("code", "KILN-" + sfx,
                "name", "Kiln", "sequence", 1, "method", "weighted_average")).get("id").asString();
        String from = LocalDate.now().withDayOfMonth(1).toString();
        String period = ok(201, "POST", "/manufacturing/process-costing/processes/" + processId + "/periods", Map.of(
                "periodFrom", from, "periodTo", today)).get("id").asString();
        String base = "/manufacturing/process-costing/periods/" + period;
        // the first period of a process may state its opening (go-live)
        ok(200, "PATCH", base + "/units", Map.of("openingUnits", 0, "openingConversionCost", 0));
        Res labour = post(base + "/costs", Map.of("element", "labour", "amount", 1000, "creditAccountId", applied));
        assertThat(labour.status()).as(labour.raw().body()).isIn(200, 201);

        // (1) 1,000 of cost and no units at all: nothing would carry it
        Res stranded = post(base + "/close", null);
        assertThat(stranded.status()).isEqualTo(400);
        assertThat(message(stranded)).isEqualTo("Process KILN-" + sfx + " has 1000 of cost in the period but no"
                + " completed or closing units to carry it: record the units still in process at the period end (closing"
                + " units), or the units lost (abnormal loss), before closing");
        ok(200, "PATCH", base + "/units", Map.of("unitsStarted", 100, "closingUnits", 100, "closingMaterialPct", 100,
                "closingConversionPct", 50));
        Res closed = post(base + "/close", null);
        assertThat(closed.status()).as(closed.raw().body()).isIn(200, 201);
        String next = closed.body().get("nextPeriodId").asString();
        JsonNode carried = ok(200, "GET", "/manufacturing/process-costing/periods/" + next, null);
        assertThat(new BigDecimal(carried.get("openingConversionCost").asString())).as("the 1,000 is carried forward")
                .isEqualByComparingTo("1000");

        // (2) the carried opening is the GL's WIP: not editable; the period's own units still are
        String nextBase = "/manufacturing/process-costing/periods/" + next;
        Res edit = as("PATCH", nextBase + "/units", Map.of("openingConversionCost", 500));
        assertThat(edit.status()).isEqualTo(400);
        assertThat(message(edit)).isEqualTo("The opening work in process was carried forward from the previous closed"
                + " period and cannot be edited");
        ok(200, "PATCH", nextBase + "/units", Map.of("unitsCompleted", 100));

        // (3) recorded only: needs the posted entry that already put the cost in WIP, within what it put there
        Res bare = post(nextBase + "/costs", Map.of("element", "overhead", "amount", 300));
        assertThat(bare.status()).isEqualTo(400);
        assertThat(message(bare)).isEqualTo("A cost without a credit account must name the posted journal entry that"
                + " already put it in work in process (journalEntryId)");
        Map<String, Object> wages = wagesToWip("300");
        String number = (String) wages.get("entryNumber");
        Res tooMuch = post(nextBase + "/costs", Map.of("element", "overhead", "amount", 301, "journalEntryId", number));
        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(message(tooMuch)).isEqualTo("Journal entry " + number + " put 300 into work in process and 0 of it"
                + " is already recorded on process periods; it cannot carry 301 more");
        Res recorded = post(nextBase + "/costs", Map.of("element", "overhead", "amount", 200, "journalEntryId",
                number));
        assertThat(recorded.status()).as(recorded.raw().body()).isIn(200, 201);
        Res again = post(nextBase + "/costs", Map.of("element", "overhead", "amount", 200, "journalEntryId",
                wages.get("id")));
        assertThat(again.status()).isEqualTo(400);
        assertThat(message(again)).endsWith(" put 300 into work in process and 200 of it is already recorded on process"
                + " periods; it cannot carry 200 more");
        String labourEntry = jdbc.sql("SELECT \"entryNumber\" FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'process_costing' AND \"sourceId\" = ? LIMIT 1").params(demoOrg, "KILN-" + sfx + ":"
                        + period).query(String.class).single();
        Res own = post(nextBase + "/costs", Map.of("element", "labour", "amount", 1, "journalEntryId", labourEntry));
        assertThat(own.status()).isEqualTo(400);
        assertThat(message(own)).isEqualTo("Journal entry " + labourEntry + " was posted by process costing; its cost is"
                + " already on a process period");
    }
}
