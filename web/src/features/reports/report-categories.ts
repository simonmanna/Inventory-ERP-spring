import {
  Banknote,
  BookText,
  Boxes,
  Building2,
  Calculator,
  HandCoins,
  Landmark,
  Layers,
  LineChart,
  Mountain,
  Network,
  ReceiptText,
  ShieldCheck,
  Target,
  Wallet,
  type LucideIcon,
} from 'lucide-react';

/**
 * The 15 report library categories (backend ReportCategory, menu order). Static so the sidebar can list them
 * without waiting for the library call; `code` joins them to the API's LibraryCategory.
 */
export interface ReportCategoryNav {
  code: string;
  slug: string;
  title: string;
  icon: LucideIcon;
}

export const REPORT_CATEGORIES: ReportCategoryNav[] = [
  { code: '01', slug: 'financial-statements', title: 'Financial Statements', icon: Landmark },
  { code: '02', slug: 'general-ledger', title: 'General Ledger', icon: BookText },
  { code: '03', slug: 'accounts-receivable', title: 'Accounts Receivable', icon: HandCoins },
  { code: '04', slug: 'accounts-payable', title: 'Accounts Payable', icon: Banknote },
  { code: '05', slug: 'cash-banking', title: 'Cash & Banking', icon: Wallet },
  { code: '06', slug: 'tax-statutory', title: 'Tax & Statutory', icon: ReceiptText },
  { code: '07', slug: 'fixed-assets', title: 'Fixed Assets', icon: Building2 },
  { code: '08', slug: 'inventory-accounting', title: 'Inventory Accounting', icon: Boxes },
  { code: '09', slug: 'cost-accounting', title: 'Cost Accounting', icon: Calculator },
  { code: '10', slug: 'budgeting-forecasting', title: 'Budgeting & Forecasting', icon: Target },
  { code: '11', slug: 'financial-analysis', title: 'Financial Analysis', icon: LineChart },
  { code: '12', slug: 'multi-branch-consolidation', title: 'Multi-Branch & Consolidation', icon: Network },
  { code: '13', slug: 'audit-compliance', title: 'Audit & Compliance', icon: ShieldCheck },
  { code: '14', slug: 'advanced-accounting', title: 'Advanced Accounting', icon: Layers },
  { code: '15', slug: 'quarry-manufacturing', title: 'Quarry & Manufacturing', icon: Mountain },
];

export const reportCategoryPath = (slug: string) => `/reports/library/category/${slug}`;

/** Badge colours per report level. */
export const LEVEL_STYLE: Record<string, string> = {
  FINANCIAL: 'bg-blue-50 text-blue-700 border-blue-200',
  OPERATIONAL: 'bg-gray-50 text-gray-600 border-gray-200',
  MANAGEMENT: 'bg-amber-50 text-amber-700 border-amber-200',
};
