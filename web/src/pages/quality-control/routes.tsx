import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const QcOverviewPage = lazy(() => import('./QcOverviewPage').then((m) => ({ default: m.QcOverviewPage })));
const ChecksPage = lazy(() => import('./ChecksPage').then((m) => ({ default: m.ChecksPage })));
const CheckDetailPage = lazy(() => import('./CheckDetailPage').then((m) => ({ default: m.CheckDetailPage })));
const ControlPointsPage = lazy(() => import('./ControlPointsPage').then((m) => ({ default: m.ControlPointsPage })));
const ControlPointDetailPage = lazy(() => import('./ControlPointDetailPage').then((m) => ({ default: m.ControlPointDetailPage })));
const QcTeamsPage = lazy(() => import('./QcTeamsPage').then((m) => ({ default: m.QcTeamsPage })));
const WorksheetTemplatesPage = lazy(() => import('./WorksheetTemplatesPage').then((m) => ({ default: m.WorksheetTemplatesPage })));
const QuarantineExceptionsPage = lazy(() => import('./QuarantineExceptionsPage').then((m) => ({ default: m.QuarantineExceptionsPage })));
const FailureClassesPage = lazy(() => import('./FailureClassesPage').then((m) => ({ default: m.FailureClassesPage })));

/** Odoo-style quality control (ADR-S087); core-available, gated by quality_control:* permissions. */
const read = PERMISSIONS.qualityControl.read;
const guard = (el: JSX.Element) => <RequirePermission permission={read}>{el}</RequirePermission>;
export const qualityControlRoutes = (
  <>
    <Route path="/quality-control" element={guard(<QcOverviewPage />)} />
    <Route path="/quality-control/checks" element={guard(<ChecksPage />)} />
    <Route path="/quality-control/checks/:id" element={guard(<CheckDetailPage />)} />
    <Route path="/quality-control/quarantine-exceptions" element={guard(<QuarantineExceptionsPage />)} />
    <Route path="/quality-control/control-points" element={guard(<ControlPointsPage />)} />
    <Route path="/quality-control/control-points/:id" element={guard(<ControlPointDetailPage />)} />
    <Route path="/quality-control/teams" element={guard(<QcTeamsPage />)} />
    <Route path="/quality-control/worksheet-templates" element={guard(<WorksheetTemplatesPage />)} />
    <Route path="/quality-control/failure-classes" element={guard(<FailureClassesPage />)} />
  </>
);
