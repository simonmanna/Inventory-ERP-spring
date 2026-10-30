import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { TerminalPage } from './TerminalPage';
import { FloorBoardPage } from './FloorBoardPage';
import { ShiftReportsPage } from './ShiftReportsPage';
import { ShiftReportDetailPage } from './ShiftReportDetailPage';
import { OeePage } from './OeePage';
import { FloorSetupPage } from './FloorSetupPage';

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
