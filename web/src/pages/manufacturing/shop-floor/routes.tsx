import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const TerminalPage = lazy(() => import('./TerminalPage').then((m) => ({ default: m.TerminalPage })));
const FloorBoardPage = lazy(() => import('./FloorBoardPage').then((m) => ({ default: m.FloorBoardPage })));
const ShiftReportsPage = lazy(() => import('./ShiftReportsPage').then((m) => ({ default: m.ShiftReportsPage })));
const ShiftReportDetailPage = lazy(() => import('./ShiftReportDetailPage').then((m) => ({ default: m.ShiftReportDetailPage })));
const OeePage = lazy(() => import('./OeePage').then((m) => ({ default: m.OeePage })));
const FloorSetupPage = lazy(() => import('./FloorSetupPage').then((m) => ({ default: m.FloorSetupPage })));

/** Shop-floor execution & data capture screens (ADR-S015, manufacturing 2.7). Static segments before :id. */
const read = PERMISSIONS.workOrder.read;
export const shopFloorRoutes = (
  <>
    <Route path="/manufacturing/terminal" element={<RequirePermission permission={read}><TerminalPage /></RequirePermission>} />
    <Route path="/manufacturing/floor-board" element={<RequirePermission permission={read}><FloorBoardPage /></RequirePermission>} />
    <Route path="/manufacturing/shift-reports" element={<RequirePermission permission={read}><ShiftReportsPage /></RequirePermission>} />
    <Route path="/manufacturing/shift-reports/:id" element={<RequirePermission permission={read}><ShiftReportDetailPage /></RequirePermission>} />
    <Route path="/manufacturing/oee" element={<RequirePermission permission={PERMISSIONS.production.report}><OeePage /></RequirePermission>} />
    <Route path="/manufacturing/floor-setup" element={<RequirePermission permission={[PERMISSIONS.workCenter.read, PERMISSIONS.workCenter.update]}><FloorSetupPage /></RequirePermission>} />
  </>
);
