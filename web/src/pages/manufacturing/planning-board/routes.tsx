import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { PlanningWorkbenchPage } from './PlanningWorkbenchPage';
import { GanttPage } from './GanttPage';
import { CapacityPage } from './CapacityPage';
import { ShiftPlansPage } from './ShiftPlansPage';
import { PlanPerformancePage } from './PlanPerformancePage';

/** Advanced planning & scheduling screens (ADR-S017, manufacturing 2.5). */
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
