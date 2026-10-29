import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { WeighbridgeQueuePage } from './WeighbridgeQueuePage';
import { TicketEntryPage } from './TicketEntryPage';
import { TicketDetailPage } from './TicketDetailPage';
import { WeighbridgeExceptionsPage } from './WeighbridgeExceptionsPage';
import { StoredTaresPage } from './StoredTaresPage';
import { WeighbridgeSettingsPage } from './WeighbridgeSettingsPage';
import { WeighbridgeReportPage } from './WeighbridgeReportPage';

/** Fleet 1.4 — weighbridge screens (static segments before `:id`). */
export const weighbridgeRoutes = (
  <>
    <Route path="/fleet/weighbridge" element={<RequirePermission permission={PERMISSIONS.weighbridge.read}><WeighbridgeQueuePage /></RequirePermission>} />
    <Route path="/fleet/weighbridge/tickets/new" element={<RequirePermission permission={PERMISSIONS.weighbridge.weigh}><TicketEntryPage /></RequirePermission>} />
    <Route path="/fleet/weighbridge/tickets/:id" element={<RequirePermission permission={PERMISSIONS.weighbridge.read}><TicketDetailPage /></RequirePermission>} />
    <Route path="/fleet/weighbridge/exceptions" element={<RequirePermission permission={PERMISSIONS.weighbridge.read}><WeighbridgeExceptionsPage /></RequirePermission>} />
    <Route path="/fleet/weighbridge/tares" element={<RequirePermission permission={PERMISSIONS.weighbridge.read}><StoredTaresPage /></RequirePermission>} />
    <Route path="/fleet/weighbridge/reports" element={<RequirePermission permission={PERMISSIONS.weighbridge.read}><WeighbridgeReportPage /></RequirePermission>} />
    <Route path="/fleet/weighbridge/settings" element={<RequirePermission permission={PERMISSIONS.weighbridge.configure}><WeighbridgeSettingsPage /></RequirePermission>} />
  </>
);
