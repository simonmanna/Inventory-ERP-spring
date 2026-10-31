import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const MyTripsPage = lazy(() => import('@/pages/fleet/dispatch/MyTripsPage').then((m) => ({ default: m.MyTripsPage })));
const PodCapturePage = lazy(() => import('@/pages/fleet/dispatch/PodCapturePage').then((m) => ({ default: m.PodCapturePage })));
const DriverShell = lazy(() => import('./DriverShell').then((m) => ({ default: m.DriverShell })));
const DriverHomePage = lazy(() => import('./DriverHomePage').then((m) => ({ default: m.DriverHomePage })));
const DriverFuelPage = lazy(() => import('./DriverFuelPage').then((m) => ({ default: m.DriverFuelPage })));
const DriverPreTripPage = lazy(() => import('./DriverPreTripPage').then((m) => ({ default: m.DriverPreTripPage })));

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
