import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { DispatchBoardPage } from './DispatchBoardPage';
import { DeliveryOrdersPage } from './DeliveryOrdersPage';
import { DeliveryOrderFormPage } from './DeliveryOrderFormPage';
import { DeliveryOrderDetailPage } from './DeliveryOrderDetailPage';
import { TripsPage } from './TripsPage';
import { TripDetailPage } from './TripDetailPage';
import { MyTripsPage } from './MyTripsPage';
import { PodCapturePage } from './PodCapturePage';
import { CustomerSitesPage } from './CustomerSitesPage';
import { DiscrepanciesPage } from './DiscrepanciesPage';
import { DispatchSettingsPage } from './DispatchSettingsPage';

/** Fleet 1.3 — trips, delivery planning & dispatch screens (static segments before `:id`). */
export const dispatchRoutes = (
  <>
    <Route path="/fleet/dispatch" element={<RequirePermission permission={PERMISSIONS.fleetDispatch.read}><DispatchBoardPage /></RequirePermission>} />
    <Route path="/fleet/delivery-orders" element={<RequirePermission permission={PERMISSIONS.fleetDispatch.read}><DeliveryOrdersPage /></RequirePermission>} />
    <Route path="/fleet/delivery-orders/new" element={<RequirePermission permission={PERMISSIONS.fleetDispatch.create}><DeliveryOrderFormPage /></RequirePermission>} />
    <Route path="/fleet/delivery-orders/:id" element={<RequirePermission permission={PERMISSIONS.fleetDispatch.read}><DeliveryOrderDetailPage /></RequirePermission>} />
    <Route path="/fleet/trips" element={<RequirePermission permission={PERMISSIONS.fleetTrip.read}><TripsPage /></RequirePermission>} />
    <Route path="/fleet/trips/:id" element={<RequirePermission permission={PERMISSIONS.fleetTrip.read}><TripDetailPage /></RequirePermission>} />
    <Route path="/fleet/my-trips" element={<RequirePermission permission={PERMISSIONS.fleetTrip.read}><MyTripsPage /></RequirePermission>} />
    <Route path="/fleet/my-trips/:id" element={<RequirePermission permission={PERMISSIONS.fleetTrip.read}><PodCapturePage /></RequirePermission>} />
    <Route path="/fleet/customer-sites" element={<RequirePermission permission={PERMISSIONS.fleetDispatch.read}><CustomerSitesPage /></RequirePermission>} />
    <Route path="/fleet/delivery-discrepancies" element={<RequirePermission permission={PERMISSIONS.fleetDispatch.read}><DiscrepanciesPage /></RequirePermission>} />
    <Route path="/fleet/settings/dispatch" element={<RequirePermission permission={PERMISSIONS.fleetDispatch.read}><DispatchSettingsPage /></RequirePermission>} />
  </>
);
