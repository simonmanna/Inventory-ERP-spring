import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const DocumentsPage = lazy(() => import('./DocumentsPage').then((m) => ({ default: m.DocumentsPage })));
const RequestsPage = lazy(() => import('./RequestsPage').then((m) => ({ default: m.RequestsPage })));
const DmsSettingsPage = lazy(() => import('./DmsSettingsPage').then((m) => ({ default: m.DmsSettingsPage })));

const P = PERMISSIONS.dms;

/** Document management routes (ADR-S044). */
export const dmsRoutes = (
  <>
    <Route path="/dms" element={<RequirePermission permission={P.documentRead}><DocumentsPage /></RequirePermission>} />
    <Route path="/dms/requests" element={<RequirePermission permission={P.documentRead}><RequestsPage /></RequirePermission>} />
    <Route path="/dms/settings" element={
      <RequirePermission permission={[P.workspaceManage, P.tagManage, P.documentShare]}><DmsSettingsPage /></RequirePermission>} />
  </>
);
