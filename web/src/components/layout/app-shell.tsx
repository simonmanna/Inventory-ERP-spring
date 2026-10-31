import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PendingCashOperations } from '@/pages/pos/PendingCashOperations';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { PageSuspense } from '@/components/page-loading';
import {
  LayoutDashboard,
  Building2,
  Users,
  Package,
  Receipt,
  FileMinus,
  HandCoins,
  Globe,
  LineChart,
  Presentation,
  BadgePercent,
  FileSignature,
  FileText,
  GraduationCap,
  UserCircle,
  Users2,
  Banknote,
  Clock,
  Scale,
  PackageX,
  FileSpreadsheet,
  Settings as SettingsIcon,
  LogOut,
  Menu,
  MessageSquareWarning,
  ShieldAlert,
  ShoppingCart,
  Truck,
  FilePlus2,
  Coffee,
  Tag,
  BarChart3,
  UserCog,
  Shield,
  ShieldCheck,
  ClipboardList,
  SlidersHorizontal,
  PackageSearch,
  ClipboardCheck,
  PanelLeftClose,
  PanelLeft,
  BookOpen,
  BookText,
  ScrollText,
  Smartphone,
  MapPin,
  Layers,
  Link2,
  Landmark,
  HardDrive,
  Ruler,
  ArrowUpDown,
  TrendingUp,
  TrendingDown,
  History,
  CalendarDays,
  CalendarHeart,
  Briefcase,
  FileClock,
  Wallet,
  ArrowRightLeft,
  Percent,
  Lock,
  Factory,
  Handshake,
  Wrench,
  Timer,
  LayoutGrid,
  Wine,
  KeyRound,
  Calculator,
  Boxes,
  ChefHat,
  UtensilsCrossed,
  MessagesSquare,
  Radio,
  Zap,
  CalendarClock,
  ChevronDown,
  Undo2,
  ListRestart,
  Target,
  FileInput,
  ReceiptText,
  BellRing,
  Database,
  Network,
  Inbox,
  CalendarCheck,
  Tags,
  IdCard,
  // fleet 1.6 maintenance
  AlertTriangle,
  TimerOff,
  Activity,
  CalendarCog,
  ListChecks,
  Fuel,
  Cylinder,
  CreditCard,
  Settings2,
  // fleet 1.3 dispatch
  CalendarRange,
  Route as RouteIcon,
  Navigation,
  // quality (ADR-S013)
  FlaskConical,
  Microscope,
  AlertOctagon,
  ListTodo,
  FlaskRound,
  GitFork,
  // quarry 2.2
  Bomb,
  Gem,
  MapPinned,
  Mountain,
  Warehouse,
  FileQuestion,
  PackagePlus,
  Siren,
} from 'lucide-react';
import { PERMISSIONS, REPORT_CATALOG } from '@erp/shared';
import { useCanViewReport } from '@/features/reports/access-api';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { useAuthStore } from '@/stores/auth.store';
import { useSidebarStore } from '@/lib/sidebar.store';
import { useOrgFeatures } from '@/lib/use-org-features';
import { sectionEnabled } from '@/lib/features';
import { usePosSettings } from '@/features/pos/api';
import { LicenseBanner } from '@/components/license/LicenseBanner';
import { LicenseBlockedDialog } from '@/components/license/LicenseBlockedDialog';
import { useLicense } from '@/features/license/api';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { GlobalSearch } from '@/components/global-search';
import { PushBootstrap } from '@/components/push-bootstrap';
import { ThemePicker } from '@/components/theme-picker';
import { LanguageSwitcher } from '@/components/language-switcher';
import { useTranslation } from 'react-i18next';
import { useSidebarTheme } from '@/lib/sidebar-theme';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { tx } from '@/lib/i18n/tx';

interface BranchOption {
  id: string;
  code: string;
  name: string;
}

function BranchSwitcher() {
  const { currentBranchId, setCurrentBranch } = useAuthStore();
  const { data } = useQuery<{ data: BranchOption[] }>({
    queryKey: ['branches-switch'],
    queryFn: async () => (await api.get('/branches', { params: { pageSize: 200 } })).data,
  });

  const branches = data?.data ?? [];

  const onSelect = async (value: string) => {
    const id = value === '__all__' ? null : value;
    setCurrentBranch(id); // optimistic local state
    try {
      await api.patch('/organizations/me/branch', { defaultBranchId: id });
    } catch {
      notify.error(tx('Failed to switch branch'));
    }
  };

  return (
    <Select value={currentBranchId ?? '__all__'} onValueChange={onSelect}>
      <SelectTrigger className="h-8 text-xs">
        <SelectValue placeholder={tx('Select branch')} />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="__all__">{tx('All branches')}</SelectItem>
        {branches.map((b) => (
          <SelectItem key={b.id} value={b.id}>{b.name} ({b.code})</SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

interface NavItem {
  to: string;
  label: string;
  icon: typeof LayoutDashboard;
  permission?: string;
  /**
   * Configurable report this item opens. Hidden when Settings → Report
   * Permissions turns the report off for the signed-in user's roles.
   */
  reportId?: string | string[];
  badge?: string;
  /** Per-item feature gate, resolved the same way as section flags. */
  flag?: 'VITE_ENABLE_BEVERAGE' | 'VITE_ENABLE_TASKS' | 'VITE_ENABLE_MANUFACTURING' | 'VITE_ENABLE_HR' | 'VITE_ENABLE_ORDERS' | 'VITE_ENABLE_ALERTS';
}

interface NavSection {
  title?: string;
  icon?: typeof LayoutDashboard;
  items: NavItem[];
  /**
   * Opt-in section, mirroring the API's ENABLE_* module gates. This is the
   * second layer only — the API decides what actually runs. Hiding a section
   * here without gating the module server-side would leave its routes, crons
   * and boot hooks live.
   */
  flag?: 'VITE_ENABLE_BEVERAGE' | 'VITE_ENABLE_TASKS' | 'VITE_ENABLE_MANUFACTURING' | 'VITE_ENABLE_HR' | 'VITE_ENABLE_ORDERS';
}

/** Tabs of the POS Reports page — the nav link survives while any one shows. */
const POS_REPORT_TAB_IDS = REPORT_CATALOG.filter((r) => r.route === '/pos/reports').map((r) => r.id);

const flagEnabled = (flag?: string): boolean =>
  !flag || (import.meta.env as Record<string, string | undefined>)[flag] === 'true';

const NAV_SECTIONS: NavSection[] = [
  {
    items: [
      { to: '/', label: tx('Dashboard'), icon: LayoutDashboard },
      { to: '/alerts', label: tx('Alerts & Exceptions'), icon: BellRing, permission: PERMISSIONS.alerts.read, flag: 'VITE_ENABLE_ALERTS' },
    ],
  },
  {
    title: 'POS',
    icon: Coffee,
    items: [
      { to: '/pos/terminal', label: tx('POS Terminal'), icon: Coffee, permission: PERMISSIONS.pos.checkout },
      { to: '/pos/kds', label: tx('Kitchen Display'), icon: ChefHat, permission: PERMISSIONS.pos.kds },
      { to: '/pos/kitchen-stations', label: tx('Kitchen Stations'), icon: UtensilsCrossed, permission: PERMISSIONS.pos.override },
      { to: '/pos/kds-reports', label: tx('Kitchen Reports'), icon: BarChart3, permission: PERMISSIONS.pos.reports, reportId: 'pos.kds' },
      { to: '/pos/cash-registers', label: tx('Cash Registers'), icon: Banknote, permission: PERMISSIONS.cashSession.read },
      { to: '/pos/receipts', label: tx('POS Receipts'), icon: ScrollText, permission: PERMISSIONS.pos.read },
      { to: '/pos/receivables', label: tx('Credit / Receivables'), icon: HandCoins, permission: PERMISSIONS.pos.read },
      { to: '/pos/reports', label: tx('POS Reports'), icon: BarChart3, permission: PERMISSIONS.pos.reports, reportId: POS_REPORT_TAB_IDS },
    ],
  },
  {
    title: 'Sales',
    icon: ShoppingCart,
    items: [
      { to: '/invoices', label: tx('Sales/Invoices'), icon: Receipt, permission: PERMISSIONS.invoice.read },
      { to: '/orders', label: tx('Orders'), icon: ClipboardList },
      { to: '/returns', label: tx('Returns Register'), icon: ListRestart, permission: PERMISSIONS.pos.read },
      { to: '/returns/new', label: tx('New Return'), icon: Undo2, permission: PERMISSIONS.pos.refund },
      { to: '/credit-notes', label: tx('Credit Notes'), icon: FileMinus, permission: PERMISSIONS.creditNote.read },
      { to: '/credit-control', label: tx('Credit Control'), icon: HandCoins, permission: PERMISSIONS.creditControl.read },
      { to: '/price-lists', label: tx('Price Lists'), icon: Tags, permission: PERMISSIONS.priceList.read },
      { to: '/reports/sales-analysis', label: tx('Sales Analysis'), icon: BarChart3, permission: PERMISSIONS.report.ar },
      { to: '/payments', label: tx('Receipts'), icon: HandCoins, permission: PERMISSIONS.payment.read },
      { to: '/ar-aging', label: tx('Accounts Receivable'), icon: Clock, permission: PERMISSIONS.report.ar, reportId: 'sales.ar-aging' },
      { to: '/statements', label: tx('Statements'), icon: ScrollText, permission: PERMISSIONS.report.ar },
    ],
  },
  {
    title: 'Inventory',
    icon: Package,
    items: [
      { to: '/inventory', label: tx('Stock Levels'), icon: Package, permission: 'inventory:read' },
      { to: '/inventory/ledger', label: tx('Stock Ledger'), icon: ScrollText, permission: 'inventory:read' },
      { to: '/labels', label: tx('Label Printing'), icon: Tag, permission: PERMISSIONS.label.print },
      { to: '/inventory/count', label: tx('Stock Count'), icon: ClipboardList, permission: PERMISSIONS.inventoryCount.read },
      { to: '/inventory/adjustments', label: tx('Stock Adjustments'), icon: Scale, permission: PERMISSIONS.inventoryDoc.read },
      { to: '/inventory/waste', label: tx('Damages & Waste'), icon: PackageX, permission: PERMISSIONS.inventoryDoc.read },
      { to: '/inventory/transfers', label: tx('Stock Transfers'), icon: Truck, permission: PERMISSIONS.inventoryDoc.read },
      { to: '/inventory/reservations', label: tx('Stock Reservations'), icon: ClipboardCheck, permission: 'inventory:read' },
      { to: '/inventory/replenishment', label: tx('Replenishment'), icon: ClipboardList, permission: 'inventory:read' },
      { to: '/inventory/locations', label: tx('Locations'), icon: MapPin, permission: PERMISSIONS.inventoryLocation.read },
      { to: '/inventory/cost-policies', label: tx('Costing Policy'), icon: Calculator, permission: PERMISSIONS.costingPolicy.read },
      { to: '/products', label: tx('Products'), icon: Package, permission: PERMISSIONS.products.view },
      { to: '/products/specifications', label: tx('Product specifications'), icon: ClipboardList, permission: PERMISSIONS.product.read },
      { to: '/uom', label: tx('Units of Measure'), icon: Ruler, permission: PERMISSIONS.uom.read },
      { to: '/inventory/reports', label: tx('Inventory Reports'), icon: BarChart3, permission: 'inventory:read', reportId: 'inventory.reports' },
    ],
  },
  {
    title: 'Customers',
    icon: Users,
    items: [
      { to: '/customers', label: tx('Customers'), icon: Users, permission: PERMISSIONS.partners.view },
    ],
  },
  {
    title: 'Suppliers',
    icon: Building2,
    items: [
      { to: '/suppliers', label: tx('Suppliers'), icon: Building2, permission: PERMISSIONS.partners.view },
    ],
  },
  {
    title: 'Menus',
    icon: Coffee,
    items: [
      { to: '/menu', label: tx('Menu'), icon: Coffee, permission: PERMISSIONS.menu.view },
      // F13 — combo authoring hidden while combo selling is paused (the POS
      // checkout guard rejects combo lines). Route kept for when it is finished.
      { to: '/menu/accompaniments', label: tx('Accompaniments'), icon: Tag, permission: PERMISSIONS.menu.view },
      { to: '/menu/modifiers', label: tx('Modifiers'), icon: Tag, permission: PERMISSIONS.menu.view },
    ],
  },
  {
    title: 'Tables',
    icon: LayoutGrid,
    items: [
      { to: '/tables', label: tx('Tables'), icon: Coffee, permission: PERMISSIONS.menu.view },
      { to: '/tables/zones', label: tx('Areas/Zones'), icon: MapPin, permission: PERMISSIONS.menu.view },
    ],
  },
  {
    title: 'Cash Flow & Accounts',
    icon: Wallet,
    items: [
      { to: '/accounts/cash-accounts', label: tx('Overview'), icon: LayoutDashboard, permission: PERMISSIONS.account.read },
      { to: '/accounts/cash-accounts/accounts', label: tx('Accounts'), icon: Wallet, permission: PERMISSIONS.account.read },
      { to: '/accounts/cash-accounts/activity', label: tx('Money Activity'), icon: ArrowRightLeft, permission: PERMISSIONS.account.read },
      { to: '/accounts/cash-accounts/settlements', label: tx('Settlements'), icon: Landmark, permission: PERMISSIONS.cashSession.reconcile },
      { to: '/bank-reconciliation', label: tx('Bank Reconciliation'), icon: Landmark, permission: PERMISSIONS.bankAccount.read },
      // Cash Registers lives under POS only; Overview links to it from "Open registers".
      { to: '/payments', label: tx('Customer Receipts'), icon: HandCoins, permission: PERMISSIONS.payment.read },
      { to: '/payments/mobile-money', label: tx('Mobile Money'), icon: Smartphone, permission: PERMISSIONS.payment.read },
      { to: '/accounts/bank-import', label: tx('Import Bank Statement'), icon: FileInput, permission: PERMISSIONS.bankAccount.update },
      { to: '/supplier-payments', label: tx('Supplier Payments'), icon: Truck, permission: PERMISSIONS.payment.read },
      { to: '/cash-flow/report', label: tx('Money Reports'), icon: BarChart3, permission: PERMISSIONS.account.read, reportId: 'money.reports' },
    ],
  },
  {
    title: 'Accounting',
    icon: Calculator,
    items: [
      { to: '/accounts', label: tx('Chart of Accounts'), icon: BookOpen, permission: PERMISSIONS.account.read },
      { to: '/accounts/categories', label: tx('Account Categories'), icon: Layers, permission: PERMISSIONS.accountCategory.read },
      { to: '/accounts/mappings', label: tx('Account Mappings'), icon: Link2, permission: PERMISSIONS.accountMapping.read },
      { to: '/accounts/posting-rules', label: tx('Posting Rules'), icon: FileText, permission: PERMISSIONS.inventoryPostingRule.read },
      { to: '/journals', label: tx('Journals'), icon: BookText, permission: PERMISSIONS.journal.read },
      { to: '/journal-entries', label: tx('Journal Entries'), icon: ScrollText, permission: PERMISSIONS.journalEntry.read },
      { to: '/trial-balance', label: tx('Trial Balance'), icon: Scale, permission: PERMISSIONS.report.accounting, reportId: 'accounting.trial-balance' },
      { to: '/general-ledger', label: tx('General Ledger'), icon: ArrowUpDown, permission: PERMISSIONS.report.accounting, reportId: 'accounting.general-ledger' },
      { to: '/accounting-detailed-report', label: tx('Detailed Report'), icon: FileSpreadsheet, permission: PERMISSIONS.report.accounting, reportId: 'accounting.detailed' },
      { to: '/profit-and-loss', label: tx('Profit & Loss'), icon: TrendingUp, permission: PERMISSIONS.report.accounting, reportId: 'accounting.profit-and-loss' },
      { to: '/cash-flow', label: tx('Cash Flow Statement'), icon: TrendingDown, permission: PERMISSIONS.report.accounting, reportId: 'accounting.cash-flow-statement' },
      { to: '/tieout', label: tx('Tie-Out'), icon: ShieldCheck, permission: PERMISSIONS.report.accounting, reportId: 'accounting.tieout' },
      { to: '/pos-gl-reconciliation', label: tx('POS → GL Recon'), icon: Package, permission: PERMISSIONS.report.accounting, reportId: 'accounting.pos-gl-recon' },
      { to: '/audit-log', label: tx('Audit Log'), icon: History, permission: PERMISSIONS.auditLog.read },
      { to: '/fiscal-years', label: tx('Fiscal Years'), icon: CalendarDays, permission: PERMISSIONS.fiscalPeriod.read },
      { to: '/fiscal-periods', label: tx('Fiscal Periods'), icon: CalendarDays, permission: PERMISSIONS.fiscalPeriod.read },
      { to: '/taxes', label: tx('Tax Rates'), icon: Percent, permission: PERMISSIONS.tax.read },
      { to: '/cost-centers', label: tx('Cost Centers'), icon: Users, permission: PERMISSIONS.costCenter.read },
      { to: '/analytic-dimensions', label: tx('Analytic Dimensions'), icon: Users, permission: PERMISSIONS.analyticDimension.read },
      { to: '/cost-center-accounting', label: tx('Cost Centre Accounting'), icon: Network, permission: PERMISSIONS.costCenter.read },
      { to: '/currency', label: tx('Currency'), icon: Banknote, permission: PERMISSIONS.currency.read },
      { to: '/accounts/payment-terms', label: tx('Payment Terms'), icon: CalendarClock, permission: PERMISSIONS.account.read },
      { to: '/accounts/fiscal-positions', label: tx('Fiscal Positions'), icon: Landmark, permission: PERMISSIONS.account.read },
      { to: '/inventory-valuation', label: tx('Inventory Val.'), icon: Package, permission: PERMISSIONS.report.accounting, reportId: 'accounting.inventory-valuation' },
      { to: '/inventory-gl-tieout', label: tx('Inventory ↔ GL'), icon: ShieldCheck, permission: PERMISSIONS.report.accounting, reportId: 'accounting.inventory-gl-tieout' },
      { to: '/year-end-close', label: tx('Year-End Close'), icon: Lock, permission: PERMISSIONS.fiscalPeriod.update },
      { to: '/balance-sheet', label: tx('Balance Sheet'), icon: Landmark, permission: PERMISSIONS.report.accounting, reportId: 'accounting.balance-sheet' },
      { to: '/tax-report', label: tx('Tax Report'), icon: ReceiptText, permission: PERMISSIONS.report.accounting },
      { to: '/budgets', label: tx('Budgets'), icon: Target, permission: PERMISSIONS.budget.read },
      { to: '/consolidated-report', label: tx('Branch Consolidation'), icon: Network, permission: PERMISSIONS.report.accounting },
      { to: '/reports/branches', label: tx('Branch Performance'), icon: Network, permission: PERMISSIONS.report.accounting },
      { to: '/opening-balances', label: tx('Opening Balances'), icon: FileInput, permission: PERMISSIONS.openingBalance.read },
      { to: '/reports', label: tx('Report Center'), icon: BarChart3, permission: PERMISSIONS.report.accounting, reportId: 'accounting.report-center' },
      { to: '/reports/bi-exports', label: tx('BI Exports'), icon: Database, permission: PERMISSIONS.bi.export },
    ],
  },
  {
    title: 'Expenses',
    icon: FileText,
    items: [
      { to: '/expenses', label: tx('Expenses'), icon: FileText, permission: PERMISSIONS.expense.read },
      { to: '/expenses/categories', label: tx('Expense Categories'), icon: Tag, permission: PERMISSIONS.expense.read },
      { to: '/expenses/reports', label: tx('Expense Reports'), icon: BarChart3, permission: PERMISSIONS.expense.read, reportId: 'expenses.reports' },
      { to: '/supplier-payments', label: tx('Supplier Payments'), icon: Banknote, permission: PERMISSIONS.payment.read },
    ],
  },
  {
    title: 'Purchases',
    icon: Truck,
    items: [
      { to: '/procurement/analytics', label: tx('Purchasing Dashboard'), icon: BarChart3, permission: PERMISSIONS.procurementAnalytics.read },
      { to: '/procurement/replenishment', label: tx('Replenishment'), icon: PackagePlus, permission: PERMISSIONS.replenishment.read },
      { to: '/procurement/purchase-requests', label: tx('Purchase Requests'), icon: ClipboardList, permission: PERMISSIONS.procurement.purchaseRequest.read },
      { to: '/procurement/sourcing', label: tx('Sourcing Workbench'), icon: PackageSearch, permission: PERMISSIONS.procurement.purchaseOrder.create },
      { to: '/procurement/rfqs', label: tx('RFQs'), icon: FileQuestion, permission: PERMISSIONS.rfq.read },
      { to: '/procurement/agreements', label: tx('Purchase Agreements'), icon: FileSignature, permission: PERMISSIONS.purchaseAgreement.read },
      { to: '/procurement/purchase-orders', label: tx('Purchases'), icon: ShoppingCart, permission: 'purchase_order:read' },
      { to: '/procurement/reports/open-orders', label: tx('Open Orders'), icon: Clock, permission: PERMISSIONS.procurement.purchaseOrder.read },
      { to: '/procurement/budgets', label: tx('Procurement Budgets'), icon: Calculator, permission: 'procurement_budget:read' },
      { to: '/procurement/delegations', label: tx('Purchasing Delegation'), icon: UserCog, permission: 'procurement_delegation:read' },
      { to: '/procurement/goods-receipts', label: tx('Goods Receipts'), icon: Truck, permission: 'goods_receipt:read' },
      { to: '/vendor-bills', label: tx('Vendor Bills'), icon: ReceiptText, permission: PERMISSIONS.expense.read },
      { to: '/ap-aging', label: tx('Accounts Payable'), icon: Clock, permission: PERMISSIONS.report.ar },
      { to: '/payment-schedule', label: tx('Payment Schedule'), icon: CalendarClock, permission: PERMISSIONS.expense.read },
      { to: '/procurement/supplier-performance', label: tx('Supplier Performance'), icon: BarChart3, permission: 'purchase_order:read' },
      // { to: '/procurement/three-way-match', label: tx('3-Way Match'), icon: Scale, permission: 'three_way_match:read' },
      { to: '/procurement/debit-notes', label: tx('Debit Notes'), icon: FilePlus2, permission: 'debit_note:read' },
      { to: '/procurement/shipments', label: tx('Import Shipments'), icon: Boxes, permission: PERMISSIONS.landedCost.read },
      { to: '/procurement/landed-cost-vouchers', label: tx('Landed Costs'), icon: Calculator, permission: PERMISSIONS.landedCost.read },
      { to: '/procurement/landed-cost-reports', label: tx('Landed Cost Reports'), icon: BarChart3, permission: PERMISSIONS.landedCost.read },
      { to: '/supplier-payments', label: tx('Supplier Payments'), icon: Banknote, permission: 'payment:read' },
      { to: '/procurement/inspections', label: tx('Incoming Inspection'), icon: ShieldCheck, permission: PERMISSIONS.inspection.read },
      { to: '/procurement/reports/emergency', label: tx('Emergency Purchases'), icon: Siren, permission: PERMISSIONS.emergencyPurchase.review },
      { to: '/procurement/claims', label: tx('Supplier Claims'), icon: MessageSquareWarning, permission: PERMISSIONS.supplierClaim.read },
      { to: '/procurement/po-changes', label: tx('Order Changes'), icon: ClipboardCheck, permission: PERMISSIONS.poChange.approve },
      { to: '/procurement/commitments', label: tx('Budget Commitments'), icon: Landmark, permission: PERMISSIONS.budgetControl.commitments },
      { to: '/procurement/suppliers', label: tx('Supplier Register'), icon: Building2, permission: PERMISSIONS.supplier.read },
      { to: '/procurement/policies', label: tx('Purchasing Policies'), icon: SlidersHorizontal, permission: PERMISSIONS.procurementPolicy.read },
    ],
  },
  {
    title: 'Human Resource',
    icon: Briefcase,
    flag: 'VITE_ENABLE_HR',
    items: [
      { to: '/hr', label: tx('Dashboard'), icon: LayoutDashboard, permission: PERMISSIONS.hr.read },
      { to: '/hr/employees', label: tx('Employees'), icon: Users, permission: PERMISSIONS.hr.employee },
      { to: '/hr/departments', label: tx('Departments'), icon: Building2, permission: PERMISSIONS.hr.employee },
      { to: '/hr/positions', label: tx('Positions'), icon: Briefcase, permission: PERMISSIONS.hr.employee },
      { to: '/hr/shifts', label: tx('Shifts'), icon: Clock, permission: PERMISSIONS.hr.attendance },
      { to: '/hr/attendance', label: tx('Attendance'), icon: ClipboardCheck, permission: PERMISSIONS.hr.attendance },
      { to: '/hr/timesheets', label: tx('Timesheets'), icon: FileClock, permission: PERMISSIONS.hr.timesheet },
      { to: '/hr/leave', label: tx('Leave'), icon: CalendarDays, permission: PERMISSIONS.hr.leave },
      { to: '/hr/holidays', label: tx('Holidays'), icon: CalendarHeart, permission: PERMISSIONS.hr.holiday },
      { to: '/hr/payroll', label: tx('Payroll'), icon: Wallet, permission: PERMISSIONS.hr.payroll },
      { to: '/hr/payslips', label: tx('Payslips'), icon: FileText, permission: PERMISSIONS.hr.payslip },
      { to: '/hr/advances-loans', label: tx('Advances & Loans'), icon: HandCoins, permission: PERMISSIONS.hr.payroll },
      { to: '/hr/payroll/settings', label: tx('Payroll Settings'), icon: SettingsIcon, permission: PERMISSIONS.hr.payroll },
      { to: '/hr/documents', label: tx('Documents'), icon: FileText, permission: PERMISSIONS.hr.document },
      { to: '/hr/training', label: tx('Training'), icon: GraduationCap, permission: PERMISSIONS.hr.training },
      { to: '/hr/recruitment', label: tx('Recruitment'), icon: Users2, permission: PERMISSIONS.hr.recruitment },
      { to: '/hr/onboarding', label: tx('Onboarding'), icon: ClipboardList, permission: PERMISSIONS.hr.recruitment },
      { to: '/hr/contracts', label: tx('Contracts'), icon: ScrollText, permission: PERMISSIONS.hr.contract },
      { to: '/hr/reviews', label: tx('Performance reviews'), icon: ClipboardCheck, permission: PERMISSIONS.hr.performance },
      { to: '/hr/reports', label: tx('Reports'), icon: BarChart3, permission: PERMISSIONS.hr.report, reportId: 'hr.reports' },
      // No permission: both are scoped server-side to the caller's own record
      // (or their own reports), so gating them would mean handing every cashier
      // the staff directory just to let them see their own payslip.
      { to: '/hr/me', label: tx('My HR'), icon: UserCircle },
      { to: '/hr/team', label: tx('My Team'), icon: Users2 },
    ],
  },
  {
    title: 'Sales',
    icon: ShoppingCart,
    items: [
      { to: '/sales', label: tx('Sales Dashboard'), icon: LayoutDashboard, permission: PERMISSIONS.sales.reportRead },
      { to: '/sales/analytics', label: tx('Sales analytics'), icon: LineChart, permission: PERMISSIONS.sales.reportRead },
      { to: '/sales/presentation', label: tx('Generate presentation'), icon: Presentation, permission: PERMISSIONS.sales.reportRead },
      { to: '/crm/quotations', label: tx('Quotations'), icon: FileText, permission: PERMISSIONS.crm.quotationRead },
      { to: '/sales/orders', label: tx('Sales Orders'), icon: ShoppingCart, permission: PERMISSIONS.sales.orderRead },
      { to: '/sales/collections', label: tx('Collections'), icon: HandCoins, permission: PERMISSIONS.sales.collectionsRead },
      { to: '/sales/agreements', label: tx('Agreements & rebates'), icon: FileSignature, permission: PERMISSIONS.sales.agreementRead },
      { to: '/sales/commissions', label: tx('Commissions & targets'), icon: BadgePercent, permission: PERMISSIONS.sales.commissionRead },
      { to: '/sales/returns', label: tx('Returns'), icon: Undo2, permission: PERMISSIONS.sales.returnRead },
      { to: '/sales/complaints', label: tx('Complaints'), icon: MessageSquareWarning, permission: PERMISSIONS.sales.complaintRead },
      { to: '/sales/backorders', label: tx('Backorders'), icon: PackageX, permission: PERMISSIONS.sales.reportRead },
      { to: '/sales/pricelists', label: tx('Pricelists'), icon: Tags, permission: PERMISSIONS.sales.pricingRead },
      { to: '/sales/margin-floors', label: tx('Margin floors'), icon: Percent, permission: PERMISSIONS.sales.pricingRead },
      { to: '/sales/credit', label: tx('Credit Control'), icon: ShieldAlert, permission: PERMISSIONS.credit.read },
      { to: '/sales/portal-access', label: tx('Customer portal'), icon: Globe, permission: PERMISSIONS.sales.portalManage },
    ],
  },
  {
    title: 'CRM',
    icon: Users,
    items: [
      { to: '/crm', label: tx('Sales Dashboard'), icon: LayoutDashboard, permission: PERMISSIONS.crm.dashboardRead },
      { to: '/crm/pipeline', label: tx('Pipeline'), icon: Handshake, permission: PERMISSIONS.crm.dealRead },
      { to: '/crm/leads', label: tx('Leads'), icon: Inbox, permission: PERMISSIONS.crm.leadRead },
      { to: '/crm/quotations', label: tx('Quotations'), icon: FileText, permission: PERMISSIONS.crm.quotationRead },
      { to: '/crm/activities', label: tx('Activities'), icon: CalendarCheck, permission: PERMISSIONS.crm.activityRead },
      { to: '/crm/customers', label: tx('Customers'), icon: Users2, permission: PERMISSIONS.crm.dealRead },
      { to: '/crm/cases', label: tx('Complaints & Requests'), icon: ClipboardCheck, permission: PERMISSIONS.crm.caseRead },
      { to: '/crm/pricing', label: tx('Price Rules'), icon: Tags, permission: PERMISSIONS.crm.pricingRead },
      { to: '/crm/reports', label: tx('Reports'), icon: BarChart3, permission: PERMISSIONS.crm.reportRead },
      { to: '/crm/settings', label: tx('Configuration'), icon: SettingsIcon, permission: PERMISSIONS.crm.configure },
    ],
  },
  {
    title: 'Production',
    icon: Factory,
    items: [
      { to: '/manufacturing', label: tx('Dashboard'), icon: Factory },
      { to: '/manufacturing/orders', label: tx('Orders'), icon: ClipboardList },
      { to: '/manufacturing/boms', label: tx('Recipes (BOM)'), icon: ScrollText },
      { to: '/manufacturing/planning', label: tx('Planning'), icon: CalendarDays },
      { to: '/manufacturing/mrp', label: tx('MRP'), icon: CalendarDays },
      { to: '/manufacturing/planning-board', label: tx('Planning Workbench'), icon: Target, permission: PERMISSIONS.productionPlan.read },
      { to: '/manufacturing/gantt', label: tx('Production Gantt'), icon: CalendarDays, permission: PERMISSIONS.workOrder.read },
      { to: '/manufacturing/capacity', label: tx('Capacity Planning'), icon: BarChart3, permission: PERMISSIONS.productionPlan.read },
      { to: '/manufacturing/shift-plans', label: tx('Shift Plans'), icon: CalendarClock, permission: PERMISSIONS.productionPlan.read },
      { to: '/manufacturing/plan-performance', label: tx('Plan Performance'), icon: TrendingUp, permission: PERMISSIONS.production.report },
      { to: '/manufacturing/shop-floor', label: tx('Shop Floor'), icon: Boxes },
      { to: '/manufacturing/terminal', label: tx('Operator Terminal'), icon: Smartphone, permission: PERMISSIONS.workOrder.read },
      { to: '/manufacturing/floor-board', label: tx('Shop-floor Live'), icon: Radio, permission: PERMISSIONS.workOrder.read },
      { to: '/manufacturing/shift-reports', label: tx('Shift Reports'), icon: ClipboardCheck, permission: PERMISSIONS.workOrder.read },
      { to: '/manufacturing/oee', label: tx('OEE'), icon: TrendingUp, permission: PERMISSIONS.production.report },
      { to: '/manufacturing/floor-setup', label: tx('Shop-floor Setup'), icon: Wrench, permission: PERMISSIONS.workCenter.update },
      { to: '/manufacturing/engineering', label: tx('Engineering & Costing'), icon: ScrollText },
      { to: '/manufacturing/standard-costs', label: tx('Standard Costs'), icon: Calculator, permission: PERMISSIONS.standardCost.read },
      { to: '/manufacturing/process-costing', label: tx('Process Costing'), icon: Layers, permission: PERMISSIONS.processCosting.read },
      { to: '/manufacturing/costing', label: tx('Manufacturing Costing'), icon: Calculator, permission: PERMISSIONS.production.report },
      { to: '/manufacturing/stage-wip', label: tx('Stage WIP'), icon: Layers, permission: PERMISSIONS.production.report },
      { to: '/manufacturing/precast/curing', label: tx('Precast · Curing board'), icon: Boxes, permission: PERMISSIONS.productionOrder.read },
      { to: '/manufacturing/precast/orders', label: tx('Precast orders'), icon: ClipboardList, permission: PERMISSIONS.productionOrder.read },
      { to: '/manufacturing/precast/mix-designs', label: tx('Mix designs'), icon: ScrollText, permission: PERMISSIONS.bom.read },
      { to: '/manufacturing/precast/setup', label: tx('Precast set-up'), icon: SettingsIcon, permission: PERMISSIONS.bom.read },
      { to: '/manufacturing/precast/reports', label: tx('Precast reports'), icon: BarChart3, permission: PERMISSIONS.production.report },
      // crushing & screening 2.3 (ADR-S016)
      { to: '/manufacturing/crushing', label: tx('Crushing Runs'), icon: Factory, permission: PERMISSIONS.crushing.read },
      { to: '/manufacturing/crushing/plants', label: tx('Crushing Plants'), icon: Boxes, permission: PERMISSIONS.crushing.read },
      { to: '/manufacturing/crushing/stockpiles', label: tx('Aggregate Stockpiles'), icon: Layers, permission: PERMISSIONS.crushing.read },
      { to: '/manufacturing/crushing/reports', label: tx('Crushing Reports'), icon: BarChart3, permission: PERMISSIONS.production.report },
      { to: '/manufacturing/resources', label: tx('Work Centres'), icon: Boxes },
      { to: '/manufacturing/reports', label: tx('Reports'), icon: BarChart3, reportId: 'manufacturing.reports' },
    ],
  },
  {
    title: 'Task Management',
    icon: ClipboardList,
    items: [
      { to: '/tasks', label: tx('Task Board'), icon: ClipboardList },
    ],
  },
  {
    title: 'Repair & Maintenance',
    icon: Wrench,
    items: [
      { to: '/repair', label: tx('Dashboard'), icon: Wrench, permission: PERMISSIONS.repair.read },
      { to: '/repair/orders', label: tx('Repair Orders'), icon: FileText, permission: PERMISSIONS.repair.read },
      { to: '/repair/jobs', label: tx('Work Orders'), icon: ClipboardCheck, permission: PERMISSIONS.repair.read },
      { to: '/repair/technicians', label: tx('Technicians'), icon: Users, permission: PERMISSIONS.repair.read },
      { to: '/repair/labour', label: tx('Labour Catalog'), icon: Timer, permission: PERMISSIONS.repair.read },
      { to: '/repair/warranties', label: tx('Warranties'), icon: ShieldCheck, permission: PERMISSIONS.repair.read },
      { to: '/repair/contracts', label: tx('Service Contracts'), icon: Handshake, permission: PERMISSIONS.repair.read },
      { to: '/repair/reports', label: tx('Reports'), icon: BarChart3, permission: PERMISSIONS.repair.read, reportId: 'repair.reports' },
    ],
  },
  {
    title: 'Rentals',
    icon: KeyRound,
    items: [
      { to: '/rental', label: tx('Dashboard'), icon: CalendarDays, permission: PERMISSIONS.rental.read },
      { to: '/rental/agreements', label: tx('Agreements'), icon: FileText, permission: PERMISSIONS.rental.read },
      { to: '/rental/units', label: tx('Rental Units'), icon: Boxes, permission: PERMISSIONS.rental.read },
      { to: '/rental/catalog', label: tx('Rates & Packages'), icon: Package, permission: PERMISSIONS.rental.read },
      { to: '/rental/returns', label: tx('Returns'), icon: ClipboardCheck, permission: PERMISSIONS.rental.read },
      { to: '/rental/reports', label: tx('Reports'), icon: BarChart3, permission: PERMISSIONS.rental.read, reportId: 'rental.reports' },
    ],
  },
  {
    // Fleet & logistics (ADR-S009). Sub-modules 1.2–1.9 add drivers, dispatch, weighbridge, fuel, maintenance, reports.
    title: 'Fleet',
    icon: Truck,
    items: [
      { to: '/fleet', label: tx('Fleet Overview'), icon: LayoutDashboard, permission: PERMISSIONS.fleetVehicle.read },
      // fleet 1.9 costing & reports
      { to: '/fleet/reports', label: tx('Fleet Reports'), icon: BarChart3, permission: PERMISSIONS.fleet.report },
      { to: '/fleet/vehicles', label: tx('Vehicles & Equipment'), icon: Truck, permission: PERMISSIONS.fleetVehicle.read },
      { to: '/fleet/compliance', label: tx('Compliance & Renewals'), icon: ShieldCheck, permission: PERMISSIONS.fleetVehicle.read },
      { to: '/fleet/reminders', label: tx('Fleet Reminders'), icon: BellRing, permission: PERMISSIONS.fleet.report },
      { to: '/fleet/drivers', label: tx('Drivers & Operators'), icon: IdCard, permission: PERMISSIONS.fleetDriver.read },
      { to: '/fleet/checkout', label: tx('Check-out / Check-in'), icon: LogOut, permission: PERMISSIONS.fleetDriver.checkout },
      { to: '/fleet/claims', label: tx('Driver Claims'), icon: Receipt, permission: PERMISSIONS.fleetDriver.read },
      { to: '/fleet/dispatch', label: tx('Dispatch Board'), icon: CalendarRange, permission: PERMISSIONS.fleetDispatch.read },
      { to: '/fleet/delivery-orders', label: tx('Delivery Orders'), icon: ClipboardList, permission: PERMISSIONS.fleetDispatch.read },
      { to: '/fleet/trips', label: tx('Trips'), icon: RouteIcon, permission: PERMISSIONS.fleetTrip.read },
      { to: '/fleet/my-trips', label: tx('My Trips'), icon: Navigation, permission: PERMISSIONS.fleetTrip.deliver },
      { to: '/driver', label: tx('Driver App'), icon: Smartphone, permission: PERMISSIONS.fleetTrip.deliver },
      { to: '/fleet/customer-sites', label: tx('Customer Sites'), icon: MapPin, permission: PERMISSIONS.fleetDispatch.read },
      { to: '/fleet/delivery-discrepancies', label: tx('Delivery Discrepancies'), icon: Scale, permission: PERMISSIONS.fleetDispatch.read },
      { to: '/fleet/settings/dispatch', label: tx('Dispatch Settings'), icon: Settings2, permission: PERMISSIONS.fleet.settings },
      { to: '/fleet/freight-bills', label: tx('Freight Bills'), icon: ReceiptText, permission: PERMISSIONS.fleetFreight.read },
      // 1.6 maintenance
      { to: '/fleet/plant', label: tx('Fixed Plant'), icon: Factory, permission: PERMISSIONS.fleetVehicle.read },
      { to: '/fleet/maintenance', label: tx('Services Due'), icon: CalendarClock, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/maintenance/work-orders', label: tx('Maintenance Work Orders'), icon: Wrench, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/inspections', label: tx('Inspections'), icon: ClipboardCheck, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/defects', label: tx('Defects'), icon: AlertTriangle, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/maintenance/downtime', label: tx('Downtime'), icon: TimerOff, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/maintenance/reliability', label: tx('Availability & Reliability'), icon: Activity, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/maintenance/templates', label: tx('Service Templates'), icon: CalendarCog, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/maintenance/checklists', label: tx('Inspection Checklists'), icon: ListChecks, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/tyres', label: tx('Tyres'), icon: Target, permission: PERMISSIONS.fleetMaintenance.read },
      { to: '/fleet/incidents', label: tx('Incidents & Claims'), icon: Shield, permission: PERMISSIONS.fleetIncident.read },
      { to: '/fleet/fines', label: tx('Traffic Fines'), icon: Banknote, permission: PERMISSIONS.fleetIncident.read },
      { to: '/fleet/live', label: tx('Live Fleet'), icon: MapPin, permission: PERMISSIONS.fleetTrip.read },
      { to: '/fleet/telematics', label: tx('Telematics'), icon: Radio, permission: PERMISSIONS.fleet.settings },
      { to: '/fleet/settings/classes', label: tx('Vehicle Classes'), icon: Layers, permission: PERMISSIONS.fleet.settings },
      { to: '/fleet/settings/allowances', label: tx('Allowance Rules'), icon: HandCoins, permission: PERMISSIONS.fleet.settings },
      { to: '/fleet/fuel', label: tx('Fuel Logs'), icon: Fuel, permission: PERMISSIONS.fleetFuel.read },
      { to: '/fleet/fuel/tanks', label: tx('Fuel Tanks'), icon: Cylinder, permission: PERMISSIONS.fleetFuel.read },
      { to: '/fleet/fuel/cards', label: tx('Fuel Cards'), icon: CreditCard, permission: PERMISSIONS.fleetFuel.read },
      { to: '/fleet/fuel/anomalies', label: tx('Fuel Anomalies'), icon: AlertTriangle, permission: PERMISSIONS.fleetFuel.read },
      { to: '/fleet/settings/fuel', label: tx('Fuel Settings'), icon: Settings2, permission: PERMISSIONS.fleet.settings },
      // fleet 1.4 weighbridge
      { to: '/fleet/weighbridge', label: tx('Weighbridge'), icon: Scale, permission: PERMISSIONS.weighbridge.read },
      { to: '/fleet/weighbridge/exceptions', label: tx('Weighbridge Exceptions'), icon: AlertTriangle, permission: PERMISSIONS.weighbridge.read },
      { to: '/fleet/weighbridge/reports', label: tx('Weighbridge Discrepancies'), icon: BarChart3, permission: PERMISSIONS.weighbridge.read },
      { to: '/fleet/weighbridge/settings', label: tx('Weighbridge Settings'), icon: Settings2, permission: PERMISSIONS.weighbridge.configure },
    ],
  },
  {
    // Quality management (ADR-S013): core-available, no module flag — items gated by quality:read.
    title: 'Quality',
    icon: ShieldCheck,
    items: [
      { to: '/quality', label: tx('Quality Overview'), icon: LayoutDashboard, permission: PERMISSIONS.quality.read },
      { to: '/quality/lots', label: tx('Inspection Lots'), icon: Microscope, permission: PERMISSIONS.quality.read },
      { to: '/quality/specifications', label: tx('Specifications'), icon: FlaskConical, permission: PERMISSIONS.quality.read },
      { to: '/quality/plans', label: tx('Inspection Plans'), icon: ClipboardList, permission: PERMISSIONS.quality.read },
      { to: '/quality/ncrs', label: tx('Nonconformance Reports'), icon: AlertOctagon, permission: PERMISSIONS.quality.read },
      { to: '/quality/capas', label: tx('Corrective & Preventive Actions'), icon: ListTodo, permission: PERMISSIONS.quality.read },
      { to: '/quality/lab', label: tx('Laboratory Results'), icon: FlaskRound, permission: PERMISSIONS.quality.read },
      { to: '/quality/traceability', label: tx('Batch Traceability'), icon: GitFork, permission: PERMISSIONS.quality.read },
      { to: '/quality/reports', label: tx('Quality Reports'), icon: BarChart3, permission: PERMISSIONS.quality.read },
    ],
  },
  {
    // Management dashboards (ADR-S020, Track C Phase 5): each dashboard has its own permission.
    title: 'Analytics',
    icon: BarChart3,
    items: [
      { to: '/analytics', label: tx('Dashboards Overview'), icon: LayoutDashboard, permission: PERMISSIONS.analytics.view },
      { to: '/analytics/quarry', label: tx('Quarry Operations'), icon: Mountain, permission: PERMISSIONS.analytics.quarry },
      { to: '/analytics/aggregates', label: tx('Aggregate Processing'), icon: Factory, permission: PERMISSIONS.analytics.aggregates },
      { to: '/analytics/blocks', label: tx('Block Manufacturing'), icon: Layers, permission: PERMISSIONS.analytics.blocks },
      { to: '/analytics/inventory', label: tx('Inventory KPIs'), icon: Boxes, permission: PERMISSIONS.analytics.inventory },
      { to: '/analytics/procurement', label: tx('Procurement KPIs'), icon: ShoppingCart, permission: PERMISSIONS.analytics.procurement },
      { to: '/analytics/sales', label: tx('Sales KPIs'), icon: ReceiptText, permission: PERMISSIONS.analytics.sales },
      { to: '/analytics/fleet', label: tx('Fleet & Delivery KPIs'), icon: Truck, permission: PERMISSIONS.analytics.fleet },
      { to: '/analytics/maintenance', label: tx('Maintenance KPIs'), icon: Wrench, permission: PERMISSIONS.analytics.maintenance },
      { to: '/analytics/finance', label: tx('Finance KPIs'), icon: Landmark, permission: PERMISSIONS.analytics.finance },
      { to: '/analytics/branches', label: tx('Branch KPIs'), icon: Network, permission: PERMISSIONS.analytics.branch },
    ],
  },
  {
    // Quarry & extraction (ADR-S012, Track C 2.2): sites, extraction into ROM stock, blasting, controlled explosives.
    title: 'Quarry',
    icon: Mountain,
    items: [
      { to: '/quarry', label: tx('Quarry Overview'), icon: LayoutDashboard, permission: PERMISSIONS.quarryExtraction.read },
      { to: '/quarry/extraction', label: tx('Extraction Logs'), icon: ClipboardList, permission: PERMISSIONS.quarryExtraction.read },
      { to: '/quarry/haulage', label: tx('Haulage'), icon: Truck, permission: PERMISSIONS.quarryExtraction.read },
      { to: '/quarry/stockpiles', label: tx('ROM Stockpiles'), icon: Layers, permission: PERMISSIONS.quarrySite.read },
      { to: '/quarry/plans', label: tx('Plans & Targets'), icon: Target, permission: PERMISSIONS.quarryExtraction.read },
      { to: '/quarry/losses', label: tx('Losses'), icon: TrendingDown, permission: PERMISSIONS.quarryExtraction.read },
      { to: '/quarry/blasts', label: tx('Drilling & Blasting'), icon: Bomb, permission: PERMISSIONS.quarryBlasting.read },
      { to: '/quarry/explosive-issues', label: tx('Explosive Issues'), icon: ShieldAlert, permission: PERMISSIONS.quarryBlasting.read },
      { to: '/quarry/magazines', label: tx('Magazines & Explosives'), icon: Warehouse, permission: PERMISSIONS.quarryBlasting.read },
      { to: '/quarry/sites', label: tx('Quarry Sites'), icon: MapPinned, permission: PERMISSIONS.quarrySite.read },
      { to: '/quarry/materials', label: tx('Materials & Grades'), icon: Gem, permission: PERMISSIONS.quarrySite.read },
      { to: '/quarry/reports', label: tx('Quarry Reports'), icon: BarChart3, permission: PERMISSIONS.quarry.report },
      { to: '/quarry/settings', label: tx('Quarry Settings'), icon: Settings2, permission: PERMISSIONS.quarry.settings },
    ],
  },
  {
    title: 'Communication',
    icon: MessagesSquare,
    items: [
      { to: '/communication', label: tx('Inbox'), icon: MessagesSquare, permission: PERMISSIONS.communication.conversationRead },
      { to: '/communication/channels', label: tx('Channels'), icon: Radio, permission: PERMISSIONS.communication.channelRead },
      { to: '/communication/rules', label: tx('Automation'), icon: Zap, permission: PERMISSIONS.communication.channelManage },
    ],
  },
  {
    title: 'Fixed Assets',
    icon: Landmark,
    items: [
      { to: '/fixed-assets', label: tx('Dashboard'), icon: LayoutDashboard, permission: PERMISSIONS.fixedAsset.read },
      { to: '/fixed-assets/register', label: tx('Asset Register'), icon: Building2, permission: PERMISSIONS.fixedAsset.read },
      { to: '/fixed-assets/categories', label: tx('Asset Categories'), icon: Tag, permission: PERMISSIONS.assetCategory.read },
    ],
  },
  {
    title: 'Beverage Control',
    icon: Wine,
    flag: 'VITE_ENABLE_BEVERAGE',
    items: [
      { to: '/beverage', label: tx('Alcohol Dashboard'), icon: BarChart3, permission: PERMISSIONS.beverage.read },
      { to: '/beverage/count', label: tx('Bottle Count'), icon: Scale, permission: PERMISSIONS.beverage.count },
    ],
  },
  {
    title: 'Settings',
    icon: SettingsIcon,
    items: [
      { to: '/approvals', label: tx('Approvals'), icon: ShieldCheck, permission: 'approvals:read' },
      { to: '/approval-workflows', label: tx('Approval Workflows'), icon: Shield, permission: 'approvals:read' },
      { to: '/approval-controls', label: tx('Approval Controls'), icon: Shield, permission: 'approvals:read' },
      { to: '/settings/document-numbering', label: tx('Document Numbering'), icon: SettingsIcon, permission: 'document_numbering:read' },
      { to: '/approval-policies', label: tx('Approval Policies (legacy)'), icon: Shield, permission: 'approvals:read' },
      { to: '/staff', label: tx('Staff'), icon: UserCog, permission: PERMISSIONS.user.read },
      { to: '/staff/roles', label: tx('Roles & Permissions'), icon: Shield, permission: PERMISSIONS.role.read },
      { to: '/settings/portal-accounts', label: tx('Customer Portal Accounts'), icon: Users2, permission: PERMISSIONS.portal.manage },
      { to: '/settings/report-permissions', label: tx('Report Permissions'), icon: BarChart3, permission: PERMISSIONS.role.read },
      { to: '/settings/payment-methods', label: tx('Payment Methods'), icon: Wallet, permission: PERMISSIONS.account.read },
      { to: '/settings/registers', label: tx('Registers'), icon: Banknote, permission: PERMISSIONS.cashRegister.read },
      { to: '/settings/devices', label: tx('Offline devices'), icon: Smartphone, permission: PERMISSIONS.organization.read },
      { to: '/settings/backup', label: tx('Backup'), icon: HardDrive, permission: PERMISSIONS.backup.read },
      { to: '/settings/company', label: tx('Company Settings'), icon: Landmark, permission: PERMISSIONS.setting.read },
      { to: '/settings/developer', label: tx('Developer Settings'), icon: SettingsIcon, permission: PERMISSIONS.setting.read },
      { to: '/settings/license', label: tx('Licence'), icon: ShieldCheck, permission: PERMISSIONS.setting.read },
    ],
  },
];

// Flags are build-time constants under Vite, so this resolves once.
const VISIBLE_SECTIONS = NAV_SECTIONS.filter((s) => flagEnabled(s.flag));

// Collapsed (icon-only) sidebar keeps sections flat; accordion is only for expanded mode.
const SIDEBAR_EXPAND_STATE_KEY = 'poscafe.sidebar.expandedSections';

// Localized section titles for the accordion parent buttons.
const SECTION_TITLE_KEYS: Record<string, string> = {
  Communication: 'nav.communication',
  POS: 'nav.pos',
  'Master Data': 'nav.masterData',
  Sales: 'nav.sales',
  CRM: 'nav.crm',
  Inventory: 'nav.inventory',
  Customers: 'nav.customers',
  Suppliers: 'nav.suppliers',
  Menus: 'nav.menus',
  Tables: 'nav.tables',
  'Beverage Control': 'nav.beverageControl',
  Rentals: 'nav.rentals',
  Fleet: 'nav.fleet',
  Quarry: 'nav.quarry',
  'Repair & Maintenance': 'nav.repairMaintenance',
  'Human Resource': 'nav.workforce',
  'Human Resources': 'nav.workforce',
  Purchases: 'nav.purchasing',
  Expenses: 'nav.expenses',
  'Cash Flow': 'nav.cashFlow',
  'Cash Flow & Accounts': 'nav.cashFlowAccounts',
  Tasks: 'nav.tasks',
  'Task Management': 'nav.taskManagement',
  Accounting: 'nav.accounting',
  'Fixed Assets': 'nav.fixedAssets',
  Manufacturing: 'nav.manufacturing',
  Production: 'nav.production',
  Bakery: 'nav.bakery',
  Settings: 'nav.settings',
};

const sectionTitle = (title: string | undefined, t: (k: string) => string) =>
  title ? (SECTION_TITLE_KEYS[title] ? t(SECTION_TITLE_KEYS[title]) : tx(title)) : '';

export function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { theme: sb } = useSidebarTheme();
  const user = useAuthStore((s) => s.user);
  // Subscribe to the list so the menu re-renders when permissions are refreshed.
  const permissions = useAuthStore((s) => s.permissions);
  const hasPermission = (p: string) => permissions.includes(p);
  // Report visibility is configurable per role (Settings → Report Permissions).
  const canViewReport = useCanViewReport();
  const clear = useAuthStore((s) => s.clear);
  const org = useAuthStore((s) => s.organization);
  const setOrganization = useAuthStore((s) => s.setOrganization);
  const [mobileOpen, setMobileOpen] = useState(false);

  // Permissions are cached at sign-in; refresh them so role changes (and permissions added by
  // upgrades) apply to an open session without signing out and back in.
  const setPermissions = useAuthStore((s) => s.setPermissions);
  const userId = user?.id;
  useEffect(() => {
    if (!userId) return;
    let cancelled = false;
    api.get<{ permissions?: string[] }>('/auth/me')
      .then(({ data }) => { if (!cancelled && Array.isArray(data.permissions)) setPermissions(data.permissions); })
      .catch(() => { /* offline or signed out: keep the cached list */ });
    return () => { cancelled = true; };
  }, [userId, setPermissions]);
  const [searchOpen, setSearchOpen] = useState(false);
  const { collapsed: sidebarCollapsed, setCollapsed: setSidebarCollapsed } = useSidebarStore();

  // Developer Settings (/settings/developer) can switch optional modules off;
  // a disabled module's parent nav group disappears from the sidebar.
  //
  // The licence is the ceiling above that: a module nobody paid for is hidden
  // whatever the local switch says. While the status is still loading the
  // licence imposes no ceiling at all, so navigation never blanks out waiting
  // for a request — the server still refuses an unlicensed route with a clear
  // message if someone reaches it by URL.
  const { data: orgFeatures } = useOrgFeatures();
  const { data: license } = useLicense();
  // Menus and Tables only apply to the cafe/restaurant POS mode.
  const { data: posSettings } = usePosSettings();
  const sections = VISIBLE_SECTIONS.filter((s) =>
    sectionEnabled(orgFeatures, s.title, license?.features, posSettings?.posMode),
  );

  // Accordion state: which titled sections are expanded (expanded sidebar only).
  const [expanded, setExpanded] = useState<Record<string, boolean>>(() => {
    try {
      const raw = localStorage.getItem(SIDEBAR_EXPAND_STATE_KEY);
      return raw ? (JSON.parse(raw) as Record<string, boolean>) : {};
    } catch {
      return {};
    }
  });
  const persistExpanded = (next: Record<string, boolean>) => {
    setExpanded(next);
    try {
      localStorage.setItem(SIDEBAR_EXPAND_STATE_KEY, JSON.stringify(next));
    } catch {
      /* ignore quota / privacy-mode errors */
    }
  };
  const toggleSection = (title: string) =>
    persistExpanded({ ...expanded, [title]: !expanded[title] });

  // Auto-expand the section containing the active route so it's never hidden.
  const [autoExpanded, setAutoExpanded] = useState(false);
  useEffect(() => {
    if (sidebarCollapsed || autoExpanded) return;
    const activeSection = VISIBLE_SECTIONS.find(
      (s) =>
        s.title &&
        s.items.some(
          (n) => n.to !== '/' && (location.pathname === n.to || location.pathname.startsWith(`${n.to}/`)),
        ),
    );
    if (activeSection?.title && !expanded[activeSection.title]) {
      persistExpanded({ ...expanded, [activeSection.title]: true });
    }
    setAutoExpanded(true);
  }, [location.pathname, sidebarCollapsed]);

  // Keep the org (incl. base currency) in sync with the server on boot.
  useEffect(() => {
    let cancelled = false;
    api
      .get('/organizations/me')
      .then((res) => {
        if (cancelled || !res.data?.id) return;
        const o = res.data;
        setOrganization({
          id: o.id,
          code: o.code,
          name: o.name,
          currencyCode: o.currencyCode,
          timezone: o.timezone,
        });
      })
      .catch(() => {
        /* token invalid — protected route handles it */
      });
    return () => {
      cancelled = true;
    };
  }, [setOrganization]);

  // Auto-collapse sidebar on POS terminal, restore on other pages.
  useEffect(() => {
    setSidebarCollapsed(location.pathname.startsWith('/pos/terminal'));
  }, [location.pathname]);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setSearchOpen(true);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  // Close mobile drawer on navigation.
  useEffect(() => setMobileOpen(false), [location.pathname]);

  const allItems = sections.flatMap((s) => s.items);
  const current =
    allItems.find(
      (n) =>
        n.to !== '/' &&
        (location.pathname === n.to || location.pathname.startsWith(`${n.to}/`)),
    )?.label ?? (location.pathname === '/' ? tx('Dashboard') : '');

  const logout = () => {
    clear();
    navigate('/login', { replace: true });
  };

  // Hide the app-shell header (Theme / User profile) on the full-screen POS
  // selling terminal — the Terminal's own Topbar covers those controls.
  const hideHeader = location.pathname.startsWith('/pos/terminal');

  // Page transition: a short fade-rise of the content area on every route
  // change. WAAPI on the existing element — no remount, so page state and
  // nested layouts survive. Skipped on the selling terminal (speed first).
  const mainRef = useRef<HTMLElement>(null);
  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) { firstRender.current = false; return; }
    const el = mainRef.current;
    if (!el || hideHeader) return;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    el.animate(
      [
        { opacity: 0, transform: 'translateY(8px)' },
        { opacity: 1, transform: 'translateY(0)' },
      ],
      { duration: 340, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' },
    );
  }, [location.pathname, hideHeader]);

  // ── Sidebar rendering: themed background, brand tile, themed nav items ──
  const renderNav = (onItemClick?: () => void, collapsed = false) => {
    return (
      <nav className="flex-1 space-y-1 overflow-y-auto px-1 py-1">
        {sections.map((section, idx) => {
          const items = section.items.filter(
                      (i) =>
                        flagEnabled(i.flag) &&
                        (!i.permission || hasPermission(i.permission)) &&
                        (!i.reportId ||
                          (Array.isArray(i.reportId)
                            ? i.reportId.some((id) => canViewReport(id))
                            : canViewReport(i.reportId))),
                    );
          if (items.length === 0) return null;
          const isOpen = !section.title || (!collapsed && expanded[section.title]);
          // Collapsed rail: one icon per section, not every item — the full
          // list ran far down the page. Clicking it opens the sidebar on that section.
          if (collapsed && section.title) {
            const SectionIcon = section.icon ?? items[0].icon;
            const title = section.title;
            const active = items.some((i) => i.to !== '/' && location.pathname.startsWith(i.to));
            return (
              <button
                key={idx}
                type="button"
                onClick={() => {
                  persistExpanded({ ...expanded, [title]: true });
                  setSidebarCollapsed(false);
                }}
                className={cn('sb-link press relative flex w-full items-center justify-center rounded-lg px-2 py-2', active && 'is-active')}
                title={sectionTitle(title, t)}
                aria-label={sectionTitle(title, t)}
              >
                {active && (
                  <span
                    className="sb-active-bar absolute left-0 top-1/2 h-6 w-[3px] rounded-r-full"
                    style={{ background: sb.sidebarActiveBar, boxShadow: `0 0 10px ${sb.sidebarActiveBar}` }}
                  />
                )}
                <SectionIcon className="sb-link-icon h-4 w-4 shrink-0" style={{ width: 16, height: 16 }} />
              </button>
            );
          }
          return (
            <div key={idx} className="space-y-0">
              {section.title && !collapsed ? (
                <button
                  type="button"
                  onClick={() => toggleSection(section.title as string)}
                  className="sb-link press relative flex w-full items-center gap-3 rounded-lg px-3 py-2 text-[15px]"
                  aria-expanded={isOpen}
                >
                  {section.icon && (
                    <section.icon className="sb-link-icon h-4 w-4 shrink-0" style={{ width: 16, height: 16 }} />
                  )}
                  <span className="flex-1 truncate text-left tracking-[0.01em]">{sectionTitle(section.title, t)}</span>
                  <ChevronDown
                    className={cn('h-3.5 w-3.5 shrink-0 opacity-70 transition-transform duration-200 ease-out', !isOpen && '-rotate-90')}
                  />
                </button>
              ) : null}
              {isOpen &&
                items.map((item, i) => {
                const Icon = item.icon;
                return (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.to === '/' || item.to === '/returns' || item.to === '/accounts' || item.to === '/accounts/cash-accounts' || item.to === '/tables'}
                    onClick={onItemClick}
                    className={({ isActive }) =>
                      cn(
                        'sb-link press group relative flex items-center gap-3 rounded-lg px-3 py-2 text-[15px]',
                        section.title && !collapsed && 'enter-up pl-6',
                        isActive && 'is-active',
                        collapsed && 'justify-center px-2',
                      )
                    }
                    style={{ '--i': Math.min(i, 12) } as CSSProperties}
                    title={collapsed ? item.label : undefined}
                  >
                    {({ isActive }) => (
                      <>
                        {isActive && (
                          <span
                            className="sb-active-bar absolute left-0 top-1/2 h-6 w-[3px] rounded-r-full"
                            style={{ background: sb.sidebarActiveBar, boxShadow: `0 0 10px ${sb.sidebarActiveBar}` }}
                          />
                        )}
                        <Icon className="sb-link-icon h-4 w-4 shrink-0" style={{ width: 16, height: 16 }} />
                        {!collapsed && <span className="flex-1 truncate tracking-[0.01em]">{item.label}</span>}
                        {item.badge && !collapsed && (
                          <span
                            className="rounded-full px-1.5 py-0.5 text-[10px] font-bold text-white"
                            style={{ background: sb.badgeBg, minWidth: 18, textAlign: 'center', lineHeight: 'tight' }}
                          >
                            {item.badge}
                          </span>
                        )}
                      </>
                    )}
                  </NavLink>
                );
              })}
            </div>
          );
        })}
      </nav>
    );
  };

  // ── Brand tile + section heading text inside the sidebar ──
  const sidebarInner = (collapsed = false) => {
    const toggle = () => setSidebarCollapsed(!sidebarCollapsed);
    return (
      <div className="flex h-full flex-col" style={{ background: sb.sidebar }}>
        {/* Brand Header */}
        <div
          className="flex h-[58px] shrink-0 items-center gap-2 px-4"
          style={{ borderBottom: `1px solid ${sb.sidebarBorder}` }}
        >
          <div
            className="flex shrink-0 items-center justify-center rounded-xl"
            style={{
              width: 34,
              height: 34,
              background: sb.brandBg,
              border: '1px solid rgba(255,255,255,0.18)',
              boxShadow: '0 0 0 1px rgba(217,184,114,0.35), 0 6px 16px -6px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.25)',
            }}
          >
            <Coffee style={{ width: 18, height: 18, color: '#fff' }} />
          </div>
          {!collapsed && (
            <div className="ml-1 flex flex-1 flex-col leading-none">
              <span style={{ color: '#fff', fontWeight: 700, fontSize: 16, letterSpacing: '-0.3px' }}>
                {org?.name ?? tx('ERP')}
              </span>
              <span
                style={{
                  color: sb.sidebarMuted,
                  fontSize: 10,
                  fontWeight: 500,
                  letterSpacing: '0.08em',
                  textTransform: 'uppercase',
                  marginTop: 2,
                }}
              >
                {tx('Point of Sale')}
              </span>
            </div>
          )}
          <button
            onClick={toggle}
            className="ml-auto flex shrink-0 items-center justify-center rounded-lg p-1 transition-colors hover:bg-white/10"
            style={{ color: sb.sidebarMuted }}
            title={collapsed ? tx('Expand sidebar') : tx('Collapse sidebar')}
          >
            {collapsed ? <PanelLeft className="h-4 w-4" /> : <PanelLeftClose className="h-4 w-4" />}
          </button>
        </div>

        {renderNav(() => setMobileOpen(false), collapsed)}

      </div>
    );
  };

  return (
    <div className="flex min-h-screen">
      {/* Desktop sidebar */}
      <aside
        className={cn(
          'sticky top-0 hidden h-screen shrink-0 flex-col transition-all duration-200 md:flex print:hidden',
          sidebarCollapsed ? 'w-16' : 'w-72',
        )}
        style={{ background: sb.sidebar, boxShadow: '1px 0 0 rgba(217,184,114,0.22), 8px 0 30px -18px rgba(0,0,0,0.45)' }}
      >
        {sidebarInner(sidebarCollapsed)}
      </aside>

      {/* Mobile drawer */}
      {mobileOpen && (
        <div className="fixed inset-0 z-40 md:hidden" role="dialog" aria-modal="true">
          <div className="fade-in absolute inset-0 bg-black/50 backdrop-blur-[2px]" onClick={() => setMobileOpen(false)} />
          <aside className="drawer-in absolute inset-y-0 left-0 flex w-72 flex-col shadow-2xl">
            {sidebarInner(false)}
          </aside>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        {!hideHeader && (
          <header className="app-shell-header sticky top-0 z-30 flex h-12 items-center justify-between gap-2 border-b border-border/70 bg-background/75 px-4 backdrop-blur-xl backdrop-saturate-150 print:hidden md:px-6">
            <div className="flex min-w-0 items-center gap-2">
              <Button
                variant="ghost"
                size="icon"
                className="hidden md:inline-flex"
                onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
                aria-label={sidebarCollapsed ? tx('Expand sidebar') : tx('Collapse sidebar')}
              >
                {sidebarCollapsed ? <PanelLeft className="h-5 w-5" /> : <PanelLeftClose className="h-5 w-5" />}
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="md:hidden"
                onClick={() => setMobileOpen(true)}
                aria-label={tx('Open menu')}
              >
                <Menu className="h-5 w-5" />
              </Button>
              <div className="truncate text-sm text-muted-foreground">
                <span key={current} className="enter-up inline-block font-semibold tracking-tight text-foreground">{current}</span>
              </div>
            </div>
            <div className="flex items-center gap-1 sm:gap-2">
              <LanguageSwitcher />
              <ThemePicker />
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    type="button"
                    className="flex items-center gap-2 rounded-full px-1.5 py-1 outline-none transition-colors hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <span
                      className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-sm font-semibold text-primary"
                      aria-hidden="true"
                    >
                      {(user?.firstName?.[0] ?? '').toUpperCase()}
                      {(user?.lastName?.[0] ?? '').toUpperCase()}
                    </span>
                    <span className="hidden text-sm font-medium text-foreground sm:inline">
                      {user?.firstName}
                    </span>
                    <ChevronDown className="hidden h-4 w-4 text-muted-foreground sm:inline" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-56">
                  <DropdownMenuLabel className="flex flex-col gap-0.5">
                    <span className="text-sm font-semibold text-foreground">
                      {user?.firstName} {user?.lastName}
                    </span>
                    <span className="truncate text-xs font-normal text-muted-foreground">
                      {user?.email}
                    </span>
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <div className="px-2 py-1.5">
                    <label className="mb-1 block text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                      {tx('Active Branch')}
                    </label>
                    <BranchSwitcher />
                  </div>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={logout} className="cursor-pointer text-destructive focus:text-destructive">
                    <LogOut className="mr-2 h-4 w-4" />
                    <span>{t('auth.signOut')}</span>
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </header>
        )}

        <main
          ref={mainRef}
          className={`flex-1 overflow-auto ${
            location.pathname.startsWith('/pos/terminal') ? 'p-1 md:p-1' : 'p-1 md:p-1'
          }`}
        >
          {/* Above the content, not over it: a licence notice must be impossible
              to miss and equally impossible to be in the way of a sale. */}
          <LicenseBanner />
          <PageSuspense>
            <Outlet />
          </PageSuspense>
        </main>
      </div>

      <GlobalSearch open={searchOpen} onOpenChange={setSearchOpen} />
      <PendingCashOperations />
      <PushBootstrap />
      <LicenseBlockedDialog />
    </div>
  );
}
