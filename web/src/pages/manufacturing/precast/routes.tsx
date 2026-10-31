import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { MixDesignsPage } from './MixDesignsPage';
import { MixDesignDetailPage } from './MixDesignDetailPage';
import { PrecastSetupPage } from './PrecastSetupPage';
import { PrecastOrdersPage } from './PrecastOrdersPage';
import { PrecastOrderDetailPage } from './PrecastOrderDetailPage';
import { CuringBoardPage } from './CuringBoardPage';
import { CuringBatchDetailPage } from './CuringBatchDetailPage';
import { PalletLabelsPage } from './PalletLabelsPage';
import { PrecastReportsPage } from './PrecastReportsPage';

/** Concrete block / precast manufacturing screens (2.4). Static segments before :id. */
const bomRead = PERMISSIONS.bom.read;
const orderRead = PERMISSIONS.productionOrder.read;
export const precastRoutes = (
  <>
    <Route path="/manufacturing/precast/mix-designs" element={<RequirePermission permission={bomRead}><MixDesignsPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/mix-designs/:id" element={<RequirePermission permission={bomRead}><MixDesignDetailPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/setup" element={<RequirePermission permission={bomRead}><PrecastSetupPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/orders" element={<RequirePermission permission={orderRead}><PrecastOrdersPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/orders/:id" element={<RequirePermission permission={orderRead}><PrecastOrderDetailPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/curing" element={<RequirePermission permission={orderRead}><CuringBoardPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/curing/:id" element={<RequirePermission permission={orderRead}><CuringBatchDetailPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/curing/:id/labels" element={<RequirePermission permission={orderRead}><PalletLabelsPage /></RequirePermission>} />
    <Route path="/manufacturing/precast/reports" element={<RequirePermission permission={PERMISSIONS.production.report}><PrecastReportsPage /></RequirePermission>} />
  </>
);
