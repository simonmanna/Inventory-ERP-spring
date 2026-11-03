package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ACC-0 reproduction: posting dates (finding B13). Payroll ({@code HrPayrollService} approve → {@code Instant.now()})
 * and POS cash movements are not reproduced here — see docs/accounting/ACC-0-verification.md for the disposition.
 */
class Acc0PostingDateIT extends AccountingFixtures {

    String category;
    String supplier;

    @BeforeAll
    void fixtures() {
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        category = ok(201, "POST", "/expense-categories", Map.of("name", "ACC0 dates " + sfx, "ledgerAccountId",
                account(demoOrg, accountCodeOfSection(demoOrg, "operating_expense")))).get("id").asString();
        supplier = demoPartner("ACC0 dates supplier", true);
    }

    LocalDate postingDay(String entryId) {
        return jdbc.sql("SELECT \"postingDate\" FROM \"JournalEntry\" WHERE id = ?").param(entryId)
                .query(java.sql.Timestamp.class).single().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    @Test
    @DisplayName("[B13][owner:accounting][ACC-3] a back-dated credit expense accrues on its expense date")
    void backDatedExpenseAccrual() {
        LocalDate expenseDate = LocalDate.now().minusDays(6);
        String id = demoMoney("POST", "/expenses", Map.of("title", "ACC0 back-dated", "amount", 70, "expenseDate",
                expenseDate.toString(), "paymentType", "CREDIT", "categoryId", category, "supplierId", supplier))
                .get("id").asString();
        List<String> accruals = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'expense_accrual' AND"
                + " \"sourceId\" = ?").param(id).query(String.class).list();
        assertThat(accruals).hasSize(1);
        assertThat(postingDay(accruals.getFirst())).as("accrual posting date").isEqualTo(expenseDate);
    }
}
