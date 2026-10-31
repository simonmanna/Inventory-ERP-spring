import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { MyTripsPage } from '@/pages/fleet/dispatch/MyTripsPage';
import { PodCapturePage } from '@/pages/fleet/dispatch/PodCapturePage';
import { DriverShell } from './DriverShell';
import { DriverHomePage } from './DriverHomePage';
import { DriverFuelPage } from './DriverFuelPage';
import { DriverPreTripPage } from './DriverPreTripPage';

/** Driver web app (DRIVER-1): phone shell outside the desktop sidebar. Web only; the Android POS app is unrelated. */
export const driverRoutes = (
  <Route element={<RequirePermission permission={PERMISSIONS.fleetTrip.deliver}><DriverShell /></RequirePermission>}>
    <Route path="/driver" element={<DriverHomePage />} />
    <Route path="/driver/trips" element={<MyTripsPage basePath="/driver/trips" />} />
    <Route path="/driver/trips/:id" element={<PodCapturePage basePath="/driver/trips" />} />
    <Route path="/driver/pre-trip" element={<DriverPreTripPage />} />
    <Route path="/driver/fuel" element={<DriverFuelPage />} />
  </Route>
);
