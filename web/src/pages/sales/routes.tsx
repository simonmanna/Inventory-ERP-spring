import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { SalesDashboardPage } from './SalesDashboardPage';
import { SalesOrdersPage } from './SalesOrdersPage';
import { SalesOrderPage } from './SalesOrderPage';

const P = PERMISSIONS.sales;

/** Sales / order-to-cash routes (ADR-S011); static segments before `:id`. */
export const salesRoutes = (
  <>
    <Route path="/sales" element={<RequirePermission permission={P.reportRead}><SalesDashboardPage /></RequirePermission>} />
    <Route path="/sales/orders" element={<RequirePermission permission={P.orderRead}><SalesOrdersPage /></RequirePermission>} />
    <Route path="/sales/orders/new" element={<RequirePermission permission={P.orderWrite}><SalesOrderPage /></RequirePermission>} />
    <Route path="/sales/orders/:id" element={<RequirePermission permission={P.orderRead}><SalesOrderPage /></RequirePermission>} />
  </>
);
