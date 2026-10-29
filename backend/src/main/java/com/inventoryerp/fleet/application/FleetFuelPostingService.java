package com.inventoryerp.fleet.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.expenses.application.ExpenseCategoriesService;
import com.inventoryerp.expenses.application.ExpensesService;
import com.inventoryerp.expenses.application.ExpensesService.PostingTags;
import com.inventoryerp.fleet.domain.FleetFuelSettings;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The accounting and stock effects of a fuel log (fleet 1.5, ADR-S009 §6), run inside the posting / reversal
 * transaction of {@link FleetFuelLogService}:
 *
 * <ul>
 *   <li><b>Site tank issue</b>: {@link StockService#issue} from the tank location (quantity only, refused when the tank
 *       is short, costed AVCO / FIFO per the product), then Dr fleet fuel expense (vehicle cost centre, dimensions
 *       {@code vehicleId}) / Cr the product's stock account — once per log (posting key {@code fleet:fuel:<id>}).</li>
 *   <li><b>Station, fuel card</b>: Dr fleet fuel expense / Cr fuel-card payable (provider as partner).</li>
 *   <li><b>Station, cash or supplier credit</b>: an expense through the expenses module (category "Fleet fuel"):
 *       cash pays at once (Dr fleet fuel expense / Cr the cash or bank account), credit waits for approval and payment
 *       in Expenses; the expense line always carries the vehicle cost centre ({@link FleetFuelExpenseTagger}).</li>
 * </ul>
 *
 * Accounts come from the mapping keys {@code fleet_fuel_expense} (default 6410) and {@code fleet_fuel_card_payable}
 * (default 2165), created on first use and remappable under Accounting → Account Mapping. Reversal mirrors the entry
 * (posting engine reversal), returns issued stock at its original cost, or voids the expense.
 */
@Service
public class FleetFuelPostingService {

    static final String SOURCE_TYPE = "fleet_fuel_log";
    static final String STOCK_SOURCE = "fleet_fuel_issue";
    static final String STOCK_REVERSAL_SOURCE = "fleet_fuel_issue_reversal";

    /** What a posting did: references and the cost it put on the log. */
    record Effect(String journalEntryId, String expenseId, String stockLedgerCode, BigDecimal stockUnitCost,
            BigDecimal stockValue, BigDecimal unitPrice, BigDecimal amount) {}

    private final FleetDb db;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final StockReversalService stockReversal;
    private final ExpensesService expenses;
    private final ExpenseCategoriesService expenseCategories;
    private final FleetFuelSettingsService settings;

    public FleetFuelPostingService(FleetDb db, PostingService posting, AccountResolverService accounts,
            StockService stock, StockPostingService stockPosting, StockReversalService stockReversal,
            ExpensesService expenses, ExpenseCategoriesService expenseCategories, FleetFuelSettingsService settings) {
        this.db = db;
        this.posting = posting;
        this.accounts = accounts;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.stockReversal = stockReversal;
        this.expenses = expenses;
        this.expenseCategories = expenseCategories;
        this.settings = settings;
    }

    /** Fleet fuel &amp; energy expense account (mapping {@code fleet_fuel_expense}). */
    public String fuelExpenseAccount() {
        return accounts.ensureByCode("6410", "Fleet Fuel & Energy", "operating_expense", "fleet_fuel_expense");
    }

    /** Fuel-card payable (mapping {@code fleet_fuel_card_payable}). */
    public String cardPayableAccount() {
        return accounts.ensureByCode("2165", "Fuel Card Payable", "current_liability", "fleet_fuel_card_payable");
    }

    /** Post the effects of {@code log} (a locked draft) for {@code vehicle}; {@code tank} / {@code card} when used. */
    Effect post(Map<String, Object> log, Map<String, Object> vehicle, Map<String, Object> tank,
            Map<String, Object> card) {
        String id = (String) log.get("id");
        String code = (String) log.get("code");
        Instant at = (Instant) log.get("fuelledAt");
        BigDecimal litres = SqlRows.dec(log, "litres");
        String label = "Fuel " + code + " · " + vehicle.get("code");
        if ("tank".equals(log.get("source"))) {
            StockMove m = StockMove.of((String) tank.get("productId"), (String) tank.get("locationId"), litres)
                    .source(STOCK_SOURCE, id).reference(code).notes(label).moveType("internal_use").date(at).skipGl()
                    .requireAvailable(true);
            m.responsibleById = TenantContext.userId();
            Issued issued = stock.issue(m);
            BigDecimal value = issued.totalValue();
            String entryId = null;
            if (value.signum() > 0) {
                String v = JsNumbers.dec(value);
                Map<String, Object> entry = posting.post(new PostingRequest("INV", at, label + " from tank "
                        + tank.get("code"), null, null, SOURCE_TYPE, id, null, key(id), null, null, null, List.of(
                                expenseLine(v, label, log),
                                Line.credit(stockPosting.stockAccount((String) tank.get("productId"), value), v,
                                        "Fuel issued · tank " + tank.get("code")))));
                entryId = (String) entry.get("id");
            }
            BigDecimal unit = issued.unitCost() == null ? BigDecimal.ZERO : issued.unitCost();
            return new Effect(entryId, null, issued.ledgerCode(), unit, value,
                    unit.setScale(4, RoundingMode.HALF_UP), value.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal amount = SqlRows.dec(log, "amount");
        String method = (String) log.get("paymentMethod");
        if ("fuel_card".equals(method)) {
            String v = JsNumbers.dec(amount);
            String provider = card == null ? null : (String) card.get("providerPartnerId");
            Map<String, Object> entry = posting.post(new PostingRequest("GEN", at, label + " · card "
                    + card.get("maskedNumber"), null, null, SOURCE_TYPE, id, null, key(id), null, null, null, List.of(
                            expenseLine(v, label, log),
                            new Line(cardPayableAccount(), null, v, provider, "Fuel card " + card.get("maskedNumber"),
                                    null, null, null))));
            return new Effect((String) entry.get("id"), null, null, null, null, (BigDecimal) log.get("unitPrice"),
                    amount);
        }
        // cash or supplier credit: an expense document in the expenses module.
        boolean cash = "cash".equals(method);
        String accountId = (String) log.get("paymentAccountId");
        String title = label + (log.get("stationName") == null ? "" : " · " + log.get("stationName"));
        ExpensesService.CreateInput input = new ExpensesService.CreateInput(title,
                FuelEfficiencyPolicy.plain(litres) + " L" + (log.get("receiptNumber") == null ? ""
                        : " · receipt " + log.get("receiptNumber")),
                amount, expenseCategoryId(), at.toString(), (String) log.get("notes"),
                (String) log.get("supplierId"), cash ? "CASH" : "CREDIT", cash ? expensePaymentMethod(accountId) : null,
                cash ? accountId : null, (String) log.get("receiptNumber"));
        PostingTags tags = tags(log);
        // The fleet route owns the idempotent outcome: the expense must not write the caller's operation record.
        Map<String, Object> expense = BusinessOutcome.run(null, () -> expenses.create(input, tags));
        String expenseId = (String) expense.get("id");
        String entryId = db.jdbc().sql("SELECT \"journalEntryId\" FROM \"ExpensePayment\" WHERE \"organizationId\" = ?"
                + " AND \"expenseId\" = ? AND status = 'posted' ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(FleetDb.org(), expenseId).query(String.class).optional().orElse(null);
        return new Effect(entryId, expenseId, null, null, null, (BigDecimal) log.get("unitPrice"), amount);
    }

    /** Undo the effects of a posted {@code log}; returns the reversal journal entry id (null for expenses). */
    String reverse(Map<String, Object> log, String reason) {
        String id = (String) log.get("id");
        String note = "Reversal of fuel log " + log.get("code") + ": " + reason;
        String reversalId = null;
        if ("tank".equals(log.get("source")) && log.get("stockLedgerCode") != null) {
            // Stock back at the cost it left with; the inventory journal is fleet's own entry (reversed below).
            stockReversal.reverseLedgerSource(new LedgerSource(STOCK_SOURCE, id, STOCK_REVERSAL_SOURCE, id,
                    List.of(STOCK_SOURCE), note, null));
        }
        if (log.get("expenseId") != null) {
            String status = db.jdbc().sql("SELECT status::text FROM \"Expense\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(log.get("expenseId"), FleetDb.org()).query(String.class).optional().orElse(null);
            if (status != null && !List.of("VOID", "CANCELLED").contains(status)) {
                BusinessOutcome.run(null, () -> expenses.voidExpense((String) log.get("expenseId"), note));
            }
        } else if (log.get("journalEntryId") != null) {
            String status = db.jdbc().sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(log.get("journalEntryId"), FleetDb.org()).query(String.class)
                    .optional().orElse(null);
            if ("posted".equals(status)) {
                reversalId = (String) posting.reverse((String) log.get("journalEntryId"), Instant.now(), note)
                        .get("id");
            }
        }
        return reversalId;
    }

    /** Cost centre + {@code vehicleId} / {@code fuelLogId} dimensions + the current fuel expense account. */
    PostingTags tags(Map<String, Object> log) {
        return new PostingTags((String) log.get("costCenterId"), null, dimensions(log), fuelExpenseAccount());
    }

    private Line expenseLine(String amount, String label, Map<String, Object> log) {
        return new Line(fuelExpenseAccount(), amount, null, null, label, null, (String) log.get("costCenterId"),
                dimensions(log));
    }

    private static Map<String, Object> dimensions(Map<String, Object> log) {
        return Obj.of("vehicleId", log.get("vehicleId"), "fuelLogId", log.get("id"));
    }

    private static String key(String logId) {
        return "fleet:fuel:" + logId;
    }

    /** CASH for cash / petty-cash accounts, BANK_TRANSFER for bank and mobile-money accounts. */
    private String expensePaymentMethod(String accountId) {
        String category = db.jdbc().sql("SELECT c.key FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id ="
                + " a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?").params(accountId, FleetDb.org())
                .query(String.class).optional().orElse(null);
        return "cash".equals(category) || "petty_cash".equals(category) ? "CASH" : "BANK_TRANSFER";
    }

    /** The "Fleet fuel" expense category (created on first use, ledger = the fuel expense account). */
    private String expenseCategoryId() {
        Map<String, Object> s = settings.current();
        String id = (String) s.get("expenseCategoryId");
        if (id != null && db.exists("ExpenseCategory", id, true)) {
            return id;
        }
        Map<String, Object> created = expenseCategories.create(new ExpenseCategoriesService.CategoryInput(
                "Fleet fuel", "fuel", "Fuel bought at stations for fleet vehicles (fleet fuel logs)",
                fuelExpenseAccount(), true));
        String newId = (String) created.get("id");
        if (newId == null) {
            throw ApiException.badRequest("Could not create the Fleet fuel expense category");
        }
        db.update(FleetFuelSettings.class, (String) s.get("id"), Obj.of("expenseCategoryId", newId));
        return newId;
    }
}
