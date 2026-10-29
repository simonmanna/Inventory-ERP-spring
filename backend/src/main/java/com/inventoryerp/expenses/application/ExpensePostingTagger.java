package com.inventoryerp.expenses.application;

/**
 * Extension point for modules that raise expenses on behalf of their own documents (fleet fuel purchases, ...): it
 * tags the expense-side line of the GL entry an expense payment posts with a cost centre, branch, analytic dimensions
 * and, optionally, the expense account to use. Expenses no tagger claims post exactly as before.
 */
public interface ExpensePostingTagger {

    /** Tags for the payment postings of expense {@code expenseId}, or {@code null} when this tagger does not own it. */
    ExpensesService.PostingTags tagsFor(String expenseId);
}
