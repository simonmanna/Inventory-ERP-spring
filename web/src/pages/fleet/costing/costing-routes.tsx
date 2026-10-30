import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { FleetReportsPage } from './FleetReportsPage';
import { FreightBillsPage } from './FreightBillsPage';
import { FreightBillDetailPage } from './FreightBillDetailPage';
import { RemindersPage } from './RemindersPage';

/** Fleet 1.9 — reports, transporter freight bills, reminders. */
export const costingRoutes = (
  <>
    <Route path="/fleet/reports" element={<RequirePermission permission={PERMISSIONS.fleet.report}><FleetReportsPage /></RequirePermission>} />
    <Route path="/fleet/freight-bills" element={<RequirePermission permission={PERMISSIONS.fleetFreight.read}><FreightBillsPage /></RequirePermission>} />
    <Route path="/fleet/freight-bills/:id" element={<RequirePermission permission={PERMISSIONS.fleetFreight.read}><FreightBillDetailPage /></RequirePermission>} />
    <Route path="/fleet/reminders" element={<RequirePermission permission={PERMISSIONS.fleet.report}><RemindersPage /></RequirePermission>} />
  </>
);
