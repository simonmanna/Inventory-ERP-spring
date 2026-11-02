package com.inventoryerp.reporting.spi;

/** The 15 report library categories (owner catalogue §3), in menu order. */
public enum ReportCategory {
    FINANCIAL_STATEMENTS("01", "Financial Statements"),
    GENERAL_LEDGER("02", "General Ledger"),
    ACCOUNTS_RECEIVABLE("03", "Accounts Receivable"),
    ACCOUNTS_PAYABLE("04", "Accounts Payable"),
    CASH_AND_BANK("05", "Cash & Banking"),
    TAX("06", "Tax & Statutory"),
    FIXED_ASSETS("07", "Fixed Assets"),
    INVENTORY("08", "Inventory Accounting"),
    COST_ACCOUNTING("09", "Cost Accounting"),
    BUDGETING("10", "Budgeting & Forecasting"),
    ANALYSIS("11", "Financial Analysis"),
    CONSOLIDATION("12", "Multi-Branch & Consolidation"),
    AUDIT("13", "Audit & Compliance"),
    ADVANCED("14", "Advanced Accounting"),
    QUARRY("15", "Quarry & Manufacturing");

    private final String code;
    private final String title;

    ReportCategory(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }
}
