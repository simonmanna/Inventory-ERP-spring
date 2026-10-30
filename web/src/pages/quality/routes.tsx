import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { QualityOverviewPage } from './QualityOverviewPage';
import { SpecificationsPage } from './SpecificationsPage';
import { SpecificationDetailPage } from './SpecificationDetailPage';
import { InspectionPlansPage } from './InspectionPlansPage';
import { InspectionLotsPage } from './InspectionLotsPage';
import { InspectionLotDetailPage } from './InspectionLotDetailPage';
import { NcrsPage } from './NcrsPage';
import { NcrDetailPage } from './NcrDetailPage';
import { CapasPage } from './CapasPage';
import { CapaDetailPage } from './CapaDetailPage';
import { LabImportsPage } from './LabImportsPage';
import { TraceabilityPage } from './TraceabilityPage';
import { QualityReportsPage } from './QualityReportsPage';

/** Quality management screens (ADR-S013); core-available, gated by quality:* permissions. Static segments before :id. */
const read = PERMISSIONS.quality.read;
export const qualityRoutes = (
  <>
    <Route path="/quality" element={<RequirePermission permission={read}><QualityOverviewPage /></RequirePermission>} />
    <Route path="/quality/specifications" element={<RequirePermission permission={read}><SpecificationsPage /></RequirePermission>} />
    <Route path="/quality/specifications/:id" element={<RequirePermission permission={read}><SpecificationDetailPage /></RequirePermission>} />
    <Route path="/quality/plans" element={<RequirePermission permission={read}><InspectionPlansPage /></RequirePermission>} />
    <Route path="/quality/lots" element={<RequirePermission permission={read}><InspectionLotsPage /></RequirePermission>} />
    <Route path="/quality/lots/:id" element={<RequirePermission permission={read}><InspectionLotDetailPage /></RequirePermission>} />
    <Route path="/quality/ncrs" element={<RequirePermission permission={read}><NcrsPage /></RequirePermission>} />
    <Route path="/quality/ncrs/:id" element={<RequirePermission permission={read}><NcrDetailPage /></RequirePermission>} />
    <Route path="/quality/capas" element={<RequirePermission permission={read}><CapasPage /></RequirePermission>} />
    <Route path="/quality/capas/:id" element={<RequirePermission permission={read}><CapaDetailPage /></RequirePermission>} />
    <Route path="/quality/lab" element={<RequirePermission permission={read}><LabImportsPage /></RequirePermission>} />
    <Route path="/quality/traceability" element={<RequirePermission permission={read}><TraceabilityPage /></RequirePermission>} />
    <Route path="/quality/reports" element={<RequirePermission permission={read}><QualityReportsPage /></RequirePermission>} />
  </>
);
