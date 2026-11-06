import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const SalesDashboardPage = lazy(() => import('./SalesDashboardPage').then((m) => ({ default: m.SalesDashboardPage })));
const SalesOrdersPage = lazy(() => import('./SalesOrdersPage').then((m) => ({ default: m.SalesOrdersPage })));
const SalesOrderPage = lazy(() => import('./SalesOrderPage').then((m) => ({ default: m.SalesOrderPage })));
const QuotationsPage = lazy(() => import('@/pages/crm/QuotationsPage').then((m) => ({ default: m.QuotationsPage })));
const QuotationEditorPage = lazy(() => import('@/pages/crm/QuotationEditorPage').then((m) => ({ default: m.QuotationEditorPage })));
const ProformasPage = lazy(() => import('./ProformasPage').then((m) => ({ default: m.ProformasPage })));
const ProformaPage = lazy(() => import('./ProformasPage').then((m) => ({ default: m.ProformaPage })));
const SalesSettingsPage = lazy(() => import('./SalesSettingsPage').then((m) => ({ default: m.SalesSettingsPage })));
const PricelistsPage = lazy(() => import('./PricelistsPage').then((m) => ({ default: m.PricelistsPage })));
const PricelistPage = lazy(() => import('./PricelistPage').then((m) => ({ default: m.PricelistPage })));
const MarginFloorsPage = lazy(() => import('./MarginFloorsPage').then((m) => ({ default: m.MarginFloorsPage })));
const CreditPage = lazy(() => import('./CreditPage').then((m) => ({ default: m.CreditPage })));
const BackordersPage = lazy(() => import('./BackordersPage').then((m) => ({ default: m.BackordersPage })));
const ComplaintsPage = lazy(() => import('./ReturnsPages').then((m) => ({ default: m.ComplaintsPage })));
const ReturnDetailPage = lazy(() => import('./ReturnsPages').then((m) => ({ default: m.ReturnDetailPage })));
const ReturnsPage = lazy(() => import('./ReturnsPages').then((m) => ({ default: m.ReturnsPage })));
const CollectionsPage = lazy(() => import('./CollectionsPage').then((m) => ({ default: m.CollectionsPage })));
const AgreementsPage = lazy(() => import('./AgreementsPages').then((m) => ({ default: m.AgreementsPage })));
const CommissionsPage = lazy(() => import('./AgreementsPages').then((m) => ({ default: m.CommissionsPage })));
const AnalyticsPage = lazy(() => import('./AnalyticsPage').then((m) => ({ default: m.AnalyticsPage })));
const PresentationPage = lazy(() => import('./PresentationPage').then((m) => ({ default: m.PresentationPage })));
const PortalAccessPage = lazy(() => import('./PortalAccessPage').then((m) => ({ default: m.PortalAccessPage })));
const CashSalesPage = lazy(() => import('./cash-sales/CashSalesPage').then((m) => ({ default: m.CashSalesPage })));
const CashSalePage = lazy(() => import('./cash-sales/CashSalePage').then((m) => ({ default: m.CashSalePage })));
const SalesReportsHubPage = lazy(() => import('./reports/SalesReportsHubPage').then((m) => ({ default: m.SalesReportsHubPage })));
const SalesReportPage = lazy(() => import('./reports/SalesReportPage').then((m) => ({ default: m.SalesReportPage })));
const BranchProfilesPage = lazy(() => import('./cash-sales/BranchProfilesPage').then((m) => ({ default: m.BranchProfilesPage })));

const P = PERMISSIONS.sales;

/** Sales / order-to-cash routes (ADR-S011); static segments before `:id`. */
export const salesRoutes = (
  <>
    <Route path="/sales" element={<RequirePermission permission={P.reportRead}><SalesDashboardPage /></RequirePermission>} />
    <Route path="/sales/orders" element={<RequirePermission permission={P.orderRead}><SalesOrdersPage /></RequirePermission>} />
    <Route path="/sales/orders/new" element={<RequirePermission permission={P.orderWrite}><SalesOrderPage /></RequirePermission>} />
    <Route path="/sales/orders/:id" element={<RequirePermission permission={P.orderRead}><SalesOrderPage /></RequirePermission>} />
    <Route path="/sales/quotations" element={<RequirePermission permission={P.quotationRead}><QuotationsPage /></RequirePermission>} />
    <Route path="/sales/quotations/new" element={<RequirePermission permission={P.quotationWrite}><QuotationEditorPage /></RequirePermission>} />
    <Route path="/sales/quotations/:id" element={<RequirePermission permission={P.quotationRead}><QuotationEditorPage /></RequirePermission>} />
    <Route path="/sales/proformas" element={<RequirePermission permission={P.orderRead}><ProformasPage /></RequirePermission>} />
    <Route path="/sales/proformas/:id" element={<RequirePermission permission={P.orderRead}><ProformaPage /></RequirePermission>} />
    <Route path="/sales/settings" element={<RequirePermission permission={P.configure}><SalesSettingsPage /></RequirePermission>} />
    <Route path="/sales/pricelists" element={<RequirePermission permission={P.pricingRead}><PricelistsPage /></RequirePermission>} />
    <Route path="/sales/pricelists/:id" element={<RequirePermission permission={P.pricingRead}><PricelistPage /></RequirePermission>} />
    <Route path="/sales/collections" element={<RequirePermission permission={P.collectionsRead}><CollectionsPage /></RequirePermission>} />
    <Route path="/sales/reports" element={<RequirePermission permission={P.reportRead}><SalesReportsHubPage /></RequirePermission>} />
    <Route path="/sales/reports/:page" element={<RequirePermission permission={P.reportRead}><SalesReportPage /></RequirePermission>} />
    <Route path="/sales/analytics" element={<RequirePermission permission={P.reportRead}><AnalyticsPage /></RequirePermission>} />
    <Route path="/sales/presentation" element={<RequirePermission permission={P.reportRead}><PresentationPage /></RequirePermission>} />
    <Route path="/sales/portal-access" element={<RequirePermission permission={P.portalManage}><PortalAccessPage /></RequirePermission>} />
    <Route path="/sales/agreements" element={<RequirePermission permission={P.agreementRead}><AgreementsPage /></RequirePermission>} />
    <Route path="/sales/commissions" element={<RequirePermission permission={P.commissionRead}><CommissionsPage /></RequirePermission>} />
    <Route path="/sales/returns" element={<RequirePermission permission={P.returnRead}><ReturnsPage /></RequirePermission>} />
    <Route path="/sales/returns/:id" element={<RequirePermission permission={P.returnRead}><ReturnDetailPage /></RequirePermission>} />
    <Route path="/sales/complaints" element={<RequirePermission permission={P.complaintRead}><ComplaintsPage /></RequirePermission>} />
    <Route path="/sales/backorders" element={<RequirePermission permission={P.reportRead}><BackordersPage /></RequirePermission>} />
    <Route path="/sales/credit" element={<RequirePermission permission={PERMISSIONS.credit.read}><CreditPage /></RequirePermission>} />
    <Route path="/sales/cash-sales" element={<RequirePermission permission={P.cashSaleRead}><CashSalesPage scope="branch" /></RequirePermission>} />
    <Route path="/sales/cash-sales/processing" element={<RequirePermission permission={P.cashSaleReadAll}><CashSalesPage scope="head_office" /></RequirePermission>} />
    <Route path="/sales/cash-sales/new" element={<RequirePermission permission={P.cashSaleCreate}><CashSalePage /></RequirePermission>} />
    <Route path="/sales/cash-sales/:id" element={<RequirePermission permission={P.cashSaleRead}><CashSalePage /></RequirePermission>} />
    <Route path="/sales/branch-profiles" element={<RequirePermission permission={P.cashSaleRead}><BranchProfilesPage /></RequirePermission>} />
    <Route path="/sales/margin-floors" element={<RequirePermission permission={P.pricingRead}><MarginFloorsPage /></RequirePermission>} />
  </>
);
