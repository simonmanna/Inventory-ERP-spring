import { api } from '@/lib/api';

export interface ExpenseCategory {
  id: string;
  name: string;
  icon?: string | null;
  description?: string | null;
  isActive?: boolean;
  ledgerAccountId?: string | null;
  ledgerAccount?: { id: string; name: string; code: string } | null;
  // ADR-S052 controls
  code?: string | null;
  defaultTaxId?: string | null;
  defaultCostCenterId?: string | null;
  receiptRequired?: boolean;
  approvalRequired?: boolean;
  approvalThreshold?: number | null;
  reimbursable?: boolean;
  budgetControl?: 'none' | 'warn' | 'block';
  canCapitalise?: boolean;
  assetAccountId?: string | null;
}

export const expenseCategoriesApi = {
  list(): Promise<ExpenseCategory[]> {
    return api.get('/expense-categories').then((r) => r.data);
  },
  get(id: string): Promise<ExpenseCategory> {
    return api.get(`/expense-categories/${id}`).then((r) => r.data);
  },
  create(body: {
    name: string;
    icon?: string;
    description?: string;
    ledgerAccountId?: string;
  }): Promise<ExpenseCategory> {
    return api.post('/expense-categories', body).then((r) => r.data);
  },
  // ledgerAccountId / icon / description: send '' to clear (the API maps '' to null).
  update(id: string, body: Partial<ExpenseCategory>): Promise<ExpenseCategory> {
    return api.patch(`/expense-categories/${id}`, body).then((r) => r.data);
  },
  delete(id: string): Promise<void> {
    return api.delete(`/expense-categories/${id}`).then((r) => r.data);
  },
};

export interface LedgerAccountOption {
  id: string;
  code: string;
  name: string;
  isPostable?: boolean;
  isActive?: boolean;
}

/**
 * Postable, active GL accounts a category may link to (the API rejects anything else with
 * "Ledger account must be a postable (non-group, active) account"). Needs `account.read`.
 */
export function listLinkableLedgerAccounts(): Promise<LedgerAccountOption[]> {
  return api.get('/accounts', { params: { pageSize: 1000 } }).then((r) => {
    const rows: LedgerAccountOption[] = r.data?.data ?? r.data ?? [];
    return rows
      .filter((a) => a.isPostable && a.isActive !== false)
      .sort((a, b) => String(a.code).localeCompare(String(b.code), undefined, { numeric: true }));
  });
}
