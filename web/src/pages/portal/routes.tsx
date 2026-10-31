import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const PortalShell = lazy(() => import('./PortalShell').then((m) => ({ default: m.PortalShell })));
const PortalHomePage = lazy(() => import('./PortalHomePage').then((m) => ({ default: m.PortalHomePage })));
const PortalOrderDetailPage = lazy(() => import('./PortalOrdersPage').then((m) => ({ default: m.PortalOrderDetailPage })));
const PortalOrdersPage = lazy(() => import('./PortalOrdersPage').then((m) => ({ default: m.PortalOrdersPage })));
const PortalDeliveriesPage = lazy(() => import('./PortalDeliveriesPage').then((m) => ({ default: m.PortalDeliveriesPage })));
const PortalDeliveryDetailPage = lazy(() => import('./PortalDeliveriesPage').then((m) => ({ default: m.PortalDeliveryDetailPage })));
const PortalInvoiceDetailPage = lazy(() => import('./PortalInvoicesPage').then((m) => ({ default: m.PortalInvoiceDetailPage })));
const PortalInvoicesPage = lazy(() => import('./PortalInvoicesPage').then((m) => ({ default: m.PortalInvoicesPage })));
const PortalStatementPage = lazy(() => import('./PortalInvoicesPage').then((m) => ({ default: m.PortalStatementPage })));

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
