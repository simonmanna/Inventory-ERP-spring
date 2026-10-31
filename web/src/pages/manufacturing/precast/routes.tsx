import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const MixDesignsPage = lazy(() => import('./MixDesignsPage').then((m) => ({ default: m.MixDesignsPage })));
const MixDesignDetailPage = lazy(() => import('./MixDesignDetailPage').then((m) => ({ default: m.MixDesignDetailPage })));
const PrecastSetupPage = lazy(() => import('./PrecastSetupPage').then((m) => ({ default: m.PrecastSetupPage })));
const PrecastOrdersPage = lazy(() => import('./PrecastOrdersPage').then((m) => ({ default: m.PrecastOrdersPage })));
const PrecastOrderDetailPage = lazy(() => import('./PrecastOrderDetailPage').then((m) => ({ default: m.PrecastOrderDetailPage })));
const CuringBoardPage = lazy(() => import('./CuringBoardPage').then((m) => ({ default: m.CuringBoardPage })));
const CuringBatchDetailPage = lazy(() => import('./CuringBatchDetailPage').then((m) => ({ default: m.CuringBatchDetailPage })));
const PalletLabelsPage = lazy(() => import('./PalletLabelsPage').then((m) => ({ default: m.PalletLabelsPage })));
const PrecastReportsPage = lazy(() => import('./PrecastReportsPage').then((m) => ({ default: m.PrecastReportsPage })));

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
