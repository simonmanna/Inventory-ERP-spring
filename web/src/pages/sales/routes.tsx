import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { SalesDashboardPage } from './SalesDashboardPage';
import { SalesOrdersPage } from './SalesOrdersPage';
import { SalesOrderPage } from './SalesOrderPage';
import { PricelistsPage } from './PricelistsPage';
import { PricelistPage } from './PricelistPage';
import { MarginFloorsPage } from './MarginFloorsPage';
import { CreditPage } from './CreditPage';
import { BackordersPage } from './BackordersPage';
import { ComplaintsPage, ReturnDetailPage, ReturnsPage } from './ReturnsPages';
import { CollectionsPage } from './CollectionsPage';
import { AgreementsPage, CommissionsPage } from './AgreementsPages';
import { AnalyticsPage } from './AnalyticsPage';
import { PortalAccessPage } from './PortalAccessPage';

const P = PERMISSIONS.sales;

/** Sales / order-to-cash routes (ADR-S011); static segments before `:id`. */
export const salesRoutes = (
  <>
    <Route path="/sales" element={<RequirePermission permission={P.reportRead}><SalesDashboardPage /></RequirePermission>} />
    <Route path="/sales/orders" element={<RequirePermission permission={P.orderRead}><SalesOrdersPage /></RequirePermission>} />
    <Route path="/sales/orders/new" element={<RequirePermission permission={P.orderWrite}><SalesOrderPage /></RequirePermission>} />
    <Route path="/sales/orders/:id" element={<RequirePermission permission={P.orderRead}><SalesOrderPage /></RequirePermission>} />
    <Route path="/sales/pricelists" element={<RequirePermission permission={P.pricingRead}><PricelistsPage /></RequirePermission>} />
    <Route path="/sales/pricelists/:id" element={<RequirePermission permission={P.pricingRead}><PricelistPage /></RequirePermission>} />
    <Route path="/sales/collections" element={<RequirePermission permission={P.collectionsRead}><CollectionsPage /></RequirePermission>} />
    <Route path="/sales/analytics" element={<RequirePermission permission={P.reportRead}><AnalyticsPage /></RequirePermission>} />
    <Route path="/sales/portal-access" element={<RequirePermission permission={P.portalManage}><PortalAccessPage /></RequirePermission>} />
    <Route path="/sales/agreements" element={<RequirePermission permission={P.agreementRead}><AgreementsPage /></RequirePermission>} />
    <Route path="/sales/commissions" element={<RequirePermission permission={P.commissionRead}><CommissionsPage /></RequirePermission>} />
    <Route path="/sales/returns" element={<RequirePermission permission={P.returnRead}><ReturnsPage /></RequirePermission>} />
    <Route path="/sales/returns/:id" element={<RequirePermission permission={P.returnRead}><ReturnDetailPage /></RequirePermission>} />
    <Route path="/sales/complaints" element={<RequirePermission permission={P.complaintRead}><ComplaintsPage /></RequirePermission>} />
    <Route path="/sales/backorders" element={<RequirePermission permission={P.reportRead}><BackordersPage /></RequirePermission>} />
    <Route path="/sales/credit" element={<RequirePermission permission={PERMISSIONS.credit.read}><CreditPage /></RequirePermission>} />
    <Route path="/sales/margin-floors" element={<RequirePermission permission={P.pricingRead}><MarginFloorsPage /></RequirePermission>} />
  </>
);
