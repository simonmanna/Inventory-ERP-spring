import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const PlmOverviewPage = lazy(() => import('./PlmOverviewPage').then((m) => ({ default: m.PlmOverviewPage })));
const PlmProductsPage = lazy(() => import('./PlmProductsPage').then((m) => ({ default: m.PlmProductsPage })));
const PlmProductDetailPage = lazy(() => import('./PlmProductDetailPage').then((m) => ({ default: m.PlmProductDetailPage })));
const PlmRequestsPage = lazy(() => import('./PlmRequestsPage').then((m) => ({ default: m.PlmRequestsPage })));
const PlmChangesPage = lazy(() => import('./PlmChangesPage').then((m) => ({ default: m.PlmChangesPage })));
const PlmChangeDetailPage = lazy(() => import('./PlmChangeDetailPage').then((m) => ({ default: m.PlmChangeDetailPage })));
const PlmDocumentsPage = lazy(() => import('./PlmDocumentsPage').then((m) => ({ default: m.PlmDocumentsPage })));
const PlmSettingsPage = lazy(() => import('./PlmSettingsPage').then((m) => ({ default: m.PlmSettingsPage })));

const read = PERMISSIONS.plm.read;

/** Product lifecycle management screens (ADR-S063). Static segments before :id. */
export const plmRoutes = (
  <>
    <Route path="/manufacturing/plm" element={<RequirePermission permission={read}><PlmOverviewPage /></RequirePermission>} />
    <Route path="/manufacturing/plm/products" element={<RequirePermission permission={read}><PlmProductsPage /></RequirePermission>} />
    <Route path="/manufacturing/plm/products/:id" element={<RequirePermission permission={read}><PlmProductDetailPage /></RequirePermission>} />
    <Route path="/manufacturing/plm/requests" element={<RequirePermission permission={read}><PlmRequestsPage /></RequirePermission>} />
    <Route path="/manufacturing/plm/changes" element={<RequirePermission permission={read}><PlmChangesPage /></RequirePermission>} />
    <Route path="/manufacturing/plm/changes/:id" element={<RequirePermission permission={read}><PlmChangeDetailPage /></RequirePermission>} />
    <Route path="/manufacturing/plm/documents" element={<RequirePermission permission={read}><PlmDocumentsPage /></RequirePermission>} />
    <Route path="/manufacturing/plm/settings" element={<RequirePermission permission={PERMISSIONS.plm.manage}><PlmSettingsPage /></RequirePermission>} />
  </>
);
