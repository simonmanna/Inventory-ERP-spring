package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who reverses a posted journal entry. The generic {@code POST /journal-entries/{id}/reverse} and the
 * {@code journal_entry} workflow's {@code reverse} transition accept only (finance readiness audit A2, owner decision
 * 2026-11-23, widening the "document reversals" decision of 2026-11-17):
 * <ul>
 *   <li>manual journal entries (no source type); reversing a control-account adjustment also needs
 *       {@code journal_entry:control_adjust};</li>
 *   <li>the sources of {@link AccountingCatalogs#GENERIC_REVERSAL_SOURCE_TYPES} ({@code recurring_entry}, from
 *       {@code packages/shared/src/accounting/journal-reversal.ts}).</li>
 * </ul>
 * Every other source is refused, unknown and future ones included. The document or process that posted the entry keeps
 * records of it (a sub-ledger residual, a closed period, a VAT lock, an asset register, a payroll run, a stock valuation,
 * a cash session, …) that a bare GL reversal would leave out of step. The 400 and the journal-entry detail
 * ({@code sourceDocument}) name the action that reverses the entry, or an adjusting manual journal entry where the
 * process has no reversal of its own.
 *
 * <p>The internal {@link PostingService#reverse} stays unrestricted: the document and process workflows call it in their
 * own transaction. A {@code reversal} entry is followed back to its original: undoing the reversal of an invoice would
 * re-open its receivable behind the cancelled document's back, so it is refused the same way.
 */
@Component
public class SubledgerSources {

    /**
     * One kind of document or process that posts journal entries.
     *
     * @param documentType stable key ({@code sales_invoice}, {@code period_close}, …)
     * @param label        human name of the document or process
     * @param table        table holding the document ({@code null}: no single row to name)
     * @param numberColumn its number column; with {@code byNumber}, the column the entry's source id matches
     * @param action       what reverses it (or corrects it, when the process has no reversal of its own)
     * @param route        API route of that action ({@code {id}} = the document id), {@code null} when there is none
     * @param webPath      web page of the document ({@code {id}} = the document id), {@code null} when there is none
     * @param subledger    a customer / supplier sub-ledger document (owner decision 2026-11-17)
     * @param byNumber     the entry's source id is the document's number ({@code numberColumn}), not its id
     */
    public record Kind(String documentType, String label, String table, String numberColumn, String action,
            String route, String webPath, boolean subledger, boolean byNumber) {

        /** A customer / supplier sub-ledger document whose entries carry its id as source id. */
        Kind(String documentType, String label, String table, String numberColumn, String action, String route,
                String webPath) {
            this(documentType, label, table, numberColumn, action, route, webPath, true, false);
        }
    }

    /** The document or process behind one journal entry. */
    public record SourceDocument(Kind kind, String sourceType, String documentId, String documentNumber,
            boolean viaReversal) {

        /** The action's route, with the document id filled in when it is known. */
        public String route() {
            if (kind.route() == null) {
                return null;
            }
            return documentId == null ? kind.route() : kind.route().replace("{id}", documentId);
        }

        /** The document's web page, {@code null} when it has none or needs an id that is not known. */
        public String path() {
            if (kind.webPath() == null) {
                return null;
            }
            if (!kind.webPath().contains("{id}")) {
                return kind.webPath();
            }
            return documentId == null ? null : kind.webPath().replace("{id}", documentId);
        }

        /** Additive {@code sourceDocument} block of the journal-entry detail (web: hide Reverse, link the document). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentType", kind.documentType());
            m.put("label", kind.label());
            m.put("id", documentId);
            m.put("number", documentNumber);
            m.put("sourceType", sourceType);
            m.put("action", kind.action());
            m.put("route", route());
            m.put("path", path());
            m.put("viaReversal", viaReversal);
            m.put("genericReversal", false);
            return m;
        }
    }

    // ------------------------------------------------------------------ customer / supplier sub-ledgers (2026-11-17)

    static final Kind SALES_INVOICE = new Kind("sales_invoice", "sales invoice", "Document", "documentNumber",
            "cancel the invoice", "POST /invoices/{id}/cancel", "/invoices/{id}");
    static final Kind CREDIT_NOTE = new Kind("credit_note", "credit note", "Document", "documentNumber",
            "reverse the credit note", "POST /credit-notes/{id}/reverse", "/credit-notes/{id}");
    static final Kind VENDOR_BILL = new Kind("vendor_bill", "supplier bill", "Document", "documentNumber",
            "void the bill", "POST /vendor-bills/{id}/cancel", "/vendor-bills/{id}");
    static final Kind VENDOR_BILL_WRITEOFF = new Kind("vendor_bill_writeoff", "supplier bill write-off", "Document",
            "documentNumber", "reverse the write-off on the bill (Write-offs → Reverse)",
            "POST /vendor-bills/{id}/write-offs/{entryId}/reverse", "/vendor-bills/{id}");
    static final Kind DEBIT_NOTE = new Kind("debit_note", "debit note", "DebitNote", "noteNumber",
            "reverse the debit note", "PATCH /procurement/debit-notes/{id}/reverse", "/procurement/debit-notes");
    static final Kind PAYMENT = new Kind("payment", "payment", "Payment", "paymentNumber",
            "void the payment (a deposit application is reversed from its customer deposit:"
            + " POST /customer-deposits/{depositId}/applications/{paymentId}/reverse)",
            "POST /payments/{id}/void", "/payments/{id}");
    static final Kind CUSTOMER_DEPOSIT = new Kind("customer_deposit", "customer deposit", "CustomerDeposit", "number",
            "void or refund the deposit", "POST /customer-deposits/{id}/void", null);
    static final Kind POS_INVOICE = new Kind("pos_invoice", "POS invoice", "Invoice", "invoiceNumber",
            "refund the POS invoice", "POST /pos/invoices/{id}/refund", null);
    static final Kind POS_REFUND = new Kind("pos_refund", "POS refund", "PosRefund", null,
            "issue a new sale (a POS refund is itself the reversal of its invoice)", null, null);
    static final Kind SUPPLIER_CLAIM = new Kind("supplier_claim", "supplier claim", "SupplierClaim", "claimNumber",
            "correct the supplier balance with a debit note or a control-account adjustment", null, null);
    static final Kind GOODS_RECEIPT = new Kind("goods_receipt", "goods receipt", "GoodsReceiptNote", "receiptNumber",
            "reverse the goods receipt", "PATCH /procurement/goods-receipts/{id}/reverse",
            "/procurement/goods-receipts/{id}");
    static final Kind PURCHASE_ORDER = new Kind("purchase_order", "purchase order payment", "PurchaseOrder",
            "orderNumber", "correct it on the purchase order or with a control-account adjustment", null,
            "/procurement/purchase-orders/{id}");
    static final Kind CASH_SALE = new Kind("cash_sale", "cash sale", "SalesSubmission", "number",
            "void the cash sale", "POST /sales/cash-sales/{id}/void", "/sales/cash-sales/{id}");
    static final Kind EXPENSE = new Kind("expense", "expense", "Expense", "expenseCode", "void the expense",
            "POST /expenses/{id}/void", "/expenses");
    static final Kind OPENING_BALANCE = new Kind("opening_balance", "opening-balance batch", null, null,
            "reverse the whole batch (its invoices and bills are cancelled with it)",
            "POST /opening-balances/{id}/reverse", "/opening-balances");

    /** sourceType → document kind. Every sourceType a customer / supplier sub-ledger posts under. */
    private static final Map<String, Kind> SUBLEDGER = Map.ofEntries(
            Map.entry("invoice", SALES_INVOICE),
            Map.entry("sales_invoice", SALES_INVOICE),
            Map.entry("sales_invoice_cancel", SALES_INVOICE),
            Map.entry("credit_note", CREDIT_NOTE),
            Map.entry("credit_note_fx", CREDIT_NOTE),
            Map.entry("credit_note_cancel", CREDIT_NOTE),
            Map.entry("vendor_bill", VENDOR_BILL),
            Map.entry("vendor_bill_cancel", VENDOR_BILL),
            Map.entry("vendor_bill_writeoff", VENDOR_BILL_WRITEOFF),
            Map.entry("debit_note", DEBIT_NOTE),
            Map.entry("debit_note_reversal", DEBIT_NOTE),
            Map.entry("payment", PAYMENT),
            Map.entry("payment_allocation", PAYMENT),
            Map.entry("customer_deposit", CUSTOMER_DEPOSIT),
            Map.entry("pos_invoice", POS_INVOICE),
            Map.entry("pos_invoice_writeoff", POS_INVOICE),
            Map.entry("pos_refund", POS_REFUND),
            Map.entry("supplier_claim", SUPPLIER_CLAIM),
            Map.entry("goods_receipt", GOODS_RECEIPT),
            Map.entry("purchase_order", PURCHASE_ORDER),
            Map.entry("cash_sale", CASH_SALE),
            Map.entry("expense_accrual", EXPENSE),
            Map.entry("expense_payment", EXPENSE),
            Map.entry("opening_balance", OPENING_BALANCE));

    // ------------------------------------------------------------------ other processes (finance readiness audit A2)

    /** What corrects an entry whose process has no reversal of its own. */
    static final String ADJUSTING_JOURNAL = "correct it with an adjusting manual journal entry";

    private static Kind process(String type, String label, String table, String numberColumn, String action,
            String route, String webPath) {
        return new Kind(type, label, table, numberColumn, action, route, webPath, false, false);
    }

    /** A process whose entries carry the document's number as source id ({@code numberColumn} finds its id). */
    private static Kind processByNumber(String type, String label, String table, String numberColumn, String action,
            String route) {
        return new Kind(type, label, table, numberColumn, action, route, null, false, true);
    }

    static final Kind PERIOD_CLOSE = process("period_close", "period close", "FiscalPeriod", "name",
            "reopen the fiscal period", "POST /fiscal-periods/{id}/reopen", "/fiscal-periods");
    // The entry's source id is the settled period's end date, not the settlement's id: the route names its parameter.
    static final Kind VAT_SETTLEMENT = process("tax_settlement", "VAT settlement", null, null,
            "undo the latest VAT settlement (finance audit A3)", "POST /tax-settlements/{settlementId}/reverse",
            "/period-end");
    static final Kind FX_REVALUATION = process("fx_revaluation", "FX revaluation", null, null,
            "run the FX revaluation again for the date; a re-run posts only the difference", "POST /fx-revaluations",
            null);
    static final Kind ASSET_ACQUISITION = process("asset_acquisition", "asset capitalisation", null, null,
            "remove the asset's acquisition record", "DELETE /fixed-assets/{id}/acquisition", null);
    static final Kind ASSET_IMPAIRMENT = process("asset_impairment", "asset impairment", null, null,
            "record an impairment reversal on the asset", "POST /fixed-assets/{id}/impairments/reversal", null);
    static final Kind BANK_MATCH = process("bank_statement_line", "bank statement match", null, null,
            "undo the bank match", "POST /bank-reconciliation/unmatch/{id}", "/bank-reconciliation");
    static final Kind COST_CENTER_ALLOCATION = process("cost_allocation", "cost-centre allocation run", null, null,
            "reverse the allocation run", "POST /cost-centers/allocation-runs/{id}/reverse", null);
    static final Kind COSTING_ALLOCATION = process("cost_allocation", "costing allocation run", "AllocationRun",
            "number", "reverse the allocation run", "POST /costing/allocation-runs/{id}/reverse", null);
    static final Kind INVENTORY_REVALUATION = process("inventory_revaluation", "inventory revaluation", null, null,
            "reverse the revaluation", "POST /inventory/revaluations/{id}/reverse", null);
    static final Kind PRODUCTION_STAGE = process("production_stage", "production stage posting", null, null,
            "reverse the stage posting", "POST /manufacturing/stage-postings/{id}/reverse", null);
    static final Kind LANDED_COST_ACCRUAL = process("landed_cost_accrual", "landed-cost accrual", null, null,
            "reverse the accrual (only while nothing of it is cleared)",
            "POST /procurement/landed-cost-accruals/{id}/reverse", null);
    static final Kind MATERIAL_LEDGER_RUN = process("material_ledger_run", "material-ledger run", null, null,
            "reverse the run", "POST /costing/material-ledger/runs/{id}/reverse", null);
    static final Kind ABSORPTION_SETTLEMENT = process("absorption_settlement", "absorption settlement", null, null,
            "reverse the settlement", "POST /costing/absorption-settlements/{id}/reverse", null);
    static final Kind COST_ADJUSTMENT_RUN = process("cost_adjustment_run", "cost adjustment run", null, null,
            "reverse the run", "POST /costing/cost-adjustment-runs/{id}/reverse", null);
    static final Kind DELIVERY_COST_RUN = process("delivery_cost_run", "delivery cost run", null, null,
            "reverse the run", "POST /costing/delivery-cost-runs/{id}/reverse", null);
    static final Kind QUARRY_STRIPPING = process("quarry_stripping", "quarry stripping movement", null, null,
            "reverse the movement (the latest one first)", "POST /quarry/stripping-movements/{id}/reverse", null);
    static final Kind QUARRY_REHAB = process("quarry_rehab", "quarry rehabilitation movement", null, null,
            "reverse the movement", "POST /quarry/rehab-movements/{id}/reverse", null);
    static final Kind QUARRY_EXTRACTION = process("quarry_extraction_log", "quarry extraction log", null, null,
            "reverse the extraction log", "POST /quarry/extraction-logs/{id}/reverse", null);
    static final Kind EXPENSE_PERIOD_ACCRUAL = process("expense_period_accrual", "expense accrual", null, null,
            "reverse the accrual", "POST /expense-accruals/{id}/reverse", null);
    static final Kind EXPENSE_PREPAID_RELEASE = process("expense_prepaid_release", "prepaid expense release", null, null,
            "void the expense", "POST /expenses/{id}/void", "/expenses");
    static final Kind FLEET_DRIVER_CLAIM = process("fleet_driver_claim", "driver claim", null, null,
            "reverse the claim", "POST /fleet/claims/{id}/reverse", null);
    static final Kind FLEET_FUEL_LOG = process("fleet_fuel_log", "fuel log", null, null,
            "reverse the fuel log", "POST /fleet/fuel-logs/{id}/reverse", null);
    static final Kind FLEET_WORK_ORDER = process("fleet_work_order", "fleet work order", null, null,
            "reverse the work-order line", "POST /fleet/work-orders/{id}/lines/{lineId}/reverse", null);
    static final Kind FLEET_FREIGHT_BILL = process("fleet_freight_bill", "freight bill", null, null,
            "cancel the freight bill", "POST /fleet/freight-bills/{id}/cancel", null);
    static final Kind PAYROLL_RUN = process("payroll_run", "payroll run", null, null,
            "reverse the payroll run", "POST /hr/payroll/runs/{id}/reverse", "/hr/payroll/runs/{id}");
    static final Kind PROJECT_COST = process("project_cost", "project cost line", null, null,
            "reverse the project cost line", "POST /projects/costs/{id}/reverse", null);
    static final Kind PROJECT_OVERHEAD = process("project_overhead", "project overhead run", null, null,
            "reverse the overhead run", "POST /projects/overhead-runs/{id}/reverse", null);
    static final Kind REVENUE_RECOGNITION = process("revenue_recognition", "revenue recognition run", null, null,
            "reverse the revenue run", "POST /projects/revenue-runs/{id}/reverse", null);
    static final Kind PROJECT_CAPITALISATION = process("project_capitalisation", "project capitalisation", null, null,
            "reverse the capitalisation", "POST /projects/capitalisations/{id}/reverse", null);
    static final Kind PROJECT_TIMESHEET = processByNumber("project_timesheet", "project timesheet batch", null, null,
            "reverse any cost line of the batch (the batch entry is reversed as a whole)",
            "POST /projects/costs/{costLineId}/reverse");
    static final Kind PRODUCTION_ORDER = processByNumber("production_order", "production order", "ProductionOrder",
            "orderCode", "reverse the production order", "POST /manufacturing/production-orders/{id}/reverse");
    static final Kind PRODUCTION_VARIANCE = processByNumber("production_variance", "production variance", null, null,
            "reverse the production order it belongs to, or " + ADJUSTING_JOURNAL,
            "POST /manufacturing/production-orders/{id}/reverse");
    static final Kind LANDED_COST = processByNumber("landed_cost", "landed cost", "LandedCost", "code",
            "reverse the landed cost", "POST /procurement/landed-costs/{id}/reverse");
    static final Kind LANDED_COST_VOUCHER = processByNumber("landed_cost_voucher", "landed-cost voucher", null, null,
            "reverse the voucher", "POST /procurement/landed-cost-vouchers/{id}/reverse");
    static final Kind STOCK_OUT = processByNumber("stock_out", "stock-out", "StockOut", "outCode",
            "reverse the stock-out", "POST /inventory/stock-outs/{id}/reverse");
    static final Kind WASTE = processByNumber("waste", "waste record", "WasteRecord", "wasteCode",
            "reverse the waste record", "POST /inventory/waste/{id}/reverse");
    static final Kind STOCK_ADJUSTMENT = processByNumber("stock_adjustment", "stock adjustment", "StockAdjustment",
            "adjCode", "reverse the adjustment", "POST /inventory/adjustments/{id}/reverse");
    static final Kind STOCK_TRANSFER = processByNumber("stock_transfer", "stock transfer", "StockTransfer",
            "transferCode", "reverse the transfer", "POST /inventory/transfers/{id}/reverse");

    /** sourceType → process kind, for processes other than the sub-ledgers ({@code cost_allocation}: see kindFor). */
    private static final Map<String, Kind> PROCESSES = Map.ofEntries(
            Map.entry("period_close", PERIOD_CLOSE),
            Map.entry("tax_settlement", VAT_SETTLEMENT),
            Map.entry("fx_revaluation", FX_REVALUATION),
            Map.entry("asset_acquisition", ASSET_ACQUISITION),
            Map.entry("asset_impairment", ASSET_IMPAIRMENT),
            Map.entry("bank_statement_line", BANK_MATCH),
            Map.entry("cost_allocation", COST_CENTER_ALLOCATION),
            Map.entry("inventory_revaluation", INVENTORY_REVALUATION),
            Map.entry("inventory_nrv", INVENTORY_REVALUATION),
            Map.entry("production_stage", PRODUCTION_STAGE),
            Map.entry("landed_cost_accrual", LANDED_COST_ACCRUAL),
            Map.entry("material_ledger_run", MATERIAL_LEDGER_RUN),
            Map.entry("absorption_settlement", ABSORPTION_SETTLEMENT),
            Map.entry("cost_adjustment_run", COST_ADJUSTMENT_RUN),
            Map.entry("delivery_cost_run", DELIVERY_COST_RUN),
            Map.entry("quarry_stripping", QUARRY_STRIPPING),
            Map.entry("quarry_rehab", QUARRY_REHAB),
            Map.entry("quarry_extraction_log", QUARRY_EXTRACTION),
            Map.entry("expense_period_accrual", EXPENSE_PERIOD_ACCRUAL),
            Map.entry("expense_prepaid_release", EXPENSE_PREPAID_RELEASE),
            Map.entry("fleet_driver_claim", FLEET_DRIVER_CLAIM),
            Map.entry("fleet_fuel_log", FLEET_FUEL_LOG),
            Map.entry("fleet_work_order", FLEET_WORK_ORDER),
            Map.entry("fleet_freight_bill", FLEET_FREIGHT_BILL),
            Map.entry("payroll_run", PAYROLL_RUN),
            Map.entry("project_issue", PROJECT_COST),
            Map.entry("project_equipment", PROJECT_COST),
            Map.entry("project_reclass", PROJECT_COST),
            Map.entry("project_timesheet", PROJECT_TIMESHEET),
            Map.entry("project_overhead", PROJECT_OVERHEAD),
            Map.entry("revenue_recognition", REVENUE_RECOGNITION),
            Map.entry("project_capitalisation", PROJECT_CAPITALISATION),
            Map.entry("production_order", PRODUCTION_ORDER),
            Map.entry("production_variance", PRODUCTION_VARIANCE),
            Map.entry("landed_cost", LANDED_COST),
            Map.entry("landed_cost_voucher", LANDED_COST_VOUCHER),
            Map.entry("stock_out", STOCK_OUT),
            Map.entry("waste", WASTE),
            Map.entry("stock_adjustment", STOCK_ADJUSTMENT),
            Map.entry("stock_adjust", STOCK_ADJUSTMENT),
            Map.entry("stock_transfer", STOCK_TRANSFER));

    /**
     * Sources whose process has no reversal of its own (2026-11-23 sweep): an adjusting manual journal entry corrects
     * them. Labels default to the source type in words.
     */
    private static final Set<String> CORRECTED_BY_JOURNAL = Set.of("asset_depreciation", "asset_disposal",
            "asset_revaluation", "cash_session_opening", "cash_movement", "cash_session_banking", "cash_session_variance",
            "tender_settlement", "treasury_transfer", "cash_flow_deposit", "cash_flow_withdrawal", "fx_designation",
            "deferral", "deferral_release", "deposit_classification", "payroll_payment", "payroll_remittance",
            "hr_separation", "hr_separation_payment", "hr_gratuity", "intercompany_journal", "store_credit_issue",
            "rebate_accrual", "petty_cash_replenish", "petty_cash_count", "expense_advance", "process_costing",
            "cost_policy", "cost_release", "lineage_exception", "standard_cost", "product_cost_price", "branch_transfer",
            "quality_downgrade", "fleet_tyre", "progress_claim", "project_subcontract", "project_back_charge",
            "onerous_contract", "project_bond", "quarry_explosive_issue", "landed_cost_accrual_clearing",
            "demo_journal");

    private static final Map<String, String> LABELS = Map.of("fx_designation", "FX designation",
            "hr_separation", "employee separation", "hr_separation_payment", "separation payment",
            "hr_gratuity", "gratuity provision", "store_credit_issue", "store credit issue",
            "cash_flow_deposit", "cash-flow deposit", "cash_flow_withdrawal", "cash-flow withdrawal",
            "demo_journal", "demo data journal");

    /** The longest reversal chain {@link #resolve} follows back to its original. */
    private static final int MAX_HOPS = 32;

    /** Every sourceType a customer / supplier sub-ledger posts under. */
    public static Set<String> sourceTypes() {
        return SUBLEDGER.keySet();
    }

    /** Every kind with a route (tests check that each route a refusal names is served). */
    public static Set<Kind> kindsWithRoutes() {
        return Stream.concat(Stream.concat(SUBLEDGER.values().stream(), PROCESSES.values().stream()),
                Stream.of(COSTING_ALLOCATION)).filter(k -> k.route() != null).collect(Collectors.toUnmodifiableSet());
    }

    /** The sub-ledger document kind posting under {@code sourceType}; {@code null} for any other source. */
    public static Kind kindOf(String sourceType) {
        return sourceType == null ? null : SUBLEDGER.get(sourceType);
    }

    /** Whether {@code sourceType} belongs to a customer / supplier sub-ledger. */
    public static boolean isSubledger(String sourceType) {
        return kindOf(sourceType) != null;
    }

    /** The document-level action hint for a sub-ledger {@code sourceType}; {@code null} for any other source. */
    public static String documentAction(String sourceType) {
        Kind k = kindOf(sourceType);
        return k == null ? null : k.action();
    }

    /** Whether the generic reversal accepts an entry of {@code sourceType} itself (a reversal: see its original). */
    public static boolean genericallyReversible(String sourceType) {
        return sourceType == null || AccountingCatalogs.GENERIC_REVERSAL_SOURCE_TYPES.contains(sourceType);
    }

    private final JdbcClient jdbc;

    public SubledgerSources(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The document or process behind entry {@code journalEntryId} (a {@code reversal} entry is followed back to its
     * original), or {@code null} when the generic reversal accepts it: a manual entry, an allow-listed source, or no
     * such entry (the reversal then answers 404).
     */
    public SourceDocument resolve(String journalEntryId) {
        String org = TenantContext.organizationId();
        String id = journalEntryId;
        boolean viaReversal = false;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            Map<String, Object> e = jdbc.sql("SELECT \"sourceType\", \"sourceId\", \"reversalOfId\" FROM \"JournalEntry\""
                            + " WHERE id = ? AND \"organizationId\" = ?").params(id, org).query().listOfRows().stream()
                    .findFirst().orElse(null);
            if (e == null) {
                // no such entry (404 from the reversal itself); an original that cannot be found is refused
                return hop == 0 ? null : unresolved(viaReversal);
            }
            String sourceType = (String) e.get("sourceType");
            String reversalOf = (String) e.get("reversalOfId");
            if (AccountingCatalogs.REVERSAL_SOURCE_TYPE.equals(sourceType) || (sourceType == null && reversalOf != null)) {
                if (reversalOf == null) {
                    return unresolved(true);
                }
                id = reversalOf;
                viaReversal = true;
                continue;
            }
            if (genericallyReversible(sourceType)) {
                return null;
            }
            String sourceId = (String) e.get("sourceId");
            return document(kindFor(sourceType, sourceId, org), sourceType, sourceId, org, viaReversal);
        }
        return unresolved(viaReversal);
    }

    /**
     * The full guard of the generic reversal routes ({@code POST /journal-entries/{id}/reverse}, the
     * {@code journal_entry} workflow): the automatic reversal of an FX revaluation (FX-1), any source outside the
     * allowlist (400 naming the action that reverses it), and a control-account adjustment without
     * {@code journal_entry:control_adjust} (403).
     */
    public void assertGenericReversible(String journalEntryId) {
        String org = TenantContext.organizationId();
        // FX-1: the automatic reversal of an FX revaluation belongs to the revaluation lifecycle; reversing it by hand
        // would turn an unrealised gain or loss into a permanent one. Re-run the revaluation instead.
        Boolean revaluationReversal = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"JournalEntry\" e JOIN \"JournalEntry\" o"
                        + " ON o.id = e.\"reversalOfId\" WHERE e.id = ? AND e.\"organizationId\" = ?"
                        + " AND o.\"sourceType\" = 'fx_revaluation')")
                .params(journalEntryId, org).query(Boolean.class).single();
        if (Boolean.TRUE.equals(revaluationReversal)) {
            throw ApiException.badRequest("This entry is the automatic reversal of an FX revaluation and cannot be"
                    + " reversed by hand; run the FX revaluation again for the date instead");
        }
        SourceDocument doc = resolve(journalEntryId);
        if (doc != null) {
            throw ApiException.badRequest(refusal(journalEntryId, doc));
        }
        // Control-account policy (V518): reversing a control-account adjustment posts to AR / AP again, so it needs
        // the same permission as staging or posting one (the reversal itself is audited by PostingService).
        Boolean adjustment = jdbc.sql("SELECT \"controlAdjustment\" FROM \"JournalEntry\" WHERE id = ? AND"
                        + " \"organizationId\" = ?")
                .params(journalEntryId, org).query(Boolean.class).optional().orElse(false);
        if (Boolean.TRUE.equals(adjustment) && !TenantContext.has(Permissions.JOURNAL_ENTRY__CONTROL_ADJUST)) {
            throw ApiException.forbidden("Reversing a control-account adjustment requires "
                    + Permissions.JOURNAL_ENTRY__CONTROL_ADJUST);
        }
    }

    private String refusal(String journalEntryId, SourceDocument doc) {
        String entryNumber = jdbc.sql("SELECT \"entryNumber\" FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ?")
                .params(journalEntryId, TenantContext.organizationId()).query(String.class).optional()
                .orElse(journalEntryId);
        Kind kind = doc.kind();
        String document = kind.label() + (doc.documentNumber() != null ? " " + doc.documentNumber()
                : doc.documentId() != null ? " " + doc.documentId() : "");
        String route = doc.route() == null ? "" : " (" + doc.route() + ")";
        if (kind.subledger()) {
            if (doc.viaReversal()) {
                return "Journal entry " + entryNumber + " is the reversal of an entry posted by " + document + "; the"
                        + " document's own reversal workflow posted it and it cannot be reversed generically. Issue a"
                        + " new document instead.";
            }
            return "Journal entry " + entryNumber + " was posted by " + document + " and cannot be reversed generically:"
                    + " a bare journal reversal would leave the customer / supplier sub-ledger out of step with the"
                    + " control account. Use the document-level action instead: " + kind.action() + route + ".";
        }
        if (doc.viaReversal()) {
            return "Journal entry " + entryNumber + " is the reversal of an entry posted by " + document + " and cannot"
                    + " be reversed generically: only manual journal entries and recurring-journal runs can be. Instead:"
                    + " post it again through the " + kind.label() + ", or " + ADJUSTING_JOURNAL + ".";
        }
        return "Journal entry " + entryNumber + " was posted by " + document + " and cannot be reversed generically: only"
                + " manual journal entries and recurring-journal runs can be, so that the " + kind.label() + " and the"
                + " general ledger stay in step. Instead: " + kind.action() + route + ".";
    }

    /** The kind posting under {@code sourceType}; never {@code null} (an unlisted source gets a generic kind). */
    private Kind kindFor(String sourceType, String sourceId, String org) {
        if ("cost_allocation".equals(sourceType) && sourceId != null && jdbc.sql("SELECT EXISTS (SELECT 1 FROM"
                        + " \"AllocationRun\" WHERE id = ? AND \"organizationId\" = ?)")
                .params(sourceId, org).query(Boolean.class).single()) {
            // two engines post cost_allocation: costing cycles (AllocationRun) and cost centres (CostAllocationRun)
            return COSTING_ALLOCATION;
        }
        Kind listed = SUBLEDGER.containsKey(sourceType) ? SUBLEDGER.get(sourceType) : PROCESSES.get(sourceType);
        if (listed != null) {
            return listed;
        }
        String label = LABELS.getOrDefault(sourceType, sourceType.replace('_', ' '));
        if (CORRECTED_BY_JOURNAL.contains(sourceType)) {
            return process(sourceType, label, null, null, ADJUSTING_JOURNAL + " (the " + label
                    + " has no reversal of its own)", null, null);
        }
        if (sourceType.endsWith("_reversal") || sourceType.endsWith("_cancel")) {
            return process(sourceType, label, null, null, "it is itself a reversal posted by its document's own"
                    + " workflow; issue a new document, or " + ADJUSTING_JOURNAL, null, null);
        }
        return process(sourceType, label, null, null, "reverse it from the document or process that posted it, or "
                + ADJUSTING_JOURNAL, null, null);
    }

    private SourceDocument document(Kind kind, String sourceType, String sourceId, String org, boolean viaReversal) {
        if (kind.byNumber()) {
            String id = kind.table() == null || sourceId == null ? null : jdbc.sql("SELECT id FROM \"" + kind.table()
                            + "\" WHERE \"" + kind.numberColumn() + "\" = ? AND \"organizationId\" = ? LIMIT 1")
                    .params(sourceId, org).query(String.class).optional().orElse(null);
            return new SourceDocument(kind, sourceType, id, sourceId, viaReversal);
        }
        return new SourceDocument(kind, sourceType, sourceId, number(kind, sourceId, org), viaReversal);
    }

    /** A reversal chain that cannot be followed to its original: refused by default. */
    private SourceDocument unresolved(boolean viaReversal) {
        return new SourceDocument(process("reversal", "reversal chain", null, null, "reverse the original entry's"
                + " document instead, or " + ADJUSTING_JOURNAL, null, null), "reversal", null, null, viaReversal);
    }

    /** The document's number, read only from tables known to exist; {@code null} when there is none. */
    private String number(Kind kind, String sourceId, String org) {
        if (kind.table() == null || kind.numberColumn() == null || sourceId == null) {
            return null;
        }
        return jdbc.sql("SELECT \"" + kind.numberColumn() + "\"::text FROM \"" + kind.table() + "\" WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(sourceId, org).query(String.class).optional().orElse(null);
    }
}
