import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { FleetDashboardPage } from './FleetDashboardPage';
import { VehiclesPage } from './VehiclesPage';
import { VehicleFormPage } from './VehicleFormPage';
import { VehicleDetailPage } from './VehicleDetailPage';
import { FleetCompliancePage } from './FleetCompliancePage';
import { FleetCategoriesPage } from './FleetCategoriesPage';
import { DriversPage } from './DriversPage';
import { DriverFormPage } from './DriverFormPage';
import { DriverDetailPage } from './DriverDetailPage';
import { CheckoutPage } from './CheckoutPage';
import { AllowanceRulesPage } from './AllowanceRulesPage';
import { ClaimsPage } from './ClaimsPage';
import { ClaimFormPage } from './ClaimFormPage';
import { ClaimDetailPage } from './ClaimDetailPage';
import { MaintenanceDuePage } from './maintenance/MaintenanceDuePage';
import { WorkOrdersPage } from './maintenance/WorkOrdersPage';
import { WorkOrderDetailPage } from './maintenance/WorkOrderDetailPage';
import { ServiceTemplatesPage } from './maintenance/ServiceTemplatesPage';
import { DowntimePage } from './maintenance/DowntimePage';
import { ReliabilityPage } from './maintenance/ReliabilityPage';
import { ChecklistTemplatesPage } from './maintenance/ChecklistTemplatesPage';
import { InspectionsPage, InspectionDetailPage } from './maintenance/InspectionsPage';
import { InspectionEntryPage } from './maintenance/InspectionEntryPage';
import { DefectsPage } from './maintenance/DefectsPage';
import { fuelRoutes } from './fuel/fuel-routes';
import { dispatchRoutes } from './dispatch/dispatch-routes';
import { weighbridgeRoutes } from './weighbridge/weighbridge-routes';
import { costingRoutes } from './costing/costing-routes';
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
    {/* 1.2 drivers & operators */}
    <Route path="/fleet/drivers" element={<RequirePermission permission={PERMISSIONS.fleetDriver.read}><DriversPage /></RequirePermission>} />
    <Route path="/fleet/drivers/new" element={<RequirePermission permission={PERMISSIONS.fleetDriver.create}><DriverFormPage /></RequirePermission>} />
    <Route path="/fleet/drivers/:id" element={<RequirePermission permission={PERMISSIONS.fleetDriver.read}><DriverDetailPage /></RequirePermission>} />
    <Route path="/fleet/drivers/:id/edit" element={<RequirePermission permission={PERMISSIONS.fleetDriver.update}><DriverFormPage /></RequirePermission>} />
    <Route path="/fleet/checkout" element={<RequirePermission permission={[PERMISSIONS.fleetDriver.checkout, PERMISSIONS.fleetVehicle.read]}><CheckoutPage /></RequirePermission>} />
    <Route path="/fleet/claims" element={<RequirePermission permission={PERMISSIONS.fleetDriver.read}><ClaimsPage /></RequirePermission>} />
    <Route path="/fleet/claims/new" element={<RequirePermission permission={PERMISSIONS.fleetDriver.allowance}><ClaimFormPage /></RequirePermission>} />
    <Route path="/fleet/claims/:id" element={<RequirePermission permission={PERMISSIONS.fleetDriver.read}><ClaimDetailPage /></RequirePermission>} />
    <Route path="/fleet/settings/allowances" element={<RequirePermission permission={[PERMISSIONS.fleet.settings, PERMISSIONS.fleetDriver.read]}><AllowanceRulesPage /></RequirePermission>} />
    {/* 1.6 maintenance */}
    <Route path="/fleet/maintenance" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><MaintenanceDuePage /></RequirePermission>} />
    <Route path="/fleet/maintenance/work-orders" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><WorkOrdersPage /></RequirePermission>} />
    <Route path="/fleet/maintenance/work-orders/:id" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><WorkOrderDetailPage /></RequirePermission>} />
    <Route path="/fleet/maintenance/templates" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><ServiceTemplatesPage /></RequirePermission>} />
    <Route path="/fleet/maintenance/downtime" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><DowntimePage /></RequirePermission>} />
    <Route path="/fleet/maintenance/reliability" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><ReliabilityPage /></RequirePermission>} />
    <Route path="/fleet/maintenance/checklists" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><ChecklistTemplatesPage /></RequirePermission>} />
    <Route path="/fleet/inspections" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><InspectionsPage /></RequirePermission>} />
    <Route path="/fleet/inspections/new" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.inspect}><InspectionEntryPage /></RequirePermission>} />
    <Route path="/fleet/inspections/:id" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><InspectionDetailPage /></RequirePermission>} />
    <Route path="/fleet/defects" element={<RequirePermission permission={PERMISSIONS.fleetMaintenance.read}><DefectsPage /></RequirePermission>} />
    {fuelRoutes}
    {/* 1.3 trips, delivery planning & dispatch */}
    {dispatchRoutes}
    {weighbridgeRoutes}
    {/* 1.9 costing, reports, freight bills, reminders */}
    {costingRoutes}
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
