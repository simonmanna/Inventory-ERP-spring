import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { FuelLogsPage } from './FuelLogsPage';
import { FuelEntryPage } from './FuelEntryPage';
import { FuelLogDetailPage } from './FuelLogDetailPage';
import { FuelAnomaliesPage } from './FuelAnomaliesPage';
import { FuelCardsPage } from './FuelCardsPage';
import { FuelTanksPage } from './FuelTanksPage';
import { FuelTankDetailPage } from './FuelTankDetailPage';
import { FuelSettingsPage } from './FuelSettingsPage';

/** Fleet 1.5 — fuel & energy screens (static segments before `:id`). */
export const fuelRoutes = (
  <>
    <Route path="/fleet/fuel" element={<RequirePermission permission={PERMISSIONS.fleetFuel.read}><FuelLogsPage /></RequirePermission>} />
    <Route path="/fleet/fuel/new" element={<RequirePermission permission={PERMISSIONS.fleetFuel.create}><FuelEntryPage /></RequirePermission>} />
    <Route path="/fleet/fuel/anomalies" element={<RequirePermission permission={PERMISSIONS.fleetFuel.read}><FuelAnomaliesPage /></RequirePermission>} />
    <Route path="/fleet/fuel/cards" element={<RequirePermission permission={PERMISSIONS.fleetFuel.read}><FuelCardsPage /></RequirePermission>} />
    <Route path="/fleet/fuel/tanks" element={<RequirePermission permission={PERMISSIONS.fleetFuel.read}><FuelTanksPage /></RequirePermission>} />
    <Route path="/fleet/fuel/tanks/:id" element={<RequirePermission permission={PERMISSIONS.fleetFuel.read}><FuelTankDetailPage /></RequirePermission>} />
    <Route path="/fleet/fuel/logs/:id" element={<RequirePermission permission={PERMISSIONS.fleetFuel.read}><FuelLogDetailPage /></RequirePermission>} />
    <Route path="/fleet/fuel/logs/:id/edit" element={<RequirePermission permission={PERMISSIONS.fleetFuel.update}><FuelEntryPage /></RequirePermission>} />
    <Route path="/fleet/settings/fuel" element={<RequirePermission permission={PERMISSIONS.fleetFuel.read}><FuelSettingsPage /></RequirePermission>} />
  </>
);
