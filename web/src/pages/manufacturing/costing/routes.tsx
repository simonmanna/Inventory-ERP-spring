import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { CostingPage } from './CostingPage';
import { OrderCostPage } from './OrderCostPage';

/** Manufacturing costing & analytics screens (ADR-S018, manufacturing 2.8). Static segments before :id. */
export const mfgCostingRoutes = (
  <>
    <Route path="/manufacturing/costing" element={<RequirePermission permission={PERMISSIONS.production.report}><CostingPage /></RequirePermission>} />
    <Route path="/manufacturing/costing/orders/:id" element={<RequirePermission permission={PERMISSIONS.productionOrder.read}><OrderCostPage /></RequirePermission>} />
  </>
);
