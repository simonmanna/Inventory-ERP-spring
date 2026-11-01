import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAppLanguage } from '@/lib/i18n/use-app-language';
import { ProtectedRoute } from '@/components/protected-route';
import { AppShell } from '@/components/layout/app-shell';
import { PageSuspense } from '@/components/page-loading';
import { crmRoutes } from '@/pages/crm/routes';
import { salesRoutes } from '@/pages/sales/routes';
import { procurementPrintRoutes, procurementPublicRoutes, procurementRoutes } from '@/pages/procurement/routes';
import { precastRoutes } from '@/pages/manufacturing/precast/routes';
import { shopFloorRoutes } from '@/pages/manufacturing/shop-floor/routes';
import { planningBoardRoutes } from '@/pages/manufacturing/planning-board/routes';
import { mfgCostingRoutes } from '@/pages/manufacturing/costing/routes';
import { RequirePermission } from '@/components/require-permission';
import { RequireReport } from '@/components/require-report';
import { PERMISSIONS } from '@erp/shared';
import { fleetRoutes } from '@/pages/fleet/routes';
import { qualityRoutes } from '@/pages/quality/routes';
import { quarryRoutes } from '@/pages/quarry/routes';
import { analyticsRoutes } from '@/pages/analytics/routes';
import { crushingRoutes } from '@/pages/crushing/routes';
import { driverRoutes } from '@/pages/driver/routes';
import { portalRoutes } from '@/pages/portal/routes';

const LoginPage = lazy(() => import('@/pages/login').then((m) => ({ default: m.LoginPage })));
const DashboardPage = lazy(() => import('@/pages/dashboard').then((m) => ({ default: m.DashboardPage })));
const PartnersPage = lazy(() => import('@/pages/partners').then((m) => ({ default: m.PartnersPage })));
const CustomersPage = lazy(() => import('@/pages/customers').then((m) => ({ default: m.CustomersPage })));
const CustomerDetailPage = lazy(() => import('@/pages/customers').then((m) => ({ default: m.CustomerDetailPage })));
const SuppliersPage = lazy(() => import('@/pages/suppliers').then((m) => ({ default: m.SuppliersPage })));
const SupplierDetailPage = lazy(() => import('@/pages/suppliers').then((m) => ({ default: m.SupplierDetailPage })));
const SupplierLedgerPage = lazy(() => import('@/pages/purchasing/supplier-ledger').then((m) => ({ default: m.SupplierLedgerPage })));
const ProductsPage = lazy(() => import('@/pages/products').then((m) => ({ default: m.ProductsPage })));
const UomPage = lazy(() => import('@/pages/uom').then((m) => ({ default: m.UomPage })));
const MenuPage = lazy(() => import('@/pages/menu').then((m) => ({ default: m.MenuPage })));
const MenuDetailPage = lazy(() => import('@/pages/menu/MenuDetailPage'));
const ModifiersPage = lazy(() => import('@/pages/menu/ModifiersPage'));
const ComboListPage = lazy(() => import('@/pages/pos/ComboListPage'));
const AccompanimentGroupsPage = lazy(() => import('@/pages/menu/AccompanimentGroupsPage'));
const SettingsPage = lazy(() => import('@/pages/settings').then((m) => ({ default: m.SettingsPage })));
const DevCompanySettingsPage = lazy(() => import('@/pages/settings/DevCompanySettingsPage').then((m) => ({ default: m.DevCompanySettingsPage })));
const CompanySettingsPage = lazy(() => import('@/pages/settings/CompanySettingsPage').then((m) => ({ default: m.CompanySettingsPage })));
const LicensePage = lazy(() => import('@/pages/settings/LicensePage').then((m) => ({ default: m.LicensePage })));
const ReceiptSettingsPage = lazy(() => import('@/pages/pos/ReceiptSettingsPage').then((m) => ({ default: m.ReceiptSettingsPage })));
const DevicesPage = lazy(() => import('@/pages/pos/DevicesPage').then((m) => ({ default: m.DevicesPage })));
const DeadLettersPage = lazy(() => import('@/pages/pos/DeadLettersPage').then((m) => ({ default: m.DeadLettersPage })));
const PostingMonitorPage = lazy(() => import('@/pages/pos/PostingMonitorPage').then((m) => ({ default: m.PostingMonitorPage })));
const ApprovalsPage = lazy(() => import('@/pages/approvals').then((m) => ({ default: m.ApprovalsPage })));
const ApprovalPoliciesPage = lazy(() => import('@/pages/approval-policies').then((m) => ({ default: m.ApprovalPoliciesPage })));
const ApprovalWorkflowsPage = lazy(() => import('@/pages/approval-workflows').then((m) => ({ default: m.ApprovalWorkflowsPage })));
const ApprovalControlsPage = lazy(() => import('@/pages/approval-controls').then((m) => ({ default: m.ApprovalControlsPage })));
const DocumentNumberingPage = lazy(() => import('@/pages/settings/DocumentNumberingPage').then((m) => ({ default: m.DocumentNumberingPage })));
const RecurringPage = lazy(() => import('@/pages/recurring').then((m) => ({ default: m.RecurringPage })));
const WebhooksPage = lazy(() => import('@/pages/webhooks').then((m) => ({ default: m.WebhooksPage })));
const FilesPage = lazy(() => import('@/pages/files').then((m) => ({ default: m.FilesPage })));
const ModulesPage = lazy(() => import('@/pages/modules').then((m) => ({ default: m.ModulesPage })));
const BackupPage = lazy(() => import('@/pages/settings/BackupPage').then((m) => ({ default: m.BackupPage })));
const AssetDashboardPage = lazy(() => import('@/pages/fixed-asset/AssetDashboardPage').then((m) => ({ default: m.AssetDashboardPage })));
const AssetCategoriesPage = lazy(() => import('@/pages/fixed-asset/AssetCategoriesPage').then((m) => ({ default: m.AssetCategoriesPage })));
const AssetsPage = lazy(() => import('@/pages/fixed-asset/AssetsPage').then((m) => ({ default: m.AssetsPage })));
const AssetDetailPage = lazy(() => import('@/pages/fixed-asset/AssetDetailPage').then((m) => ({ default: m.AssetDetailPage })));
const ProductionDashboardPage = lazy(() => import('@/pages/manufacturing/ProductionDashboardPage').then((m) => ({ default: m.ProductionDashboardPage })));
const BomsPage = lazy(() => import('@/pages/manufacturing/BomsPage').then((m) => ({ default: m.BomsPage })));
const ProductionOrdersPage = lazy(() => import('@/pages/manufacturing/ProductionOrdersPage').then((m) => ({ default: m.ProductionOrdersPage })));
const ProductionOrderDetailPage = lazy(() => import('@/pages/manufacturing/ProductionOrderDetailPage').then((m) => ({ default: m.ProductionOrderDetailPage })));
const ManufacturingPlanningPage = lazy(() => import('@/pages/manufacturing/ManufacturingPlanningPage').then((m) => ({ default: m.ManufacturingPlanningPage })));
const ManufacturingReportsPage = lazy(() => import('@/pages/manufacturing/ManufacturingReportsPage').then((m) => ({ default: m.ManufacturingReportsPage })));
const ManufacturingResourcesPage = lazy(() => import('@/pages/manufacturing/ManufacturingResourcesPage').then((m) => ({ default: m.ManufacturingResourcesPage })));
const MrpPage = lazy(() => import('@/pages/manufacturing/MrpPage').then((m) => ({ default: m.MrpPage })));
const ShopFloorPage = lazy(() => import('@/pages/manufacturing/ShopFloorPage').then((m) => ({ default: m.ShopFloorPage })));
const BomDetailPage = lazy(() => import('@/pages/manufacturing/BomDetailPage').then((m) => ({ default: m.BomDetailPage })));
const EngineeringChangesPage = lazy(() => import('@/pages/manufacturing/EngineeringChangesPage').then((m) => ({ default: m.EngineeringChangesPage })));
const StageWipReportPage = lazy(() => import('@/pages/manufacturing/StageWipReportPage').then((m) => ({ default: m.StageWipReportPage })));
const ChartOfAccountsPage = lazy(() => import('@/pages/accounting/chart-of-accounts').then((m) => ({ default: m.ChartOfAccountsPage })));
const AccountDetailPage = lazy(() => import('@/pages/accounting/AccountDetailPage').then((m) => ({ default: m.AccountDetailPage })));
const CashFlowReportPage = lazy(() => import('@/pages/accounting/cash-flow-report').then((m) => ({ default: m.CashFlowReportPage })));
const MoneyOverviewPage = lazy(() => import('@/pages/money/MoneyOverviewPage').then((m) => ({ default: m.MoneyOverviewPage })));
const MoneyAccountsPage = lazy(() => import('@/pages/money/MoneyAccountsPage').then((m) => ({ default: m.MoneyAccountsPage })));
const MoneyAccountDetailPage = lazy(() => import('@/pages/money/MoneyAccountDetailPage').then((m) => ({ default: m.MoneyAccountDetailPage })));
const MoneyActivityPage = lazy(() => import('@/pages/money/MoneyActivityPage').then((m) => ({ default: m.MoneyActivityPage })));
const MoneySettlementsPage = lazy(() => import('@/pages/money/MoneySettlementsPage').then((m) => ({ default: m.MoneySettlementsPage })));
const PaymentMethodsSettingsPage = lazy(() => import('@/pages/settings/PaymentMethodsSettingsPage').then((m) => ({ default: m.PaymentMethodsSettingsPage })));
const RegistersSettingsPage = lazy(() => import('@/pages/settings/RegistersSettingsPage').then((m) => ({ default: m.RegistersSettingsPage })));
const AccountMappingsPage = lazy(() => import('@/pages/accounting/account-mappings').then((m) => ({ default: m.AccountMappingsPage })));
const AccountCategoriesPage = lazy(() => import('@/pages/accounting/account-categories').then((m) => ({ default: m.AccountCategoriesPage })));
const JournalEntriesPage = lazy(() => import('@/pages/accounting/journal-entries').then((m) => ({ default: m.JournalEntriesPage })));
const GeneralLedgerPage = lazy(() => import('@/pages/accounting/general-ledger').then((m) => ({ default: m.GeneralLedgerPage })));
const ProfitAndLossPage = lazy(() => import('@/pages/accounting/profit-and-loss').then((m) => ({ default: m.ProfitAndLossPage })));
const CashFlowPage = lazy(() => import('@/pages/accounting/cash-flow').then((m) => ({ default: m.CashFlowPage })));
const AccountLedgerPage = lazy(() => import('@/pages/accounting/account-ledger').then((m) => ({ default: m.AccountLedgerPage })));
const TieOutPage = lazy(() => import('@/pages/accounting/tieout').then((m) => ({ default: m.TieOutPage })));
const PosGlReconciliationPage = lazy(() => import('@/pages/accounting/pos-gl-reconciliation').then((m) => ({ default: m.PosGlReconciliationPage })));
const AuditLogPage = lazy(() => import('@/pages/accounting/audit-log').then((m) => ({ default: m.AuditLogPage })));
const FiscalPeriodsPage = lazy(() => import('@/pages/accounting/fiscal-periods'));
const FiscalYearsPage = lazy(() => import('@/pages/accounting/fiscal-years'));
const FiscalYearClosePage = lazy(() => import('@/pages/accounting/fiscal-year-close'));
const AnalyticDimensionsPage = lazy(() => import('@/pages/accounting/analytic-dimensions'));
const TaxesPage = lazy(() => import('@/pages/accounting/taxes'));
const PaymentTermsPage = lazy(() => import('@/pages/accounting/payment-terms'));
const FiscalPositionsPage = lazy(() => import('@/pages/accounting/fiscal-positions'));
const CostCentersPage = lazy(() => import('@/pages/accounting/cost-centers').then((m) => ({ default: m.CostCentersPage })));
const CurrencyPage = lazy(() => import('@/pages/accounting/currency').then((m) => ({ default: m.CurrencyPage })));
const InventoryValuationPage = lazy(() => import('@/pages/accounting/inventory-valuation').then((m) => ({ default: m.InventoryValuationPage })));
const InventoryGlTieOutPage = lazy(() => import('@/pages/accounting/inventory-gl-tieout').then((m) => ({ default: m.InventoryGlTieOutPage })));
const YearEndClosePage = lazy(() => import('@/pages/accounting/year-end-close').then((m) => ({ default: m.YearEndClosePage })));
const PeriodCloseChecklistPage = lazy(() => import('@/pages/accounting/period-close-checklist').then((m) => ({ default: m.PeriodCloseChecklistPage })));
const TrialBalancePage = lazy(() => import('@/pages/accounting/trial-balance').then((m) => ({ default: m.TrialBalancePage })));
const BudgetsPage = lazy(() => import('@/pages/accounting/budgets').then((m) => ({ default: m.BudgetsPage })));
const BudgetDetailPage = lazy(() => import('@/pages/accounting/budget-detail').then((m) => ({ default: m.BudgetDetailPage })));
const OpeningBalancesPage = lazy(() => import('@/pages/accounting/opening-balances').then((m) => ({ default: m.OpeningBalancesPage })));
const TaxReportPage = lazy(() => import('@/pages/accounting/tax-report').then((m) => ({ default: m.TaxReportPage })));
const BankReconciliationPage = lazy(() => import('@/pages/accounting/bank-reconciliation').then((m) => ({ default: m.BankReconciliationPage })));
const ConsolidatedReportPage = lazy(() => import('@/pages/accounting/consolidated-report').then((m) => ({ default: m.ConsolidatedReportPage })));
const StatementsPage = lazy(() => import('@/pages/invoicing/statements').then((m) => ({ default: m.StatementsPage })));
const DetailedAccountingReportPage = lazy(() => import('@/pages/accounting/detailed-report').then((m) => ({ default: m.DetailedAccountingReportPage })));
const BalanceSheetPage = lazy(() => import('@/pages/accounting/balance-sheet').then((m) => ({ default: m.BalanceSheetPage })));
const InvoicesPage = lazy(() => import('@/pages/invoicing/invoices').then((m) => ({ default: m.InvoicesPage })));
const InvoiceCreatePage = lazy(() => import('@/pages/invoicing/invoice-create').then((m) => ({ default: m.InvoiceCreatePage })));
const InvoiceDetailPage = lazy(() => import('@/pages/invoicing/invoice-detail').then((m) => ({ default: m.InvoiceDetailPage })));
const OrdersPage = lazy(() => import('@/pages/orders/orders').then((m) => ({ default: m.OrdersPage })));
const CommunicationInboxPage = lazy(() => import('@/pages/communication/inbox'));
const CommunicationRulesPage = lazy(() => import('@/pages/communication/rules'));
const CommunicationChannelsPage = lazy(() => import('@/pages/communication/channels'));
const OrderCreatePage = lazy(() => import('@/pages/orders/order-create').then((m) => ({ default: m.OrderCreatePage })));
const OrderDetailPage = lazy(() => import('@/pages/orders/order-detail').then((m) => ({ default: m.OrderDetailPage })));
const ArAgingPage = lazy(() => import('@/pages/invoicing/ar-aging').then((m) => ({ default: m.ArAgingPage })));
const CreditControlPage = lazy(() => import('@/pages/invoicing/credit-control').then((m) => ({ default: m.CreditControlPage })));
const PriceListsPage = lazy(() => import('@/pages/invoicing/price-lists').then((m) => ({ default: m.PriceListsPage })));
const CreditNotesPage = lazy(() => import('@/pages/invoicing/credit-notes').then((m) => ({ default: m.CreditNotesPage })));
const CreditNoteCreatePage = lazy(() => import('@/pages/invoicing/credit-note-create').then((m) => ({ default: m.CreditNoteCreatePage })));
const CreditNoteDetailPage = lazy(() => import('@/pages/invoicing/credit-note-detail').then((m) => ({ default: m.CreditNoteDetailPage })));
const PaymentsPage = lazy(() => import('@/pages/invoicing/payments').then((m) => ({ default: m.PaymentsPage })));
const MobileMoneyPage = lazy(() => import('@/pages/invoicing/MobileMoneyPage').then((m) => ({ default: m.MobileMoneyPage })));
const BankImportPage = lazy(() => import('@/pages/money/BankImportPage').then((m) => ({ default: m.BankImportPage })));
const LabelPrintPage = lazy(() => import('@/pages/labels/LabelPrintPage').then((m) => ({ default: m.LabelPrintPage })));
const PaymentDetailPage = lazy(() => import('@/pages/invoicing/payment-detail').then((m) => ({ default: m.PaymentDetailPage })));
const ReceiptsPage = lazy(() => import('@/pages/pos/ReceiptsPage').then((m) => ({ default: m.ReceiptsPage })));
const ReceiptDetailPage = lazy(() => import('@/pages/pos/ReceiptDetailPage').then((m) => ({ default: m.ReceiptDetailPage })));
const ReturnsPage = lazy(() => import('@/pages/returns/ReturnsPage').then((m) => ({ default: m.ReturnsPage })));
const NewReturnPage = lazy(() => import('@/pages/returns/NewReturnPage').then((m) => ({ default: m.NewReturnPage })));
const ReturnDetailPage = lazy(() => import('@/pages/returns/ReturnDetailPage').then((m) => ({ default: m.ReturnDetailPage })));
const ReceivablesPage = lazy(() => import('@/pages/pos/ReceivablesPage').then((m) => ({ default: m.ReceivablesPage })));
const PosSettingsPage = lazy(() => import('@/pages/pos/SettingsPage').then((m) => ({ default: m.PosSettingsPage })));
const ExpensesPage = lazy(() => import('@/pages/expenses/ExpensesPage').then((m) => ({ default: m.ExpensesPage })));
const ExpensesReportPage = lazy(() => import('@/pages/expenses/ExpensesReportPage'));
const ExpenseCategoriesPage = lazy(() => import('@/pages/expenses/ExpenseCategoriesPage'));
const SupplierPaymentsPage = lazy(() => import('@/pages/purchasing/supplier-payments').then((m) => ({ default: m.SupplierPaymentsPage })));
const VendorBillsPage = lazy(() => import('@/pages/purchasing/vendor-bills').then((m) => ({ default: m.VendorBillsPage })));
const VendorBillEditPage = lazy(() => import('@/pages/purchasing/vendor-bill-edit').then((m) => ({ default: m.VendorBillEditPage })));
const VendorBillDetailPage = lazy(() => import('@/pages/purchasing/vendor-bill-detail').then((m) => ({ default: m.VendorBillDetailPage })));
const ApAgingPage = lazy(() => import('@/pages/purchasing/ap-aging').then((m) => ({ default: m.ApAgingPage })));
const PaymentSchedulePage = lazy(() => import('@/pages/purchasing/payment-schedule').then((m) => ({ default: m.PaymentSchedulePage })));
const JournalsPage = lazy(() => import('@/pages/accounting/journals').then((m) => ({ default: m.JournalsPage })));
const JournalDetailPage = lazy(() => import('@/pages/accounting/JournalDetailPage').then((m) => ({ default: m.JournalDetailPage })));
const JournalEditPage = lazy(() => import('@/pages/accounting/JournalEditPage').then((m) => ({ default: m.JournalEditPage })));
const JournalEntryCreatePage = lazy(() => import('@/pages/accounting/journal-entry-create').then((m) => ({ default: m.JournalEntryCreatePage })));
const JournalEntryDetailPage = lazy(() => import('@/pages/accounting/journal-entry-detail').then((m) => ({ default: m.JournalEntryDetailPage })));
const InventoryPostingRulesPage = lazy(() => import('@/pages/accounting/inventory-posting-rules').then((m) => ({ default: m.InventoryPostingRulesPage })));
const ProductEditPage = lazy(() => import('@/pages/products/ProductEditPage').then((m) => ({ default: m.ProductEditPage })));
const ProcurementBudgetsPage = lazy(() => import('@/pages/procurement/procurement-budgets').then((m) => ({ default: m.ProcurementBudgetsPage })));
const ProcurementDelegationsPage = lazy(() => import('@/pages/procurement/procurement-delegations').then((m) => ({ default: m.ProcurementDelegationsPage })));
const CostPoliciesPage = lazy(() => import('@/pages/costing/CostPoliciesPage').then((m) => ({ default: m.CostPoliciesPage })));
const StandardCostsPage = lazy(() => import('@/pages/costing/StandardCostsPage').then((m) => ({ default: m.StandardCostsPage })));
const ProcessCostingPage = lazy(() => import('@/pages/costing/ProcessCostingPage').then((m) => ({ default: m.ProcessCostingPage })));
const CostCenterAccountingPage = lazy(() => import('@/pages/costing/CostCenterAccountingPage').then((m) => ({ default: m.CostCenterAccountingPage })));
const SupplierPerformancePage = lazy(() => import('@/pages/procurement/supplier-performance').then((m) => ({ default: m.SupplierPerformancePage })));
const InventoryItemsPage = lazy(() => import('@/pages/inventory/InventoryItemsPage').then((m) => ({ default: m.InventoryItemsPage })));
const InventoryDetailPage = lazy(() => import('@/pages/inventory/InventoryDetailPage'));
const StockAdjustmentsPage = lazy(() => import('@/pages/inventory/StockAdjustmentsPage').then((m) => ({ default: m.StockAdjustmentsPage })));
const WastePage = lazy(() => import('@/pages/inventory/WastePage').then((m) => ({ default: m.WastePage })));
const StockTransfersPage = lazy(() => import('@/pages/inventory/StockTransfersPage').then((m) => ({ default: m.StockTransfersPage })));
const InventoryCountPage = lazy(() => import('@/pages/inventory/InventoryCountPage').then((m) => ({ default: m.InventoryCountPage })));
const InventoryReportsPage = lazy(() => import('@/pages/inventory/InventoryReportsPage').then((m) => ({ default: m.InventoryReportsPage })));
const BottleCountPage = lazy(() => import('@/pages/beverage/BottleCountPage').then((m) => ({ default: m.BottleCountPage })));
const BeverageDashboardPage = lazy(() => import('@/pages/beverage/BeverageDashboardPage').then((m) => ({ default: m.BeverageDashboardPage })));
const RentalDashboardPage = lazy(() => import('@/pages/rental/RentalDashboardPage').then((m) => ({ default: m.RentalDashboardPage })));
const RentalAgreementsPage = lazy(() => import('@/pages/rental/RentalAgreementsPage').then((m) => ({ default: m.RentalAgreementsPage })));
const RentalAgreementCreatePage = lazy(() => import('@/pages/rental/RentalAgreementCreatePage').then((m) => ({ default: m.RentalAgreementCreatePage })));
const RentalAgreementDetailPage = lazy(() => import('@/pages/rental/RentalAgreementDetailPage').then((m) => ({ default: m.RentalAgreementDetailPage })));
const RentalUnitsPage = lazy(() => import('@/pages/rental/RentalUnitsPage').then((m) => ({ default: m.RentalUnitsPage })));
const RentalCatalogPage = lazy(() => import('@/pages/rental/RentalCatalogPage').then((m) => ({ default: m.RentalCatalogPage })));
const RentalReturnsPage = lazy(() => import('@/pages/rental/RentalReturnsPage').then((m) => ({ default: m.RentalReturnsPage })));
const RentalReportsPage = lazy(() => import('@/pages/rental/RentalReportsPage').then((m) => ({ default: m.RentalReportsPage })));
const PortalAccountsPage = lazy(() => import('@/pages/portal/PortalAccountsPage').then((m) => ({ default: m.PortalAccountsPage })));
const RepairDashboardPage = lazy(() => import('@/pages/repair/RepairDashboardPage').then((m) => ({ default: m.RepairDashboardPage })));
const RepairOrdersPage = lazy(() => import('@/pages/repair/RepairOrdersPage').then((m) => ({ default: m.RepairOrdersPage })));
const RepairOrderCreatePage = lazy(() => import('@/pages/repair/RepairOrderCreatePage').then((m) => ({ default: m.RepairOrderCreatePage })));
const RepairOrderDetailPage = lazy(() => import('@/pages/repair/RepairOrderDetailPage').then((m) => ({ default: m.RepairOrderDetailPage })));
const RepairTechniciansPage = lazy(() => import('@/pages/repair/RepairTechniciansPage').then((m) => ({ default: m.RepairTechniciansPage })));
const RepairLabourPage = lazy(() => import('@/pages/repair/RepairLabourPage').then((m) => ({ default: m.RepairLabourPage })));
const RepairJobsPage = lazy(() => import('@/pages/repair/RepairJobsPage').then((m) => ({ default: m.RepairJobsPage })));
const RepairWarrantiesPage = lazy(() => import('@/pages/repair/RepairWarrantiesPage').then((m) => ({ default: m.RepairWarrantiesPage })));
const RepairContractsPage = lazy(() => import('@/pages/repair/RepairContractsPage').then((m) => ({ default: m.RepairContractsPage })));
const RepairReportsPage = lazy(() => import('@/pages/repair/RepairReportsPage').then((m) => ({ default: m.RepairReportsPage })));
const HrDashboardPage = lazy(() => import('@/pages/hr/HrDashboardPage').then((m) => ({ default: m.HrDashboardPage })));
const HrEmployeesPage = lazy(() => import('@/pages/hr/HrEmployeesPage').then((m) => ({ default: m.HrEmployeesPage })));
const HrEmployeeDetailPage = lazy(() => import('@/pages/hr/HrEmployeeDetailPage').then((m) => ({ default: m.HrEmployeeDetailPage })));
const HrDepartmentsPage = lazy(() => import('@/pages/hr/HrDepartmentsPage').then((m) => ({ default: m.HrDepartmentsPage })));
const HrPositionsPage = lazy(() => import('@/pages/hr/HrPositionsPage').then((m) => ({ default: m.HrPositionsPage })));
const HrShiftsPage = lazy(() => import('@/pages/hr/HrShiftsPage').then((m) => ({ default: m.HrShiftsPage })));
const HrAttendancePage = lazy(() => import('@/pages/hr/HrAttendancePage').then((m) => ({ default: m.HrAttendancePage })));
const HrTimesheetsPage = lazy(() => import('@/pages/hr/HrTimesheetsPage').then((m) => ({ default: m.HrTimesheetsPage })));
const HrLeavePage = lazy(() => import('@/pages/hr/HrLeavePage').then((m) => ({ default: m.HrLeavePage })));
const HrHolidaysPage = lazy(() => import('@/pages/hr/HrHolidaysPage').then((m) => ({ default: m.HrHolidaysPage })));
const HrPayrollPage = lazy(() => import('@/pages/hr/HrPayrollPage').then((m) => ({ default: m.HrPayrollPage })));
const HrPayrollSettingsPage = lazy(() => import('@/pages/hr/HrPayrollSettingsPage').then((m) => ({ default: m.HrPayrollSettingsPage })));
const HrPayslipsPage = lazy(() => import('@/pages/hr/HrPayslipsPage').then((m) => ({ default: m.HrPayslipsPage })));
const HrAdvancesLoansPage = lazy(() => import('@/pages/hr/HrAdvancesLoansPage').then((m) => ({ default: m.HrAdvancesLoansPage })));
const HrReportsPage = lazy(() => import('@/pages/hr/HrReportsPage').then((m) => ({ default: m.HrReportsPage })));
const HrTeamPage = lazy(() => import('@/pages/hr/HrTeamPage').then((m) => ({ default: m.HrTeamPage })));
const HrDocumentsPage = lazy(() => import('@/pages/hr/HrDocumentsPage').then((m) => ({ default: m.HrDocumentsPage })));
const HrTrainingPage = lazy(() => import('@/pages/hr/HrTrainingPage').then((m) => ({ default: m.HrTrainingPage })));
const HrSelfServicePage = lazy(() => import('@/pages/hr/HrSelfServicePage').then((m) => ({ default: m.HrSelfServicePage })));
const HrRecruitmentPage = lazy(() => import('@/pages/hr/HrRecruitmentPage').then((m) => ({ default: m.HrRecruitmentPage })));
const HrOnboardingPage = lazy(() => import('@/pages/hr/HrOnboardingPage').then((m) => ({ default: m.HrOnboardingPage })));
const HrContractsPage = lazy(() => import('@/pages/hr/HrContractsPage').then((m) => ({ default: m.HrContractsPage })));
const HrReviewsPage = lazy(() => import('@/pages/hr/HrReviewsPage').then((m) => ({ default: m.HrReviewsPage })));
const StockLedgerPage = lazy(() => import('@/pages/inventory/StockLedgerPage').then((m) => ({ default: m.StockLedgerPage })));
const StockMovementDetailPage = lazy(() => import('@/pages/inventory/StockMovementDetailPage').then((m) => ({ default: m.StockMovementDetailPage })));
const LocationsPage = lazy(() => import('@/pages/inventory/LocationsPage'));
const StockReservationsPage = lazy(() => import('@/pages/inventory/StockReservationsPage').then((m) => ({ default: m.StockReservationsPage })));
const ProductSpecsPage = lazy(() => import('@/pages/products/ProductSpecsPage').then((m) => ({ default: m.ProductSpecsPage })));
const ReplenishmentPage = lazy(() => import('@/pages/inventory/ReplenishmentPage').then((m) => ({ default: m.ReplenishmentPage })));
const WarehousePage = lazy(() => import('@/pages/inventory/WarehousePage'));
const TerminalPage = lazy(() => import('@/pages/pos/Terminal'));
const TerminalWorkspace = lazy(() => import('@/pages/pos/TerminalWorkspace').then((m) => ({ default: m.TerminalWorkspace })));
const ReportsPage = lazy(() => import('@/pages/pos/ReportsPage'));
const DisplayPage = lazy(() => import('@/pages/pos/DisplayPage'));
const KdsPage = lazy(() => import('@/pages/pos/KdsPage'));
const KitchenStationsPage = lazy(() => import('@/pages/pos/KitchenStationsPage'));
const KdsReportsPage = lazy(() => import('@/pages/pos/KdsReportsPage'));
const CashRegistersPage = lazy(() => import('@/pages/pos/cash/CashRegistersPage'));
const DigitalMenuPage = lazy(() => import('@/pages/pos/DigitalMenuPage'));
const CustomerPortalPage = lazy(() => import('@/pages/portal/CustomerPortalPage'));
const TablesPage = lazy(() => import('@/pages/tables/TablesPage'));
const ReservationsPage = lazy(() => import('@/pages/tables/ReservationsPage'));
const TableReportsPage = lazy(() => import('@/pages/tables/TableReportsPage'));
const ZonesPage = lazy(() => import('@/pages/tables/ZonesPage'));
const ReportCenterPage = lazy(() => import('@/pages/reports/ReportCenterPage'));
const BiExportsPage = lazy(() => import('@/pages/reports/BiExportsPage').then((m) => ({ default: m.BiExportsPage })));
const AlertsPage = lazy(() => import('@/pages/alerts/AlertsPage').then((m) => ({ default: m.AlertsPage })));
const BranchPerformancePage = lazy(() => import('@/pages/reports/BranchPerformancePage').then((m) => ({ default: m.BranchPerformancePage })));
const SalesAnalysisPage = lazy(() => import('@/pages/reports/SalesAnalysisPage').then((m) => ({ default: m.SalesAnalysisPage })));
const ReportPermissionsPage = lazy(() => import('@/pages/settings/ReportPermissionsPage').then((m) => ({ default: m.ReportPermissionsPage })));
const StaffPage = lazy(() => import('@/pages/staff/StaffPage').then((m) => ({ default: m.StaffPage })));
const RolesPage = lazy(() => import('@/pages/staff/RolesPage').then((m) => ({ default: m.RolesPage })));
const RoleEditPage = lazy(() => import('@/pages/staff/RoleEditPage').then((m) => ({ default: m.RoleEditPage })));
const TasksPage = lazy(() => import('@/pages/tasks/TasksPage').then((m) => ({ default: m.TasksPage })));
const TaskEditPage = lazy(() => import('@/pages/tasks/TaskEditPage').then((m) => ({ default: m.TaskEditPage })));

export function App() {
  // Remount the whole tree when the language changes so tx() re-evaluates.
  const lang = useAppLanguage();
  return (
    <PageSuspense>
      <Routes key={lang}>
        <Route path="/login" element={<LoginPage />} />
        {procurementPublicRoutes}
        {/* Digital Menu — customer-facing public route (no auth, no shell). */}
        <Route path="/menu/:branchId/:tableId" element={<DigitalMenuPage />} />
        {/* Customer portal — magic-link token, no staff login (ADR-S011 S10). */}
        <Route path="/sales-portal" element={<CustomerPortalPage />} />
        <Route element={<ProtectedRoute />}>
          {/* KDS — full-screen kitchen monitor, chrome-less (no sidebar/header). */}
          <Route path="/pos/kds" element={<KdsPage />} />
          {/* Driver web app (DRIVER-1) — phone shell, no sidebar. */}
          {driverRoutes}
          {/* Customer portal (PORTAL-1) — customers never see the staff shell. */}
          {portalRoutes}
          {procurementPrintRoutes}
          <Route element={<AppShell />}>
            <Route path="/" element={<DashboardPage />} />
            {/* POS terminal — full-screen cashier UI. Renders outside the app shell. */}
            <Route path="/pos/terminal" element={<TerminalWorkspace><TerminalPage /></TerminalWorkspace>} />
            <Route path="/pos/reports" element={<ReportsPage />} />
            {/* POS Dashboard — the sales/KPI dashboard, reachable from the Point of Sale menu. */}
            <Route path="/pos/dashboard" element={<DashboardPage />} />
            <Route path="/pos/settings" element={<RequirePermission permission={PERMISSIONS.setting.update}><PosSettingsPage /></RequirePermission>} />
            {/* POS customer display — second monitor / pole display, no shell. */}
            <Route path="/pos/display" element={<DisplayPage />} />
            {/* KDS station configuration (admin, inside the shell). */}
            <Route path="/pos/kitchen-stations" element={<KitchenStationsPage />} />
            {/* KDS kitchen performance reports + live dashboard. */}
            <Route path="/pos/kds-reports" element={<RequireReport reportId="pos.kds"><KdsReportsPage /></RequireReport>} />
            {/* POS Cash Register Management */}
            <Route path="/pos/cash-registers" element={<RequirePermission permission={PERMISSIONS.cashSession.read}><CashRegistersPage /></RequirePermission>} />
            <Route path="/tables" element={<TablesPage />} />
            <Route path="/tables/reservations" element={<ReservationsPage />} />
            <Route path="/tables/reports" element={<RequireReport reportId="pos.tables"><TableReportsPage /></RequireReport>} />
            {/* Zone (dining area) catalog — View Order drives POS table grouping. */}
            <Route path="/tables/zones" element={<ZonesPage />} />
            <Route path="/staff" element={<StaffPage />} />
            <Route path="/staff/roles" element={<RolesPage />} />
            <Route path="/staff/roles/:id/edit" element={<RoleEditPage />} />
            <Route path="/partners" element={<PartnersPage />} />
            <Route path="/customers" element={<CustomersPage />} />
            <Route path="/customers/:partnerId" element={<CustomerDetailPage />} />
            <Route path="/suppliers" element={<SuppliersPage />} />
            <Route path="/suppliers/:partnerId" element={<SupplierDetailPage />} />
            <Route path="/suppliers/:partnerId/ledger" element={<SupplierLedgerPage />} />
            <Route path="/products" element={<ProductsPage />} />
            <Route path="/products/new" element={<ProductEditPage />} />
            <Route path="/products/specifications" element={<ProductSpecsPage />} />
            <Route path="/products/:id/edit" element={<ProductEditPage />} />
            <Route path="/uom" element={<UomPage />} />
            <Route path="/menu" element={<MenuPage />} />
            <Route path="/menu/:menuItemId" element={<MenuDetailPage />} />
            <Route path="/menu/modifiers" element={<ModifiersPage />} />
            <Route path="/menu/combos" element={<ComboListPage />} />
            <Route path="/menu/accompaniments" element={<AccompanimentGroupsPage />} />
            <Route path="/invoices" element={<InvoicesPage />} />
                      <Route path="/invoices/new" element={<InvoiceCreatePage />} />
                      <Route path="/invoices/:id" element={<InvoiceDetailPage />} />
                      <Route path="/orders" element={<OrdersPage />} />
                      <Route path="/orders/new" element={<OrderCreatePage />} />
                      <Route path="/orders/:id" element={<OrderDetailPage />} />
                      <Route path="/communication" element={<CommunicationInboxPage />} />
                      <Route path="/communication/rules" element={<CommunicationRulesPage />} />
                      <Route path="/communication/channels" element={<CommunicationChannelsPage />} />
            <Route path="/price-lists" element={<RequirePermission permission={PERMISSIONS.priceList.read}><PriceListsPage /></RequirePermission>} />
            <Route path="/credit-control" element={<RequirePermission permission={PERMISSIONS.creditControl.read}><CreditControlPage /></RequirePermission>} />
            <Route path="/credit-notes" element={<CreditNotesPage />} />
            <Route path="/credit-notes/new" element={<CreditNoteCreatePage />} />
            <Route path="/credit-notes/:id" element={<CreditNoteDetailPage />} />
            <Route path="/payments" element={<PaymentsPage />} />
            <Route path="/accounts/bank-import" element={<RequirePermission permission={PERMISSIONS.bankAccount.update}><BankImportPage /></RequirePermission>} />
            <Route path="/labels" element={<RequirePermission permission={PERMISSIONS.label.print}><LabelPrintPage /></RequirePermission>} />
            <Route path="/payments/mobile-money" element={<RequirePermission permission={PERMISSIONS.payment.read}><MobileMoneyPage /></RequirePermission>} />
            <Route path="/payments/:id" element={<PaymentDetailPage />} />
            <Route path="/pos/receipts" element={<ReceiptsPage />} />
            <Route path="/pos/receipts/:invoiceId" element={<ReceiptDetailPage />} />
            {/* Sales returns (retail + café/restaurant), recorded through the POS refund engine. */}
            <Route path="/returns" element={<ReturnsPage />} />
            <Route path="/returns/new" element={<NewReturnPage />} />
            <Route path="/returns/:id" element={<ReturnDetailPage />} />
            {/* Sales charged to a customer's account, awaiting payment. */}
            <Route path="/pos/receivables" element={<ReceivablesPage />} />
            <Route path="/expenses" element={<ExpensesPage />} />
            <Route path="/expenses/categories" element={<ExpenseCategoriesPage />} />
            <Route path="/expenses/reports" element={<RequireReport reportId="expenses.reports"><ExpensesReportPage /></RequireReport>} />
            <Route path="/supplier-payments" element={<SupplierPaymentsPage />} />
            <Route path="/vendor-bills" element={<RequirePermission permission={PERMISSIONS.expense.read}><VendorBillsPage /></RequirePermission>} />
            <Route path="/vendor-bills/new" element={<RequirePermission permission={PERMISSIONS.expense.create}><VendorBillEditPage /></RequirePermission>} />
            <Route path="/vendor-bills/:id" element={<RequirePermission permission={PERMISSIONS.expense.read}><VendorBillDetailPage /></RequirePermission>} />
            <Route path="/vendor-bills/:id/edit" element={<RequirePermission permission={PERMISSIONS.expense.update}><VendorBillEditPage /></RequirePermission>} />
            <Route path="/payment-schedule" element={<RequirePermission permission={PERMISSIONS.expense.read}><PaymentSchedulePage /></RequirePermission>} />
            <Route path="/ap-aging" element={<RequirePermission permission={PERMISSIONS.report.ar}><ApAgingPage /></RequirePermission>} />
            <Route path="/ar-aging" element={<RequireReport reportId="sales.ar-aging"><ArAgingPage /></RequireReport>} />
            <Route path="/accounts" element={<ChartOfAccountsPage />} />
            <Route path="/accounts/new" element={<AccountDetailPage />} />
            {/* Static segments must stay ahead of /accounts/:id. */}
            <Route path="/accounts/categories" element={<AccountCategoriesPage />} />
            <Route path="/accounts/:id" element={<AccountDetailPage />} />
            {/* Money & Accounts — overview keeps the old /accounts/cash-accounts URL. */}
            <Route path="/accounts/cash-accounts" element={<RequirePermission permission={PERMISSIONS.account.read}><MoneyOverviewPage /></RequirePermission>} />
            <Route path="/accounts/cash-accounts/accounts" element={<RequirePermission permission={PERMISSIONS.account.read}><MoneyAccountsPage /></RequirePermission>} />
            <Route path="/accounts/cash-accounts/activity" element={<RequirePermission permission={PERMISSIONS.account.read}><MoneyActivityPage /></RequirePermission>} />
            <Route path="/accounts/cash-accounts/settlements" element={<RequirePermission permission={PERMISSIONS.cashSession.reconcile}><MoneySettlementsPage /></RequirePermission>} />
            <Route path="/bank-reconciliation" element={<RequirePermission permission={PERMISSIONS.bankAccount.read}><BankReconciliationPage /></RequirePermission>} />
            <Route path="/accounts/cash-accounts/transactions" element={<Navigate to="/accounts/cash-accounts/activity" replace />} />
            <Route path="/accounts/cash-accounts/:id" element={<RequirePermission permission={PERMISSIONS.account.read}><MoneyAccountDetailPage /></RequirePermission>} />
            <Route path="/cash-flow/report" element={<RequireReport reportId="money.reports"><RequirePermission permission={PERMISSIONS.account.read}><CashFlowReportPage /></RequirePermission></RequireReport>} />
            <Route path="/accounts/cash-registers" element={<Navigate to="/settings/registers" replace />} />
            <Route path="/settings/registers" element={<RequirePermission permission={PERMISSIONS.cashRegister.read}><RegistersSettingsPage /></RequirePermission>} />
            <Route path="/settings/payment-methods" element={<RequirePermission permission={PERMISSIONS.account.read}><PaymentMethodsSettingsPage /></RequirePermission>} />
            <Route path="/accounts/mappings" element={<AccountMappingsPage />} />
            <Route path="/accounts/posting-rules" element={<InventoryPostingRulesPage />} />
            <Route path="/journals" element={<JournalsPage />} />
            <Route path="/journals/new" element={<JournalEditPage />} />
            <Route path="/journals/:id" element={<JournalDetailPage />} />
            <Route path="/journals/:id/edit" element={<JournalEditPage />} />
            <Route path="/journal-entries" element={<JournalEntriesPage />} />
            <Route path="/journal-entries/new" element={<JournalEntryCreatePage />} />
            <Route path="/journal-entries/:id" element={<JournalEntryDetailPage />} />
            <Route path="/trial-balance" element={<RequireReport reportId="accounting.trial-balance"><TrialBalancePage /></RequireReport>} />
            <Route path="/budgets" element={<RequirePermission permission={PERMISSIONS.budget.read}><BudgetsPage /></RequirePermission>} />
            <Route path="/budgets/:id" element={<RequirePermission permission={PERMISSIONS.budget.read}><BudgetDetailPage /></RequirePermission>} />
            <Route path="/opening-balances" element={<RequirePermission permission={PERMISSIONS.openingBalance.read}><OpeningBalancesPage /></RequirePermission>} />
            <Route path="/tax-report" element={<RequirePermission permission={PERMISSIONS.report.accounting}><TaxReportPage /></RequirePermission>} />
            <Route path="/reports/sales-analysis" element={<RequirePermission permission={PERMISSIONS.report.ar}><SalesAnalysisPage /></RequirePermission>} />
            <Route path="/reports/branches" element={<RequirePermission permission={PERMISSIONS.report.accounting}><BranchPerformancePage /></RequirePermission>} />
            <Route path="/reports/bi-exports" element={<RequirePermission permission={PERMISSIONS.bi.export}><BiExportsPage /></RequirePermission>} />
            <Route path="/alerts" element={<RequirePermission permission={PERMISSIONS.alerts.read}><AlertsPage /></RequirePermission>} />
            <Route path="/consolidated-report" element={<RequirePermission permission={PERMISSIONS.report.accounting}><ConsolidatedReportPage /></RequirePermission>} />
            <Route path="/statements" element={<RequirePermission permission={PERMISSIONS.report.ar}><StatementsPage /></RequirePermission>} />
            <Route path="/general-ledger" element={<RequireReport reportId="accounting.general-ledger"><GeneralLedgerPage /></RequireReport>} />
            <Route path="/accounting-detailed-report" element={<RequireReport reportId="accounting.detailed"><DetailedAccountingReportPage /></RequireReport>} />
            <Route path="/profit-and-loss" element={<RequireReport reportId="accounting.profit-and-loss"><ProfitAndLossPage /></RequireReport>} />
            <Route path="/cash-flow" element={<RequireReport reportId="accounting.cash-flow-statement"><RequirePermission permission={PERMISSIONS.report.accounting}><CashFlowPage /></RequirePermission></RequireReport>} />
            <Route path="/accounts/ledger/:id" element={<AccountLedgerPage />} />
            <Route path="/tieout" element={<RequireReport reportId="accounting.tieout"><TieOutPage /></RequireReport>} />
            <Route path="/pos-gl-reconciliation" element={<RequireReport reportId="accounting.pos-gl-recon"><PosGlReconciliationPage /></RequireReport>} />
            <Route path="/audit-log" element={<AuditLogPage />} />
            <Route path="/fiscal-periods" element={<FiscalPeriodsPage />} />
            <Route path="/fiscal-years" element={<RequirePermission permission={PERMISSIONS.fiscalPeriod.read}><FiscalYearsPage /></RequirePermission>} />
            <Route path="/fiscal-years/:id" element={<RequirePermission permission={PERMISSIONS.fiscalPeriod.read}><FiscalYearClosePage /></RequirePermission>} />
            <Route path="/analytic-dimensions" element={<RequirePermission permission={PERMISSIONS.analyticDimension.read}><AnalyticDimensionsPage /></RequirePermission>} />
            <Route path="/fiscal-periods/:id/checklist" element={<RequirePermission permission={PERMISSIONS.fiscalPeriod.read}><PeriodCloseChecklistPage /></RequirePermission>} />
            <Route path="/taxes" element={<TaxesPage />} />
            <Route path="/accounts/payment-terms" element={<PaymentTermsPage />} />
            <Route path="/accounts/fiscal-positions" element={<FiscalPositionsPage />} />
            <Route path="/cost-centers" element={<CostCentersPage />} />
            <Route path="/cost-center-accounting" element={<CostCenterAccountingPage />} />
            <Route path="/currency" element={<CurrencyPage />} />
            <Route path="/inventory-valuation" element={<RequireReport reportId="accounting.inventory-valuation"><InventoryValuationPage /></RequireReport>} />
            <Route path="/inventory-gl-tieout" element={<RequireReport reportId="accounting.inventory-gl-tieout"><InventoryGlTieOutPage /></RequireReport>} />
            <Route path="/year-end-close" element={<YearEndClosePage />} />
            <Route path="/balance-sheet" element={<RequireReport reportId="accounting.balance-sheet"><BalanceSheetPage /></RequireReport>} />
            <Route path="/reports" element={<RequireReport reportId="accounting.report-center"><ReportCenterPage /></RequireReport>} />
            <Route path="/approvals" element={<ApprovalsPage />} />
            <Route path="/approval-workflows" element={<ApprovalWorkflowsPage />} />
            <Route path="/approval-controls" element={<ApprovalControlsPage />} />
            <Route path="/settings/document-numbering" element={<DocumentNumberingPage />} />
            <Route path="/approval-policies" element={<ApprovalPoliciesPage />} />
            <Route path="/recurring" element={<RecurringPage />} />
            <Route path="/webhooks" element={<WebhooksPage />} />
            <Route path="/files" element={<FilesPage />} />
            <Route path="/modules" element={<ModulesPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/settings/developer" element={<DevCompanySettingsPage />} />
            <Route path="/settings/company" element={<CompanySettingsPage />} />
            {/* Reachable by anyone who can read settings: when the licence has lapsed this is
                the one page that leads out of read-only, so it must not be hard to find. */}
            <Route path="/settings/license" element={<RequirePermission permission={PERMISSIONS.setting.read}><LicensePage /></RequirePermission>} />
            <Route path="/settings/receipt" element={<ReceiptSettingsPage />} />
            <Route path="/settings/backup" element={<BackupPage />} />
            <Route path="/settings/portal-accounts" element={<RequirePermission permission={PERMISSIONS.portal.manage}><PortalAccountsPage /></RequirePermission>} />
            <Route path="/settings/report-permissions" element={<RequirePermission permission={PERMISSIONS.role.read}><ReportPermissionsPage /></RequirePermission>} />
            <Route path="/settings/devices" element={<DevicesPage />} />
            <Route path="/settings/devices/rejected" element={<DeadLettersPage />} />
            <Route path="/inventory/posting-monitor" element={<PostingMonitorPage />} />
            <Route path="/fixed-assets" element={<AssetDashboardPage />} />
            <Route path="/fixed-assets/categories" element={<AssetCategoriesPage />} />
            <Route path="/fixed-assets/register" element={<AssetsPage />} />
            <Route path="/fixed-assets/:id" element={<AssetDetailPage />} />
            {/* Manufacturing — static segments before the :id route */}
            <Route path="/manufacturing" element={<ProductionDashboardPage />} />
            <Route path="/manufacturing/boms" element={<BomsPage />} />
            <Route path="/manufacturing/boms/:id" element={<BomDetailPage />} />
            <Route path="/manufacturing/mrp" element={<MrpPage />} />
            <Route path="/manufacturing/shop-floor" element={<ShopFloorPage />} />
            <Route path="/manufacturing/engineering" element={<EngineeringChangesPage />} />
            <Route path="/manufacturing/standard-costs" element={<StandardCostsPage />} />
            <Route path="/manufacturing/process-costing" element={<ProcessCostingPage />} />
            <Route path="/manufacturing/stage-wip" element={<StageWipReportPage />} />
            {/* Concrete block / precast (2.4) — /manufacturing/precast/* */}
            {precastRoutes}
            {/* Crushing & screening (ADR-S016) — screens in pages/crushing/routes.tsx */}
            {crushingRoutes}
            <Route path="/manufacturing/planning" element={<ManufacturingPlanningPage />} />
            <Route path="/manufacturing/reports" element={<RequireReport reportId="manufacturing.reports"><ManufacturingReportsPage /></RequireReport>} />
            <Route path="/manufacturing/resources" element={<ManufacturingResourcesPage />} />
            <Route path="/manufacturing/orders" element={<ProductionOrdersPage />} />
            <Route path="/manufacturing/orders/:id" element={<ProductionOrderDetailPage />} />
            {/* Shop-floor execution & data capture (ADR-S015, 2.7) */}
            {shopFloorRoutes}
            {planningBoardRoutes}
            {mfgCostingRoutes}
            <Route path="/inventory" element={<InventoryItemsPage />} />
            <Route path="/inventory/items" element={<InventoryItemsPage />} />
            <Route path="/inventory/items/:productId" element={<InventoryDetailPage />} />
            <Route path="/inventory/adjustments" element={<StockAdjustmentsPage />} />
            <Route path="/inventory/waste" element={<WastePage />} />
            <Route path="/inventory/transfers" element={<StockTransfersPage />} />
            <Route path="/inventory/reports" element={<RequireReport reportId="inventory.reports"><InventoryReportsPage /></RequireReport>} />
            <Route path="/inventory/count" element={<InventoryCountPage />} />
            <Route path="/beverage" element={<BeverageDashboardPage />} />
            <Route path="/beverage/count" element={<BottleCountPage />} />
            {/* Rental — static segments before /rental/agreements/:id */}
            <Route path="/rental" element={<RentalDashboardPage />} />
            <Route path="/rental/agreements" element={<RentalAgreementsPage />} />
            <Route path="/rental/agreements/new" element={<RentalAgreementCreatePage />} />
            <Route path="/rental/agreements/:id" element={<RentalAgreementDetailPage />} />
            <Route path="/rental/units" element={<RentalUnitsPage />} />
            <Route path="/rental/catalog" element={<RentalCatalogPage />} />
            <Route path="/rental/returns" element={<RentalReturnsPage />} />
            <Route path="/rental/reports" element={<RequireReport reportId="rental.reports"><RentalReportsPage /></RequireReport>} />
            {/* Fleet & logistics (ADR-S009) — sub-modules 1.1–1.9 register their screens in pages/fleet/routes.tsx */}
            {fleetRoutes}
            {/* Quality management (ADR-S013) — core-available, quality:* permissions */}
            {qualityRoutes}
            {/* Quarry & extraction (ADR-S012) — screens in pages/quarry/routes.tsx */}
            {quarryRoutes}
            {/* Management dashboards (ADR-S020) — screens in pages/analytics/routes.tsx */}
            {analyticsRoutes}
            {/* Repair & Maintenance — static segments before /repair/orders/:id */}
            <Route path="/repair" element={<RepairDashboardPage />} />
            <Route path="/repair/orders" element={<RepairOrdersPage />} />
            <Route path="/repair/orders/new" element={<RepairOrderCreatePage />} />
            <Route path="/repair/orders/:id" element={<RepairOrderDetailPage />} />
            <Route path="/repair/jobs" element={<RepairJobsPage />} />
            <Route path="/repair/technicians" element={<RepairTechniciansPage />} />
            <Route path="/repair/labour" element={<RepairLabourPage />} />
            <Route path="/repair/warranties" element={<RepairWarrantiesPage />} />
            <Route path="/repair/contracts" element={<RepairContractsPage />} />
            <Route path="/repair/reports" element={<RequireReport reportId="repair.reports"><RepairReportsPage /></RequireReport>} />

            {/* Workforce Management (HR) — static segments before /hr/employees/:id */}
            <Route path="/hr" element={<HrDashboardPage />} />
            {/* Self-service and team live under /hr but must precede the
                `employees/:id` param route so neither can be swallowed by it. */}
            <Route path="/hr/me" element={<HrSelfServicePage />} />
            <Route path="/hr/team" element={<HrTeamPage />} />
            <Route path="/hr/documents" element={<HrDocumentsPage />} />
            <Route path="/hr/training" element={<HrTrainingPage />} />
            <Route path="/hr/recruitment" element={<HrRecruitmentPage />} />
            <Route path="/hr/onboarding" element={<HrOnboardingPage />} />
            <Route path="/hr/contracts" element={<HrContractsPage />} />
            <Route path="/hr/reviews" element={<HrReviewsPage />} />
            <Route path="/hr/employees" element={<HrEmployeesPage />} />
            <Route path="/hr/employees/:id" element={<HrEmployeeDetailPage />} />
            <Route path="/hr/departments" element={<HrDepartmentsPage />} />
            <Route path="/hr/positions" element={<HrPositionsPage />} />
            <Route path="/hr/shifts" element={<HrShiftsPage />} />
            <Route path="/hr/attendance" element={<HrAttendancePage />} />
            <Route path="/hr/timesheets" element={<HrTimesheetsPage />} />
            <Route path="/hr/leave" element={<HrLeavePage />} />
            <Route path="/hr/holidays" element={<HrHolidaysPage />} />
            <Route path="/hr/payroll" element={<HrPayrollPage />} />
            <Route path="/hr/payroll/runs/:id" element={<HrPayrollPage />} />
            <Route path="/hr/payroll/settings" element={<HrPayrollSettingsPage />} />
            <Route path="/hr/payslips" element={<HrPayslipsPage />} />
            <Route path="/hr/advances-loans" element={<HrAdvancesLoansPage />} />
            <Route path="/hr/reports" element={<RequireReport reportId="hr.reports"><HrReportsPage /></RequireReport>} />
            <Route path="/inventory/ledger" element={<StockLedgerPage />} />
            <Route path="/inventory/ledger/:id" element={<StockMovementDetailPage />} />
            <Route path="/inventory/locations" element={<LocationsPage />} />
            <Route path="/inventory/reservations" element={<StockReservationsPage />} />
            <Route path="/inventory/replenishment" element={<ReplenishmentPage />} />
            <Route path="/inventory/warehouse" element={<RequirePermission permission={PERMISSIONS.warehouse.read}><WarehousePage /></RequirePermission>} />
            <Route path="/procurement/budgets" element={<ProcurementBudgetsPage />} />
            <Route path="/procurement/delegations" element={<ProcurementDelegationsPage />} />
            <Route path="/procurement/supplier-performance" element={<RequirePermission permission="purchase_order:read"><SupplierPerformancePage /></RequirePermission>} />
            <Route path="/inventory/cost-policies" element={<CostPoliciesPage />} />
            <Route path="/tasks" element={<TasksPage />} />
            <Route path="/tasks/new" element={<TaskEditPage />} />
            <Route path="/tasks/:id/edit" element={<TaskEditPage />} />
            {/* CRM sales suite (ADR-S010) — pages/crm/routes.tsx; /crm/deals links redirect */}
            {crmRoutes}
            {/* Sales / order-to-cash (ADR-S011) — pages/sales/routes.tsx */}
            {salesRoutes}
            {procurementRoutes}

                    </Route>
                  </Route>
                  <Route path="*" element={<Navigate to="/" replace />} />
                </Routes>
    </PageSuspense>
  );
}
