import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const PlanningWorkbenchPage = lazy(() => import('./PlanningWorkbenchPage').then((m) => ({ default: m.PlanningWorkbenchPage })));
const GanttPage = lazy(() => import('./GanttPage').then((m) => ({ default: m.GanttPage })));
const CapacityPage = lazy(() => import('./CapacityPage').then((m) => ({ default: m.CapacityPage })));
const ShiftPlansPage = lazy(() => import('./ShiftPlansPage').then((m) => ({ default: m.ShiftPlansPage })));
const PlanPerformancePage = lazy(() => import('./PlanPerformancePage').then((m) => ({ default: m.PlanPerformancePage })));

/** Advanced planning & scheduling screens (ADR-S019, manufacturing 2.5). */
const read = PERMISSIONS.productionPlan.read;
export const planningBoardRoutes = (
  <>
    <Route path="/manufacturing/planning-board" element={<RequirePermission permission={read}><PlanningWorkbenchPage /></RequirePermission>} />
    <Route path="/manufacturing/gantt" element={<RequirePermission permission={PERMISSIONS.workOrder.read}><GanttPage /></RequirePermission>} />
    <Route path="/manufacturing/capacity" element={<RequirePermission permission={read}><CapacityPage /></RequirePermission>} />
    <Route path="/manufacturing/shift-plans" element={<RequirePermission permission={read}><ShiftPlansPage /></RequirePermission>} />
    <Route path="/manufacturing/plan-performance" element={<RequirePermission permission={PERMISSIONS.production.report}><PlanPerformancePage /></RequirePermission>} />
  </>
);
