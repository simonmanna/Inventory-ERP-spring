package com.inventoryerp.fleet.application;

import com.inventoryerp.expenses.application.ExpensePostingTagger;
import com.inventoryerp.expenses.application.ExpensesService.PostingTags;
import com.inventoryerp.fleet.domain.FleetFuelLog;
import com.inventoryerp.kernel.crud.SqlRows;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tags the payment postings of expenses raised by fleet fuel logs (a supplier-credit fuel purchase paid later in
 * Expenses): the expense line gets the vehicle's cost centre, the {@code vehicleId} / {@code fuelLogId} dimensions and
 * the current {@code fleet_fuel_expense} account. Other expenses are left alone. Registered only with ENABLE_FLEET.
 */
@Component
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
public class FleetFuelExpenseTagger implements ExpensePostingTagger {

    private final FleetDb db;
    private final FleetFuelPostingService postings;

    public FleetFuelExpenseTagger(FleetDb db, FleetFuelPostingService postings) {
        this.db = db;
        this.postings = postings;
    }

    @Override
    public PostingTags tagsFor(String expenseId) {
        if (expenseId == null) {
            return null;
        }
        Map<String, Object> log = SqlRows.one(FleetFuelLog.class, db.jdbc().sql("SELECT * FROM \"FleetFuelLog\" WHERE"
                + " \"organizationId\" = ? AND \"expenseId\" = ? AND \"deletedAt\" IS NULL").params(FleetDb.org(),
                        expenseId));
        return log == null ? null : postings.tags(log);
    }
}
