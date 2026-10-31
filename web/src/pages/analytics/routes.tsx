import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { AnalyticsDomainPage } from './AnalyticsDomainPage';
import { AnalyticsOverviewPage } from './AnalyticsOverviewPage';

const P = PERMISSIONS.analytics;
const DASHBOARDS: Array<[string, string]> = [
  ['quarry', P.quarry], ['aggregates', P.aggregates], ['blocks', P.blocks], ['inventory', P.inventory],
  ['procurement', P.procurement], ['sales', P.sales], ['fleet', P.fleet], ['maintenance', P.maintenance],
  ['finance', P.finance], ['branches', P.branch],
];

/** Management dashboards (ADR-S020): the overview needs analytics:view, each dashboard its own permission too. */
export const analyticsRoutes = (
  <>
    <Route path="/analytics" element={<RequirePermission permission={P.view}><AnalyticsOverviewPage /></RequirePermission>} />
    {DASHBOARDS.map(([path, permission]) => (
      <Route key={path} path={`/analytics/${path}`}
        element={<RequirePermission permission={permission}><AnalyticsDomainPage path={path} /></RequirePermission>} />
    ))}
  </>
);
