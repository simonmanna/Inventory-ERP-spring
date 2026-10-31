import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { PortalShell } from './PortalShell';
import { PortalHomePage } from './PortalHomePage';
import { PortalOrderDetailPage, PortalOrdersPage } from './PortalOrdersPage';
import { PortalDeliveriesPage, PortalDeliveryDetailPage } from './PortalDeliveriesPage';
import { PortalInvoiceDetailPage, PortalInvoicesPage, PortalStatementPage } from './PortalInvoicesPage';

/** Customer portal (PORTAL-1, ADR-S050): its own shell; the staff sidebar is never shown to a customer. */
export const portalRoutes = (
  <Route element={<RequirePermission permission={PERMISSIONS.portal.customer}><PortalShell /></RequirePermission>}>
    <Route path="/portal" element={<PortalHomePage />} />
    <Route path="/portal/orders" element={<PortalOrdersPage />} />
    <Route path="/portal/orders/:id" element={<PortalOrderDetailPage />} />
    <Route path="/portal/deliveries" element={<PortalDeliveriesPage />} />
    <Route path="/portal/deliveries/:id" element={<PortalDeliveryDetailPage />} />
    <Route path="/portal/invoices" element={<PortalInvoicesPage />} />
    <Route path="/portal/invoices/:id" element={<PortalInvoiceDetailPage />} />
    <Route path="/portal/statement" element={<PortalStatementPage />} />
  </Route>
);
