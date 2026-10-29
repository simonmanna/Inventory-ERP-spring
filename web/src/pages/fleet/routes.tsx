import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { FleetDashboardPage } from './FleetDashboardPage';
import { VehiclesPage } from './VehiclesPage';
import { VehicleFormPage } from './VehicleFormPage';
import { VehicleDetailPage } from './VehicleDetailPage';
import { FleetCompliancePage } from './FleetCompliancePage';
import { FleetCategoriesPage } from './FleetCategoriesPage';
import { TyresPage } from './TyresPage';
import { TyreDetailPage } from './TyreDetailPage';
import { IncidentsPage } from './IncidentsPage';
import { IncidentFormPage } from './IncidentFormPage';
import { IncidentDetailPage } from './IncidentDetailPage';
import { FinesPage } from './FinesPage';
import { FineDetailPage } from './FineDetailPage';
import { TelematicsPage } from './TelematicsPage';
import { TelematicsLivePage } from './TelematicsLivePage';

/**
 * Route skeleton of the Fleet section (ADR-S009). Each sub-module adds its screens here, static segments before the
 * `:id` routes: 1.2 drivers (/fleet/drivers), 1.3 dispatch (/fleet/dispatch, /fleet/delivery-orders, /fleet/trips),
 * 1.4 weighbridge (/fleet/weighbridge), 1.5 fuel (/fleet/fuel), 1.6 maintenance (/fleet/maintenance),
 * 1.7 tyres / incidents (/fleet/tyres, /fleet/incidents), 1.9 reports (/fleet/reports).
 */
export const fleetRoutes = (
  <>
    <Route path="/fleet" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.read}><FleetDashboardPage /></RequirePermission>} />
    <Route path="/fleet/vehicles" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.read}><VehiclesPage /></RequirePermission>} />
    <Route path="/fleet/vehicles/new" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.create}><VehicleFormPage /></RequirePermission>} />
    <Route path="/fleet/vehicles/:id" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.read}><VehicleDetailPage /></RequirePermission>} />
    <Route path="/fleet/vehicles/:id/edit" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.update}><VehicleFormPage /></RequirePermission>} />
    <Route path="/fleet/compliance" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.read}><FleetCompliancePage /></RequirePermission>} />
    <Route path="/fleet/settings/classes" element={<RequirePermission permission={[PERMISSIONS.fleet.settings, PERMISSIONS.fleetVehicle.read]}><FleetCategoriesPage /></RequirePermission>} />
    {/* 1.7 tyres, incidents & claims, traffic fines */}
    <Route path="/fleet/tyres" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><TyresPage /></RequirePermission>} />
    <Route path="/fleet/tyres/:id" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><TyreDetailPage /></RequirePermission>} />
    <Route path="/fleet/incidents" element={<RequirePermission permission={PERMISSIONS.fleetIncident.read}><IncidentsPage /></RequirePermission>} />
    <Route path="/fleet/incidents/new" element={<RequirePermission permission={PERMISSIONS.fleetIncident.create}><IncidentFormPage /></RequirePermission>} />
    <Route path="/fleet/incidents/:id" element={<RequirePermission permission={PERMISSIONS.fleetIncident.read}><IncidentDetailPage /></RequirePermission>} />
    <Route path="/fleet/incidents/:id/edit" element={<RequirePermission permission={PERMISSIONS.fleetIncident.update}><IncidentFormPage /></RequirePermission>} />
    <Route path="/fleet/fines" element={<RequirePermission permission={PERMISSIONS.fleetIncident.read}><FinesPage /></RequirePermission>} />
    <Route path="/fleet/fines/:id" element={<RequirePermission permission={PERMISSIONS.fleetIncident.read}><FineDetailPage /></RequirePermission>} />
    {/* 1.8 telematics */}
    <Route path="/fleet/telematics" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.read}><TelematicsPage /></RequirePermission>} />
    <Route path="/fleet/live" element={<RequirePermission permission={PERMISSIONS.fleetTrip.read}><TelematicsLivePage /></RequirePermission>} />
  </>
);
