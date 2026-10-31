import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

import { fuelRoutes } from './fuel/fuel-routes';
import { dispatchRoutes } from './dispatch/dispatch-routes';
import { weighbridgeRoutes } from './weighbridge/weighbridge-routes';
import { costingRoutes } from './costing/costing-routes';

const FleetDashboardPage = lazy(() => import('./FleetDashboardPage').then((m) => ({ default: m.FleetDashboardPage })));
const VehiclesPage = lazy(() => import('./VehiclesPage').then((m) => ({ default: m.VehiclesPage })));
const VehicleFormPage = lazy(() => import('./VehicleFormPage').then((m) => ({ default: m.VehicleFormPage })));
const VehicleDetailPage = lazy(() => import('./VehicleDetailPage').then((m) => ({ default: m.VehicleDetailPage })));
const FleetCompliancePage = lazy(() => import('./FleetCompliancePage').then((m) => ({ default: m.FleetCompliancePage })));
const FleetCategoriesPage = lazy(() => import('./FleetCategoriesPage').then((m) => ({ default: m.FleetCategoriesPage })));
const DriversPage = lazy(() => import('./DriversPage').then((m) => ({ default: m.DriversPage })));
const DriverFormPage = lazy(() => import('./DriverFormPage').then((m) => ({ default: m.DriverFormPage })));
const DriverDetailPage = lazy(() => import('./DriverDetailPage').then((m) => ({ default: m.DriverDetailPage })));
const CheckoutPage = lazy(() => import('./CheckoutPage').then((m) => ({ default: m.CheckoutPage })));
const AllowanceRulesPage = lazy(() => import('./AllowanceRulesPage').then((m) => ({ default: m.AllowanceRulesPage })));
const ClaimsPage = lazy(() => import('./ClaimsPage').then((m) => ({ default: m.ClaimsPage })));
const ClaimFormPage = lazy(() => import('./ClaimFormPage').then((m) => ({ default: m.ClaimFormPage })));
const ClaimDetailPage = lazy(() => import('./ClaimDetailPage').then((m) => ({ default: m.ClaimDetailPage })));
const MaintenanceDuePage = lazy(() => import('./maintenance/MaintenanceDuePage').then((m) => ({ default: m.MaintenanceDuePage })));
const WorkOrdersPage = lazy(() => import('./maintenance/WorkOrdersPage').then((m) => ({ default: m.WorkOrdersPage })));
const WorkOrderDetailPage = lazy(() => import('./maintenance/WorkOrderDetailPage').then((m) => ({ default: m.WorkOrderDetailPage })));
const ServiceTemplatesPage = lazy(() => import('./maintenance/ServiceTemplatesPage').then((m) => ({ default: m.ServiceTemplatesPage })));
const DowntimePage = lazy(() => import('./maintenance/DowntimePage').then((m) => ({ default: m.DowntimePage })));
const ReliabilityPage = lazy(() => import('./maintenance/ReliabilityPage').then((m) => ({ default: m.ReliabilityPage })));
const ChecklistTemplatesPage = lazy(() => import('./maintenance/ChecklistTemplatesPage').then((m) => ({ default: m.ChecklistTemplatesPage })));
const InspectionsPage = lazy(() => import('./maintenance/InspectionsPage').then((m) => ({ default: m.InspectionsPage })));
const InspectionDetailPage = lazy(() => import('./maintenance/InspectionsPage').then((m) => ({ default: m.InspectionDetailPage })));
const InspectionEntryPage = lazy(() => import('./maintenance/InspectionEntryPage').then((m) => ({ default: m.InspectionEntryPage })));
const DefectsPage = lazy(() => import('./maintenance/DefectsPage').then((m) => ({ default: m.DefectsPage })));
const PlantRegisterPage = lazy(() => import('./maintenance/PlantRegisterPage').then((m) => ({ default: m.PlantRegisterPage })));
const PlantCostsPage = lazy(() => import('./maintenance/PlantCostsPage').then((m) => ({ default: m.PlantCostsPage })));
const TyresPage = lazy(() => import('./TyresPage').then((m) => ({ default: m.TyresPage })));
const TyreDetailPage = lazy(() => import('./TyreDetailPage').then((m) => ({ default: m.TyreDetailPage })));
const IncidentsPage = lazy(() => import('./IncidentsPage').then((m) => ({ default: m.IncidentsPage })));
const IncidentFormPage = lazy(() => import('./IncidentFormPage').then((m) => ({ default: m.IncidentFormPage })));
const IncidentDetailPage = lazy(() => import('./IncidentDetailPage').then((m) => ({ default: m.IncidentDetailPage })));
const FinesPage = lazy(() => import('./FinesPage').then((m) => ({ default: m.FinesPage })));
const FineDetailPage = lazy(() => import('./FineDetailPage').then((m) => ({ default: m.FineDetailPage })));
const TelematicsPage = lazy(() => import('./TelematicsPage').then((m) => ({ default: m.TelematicsPage })));
const TelematicsLivePage = lazy(() => import('./TelematicsLivePage').then((m) => ({ default: m.TelematicsLivePage })));

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
    {/* CMMS-1 fixed plant */}
    <Route path="/fleet/plant" element={<RequirePermission permission={PERMISSIONS.fleetVehicle.read}><PlantRegisterPage /></RequirePermission>} />
    <Route path="/fleet/plant/costs" element={<RequirePermission permission={PERMISSIONS.fleet.report}><PlantCostsPage /></RequirePermission>} />
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
